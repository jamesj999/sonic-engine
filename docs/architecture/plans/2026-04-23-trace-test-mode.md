# Trace Test Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Trace Test Mode specified in `docs/architecture/designs/2026-04-23-trace-test-mode-design.md` — a config-gated screen on top of the master title that lists every trace under `src/test/resources/traces/`, plays a selected trace inside the live graphical engine driven by its BK2, and overlays red/orange/grey divergence counters + a BK2 input visualiser + a rolling mismatch log. End-of-session uses a 1 s hold on `TRACE COMPLETE` then a `FadeManager` fade to black.

**Architecture:**
The trace infrastructure (`TraceData`, `TraceBinder`, `TraceReplayBootstrap`, etc.) moves from `src/test/java/com/openggf/tests/trace/` to `src/main/java/com/openggf/trace/` so it can be used at runtime. A new `TraceReplayFixture` interface abstracts the difference between the headless test fixture and the live engine. `TraceSessionLauncher` launches traces asynchronously through the existing master-title fade path: it requests the selected game, waits for `Engine.exitMasterTitleScreen(...)` to finish creating the gameplay runtime, then loads the trace's zone/act, runs the shared replay bootstrap, and only then attaches `LiveTraceComparator` + `PlaybackDebugManager` for live playback. The UI sits in `com.openggf.testmode` (picker + HUD), and the HUD is rendered from `Engine.display()` after the fade pass so it remains visible during the completion fade.

**Tech Stack:** Java 21, JUnit 5 / Jupiter (JUnit 4 is forbidden — CLAUDE.md), LWJGL/GLFW, existing `MasterTitleScreen`, `GameLoop`, `FadeManager`, `PlaybackDebugManager`, `HeadlessTestFixture`, `TraceBinder`. Maven build (`mvn package` / `mvn test`).

**Spec reference:** every task below points to the relevant section of
`docs/architecture/designs/2026-04-23-trace-test-mode-design.md`.

