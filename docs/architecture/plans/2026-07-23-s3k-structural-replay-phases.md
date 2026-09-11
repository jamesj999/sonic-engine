# S3K Structural Replay Phases Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the three S3K replay phase-control metadata dependencies and make AIZ/CNZ replay scheduling follow native lifecycle and recorded LEVEL transitions.

**Architecture:** A generic fresh-main-playable dispatch marker in `SpriteManager` models the cleared playable slot whose first native dispatch initializes without movement; the S3K level-init profile enables it through production startup. `TraceReplayBootstrap` derives an intro prefix from `zone_act_state` transitions and uses a full-frame default after the LEVEL setup boundary, while retaining direct lag and input-latch exceptions.

**Tech Stack:** Java 21, JUnit Jupiter, Maven, Lua 5.1-compatible BizHawk recorder.

## Global Constraints

- Trace data remains comparison-only; CSV and aux values must never be copied into engine state.
- Do not branch on zone id/name, route name, frame number, or known fixture identity.
- Do not infer scheduling from player/sidekick motion, animation, or oscillator outcome values.
- Do not add always-on BizHawk execution hooks.
- S1/S2 behavior remains unchanged unless an explicit profile value enables the lifecycle behavior.
- Preserve unrelated workspace changes and update `docs/status/trace-frontier-log.md` for measured frontier changes.

---

### Task 1: Native fresh-main-playable dispatch lifecycle

**Files:**
- Modify: `src/main/java/com/openggf/game/LevelInitProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Test: `src/test/java/com/openggf/tests/trace/TestS3kFreshPlayableDispatch.java`

**Interfaces:**
- Produces: `LevelInitProfile.firstMainPlayableDispatchInitializesWithoutMovement(): boolean`
- Produces: `SpriteManager.armFreshMainPlayableDispatch(AbstractPlayableSprite)`
- Produces: `SpriteManager.tickFreshMainPlayableInitialization(...)`, shared by every ordinary playable update entry point.
- Produces: one-shot consumption inside the ordinary playable update path.

- [ ] **Step 1: Write the failing lifecycle tests**

Create `TestS3kFreshPlayableDispatch` with a real headless S3K fixture. Assert that the first ordinary frame from a freshly armed main playable preserves main position/velocity while still allowing its animation dispatch, sidekick update, object update, oscillator update, and controller-history closure. Assert the second frame runs normal main physics. Add profile assertions that S3K returns `true` and S1/S2 return `false`.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn -Dtest=com.openggf.tests.trace.TestS3kFreshPlayableDispatch test
```

Expected: FAIL because the profile method/arming API does not exist and the first main-player dispatch advances movement.

- [ ] **Step 3: Add the narrow lifecycle contract**

Add to `LevelInitProfile`:

```java
default boolean firstMainPlayableDispatchInitializesWithoutMovement() {
    return false;
}
```

Override in `Sonic3kLevelInitProfile`:

```java
@Override
public boolean firstMainPlayableDispatchInitializesWithoutMovement() {
    return true;
}
```

In `SpriteManager`, store the armed main playable by identity. Add one
`tickFreshMainPlayableInitialization(...)` helper and route every ordinary
playable update entry point through it before `tickPlayablePhysics(...)`. On
one-shot consumption it must publish queued controls, call
`recordFollowerHistoryForTick()`, run the native animation/status/end-of-tick
closure and deferred mutations, but omit movement, terrain/solid collision, and
zone-feature-after-physics for the main playable only. Do not suppress
sidekicks, objects, oscillation, controller history, animation, input closure,
or later frames. Clear the marker during manager reset.

At the end of `LevelManager.resetPlayerForLevelStart(LevelLoadContext)`, after
`playable.resetState()` and the remaining fresh-slot fields have been assigned,
arm the marker when
`GameServices.module().getLevelInitProfile().firstMainPlayableDispatchInitializesWithoutMovement()`
is true. Do not arm during generic sprite registration.

- [ ] **Step 4: Verify GREEN and cross-game profile isolation**

Run:

```bash
mvn -Dtest='com.openggf.tests.trace.TestS3kFreshPlayableDispatch,com.openggf.tests.TestHeadlessWallCollision,com.openggf.tests.TestS3kAiz1SkipHeadless' test
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

Commit the lifecycle change and tests with policy trailers. Because this changes `src/main`, update `CHANGELOG.md`.

---

### Task 2: Metadata-free AIZ prefix and CNZ replay policy

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceReplayBootstrap.java`
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceReplayStartPositionPolicy.java`
- Modify: `src/test/java/com/openggf/trace/TestPreludeFramesKnobsZero.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestBuildToolingGuard.java`

**Interfaces:**
- Consumes: production fresh-main-playable lifecycle from Task 1.
- Produces: `TraceReplayBootstrap.hasRecordedPreLevelPrefix(TraceData)`.
- Produces: S3K sidekick/object/oscillator prelude counts of zero for level-gated starts.

- [ ] **Step 1: Write failing policy tests**

Using the checked-in AIZ and CNZ fixtures, add assertions that:

```java
assertTrue(TraceReplayBootstrap.hasRecordedPreLevelPrefix(aiz));
assertEquals(0, TraceReplayBootstrap.replaySeedTraceIndexForTraceReplay(aiz));
assertFalse(TraceReplayBootstrap.hasRecordedPreLevelPrefix(cnz));
assertEquals(0, TraceReplayBootstrap.sidekickTitleCardPreludeFramesForTraceReplay(cnz));
assertEquals(0, TraceReplayBootstrap.levelObjectTitleCardPreludeFramesForTraceReplay(cnz));
assertEquals(0, TraceReplayBootstrap.preTraceOscillationFramesForTraceReplay(cnz, -1));
```

Add phase assertions around AIZ’s actual LEVEL transition: the transition row is `VBLANK_ONLY`, the following pinned-counter row is `FULL_LEVEL_FRAME`, an unchanged-state input-edge row is `ADVANCE_ONLY`, and a lag-counter row is `VBLANK_ONLY`. Add negative assertions for AIZ/CNZ complete-run, MGZ counter-zero, bonus-stage, single-character, and visible-hold start shapes.

Create temporary metadata variants for AIZ/CNZ with contradictory legacy
`pre_level_intro_prefix`, `sidekick_seed_frame_prelude`, and
`pre_trace_osc_frames` values and assert the classifications above are
unchanged.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -Dtest='com.openggf.tests.trace.TestTraceReplayStartPositionPolicy,com.openggf.trace.TestPreludeFramesKnobsZero,com.openggf.tests.TestBuildToolingGuard' test
```

Expected: failures show AIZ still depends on `hasPreLevelIntroPrefix()`, CNZ still requests seed preludes, and the source guard still requires capability metadata.

- [ ] **Step 3: Implement structural prefix classification**

Implement `hasRecordedPreLevelPrefix(TraceData)` using existing parsed `zone_act_state` events:

- first recorded mode is outside live LEVEL mode;
- a later event transitions into live LEVEL mode;
- no zone/route/frame-name predicate participates.

Use it in replay seed selection, start-position policy, fresh-load policy, previous-input policy, strict-start policy, and prefix phase classification. At the transition row return `VBLANK_ONLY`; afterward return `FULL_LEVEL_FRAME` by default until `gameplay_start`, except existing direct lag evidence and unchanged-state input-latch detection.

- [ ] **Step 4: Remove S3K compensation paths**

Remove S3K use of:

```java
TraceMetadata.hasSidekickSeedFramePrelude()
TraceMetadata.hasPreLevelIntroPrefix()
TraceMetadata.preTraceOscillationFrames()
```

from replay scheduling. Keep S2/S1 prelude logic intact. Remove the S3K seed-row cursor special case so CNZ drives and compares frame 0 normally using Task 1’s production lifecycle.

Change `TestBuildToolingGuard` to reject S3K scheduling references to those
metadata accessors and retain its existing trace-to-engine hydration scanner.
Add source ratchets rejecting scheduling predicates that inspect first-row
player/sidekick position, speed, animation, mapping frame, or oscillator bytes.

- [ ] **Step 5: Verify GREEN**

Run:

```bash
mvn -Dtest='com.openggf.tests.trace.TestTraceReplayStartPositionPolicy,com.openggf.trace.TestPreludeFramesKnobsZero,com.openggf.tests.TestBuildToolingGuard' test
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit**

Commit the replay-policy change and tests with policy trailers. Update `CHANGELOG.md`.

---

### Task 3: Recorder and documentation contract cleanup

**Files:**
- Modify: `tools/bizhawk/s3k_trace_recorder.lua`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceAnimationRecorderContract.java`
- Modify: `docs/status/known-discrepancies.md`
- Modify: `docs/status/trace-frontier-log.md`

**Interfaces:**
- Consumes: metadata-free replay policy from Task 2.
- Produces: recorder metadata that no longer advertises phase-control extras.

- [ ] **Step 1: Write failing recorder contract assertions**

Add source-contract assertions that `s3k_trace_recorder.lua` does not emit:

```text
pre_level_intro_prefix
sidekick_seed_frame_prelude
pre_trace_osc_frames
```

The contract must continue requiring `trace_profile`, input alignment metadata, schema versions, and diagnostic-hook opt-in behavior.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -Dtest=com.openggf.tests.trace.TestTraceAnimationRecorderContract test
```

Expected: FAIL while `pre_trace_osc_frames` remains emitted or legacy phase extras remain accepted.

- [ ] **Step 3: Remove obsolete recorder output and documentation**

Stop writing the three obsolete phase controls from the S3K recorder. Keep parsing compatibility in Java. Update `docs/status/known-discrepancies.md` to describe production lifecycle and structural LEVEL-transition scheduling, explicitly stating that trace values remain comparison-only.

Update `docs/status/trace-frontier-log.md` with the exact focused AIZ/CNZ commands and pre-fix first divergences; fill final counts/frontiers only after Task 4 measurements.

- [ ] **Step 4: Verify GREEN and Lua syntax**

Run:

```bash
mvn -Dtest=com.openggf.tests.trace.TestTraceAnimationRecorderContract test
lua -e 'assert(loadfile("tools/bizhawk/s3k_trace_recorder.lua"))'
```

Expected: both commands pass.

- [ ] **Step 5: Commit**

Commit recorder/docs cleanup with policy trailers marking Known-Discrepancies updated.

---

### Task 4: Focused replay and fleet verification

**Files:**
- Modify: `docs/status/trace-frontier-log.md`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: verified AIZ/CNZ measurements and S3K regression evidence.

- [ ] **Step 1: Run CNZ with sufficient heap**

```bash
mvn -Dtest=com.openggf.tests.trace.s3k.TestS3kCnzTraceReplay \
  -Dsurefire.argLine='-Xshare:off -Xmx3g' test
```

Expected: no input-alignment error; frame-zero `y_speed 0x0000 -> 0x0038` bootstrap divergence is gone. Record the true new frontier or green result.

- [ ] **Step 2: Run AIZ**

```bash
mvn -Dtest=com.openggf.tests.trace.s3k.TestS3kAizTraceReplay \
  -Dsurefire.argLine='-Xshare:off -Xmx3g' test
```

Expected: no input-alignment error; frame-290 route-state/bootstrap divergence is gone. Record the true new frontier or green result.

- [ ] **Step 3: Run S3K bootstrap and must-keep-green regression tests**

```bash
mvn -Dtest='com.openggf.tests.TestS3kAiz1SkipHeadless,com.openggf.tests.TestSonic3kLevelLoading,com.openggf.tests.TestSonic3kBootstrapResolver,com.openggf.tests.TestSonic3kDecodingUtils,com.openggf.tests.trace.TestTraceReplayStartPositionPolicy,com.openggf.trace.TestPreludeFramesKnobsZero,com.openggf.tests.TestBuildToolingGuard,com.openggf.tests.trace.TestTraceAnimationRecorderContract' test
```

Expected: all selected tests pass.

- [ ] **Step 4: Run the S3K trace fleet**

```bash
mvn -Dtest='*S3k*TraceReplay' -Dsurefire.argLine='-Xshare:off -Xmx3g' test
```

Expected: previously green traces remain green; any remaining AIZ/CNZ failures represent later true parity frontiers, not bootstrap or input alignment.

- [ ] **Step 5: Finalize frontier log and commit**

Record exact commands, commit context, pass/fail state, error count, and first field/frame for AIZ/CNZ and any regression. Commit the verification documentation with policy trailers.
