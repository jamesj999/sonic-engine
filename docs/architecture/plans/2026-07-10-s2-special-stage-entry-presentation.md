# Sonic 2 Special-Stage Entry Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make normal Sonic 2 special-stage startup immediate while preserving trace-accurate bootstrap cadence, and start music only when the opaque entry transition begins revealing the complete stage.

**Architecture:** A call-scoped `SpecialStageStartupPolicy` selects fast or trace-accurate initialization. Sonic 2 fast-forwards through its existing manager update path to a provider-reported presentation-ready boundary. `GameLoop` owns the opaque hold, reveal direction, and music start; `FadeManager` supplies explicit white/black hold primitives.

**Tech Stack:** Java 21, JUnit 5, Mockito, Maven Surefire, existing `FadeManager`, `SpecialStageProvider`, Sonic 2 replay harness.

---

## File map

- Create `src/main/java/com/openggf/game/SpecialStageStartupPolicy.java`: call-scoped startup policy enum.
- Modify `src/main/java/com/openggf/game/SpecialStageProvider.java`: policy-aware initialization and presentation-readiness defaults.
- Modify `src/main/java/com/openggf/game/sonic2/Sonic2SpecialStageProvider.java`: Sonic 2 policy application and readiness.
- Modify `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`: bounded fast-forward and readiness query.
- Modify `src/main/java/com/openggf/graphics/FadeManager.java`: explicit opaque hold operations.
- Create `src/main/java/com/openggf/game/SpecialStageEntryPresentationController.java`: pending direction, opaque hold, and exactly-once reveal/music coordination.
- Modify `src/main/java/com/openggf/GameLoop.java`: own the focused presentation controller, add the policy-aware entry overload, and delegate readiness polling/cleanup without growing past its architectural budget.
- Modify `src/main/java/com/openggf/TraceSessionLauncher.java`: live trace requests accurate startup directly.
- Modify `src/test/java/com/openggf/tests/trace/s2/S2SpecialStageReplayHarness.java`: headless replay requests accurate startup directly.
- Create `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageStartupPolicyTest.java`: policy, guard, readiness, and lag independence.
- Modify `Sonic2SpecialStageBootstrapCadenceTest`, `Sonic2SpecialStagePreRollTest`, and `Sonic2SpecialStageSpawnGateTest`: declare trace-accurate startup explicitly.
- Modify `src/test/java/com/openggf/tests/graphics/FadeManagerTest.java`: opaque hold behavior.
- Create `src/test/java/com/openggf/TestGameLoopSpecialStageEntryPresentation.java`: shared entry ordering, deferred cover, audio, cleanup, S1/S3K regressions.
- Create `src/test/java/com/openggf/game/TestSpecialStageEntryPresentationController.java`: immediate/deferred controller behavior and idempotence.
- Modify `src/test/java/com/openggf/TestSpecialStageVisualTraceSession.java`: real live trace uses accurate startup and stays black until ready.
- Modify `CHANGELOG.md` and `README.md`: user-visible startup/reveal behavior.

### Task 1: Add explicit opaque fade holds

**Files:**
- Modify: `src/main/java/com/openggf/graphics/FadeManager.java`
- Test: `src/test/java/com/openggf/tests/graphics/FadeManagerTest.java`

- [ ] **Step 1: Write failing white/black hold tests**

Add tests which begin an active fade with a callback, force the opposite opaque hold, and prove state, color, callback cancellation, and indefinite stability:

```java
@Test
void holdBlackReplacesRunningRevealAndRemainsOpaque() {
    AtomicBoolean completed = new AtomicBoolean();
    fadeManager.startFadeFromBlack(() -> completed.set(true));

    fadeManager.holdBlack();
    for (int frame = 0; frame < 30; frame++) {
        fadeManager.update();
    }

    assertEquals(FadeState.HOLD_BLACK, fadeManager.getState());
    assertArrayEquals(new float[] {1f, 1f, 1f}, fadeManager.getFadeColor());
    assertFalse(fadeManager.hasPendingCompletion());
    assertFalse(completed.get());
}
```

