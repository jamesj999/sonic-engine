# Rewind Framework — Prerequisites Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the four day-1 prerequisites called out in the rewind framework spec so the framework PR has a clean foundation: pipeline migration of 7 mutation stragglers, an exposed per-frame divergence vector on `LiveTraceComparator`, an RNG CI guard, and snapshot helpers on `OscillationManager`.

**Architecture:** Three independent tracks. Track A migrates direct `Map.setValue(...)` callers onto `ZoneLayoutMutationPipeline` (one per straggler). Track B extends `LiveTraceComparator` with a per-frame `FrameComparison` callback so two engine runs can be compared field-for-field. Track C adds a source-scan test that fails on `java.util.Random`/`ThreadLocalRandom`/`Math.random()` in gameplay packages. Track D adds `snapshot()/restore(...)` static helpers and a snapshot record to `OscillationManager`.

**Tech Stack:** Java 21, Maven 3.x, JUnit 5 (Jupiter only — no JUnit 4), existing project conventions in CLAUDE.md.

**Tracks are independent.** Tasks within a track must run in order. Tracks themselves can run in any order.

**Spec reference:** `docs/architecture/designs/2026-05-04-rewind-framework-design.md` — section "Hard prerequisites for day-1 ship".

---

## Track A — Pipeline migration (7 stragglers)

7 gameplay-path callsites currently bypass `ZoneLayoutMutationPipeline` and write directly via `map.setValue(...)`. Migrate each to the pipeline so a future copy-on-write snapshot in `DirectLevelMutationSurface` can intercept all gameplay mutations.

**Pattern reference:** `Sonic2CNZEvents.java:213-221` (read it). The migration is:

```java
// Before:
Map map = level.getMap();
map.setValue(0, x, y, (byte) blockId);
lm.invalidateForegroundTilemap();   // sometimes present

// After:
mutationPipeline().queue(context -> {
    try {
        return context.surface().setBlockInMap(0, x, y, blockId);
    } catch (IllegalArgumentException e) {
        return MutationEffects.NONE;
    }
});
```

The pipeline's `queue(...)`/`flush(...)` route through `LevelManager` later in the frame; the `setBlockInMap` returns `MutationEffects` that include the redraw signal, so the standalone `lm.invalidateForegroundTilemap()` is no longer needed.

**Pipeline access path:** Each migrating class must obtain the pipeline. Zone events (`Sonic1LZEvents`, `Sonic1LZWaterEvents`, `Sonic2CNZEvents`, `Sonic3kMGZEvents`) extend `AbstractLevelEventManager`, which exposes `mutationPipeline()`. Object instances (`Sonic1EndingSonicObjectInstance`, `RivetObjectInstance`, `TornadoObjectInstance`) extend `AbstractObjectInstance`, which exposes `services()`; resolve via `services().mutationPipeline()` if the accessor exists, otherwise `services().gameplayMode().getZoneLayoutMutationPipeline()`. Static helpers like `S3kSeamlessMutationExecutor` take a `LevelManager`/context as a parameter today; thread the pipeline through the same parameter list.

> If `services().mutationPipeline()` does not exist on `ObjectServices`, **add it** before doing the object-instance migrations (Tasks A.3, A.4, A.5). One-line accessor on `ObjectServices` interface + `DefaultObjectServices` impl returning `GameplayModeContext.getZoneLayoutMutationPipeline()`. This is a 5-minute prerequisite, not its own task.

---

### Task A.1: Migrate `Sonic1LZEvents` LZ3 layout gap chunk swap

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/events/Sonic1LZEvents.java:90-116`
- Test: `src/test/java/com/openggf/game/sonic1/events/TestSonic1LZEventsLayoutGap.java` (create)

- [ ] **Step 1: Write the failing test**

Pattern: load LZ3 in headless, advance to the layout-gap trigger position, assert that after the trigger the `Map` block at `(LAYOUT_GAP_X, LAYOUT_GAP_Y)` on layer 0 equals `CHUNK_ID_GAP`. The test must cover both the old direct-write path (currently passing) and the new pipeline path (will pass after migration). Use `HeadlessTestRunner` with `@FullReset`.

```java
package com.openggf.game.sonic1.events;

import com.openggf.game.sonic1.Sonic1Constants;
import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.FullReset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestSonic1LZEventsLayoutGap {

    @Test
    void layoutGapChunkSwapAppliesViaPipeline() {
        // Reuse existing LZ3 fixture if present; otherwise load by zone/act.
        HeadlessTestRunner runner = HeadlessTestRunner.forZoneAct(
                /* game= */ "s1", /* zone= */ 11 /* LZ */, /* act= */ 2 /* LZ3 */);
        runner.teleportPlayerTo(Sonic1LZEvents.LAYOUT_GAP_TRIGGER_X,
                                Sonic1LZEvents.LAYOUT_GAP_TRIGGER_Y);
        runner.stepIdleFrames(10);  // let event manager run

        var level = runner.gameplay().getLevelManager().getCurrentLevel();
        int chunk = level.getMap().getValue(0,
                Sonic1LZEvents.LAYOUT_GAP_X,
                Sonic1LZEvents.LAYOUT_GAP_Y) & 0xFF;
        assertEquals(Sonic1LZEvents.CHUNK_ID_GAP, chunk,
                "LZ3 layout-gap chunk should be set after trigger");
    }
}
```

If `LAYOUT_GAP_X` etc. are private, promote them to package-private (`int` not `private int`) so the test can reference them. Don't add public accessors.

- [ ] **Step 2: Run the test to confirm it currently passes**

The pre-migration code already produces the same end-state, so this test should pass against today's code. That's by design — the test exists to prove behavioural equivalence after migration.

```
mvn test "-Dtest=TestSonic1LZEventsLayoutGap" -q
```

Expected: PASS.

- [ ] **Step 3: Migrate the direct mutation onto the pipeline**

Replace lines 110-112 (the `map.setValue(...)` + `lm.invalidateForegroundTilemap()` call) with a pipeline queue:

```java
        mutationPipeline().queue(context -> {
            try {
                return context.surface().setBlockInMap(0,
                        LAYOUT_GAP_X, LAYOUT_GAP_Y, CHUNK_ID_GAP);
            } catch (IllegalArgumentException e) {
                return com.openggf.game.mutation.MutationEffects.NONE;
            }
        });
