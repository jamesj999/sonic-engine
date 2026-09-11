# Rewind Boundary Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent live gameplay rewind from crossing deterministic session and level boundaries, while preserving trace-session rewind ownership and adding a regression test for stale input-source/controller frame desync after level loads.

**Architecture:** Add a small rewind-boundary vocabulary under `com.openggf.game.rewind`, route boundary reporting through `GameplayModeContext`, install a `GameLoop`-owned live reporter that no-ops while `TraceSessionLauncher` owns rewind, and centralize live buffer teardown/realignment in `LiveRewindManager`. `LevelManager` reports intent through the mode context instead of manipulating `RewindController` directly.

**Tech Stack:** Java 17, Maven, JUnit 5, existing `GameServices` / `SessionManager` / `GameplayModeContext` runtime ownership model.

---

## Reference Material

- Design spec: `docs/architecture/designs/2026-06-26-rewind-boundary-policy-design.md`
- Live manager: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Input source: `src/main/java/com/openggf/game/rewind/LiveRewindInputSource.java`
- Controller: `src/main/java/com/openggf/game/rewind/RewindController.java`
- Context owner: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Boundary callers: `src/main/java/com/openggf/GameLoop.java`, `src/main/java/com/openggf/level/LevelManager.java`
- Existing tests:
  - `src/test/java/com/openggf/game/rewind/TestLiveRewindInputSource.java`
  - `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerAudioCleanup.java`
  - `src/test/java/com/openggf/game/rewind/TestHeldRewindAudioRestoreDeferral.java`
  - `src/test/java/com/openggf/game/session/TestGameplayModeContextPlaybackController.java`
  - `src/test/java/com/openggf/game/session/TestGameplayModeContextRewindRegistry.java`

## Boundaries To Implement

`RewindBoundary.LEVEL_LOAD`

- Commit point: after `LevelManager.loadZoneAndAct(...)` finishes installing the new level and registering the gameplay rewind adapters.
- Live behavior: reset live input rows to frame 0 and reset the controller to frame 0, so `LiveRewindInputSource.earliestFrame() == 0`, `LiveRewindInputSource.frameCount() == 1`, and `RewindController.currentFrame() == 0`.
- Trace behavior: no live-manager action. Trace sessions keep using `TraceSessionLauncher.recordExternalRewindFrameAtBoundary()` where already present.

`RewindBoundary.SEAMLESS_LEVEL_TRANSITION`

- Commit point: immediately after `LevelManager.applySeamlessTransition(...)` succeeds.
- Live behavior: keep only the current input row and reset keyframe storage at the current frame. This blocks rewind from crossing the act transition without rebasing frame numbers.
- Trace behavior: preserve the existing trace-session branch in `GameLoop`.

`RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE`

- Commit point: when the game loop commits a transition from `GameMode.LEVEL` into `SPECIAL_STAGE`, `BONUS_STAGE`, title-card staging for bonus stages, data select, master title, legal disclaimer, or another non-level mode.
- Live behavior: deterministic teardown through `LiveRewindManager.clear()`, including reverse-audio/fade cleanup if a rewind is in progress. This is distinct from the existing mode gate, which lazily clears on the next non-level frame.

`RewindBoundary.MODE_ENTER_REWINDABLE`

- Commit point: when the game loop commits a transition into `GameMode.LEVEL` after a non-level mode or title-card staging.
- Live behavior: idempotent install/realign. If live rewind is enabled and a gameplay context exists, ensure the manager has the current context/controller/input-source association. Do not create duplicate keyframes if the level-load boundary already reset the controller.

---

## Task 1: Add Boundary Vocabulary And Context Reporter

- [ ] Create `src/main/java/com/openggf/game/rewind/RewindBoundary.java`.

```java
package com.openggf.game.rewind;

public enum RewindBoundary {
    LEVEL_LOAD,
    SEAMLESS_LEVEL_TRANSITION,
    MODE_EXIT_TO_NON_REWINDABLE,
    MODE_ENTER_REWINDABLE
}
```