Add the symmetric `holdWhiteReplacesRunningFadeAndRemainsOpaque` assertion with `FadeState.HOLD_WHITE`.

- [ ] **Step 2: Run the tests and verify RED**

Run: `mvn "-Dtest=com.openggf.tests.graphics.FadeManagerTest" test`

Expected: compilation failure because `holdBlack()` and `holdWhite()` do not exist.

- [ ] **Step 3: Implement explicit holds**

Add a private common initializer and two public methods:

```java
public void holdWhite() {
    holdOpaque(FadeState.HOLD_WHITE, FadeType.WHITE);
}

public void holdBlack() {
    holdOpaque(FadeState.HOLD_BLACK, FadeType.BLACK);
}

private void holdOpaque(FadeState holdState, FadeType type) {
    holdRestoredFrameForNextUpdate = false;
    state = holdState;
    fadeType = type;
    frameCount = 0;
    fadeR = 1f;
    fadeG = 1f;
    fadeB = 1f;
    fadeAlpha = type == FadeType.BLACK ? 1f : 0f;
    onFadeComplete = null;
    holdDuration = Integer.MAX_VALUE;
    holdFrameCount = 0;
}
```

Change both hold update methods to avoid increment overflow when `holdDuration == Integer.MAX_VALUE` by returning immediately for the indefinite hold.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `mvn "-Dtest=com.openggf.tests.graphics.FadeManagerTest" test`

Expected: all `FadeManagerTest` tests pass.

- [ ] **Step 5: Commit**

Stage only the two files and commit `feat(fade): add explicit opaque hold states` with all required trailers, including `Changelog: n/a: partial S2 entry fix documented at feature closeout`.

### Task 2: Add call-scoped startup policy and Sonic 2 fast initialization

**Files:**
- Create: `src/main/java/com/openggf/game/SpecialStageStartupPolicy.java`
- Modify: `src/main/java/com/openggf/game/SpecialStageProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2SpecialStageProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Create: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageStartupPolicyTest.java`
- Modify: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageBootstrapCadenceTest.java`
- Modify: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePreRollTest.java`
- Modify: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSpawnGateTest.java`

- [ ] **Step 1: Write failing startup-policy tests**

Create ROM-backed tests with the same fixture setup used by `Sonic2SpecialStageBootstrapCadenceTest`:

```java
@ParameterizedTest
@ValueSource(doubles = {0.0, 1.0})
void defaultInitializationFastForwardsToRevealRegardlessOfLag(double lagFactor) throws Exception {
    Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
    provider.setLagCompensation(lagFactor);
    provider.initializeStage(0);

    assertEquals(Sonic2SpecialStageIntro.Phase.FADE_FROM_WHITE,
            provider.getManager().getIntro().getCurrentPhase());
    assertTrue(provider.isEntryPresentationReady());
}

@ParameterizedTest
@ValueSource(doubles = {0.0, 1.0})
void accurateInitializationPreservesPreRollRegardlessOfLag(double lagFactor) throws Exception {
    Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();
    provider.setLagCompensation(lagFactor);
    provider.initializeStage(0, SpecialStageStartupPolicy.TRACE_ACCURATE);

    assertEquals(Sonic2SpecialStageIntro.Phase.PRE_ROLL,
            provider.getManager().getIntro().getCurrentPhase());
    assertFalse(provider.isEntryPresentationReady());
}
```

Add tests proving an accurate initialization followed by `reset()` and default initialization is fast, a null policy is rejected, `advanceToEntryPresentation(0)` reports `PRE_ROLL`, and calling fast-forward after reaching `FADE_FROM_WHITE` reports that phase.

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStageStartupPolicyTest" test`

Expected: compilation failure for missing policy/readiness/fast-forward APIs.

- [ ] **Step 3: Add the shared policy contract**

Create:

```java
package com.openggf.game;

public enum SpecialStageStartupPolicy {
    FAST,
    TRACE_ACCURATE
}
```

Add defaults to `SpecialStageProvider`:

```java
import java.util.Objects;

default void initializeStage(int stageIndex, SpecialStageStartupPolicy policy) throws IOException {
    Objects.requireNonNull(policy, "policy");
    initializeStage(stageIndex);
}

default boolean isEntryPresentationReady() {
    return true;
}
```

- [ ] **Step 4: Add bounded manager fast-forward**

Add `isEntryPresentationReady()`, a public no-argument fast-forward using a fixed guard, and a package-visible bounded overload:

```java
public boolean isEntryPresentationReady() {
    if (!initialized || intro == null) return false;
    return switch (intro.getCurrentPhase()) {
        case FADE_FROM_WHITE, DROP, WAIT1, WAIT2, MESSAGE_FLYOUT, GAMEPLAY -> true;
        case PRE_ROLL, ROM_STARTUP -> false;
    };
}

public void advanceToEntryPresentation() {
    advanceToEntryPresentation(256);
}

void advanceToEntryPresentation(int maxUpdates) {
    Sonic2SpecialStageIntro.Phase phase = intro.getCurrentPhase();
    if (phase != Sonic2SpecialStageIntro.Phase.PRE_ROLL
            && phase != Sonic2SpecialStageIntro.Phase.ROM_STARTUP) {
        throw new IllegalStateException("Cannot fast-forward special-stage startup from " + phase);
    }
    for (int update = 0; update < maxUpdates && !isEntryPresentationReady(); update++) {
        update();
    }
    if (!isEntryPresentationReady()) {
        throw new IllegalStateException("Special-stage startup did not reach reveal boundary from "
                + intro.getCurrentPhase() + " within " + maxUpdates + " updates");
    }
}
```

- [ ] **Step 5: Apply policy in the S2 provider**

Keep `initializeStage(int)` as `FAST`, override the policy-aware overload, and expose readiness:

```java
@Override
public void initializeStage(int stageIndex) throws IOException {
    initializeStage(stageIndex, SpecialStageStartupPolicy.FAST);
}

@Override
public void initializeStage(int stageIndex, SpecialStageStartupPolicy policy) throws IOException {
    Objects.requireNonNull(policy, "policy");
    manager.reset();
    manager.initialize(stageIndex);
    if (policy == SpecialStageStartupPolicy.FAST) {
        manager.advanceToEntryPresentation();
    }
}

@Override
public boolean isEntryPresentationReady() {
    return manager.isEntryPresentationReady();
}
```

- [ ] **Step 6: Mark bootstrap tests accurate**

Change only the fixture initialization in the three phase/cadence suites to:

```java
provider.initializeStage(0, SpecialStageStartupPolicy.TRACE_ACCURATE);
```

Do not change expected frame counts or state assertions.

- [ ] **Step 7: Run policy and existing cadence suites**

Run: `mvn "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStageStartupPolicyTest,com.openggf.game.sonic2.specialstage.Sonic2SpecialStageBootstrapCadenceTest,com.openggf.game.sonic2.specialstage.Sonic2SpecialStagePreRollTest,com.openggf.game.sonic2.specialstage.Sonic2SpecialStageSpawnGateTest" test`

Expected: all tests pass with no errors.

- [ ] **Step 8: Commit**

Stage only Task 2 files and commit `feat(s2ss): add fast and trace-accurate startup policies` with all required trailers, including `Changelog: n/a: partial S2 entry fix documented at feature closeout`.

### Task 3: Synchronize entry cover, reveal, music, and lifecycle in GameLoop

**Files:**
- Create: `src/main/java/com/openggf/game/SpecialStageEntryPresentationController.java`
- Create: `src/test/java/com/openggf/game/TestSpecialStageEntryPresentationController.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Create: `src/test/java/com/openggf/TestGameLoopSpecialStageEntryPresentation.java`
- Modify: `src/test/java/com/openggf/TestGameLoopSpecialStageRewindBoundary.java`
- Modify: `src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java` only if the measured effective `GameLoop` count exceeds 2985 after extraction.

- [ ] **Step 1: Write failing controller tests**

Use a minimal provider double with mutable readiness plus mocked `FadeManager` and `Runnable musicStart`. Cover:

- ready white and black presentation call music then the matching reveal immediately;
- not-ready white and black presentation call only the matching opaque hold;
- changing readiness and calling `update()` starts music/reveal exactly once;
- `clear()` prevents a later readiness change from starting presentation.

```java
controller.begin(provider, true, fade, musicStart);
verify(fade).holdBlack();
verifyNoInteractions(musicStart);