```

The `Map map = level.getMap()` lookup at line 98-101 is now only used for the read at line 105 (early-out check). Keep that read; remove the unused write path. Drop the `Map` import if it becomes unused.

- [ ] **Step 4: Run the test to confirm migration preserves behaviour**

```
mvn test "-Dtest=TestSonic1LZEventsLayoutGap" -q
```

Expected: PASS.

- [ ] **Step 5: Run any broader LZ-related tests to catch regressions**

```
mvn test "-Dtest=*LZ*" -q
```

Expected: all LZ-related tests pass.

- [ ] **Step 6: Commit**

```
git add src/main/java/com/openggf/game/sonic1/events/Sonic1LZEvents.java \
        src/test/java/com/openggf/game/sonic1/events/TestSonic1LZEventsLayoutGap.java
git commit -m "$(cat <<'EOF'
refactor(s1): route LZ3 layout-gap chunk swap through ZoneLayoutMutationPipeline

Direct map.setValue + invalidateForegroundTilemap replaced with a
pipeline.queue() call. Equivalent behaviour; new test asserts the
post-trigger chunk state to lock in the migration. Prerequisite for
the rewind framework's level-state copy-on-write.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task A.2: Migrate `Sonic1LZWaterEvents` LZ3 water-slide chunk write

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/events/Sonic1LZWaterEvents.java:1183` (and surrounding context — read ±20 lines first)
- Test: `src/test/java/com/openggf/game/sonic1/events/TestSonic1LZWaterEventsSlide.java` (create)

- [ ] **Step 1: Read the callsite and identify the trigger**

Inspect `Sonic1LZWaterEvents.java:1170-1200` to understand what condition fires the `map.setValue` at :1183. Capture the trigger condition, the X/Y coordinates, and the chunk byte for the test.

- [ ] **Step 2: Write the failing test**

Same shape as Task A.1 — load LZ3, advance to trigger, assert chunk byte. Adjust class name and constants to match what the callsite uses.

```java
package com.openggf.game.sonic1.events;

import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.FullReset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestSonic1LZWaterEventsSlide {

    @Test
    void waterSlideChunkWriteAppliesViaPipeline() {
        // Use the (X, Y, expectedChunk) values you captured from the callsite.
        HeadlessTestRunner runner = HeadlessTestRunner.forZoneAct("s1", 11, 2);
        // teleport / step into the trigger condition you identified
        // ...
        var level = runner.gameplay().getLevelManager().getCurrentLevel();
        int chunk = level.getMap().getValue(0, /* X */, /* Y */) & 0xFF;
        assertEquals(/* expected chunk */, chunk);
    }
}
```

- [ ] **Step 3: Run the test, confirm PASS pre-migration**

```
mvn test "-Dtest=TestSonic1LZWaterEventsSlide" -q
```

- [ ] **Step 4: Migrate the direct write onto the pipeline**

Replace the `map.setValue(...)` (and any companion `invalidateForegroundTilemap()` call) with the same `mutationPipeline().queue(...)` pattern from Task A.1, capturing the X/Y/chunk values into local final variables for use inside the lambda.

- [ ] **Step 5: Run the test, confirm PASS post-migration**

- [ ] **Step 6: Run broader LZ tests**

```
mvn test "-Dtest=*LZ*" -q
```

- [ ] **Step 7: Commit**

```
git add src/main/java/com/openggf/game/sonic1/events/Sonic1LZWaterEvents.java \
        src/test/java/com/openggf/game/sonic1/events/TestSonic1LZWaterEventsSlide.java
git commit -m "$(cat <<'EOF'
refactor(s1): route LZ3 water-slide chunk write through ZoneLayoutMutationPipeline

Replaces direct map.setValue with a pipeline queue call. Behaviour-
equivalent. Prerequisite for level-state copy-on-write.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task A.3: Migrate `Sonic1EndingSonicObjectInstance` ending layout patches

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1EndingSonicObjectInstance.java:340-360` (read ±20 lines first)
- Test: `src/test/java/com/openggf/game/sonic1/objects/TestSonic1EndingSonicLayoutPatch.java` (create)

- [ ] **Step 0 (one-time prerequisite if not already done): Add `mutationPipeline()` accessor to `ObjectServices`**

Inspect `src/main/java/com/openggf/level/objects/ObjectServices.java`. If it does not already expose the pipeline, add:

```java
// In ObjectServices interface:
default ZoneLayoutMutationPipeline mutationPipeline() {
    return null;  // override in DefaultObjectServices
}

