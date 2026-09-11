# Frame and Resource Lifecycle Repair Implementation Plan

> **For Codex:** Use the executing-plans workflow to implement this plan task by task, preserving the baseline inventory in `docs/architecture/audits/2026-08-28-develop-suite-repair-baseline.md`.

**Goal:** Restore the shared frame/PLC/trace/title-card lifecycle contracts on `develop` without reintroducing obsolete overloads, trace-specific state, or regressions in currently passing tests.

**Architecture:** Keep `LevelFrameStep` as the single boundary-order owner, `LevelIterationAdmissionController` as playback admission/cursor owner, and `TraceSuppressedRowClosure` as the timing-only closure for suppressed rows. Repair contracts at those semantic boundaries; update stale tests when production has intentionally migrated to a phase-aware API.

**Tech Stack:** Java 21, Maven, JUnit Jupiter, Mockito, Surefire alphabetical ordering.

**Execution outcome:** Completed on 2026-08-28. Production changes were limited
to playback suppression classification, special-stage PLC phase normalization,
and suppressed-row VBlank ordering. Review showed several failures encoded
superseded production APIs or leaked local configuration, so those tests were
repaired rather than changing correct runtime behavior. The ordinary suite
moved from 45 failures / 6 errors to 30 failures / 3 errors with no new failing
identity; exact evidence is in the companion baseline audit.

---

### Task 1: Make hardware-timed object scans reject ambiguous provider phases at the caller boundary

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/test/java/com/openggf/TestGameLoopHardwareTimingBoundaries.java`

- [ ] Add a focused test whose mocked `SpecialStageProvider` does not stub its default phase and assert that the special-stage scan uses `SPECIAL_STAGE` and emits all three boundaries.
- [ ] Add a small phase-normalization helper at the provider/caller boundary:

```java
PlcLifecyclePhase phase = ssProvider.specialStagePlcLifecyclePhase();
if (phase == null) {
    phase = PlcLifecyclePhase.SPECIAL_STAGE;
}
```

- [ ] Run `mvn -Dmse=off -Dtest=TestGameLoopHardwareTimingBoundaries test -B` and require zero failures/errors.

### Task 2: Restore suppression classification and applied-frame ownership before generic timers

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/LevelIterationAdmissionController.java`
- Modify: `src/test/java/com/openggf/TestGameLoop.java`
- Test: `src/test/java/com/openggf/debug/playback/TestPlaybackDebugManagerPreparedInput.java`

- [ ] Replace source-shape-only coverage with behavioral coverage that exercises LEVEL and BONUS_STAGE playback suppression before `TimerManager.update`.
- [ ] Classify playback-driven gameplay rows for both gameplay modes before generic timers, without admitting trace comparison data into gameplay state.
- [ ] Capture the applied movie cursor before calling `onLevelFrameAdvanced` and pass that captured value to `afterPlaybackFrame` on every admitted and boundary-return path.
- [ ] Run the four focused tests covering suppression, playback input preparation, boundary returns, and user recording controls.

### Task 3: Repair suppressed-row closure ordering

**Files:**
- Modify: `src/main/java/com/openggf/trace/replay/TraceSuppressedRowClosure.java`
- Modify: `src/test/java/com/openggf/trace/replay/TestTraceSuppressedRowClosure.java`
- Test: `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java`

- [ ] Preserve tests that prove V-int is serviced exactly once and no PLC work is invented.
- [ ] Move `advanceVblankOnlyState()` after represented completion and pending-title dispatch, matching the recorded ROM loop order for both ordinary lag and scheduled completion arms.
- [ ] Keep held title-card object scans in their existing object-scan position and avoid any frame-, zone-, route-, or game-name branch.
- [ ] Run the closure tests and hardware-timing authority guard.

### Task 4: Reconcile phase-aware object and transition bridge tests

**Files:**
- Modify: `src/test/java/com/openggf/TestPlcVBlankOrdering.java`
- Modify: `src/test/java/com/openggf/level/TestLevelSeamlessTransitionExecutor.java`
- Test: `src/test/java/com/openggf/TestLevelFrameHardwareTimingBoundaries.java`

- [ ] Update the PLC ordering test to observe `updateObjectPositionsWithoutTouches(false)`, the current production API, while retaining the exact expected order.
- [ ] Verify from `LevelManager` and the transition executor that the loop-tail oscillator method is the intended current owner; update stale Mockito expectations only if no production behavior is missing.
- [ ] Run the three focused test classes and require zero failures/errors.

### Task 5: Restore title-card physics policy at its game-owned provider boundary

**Files:**
- Modify: the smallest applicable S2 title-card provider/rules file identified by the failing test
- Modify: `src/test/java/com/openggf/game/TestTitleCardPhysicsPolicy.java`
- Test: relevant title-card lifecycle tests under `src/test/java/com/openggf/`

- [ ] Trace the S2 locked-title loop to its provider policy and confirm that its player update is ROM-owned during the phase.
- [ ] Set the semantic provider policy for S2 only; do not branch on the game name in shared lifecycle code.
- [ ] Run the policy test and all title-card lifecycle tests.

### Task 6: Verify lifecycle repair before opening later failure lanes

**Files:**
- Modify if frontier moves: `docs/status/trace-frontier-log.md`
- Update: `docs/architecture/audits/2026-08-28-develop-suite-repair-baseline.md`

- [ ] Run the focused lifecycle cluster in one fresh alphabetical fork.
- [ ] Delete Surefire reports, then run the exact ordinary baseline command with all three ROM properties.
- [ ] Compare test names and outcomes against the 45-failure/6-error baseline; record every resolved, unchanged, and newly failing test.
- [ ] Run `mvn -Dmse=off -Pguards test -B`.
- [ ] Run the four keep-green headless tests listed in `AGENTS.md`.
- [ ] Run the alphabetical `-Ptrace-replay` profile with all three ROM properties and update the trace frontier log if any frontier moves or green trace regresses.
- [ ] Commit the independently verified lifecycle repair with all required policy trailers before planning the next failure lane.