**Branching:** work on a branch matching `feature/ai-*` per CLAUDE.md. Commit trailers (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`) are auto-appended by `.githooks/prepare-commit-msg`; fill them in, do not remove them. Set `core.hooksPath .githooks` once.

---

## File map

### Created
- `src/main/java/com/openggf/trace/` — new package tree
  - `TraceData.java`, `TraceFrame.java`, `TraceCharacterState.java`, `TraceMetadata.java`, `TraceEvent.java`, `TraceEventFormatter.java`, `TraceExecutionPhase.java`, `TraceExecutionModel.java`, `TraceHistoryHydration.java` (moved)
  - `TraceBinder.java`, `ToleranceConfig.java`, `FieldComparison.java`, `FrameComparison.java`, `DivergenceGroup.java`, `DivergenceReport.java`, `Severity.java` (moved)
  - `TraceObjectSnapshotBinder.java`, `TraceReplayBootstrap.java` (moved)
  - `EngineDiagnostics.java`, `EngineNearbyObject.java`, `EngineNearbyObjectFormatter.java`, `TouchResponseDebugHitFormatter.java` (moved)
- `src/main/java/com/openggf/trace/catalog/TraceCatalog.java`
- `src/main/java/com/openggf/trace/catalog/TraceEntry.java`
- `src/main/java/com/openggf/trace/replay/TraceReplayFixture.java`
- `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`
- `src/main/java/com/openggf/trace/live/LiveTraceComparator.java`
- `src/main/java/com/openggf/trace/live/MismatchEntry.java`
- `src/main/java/com/openggf/trace/live/MismatchRingBuffer.java`
- `src/main/java/com/openggf/testmode/TestModeTracePicker.java`
- `src/main/java/com/openggf/testmode/TraceHudOverlay.java`
- `src/main/java/com/openggf/TraceSessionLauncher.java`
- `src/test/java/com/openggf/trace/catalog/TraceCatalogTest.java`
- `src/test/java/com/openggf/trace/live/LiveTraceComparatorTest.java`
- `src/test/java/com/openggf/trace/live/MismatchRingBufferTest.java`

### Modified
- `src/main/java/com/openggf/configuration/SonicConfiguration.java` — add `TEST_MODE_ENABLED`, `TRACE_CATALOG_DIR`
- `src/main/resources/config.json` — add defaults for those keys
- `src/main/java/com/openggf/game/MasterTitleScreen.java` — add public `selectEntry(GameEntry)` and `TEST_MODE_ENABLED` branch
- `src/main/java/com/openggf/GameLoop.java` — add package-private `launchGameByEntry(GameEntry, Runnable)` wrapper, pending post-exit callback, skip-tick query integration, and master-title return hook
- `src/main/java/com/openggf/debug/playback/PlaybackDebugManager.java` — `PlaybackFrameObserver` interface, `startSession`, `endSession`, bootstrap cursor helpers for `TraceReplayFixture`, `shouldSkipCurrentGameplayTick`, observer hook in `onLevelFrameAdvanced`
- `src/main/java/com/openggf/Engine.java` — add static `currentGameLoop()` accessor, recreate master title on trace-session teardown, and render the trace HUD after the fade pass
- `src/test/java/com/openggf/tests/HeadlessTestFixture.java` — implement `TraceReplayFixture`
- `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java` — use `TraceReplaySessionBootstrap` + new imports; mechanical import updates from the package move
- Every file in `src/test/` that imports from `com.openggf.tests.trace.*` — import updates (Phase 1 Task 1)

---

## Phase 1 — Move trace infrastructure from src/test to src/main

### Task 1: Move trace package and update all consumers

**Files:**
- Create: `src/main/java/com/openggf/trace/` (directory) and all subpackages listed in file map
- Move (one by one): the 22 source files listed in the spec section 7
- Modify: every `src/test/java/com/openggf/tests/trace/` consumer and every other `src/test/` file that imports `com.openggf.tests.trace.*`

> **Why this is a single "task":** the package move is mechanical and must land atomically — every cross-file import is broken until the last rename is done. Land it in one commit so CI never sees a half-state. (Spec §7.)

- [ ] **Step 1: Create a feature branch**

Run:
```powershell
git checkout develop
git pull
git checkout -b feature/ai-trace-test-mode
git config core.hooksPath .githooks
```

- [ ] **Step 2: Confirm baseline tests pass before moving anything**

Run: `mvn -q test -Dtest='Test*Trace*' -Dmse=off`
Expected: tests pass (or skip cleanly without a ROM). If failures exist, stop and resolve before refactoring.

- [ ] **Step 3: Create the new package directories**

Run:
```powershell
New-Item -ItemType Directory -Force 'src/main/java/com/openggf/trace' | Out-Null
New-Item -ItemType Directory -Force 'src/main/java/com/openggf/trace/catalog' | Out-Null
New-Item -ItemType Directory -Force 'src/main/java/com/openggf/trace/replay' | Out-Null
New-Item -ItemType Directory -Force 'src/main/java/com/openggf/trace/live' | Out-Null
```

- [ ] **Step 4: Move the 22 source files (`git mv` preserves history)**

Run:
```powershell
$traceFiles = @(
  'TraceData',
  'TraceFrame',
  'TraceCharacterState',
  'TraceMetadata',
  'TraceEvent',
  'TraceEventFormatter',
  'TraceExecutionPhase',
  'TraceExecutionModel',
  'TraceHistoryHydration',
  'TraceBinder',
  'ToleranceConfig',
  'FieldComparison',
  'FrameComparison',
  'DivergenceGroup',
  'DivergenceReport',
  'Severity',
  'TraceObjectSnapshotBinder',
  'TraceReplayBootstrap',
  'EngineDiagnostics',
  'EngineNearbyObject',
  'EngineNearbyObjectFormatter',
  'TouchResponseDebugHitFormatter'
)

foreach ($name in $traceFiles) {
  git mv "src/test/java/com/openggf/tests/trace/$name.java" `
         "src/main/java/com/openggf/trace/$name.java"
}
```

- [ ] **Step 5: Rewrite package declarations in the moved files**

Run:
```powershell
Get-ChildItem 'src/main/java/com/openggf/trace' -Filter '*.java' | ForEach-Object {
  $text = Get-Content -Raw $_.FullName
  $text = $text.Replace('package com.openggf.tests.trace;', 'package com.openggf.trace;')
  Set-Content -NoNewline $_.FullName $text
}
```

Verify:
```powershell
git grep -n 'package com.openggf.tests.trace' src/main/java/com/openggf/trace/
```
Expected: no results.

- [ ] **Step 6: Rewrite imports across src/test/**

Run:
```powershell
Get-ChildItem 'src/test/java' -Recurse -Filter '*.java' | ForEach-Object {
  $text = Get-Content -Raw $_.FullName
  $text = $text.Replace('com.openggf.tests.trace.', 'com.openggf.trace.')
  Set-Content -NoNewline $_.FullName $text
}
```

- [ ] **Step 7: Rewrite intra-package references inside the moved files themselves**

The moved files import each other via fully qualified names in places. Run:
```powershell
Get-ChildItem 'src/main/java/com/openggf/trace' -Filter '*.java' | ForEach-Object {
  $text = Get-Content -Raw $_.FullName
  $text = $text.Replace('com.openggf.tests.trace.', 'com.openggf.trace.')
  Set-Content -NoNewline $_.FullName $text
}
```

- [ ] **Step 8: Compile**

Run: `mvn -q -o compile -Dmse=off`
Expected: BUILD SUCCESS. If any file still references `com.openggf.tests.trace.*`, fix it in place and rerun — common stragglers are classes like `DebugS1Ghz1RingParity` in `src/test/java/com/openggf/tests/trace/` that were **not** moved and import siblings from the moved set.

- [ ] **Step 9: Run the full trace test suite**

Run: `mvn -q test -Dtest='Test*Trace*,TestS2*Trace*,TestS3k*Trace*,TestTrace*,TestDivergenceReport,TestEngineNearbyObjectFormatter,TestTouchResponseDebugHitFormatter,TestTraceBinder,TestTraceDataParsing,TestTraceExecutionModel,TestTraceHistoryHydration,TestTraceObjectSnapshotBinder,TestTraceRecorderCounterAddresses,TestTraceReplayStartPositionPolicy,TestTraceEventFormatting,TestRomObjectSnapshot,TestS2SyntheticV3Fixture,TestS3kSyntheticV3Fixture' -Dmse=off`
Expected: same pass/skip set as Step 2.

- [ ] **Step 10: Commit the move**

Run:
```powershell
git add -A src/main/java/com/openggf/trace src/test/java
$message = @'
refactor(trace): move trace infrastructure from src/test to src/main

Enables runtime use of TraceData, TraceBinder, TraceReplayBootstrap,
TraceObjectSnapshotBinder, and their data/diagnostic classes by the
upcoming TraceSessionLauncher. Package renamed from
com.openggf.tests.trace → com.openggf.trace. Pure move; no behaviour
change.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

---

## Phase 2 — TraceReplayFixture abstraction

### Task 2: Introduce the TraceReplayFixture interface

**Files:**
- Create: `src/main/java/com/openggf/trace/replay/TraceReplayFixture.java`

Spec §6.1.

- [ ] **Step 1: Create the interface**

```java
package com.openggf.trace.replay;

import com.openggf.game.GameRuntime;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Narrow view of a fixture capable of driving trace replay. Implemented
 * by {@code HeadlessTestFixture} in tests and by the live launcher's
 * internal adapter at runtime.
 */
public interface TraceReplayFixture {
    AbstractPlayableSprite sprite();
    GameRuntime runtime();
    /** Run one gameplay tick using the next BK2 input. Returns the mask. */
    int stepFrameFromRecording();
    /** Advance BK2 without stepping gameplay (lag frame). Returns the mask. */
    int skipFrameFromRecording();
    /** Advance the BK2 cursor by N frames, no gameplay ticks. */
    void advanceRecordingCursor(int frameCount);
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q -o compile -Dmse=off`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/openggf/trace/replay/TraceReplayFixture.java
$message = @'
feat(trace): TraceReplayFixture interface for headless + live replay

Abstracts the five methods (sprite, runtime, step, skip,
advanceCursor) that TraceReplayBootstrap already calls on
HeadlessTestFixture, so a live engine adapter can implement the same
contract for the upcoming TraceSessionLauncher.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

### Task 3: Make HeadlessTestFixture implement TraceReplayFixture

**Files:**
- Modify: `src/test/java/com/openggf/tests/HeadlessTestFixture.java`

- [ ] **Step 1: Add `implements TraceReplayFixture`**

Locate the class declaration (around line 26):
```java
public final class HeadlessTestFixture {
```
Change to:
```java
public final class HeadlessTestFixture implements com.openggf.trace.replay.TraceReplayFixture {
```

The required methods (`sprite()`, `runtime()`, `stepFrameFromRecording()`, `skipFrameFromRecording()`, `advanceRecordingCursor(int)`) already exist on the class with matching signatures — no new methods needed. If any signature mismatch is flagged by the compiler, fix by aligning the existing method to the interface (not the other way around).

- [ ] **Step 2: Compile and run all trace tests**

Run: `mvn -q test -Dtest='Test*Trace*' -Dmse=off`
Expected: same pass/skip set as before.

- [ ] **Step 3: Commit**

```powershell
git add src/test/java/com/openggf/tests/HeadlessTestFixture.java
$message = @'
refactor(tests): HeadlessTestFixture implements TraceReplayFixture

Pure declaration change — existing method signatures already match the
interface. Lets TraceReplayBootstrap be rewritten against the interface
in a follow-up commit.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

### Task 4: Rewrite TraceReplayBootstrap signatures against the interface

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceReplayBootstrap.java`

Spec §6.1.

- [ ] **Step 1: Change fixture-typed parameters**

In `TraceReplayBootstrap.java`, replace every occurrence of `HeadlessTestFixture fixture` (as a parameter type) with `TraceReplayFixture fixture`. Add the import:
```java
import com.openggf.trace.replay.TraceReplayFixture;
```
Remove the now-unused import:
```java
import com.openggf.tests.HeadlessTestFixture;
```

Method signatures to update (verify all present via `git grep 'HeadlessTestFixture' src/main/java/com/openggf/trace/TraceReplayBootstrap.java`):
- `applyPreTraceState(TraceData, TraceReplayFixture)`
- `applyReplayStartState(TraceData, TraceReplayFixture)` (both overloads)
- `applyReplayStartStateForTraceReplay(TraceData, TraceReplayFixture)`
- `applySeedReplayStartStateForTraceReplay(TraceData, TraceReplayFixture)`
- `capturePrimaryReplayStateForComparison(TraceData, TraceFrame, ?)` if it takes a fixture
- any private helpers called by the above

- [ ] **Step 2: Verify no residual `HeadlessTestFixture` references in the moved file**

Run: `git grep -n 'HeadlessTestFixture' src/main/java/com/openggf/trace/TraceReplayBootstrap.java`
Expected: empty output.

- [ ] **Step 3: Compile and run trace tests**

Run: `mvn -q test -Dtest='Test*Trace*' -Dmse=off`
Expected: same pass/skip set as before. The fixture widens from a concrete class to an interface — call sites in `AbstractTraceReplayTest` still pass the concrete `HeadlessTestFixture` (which now implements the interface).

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/openggf/trace/TraceReplayBootstrap.java
$message = @'
refactor(trace): TraceReplayBootstrap takes TraceReplayFixture

Widens every bootstrap-entrypoint signature from the test-only
HeadlessTestFixture to the new TraceReplayFixture interface. Existing
call sites (AbstractTraceReplayTest et al.) keep working because
HeadlessTestFixture now implements the interface.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

---

## Phase 3 — Extract TraceReplaySessionBootstrap

### Task 5: Create TraceReplaySessionBootstrap static helper

**Files:**
- Create: `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`

Spec §6 steps 3+; §6.1.

- [ ] **Step 1: Create the class with the two public static entrypoints**

```java
package com.openggf.trace.replay;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.OscillationManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceObjectSnapshotBinder;
import com.openggf.trace.TraceReplayBootstrap;

/**
 * Headless and live trace replay share the same pre-gameplay setup:
 * configure team, load level, seed vblank counter, pre-advance
 * oscillation, apply pre-trace object snapshots and replay start
 * state. This helper owns that sequence so {@code AbstractTraceReplayTest}
 * and {@code TraceSessionLauncher} stay consistent.
 */
public final class TraceReplaySessionBootstrap {
    private TraceReplaySessionBootstrap() {}

    /**
     * Prepare configuration state that must be set before the level
     * is loaded. Call before the caller loads the level.
     */
    public static void prepareConfiguration(TraceData trace, TraceMetadata meta) {
        applyRecordedTeamConfig(meta);
        if (TraceReplayBootstrap.requiresFreshLevelLoadForTraceReplay(trace)
                && "s3k".equals(meta.game())) {
            SonicConfigurationService.getInstance()
                    .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        }
    }

    /**
     * Apply pre-gameplay replay state to an already-loaded level.
     * Must be called after the level has been loaded and a player
     * sprite exists on the runtime.
     */
    public static BootstrapResult applyBootstrap(TraceData trace, TraceReplayFixture fixture,
                                                 int preTraceOscOverride) {
        TraceMetadata meta = trace.metadata();
        ObjectManager om = GameServices.level().getObjectManager();
        if (om != null && TraceReplayBootstrap.shouldUseTraceStartBootstrapForTraceReplay(trace)) {
            om.initVblaCounter(
                    TraceReplayBootstrap.initialVblankCounterForTraceReplay(trace) - 1);
        }

        int preTraceOsc = preTraceOscOverride >= 0
                ? preTraceOscOverride
                : meta.preTraceOscillationFrames();
        if (TraceReplayBootstrap.shouldUseTraceStartBootstrapForTraceReplay(trace)
                && preTraceOsc > 0) {
            for (int i = 0; i < preTraceOsc; i++) {
                OscillationManager.update(-(preTraceOsc - i));
            }
        }

        TraceObjectSnapshotBinder.Result hydration =
                TraceReplayBootstrap.applyPreTraceState(trace, fixture);
        TraceReplayBootstrap.ReplayStartState replayStart =
                TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture);
        return new BootstrapResult(hydration, replayStart);
    }

    public record BootstrapResult(
            TraceObjectSnapshotBinder.Result hydration,
            TraceReplayBootstrap.ReplayStartState replayStart) {}

    private static void applyRecordedTeamConfig(TraceMetadata meta) {
        if (!meta.hasRecordedTeam()) {
            return;
        }
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                meta.recordedMainCharacter() == null ? "sonic" : meta.recordedMainCharacter());
        config.setConfigValue(
                SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                String.join(",", meta.recordedSidekicks()));
    }
}
```

> **Note on `shouldUseTraceStartBootstrapForTraceReplay`:** if that method doesn't yet exist on `TraceReplayBootstrap` but the test uses `shouldUseTraceStartBootstrap(trace)` (private in `AbstractTraceReplayTest`), promote that helper to a public static on `TraceReplayBootstrap` first. Verify with `git grep -n 'shouldUseTraceStartBootstrap' src/`.

- [ ] **Step 2: Compile**

Run: `mvn -q -o compile -Dmse=off`
Expected: BUILD SUCCESS. If `shouldUseTraceStartBootstrapForTraceReplay` (or the renamed helper) is missing, create it in `TraceReplayBootstrap` by moving the body of `AbstractTraceReplayTest.shouldUseTraceStartBootstrap` across.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java src/main/java/com/openggf/trace/TraceReplayBootstrap.java
$message = @'
feat(trace): TraceReplaySessionBootstrap helper for shared setup

Extracts the config + vblank + oscillation + snapshot + replay-start
sequence shared by AbstractTraceReplayTest and the upcoming
TraceSessionLauncher. Test continues to orchestrate JUnit/assertions;
the helper owns the deterministic setup.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

### Task 6: Migrate AbstractTraceReplayTest to use the helper

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`

- [ ] **Step 1: Replace the inline bootstrap block**

Inside `replayMatchesTrace()`, find the region from `// 4a. ROM parity: Initialize ObjectManager's frame counter ...` through the `applyReplayStartStateForTraceReplay` call (~lines 123-168). Replace with:

```java
// 4a. Shared replay bootstrap (vblank seed, oscillation pre-advance,
//     object-snapshot hydration, replay start state).
TraceReplaySessionBootstrap.BootstrapResult boot =
        TraceReplaySessionBootstrap.applyBootstrap(trace, fixture,
                overridePreTraceOscFrames());
TraceObjectSnapshotBinder.Result hydration = boot.hydration();
TraceReplayBootstrap.ReplayStartState replayStart = boot.replayStart();
List<TraceEvent.ObjectStateSnapshot> preTraceSnapshots =
        trace.preTraceObjectSnapshots();
if (!preTraceSnapshots.isEmpty() && GameServices.level().getObjectManager() != null) {
    System.out.printf(
            "Hydrated %d/%d pre-trace object snapshots (%d warnings)%n",
            hydration.matched(), hydration.attempted(),
            hydration.warnings().size());
    for (String warning : hydration.warnings()) {
        System.out.println("  WARN: " + warning);
    }
}
```

Replace the earlier `applyRecordedTeamConfig(meta)` + `S3K_SKIP_INTROS` block (~lines 98-102) with:

```java
TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);
```

Add imports:
```java
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
```

Remove the now-unused `private void applyRecordedTeamConfig(TraceMetadata meta)` helper and any now-unused imports.

- [ ] **Step 2: Run every trace test**

Run: `mvn -q test -Dtest='Test*Trace*,TestS2*Trace*,TestS3k*Trace*' -Dmse=off`
Expected: same pass/skip set as before the refactor.

- [ ] **Step 3: Commit**

```powershell
git add src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java
$message = @'
refactor(tests): AbstractTraceReplayTest uses TraceReplaySessionBootstrap

Pulls the config + vblank + osc + snapshot + replay-start sequence into
the shared helper. Test now focuses on JUnit orchestration, leaving the
deterministic setup to code shared with the live launcher.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

---

## Phase 4 — PlaybackDebugManager extensions

### Task 7: Add PlaybackFrameObserver interface + observer slot

**Files:**
- Modify: `src/main/java/com/openggf/debug/playback/PlaybackDebugManager.java`

Spec §6.3.

- [ ] **Step 1: Declare the interface and an observer field**

Near the top of `PlaybackDebugManager`, after the existing fields, add:

```java
/**
 * Observer hook that lets an external comparator classify each BK2
 * frame as gameplay or lag and accumulate results after each tick.
 * Null observer → no gating and no callbacks (normal BK2 playback).
 */
public interface PlaybackFrameObserver {
    boolean shouldSkipGameplayTick(Bk2FrameInput frame);
    void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped);
}

private PlaybackFrameObserver frameObserver;
private boolean currentTickSuppressed;
```

Add a synchronised setter:
```java
public synchronized void setFrameObserver(PlaybackFrameObserver observer) {
    this.frameObserver = observer;
}
```

- [ ] **Step 2: Compile**

Run: `mvn -q -o compile -Dmse=off`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/openggf/debug/playback/PlaybackDebugManager.java
$message = @'
feat(playback): PlaybackFrameObserver hook in PlaybackDebugManager

Adds a nullable observer slot with shouldSkipGameplayTick and
afterFrameAdvanced callbacks. Consumed by the upcoming
LiveTraceComparator; no behaviour change when no observer is set.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

### Task 8: Add startSession / endSession + bootstrap cursor helpers + shouldSkipCurrentGameplayTick

**Files:**
- Modify: `src/main/java/com/openggf/debug/playback/PlaybackDebugManager.java`

Spec §6.2, §6.3.

- [ ] **Step 1: Add startSession / endSession + bootstrap cursor helpers**

```java
public synchronized void startSession(Bk2Movie movie, int startOffsetIndex) {
    this.movie = movie;
    this.timeline = new PlaybackTimelineController(movie.getFrameCount());
    this.firstActiveFrame = findFirstActiveFrame(movie);
    this.timeline.resetTo(Math.max(0, startOffsetIndex));
    this.periodicLogCounter = 0;
    this.enabled = true;
    this.timeline.setPlaying(true);
    clearLastAppliedState();
    setStatus("Session started (" + movie.getFrameCount() + " frames)", true);
}

public synchronized void endSession() {
    if (timeline != null) {
        timeline.setPlaying(false);
    }
    this.enabled = false;
    this.movie = null;
    this.timeline = null;
    this.firstActiveFrame = -1;
    this.frameObserver = null;
    this.currentTickSuppressed = false;
    clearLastAppliedState();
    setStatus("Session ended", true);
}

public synchronized Bk2FrameInput currentFrameOrThrow() {
    if (!enabled || movie == null || timeline == null) {
        throw new IllegalStateException("No active playback session");
    }
    return movie.getFrame(timeline.getCursorFrame());
}

public synchronized void advanceCurrentFrameWithoutGameplay() {
    if (!enabled || movie == null || timeline == null) {
        return;
    }
    currentTickSuppressed = false;
    timeline.advanceIfPlaying();
}
```

- [ ] **Step 2: Add shouldSkipCurrentGameplayTick**

```java
/**
 * Called by {@link com.openggf.GameLoop} immediately before the LEVEL
 * mode gameplay tick. Returns true when the attached observer wants
 * the tick suppressed (ROM lag frame). The BK2 cursor still advances
 * via {@link #onLevelFrameAdvanced()}.
 */
public synchronized boolean shouldSkipCurrentGameplayTick() {
    if (!enabled || movie == null || timeline == null || frameObserver == null) {
        currentTickSuppressed = false;
        return false;
    }
    Bk2FrameInput frame = movie.getFrame(timeline.getCursorFrame());
    currentTickSuppressed = frameObserver.shouldSkipGameplayTick(frame);
    return currentTickSuppressed;
}
```

- [ ] **Step 3: Invoke afterFrameAdvanced from onLevelFrameAdvanced**

Modify the existing `onLevelFrameAdvanced()` (around line 152). Before `timeline.advanceIfPlaying()`, capture the pre-advance frame for the observer, then fire the callback after advance:

```java
public synchronized void onLevelFrameAdvanced() {
    if (!enabled || movie == null || timeline == null) {
        currentTickSuppressed = false;
        return;
    }
    Bk2FrameInput beforeFrame = movie.getFrame(timeline.getCursorFrame());
    boolean wasSuppressed = currentTickSuppressed;
    currentTickSuppressed = false;
    timeline.advanceIfPlaying();
    if (frameObserver != null) {
        frameObserver.afterFrameAdvanced(beforeFrame, wasSuppressed);
    }
    if (timeline.isPlaying()) {
        periodicLogCounter++;
        if (periodicLogCounter >= PERIODIC_LOG_INTERVAL_FRAMES) {
            periodicLogCounter = 0;
            logStatus("tick");
        }
    } else {
        periodicLogCounter = 0;
    }
}
```

- [ ] **Step 4: Compile + existing playback tests**

Run: `mvn -q test -Dtest='Test*Playback*,Test*Bk2*' -Dmse=off`
Expected: pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/debug/playback/PlaybackDebugManager.java
$message = @'
feat(playback): programmatic startSession + lag-frame gating hooks

Adds startSession(Bk2Movie, int)/endSession() so TraceSessionLauncher
can drive playback without the configured hotkey path, bootstrap cursor
helpers for the live TraceReplayFixture, and
shouldSkipCurrentGameplayTick() + afterFrameAdvanced callback so an
attached observer can suppress gameplay ticks on ROM lag frames.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

### Task 9: Gate gameplay tick in GameLoop.update()

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`

Spec §6.3.

- [ ] **Step 1: Read the current GameLoop update path**

In `src/main/java/com/openggf/GameLoop.java`, search for
`playbackDebugManager.onLevelFrameAdvanced()`. As of this codebase the
call lives inside the `LevelFrameStep.execute(...)` lambda (roughly
lines 694-705) and is guarded by `if (playbackFrameConsumed)`:

```java
if (!freezeForArtViewer && !freezeForSpecialStage && !freezeForBonusStage && !freezeForZoneActTransition) {
    LevelFrameStep.execute(levelManager, camera, () -> {
        spriteManager.update(inputHandler);
        if (playbackFrameConsumed) {
            playbackDebugManager.onLevelFrameAdvanced();
        }
    }, ...);
    ...
}
```

Two layered problems for the lag gate:
- `LevelFrameStep.execute` runs the gameplay body. If we skip it, the
  inner `onLevelFrameAdvanced()` never fires.
- The `if (playbackFrameConsumed)` guard suppresses the callback on
  frames where no playback input was consumed — which happens on
  skipped (lag-gated) frames by definition.

- [ ] **Step 2: Restructure the LEVEL dispatch**

Replace the lambda body and outer freeze-guard block with:

```java
if (!freezeForArtViewer && !freezeForSpecialStage && !freezeForBonusStage && !freezeForZoneActTransition) {
    boolean skipGameplay = playbackDebugManager.shouldSkipCurrentGameplayTick();
    if (!skipGameplay) {
        LevelFrameStep.execute(levelManager, camera, () -> spriteManager.update(inputHandler),
            (name, step) -> { profiler.beginSection(name); step.run(); profiler.endSection(name); });
    }
    // Fire the BK2-advance callback either way — on both real gameplay
    // ticks and lag-gated skips — so the observer's cursor always
    // matches the BK2 cursor. PlaybackDebugManager.onLevelFrameAdvanced
    // is already a no-op when no session is active, so removing the
    // existing `playbackFrameConsumed` guard is safe.
    playbackDebugManager.onLevelFrameAdvanced();
    // existing special-stage / bonus-stage / zone-act request handling
    // stays here, below the callback, unchanged.
}
// Freeze path (any of the freeze flags true): neither gameplay nor the
// BK2 cursor advances. This is intentional — a user with the art viewer
// open should be able to pause trace playback without drifting the
// comparator.
```

Rendering and input handling stay outside this block; they keep
running each frame so the window remains responsive.

- [ ] **Step 3: Verify trace tests still pass**

Run: `mvn -q test -Dtest='Test*Trace*' -Dmse=off`
Expected: pass. (No observer is set, so skip check returns false and behaviour is unchanged.)

- [ ] **Step 4: Commit**

```powershell
git add src/main/java/com/openggf/GameLoop.java
$message = @'
feat(gameloop): consult PlaybackDebugManager skip gate before LEVEL tick

Wraps the gameplay update path with a shouldSkipCurrentGameplayTick
check so an attached LiveTraceComparator can suppress ticks on ROM lag
frames. Rendering and input handling still run each frame; the BK2
cursor still advances.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

---

## Phase 5 — Config keys

### Task 10: Add TEST_MODE_ENABLED and TRACE_CATALOG_DIR

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/resources/config.json`

Spec §5.1, §11.

- [ ] **Step 1: Add the enum values**

In `SonicConfiguration.java`, near the existing `CROSS_GAME_FEATURES_ENABLED` entry, add:

```java
/** When true, the master title screen becomes the trace picker. Dev-only. */
TEST_MODE_ENABLED,

/** Directory scanned by TraceCatalog. Resolved against user.dir. */
TRACE_CATALOG_DIR,
```

- [ ] **Step 2: Add defaults to config.json**

In `src/main/resources/config.json`, append before the closing brace:

```json
  "TEST_MODE_ENABLED": false,
  "TRACE_CATALOG_DIR": "src/test/resources/traces",
```

(Ensure the preceding line has a trailing comma.)

- [ ] **Step 3: Compile**

Run: `mvn -q -o compile -Dmse=off`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Verify defaults load**

Run: `mvn -q test -Dtest='TestSonicConfiguration*' -Dmse=off`
Expected: pass (if the test class exists; otherwise skip).

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/resources/config.json
$message = @'
feat(config): TEST_MODE_ENABLED and TRACE_CATALOG_DIR keys

Dev-only toggle for the upcoming Trace Test Mode screen and the
directory it scans (defaults to src/test/resources/traces).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: updated
Skills: n/a
'@
git commit -m $message
```

> **Note:** `Configuration-Docs: updated` trailer requires updating `CONFIGURATION.md`. Do that as part of this commit — add a one-line row describing each new key.

---

## Phase 6 — TraceCatalog

### Task 11: TraceEntry record

**Files:**
- Create: `src/main/java/com/openggf/trace/catalog/TraceEntry.java`

Spec §4.3.

- [ ] **Step 1: Write the record**

```java
package com.openggf.trace.catalog;

import com.openggf.game.save.SelectedTeam;
import com.openggf.trace.TraceMetadata;

import java.nio.file.Path;

/**
 * One trace directory scanned by {@link TraceCatalog}. Constructed from
 * {@code metadata.json} + {@code physics.csv} row count + BK2 path.
 */
public record TraceEntry(
        Path dir,
        String gameId,
        int zone,
        int act,
        int frameCount,
        int bk2StartOffset,
        int preTraceOscFrames,
        SelectedTeam team,
        Path bk2Path,
        TraceMetadata metadata) {}
```

- [ ] **Step 2: Compile**

Run: `mvn -q -o compile -Dmse=off`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/openggf/trace/catalog/TraceEntry.java
$message = @'
feat(trace): TraceEntry record for catalog entries

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

### Task 12: TraceCatalog scanner + tests (TDD)

**Files:**
- Create: `src/test/java/com/openggf/trace/catalog/TraceCatalogTest.java`
- Create: `src/main/java/com/openggf/trace/catalog/TraceCatalog.java`

Spec §4.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.trace.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceCatalogTest {

    @Test
    void scanFiltersInvalidDirsAndSortsByGameZoneAct(@TempDir Path tmp) throws Exception {
        writeValidTrace(tmp.resolve("s3k/z_cnz"), "s3k", 11, 0);
        writeValidTrace(tmp.resolve("s1/ghz1"), "s1", 0, 0);
        writeValidTrace(tmp.resolve("s2/ehz1"), "s2", 0, 0);
        writeValidTrace(tmp.resolve("s3k/aiz1"), "s3k", 0, 0);
        Files.createDirectories(tmp.resolve("bogus"));            // missing files
        Files.createDirectories(tmp.resolve("synthetic/v3"));     // filtered by path

        List<TraceEntry> entries = TraceCatalog.scan(tmp);

        assertEquals(List.of("s1", "s2", "s3k", "s3k"),
                entries.stream().map(TraceEntry::gameId).toList());
        assertEquals(List.of(0, 0, 0, 11),
                entries.stream().map(TraceEntry::zone).toList());
    }

    @Test
    void scanSkipsDirWithMultipleBk2Files(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("s1/bad");
        writeValidTrace(dir, "s1", 0, 0);
        Files.writeString(dir.resolve("extra.bk2"), "extra");
        assertTrue(TraceCatalog.scan(tmp).isEmpty());
    }

    @Test
    void scanSkipsSyntheticSubtree(@TempDir Path tmp) throws Exception {
        writeValidTrace(tmp.resolve("synthetic/fake"), "s1", 0, 0);
        assertTrue(TraceCatalog.scan(tmp).isEmpty());
    }

    private static void writeValidTrace(Path dir, String game, int zoneId, int act)
            throws Exception {
        Files.createDirectories(dir);
        // TraceMetadata's "zone" field is a String label; the numeric
        // zone index lives in "zone_id".
        Files.writeString(dir.resolve("metadata.json"), String.format("""
            {
              "game": "%s",
              "zone": "ZONE",
              "zone_id": %d,
              "act": %d,
              "trace_schema": 3,
              "bk2_frame_offset": 100,
              "pre_trace_osc_frames": 12,
              "main_character": "sonic",
              "sidekicks": []
            }
            """, game, zoneId, act));
        Files.writeString(dir.resolve("physics.csv"),
                "0,0,0,0,0,0,0,0,0,0,0\n1,0,0,0,0,0,0,0,0,0,0\n");
        Files.writeString(dir.resolve("trace.bk2"), "stub");
    }
}
```

- [ ] **Step 2: Run — expect fail (class missing)**

Run: `mvn -q test -Dtest=TraceCatalogTest -Dmse=off`
Expected: FAIL — `TraceCatalog cannot be resolved`.

- [ ] **Step 3: Implement TraceCatalog**

```java
package com.openggf.trace.catalog;

import com.openggf.game.save.SelectedTeam;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class TraceCatalog {
    private static final Logger LOGGER = Logger.getLogger(TraceCatalog.class.getName());
    private static final List<String> VALID_GAME_IDS = List.of("s1", "s2", "s3k");
    private static final Comparator<String> GAME_ORDER =
            Comparator.comparingInt(VALID_GAME_IDS::indexOf);

    private TraceCatalog() {}

    public static List<TraceEntry> scan(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<TraceEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> !isSyntheticSubtree(root, p))
                  .forEach(dir -> tryLoad(dir).ifPresent(entries::add));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not scan " + root, e);
        }
        entries.sort(Comparator
                .comparing(TraceEntry::gameId, GAME_ORDER)
                .thenComparingInt(TraceEntry::zone)
                .thenComparingInt(TraceEntry::act)
                .thenComparing(e -> e.dir().getFileName().toString()));
        return Collections.unmodifiableList(entries);
    }

    private static boolean isSyntheticSubtree(Path root, Path dir) {
        Path rel = root.relativize(dir);
        return rel.getNameCount() > 0
                && "synthetic".equals(rel.getName(0).toString());
    }

    private static java.util.Optional<TraceEntry> tryLoad(Path dir) {
        Path metaPath = dir.resolve("metadata.json");
        Path physicsPath = dir.resolve("physics.csv");
        if (!Files.isRegularFile(metaPath) || !Files.isRegularFile(physicsPath)) {
            return java.util.Optional.empty();
        }
        List<Path> bk2s;
        try (Stream<Path> s = Files.list(dir)) {
            bk2s = s.filter(p -> p.getFileName().toString().endsWith(".bk2")).toList();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "list() failed for " + dir, e);
            return java.util.Optional.empty();
        }
        if (bk2s.size() != 1) {
            return java.util.Optional.empty();
        }
        TraceMetadata meta;
        int frameCount;
        try {
            meta = TraceMetadata.load(metaPath);
            frameCount = countCsvRows(physicsPath);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not load trace at " + dir, e);
            return java.util.Optional.empty();
        }
        if (meta.game() == null || !VALID_GAME_IDS.contains(meta.game())) {
            return java.util.Optional.empty();
        }
        String main = meta.recordedMainCharacter() == null
                ? "sonic"
                : meta.recordedMainCharacter();
        SelectedTeam team = new SelectedTeam(main, meta.recordedSidekicks());
        // Important: TraceMetadata.zone() returns a String display label
        // (e.g. "GHZ"). The numeric zone index is zoneId() (Integer, may
        // be null on older traces — default 0 for sorting).
        int zoneIndex = meta.zoneId() != null ? meta.zoneId() : 0;
        return java.util.Optional.of(new TraceEntry(
                dir,
                meta.game(),
                zoneIndex,
                meta.act(),
                frameCount,
                meta.bk2FrameOffset(),
                meta.preTraceOscillationFrames(),
                team,
                bk2s.get(0),
                meta));
    }

    private static int countCsvRows(Path physicsCsv) throws IOException {
        try (Stream<String> lines = Files.lines(physicsCsv)) {
            return (int) lines.filter(l -> !l.isBlank() && !l.startsWith("#")).count();
        }
    }
}
```

> **Note on `TraceMetadata.load`:** it already exists and reads JSON via Jackson. Verify via `git grep -n 'public static TraceMetadata load' src/main/java/com/openggf/trace/TraceMetadata.java`.

- [ ] **Step 4: Run test — expect pass**

Run: `mvn -q test -Dtest=TraceCatalogTest -Dmse=off`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/trace/catalog/TraceCatalog.java src/test/java/com/openggf/trace/catalog/TraceCatalogTest.java
$message = @'
feat(trace): TraceCatalog scans traces root with filter + sort

Scans a directory tree for valid trace dirs (metadata.json +
physics.csv + exactly one .bk2), filters synthetic/ subtree, returns
an immutable list sorted by game (s1<s2<s3k), zone, act, dirname.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

---

## Phase 7 — LiveTraceComparator

### Task 13: MismatchEntry record

**Files:**
- Create: `src/main/java/com/openggf/trace/live/MismatchEntry.java`

Spec §8.

- [ ] **Step 1: Write the record**

```java
package com.openggf.trace.live;

import com.openggf.trace.Severity;

public record MismatchEntry(
        int frame,
        String field,
        String romValue,
        String engineValue,
        String delta,
        Severity severity,
        int repeatCount) {
    public MismatchEntry withIncrementedRepeat() {
        return new MismatchEntry(frame, field, romValue, engineValue, delta,
                severity, repeatCount + 1);
    }
}
```

- [ ] **Step 2: Compile + commit**

```powershell
mvn -q -o compile -Dmse=off
git add src/main/java/com/openggf/trace/live/MismatchEntry.java
$message = @'
feat(trace): MismatchEntry record for live divergence HUD

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

### Task 14: MismatchRingBuffer (TDD)

**Files:**
- Create: `src/test/java/com/openggf/trace/live/MismatchRingBufferTest.java`
- Create: `src/main/java/com/openggf/trace/live/MismatchRingBuffer.java`

Spec §8.2.

- [ ] **Step 1: Write failing tests**

```java
package com.openggf.trace.live;

import com.openggf.trace.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MismatchRingBufferTest {
    @Test
    void pushFifoEvictsOldest() {
        MismatchRingBuffer b = new MismatchRingBuffer(3);
        for (int i = 0; i < 5; i++) {
            b.push(new MismatchEntry(i, "x", "a", "b", "1", Severity.ERROR, 1));
        }
        List<MismatchEntry> recent = b.recent();
        assertEquals(3, recent.size());
        assertEquals(4, recent.get(0).frame()); // newest first
        assertEquals(2, recent.get(2).frame());
    }

    @Test
    void duplicateHeadIncrementsRepeat() {
        MismatchRingBuffer b = new MismatchRingBuffer(3);
        b.push(new MismatchEntry(10, "x", "a", "b", "1", Severity.ERROR, 1));
        b.push(new MismatchEntry(11, "x", "a", "b", "1", Severity.ERROR, 1));
        b.push(new MismatchEntry(12, "x", "a", "b", "1", Severity.ERROR, 1));
        assertEquals(1, b.recent().size());
        assertEquals(3, b.recent().get(0).repeatCount());
    }

    @Test
    void differentFieldFlushesRepeatCounter() {
        MismatchRingBuffer b = new MismatchRingBuffer(3);
        b.push(new MismatchEntry(10, "x", "a", "b", "1", Severity.ERROR, 1));
        b.push(new MismatchEntry(11, "x", "a", "b", "1", Severity.ERROR, 1));
        b.push(new MismatchEntry(12, "y", "c", "d", "1", Severity.WARNING, 1));
        b.push(new MismatchEntry(13, "x", "a", "b", "1", Severity.ERROR, 1));
        assertEquals(3, b.recent().size());
        assertEquals(1, b.recent().get(0).repeatCount());
    }
}
```

- [ ] **Step 2: Run — expect fail**

Run: `mvn -q test -Dtest=MismatchRingBufferTest -Dmse=off`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package com.openggf.trace.live;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded newest-first buffer with head deduplication. If the new entry
 * matches the current head on (field, romValue, engineValue) the head's
 * repeat counter is incremented and no new entry is pushed. Any other
 * combination resets the dedup state.
 */
public final class MismatchRingBuffer {
    private final int capacity;
    private final Deque<MismatchEntry> entries = new ArrayDeque<>();

    public MismatchRingBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void push(MismatchEntry entry) {
        MismatchEntry head = entries.peekFirst();
        if (head != null && sameKey(head, entry)) {
            entries.pollFirst();
            entries.addFirst(head.withIncrementedRepeat());
            return;
        }
        entries.addFirst(entry);
        while (entries.size() > capacity) {
            entries.pollLast();
        }
    }

    public synchronized List<MismatchEntry> recent() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized void clear() {
        entries.clear();
    }

    private static boolean sameKey(MismatchEntry a, MismatchEntry b) {
        return Objects.equals(a.field(), b.field())
                && Objects.equals(a.romValue(), b.romValue())
                && Objects.equals(a.engineValue(), b.engineValue());
    }
}
```

- [ ] **Step 4: Run — expect pass**

Run: `mvn -q test -Dtest=MismatchRingBufferTest -Dmse=off`
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/trace/live/MismatchRingBuffer.java src/test/java/com/openggf/trace/live/MismatchRingBufferTest.java
$message = @'
feat(trace): MismatchRingBuffer with head-dedup + FIFO eviction

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

### Task 15: LiveTraceComparator (TDD)

**Files:**
- Create: `src/test/java/com/openggf/trace/live/LiveTraceComparatorTest.java`
- Create: `src/main/java/com/openggf/trace/live/LiveTraceComparator.java`

Spec §8.

- [ ] **Step 1: Write failing test**

```java
package com.openggf.trace.live;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.ToleranceConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveTraceComparatorTest {

    @Test
    void skipIncrementsLagCounter() {
        LiveTraceComparator c = new LiveTraceComparator(
                stubTrace(List.of(
                        TraceFrame.executionTestFrame(0, 10, 0x100, 0),
                        TraceFrame.executionTestFrame(1, 10, 0x100, 1))),
                ToleranceConfig.DEFAULT);
        Bk2FrameInput empty = new Bk2FrameInput(0, 0, 0, false, "0");
        c.afterFrameAdvanced(empty, true);
        assertEquals(1, c.laggedFrames());
        assertEquals(0, c.errorCount());
    }

    @Test
    void shouldSkipGameplayTickDelegatesToPhase() {
        // First two frames share the same gameplay_frame_counter → second is VBLANK_ONLY
        LiveTraceComparator c = new LiveTraceComparator(
                stubTrace(List.of(
                        TraceFrame.executionTestFrame(0, 10, 0x100, 0),
                        TraceFrame.executionTestFrame(1, 11, 0x100, 1))),
                ToleranceConfig.DEFAULT);
        Bk2FrameInput empty = new Bk2FrameInput(1, 0, 0, false, "0");
        // Advance our internal cursor past index 0 first:
        c.afterFrameAdvanced(new Bk2FrameInput(0, 0, 0, false, "0"), false);
        assertEquals(true, c.shouldSkipGameplayTick(empty));
    }

    private static TraceData stubTrace(List<TraceFrame> frames) {
        return TraceData.ofFrames(stubMetadata(), frames);
    }

    private static TraceMetadata stubMetadata() {
        // Construct a TraceMetadata with game="s2" (so S3K gating is off)
        // and whichever mandatory fields the record requires. Use
        // TraceMetadata's all-args canonical constructor with defaults.
        return TraceMetadata.forTest("s2", 0, 0);
    }
}
```

- [ ] **Step 1b: Add the two test factories the test depends on**

In `src/main/java/com/openggf/trace/TraceData.java` add:
```java
/**
 * Public test factory — in-memory TraceData without disk I/O.
 * Public (not package-private) because {@code LiveTraceComparatorTest}
 * lives in {@code com.openggf.trace.live}, a different subpackage.
 */
public static TraceData ofFrames(TraceMetadata metadata, java.util.List<TraceFrame> frames) {
    return new TraceData(metadata, java.util.List.copyOf(frames), java.util.Map.of());
}
```
(If the existing constructor is private, widen it to package-private; or
mirror the private call signature used by `TraceData.load`.)

In `src/main/java/com/openggf/trace/TraceMetadata.java` add:
```java
/**
 * Public test factory — minimal metadata for in-memory tests. Public
 * because callers live in different subpackages
 * (e.g. {@code com.openggf.trace.live}).
 */
public static TraceMetadata forTest(String gameId, int zoneId, int act) {
    return new TraceMetadata(
            gameId,          // game
            "TEST",         // zone (display label)
            zoneId,          // zone_id
            act,             // act
            0,               // bk2_frame_offset
            0,               // trace_frame_count
            "0x0000",       // start_x
            "0x0000",       // start_y
            null,            // recording_date
            null,            // lua_script_version
            3,               // trace_schema
            null,            // rom_checksum
            null,            // notes
            null,            // characters
            "sonic",        // main_character
            java.util.List.of(), // sidekicks
            0,               // pre_trace_osc_frames
            null,            // trace_type
            null,            // input_source
            null,            // credits_demo_index
            null);           // credits_demo_slug
}
```
(Order of arguments must match the record's canonical constructor;
verify by reading the current `TraceMetadata` record declaration. If
Jackson record components are reordered in the future, update this
factory to match.)

- [ ] **Step 2: Run — expect fail**

Run: `mvn -q test -Dtest=LiveTraceComparatorTest -Dmse=off`
Expected: FAIL.

- [ ] **Step 3: Promote GameLoop.getMainPlayableSprite() to package-private**

In `src/main/java/com/openggf/GameLoop.java`, change `getMainPlayableSprite()` (currently private, around line 831) to package-private so the later `TraceSessionLauncher.LiveFixture` in `com.openggf` can call it directly. Do **not** add `Engine.currentGameLoop()` here; Task 19 introduces the single static helper used by the launcher.

```java
// In GameLoop.java — widen visibility (remove the `private` modifier):
AbstractPlayableSprite getMainPlayableSprite() { /* existing body */ }
```

- [ ] **Step 4: Implement LiveTraceComparator**

```java
package com.openggf.trace.live;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.PlaybackDebugManager.PlaybackFrameObserver;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceBinder;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;

import java.util.List;
import java.util.function.Supplier;

/**
 * Engine-side per-frame trace comparator. Attached to
 * PlaybackDebugManager as a PlaybackFrameObserver; gates ROM lag frames
 * and accumulates divergences into a ring buffer plus counters.
 */
public final class LiveTraceComparator implements PlaybackFrameObserver {
    private static final int RING_CAPACITY = 5;

    private final TraceData trace;
    private final TraceBinder binder;
    private final MismatchRingBuffer mismatches = new MismatchRingBuffer(RING_CAPACITY);
    private final Supplier<AbstractPlayableSprite> spriteProvider;

    /**
     * Initial trace index the engine is seeded at. Set from the
     * launcher's seed index so comparison aligns from frame zero.
     */
    private int cursor;

    private int errorCount;
    private int warningCount;
    private int laggedFrames;
    private int lastActionMask;
    private int lastInputMask;
    private boolean lastStartPressed;
    private boolean complete;
    private boolean gameplayStartSeen;

    public LiveTraceComparator(TraceData trace,
                               ToleranceConfig tolerances,
                               int initialCursor,
                               Supplier<AbstractPlayableSprite> spriteProvider) {
        this.trace = trace;
        this.binder = new TraceBinder(tolerances);
        this.cursor = initialCursor;
        this.spriteProvider = spriteProvider;
    }

    @Override
    public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
        if (cursor >= trace.frameCount()) {
            return false;
        }
        TraceFrame current = trace.getFrame(cursor);
        TraceFrame previous = cursor > 0 ? trace.getFrame(cursor - 1) : null;
        TraceExecutionPhase phase =
                TraceReplayBootstrap.phaseForReplay(trace, previous, current);
        return phase == TraceExecutionPhase.VBLANK_ONLY;
    }

    @Override
    public void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped) {
        lastActionMask = frame.p1ActionMask();
        lastInputMask = frame.p1InputMask();
        lastStartPressed = frame.p1StartPressed();
        if (wasSkipped) {
            laggedFrames++;
            cursor++;
            checkComplete();
            return;
        }
        if (cursor >= trace.frameCount()) {
            checkComplete();
            return;
        }
        TraceFrame expected = trace.getFrame(cursor);
        if (shouldSuppressComparison(expected)) {
            cursor++;
            checkComplete();
            return;
        }
        AbstractPlayableSprite sprite = spriteProvider.get();
        if (sprite == null) {
            cursor++;
            checkComplete();
            return;
        }
        // TraceBinder.compareFrame returns a FrameComparison holding a
        // Map<String, FieldComparison> of every compared field. We only
        // care about the divergent ones for the HUD ring buffer.
        FrameComparison result = binder.compareFrame(expected,
                sprite.getCentreX(), sprite.getCentreY(),
                sprite.getXSpeed(), sprite.getYSpeed(), sprite.getGSpeed(),
                sprite.getAngle(), sprite.getAir(), sprite.getRolling(),
                sprite.getGroundMode().ordinal());
        absorbDivergentFields(result, expected.frame());
        cursor++;
        checkComplete();
    }

    private boolean shouldSuppressComparison(TraceFrame expected) {
        if (!"s3k".equals(trace.metadata().game())) {
            return false;
        }
        if (gameplayStartSeen) {
            return false;
        }
        boolean isGameplayStart = trace.getEventsForFrame(expected.frame()).stream()
                .anyMatch(e -> e instanceof TraceEvent.Checkpoint cp
                        && "gameplay_start".equals(cp.name()));
        if (isGameplayStart) {
            gameplayStartSeen = true;
        }
        return !gameplayStartSeen;
    }

    private void absorbDivergentFields(FrameComparison result, int frameNumber) {
        List<FieldComparison> divergent = result.divergentFields();
        for (FieldComparison fc : divergent) {
            Severity sev = fc.severity();
            if (sev == Severity.ERROR) {
                errorCount++;
            } else if (sev == Severity.WARNING) {
                warningCount++;
            } else {
                continue; // MATCH or unknown → skip
            }
            mismatches.push(new MismatchEntry(
                    frameNumber,
                    fc.fieldName(),
                    fc.expected(),
                    fc.actual(),
                    Integer.toString(fc.delta()),
                    sev,
                    1));
        }
    }

    private void checkComplete() {
        if (cursor >= trace.frameCount()) {
            complete = true;
        }
    }

    public int errorCount() { return errorCount; }
    public int warningCount() { return warningCount; }
    public int laggedFrames() { return laggedFrames; }
    public boolean isComplete() { return complete; }
    public List<MismatchEntry> recentMismatches() { return mismatches.recent(); }
    public int recentActionMask() { return lastActionMask; }
    public int recentInputMask() { return lastInputMask; }
    public boolean recentStartPressed() { return lastStartPressed; }
}
```

> **Why this shape matches the real API:** `TraceBinder.compareFrame(TraceFrame, short, short, short, short, short, byte, boolean, boolean, int)` is the existing 10-arg simple overload (verified in `TraceBinder.java` lines 26-34) that returns `FrameComparison`. `FrameComparison.divergentFields()` returns `List<FieldComparison>` filtered to non-`MATCH` entries. `FieldComparison` record components are `(fieldName, expected, actual, severity, delta)` where `delta` is `int`. No `DivergenceGroup`/`DivergenceReport` involvement at per-frame scope — those are post-hoc run aggregators, not per-frame divergence lists.

- [ ] **Step 5: Update test to pass initialCursor + spriteProvider**

Update the `stubTrace` calls in `LiveTraceComparatorTest` to pass a spriteProvider that returns null (so comparison paths early-out harmlessly):

```java
LiveTraceComparator c = new LiveTraceComparator(
        stubTrace(List.of(...)),
        ToleranceConfig.DEFAULT,
        0,
        () -> null);
```

- [ ] **Step 6: Run — expect pass**

Run: `mvn -q test -Dtest=LiveTraceComparatorTest -Dmse=off`
Expected: 2 tests pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/openggf/trace/live/LiveTraceComparator.java src/test/java/com/openggf/trace/live/LiveTraceComparatorTest.java src/main/java/com/openggf/trace/TraceBinder.java
$message = @'
feat(trace): LiveTraceComparator + S3K gameplay_start gate

Runtime PlaybackFrameObserver that classifies each BK2 frame via
TraceReplayBootstrap.phaseForReplay, skips gameplay on VBLANK_ONLY
frames (incrementing the lag counter), and compares the primary
player's state against the trace CSV on gameplay frames. For S3K
traces, comparison is suppressed until the first gameplay_start
checkpoint to avoid intro-cutscene noise.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

---

## Phase 8 — MasterTitleScreen / GameLoop hooks

### Task 16: selectEntry + launchGameByEntry callback

**Files:**
- Modify: `src/main/java/com/openggf/game/MasterTitleScreen.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`

Spec §6 step 2.

- [ ] **Step 1: Add MasterTitleScreen.selectEntry(GameEntry)**

```java
/**
 * Programmatic selection used by {@link com.openggf.TraceSessionLauncher}
 * to force a game without user input. Must be called while state is
 * {@code ACTIVE}. Seeds the internal "selected" state so
 * {@code isGameSelected()} returns true on the next tick.
 */
public void selectEntry(GameEntry entry) {
    Objects.requireNonNull(entry, "entry");
    this.selectedIndex = entry.ordinal();
    if (!romAvailable[selectedIndex]) {
        throw new IllegalStateException("Selected ROM is unavailable: " + entry.gameId);
    }
    this.state = State.CONFIRMING;
    playConfirmSound();
    this.gameSelected = true;
}
```

- [ ] **Step 2: Add GameLoop.launchGameByEntry(GameEntry, Runnable)**

```java
private Runnable pendingMasterTitleLaunchCallback;
private Runnable afterStepMasterTitleLaunchCallback;

/**
 * Programmatic path into {@link #exitMasterTitleScreen}. Seeds the
 * master-title selection and runs the same post-selection bootstrap as
 * a user pressing Enter. The callback is deferred until the current
 * {@link #step()} unwinds, so trace bootstrap code may safely call
 * {@code gameLoop.step()} without reentering the master-title frame.
 */
void launchGameByEntry(MasterTitleScreen.GameEntry entry, Runnable afterGameLoaded) {
    MasterTitleScreen masterScreen = masterTitleScreenSupplier != null
            ? masterTitleScreenSupplier.get() : null;
    if (masterScreen == null) {
        throw new IllegalStateException("No master title screen available");
    }
    pendingMasterTitleLaunchCallback = afterGameLoaded;
    masterScreen.selectEntry(entry);
    exitMasterTitleScreen(masterScreen);
}

private void runAfterStepMasterTitleLaunchCallbackIfPresent() {
    Runnable callback = afterStepMasterTitleLaunchCallback;
    afterStepMasterTitleLaunchCallback = null;
    if (callback != null) {
        callback.run();
    }
}
```

- [ ] **Step 3: Defer the pending callback until the current frame is finished**

Inside `doExitMasterTitleScreen(String selectedGameId)`, immediately after
`masterTitleExitHandler.accept(selectedGameId);`, add:

```java
afterStepMasterTitleLaunchCallback = pendingMasterTitleLaunchCallback;
pendingMasterTitleLaunchCallback = null;
```

Then run the deferred callback only after the outer `step()` has completed its
normal per-frame work:

```java
// In the MASTER_TITLE_SCREEN branch, just before `return;`
inputHandler.update();
runAfterStepMasterTitleLaunchCallbackIfPresent();
return;

// And at the bottom of step(), immediately after the normal input update:
inputHandler.update();
runAfterStepMasterTitleLaunchCallbackIfPresent();
```

- [ ] **Step 4: Compile**

Run: `mvn -q -o compile -Dmse=off`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/game/MasterTitleScreen.java src/main/java/com/openggf/GameLoop.java
$message = @'
feat(title): public selectEntry + launchGameByEntry hooks

Lets TraceSessionLauncher (same package as GameLoop) programmatically
force a master-title selection and resume on a post-bootstrap callback
once Engine.exitMasterTitleScreen(...) has rebuilt the gameplay runtime.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

---

## Phase 9 — TestModeTracePicker

### Task 17: TestModeTracePicker

**Files:**
- Create: `src/main/java/com/openggf/testmode/TestModeTracePicker.java`

Spec §5.

- [ ] **Step 1: Write the class**

```java
package com.openggf.testmode;

import com.openggf.control.InputHandler;
import com.openggf.graphics.PixelFont;
import com.openggf.trace.catalog.TraceEntry;

import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Trace picker screen shown when TEST_MODE_ENABLED is true. Owned by
 * MasterTitleScreen; it substitutes this screen's update/render for the
 * normal game-selection ACTIVE behaviour.
 */
public final class TestModeTracePicker {

    public enum Result { NONE, LAUNCH, BACK }

    private final List<TraceEntry> entries;
    private final PixelFont font;
    private int cursor;
    private Result pendingResult = Result.NONE;

    public TestModeTracePicker(List<TraceEntry> entries, PixelFont font) {
        this.entries = entries;
        this.font = font;
    }

    public void update(InputHandler input) {
        if (entries.isEmpty()) {
            if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ESCAPE)) {
                pendingResult = Result.BACK;
            }
            return;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_DOWN)) {
            cursor = Math.min(entries.size() - 1, cursor + 1);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_UP)) {
            cursor = Math.max(0, cursor - 1);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_HOME)) {
            cursor = 0;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_END)) {
            cursor = entries.size() - 1;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_PAGE_DOWN)) {
            cursor = nextGroupStart(cursor);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_PAGE_UP)) {
            cursor = prevGroupStart(cursor);
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ENTER)) {
            pendingResult = Result.LAUNCH;
        }
        if (input.isKeyPressedWithoutModifiers(GLFW_KEY_ESCAPE)) {
            pendingResult = Result.BACK;
        }
    }

    public void render() {
        font.drawTextCentered("TRACE TEST MODE", 320, 12, 1f, 1f, 1f, 1f);
        int y = 32;
        String lastGame = null;
        for (int i = 0; i < entries.size(); i++) {
            TraceEntry e = entries.get(i);
            if (!e.gameId().equals(lastGame)) {
                if (lastGame != null) y += 6;
                font.drawText(gameHeading(e.gameId()), 20, y, 1f, 1f, 0.6f, 1f);
                y += 14;
                lastGame = e.gameId();
            }
            boolean selected = (i == cursor);
            float brightness = selected ? 1.0f : 0.7f;
            String prefix = selected ? ">" : " ";
            String line = prefix + " " + e.dir().getFileName();
            font.drawText(line, 24, y, brightness, brightness, brightness, 1f);
            y += 11;
        }
        if (cursor < entries.size()) {
            renderInfoPanel(entries.get(cursor));
        }
    }

    private void renderInfoPanel(TraceEntry e) {
        int y = 180;
        font.drawText("SELECTED: " + e.gameId() + "/" + e.dir().getFileName(),
                8, y, 1f, 1f, 1f, 1f);
        y += 12;
        font.drawText(String.format("Zone: %02X  Act: %d   Frames: %d   BK2 offset: %d",
                e.zone(), e.act(), e.frameCount(), e.bk2StartOffset()),
                8, y, 0.9f, 0.9f, 0.9f, 1f);
        y += 11;
        font.drawText("Team: " + formatTeam(e) + "   Pre-osc: " + e.preTraceOscFrames(),
                8, y, 0.9f, 0.9f, 0.9f, 1f);
        y += 11;
        font.drawText("BK2: " + e.bk2Path().getFileName(),
                8, y, 0.7f, 0.7f, 0.7f, 1f);
    }

    private static String gameHeading(String gameId) {
        return switch (gameId) {
            case "s1" -> "SONIC 1";
            case "s2" -> "SONIC 2";
            case "s3k" -> "SONIC 3&K";
            default -> gameId.toUpperCase();
        };
    }

    private static String formatTeam(TraceEntry e) {
        StringBuilder sb = new StringBuilder(e.team().mainCharacter());
        for (String sk : e.team().sidekicks()) {
            sb.append('+').append(sk);
        }
        return sb.toString();
    }

    private int nextGroupStart(int from) {
        String current = entries.get(from).gameId();
        for (int i = from + 1; i < entries.size(); i++) {
            if (!entries.get(i).gameId().equals(current)) return i;
        }
        return from;
    }

    private int prevGroupStart(int from) {
        String current = entries.get(from).gameId();
        int found = -1;
        for (int i = 0; i < from; i++) {
            if (!entries.get(i).gameId().equals(current) && found == -1) {
                found = i;
            }
        }
        return found >= 0 ? found : 0;
    }

    public Result consumeResult() {
        Result r = pendingResult;
        pendingResult = Result.NONE;
        return r;
    }

    public TraceEntry selectedEntry() {
        return cursor < entries.size() ? entries.get(cursor) : null;
    }
}
```

- [ ] **Step 2: Compile + commit**

```powershell
mvn -q -o compile -Dmse=off
git add src/main/java/com/openggf/testmode/TestModeTracePicker.java
$message = @'
feat(testmode): TestModeTracePicker screen with list + info panel

Navigates TraceCatalog entries with Up/Down, PgUp/PgDn between game
groups, Home/End, Enter to launch, Esc back to normal master title.
Rendered via PixelFont for zero new asset requirements.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

### Task 18: Wire TEST_MODE_ENABLED branch in MasterTitleScreen

**Files:**
- Modify: `src/main/java/com/openggf/game/MasterTitleScreen.java`

Spec §5.2.

- [ ] **Step 1: Read and inject dependencies**

Constructor changes: accept a `Supplier<TestModeTracePicker>` or construct the picker on first entry to `ACTIVE` when `TEST_MODE_ENABLED` is true. Simplest path: lazy-init on first `update()` call.

Add a field:
```java
private TestModeTracePicker tracePicker;
```

- [ ] **Step 2: Redirect ACTIVE when TEST_MODE_ENABLED**

Inside `update(InputHandler)`, find the `case ACTIVE:` branch. At the top of that branch, add:

```java
if (configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)) {
    if (tracePicker == null) {
        Path root = Path.of(System.getProperty("user.dir"))
                .resolve(configService.getString(SonicConfiguration.TRACE_CATALOG_DIR));
        tracePicker = new TestModeTracePicker(
                TraceCatalog.scan(root.normalize()), font);
    }
    tracePicker.update(input);
    switch (tracePicker.consumeResult()) {
        case LAUNCH -> {
            TraceEntry entry = tracePicker.selectedEntry();
            if (entry != null) {
                tracePicker = null;
                TraceSessionLauncher.launch(entry);
            }
        }
        case BACK -> {
            // Turn test mode off for this session so normal game-select runs
            configService.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
            tracePicker = null;
        }
        case NONE -> {}
    }
    return;
}
// fall-through to existing game-selection logic
```

Add imports as needed (`com.openggf.trace.catalog.*`, `com.openggf.testmode.*`, `com.openggf.TraceSessionLauncher`).

- [ ] **Step 3: Render branch**

In whatever method the screen uses to draw its ACTIVE state, add the analogous guard:

```java
if (tracePicker != null) {
    tracePicker.render();
    return;
}
```

- [ ] **Step 4: Defer compile until Task 19**

`MasterTitleScreen` now references `TraceSessionLauncher`, which is created in the next task. Do not add a temporary no-op branch or stub. Leave this file modified in the working tree and run the first compile once Task 19 lands.

- [ ] **Step 5: Commit with Task 19**

Stage `src/main/java/com/openggf/game/MasterTitleScreen.java` together with Task 19's launcher files and commit them as one compile-clean change.

---

## Phase 10 — TraceSessionLauncher

### Task 19: TraceSessionLauncher skeleton + post-bootstrap live fixture

**Files:**
- Create: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify: `src/main/java/com/openggf/Engine.java` — add static `currentGameLoop()` accessor used by the launcher

Spec §6.

- [ ] **Step 0: Add the Engine accessor**

In `src/main/java/com/openggf/Engine.java` add a public static accessor
so other `com.openggf.*` classes can reach the singleton game loop:

```java
public static GameLoop currentGameLoop() {
    return instance != null ? instance.gameLoop : null;
}
```

Reuse the existing `private static Engine instance` field already present in
`Engine`; do **not** introduce a second singleton field such as `INSTANCE`.

- [ ] **Step 1: Write the class**

```java
package com.openggf;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.GameRuntime;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.RuntimeManager;
import com.openggf.graphics.PixelFontTextRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.testmode.TraceHudOverlay;
import com.openggf.trace.TraceData;
import com.openggf.trace.catalog.TraceEntry;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;

import java.io.IOException;
import java.util.logging.Logger;

public final class TraceSessionLauncher {

    private static final Logger LOGGER =
            Logger.getLogger(TraceSessionLauncher.class.getName());

    /** Hold before fade at natural trace completion. See spec §2.8 / §9.2. */
    private static final double COMPLETION_HOLD_SECONDS = 1.0;

    private static TraceSessionLauncher activeSession;

    private final TraceEntry entry;
    private final TraceData trace;
    private final Bk2Movie movie;
    private LiveTraceComparator comparator;
    private TraceHudOverlay overlay;
    private TraceReplayFixture fixture;

    private boolean completionArmed;
    private int completionHoldFrames;
    private boolean fadeStarted;

    private TraceSessionLauncher(TraceEntry entry, TraceData trace, Bk2Movie movie) {
        this.entry = entry;
        this.trace = trace;
        this.movie = movie;
    }

    public static void launch(TraceEntry entry) {
        try {
            TraceData trace = TraceData.load(entry.dir());
            Bk2Movie movie = new Bk2MovieLoader().load(entry.bk2Path());
            TraceReplaySessionBootstrap.prepareConfiguration(trace, trace.metadata());
            TraceSessionLauncher session = new TraceSessionLauncher(entry, trace, movie);
            Engine.currentGameLoop().launchGameByEntry(
                    resolveGameEntry(entry.gameId()),
                    session::finishLaunchAfterGameBootstrap);
        } catch (Exception e) {
            LOGGER.severe("Failed to launch trace " + entry.dir() + ": " + e.getMessage());
        }
    }

    private void finishLaunchAfterGameBootstrap() {
        try {
            ensureTraceLevelLoaded();

            PlaybackDebugManager playback = PlaybackDebugManager.getInstance();
            int startIndex = com.openggf.trace.TraceReplayBootstrap
                    .recordingStartFrameForTraceReplay(trace);
            playback.startSession(movie, startIndex);

            GameLoop loop = Engine.currentGameLoop();
            this.fixture = new LiveFixture(playback, loop);
            // Task 16 defers this callback until after the outer
            // MASTER_TITLE_SCREEN step has unwound, so the shared bootstrap
            // may safely drive replay frames via gameLoop.step().
            TraceReplaySessionBootstrap.BootstrapResult boot =
                    TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);

            // Align the comparator's initial cursor with the first trace
            // index the engine will produce after bootstrap. The
            // TraceReplayBootstrap.ReplayStartState already carries this
            // (startingTraceIndex). For traces with a seeded mid-level
            // start (S3K AIZ post-intro, etc.) this keeps comparison
            // meaningful from frame zero; for plain-power-on traces it's
            // simply 0.
            int initialCursor = boot.replayStart().startingTraceIndex();
            this.comparator = new LiveTraceComparator(
                    trace,
                    com.openggf.trace.ToleranceConfig.DEFAULT,
                    initialCursor,
                    loop::getMainPlayableSprite);
            this.overlay = new TraceHudOverlay(comparator, fixture);
            playback.setFrameObserver(comparator);
            activeSession = this;
        } catch (Exception e) {
            PlaybackDebugManager.getInstance().endSession();
            LOGGER.severe("Failed to finish trace launch for " + entry.dir() + ": " + e.getMessage());
        }
    }

    private void ensureTraceLevelLoaded() throws IOException {
        GameServices.level().loadZoneAndAct(entry.zone(), entry.act());
        Engine.currentGameLoop().setGameMode(GameMode.LEVEL);
    }

    public static TraceSessionLauncher active() {
        return activeSession;
    }

    /** Called by GameLoop once per LEVEL tick while a session is active. */
    public void tick() {
        if (comparator == null || fadeStarted) return;
        if (comparator.isComplete() && !completionArmed) {
            completionArmed = true;
            completionHoldFrames = (int) Math.round(COMPLETION_HOLD_SECONDS * 60.0);
        }
        if (completionArmed) {
            if (completionHoldFrames > 0) {
                completionHoldFrames--;
            } else {
                startFadeOut();
            }
        }
    }

    /** Called from the InputHandler path when Esc is pressed during a session. */
    public void requestEarlyExit() {
        if (comparator == null || fadeStarted) return;
        startFadeOut();
    }

    public void render(PixelFontTextRenderer textRenderer) {
        if (overlay != null) {
            overlay.render(textRenderer);
        }
    }

    private void startFadeOut() {
        fadeStarted = true;
        GameServices.fade().startFadeToBlack(this::teardown);
    }

    private void teardown() {
        PlaybackDebugManager.getInstance().endSession();
        // Route the game loop back to the master title; the picker will
        // re-enter via the same TEST_MODE_ENABLED branch as the initial boot.
        Engine.currentGameLoop().returnToMasterTitle();
        activeSession = null;
    }

    private static MasterTitleScreen.GameEntry resolveGameEntry(String gameId) {
        return switch (gameId) {
            case "s1" -> MasterTitleScreen.GameEntry.SONIC_1;
            case "s2" -> MasterTitleScreen.GameEntry.SONIC_2;
            case "s3k" -> MasterTitleScreen.GameEntry.SONIC_3K;
            default -> throw new IllegalArgumentException("Unknown game: " + gameId);
        };
    }

    /** Thin live-engine implementation of TraceReplayFixture. */
    private static final class LiveFixture implements TraceReplayFixture {
        private final PlaybackDebugManager playback;
        private final GameLoop gameLoop;

        private LiveFixture(PlaybackDebugManager playback, GameLoop gameLoop) {
            this.playback = playback;
            this.gameLoop = gameLoop;
        }

        @Override
        public AbstractPlayableSprite sprite() {
            // SpriteManager.getSprite() is keyed on character code
            // ("sonic"/"tails"/"knuckles"), not a fixed "player" string,
            // and depends on active team config. GameLoop.getMainPlayableSprite()
            // already resolves the primary playable via
            // ActiveGameplayTeamResolver.resolveMainCharacterCode.
            return gameLoop.getMainPlayableSprite();
        }
        @Override public GameRuntime runtime() { return RuntimeManager.getCurrent(); }
        @Override public int stepFrameFromRecording() {
            Bk2FrameInput frame = playback.currentFrameOrThrow();
            int mask = toReplayValidationMask(frame);
            gameLoop.step();
            return mask;
        }
        @Override public int skipFrameFromRecording() {
            Bk2FrameInput frame = playback.currentFrameOrThrow();
            int mask = toReplayValidationMask(frame);
            playback.advanceCurrentFrameWithoutGameplay();
            return mask;
        }
        @Override public void advanceRecordingCursor(int frameCount) {
            for (int i = 0; i < frameCount; i++) {
                playback.advanceCurrentFrameWithoutGameplay();
            }
        }
        private static int toReplayValidationMask(Bk2FrameInput frame) {
            int mask = frame.p1InputMask();
            if (frame.p1ActionMask() != 0) {
                mask |= AbstractPlayableSprite.INPUT_JUMP;
            }
            return mask;
        }
    }
}
```

> **Note on teardown:** the live launcher does not call `TestEnvironment.resetAll()` — that helper is test-only (depends on `com.openggf.tests.rules.SonicGame`) and is designed for between-test singleton isolation. Instead the launcher returns through a new Engine-owned `returnToMasterTitleScreen()` path that recreates the master title screen and resets gameplay/runtime state in-process. Implement that path as part of Task 20.

- [ ] **Step 2: Compile**

Run: `mvn -q -o compile -Dmse=off`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/com/openggf/game/MasterTitleScreen.java src/main/java/com/openggf/TraceSessionLauncher.java src/main/java/com/openggf/Engine.java
$message = @'
feat(testmode): TraceSessionLauncher wires picker → live playback

Launch path: prepare config → async launchGameByEntry callback →
load trace level → PlaybackDebugManager.startSession →
TraceReplaySessionBootstrap → LiveTraceComparator attach. tick() runs
each frame to drive the completion hold; Esc short-circuits to the
fade. Both end paths converge on fade → teardown → picker via
FadeManager's onComplete callback.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

### Task 20: Wire launcher tick + Esc to GameLoop; recreate master title on teardown

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/Engine.java`