// In DefaultObjectServices impl:
@Override
public ZoneLayoutMutationPipeline mutationPipeline() {
    return GameServices.gameplayMode().getZoneLayoutMutationPipeline();
}
```

(Adjust to actual accessor names on `GameServices` / `GameplayModeContext`.) Update `StubObjectServices` test double to also return null or a stub so existing tests still compile.

Skip this step if a `mutationPipeline()` accessor already exists.

- [ ] **Step 1: Write the failing test**

Object-instance behaviour is harder to trigger than a zone event. If a fixture already exercises the ending sequence, use it. Otherwise capture the `map.getValue(...)` at `(0, 0, 1)` and `(0, 1, 1)` after the relevant ending step. Test must assert post-trigger chunk bytes equal `0x2E` and `0x2F` respectively.

- [ ] **Step 2: Run pre-migration; confirm PASS**

- [ ] **Step 3: Migrate**

Replace `map.setValue(0, 0, 1, (byte) 0x2E); map.setValue(0, 1, 1, (byte) 0x2F);` with one queued intent that calls `setBlockInMap` twice (returning the second's `MutationEffects`):

```java
        services().mutationPipeline().queue(context -> {
            try {
                context.surface().setBlockInMap(0, 0, 1, 0x2E);
                return context.surface().setBlockInMap(0, 1, 1, 0x2F);
            } catch (IllegalArgumentException e) {
                return MutationEffects.NONE;
            }
        });
```

- [ ] **Step 4: Run post-migration test; PASS**

- [ ] **Step 5: Commit**

```
git add src/main/java/com/openggf/game/sonic1/objects/Sonic1EndingSonicObjectInstance.java \
        src/test/java/com/openggf/game/sonic1/objects/TestSonic1EndingSonicLayoutPatch.java \
        src/main/java/com/openggf/level/objects/ObjectServices.java \
        src/main/java/com/openggf/level/objects/DefaultObjectServices.java \
        src/test/java/com/openggf/tests/StubObjectServices.java
git commit -m "$(cat <<'EOF'
refactor(s1): route ending-sequence layout patches through pipeline

Adds ObjectServices.mutationPipeline() and migrates the two
map.setValue calls in Sonic1EndingSonicObjectInstance into a single
pipeline.queue. Prerequisite for level-state copy-on-write.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

If the `mutationPipeline()` accessor was already present on `ObjectServices`, drop the `ObjectServices.java`/`DefaultObjectServices.java`/`StubObjectServices.java` from the staged set.

---

### Task A.4: Migrate `RivetObjectInstance` collapse layout

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/RivetObjectInstance.java:225-250` (read ±25 lines first)
- Test: `src/test/java/com/openggf/game/sonic2/objects/TestRivetObjectLayoutCollapse.java` (create)

- [ ] **Step 1: Inspect callsite** — note the loop bounds, layer, `LAYOUT_ROW_0_X/Y`, `LAYOUT_ROW_0_BLOCKS[]`, and the second row at line 238.

- [ ] **Step 2: Write a test that exercises the collapse**

Pattern: spawn a `RivetObjectInstance` in a headless scene, simulate the trigger that fires the collapse, then assert each cell of `LAYOUT_ROW_0_*` and `LAYOUT_ROW_1_*` contains the expected block. Reuse any existing rivet-collapse fixtures if present.

- [ ] **Step 3: Confirm PASS pre-migration**

- [ ] **Step 4: Migrate both `map.setValue` loops into a single pipeline queue**

```java
        services().mutationPipeline().queue(context -> {
            try {
                MutationEffects effects = MutationEffects.NONE;
                for (int i = 0; i < LAYOUT_ROW_0_BLOCKS.length; i++) {
                    effects = context.surface().setBlockInMap(0,
                            LAYOUT_ROW_0_X + i, LAYOUT_ROW_0_Y,
                            LAYOUT_ROW_0_BLOCKS[i] & 0xFF);
                }
                for (int i = 0; i < LAYOUT_ROW_1_BLOCKS.length; i++) {
                    effects = context.surface().setBlockInMap(0,
                            LAYOUT_ROW_1_X + i, LAYOUT_ROW_1_Y,
                            LAYOUT_ROW_1_BLOCKS[i] & 0xFF);
                }
                return effects;
            } catch (IllegalArgumentException e) {
                return MutationEffects.NONE;
            }
        });
```

(Adapt array names if the actual constants are different.)

- [ ] **Step 5: Confirm PASS post-migration**

- [ ] **Step 6: Commit (same trailer block, message specific to rivet)**

---

### Task A.5: Migrate `TornadoObjectInstance` layout patches

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/TornadoObjectInstance.java:1100-1140` (read ±30 lines first)
- Test: `src/test/java/com/openggf/game/sonic2/objects/TestTornadoObjectLayoutPatch.java` (create)

- [ ] **Step 1: Inspect callsite at :1127** — capture the loop variables (`x`, `y`, `bytes[j]`) and the surrounding loop bounds.

- [ ] **Step 2: Write a test**

If a Tornado fixture exists, reuse. Otherwise capture pre/post chunk values at the affected cells and assert post = expected.

- [ ] **Step 3: Confirm PASS pre-migration**

- [ ] **Step 4: Migrate the loop into a single pipeline queue**

```java
        // Capture loop iteration values into final locals for the lambda
        final int[] capturedBytes = bytes.clone();
        final int loopStart = /* matching loop start */;
        final int loopEnd = /* matching loop end */;
        // ... and any X/Y bases needed
        services().mutationPipeline().queue(context -> {
            try {
                MutationEffects effects = MutationEffects.NONE;
                for (int j = loopStart; j < loopEnd; j++) {
                    int xCell = /* recompute x from j */;
                    int yCell = /* recompute y from j */;
                    effects = context.surface().setBlockInMap(0, xCell, yCell,
                            capturedBytes[j] & 0xFF);
                }
                return effects;
            } catch (IllegalArgumentException e) {
                return MutationEffects.NONE;
            }
        });
```