- [ ] Create `src/main/java/com/openggf/game/rewind/RewindBoundaryReporter.java`.

```java
package com.openggf.game.rewind;

@FunctionalInterface
public interface RewindBoundaryReporter {
    RewindBoundaryReporter NO_OP = boundary -> {
    };

    void markBoundary(RewindBoundary boundary);
}
```

- [ ] Modify `src/main/java/com/openggf/game/session/GameplayModeContext.java`.

Add imports:

```java
import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.rewind.RewindBoundaryReporter;
```

Add field near the rewind controller fields:

```java
private RewindBoundaryReporter rewindBoundaryReporter = RewindBoundaryReporter.NO_OP;
```

Add public methods:

```java
public void setRewindBoundaryReporter(RewindBoundaryReporter reporter) {
    this.rewindBoundaryReporter = reporter != null ? reporter : RewindBoundaryReporter.NO_OP;
}

public void markRewindBoundary(RewindBoundary boundary) {
    if (boundary != null) {
        rewindBoundaryReporter.markBoundary(boundary);
    }
}
```

In `tearDownManagers()`, reset the reporter:

```java
rewindBoundaryReporter = RewindBoundaryReporter.NO_OP;
```

- [ ] Add `src/test/java/com/openggf/game/session/TestGameplayModeContextRewindBoundaryReporter.java`.

Test cases:

```java
import com.openggf.game.sonic2.Sonic2GameModule;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Test
void defaultReporterAcceptsBoundary() {
    GameplayModeContext context = new GameplayModeContext(new WorldSession(new Sonic2GameModule()));
    assertDoesNotThrow(() -> context.markRewindBoundary(RewindBoundary.LEVEL_LOAD));
}

@Test
void installedReporterReceivesBoundary() {
    GameplayModeContext context = new GameplayModeContext(new WorldSession(new Sonic2GameModule()));
    AtomicReference<RewindBoundary> seen = new AtomicReference<>();

    context.setRewindBoundaryReporter(seen::set);
    context.markRewindBoundary(RewindBoundary.SEAMLESS_LEVEL_TRANSITION);

    assertEquals(RewindBoundary.SEAMLESS_LEVEL_TRANSITION, seen.get());
}

@Test
void nullReporterRestoresNoOpReporter() {
    GameplayModeContext context = new GameplayModeContext(new WorldSession(new Sonic2GameModule()));
    AtomicInteger calls = new AtomicInteger();

    context.setRewindBoundaryReporter(boundary -> calls.incrementAndGet());
    context.setRewindBoundaryReporter(null);
    context.markRewindBoundary(RewindBoundary.LEVEL_LOAD);

    assertEquals(0, calls.get());
}
```

Focused verification:

```powershell
mvn -Dmse=off "-Dtest=TestGameplayModeContextRewindBoundaryReporter" test
```

---

## Task 2: Add Input-Source Boundary Reset Helpers

- [ ] Modify `src/main/java/com/openggf/game/rewind/LiveRewindInputSource.java`.

Add:

```java
public void resetToFrameZero() {
    frames.clear();
    baseFrame = 0;
    frames.add(neutralFrameInput(0));
}

public void retainOnlyFrame(int frame) {
    if (frame < earliestFrame() || frame >= frameCount()) {
        resetToSingleNeutralFrame(frame);
        return;
    }
    Bk2FrameInput retained = read(frame);
    frames.clear();
    baseFrame = frame;
    frames.add(retained);
}

private void resetToSingleNeutralFrame(int frame) {
    frames.clear();
    baseFrame = frame;
    frames.add(neutralFrameInput(frame));
}

private static Bk2FrameInput neutralFrameInput(int frame) {
    return new Bk2FrameInput(frame, 0, 0, false, 0, 0, false,
            false, false, false, "live:" + frame);
}
```

Rationale:

- `resetToFrameZero()` fixes the `LEVEL_LOAD` stale-base-frame case without replacing the `LiveRewindInputSource` instance already held by `RewindController`.
- `retainOnlyFrame(frame)` supports seamless transitions at non-zero frame numbers and keeps controller/input-source frame ranges mutually consistent.

- [ ] Extend `src/test/java/com/openggf/game/rewind/TestLiveRewindInputSource.java`.

Add:

```java
@Test
void resetToFrameZeroClearsOldRowsAndBaseFrame() {
    InputHandler input = new InputHandler();
    LiveRewindInputSource source = new LiveRewindInputSource();
    input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_PRESS);
    source.appendFrame(input, config);
    input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_RELEASE);
    input.handleKeyEvent(config.getInt(SonicConfiguration.RIGHT), GLFW_PRESS);
    source.appendFrame(input, config);
    source.discardBefore(2);

    source.resetToFrameZero();

    assertEquals(0, source.earliestFrame());
    assertEquals(1, source.frameCount());
    Bk2FrameInput frame = source.read(0);
    assertEquals(0, frame.frameIndex());
    assertEquals(0, frame.p1InputMask());
    assertEquals(0, frame.p1ActionMask());
}

@Test
void retainOnlyFrameKeepsRequestedExistingFrame() {
    InputHandler input = new InputHandler();
    LiveRewindInputSource source = new LiveRewindInputSource();
    input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_PRESS);
    source.appendFrame(input, config);
    input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_RELEASE);
    input.handleKeyEvent(config.getInt(SonicConfiguration.RIGHT), GLFW_PRESS);
    source.appendFrame(input, config);

    source.retainOnlyFrame(2);

    assertEquals(2, source.earliestFrame());
    assertEquals(3, source.frameCount());
    Bk2FrameInput frame = source.read(2);
    assertEquals(2, frame.frameIndex());
    assertEquals(AbstractPlayableSprite.INPUT_RIGHT, frame.p1InputMask());
}

@Test
void retainOnlyFrameCreatesNeutralRowWhenFrameIsAbsent() {
    LiveRewindInputSource source = new LiveRewindInputSource();

    source.retainOnlyFrame(9);

    assertEquals(9, source.earliestFrame());
    assertEquals(10, source.frameCount());
    assertEquals(9, source.read(9).frameIndex());
    assertEquals(0, source.read(9).p1InputMask());
    assertEquals(0, source.read(9).p1ActionMask());
}
```

These tests must use the current `TestLiveRewindInputSource` fixture style: the `config` field initialized by `resetConfig()`, `InputHandler.handleKeyEvent(...)`, GLFW press/release constants, `Bk2FrameInput`, and mask assertions through `AbstractPlayableSprite.INPUT_*`.

Focused verification:

```powershell
mvn -Dmse=off "-Dtest=TestLiveRewindInputSource" test
```

---

## Task 3: Centralize Boundary Handling In LiveRewindManager

- [ ] Modify `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`.

Add import:

```java
import com.openggf.audio.rewind.AudioPresentationPolicy;
import com.openggf.game.session.SessionManager;
```

Add public entrypoint:

```java
public void markBoundary(RewindBoundary boundary) {
    if (boundary == null) {
        return;
    }
    switch (boundary) {
        case LEVEL_LOAD -> handleLevelLoadBoundary();
        case SEAMLESS_LEVEL_TRANSITION -> handleSeamlessLevelTransitionBoundary();
        case MODE_EXIT_TO_NON_REWINDABLE -> clear();
        case MODE_ENTER_REWINDABLE -> handleModeEnterRewindableBoundary();
    }
}
```

Add helpers:

```java
private void handleLevelLoadBoundary() {
    if (!enabled()) {
        clear();
        return;
    }
    if (!ensureInstalled()) {
        return;
    }
    if (rewinding) {
        cleanupPresentationAfterRealtimeRewind(AudioPresentationPolicy.STOP_TRANSIENT_SFX_RESYNC_MUSIC);
        rewinding = false;
    }
    inputSource.resetToFrameZero();
    rewindController.resetToFrameZero();
}

private void handleSeamlessLevelTransitionBoundary() {
    if (!enabled()) {
        clear();
        return;
    }
    if (!ensureInstalled()) {
        return;
    }
    if (rewinding) {
        cleanupPresentationAfterRealtimeRewind(AudioPresentationPolicy.STOP_TRANSIENT_SFX_RESYNC_MUSIC);
        rewinding = false;
    }
    int frame = rewindController.currentFrame();
    inputSource.retainOnlyFrame(frame);
    rewindController.resetBufferAtCurrentFrame();
}

private void handleModeEnterRewindableBoundary() {
    if (!enabled()) {
        clear();
        return;
    }
    ensureInstalled();
}
```

Implementation detail:

- `ensureInstalled()` must continue re-resolving `SessionManager.getCurrentGameplayMode()` every time it runs. Do not cache a context outside the manager field already used to detect context changes.
- Keep `resetBufferAtCurrentFrame(GameMode mode)` as a compatibility wrapper for existing callers until all call sites are migrated:

```java
public void resetBufferAtCurrentFrame(GameMode mode) {
    if (mode != GameMode.LEVEL) {
        return;
    }
    markBoundary(RewindBoundary.SEAMLESS_LEVEL_TRANSITION);
}
```

- [ ] Add `src/test/java/com/openggf/game/rewind/TestLiveRewindBoundaryPolicy.java`.

Use a fixture that creates a real `GameplayModeContext`, registers it with `SessionManager`, attaches gameplay managers so `rewindRegistry` is initialized, and drives installation through `LiveRewindManager` itself. Do not call `GameplayModeContext.installPlaybackController(...)` directly for this fixture, because `LiveRewindManager.ensureInstalled()` creates and owns the `LiveRewindInputSource` / `RewindController` pair it will later reset.

Configure services before each test:

```java
@BeforeEach
void configureServices() {
    EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
}
```

Fixture shape:

```java
private Fixture installLiveFixture() {
    SessionManager.clear();
    SonicConfigurationService config = SonicConfigurationService.getInstance();
    config.resetToDefaults();
    config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
    GameplayModeContext context = SessionManager.openGameplaySession(new Sonic2GameModule());

    Camera camera = new Camera();
    TimerManager timers = new TimerManager();
    GameStateManager gameState = new GameStateManager();
    FadeManager fade = new FadeManager();
    GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
    DefaultSolidExecutionRegistry solid = new DefaultSolidExecutionRegistry();

    context.attachGameplayManagers(camera, timers, gameState, fade, rng, solid);
    context.initializeFreshGameplayState();

    LiveRewindManager manager = new LiveRewindManager(config);
    InputHandler initialInput = new InputHandler();
    manager.handleRealtimeRewindInput(GameMode.LEVEL, initialInput);

    return new Fixture(
            manager,
            readPrivateField(manager, "inputSource"),
            readPrivateField(manager, "rewindController"));
}
```

The concrete setup must satisfy two assertions before each boundary test mutates history:

```java
assertSame(fixture.controller, SessionManager.getCurrentGameplayMode().getRewindController());
assertNotNull(fixture.inputSource);
```

Use reflection helpers matching `TestLiveRewindManagerAudioCleanup` for private field access.

Test cases:

```java
@Test
void levelLoadBoundaryRealignsInputSourceAndControllerAtFrameZero() {
    Fixture fixture = installLiveFixture();
    InputHandler input = new InputHandler();
    input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_PRESS);
    fixture.inputSource.appendFrame(input, config);
    fixture.controller.recordExternalStep();
    input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_RELEASE);
    input.handleKeyEvent(config.getInt(SonicConfiguration.RIGHT), GLFW_PRESS);
    fixture.inputSource.appendFrame(input, config);
    fixture.controller.recordExternalStep();
    fixture.inputSource.discardBefore(2);

    fixture.manager.markBoundary(RewindBoundary.LEVEL_LOAD);

    assertEquals(0, fixture.controller.currentFrame());
    assertEquals(0, fixture.inputSource.earliestFrame());
    assertEquals(1, fixture.inputSource.frameCount());
}

@Test
void seamlessBoundaryKeepsOnlyCurrentFrame() {
    Fixture fixture = installLiveFixture();
    InputHandler input = new InputHandler();
    input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_PRESS);
    fixture.inputSource.appendFrame(input, config);
    fixture.controller.recordExternalStep();
    input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_RELEASE);
    input.handleKeyEvent(config.getInt(SonicConfiguration.RIGHT), GLFW_PRESS);
    fixture.inputSource.appendFrame(input, config);
    fixture.controller.recordExternalStep();

    fixture.manager.markBoundary(RewindBoundary.SEAMLESS_LEVEL_TRANSITION);

    assertEquals(2, fixture.controller.currentFrame());
    assertEquals(2, fixture.inputSource.earliestFrame());
    assertEquals(3, fixture.inputSource.frameCount());
}

@Test
void modeExitBoundaryClearsInstalledState() {
    Fixture fixture = installLiveFixture();
    assertNotNull(readPrivateField(fixture.manager, "inputSource"));
    assertNotNull(readPrivateField(fixture.manager, "rewindController"));

    fixture.manager.markBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);

    assertNull(readPrivateField(fixture.manager, "inputSource"));
    assertNull(readPrivateField(fixture.manager, "rewindController"));
    assertNull(readPrivateField(fixture.manager, "installedGameplayMode"));
}
```

Use reflection for the private-field assertions, matching the existing style in `TestLiveRewindManagerAudioCleanup`.

Focused verification:

```powershell
mvn -Dmse=off "-Dtest=TestLiveRewindBoundaryPolicy,TestLiveRewindInputSource,TestHeldRewindAudioRestoreDeferral,TestRewindController" test
```

---

## Task 4: Wire LevelManager Through GameplayModeContext

- [ ] Modify `src/main/java/com/openggf/level/LevelManager.java`.

Add import:

```java
import com.openggf.game.rewind.RewindBoundary;
```

Replace `resetRewindBufferAfterLevelBoundary()` body:

```java
private void resetRewindBufferAfterLevelBoundary() {
    GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
    if (gameplayMode != null) {
        gameplayMode.markRewindBoundary(RewindBoundary.LEVEL_LOAD);
    }
}
```

This keeps `LevelManager` on the context-owned reporter abstraction and removes direct knowledge of `RewindController.resetToFrameZero()`.

- [ ] Add or extend a focused test in `src/test/java/com/openggf/level/TestLevelManagerRewindBoundary.java`.

Test the private helper through the smallest public path that already executes `loadZoneAndAct(...)` in headless setup. Install a reporter on the active `GameplayModeContext` before the load:

```java
AtomicReference<RewindBoundary> boundary = new AtomicReference<>();
SessionManager.getCurrentGameplayMode().setRewindBoundaryReporter(boundary::set);

levelManager.loadZoneAndAct(zone, act);

assertEquals(RewindBoundary.LEVEL_LOAD, boundary.get());
```

Use the same zone/act bootstrap already used by nearby `LevelManager` tests. Do not introduce a ROM requirement if an existing headless level-manager test avoids it.

If every public `loadZoneAndAct(...)` path requires a ROM-backed full level profile, extract a package-private static seam:

```java
private void resetRewindBufferAfterLevelBoundary() {
    markRewindLevelLoadBoundary();
}

static void markRewindLevelLoadBoundary() {
    GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
    if (gameplayMode != null) {
        gameplayMode.markRewindBoundary(RewindBoundary.LEVEL_LOAD);
    }
}
```