provider.ready = true;
controller.update(provider, fade, musicStart);
controller.update(provider, fade, musicStart);

InOrder order = inOrder(musicStart, fade);
order.verify(musicStart).run();
order.verify(fade).startFadeFromBlack(isNull());
verifyNoMoreInteractions(musicStart);
```

- [ ] **Step 2: Run controller tests and verify RED**

Run: `mvn "-Dtest=com.openggf.game.TestSpecialStageEntryPresentationController" test`

Expected: compilation failure because the controller does not exist.

- [ ] **Step 3: Implement the focused controller**

Create:

```java
public final class SpecialStageEntryPresentationController {
    private boolean pending;
    private boolean revealFromBlack;

    public void begin(SpecialStageProvider provider, boolean fromBlack,
                      FadeManager fade, Runnable musicStart) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(fade, "fade");
        Objects.requireNonNull(musicStart, "musicStart");
        clear();
        if (provider.isEntryPresentationReady()) {
            reveal(fromBlack, fade, musicStart);
            return;
        }
        pending = true;
        revealFromBlack = fromBlack;
        if (fromBlack) fade.holdBlack(); else fade.holdWhite();
    }

    public void update(SpecialStageProvider provider, FadeManager fade, Runnable musicStart) {
        if (pending && provider.isEntryPresentationReady()) {
            reveal(revealFromBlack, fade, musicStart);
        }
    }

    public void clear() {
        pending = false;
        revealFromBlack = false;
    }

    public boolean isPending() {
        return pending;
    }

    private void reveal(boolean fromBlack, FadeManager fade, Runnable musicStart) {
        pending = false;
        musicStart.run();
        if (fromBlack) fade.startFadeFromBlack(null); else fade.startFadeFromWhite(null);
    }
}
```

- [ ] **Step 4: Run controller tests and verify GREEN**

Run: `mvn "-Dtest=com.openggf.game.TestSpecialStageEntryPresentationController" test`

Expected: all controller tests pass.

- [ ] **Step 5: Write failing GameLoop integration and concrete-provider tests**

Build a provider double that records initialization policy. Inject mocked `FadeManager` and `AudioManager` using existing reflection helpers. Verify the normal overload passes `FAST`, immediate entry preserves initialize → music → reveal → mode/listener order, deferred entry enters the mode under an opaque hold, readiness polling starts presentation once, and leaving `SPECIAL_STAGE` clears pending presentation.

Use Mockito 5's configured final-class support for `Sonic1SpecialStageProvider`:

```java
Sonic1SpecialStageProvider s1 = spy(new Sonic1SpecialStageProvider());
doNothing().when(s1).initializeStage(anyInt(), eq(SpecialStageStartupPolicy.FAST));
```

Use the same `doNothing()` form for `Sonic3kSpecialStageProvider`. Verify S1 white and S3K white/black entries retain immediate initialization, music, reveal, mode change, and listener ordering. Assert on the fade mock—not the providers—that `holdWhite()` and `holdBlack()` were never called.

- [ ] **Step 6: Run GameLoop tests and verify RED**

Run: `mvn "-Dtest=com.openggf.TestGameLoopSpecialStageEntryPresentation" test`

Expected: failures because GameLoop does not own/delegate to the controller and has no policy-aware overload.

- [ ] **Step 7: Integrate the controller without growing GameLoop past its ratchet**

Add one owned collaborator:

```java
private final SpecialStageEntryPresentationController specialStageEntryPresentation =
        new SpecialStageEntryPresentationController();

