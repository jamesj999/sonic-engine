# Runtime Framework Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the runtime-framework uplift by removing noisy S3K runtime/test signals, completing the remaining S3K CNZ migration onto runtime-owned state, closing lifecycle/leakage gaps, and validating/documenting the branch cleanly.

**Architecture:** The branch already centralizes shared behavior in `GameRuntime` via `ZoneRuntimeRegistry`, `PaletteOwnershipRegistry`, `AnimatedTileChannelGraph`, `ZoneLayoutMutationPipeline`, `SpecialRenderEffectRegistry`, `AdvancedRenderModeController`, and `ScrollEffectComposer`. The remaining work should tighten the places where S3K still depends on legacy event-manager coupling or emits misleading warnings, then add lifecycle coverage and explicit migration-status documentation so the branch can merge as one coherent architectural slice.

**Tech Stack:** Java 17, Maven, JUnit 5, Mockito, OpenGGF runtime/service architecture

---

### Task 1: Quiet S3K Intro-Art and Runtime-Registration Noise

**Files:**
- Create: `src/test/java/com/openggf/tests/LogCaptureHandler.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizIntroArtLoader.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
- Test: `src/test/java/com/openggf/game/sonic3k/events/TestSonic3kAIZEvents.java`
- Test: `src/test/java/com/openggf/tests/TestS3kZoneRuntimeRegistrationHeadless.java`
- Test: `src/test/java/com/openggf/game/sonic3k/scroll/SwScrlAizTest.java`

- [ ] **Step 1: Write the failing log-capture helper and test assertions**

```java
package com.openggf.tests;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public final class LogCaptureHandler extends Handler {
    private final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
        if (record != null) {
            records.add(record);
        }
    }

    @Override public void flush() { }
    @Override public void close() { }

    public long countAtOrAbove(Level level) {
        return records.stream()
                .filter(r -> r.getLevel().intValue() >= level.intValue())
                .count();
    }
}
```

```java
@Test
void introArtFallbackDoesNotLogSevereWhenRomBackedAssetsAreUnavailable() {
    Logger logger = Logger.getLogger(AizIntroArtLoader.class.getName());
    LogCaptureHandler handler = new LogCaptureHandler();
    logger.addHandler(handler);
    logger.setUseParentHandlers(false);
    try {
        AizIntroArtLoader.reset();
        AizIntroArtLoader.loadAllIntroArt(new BootstrapObjectServices());
        assertEquals(0, handler.countAtOrAbove(Level.SEVERE));
    } finally {
        logger.removeHandler(handler);
    }
}
```

- [ ] **Step 2: Run the focused tests to verify they fail with the current noisy behavior**

Run:

```bash
mvn -q -Dmse=off "-Dtest=TestSonic3kAIZEvents,TestS3kZoneRuntimeRegistrationHeadless,SwScrlAizTest" test
```

Expected: FAIL or emit captured `SEVERE` / `WARNING` records from `AizIntroArtLoader` and `Sonic3kLevelEventManager`.

- [ ] **Step 3: Implement quiet expected-path fallbacks**

```java
private static Rom tryRom() {
    try {
        return activeServices != null ? activeServices.rom() : null;
    } catch (IOException ex) {
        return null;
    }
}

public static void loadPlaneArt() {
    if (planePatterns != null) return;
    Rom rom = tryRom();
    if (rom == null) {
        LOG.fine("Skipping AIZ intro plane art load because no ROM-backed ObjectServices are active");
        planePatterns = new Pattern[0];
        return;
    }
    byte[] data = decompressKosinskiModuled(rom, Sonic3kConstants.ART_KOSM_AIZ_INTRO_PLANE_ADDR);
    planePatterns = bytesToPatterns(data);
}
```

```java
private void installZoneRuntimeState(int zone, int act) {
    GameRuntime runtime = RuntimeManager.getActiveRuntime();
    if (runtime == null) {
        LOG.fine("Skipping S3K zone runtime registration because no active runtime is installed");
        return;
    }
    // existing registration continues here
}
```

- [ ] **Step 4: Re-run the focused tests and verify they pass without severe/warning noise**

Run:

```bash
mvn -q -Dmse=off "-Dtest=TestSonic3kAIZEvents,TestS3kZoneRuntimeRegistrationHeadless,SwScrlAizTest" test
rg -n "SEVERE|WARNING|NullPointerException|<<< FAILURE!" target/surefire-reports
```

Expected: test suite passes; grep returns no new matches from the targeted reports.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/AizIntroArtLoader.java src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java src/test/java/com/openggf/tests/LogCaptureHandler.java src/test/java/com/openggf/game/sonic3k/events/TestSonic3kAIZEvents.java src/test/java/com/openggf/tests/TestS3kZoneRuntimeRegistrationHeadless.java src/test/java/com/openggf/game/sonic3k/scroll/SwScrlAizTest.java
git commit -m "test(s3k): remove noisy intro-art and runtime registration logs"
```