- [ ] **Step 5: Confirm PASS post-migration**

- [ ] **Step 6: Commit**

---

### Task A.6: Migrate `Sonic3kMGZEvents` raw FG clear (sibling of pipelined `:1780`)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java:1770` (and surrounding helper)

The pipelined sibling at `:1780` shows the right pattern. The straggler at `:1770` is a helper called from a related path — port it identically.

- [ ] **Step 1: Read both `:1770` and `:1780` and identify the helper's call signature.**

- [ ] **Step 2: Write a test that triggers the helper.** Reuse `TestSonic3kMGZ*` fixtures if present.

- [ ] **Step 3: Confirm PASS pre-migration.**

- [ ] **Step 4: Migrate the helper to issue `mutationPipeline().queue(context -> context.surface().setBlockInMap(...))`. Match the lambda shape used at `:1780`.**

- [ ] **Step 5: Confirm PASS post-migration.**

- [ ] **Step 6: Commit.**

---

### Task A.7: Migrate `S3kSeamlessMutationExecutor` AIZ seamless terrain swaps

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/mutation/S3kSeamlessMutationExecutor.java:230-260` (read ±30 lines first)
- Test: `src/test/java/com/openggf/game/sonic3k/mutation/TestS3kSeamlessMutationExecutor.java` (create or extend)

- [ ] **Step 1: Inspect both callsites at `:233` and `:247`** — note the layer (likely 0 for FG), the X/Y, and the source block byte.

- [ ] **Step 2: Identify how `S3kSeamlessMutationExecutor` accesses gameplay services.** It is a static helper today; thread the pipeline through the existing parameter list. Likely the executor takes a `LevelManager` or `GameplayModeContext` already; add `ZoneLayoutMutationPipeline pipeline` to the call signature if it doesn't.

- [ ] **Step 3: Write a test** that drives the executor with a stub pipeline and asserts the queued intent applies the expected block-in-map writes.

- [ ] **Step 4: Confirm PASS pre-migration** (test is written against the current behaviour; should pass).

- [ ] **Step 5: Migrate both callsites** to issue `pipeline.queue(context -> context.surface().setBlockInMap(...))` instead of `level.getMap().setValue(...)`. Update all callers to thread the pipeline argument through.

- [ ] **Step 6: Confirm PASS post-migration.**

- [ ] **Step 7: Run AIZ trace replay tests if any** (`mvn test "-Dtest=*Aiz*"` etc.) to confirm seamless mutation path still produces correct visual state.

- [ ] **Step 8: Commit.**

---

### Task A.8: Add audit guard against re-introduction

**Files:**
- Test: `src/test/java/com/openggf/game/mutation/TestNoDirectMapMutationsInGameplay.java` (create)

A source-scan test that fails CI if any new file under `game/sonic*/`, `level/objects/`, or `level/events/` calls `Map.setValue(...)`. Mirrors the `TestObjectServicesMigrationGuard` pattern.

- [ ] **Step 1: Write the test**

```java
package com.openggf.game.mutation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

class TestNoDirectMapMutationsInGameplay {

    private static final Pattern DIRECT_MAP_SETVALUE = Pattern.compile(
            "\\bgetMap\\(\\)\\s*\\.\\s*setValue\\b|\\b\\.setValue\\s*\\(\\s*\\d");

    private static final String[] SCAN_ROOTS = {
            "src/main/java/com/openggf/game/sonic1",
            "src/main/java/com/openggf/game/sonic2",
            "src/main/java/com/openggf/game/sonic3k",
            "src/main/java/com/openggf/level/objects",
    };

    private static final Set<String> ALLOWED = Set.of(
            // Editor commands (not gameplay paths) - exempt
            "PlaceBlockCommand.java",
            "DeriveChunkFromPatternsCommand.java",
            "DeriveBlockFromChunksCommand.java"
    );

    @Test
    void noDirectMapSetValueInGameplay() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String root : SCAN_ROOTS) {
            Path rootPath = Paths.get(root);
            if (!Files.exists(rootPath)) continue;
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ALLOWED.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            // strip comments to avoid false positives
                            String stripped = content
                                    .replaceAll("//.*", "")
                                    .replaceAll("/\\*[\\s\\S]*?\\*/", "");
                            if (DIRECT_MAP_SETVALUE.matcher(stripped).find()
                                    && stripped.contains("getMap().setValue")) {
                                violations.add(p.toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            }
        }
        if (!violations.isEmpty()) {
            fail("Direct Map.setValue() calls found in gameplay paths. "
                    + "All level mutations must route through "
                    + "ZoneLayoutMutationPipeline.\n  "
                    + String.join("\n  ", violations));
        }
    }
}
```

- [ ] **Step 2: Run the test**

```
mvn test "-Dtest=TestNoDirectMapMutationsInGameplay" -q
```

Expected: PASS (all stragglers migrated by Tasks A.1-A.7).

- [ ] **Step 3: Verify guard catches regressions**

Temporarily reintroduce `level.getMap().setValue(0, 0, 0, (byte) 0)` somewhere in `Sonic1LZEvents` and re-run the test. Expected: FAIL with the file path. Revert the regression.

- [ ] **Step 4: Commit**

```
git add src/test/java/com/openggf/game/mutation/TestNoDirectMapMutationsInGameplay.java
git commit -m "$(cat <<'EOF'
test(mutation): guard against direct Map.setValue calls in gameplay paths