- [ ] **Step 1: Add a GameLoop → Engine return-to-master-title callback**

In `GameLoop`, add:

```java
private Runnable returnToMasterTitleHandler;

public void setReturnToMasterTitleHandler(Runnable returnToMasterTitleHandler) {
    this.returnToMasterTitleHandler = returnToMasterTitleHandler;
}

/**
 * Tear down the current trace session and hand control back to Engine so
 * it can recreate the master title screen and reset gameplay state.
 */
void returnToMasterTitle() {
    if (returnToMasterTitleHandler != null) {
        returnToMasterTitleHandler.run();
    }
}
```

- [ ] **Step 2: Implement Engine.returnToMasterTitleScreen() and wire the callback**

In the `Engine` constructor, add:

```java
this.gameLoop.setReturnToMasterTitleHandler(this::returnToMasterTitleScreen);
```

Then add:

```java
void returnToMasterTitleScreen() {
    resetForGameplayFromMasterTitle();
    if (masterTitleScreen != null) {
        masterTitleScreen.cleanup();
    }
    masterTitleScreen = new MasterTitleScreen(configService);
    masterTitleScreen.initialize();
    gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
}
```

- [ ] **Step 3: Call TraceSessionLauncher.active().tick() per LEVEL frame**

At the end of the LEVEL-mode update (after gameplay + `onLevelFrameAdvanced`), add:

```java
TraceSessionLauncher session = TraceSessionLauncher.active();
if (session != null) {
    session.tick();
}
```

- [ ] **Step 4: Esc triggers requestEarlyExit**

At the top of `update()` before the normal Esc/pause handling, add:

```java
if (currentGameMode == GameMode.LEVEL
        && TraceSessionLauncher.active() != null
        && inputHandler.isKeyPressed(GLFW_KEY_ESCAPE)) {
    TraceSessionLauncher.active().requestEarlyExit();
    inputHandler.update();
    return;
}
```

- [ ] **Step 5: Compile + commit**

```powershell
mvn -q -o compile -Dmse=off
git add src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/Engine.java
$message = @'
feat(testmode): GameLoop drives TraceSessionLauncher tick + Esc handoff + master-title return

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

---

## Phase 11 — TraceHudOverlay

### Task 21: TraceHudOverlay + Engine.display hook

**Files:**
- Create: `src/main/java/com/openggf/testmode/TraceHudOverlay.java`
- Modify: `src/main/java/com/openggf/Engine.java` — add an engine-owned `PixelFontTextRenderer` and call into the trace HUD after the fade pass so the HUD stays visible during fade-to-black.
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java` — expose a `render(PixelFontTextRenderer)` method that the Engine calls.

Spec §9. `DebugOverlayManager` has no panel-registration hook, so the HUD is painted from a direct hook in `Engine.display()` rather than via a panel interface.