Then test the seam directly from `src/test/java/com/openggf/level/TestLevelManagerRewindBoundary.java`:

```java
@Test
void levelBoundaryReportsThroughGameplayContext() {
    GameplayModeContext context = SessionManager.openGameplaySession(new Sonic2GameModule());
    AtomicReference<RewindBoundary> boundary = new AtomicReference<>();
    context.setRewindBoundaryReporter(boundary::set);

    LevelManager.markRewindLevelLoadBoundary();

    assertEquals(RewindBoundary.LEVEL_LOAD, boundary.get());
}
```

The test target is the context reporter call, not ROM decoding.

Focused verification:

```powershell
mvn -Dmse=off "-Dtest=TestLevelManagerRewindBoundary" test
```

---

## Task 5: Install The GameLoop-Owned Live Reporter

- [ ] Modify `src/main/java/com/openggf/GameLoop.java`.

Add import:

```java
import com.openggf.game.rewind.RewindBoundary;
```

Add reporter installation helper:

```java
private void installLiveRewindBoundaryReporter() {
    GameplayModeContext context = SessionManager.getCurrentGameplayMode();
    if (context == null) {
        return;
    }
    context.setRewindBoundaryReporter(boundary -> {
        if (TraceSessionLauncher.active() == null) {
            liveRewindManager.markBoundary(boundary);
        }
    });
}
```

Call `installLiveRewindBoundaryReporter()` immediately after each gameplay-session context is opened or replaced in `GameLoop`. The reporter must be installed before the first level load that should report `LEVEL_LOAD`.

Update the seamless transition block:

```java
TraceSessionLauncher traceSession = TraceSessionLauncher.active();
if (traceSession != null) {
    traceSession.recordExternalRewindFrameAtBoundary();
} else {
    GameplayModeContext context = SessionManager.getCurrentGameplayMode();
    if (context != null) {
        context.markRewindBoundary(RewindBoundary.SEAMLESS_LEVEL_TRANSITION);
    }
}
```

Add a single mode-change choke point:

```java
private void setGameMode(GameMode nextMode) {
    GameMode oldMode = currentGameMode;
    if (oldMode == nextMode) {
        return;
    }
    currentGameMode = nextMode;
    reportRewindModeBoundary(oldMode, nextMode);
}

private void reportRewindModeBoundary(GameMode oldMode, GameMode newMode) {
    GameplayModeContext context = SessionManager.getCurrentGameplayMode();
    if (context == null) {
        return;
    }
    if (oldMode == GameMode.LEVEL && newMode != GameMode.LEVEL) {
        context.markRewindBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
    } else if (oldMode != GameMode.LEVEL && newMode == GameMode.LEVEL) {
        context.markRewindBoundary(RewindBoundary.MODE_ENTER_REWINDABLE);
    }
}
```

For every committed game-mode transition in `GameLoop`, replace direct assignment with:

```java
setGameMode(newMode);
```

Apply this to the assignments found by:

```powershell
rg -n "currentGameMode\s*=" src/main/java/com/openggf/GameLoop.java
```

Required transition sites:

- `doEnterSpecialStage(...)`: transition from `LEVEL` to `SPECIAL_STAGE`.
- `doEnterBonusStage(...)`: use `setGameMode(...)` for the committed title-card or bonus transition, then explicitly mark a non-rewindable boundary after the bonus-zone `loadZoneAndAct(...)` path:

```java
GameplayModeContext context = SessionManager.getCurrentGameplayMode();
if (context != null) {
    context.markRewindBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
}
```

This explicit report is required because bonus entry can perform `LEVEL_LOAD` while the loop is already in title-card or bonus staging; a pure `LEVEL -> non-LEVEL` mode delta would miss the deterministic teardown after that non-level level load.
- `exitTitleCard()`: transition into `LEVEL` and transition into `BONUS_STAGE`.
- gameplay boot or level-select transition into `LEVEL`.
- transitions out to title/data/legal disclaimer modes.