Source-scan test fails CI if any file under game/sonic1/, sonic2/,
sonic3k/, or level/objects/ regains a direct getMap().setValue
mutation. Editor commands are explicitly allow-listed.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Track B — `LiveTraceComparator` per-frame divergence-vector exposure

The keystone parity test in the rewind framework needs to compare two engine runs field-for-field. Today `LiveTraceComparator.absorbDivergentFields` (`trace/live/LiveTraceComparator.java:135-187`) consumes per-frame `FrameComparison` results locally and only exposes counters and a 5-entry `mismatches` ring. Add a per-frame callback so a captor can record the full divergence vector.

---

### Task B.1: Add per-frame `FrameComparison` observer hook

**Files:**
- Modify: `src/main/java/com/openggf/trace/live/LiveTraceComparator.java`
- Test: `src/test/java/com/openggf/trace/live/TestLiveTraceComparatorObserver.java` (create)

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.trace.live;

import com.openggf.trace.FrameComparison;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TestLiveTraceComparatorObserver {

    @Test
    void perFrameObserverReceivesEveryComparison() {
        TraceData trace = TestTraceFixtures.twoFrameTrace();   // helper to be added
        List<FrameComparison> observed = new ArrayList<>();
        Consumer<FrameComparison> observer = observed::add;

        LiveTraceComparator c = new LiveTraceComparator(
                trace,
                ToleranceConfig.defaults(),
                /* initialCursor= */ 0,
                /* spriteProvider= */ () -> TestTraceFixtures.stubSprite(),
                /* firstErrorCallback= */ null,
                /* perFrameObserver= */ observer);

        // Drive both frames through the comparator's onPostStep (or whatever
        // the existing test harness uses).
        TestTraceFixtures.driveFrames(c, 2);

        assertEquals(2, observed.size(),
                "Observer should fire exactly once per gameplay-compared frame");
        assertNotNull(observed.get(0).divergentFields());
    }

    @Test
    void nullObserverIsHonoured() {
        TraceData trace = TestTraceFixtures.twoFrameTrace();
        LiveTraceComparator c = new LiveTraceComparator(
                trace, ToleranceConfig.defaults(), 0,
                () -> TestTraceFixtures.stubSprite(),
                null, null);
        TestTraceFixtures.driveFrames(c, 2);
        // No assertion needed — must not NPE.
    }
}
```

If `TestTraceFixtures` does not exist, create the minimum stubs needed in the same file (or a small `package-info.java`-adjacent helper) to materialise a 2-frame `TraceData` and a stub `AbstractPlayableSprite`. Keep the helpers package-private to this test; do not add a public fixture.

- [ ] **Step 2: Run the test, confirm it fails to compile**

The new constructor signature with `perFrameObserver` does not exist yet.

```
mvn test "-Dtest=TestLiveTraceComparatorObserver" -q
```

Expected: compile failure.

- [ ] **Step 3: Add the observer plumbing**

In `LiveTraceComparator.java`:

1. Add a field `private final java.util.function.Consumer<FrameComparison> perFrameObserver;`
2. Add a constructor overload that takes the observer; the existing constructors delegate with `perFrameObserver = null`.
3. In `absorbDivergentFields(...)` (or wherever the `FrameComparison result` is currently produced — see :123 and :130), invoke `if (perFrameObserver != null) perFrameObserver.accept(result);` **before** `absorbDivergentFields` is called. Capture happens regardless of severity so observers see the full per-frame state.

Concretely, change the existing `absorbDivergentFields(result, expected.frame());` call site to:

```java
        if (perFrameObserver != null) {
            perFrameObserver.accept(result);
        }
        absorbDivergentFields(result, expected.frame());
```

- [ ] **Step 4: Run the test; expect PASS**

```
mvn test "-Dtest=TestLiveTraceComparatorObserver" -q
```

- [ ] **Step 5: Run all existing trace tests**

```
mvn test "-Dtest=*Trace*" -q
```

Expected: all existing trace replay tests still pass (no behavioural change for callers that did not pass an observer).

- [ ] **Step 6: Commit**

```
git add src/main/java/com/openggf/trace/live/LiveTraceComparator.java \
        src/test/java/com/openggf/trace/live/TestLiveTraceComparatorObserver.java
git commit -m "$(cat <<'EOF'
feat(trace): expose per-frame FrameComparison observer on LiveTraceComparator

Adds a Consumer<FrameComparison> hook that fires once per
gameplay-compared frame (before absorbDivergentFields). Existing
constructors retain null-observer behaviour. Enables the rewind
framework's keystone parity test to capture full per-frame divergence
vectors from two engine runs and compare them field-for-field.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task B.2: Provide `RecordingFrameObserver` helper

**Files:**
- Create: `src/main/java/com/openggf/trace/live/RecordingFrameObserver.java`
- Test: `src/test/java/com/openggf/trace/live/TestRecordingFrameObserver.java` (create)

A small reusable captor: stores every `FrameComparison` into an `ArrayList`, exposes `frames()` and `equals(other)`/`diff(other)` for comparing two captures. Used by the rewind framework's parity tests in the next plan.

- [ ] **Step 1: Write the test**