- [ ] **Step 1: Write TraceHudOverlay as a plain class (no interface)**

```java
package com.openggf.testmode;

import com.openggf.debug.DebugColor;
import com.openggf.graphics.PixelFontTextRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.live.MismatchEntry;
import com.openggf.trace.replay.TraceReplayFixture;

import java.util.List;

public final class TraceHudOverlay {
    private final LiveTraceComparator comparator;
    private final TraceReplayFixture fixture;

    public TraceHudOverlay(LiveTraceComparator comparator, TraceReplayFixture fixture) {
        this.comparator = comparator;
        this.fixture = fixture;
    }

    public void render(PixelFontTextRenderer text) {
        int x = 180;
        int y = 130;
        text.drawShadowedText(String.format("ERRORS %4d", comparator.errorCount()),
                x, y, DebugColor.RED); y += 11;
        text.drawShadowedText(String.format("WARN   %4d", comparator.warningCount()),
                x, y, DebugColor.ORANGE); y += 11;
        text.drawShadowedText(String.format("LAG    %4d", comparator.laggedFrames()),
                x, y, DebugColor.GRAY); y += 14;

        int actionMask = comparator.recentActionMask();
        int mask = comparator.recentInputMask();
        boolean start = comparator.recentStartPressed();
        StringBuilder buttons = new StringBuilder("A B C U D L R S");
        StringBuilder active = new StringBuilder();
        active.append(bit(actionMask, 0x01, 'A'));
        active.append(' ');
        active.append(bit(actionMask, 0x02, 'B'));
        active.append(' ');
        active.append(bit(actionMask, 0x04, 'C'));
        active.append(' ');
        active.append(bit(mask, AbstractPlayableSprite.INPUT_UP, 'U'));
        active.append(' ');
        active.append(bit(mask, AbstractPlayableSprite.INPUT_DOWN, 'D'));
        active.append(' ');
        active.append(bit(mask, AbstractPlayableSprite.INPUT_LEFT, 'L'));
        active.append(' ');
        active.append(bit(mask, AbstractPlayableSprite.INPUT_RIGHT, 'R'));
        active.append(' ');
        active.append(start ? 'S' : '.');
        text.drawShadowedText(buttons.toString(), x, y, DebugColor.WHITE); y += 11;
        text.drawShadowedText(active.toString(), x + 48, y, DebugColor.GREEN); y += 11;

        text.drawShadowedText("Last mismatches:", x, y, DebugColor.LIGHT_GRAY); y += 11;
        List<MismatchEntry> recent = comparator.recentMismatches();
        for (MismatchEntry m : recent) {
            String line = String.format("f %04X %s rom=%s eng=%s Δ%s%s",
                    m.frame(), m.field(), m.romValue(),
                    m.engineValue(), m.delta(),
                    m.repeatCount() > 1 ? (" ×" + m.repeatCount()) : "");
            DebugColor color = m.severity() == com.openggf.trace.Severity.ERROR
                    ? DebugColor.RED : DebugColor.ORANGE;
            text.drawShadowedText(line, x, y, color, 0.8f);
            y += 10;
        }

        if (comparator.isComplete()) {
            text.drawShadowedText("TRACE COMPLETE", x, 120, DebugColor.YELLOW);
        }
    }

    private static char bit(int mask, int flag, char letter) {
        return (mask & flag) != 0 ? letter : '.';
    }
}
```