void doEnterSpecialStage(SpecialStageProvider provider, int stageIndex, boolean fromBlack) {
    doEnterSpecialStage(provider, stageIndex, fromBlack, SpecialStageStartupPolicy.FAST);
}
```

The policy-aware overload calls `provider.initializeStage(stageIndex, policy)`. Replace the existing inline music/reveal block with:

```java
specialStageEntryPresentation.begin(provider, fromBlack, fadeManager,
        () -> playSpecialStageStageMusic(provider));
```

After the optional provider update/lag-skip block in `updateSpecialStageMode()` delegate readiness polling:

```java
specialStageEntryPresentation.update(ssProvider, fadeManager,
        () -> playSpecialStageStageMusic(ssProvider));
```

Call `specialStageEntryPresentation.clear()` on entry exceptions and in both mode-change helpers when leaving `SPECIAL_STAGE`. The controller owns idempotence; do not inspect audio state.

- [ ] **Step 8: Preserve rewind behavior and enforce architectural budget**

Retain `TestGameLoopSpecialStageRewindBoundary`'s immediate-provider `FADING_FROM_WHITE` frame-zero expectation. Add a test documenting that deferred presentation is used only with an active visual trace session, which suppresses special-stage rewind capture.

Run `mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard" test`. The focused controller should keep `GameLoop` at or below 2985 effective lines. If the guard reports a higher exact count despite extraction, remove redundant inline entry code before considering a ratchet change; any unavoidable change must set the guard to the reported exact count and add a dated justification naming the controller delegation.

- [ ] **Step 9: Run GameLoop entry, rewind, skip-gate, and architecture suites**

Run: `mvn "-Dtest=com.openggf.TestGameLoopSpecialStageEntryPresentation,com.openggf.TestGameLoopSpecialStageRewindBoundary,com.openggf.TestGameLoopSpecialStageRewindDebugBoundary,com.openggf.TestGameLoopSpecialStageSkipGate,com.openggf.TestGameLoopSpecialStageRewindGate,com.openggf.tests.TestArchitecturalSourceGuard" test`

Expected: all tests pass and the architecture guard reports no budget violation.

- [ ] **Step 10: Commit**

Stage only Task 3 files and commit `fix(special-stage): align entry reveal and music` with all required trailers, including `Changelog: n/a: partial S2 entry fix documented at feature closeout`.

### Task 4: Wire accurate policy into headless and live trace entry

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/s2/S2SpecialStageReplayHarness.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Create: `src/test/java/com/openggf/TestTraceSessionLauncherSpecialStageEntry.java`
- Modify: `src/test/java/com/openggf/TestSpecialStageVisualTraceSession.java`

- [ ] **Step 1: Write a failing production launcher-seam test**

Add a package-level test using mocked `GameLoop` and `SpecialStageProvider`:

```java
@Test
void traceEntryRequestsAccurateStartupBeforeDisablingNativeLag() {
    GameLoop loop = mock(GameLoop.class);
    SpecialStageProvider provider = mock(SpecialStageProvider.class);

    TraceSessionLauncher.enterSpecialStageTrace(loop, provider, 4);

    InOrder order = inOrder(loop, provider);
    order.verify(loop).doEnterSpecialStage(provider, 4, true,
            SpecialStageStartupPolicy.TRACE_ACCURATE);
    order.verify(provider).setLagCompensation(0);
}
```

- [ ] **Step 2: Run the launcher test and verify RED**

Run: `mvn "-Dtest=com.openggf.TestTraceSessionLauncherSpecialStageEntry" test`

Expected: compilation failure because the package-visible production seam does not exist.

- [ ] **Step 3: Add and use the production launcher seam**

Extract the existing entry lines into a package-visible static method and make `finishSpecialStageLaunch()` call it:

```java
static void enterSpecialStageTrace(GameLoop loop, SpecialStageProvider provider, int stageIndex) {
    loop.doEnterSpecialStage(provider, stageIndex, true,
            SpecialStageStartupPolicy.TRACE_ACCURATE);
    provider.setLagCompensation(0);
}
```