```java
package com.openggf.trace.live;

import com.openggf.trace.FrameComparison;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestRecordingFrameObserver {

    @Test
    void recordsEachAcceptedFrame() {
        RecordingFrameObserver obs = new RecordingFrameObserver();
        FrameComparison a = TestTraceFixtures.dummyComparison(1);
        FrameComparison b = TestTraceFixtures.dummyComparison(2);
        obs.accept(a);
        obs.accept(b);
        assertEquals(2, obs.frames().size());
        assertSame(a, obs.frames().get(0));
        assertSame(b, obs.frames().get(1));
    }

    @Test
    void diffReportsFirstFrameDifference() {
        RecordingFrameObserver lhs = new RecordingFrameObserver();
        RecordingFrameObserver rhs = new RecordingFrameObserver();
        FrameComparison fa = TestTraceFixtures.dummyComparison(1);
        FrameComparison fb1 = TestTraceFixtures.dummyComparison(2);
        FrameComparison fb2 = TestTraceFixtures.dummyComparisonWithDelta(2, 3);
        lhs.accept(fa); lhs.accept(fb1);
        rhs.accept(fa); rhs.accept(fb2);
        var diff = lhs.diff(rhs);
        assertTrue(diff.isPresent());
        assertEquals(2, diff.get().frameNumber());
    }

    @Test
    void diffEmptyWhenIdentical() {
        RecordingFrameObserver lhs = new RecordingFrameObserver();
        RecordingFrameObserver rhs = new RecordingFrameObserver();
        FrameComparison fa = TestTraceFixtures.dummyComparison(1);
        lhs.accept(fa);
        rhs.accept(fa);
        assertTrue(lhs.diff(rhs).isEmpty());
    }
}
```

- [ ] **Step 2: Run; confirm compile failure**

- [ ] **Step 3: Implement `RecordingFrameObserver`**

```java
package com.openggf.trace.live;

import com.openggf.trace.FrameComparison;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Captures every FrameComparison emitted by a LiveTraceComparator into an
 * in-memory list, intended for use by the rewind framework's keystone
 * parity test (capture two engine runs, compare frame-for-frame).
 */
public final class RecordingFrameObserver implements Consumer<FrameComparison> {

    private final List<FrameComparison> frames = new ArrayList<>();

    @Override
    public void accept(FrameComparison frame) {
        frames.add(frame);
    }

    /** Captured frames in arrival order. */
    public List<FrameComparison> frames() {
        return List.copyOf(frames);
    }

    /** First differing frame between this capture and {@code other}, or empty. */
    public Optional<FrameComparison> diff(RecordingFrameObserver other) {
        int n = Math.min(frames.size(), other.frames.size());
        for (int i = 0; i < n; i++) {
            FrameComparison lhs = frames.get(i);
            FrameComparison rhs = other.frames.get(i);
            if (!lhs.equals(rhs)) {
                return Optional.of(lhs);
            }
        }
        if (frames.size() != other.frames.size()) {
            return Optional.of(frames.size() > n ? frames.get(n) : other.frames.get(n));
        }
        return Optional.empty();
    }
}
```

If `FrameComparison` does not implement `equals(Object)` cleanly, the diff comparison must fall back to comparing the divergent-field lists explicitly. Inspect `com.openggf.trace.FrameComparison` first; if it's a `record`, equality is generated.

- [ ] **Step 4: Run test; expect PASS**

- [ ] **Step 5: Commit**

```
git add src/main/java/com/openggf/trace/live/RecordingFrameObserver.java \
        src/test/java/com/openggf/trace/live/TestRecordingFrameObserver.java
git commit -m "$(cat <<'EOF'
feat(trace): add RecordingFrameObserver captor for per-frame FrameComparison

Buffers every FrameComparison from a LiveTraceComparator's per-frame
observer hook and exposes a frames() accessor + diff(other) for
comparing two engine runs. Used by the rewind framework's keystone
parity test.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Track C — RNG CI guard test

Source-scan test that fails CI on any new gameplay-path use of `java.util.Random` / `ThreadLocalRandom` / `Math.random()`. Mirrors `TestObjectServicesMigrationGuard`.

---

### Task C.1: Write the RNG source-scan guard

**Files:**
- Test: `src/test/java/com/openggf/game/TestNoStrayRngInGameplay.java` (create)

- [ ] **Step 1: Write the test**

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Determinism guard. Any RNG used during gameplay simulation must route
 * through GameRng on GameplayModeContext so it can be snapshotted for
 * rewind. This test fails if any file under the scanned packages uses
 * java.util.Random / ThreadLocalRandom / Math.random() outside the
 * allow-list.
 */
class TestNoStrayRngInGameplay {

    private static final String[] SCAN_ROOTS = {
            "src/main/java/com/openggf/game/sonic1",
            "src/main/java/com/openggf/game/sonic2",
            "src/main/java/com/openggf/game/sonic3k",
            "src/main/java/com/openggf/level/objects",
            "src/main/java/com/openggf/sprites",
            "src/main/java/com/openggf/physics",
    };

    private static final Pattern STRAY_RNG = Pattern.compile(
            "\\bnew\\s+Random\\s*\\(|"
            + "\\bThreadLocalRandom\\b|"
            + "\\bMath\\.random\\s*\\(|"
            + "import\\s+java\\.util\\.Random\\b|"
            + "import\\s+java\\.util\\.concurrent\\.ThreadLocalRandom\\b");

    /**
     * Permanent exceptions: pre-gameplay UI / non-determinism-relevant code.
     * Each entry must be justified.
     */
    private static final Set<String> ALLOWED = Set.of(
            // MasterTitleScreen — pre-gameplay master title, no determinism
            // boundary
            "MasterTitleScreen.java",
            // Sonic2SpecialStageRenderer uses System.currentTimeMillis for
            // an invuln blink only — renderer-only, not gameplay state
            "Sonic2SpecialStageRenderer.java"
    );

    @Test
    void noStrayRngInGameplayPaths() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String root : SCAN_ROOTS) {
            Path rootPath = Paths.get(root);
            if (!Files.exists(rootPath)) continue;
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !ALLOWED.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            String stripped = content
                                    .replaceAll("//.*", "")
                                    .replaceAll("/\\*[\\s\\S]*?\\*/", "");
                            if (STRAY_RNG.matcher(stripped).find()) {
                                violations.add(p.toString());
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            }
        }
        if (!violations.isEmpty()) {
            fail("Stray RNG sources found in gameplay paths. All RNG must "
                    + "route through GameRng on GameplayModeContext for "
                    + "rewind determinism.\n  "
                    + String.join("\n  ", violations));
        }
    }
}
```