> **Note:** `AbstractPlayableSprite.INPUT_UP`/etc. are public constants already used elsewhere (see `PlaybackDebugManager.formatInput`).

- [ ] **Step 2: Keep the render(PixelFontTextRenderer) helper on TraceSessionLauncher**

Task 19 already introduced:

```java
public void render(PixelFontTextRenderer textRenderer) {
    overlay.render(textRenderer);
}
```

Do not add a second copy; just keep this method when wiring the HUD in
`Engine.display()`.

- [ ] **Step 3: Hook Engine.display() to render the HUD**

In `src/main/java/com/openggf/Engine.java`, add an engine-owned text renderer:

```java
private final PixelFontTextRenderer traceHudTextRenderer = new PixelFontTextRenderer();
```

In `display()` after `uiPipeline.renderFadePass()` and after the normal
debug/playback overlay has rendered, add:

```java
traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
TraceSessionLauncher session = TraceSessionLauncher.active();
if (session != null) {
    session.render(traceHudTextRenderer);
}
```

This hook must stay **after** the fade pass so `TRACE COMPLETE`, counters,
and the mismatch log remain readable on top of the fade-to-black.

Also clean up the renderer from `Engine.cleanup()`:

```java
traceHudTextRenderer.cleanup();
```

- [ ] **Step 4: Compile + commit**