### Task 2: Complete S3K CNZ Runtime-State Migration

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/runtime/CnzZoneRuntimeState.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/scroll/SwScrlCnz.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzRuntimeStateRegistration.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzScroll.java`

- [ ] **Step 1: Write the failing CNZ runtime-state registration and consumer tests**

```java
class TestSonic3kCnzRuntimeStateRegistration {
    @Test
    void initLevelInstallsCnzRuntimeState() {
        RuntimeManager.createGameplay();
        Sonic3kLevelEventManager manager = new Sonic3kLevelEventManager();
        manager.initLevel(Sonic3kZoneIds.ZONE_CNZ, 0);

        assertTrue(GameServices.zoneRuntimeRegistry()
                .currentAs(CnzZoneRuntimeState.class)
                .isPresent());
    }
}
```

```java
@Test
void bossScrollReadsBossModeFromZoneRuntimeRegistry() {
    ZoneRuntimeRegistry registry = GameServices.zoneRuntimeRegistry();
    registry.install(new CnzZoneRuntimeState(0, cnzEvents));
    // exercise SwScrlCnz.update(...)
    assertEquals(expectedPackedScroll, horizScrollBuf[0]);
}
```

- [ ] **Step 2: Run the CNZ-focused tests to verify they fail before the migration**

Run:

```bash
mvn -q -Dmse=off "-Dtest=TestSonic3kCnzRuntimeStateRegistration,TestSonic3kCnzScroll" test
```

Expected: FAIL because `CnzZoneRuntimeState` does not exist yet and `SwScrlCnz` still reaches into `Sonic3kLevelEventManager`.

- [ ] **Step 3: Implement the runtime state and swap the consumer**

```java
public final class CnzZoneRuntimeState implements S3kZoneRuntimeState {
    private final int actIndex;
    private final Sonic3kCNZEvents events;