- [ ] **Step 2: Run the test**

```
mvn test "-Dtest=TestNoStrayRngInGameplay" -q
```

Expected: PASS (Phase A audit confirmed all gameplay-path offenders ported).

- [ ] **Step 3: Verify the guard catches regressions**

Temporarily insert `Random r = new Random();` into `src/main/java/com/openggf/game/sonic2/objects/RivetObjectInstance.java`. Re-run the test. Expected: FAIL with the file path. Revert.

- [ ] **Step 4: Commit**

```
git add src/test/java/com/openggf/game/TestNoStrayRngInGameplay.java
git commit -m "$(cat <<'EOF'
test(rng): guard gameplay paths against stray Random/ThreadLocalRandom/Math.random

Source-scan CI test fails if any file under game/sonic1/2/3k/,
level/objects/, sprites/, or physics/ regains a direct RNG source
outside the GameRng abstraction. Locks in the Phase A audit state
and keeps rewind determinism intact. MasterTitleScreen (pre-gameplay)
and Sonic2SpecialStageRenderer (renderer-only blink) allow-listed.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Track D — `OscillationManager` snapshot helpers

Make `OscillationManager` round-trippable without relocating it. Adds a `snapshot()` returning an immutable `OscillationSnapshot` record and a `restore(OscillationSnapshot)` method. Day-2 plan wires it into the rewind framework's `RewindRegistry`.

---

### Task D.1: Add `OscillationSnapshot` record + `snapshot()`/`restore(...)` static methods

**Files:**
- Modify: `src/main/java/com/openggf/game/OscillationManager.java`
- Create: `src/main/java/com/openggf/game/OscillationSnapshot.java`
- Test: `src/test/java/com/openggf/game/TestOscillationManagerSnapshot.java` (create)

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestOscillationManagerSnapshot {

    @Test
    void roundTripPreservesOscillatorPhase() {
        OscillationManager.reset();
        // Tick a few times to advance values away from the initial state
        for (int f = 0; f < 13; f++) {
            OscillationManager.update(f);
        }

        OscillationSnapshot snap = OscillationManager.snapshot();

        // Tick further to produce different state
        for (int f = 13; f < 50; f++) {
            OscillationManager.update(f);
        }
        int[] divergedValues = OscillationManager.valuesForTest();
        int[] divergedDeltas = OscillationManager.deltasForTest();

        OscillationManager.restore(snap);

        assertArrayEquals(snap.values(), OscillationManager.valuesForTest(),
                "values must restore exactly");
        assertArrayEquals(snap.deltas(), OscillationManager.deltasForTest(),
                "deltas must restore exactly");
        assertEquals(snap.control(), OscillationManager.controlForTest(),
                "control bitfield must restore exactly");
        // Sanity: the diverged state was not equal to the snapshot
        assertFalse(java.util.Arrays.equals(divergedValues, snap.values()));
        assertFalse(java.util.Arrays.equals(divergedDeltas, snap.deltas()));
    }

    @Test
    void snapshotIsImmutable() {
        OscillationManager.reset();
        OscillationSnapshot snap = OscillationManager.snapshot();
        int[] values = snap.values();
        values[0] = 0xDEAD;   // mutate the returned array
        OscillationSnapshot snap2 = OscillationManager.snapshot();
        assertNotEquals(0xDEAD, snap2.values()[0],
                "Snapshot must defensively copy or expose immutable views");
    }

    @Test
    void restoreAlsoCarriesS1ResetFlavour() {
        OscillationManager.resetForSonic1();
        for (int f = 0; f < 7; f++) OscillationManager.update(f);
        OscillationSnapshot s1Snap = OscillationManager.snapshot();
        // Switch flavour
        OscillationManager.reset();
        for (int f = 0; f < 7; f++) OscillationManager.update(f);
        // Restoring the S1 snap must reproduce exact S1 state
        OscillationManager.restore(s1Snap);
        assertArrayEquals(s1Snap.values(), OscillationManager.valuesForTest());
        assertArrayEquals(s1Snap.deltas(), OscillationManager.deltasForTest());
        assertEquals(s1Snap.control(), OscillationManager.controlForTest());
    }
}
```

- [ ] **Step 2: Run the test, confirm compile failure**

```
mvn test "-Dtest=TestOscillationManagerSnapshot" -q
```

Expected: compile failure (`OscillationSnapshot` doesn't exist; `snapshot()` and `restore(...)` don't exist).

- [ ] **Step 3: Create `OscillationSnapshot` record**