```powershell
mvn -q -o compile -Dmse=off
git add src/main/java/com/openggf/testmode/TraceHudOverlay.java src/main/java/com/openggf/TraceSessionLauncher.java src/main/java/com/openggf/Engine.java
$message = @'
feat(testmode): TraceHudOverlay renders counters + input + mismatch log

Bottom-right HUD painted each frame from Engine.display while a trace
session is active. Red ERRORS, orange WARN, grey LAG counters; BK2
input visualiser including A/B/C/U/D/L/R/S; the last five mismatch
entries (severity-coloured, with repeat counts). Rendered after the
fade pass so TRACE COMPLETE remains visible during fade-to-black.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

---

## Phase 12 — End-to-end smoke test + polish

### Task 22: Manual smoke test and docs update

**Files:**
- Modify: `CLAUDE.md` — one-liner under "Configuration" pointing at `TEST_MODE_ENABLED`.
- Modify: `CHANGELOG.md` — add entry.

- [ ] **Step 1: Manual smoke test**

Steps:
1. Set `TEST_MODE_ENABLED=true` in the working-directory `config.json` that `SonicConfigurationService` actually loads.
2. Ensure ROMs are present in the working directory per `CLAUDE.md`.
3. Run:
```powershell
mvn -q package -Dmse=off
if ($LASTEXITCODE -eq 0) {
  java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
}
```
4. Observe: master title fades in → trace picker appears.
5. Arrow down to `s1/ghz1_fullrun`, press Enter.
6. Watch the engine: level loads, playback starts, HUD shows counters ticking, input row reflects BK2.
7. Let playback run until TRACE COMPLETE appears. Verify: 1 s hold, fade to black, returned to picker.
8. Launch another trace, then during playback press Esc. Verify: fade to black immediately, returned to picker.

Record observed counter behaviour in the commit message (not in the repo). If counters are obviously wrong (e.g. tens of thousands of errors on frame 1) open a follow-up issue rather than patching inline.

- [ ] **Step 2: Update CLAUDE.md *and* AGENTS.md**

`.githooks` policy: the `Agent-Docs` trailer is mapped to **both**
`CLAUDE.md` and `AGENTS.md`. Staging only CLAUDE.md with
`Agent-Docs: updated` fails the policy check (and `n/a` is invalid when
a mapped file is staged). Add the same configuration-reference block to
both files under their "Configuration" section:

```markdown
- `TEST_MODE_ENABLED` - replaces the master-title game-select with a trace picker (dev-only; requires `TRACE_CATALOG_DIR`).
- `TRACE_CATALOG_DIR` - directory scanned by `TraceCatalog` (default `src/test/resources/traces`).
```

- [ ] **Step 3: Update CHANGELOG.md**

```markdown
## Trace Test Mode