    public CnzZoneRuntimeState(int actIndex, Sonic3kCNZEvents events) {
        this.actIndex = actIndex;
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override public int zoneIndex() { return Sonic3kZoneIds.ZONE_CNZ; }
    @Override public int actIndex() { return actIndex; }
    @Override public int getDynamicResizeRoutine() { return events.getDynamicResizeRoutine(); }
    @Override public boolean isActTransitionFlagActive() { return events.isEventsFg5(); }

    public Sonic3kCNZEvents.BossBackgroundMode bossBackgroundMode() {
        return events.getBossBackgroundMode();
    }
}
```

```java
if (zone == Sonic3kZoneIds.ZONE_CNZ && cnzEvents != null) {
    registry.install(new CnzZoneRuntimeState(act, cnzEvents));
} else if (zone == Sonic3kZoneIds.ZONE_AIZ && aizEvents != null) {
    registry.install(new AizZoneRuntimeState(act, aizEvents));
}
```

```java
private CnzZoneRuntimeState cnzRuntimeState() {
    return GameServices.zoneRuntimeRegistry()
            .currentAs(CnzZoneRuntimeState.class)
            .orElseThrow(() -> new IllegalStateException("CNZ runtime state not installed"));
}
```

- [ ] **Step 4: Re-run the CNZ suite and the S3K render-registration tests**

Run:

```bash
mvn -q -Dmse=off "-Dtest=TestSonic3kCnzRuntimeStateRegistration,TestSonic3kCnzScroll,TestS3kAdvancedRenderModeRegistration,TestS3kSpecialRenderEffectRegistration" test
```

Expected: PASS with `SwScrlCnz` reading only from the typed runtime state.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/runtime/CnzZoneRuntimeState.java src/main/java/com/openggf/game/sonic3k/Sonic3kLevelEventManager.java src/main/java/com/openggf/game/sonic3k/scroll/SwScrlCnz.java src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzRuntimeStateRegistration.java src/test/java/com/openggf/game/sonic3k/TestSonic3kCnzScroll.java
git commit -m "refactor(s3k): move cnz scroll state onto zone runtime registry"
```

### Task 3: Add Runtime-Owned Registry Lifecycle and Leakage Coverage

**Files:**
- Create: `src/test/java/com/openggf/game/TestRuntimeOwnedRegistryLifecycle.java`
- Modify: `src/test/java/com/openggf/game/TestGameRuntime.java`
- Modify: `src/test/java/com/openggf/tests/TestLevelManager.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestS3kAizMutationPipeline.java`

- [ ] **Step 1: Write the failing lifecycle tests**

```java
class TestRuntimeOwnedRegistryLifecycle {
    @Test
    void parkedRuntimeClearsTransientMutationAndRenderState() {
        GameRuntime runtime = RuntimeManager.createGameplay();
        runtime.getZoneLayoutMutationPipeline().queue(ctx -> MutationEffects.redrawAllTilemaps());
        runtime.getSpecialRenderEffectRegistry().register(context -> { });
        runtime.getAdvancedRenderModeController().register((context, builder) -> builder.enablePerLineForegroundScroll());

        RuntimeManager.parkCurrent();
        RuntimeManager.resumeOrCreate(new GameplayModeContext(runtime.getWorldSession()));

        assertTrue(runtime.getZoneLayoutMutationPipeline().isEmpty());
        assertTrue(runtime.getSpecialRenderEffectRegistry().isEmpty());
        assertTrue(runtime.getAdvancedRenderModeController().isEmpty());
    }
}
```

```java
@Test
void initZoneFeaturesClearsRenderRegistriesBetweenZones() {
    LevelManager levelManager = GameServices.level();
    GameServices.specialRenderEffectRegistry().register(context -> { });
    GameServices.advancedRenderModeController().register((context, builder) -> builder.enableForegroundHeatHaze());

    levelManager.initZoneFeatures();

    assertEquals(0, GameServices.specialRenderEffectRegistry().size(SpecialRenderEffectStage.AFTER_BACKGROUND));
    assertTrue(GameServices.advancedRenderModeController().isEmpty());
}
```

- [ ] **Step 2: Run the lifecycle suite and confirm the expected failures**

Run:

```bash
mvn -q -Dmse=off "-Dtest=TestGameRuntime,TestRuntimeOwnedRegistryLifecycle,TestLevelManager,TestS3kAizMutationPipeline" test
```

Expected: FAIL because the new test class and queue-emptiness helpers are not present yet.

- [ ] **Step 3: Implement the minimal helpers and assertions**

```java
public final class ZoneLayoutMutationPipeline {
    // existing fields

    public boolean isEmpty() {
        return queued.isEmpty();
    }
}
```

```java
public void clearTransientFrameState() {
    zoneLayoutMutationPipeline.clear();
}
```

```java
specialRenderEffectRegistry.clear();
advancedRenderModeController.clear();
```

- [ ] **Step 4: Re-run the lifecycle suite plus targeted Sonic 2 consumers**

Run:

```bash
mvn -q -Dmse=off "-Dtest=TestGameRuntime,TestRuntimeOwnedRegistryLifecycle,TestLevelManager,TestSonic2CnzMutationPipeline,TestSonic2SpecialRenderEffectRegistration,TestS3kAizMutationPipeline" test
```

Expected: PASS; registries clear on runtime lifecycle boundaries and zone re-init.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/mutation/ZoneLayoutMutationPipeline.java src/test/java/com/openggf/game/TestRuntimeOwnedRegistryLifecycle.java src/test/java/com/openggf/game/TestGameRuntime.java src/test/java/com/openggf/tests/TestLevelManager.java src/test/java/com/openggf/game/sonic3k/TestS3kAizMutationPipeline.java
git commit -m "test(runtime): cover registry lifecycle and leakage boundaries"
```

### Task 4: Run the Clean Verification Sweep

**Files:**
- Modify: `target/surefire-reports/*` (generated only; do not commit)

- [ ] **Step 1: Start from a clean test output directory**

Run:

```bash
mvn -Dmse=off clean
```

Expected: `BUILD SUCCESS`; stale `surefire-reports` from earlier runs are removed.

- [ ] **Step 2: Run the framework-core and Sonic 2 uplift verification**

Run:

```bash
mvn -q -Dmse=off "-Dtest=TestGameRuntime,TestGameServicesNullableAccessors,TestPaletteOwnershipRegistry,TestAnimatedTileChannelGraph,TestZoneLayoutMutationPipeline,TestAdvancedRenderModeController,TestSpecialRenderEffectRegistry,TestLevelManager,TestScrollEffectComposer,TestSonic2PaletteOwnershipIntegration,TestSonic2PatternAnimatorGraphAdapter,TestSonic2SpecialRenderEffectRegistration,TestSonic2CnzMutationPipeline,TestSonic2CnzRuntimeStateRegistration,TestSonic2CnzRuntimeStateConsumers,TestSonic2HtzRuntimeStateRegistration,TestSonic2HtzRuntimeStateConsumers,TestSonic2WaterSurfaceManager,TestScriptFramesApplyStrategyGraphicsRebind,TestHTZRisingLavaDisassemblyParity,TestS2Htz1Headless" test
```

Expected: PASS with zero failures and zero unexpected warnings.

- [ ] **Step 3: Run the S3K verification sweep**

Run:

```bash
mvn -q -Dmse=off "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestSonic3kAIZEvents,TestS3kZoneRuntimeRegistrationHeadless,TestS3kPaletteOwnershipRegistryIntegration,TestS3kHczPatternAnimation,TestS3kSozPatternAnimation,SwScrlAizTest,SwScrlHczTest,SwScrlMgzTest,TestS3kAizMutationPipeline,TestSonic3kCnzRuntimeStateRegistration,TestSonic3kCnzScroll,TestS3kAdvancedRenderModeRegistration,TestS3kSpecialRenderEffectRegistration" test
```

Expected: PASS with no `SEVERE`, `NullPointerException`, or unexpected runtime-registration warnings in the generated reports.

- [ ] **Step 4: Verify the report output is clean**

Run:

```bash
rg -n "SEVERE|NullPointerException|<<< FAILURE!|Errors: [1-9]" target/surefire-reports
```

Expected: no matches, or only known benign non-targeted reports outside this sweep.

- [ ] **Step 5: Commit the code only if the verification sweep passed**

```bash
git status --short
git commit --allow-empty -m "chore: record passing runtime framework verification sweep"
```

### Task 5: Update Architecture and Merge-Readiness Documentation

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Modify: `docs/guide/contributing/architecture.md`

- [ ] **Step 1: Add a short migration-status section to the public docs**

```markdown
### Runtime Framework Migration Status

- Landed on the runtime-owned stack: Sonic 2 palette cycling, Sonic 2 animated tiles, Sonic 2 HTZ/CNZ runtime state, Sonic 2 CNZ staged render effects, S3K HCZ/SOZ animated tiles, S3K AIZ staged render effects and advanced render modes, S3K CNZ runtime state.
- Infrastructure ready but no zone consumers yet: `ZoneLayoutMutationPipeline` (boss arena layout edits and act-transition tile swaps are natural first adopters).
- Not yet fully migrated: remaining Sonic 1 retrofit work, additional S3K zones beyond the current adopters, and any legacy zone-local booleans/buffers not yet routed through `GameRuntime`.
```

- [ ] **Step 2: Reflect the same status in contributor-facing docs**

```markdown
When extending zone behavior, prefer `GameRuntime` registries first. If a subsystem still uses a zone-local event manager directly, treat that as migration debt and document whether this branch resolves it or leaves it intentionally for follow-up.
```

- [ ] **Step 3: Verify the docs reference the finished scope rather than over-claiming**

Run:

```bash
rg -n "AnimatedTileChannelGraph|ZoneLayoutMutationPipeline|SpecialRenderEffectRegistry|AdvancedRenderModeController|Migration Status" README.md AGENTS.md CLAUDE.md docs/guide/contributing/architecture.md
```

Expected: each document mentions the runtime-owned stack, and at least one document explicitly calls out partial migration status.

- [ ] **Step 4: Review branch hygiene before handoff**

Run:

```bash
git status --short
```

Expected: only branch-relevant code/tests/docs remain. Leave `docs/s1disasm` and `docs/s2disasm` out of the final commit unless they are intentionally updated in a separate changeset.

- [ ] **Step 5: Commit**

```bash
git add README.md AGENTS.md CLAUDE.md docs/guide/contributing/architecture.md
git commit -m "docs: document runtime framework migration status"
```