```java
package com.openggf.game;

import java.util.Objects;

/**
 * Immutable capture of OscillationManager state for rewind snapshots.
 * Fields mirror the static fields on OscillationManager. The values[] and
 * deltas[] arrays are defensively copied at construction so a captured
 * snapshot is safe to retain across many frames.
 */
public record OscillationSnapshot(
        int[] values,
        int[] deltas,
        int[] activeSpeeds,
        int[] activeLimits,
        int control,
        int lastFrame,
        int suppressedUpdates) {

    public OscillationSnapshot {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(deltas, "deltas");
        Objects.requireNonNull(activeSpeeds, "activeSpeeds");
        Objects.requireNonNull(activeLimits, "activeLimits");
        // Defensive copy so the record is truly immutable.
        values = values.clone();
        deltas = deltas.clone();
        activeSpeeds = activeSpeeds.clone();
        activeLimits = activeLimits.clone();
    }

    @Override public int[] values()        { return values.clone(); }
    @Override public int[] deltas()        { return deltas.clone(); }
    @Override public int[] activeSpeeds()  { return activeSpeeds.clone(); }
    @Override public int[] activeLimits()  { return activeLimits.clone(); }
}
```

- [ ] **Step 4: Add `snapshot()` and `restore(...)` to `OscillationManager`**

In `OscillationManager.java`, add (after the `valuesForTest()` / `deltasForTest()` block):

```java
    /** Captures the current oscillator phase for rewind snapshots. */
    public static OscillationSnapshot snapshot() {
        return new OscillationSnapshot(
                values, deltas, activeSpeeds, activeLimits,
                control, lastFrame, suppressedUpdates);
    }

    /** Restores oscillator phase from a previously captured snapshot. */
    public static void restore(OscillationSnapshot snap) {
        java.util.Objects.requireNonNull(snap, "snap");
        int[] sv = snap.values();
        int[] sd = snap.deltas();
        int[] ss = snap.activeSpeeds();
        int[] sl = snap.activeLimits();
        for (int i = 0; i < OSC_COUNT; i++) {
            values[i] = sv[i] & 0xFFFF;
            deltas[i] = sd[i] & 0xFFFF;
        }
        activeSpeeds = ss;
        activeLimits = sl;
        control = snap.control() & 0xFFFF;
        lastFrame = snap.lastFrame();
        suppressedUpdates = Math.max(0, snap.suppressedUpdates());
    }
```

Note: `activeSpeeds` and `activeLimits` references can be assigned from the snapshot's defensive-copied arrays directly because the record returns a clone on each accessor call — the assigned reference is unique to this restore.

- [ ] **Step 5: Run the test, confirm PASS**

```
mvn test "-Dtest=TestOscillationManagerSnapshot" -q
```

- [ ] **Step 6: Run any oscillation-related tests**

```
mvn test "-Dtest=*Oscillat*" -q
```

Expected: all pass.

- [ ] **Step 7: Commit**

```
git add src/main/java/com/openggf/game/OscillationManager.java \
        src/main/java/com/openggf/game/OscillationSnapshot.java \
        src/test/java/com/openggf/game/TestOscillationManagerSnapshot.java
git commit -m "$(cat <<'EOF'
feat(oscillation): add snapshot()/restore() helpers and OscillationSnapshot

Captures and restores the full oscillator phase (values, deltas,
control, active speed/limit tables, lastFrame, suppressedUpdates) as
an immutable record. Prerequisite for rewind framework: MZ magma's
animated tile re-derivation reads OscillationManager.getByte, so
oscillator state must round-trip across a rewind.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final integration check

After all four tracks land, do one full-suite run to confirm no cross-track regression.

### Task FINAL.1: Full test suite

- [ ] **Step 1: Run full test suite**

```
mvn test -q
```

Expected: all tests PASS, including the four new guard/round-trip tests.

- [ ] **Step 2: If tests fail**

Diagnose. The four tracks were designed to be independent so a regression should isolate to one. Common causes:
- `mutationPipeline()` accessor on `ObjectServices` not added to `StubObjectServices` → headless object tests NPE.
- `LiveTraceComparator` constructor overload not delegating correctly → existing trace replay tests fail to construct.
- `TestNoStrayRngInGameplay` allow-list missing a legitimate exception → grep-based false positive.
- `TestNoDirectMapMutationsInGameplay` regex matching unintended `.setValue(int)` on something other than `Map` (e.g., a different `setValue` API) → tighten the pattern.

Fix and re-run.

- [ ] **Step 3: No commit needed for this task** — it's a verification step.

---

## Plan summary

When this plan completes, the codebase has:

1. **Zero direct `Map.setValue(...)` calls in gameplay paths.** Every gameplay-path level mutation goes through `ZoneLayoutMutationPipeline`, which means the future rewind framework can wrap `DirectLevelMutationSurface` with copy-on-write in one place.
2. **A per-frame divergence-vector hook on `LiveTraceComparator`.** The keystone parity test in the rewind framework can capture two engine runs as `RecordingFrameObserver`s and compare them with `lhs.diff(rhs)`.
3. **A CI guard against new gameplay-path RNG sources.** Phase A audit state is locked in.
4. **`OscillationManager.snapshot()`/`restore(...)`** with an `OscillationSnapshot` record. Plan 2's `RewindRegistry` registers a thin `RewindSnapshottable<OscillationSnapshot>` adapter against the static `snapshot/restore` pair.

The next plan — `2026-05-XX-rewind-framework-v1.md` — implements:

- `RewindSnapshottable`, `RewindRegistry`, `KeyframeStore`, `SegmentCache`, `InputSource`, `RewindController`, `PlaybackController`
- Lift snapshot/CoW machinery to `AbstractLevel` (epoch counter + `cowEnsureWritable` on `Block`/`Chunk`/`Map`)
- Per-subsystem `RewindSnapshottable` implementations (Camera, GameStateManager, GameRng, ObjectManager composite, ...)
- Visualiser integration (held-rewind / single-step / buffer-end UI)
- Trace-replay parity test using `RecordingFrameObserver` from this plan
- `RewindBenchmark` headless harness with the long-tail determinism gate