Config-gated dev tool that lists all trace-replay tests from
src/test/resources/traces/ on the master title screen and plays the
chosen trace back inside the live engine with live divergence counters,
BK2 input visualiser, and rolling mismatch log. Toggle via
`TEST_MODE_ENABLED=true`. See docs/architecture/designs/2026-04-23-trace-test-mode-design.md.
```

- [ ] **Step 4: Commit docs update**

```powershell
git add CLAUDE.md AGENTS.md CHANGELOG.md
$message = @'
docs: trace test mode — changelog + agent/claude config references

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: updated
Configuration-Docs: n/a
Skills: n/a
'@
git commit -m $message
```

- [ ] **Step 5: Final full-suite run**

Run: `mvn -q test -Dmse=off`
Expected: same pass/skip set as baseline. No regressions.

- [ ] **Step 6: Open PR to develop**

Run:
```powershell
git push -u origin feature/ai-trace-test-mode
$prBody = @'
## Summary
- Adds config-gated Trace Test Mode: master title → picker → live playback with divergence HUD.
- Trace infrastructure moved from src/test to src/main (pure package rename).
- PlaybackDebugManager extended with programmatic session start + lag-frame gating hook.
- Spec: docs/architecture/designs/2026-04-23-trace-test-mode-design.md
- Plan: docs/architecture/plans/2026-04-23-trace-test-mode.md

## Test plan
- [ ] mvn test passes with same pass/skip set as develop baseline
- [ ] Manual: launch engine with TEST_MODE_ENABLED=true, pick s1/ghz1_fullrun, watch it run
- [ ] Manual: Esc during playback fades to black and returns to picker
- [ ] Manual: natural completion holds 1 s on TRACE COMPLETE then fades

🤖 Generated with [Claude Code](https://claude.com/claude-code)
'@
gh pr create --base develop --title "feat: trace test mode (visual replay picker)" --body $prBody
```

---

## Self-review checklist

- [x] Spec §4 (catalog): Task 11 (record) + Task 12 (scanner + test).
- [x] Spec §5 (master-title integration + picker): Tasks 16 + 17 + 18.
- [x] Spec §6 (session launch): Tasks 5, 6 (shared bootstrap), 19 (launcher), 20 (GameLoop wiring).
- [x] Spec §6.2 (programmatic playback): Task 8.
- [x] Spec §6.3 (lag-frame gating): Tasks 7, 8, 9.
- [x] Spec §7 (code move): Task 1.
- [x] Spec §8 (LiveTraceComparator + ring buffer + S3K gate): Tasks 13, 14, 15.
- [x] Spec §9 (HUD overlay): Task 21.
- [x] Spec §9.2 / §2.8 (completion hold + fade + Esc symmetry): Task 19.
- [x] Spec §11 (config keys): Task 10.
- [x] Spec §12 (testing plan): Tasks 12, 14, 15 (unit); Task 22 (manual smoke).

No placeholder branches remain: where the trace-picker wiring depends on the next task's launcher class, the plan explicitly batches the compile/commit instead of asking for a temporary no-op. The live launcher keeps the full `TraceReplayFixture` contract instead of filtering out part of the trace catalog. Types are consistent: `TraceReplayFixture` signature is defined once in Task 2 and reused verbatim in Tasks 4 and 19; `PlaybackFrameObserver` is declared in Task 7 and implemented in Task 15; `COMPLETION_HOLD_SECONDS` is introduced in Task 19 and named consistently with §9.2.