Trace-session rule:

- Do not call `LiveRewindManager.markBoundary(...)` while `TraceSessionLauncher.active() != null`.
- Preserve `TraceSessionLauncher.recordExternalRewindFrameAtBoundary()` at seamless boundaries, because trace sessions own their own `RewindController`.

- [ ] Add or extend `src/test/java/com/openggf/TestGameLoopRewindBoundaryPolicy.java`.

Test cases:

```java
@Test
void traceSessionReporterDoesNotCallLiveManager() {
    // Install reporter through GameLoop helper with an active TraceSessionLauncher fixture.
    // Mark LEVEL_LOAD on the context.
    // Assert the spy LiveRewindManager receives zero boundary calls.
}

@Test
void levelToSpecialStageReportsModeExitBoundary() {
    // Drive the existing special-stage enter helper to its commit point.
    // Assert the context reporter receives MODE_EXIT_TO_NON_REWINDABLE.
}

@Test
void titleCardExitToLevelReportsModeEnterBoundary() {
    // Drive title-card exit into LEVEL.
    // Assert the context reporter receives MODE_ENTER_REWINDABLE.
}

@Test
void bonusStageEntryReportsModeExitAfterBonusLevelLoad() {
    // Drive the bonus-entry commit point far enough to execute the bonus-zone load.
    // Assert the context reporter receives LEVEL_LOAD followed by MODE_EXIT_TO_NON_REWINDABLE.
}
```

If `GameLoop` construction cannot be made small enough for unit tests, make `setGameMode(...)` and the explicit bonus-entry boundary helper package-private and test them directly from `src/test/java/com/openggf/TestGameLoopRewindBoundaryPolicy.java`.

Focused verification:

```powershell
mvn -Dmse=off "-Dtest=TestGameLoopRewindBoundaryPolicy" test
```

---

## Task 6: Add The Explicit Desync Regression Test

- [ ] Extend `src/test/java/com/openggf/game/rewind/TestLiveRewindBoundaryPolicy.java`.

Add:

```java
@Test
void levelLoadBoundaryLeavesControllerAndInputSourceMutuallyConsistent() {
    Fixture fixture = installLiveFixture();
    InputHandler input = new InputHandler();
    input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_PRESS);
    fixture.inputSource.appendFrame(input, config);
    fixture.controller.recordExternalStep();
    input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_RELEASE);
    input.handleKeyEvent(config.getInt(SonicConfiguration.RIGHT), GLFW_PRESS);
    fixture.inputSource.appendFrame(input, config);
    fixture.controller.recordExternalStep();
    fixture.inputSource.discardBefore(2);

    fixture.manager.markBoundary(RewindBoundary.LEVEL_LOAD);

    assertEquals(0, fixture.controller.currentFrame());
    assertEquals(0, fixture.inputSource.earliestFrame());
    assertEquals(1, fixture.inputSource.frameCount());
    assertTrue(fixture.controller.currentFrame() >= fixture.inputSource.earliestFrame());
    assertTrue(fixture.controller.currentFrame() < fixture.inputSource.frameCount());
}
```

This is the regression test that proves the stale `baseFrame` bug cannot return silently.

Focused verification:

```powershell
mvn -Dmse=off "-Dtest=TestLiveRewindBoundaryPolicy" test
```

---

## Task 7: Documentation And Changelog

- [ ] Update `CHANGELOG.md` with one LF-preserving entry under the unreleased section:

```markdown
- Added explicit rewind boundary reporting for level loads, seamless transitions, and gameplay mode changes so live rewind cannot cross session boundaries.
```

- [ ] Update `docs/architecture/designs/2026-06-26-rewind-boundary-policy-design.md` only if implementation names differ from the spec. Keep the architecture statements about trace no-op behavior, reporter ownership, and level-load/controller-input consistency aligned with the code.

Check `CHANGELOG.md` diff size:

```powershell
git diff --stat -- CHANGELOG.md
git diff --check -- CHANGELOG.md
```