Replace the two inline calls in `finishSpecialStageLaunch()` with `enterSpecialStageTrace(loop, provider, index != null ? index : 0)`. This seam is the production path, not a parallel test helper.

- [ ] **Step 4: Update the live trace integration test**

Enter through the same production seam:

```java
TraceSessionLauncher.enterSpecialStageTrace(loop, provider, ssIndex);
```

Before stepping, assert the fade state is `HOLD_BLACK`, provider readiness is false, and stage music has not played. During the existing trace loop, detect the first readiness transition and assert fade state changes to `FADING_FROM_BLACK` on that same update. Retain all lag-row and finish-frame assertions.

- [ ] **Step 5: Run launcher and visual trace tests**

Run: `mvn "-Dtest=com.openggf.TestTraceSessionLauncherSpecialStageEntry,com.openggf.TestSpecialStageVisualTraceSession" test`

Expected: both pass; the visual test proves the production seam drives the real provider/GameLoop path.

- [ ] **Step 6: Wire the headless trace entry point**

In the headless harness:

```java
provider.initializeStage(specialStageIndex, SpecialStageStartupPolicy.TRACE_ACCURATE);
```

Update nearby Javadocs to state that startup accuracy and lag bypass are separate calls.

- [ ] **Step 7: Run trace replay, determinism, launcher, and live session tests**

Run: `mvn clean "-Dtest=com.openggf.tests.trace.s2.TestS2SpecialStageTraceReplay,com.openggf.tests.trace.s2.S2SpecialStageReplayDeterminismTest,com.openggf.TestTraceSessionLauncherSpecialStageEntry,com.openggf.TestSpecialStageVisualTraceSession" test`

Expected: replay remains 0 errors / 0 warnings, determinism JSON remains byte-identical, and live trace stays covered until readiness.

- [ ] **Step 8: Commit**

Stage only Task 4 files and commit `fix(trace): retain accurate S2 special-stage startup` with all required trailers, including `Changelog: n/a: partial S2 entry fix documented at feature closeout`.

### Task 5: Documentation, full verification, and review

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `README.md`

- [ ] **Step 1: Update user-facing documentation**

Add an Unreleased changelog entry explaining that normal S2 special-stage startup now compresses hidden ROM bootstrap, trace replay retains accurate cadence explicitly, the transition remains opaque until readiness, and music begins with the reveal. Update the existing README S2 Special Stage 1 closeout bullet with the same concise outcome.

- [ ] **Step 2: Run formatting and policy checks**

Run: `git diff --check`

Expected: no output and exit code 0.

- [ ] **Step 3: Run the full relevant test gate**

Run: `mvn clean "-Dtest=com.openggf.game.sonic2.specialstage.*Test,com.openggf.tests.graphics.FadeManagerTest,com.openggf.game.TestSpecialStageEntryPresentationController,com.openggf.TestGameLoop,com.openggf.TestGameLoopSpecialStageEntryPresentation,com.openggf.TestGameLoopSpecialStageRewindBoundary,com.openggf.TestGameLoopSpecialStageRewindDebugBoundary,com.openggf.TestGameLoopSpecialStageSkipGate,com.openggf.TestGameLoopSpecialStageRewindGate,com.openggf.TestTraceSessionLauncherSpecialStageEntry,com.openggf.TestSpecialStageVisualTraceSession,com.openggf.tests.trace.s2.TestS2SpecialStageTraceReplay,com.openggf.tests.trace.s2.S2SpecialStageReplayDeterminismTest,com.openggf.tests.TestArchitecturalSourceGuard" test`

Expected: zero failures and zero errors; trace replay remains green and deterministic.

- [ ] **Step 4: Request independent code review**

Review the complete diff against this plan and the design spec. Fix every Critical or Important issue, rerun affected tests, and repeat review until PASS.

- [ ] **Step 5: Commit final documentation/fixes**

Stage exact files only. Commit `docs(s2ss): document synchronized entry presentation` with `Changelog: updated` and all required trailers. Confirm `git status --short --branch` is clean.