Expected result: only the added changelog lines appear. A whole-file diff means line endings were changed and must be repaired before staging.

---

## Task 8: Full Verification Gate

Run focused tests:

```powershell
mvn -Dmse=off "-Dtest=TestGameplayModeContextRewindBoundaryReporter,TestLiveRewindInputSource,TestLiveRewindBoundaryPolicy,TestLevelManagerRewindBoundary,TestGameLoopRewindBoundaryPolicy,TestHeldRewindAudioRestoreDeferral,TestRewindController" test
```

Run the existing rewind hard gate:

```powershell
mvn -Dmse=off -Ds3k.rom.path="/c/Users/farre/IdeaProjects/sonic-engine/Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestEveryObjectRewindRoundTrip,TestRewindHarnessCoverageRatchet,TestScalarOnlyCodecDeletion,TestBossChildExactStateRewind,TestBossChildNoDoubleSpawnParity,TestAiz2ShipLoopRewindRoundTrip,TestRewindParityAgainstTrace,TestArchUnitRules,TestRewindArchitectureGuard,TestObjectServicesMigrationGuard,TestRewindCoverageGuard,TestRewindAcrossActBoundary,TestRewindDeathRespawnBoundary" test
```

Run diff hygiene checks:

```powershell
git status --short
git diff --check
git diff --stat
```

Stage only implementation files:

```powershell
git add src/main/java/com/openggf/game/rewind/RewindBoundary.java
git add src/main/java/com/openggf/game/rewind/RewindBoundaryReporter.java
git add src/main/java/com/openggf/game/rewind/LiveRewindInputSource.java
git add src/main/java/com/openggf/game/rewind/LiveRewindManager.java
git add src/main/java/com/openggf/game/session/GameplayModeContext.java
git add src/main/java/com/openggf/level/LevelManager.java
git add src/main/java/com/openggf/GameLoop.java
git add src/test/java/com/openggf/game/session/TestGameplayModeContextRewindBoundaryReporter.java
git add src/test/java/com/openggf/game/rewind/TestLiveRewindBoundaryPolicy.java
git add src/test/java/com/openggf/game/rewind/TestLiveRewindInputSource.java
git add src/test/java/com/openggf/level/TestLevelManagerRewindBoundary.java
git add src/test/java/com/openggf/TestGameLoopRewindBoundaryPolicy.java
git add CHANGELOG.md
git add docs/architecture/designs/2026-06-26-rewind-boundary-policy-design.md
```

Omit `docs/architecture/designs/2026-06-26-rewind-boundary-policy-design.md` from staging when unchanged.

Commit message:

```text
feat(rewind): enforce live rewind boundaries

Changelog: updated
Guide: n/a: boundary policy covered by rewind spec
Known-Discrepancies: n/a: no parity discrepancy changed
S3K-Known-Discrepancies: n/a: no S3K discrepancy changed
Agent-Docs: n/a: no agent workflow changed
Configuration-Docs: n/a: no configuration changed
Skills: n/a: no skill changed
```

---

## Self-Review Checklist

- [ ] `LevelManager` no longer calls `RewindController.resetToFrameZero()` directly.
- [ ] `GameLoop` reporter is the only live bridge from boundary reporting to `LiveRewindManager`.
- [ ] Trace sessions do not invoke `LiveRewindManager.markBoundary(...)`.
- [ ] `LEVEL_LOAD` leaves `controller.currentFrame()`, `inputSource.earliestFrame()`, and `inputSource.frameCount()` mutually consistent.
- [ ] `SEAMLESS_LEVEL_TRANSITION` blocks rewind across the transition at the current frame.
- [ ] Mode exit cleanup is deterministic and does not rely solely on the existing per-frame mode gate.
- [ ] No global object-reference or rewind-codec resolution semantics were changed.
- [ ] `CHANGELOG.md` diff is not a whole-file line-ending rewrite.
