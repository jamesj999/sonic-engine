# Visual trace single-load and special-stage parity implementation plan

Date: 2026-08-03

Design:
`docs/architecture/designs/trace/2026-08-03-visual-trace-title-card-and-frame-zero-art-design.md`

## Goal

Make level-backed visual traces load their selected level once, continue from
the production title card into deterministic replay without restarting music,
and retain exact headless PLC/dynamic-art/hardware timing behavior. Route visual
BK2 input through ROM-correct raw/logical ownership so scripted input wins.
Give standalone special-stage traces an on-screen trace HUD, correct base-game
SFX routing, and a deterministic terminal return from S1's white hold.

Every production change below begins with a focused failing test and the test is
rerun red before implementation.

## Task 1: Reserve an unpublished dynamic-art segment origin

Files:

- Modify `src/test/java/com/openggf/game/resources/TestDynamicArtLifecycleService.java`
- Modify `src/test/java/com/openggf/game/resources/TestPlcFrameLifecycleCoordinator.java`
- Modify `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- Modify `src/main/java/com/openggf/game/resources/DynamicArtLifecycleService.java`
- Modify `src/main/java/com/openggf/game/resources/PlcFrameLifecycleCoordinator.java`
- Modify `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify `src/main/java/com/openggf/trace/live/LiveTraceComparator.java`

Steps:

1. Add lifecycle tests for a reserved external segment: closing the last
   published automatic window creates a new unpublished generation before the
   next iteration; production service then activates that same generation and
   row closure publishes row zero without another generation increment.
2. Add failures for double reservation, activation with pending production
   work, cancellation before activation, and restoring automatic ownership.
3. Add a rewind round-trip test that captures a reserved unpublished
   generation, activates/mutates it, restores it, and proves the identical
   generation remains reserved and activates without incrementing.
4. Add a launcher-through-coordinator integration test that captures the real
   pre-iteration snapshot, runs the first claim/finish, and passes the strict
   comparator atomicity check. Start from a published automatic snapshot so the
   old implementation fails for the same reason as the console report.
5. Refactor `DynamicArtLifecycleService` and its `RewindState` capture/restore/
   reset validation to distinguish reserved, active, and
   absent external windows. Reservation owns the generation/unpublished origin;
   activation reuses it and retains all pending-work guards; cancellation never
   publishes a row.
6. Refactor the coordinator's deferred ownership API to reserve at acquisition,
   service before activation, and cancel safely. Remove the launcher's adjacent-
   generation authorization and the comparator exception once the ordinary
   stable-generation contract covers row zero.
7. Run the focused lifecycle, rewind round-trip, coordinator, and launcher
   suites.

## Task 2: Add a checked live-to-recorded hardware epoch

Files:

- Modify `src/test/java/com/openggf/game/timing/TestHardwareTimingService.java`
- Modify `src/test/java/com/openggf/game/session/TestSessionManager.java` or add
  focused `GameplayModeContext` coverage beside existing admission tests
- Modify `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java`
- Modify `src/main/java/com/openggf/game/timing/HardwareTimingService.java`
- Modify `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Modify `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`

Steps:

1. Add a failing service test that submits, prepares, and claims live work,
   begins a recorded epoch on the same service, installs a schedule whose first
   ordinal is nonzero, and proves the first subsequent production submission
   receives that schedule ordinal and remains held until its matching edge.
2. Add rejection tests for unclaimed live jobs, repeat activation, incomplete
   policy maps, and any mutation of jobs/ordinals/policy after rejection.
3. Add context tests proving the narrow recorded completion authority can be
   activated only after both the timing ledger and every runtime-art owner are
   quiescent. Exercise `QueueDiagnosticSnapshot` shapes with busy, prepared,
   active, or queued work and prove rejection leaves queue diagnostics, jobs,
   ordinals, boundaries, and admission policies byte-for-byte unchanged.
4. Implement the combined context-owned checked epoch transition: require
   quiescent runtime-art queue diagnostics and no unclaimed timing jobs before
   asking the timing service to retire only completed live diagnostic records,
   clear live ordinal/boundary state, and begin recorded admission. Expose its
   capability through the same gameplay context and keep the service and
   runtime-art coordinators.
5. Install the compiled replay schedule immediately after epoch activation and
   before bootstrap or production can submit replay work. Let the existing
   replay port initialize the schedule's per-kind first ordinal bases.
6. Extend the authority guard so the new transition remains confined to timing
   owners and cannot accept physics, aux, route, zone, game, or frame values.
7. Run hardware service, context, schedule compiler, replay port, rewind, and
   authority guard suites.

## Task 3: Adopt the prepared level without reloading

Files:

- Modify `src/test/java/com/openggf/trace/replay/TestTraceReplayDriver.java` or
  the closest existing driver bootstrap test
- Modify `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- Modify `src/test/java/com/openggf/TestTraceSessionLauncherFailureCleanup.java`
- Modify `src/test/java/com/openggf/TestEngine.java`
- Modify `src/test/java/com/openggf/TestLevelIterationAdmissionController.java`
- Modify `src/test/java/com/openggf/trace/replay/TestVisualTraceLaunchPhase.java`
- Modify `src/main/java/com/openggf/trace/replay/TraceReplayDriver.java`
- Modify `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`
- Modify `src/main/java/com/openggf/trace/replay/VisualTraceLaunchPhase.java`
- Modify `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify `src/main/java/com/openggf/LevelIterationAdmissionController.java`
- Modify `src/main/java/com/openggf/Engine.java`
- Delete `src/main/java/com/openggf/game/session/VisualTraceReplayContextHandoff.java`

Steps:

1. Add a failing launcher test that reaches the production control-release
   step (`TITLE_CARD`→`LEVEL`) while the exit overlay remains active and asserts
   replay activation keeps the identical gameplay context, level manager,
   player, loaded-level generation, title-card provider, and audio command
   timeline. Verify `loadZoneAndAct` is called exactly once, `playMusic` has no
   second command, and no uncontrolled `LEVEL` frame runs before activation.
   Include S2 with its initial setup token already consumed during locked-card
   physics; this is the case that currently falls through into gameplay.
2. Add driver tests for two explicit paths:
   - ordinary headless/capture `start(zone, act)` retains reset/team/load and
     omitted-title-card bootstrap behavior;
   - visual `startPreparedLevel()` performs no reset, registration, load, or
     title-card reset and converges on shared playback/comparator activation.
3. Before production edits, add launch-phase/controller tests proving overlay
   completion is no longer required, the release transition/barrier is
   one-shot, an already-consumed S2 setup token still produces no ordinary
   gameplay, and S1/S3K release setup/pass counts are not duplicated.
4. Before production edits, add launcher failures for a non-drained prelude and
   prepared-level bootstrap exception. Keep mutation-free rejection assertions
   in Task 2; here prove no second load or epoch crossing occurs, the on-screen
   failure is recorded, and normal cleanup may then destroy the rejected
   context while returning to the picker.
5. Introduce a typed bootstrap presentation policy. For a production title card
   that has released control, omit synthetic title-card oscillation/animated-
   tile/object/sidekick preludes and duplicate setup passes already executed by
   production; retain hardware schedule installation, allowed start placement,
   native counter alignment, RNG/start policy, and comparator setup. Leave the
   real provider in its active exit tail so it continues over compared rows.
6. Change standalone and run replay handoff to activate the existing context,
   using Task 2 when recorded timing is present. Remove the Engine reopen seam,
   context replacement helper, retained-adapter reset, and all related failure
   branches/tests that existed solely for the second load.
7. Change launch admission from full-overlay completion to the structural
   control-release boundary: current mode is `LEVEL` after a session-owned
   title-card phase. Add a one-shot launch-phase release barrier in
   `LevelIterationAdmissionController` that converts that completed release to
   `SETUP_ONLY` after all release setup/mode changes, independent of both the
   level's initial setup token and whether production already returned
   `SETUP_ONLY`. Activate replay between iterations so the next host step owns
   row zero. Retain early exit, deferred dynamic-art reservation,
   HUD/ghost/rewind setup, overlay-tail rendering, and cleanup.
8. Run driver, launch-phase, launcher, failure cleanup, Engine, GameLoop
   title-card, admission controller, PLC, and audio timeline focused suites.

## Task 4: Replace visual forced-mask playback with logical input

Files:

- Modify `src/test/java/com/openggf/debug/playback/TestPlaybackDebugManagerPreparedInput.java`
- Modify `src/test/java/com/openggf/TestPlaybackAdvanceOnlyInputBridge.java`
- Modify `src/test/java/com/openggf/TestBonusStagePlaybackBridge.java`
- Add or modify a focused `SpriteManager` input-resolution test
- Modify complete-run destination activation and visual rewind tests near their
  existing owners
- Modify `src/main/java/com/openggf/debug/playback/PlaybackDebugManager.java`
- Add `src/main/java/com/openggf/debug/playback/PlaybackInputBridge.java`
- Modify `src/main/java/com/openggf/GameLoop.java`
- Modify `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify `src/main/java/com/openggf/level/LevelManager.java`
- Modify `src/main/java/com/openggf/TraceSessionLauncher.java`

Steps:

1. Add playback-manager tests for a prepared `LogicalInputSnapshot` containing
   P1/P2 held and pressed masks, action edge latching across an advance-only
   row, Start, and debug modifiers.
2. Add the GHZ signpost-shaped red test: BK2 holds left, a later scripted owner
   latches forced right/control lock, the next visual bridge runs, and movement
   plus published ROM-logical history contain right but not left while the raw
   controller snapshot remains left.
3. Add equivalent coverage at synchronous destination load activation and in
   `VisualTraceRewindStepper`; ensure P2 and Start remain available.
4. Have the playback manager publish its prepared applied row/predecessor as a
   logical snapshot while preserving the existing pending action edge.
5. Extract the loop bridge into a focused collaborator that owns an
   `InputHandler` logical override and the existing suppression marker. Remove
   sprite forced-mask writes, admit the logical override through
   `SpriteManager` while playback is suppressed, and clear only the
   bridge-owned override when driving stops, immediately refreshing the live
   logical snapshot for same-step consumers. Use the same ownership for the
   immediate post-load publication so it cannot leak after playback ends.
6. Use the same logical publisher for same-step destination activation and
   visual rewind replay. Do not clear game-owned forced latches.
7. Make `SpriteManager` publish the already-resolved logical directions instead
   of OR-ing conflicting forced/recorded directions back together. Preserve
   raw controller fields, forced-right precedence, CPU policy, and jump edges.
8. Gate the engine's gamepad-Start user-pause path while playback drives the
   logical snapshot, without gating the configured raw pause key. Add a
   behavioral BK2 Start-edge test covering both sides of that ownership.
9. Run all playback bridge, bonus-stage, rewind, input snapshot, playable
   movement, and run-transition suites.

## Task 5: Give standalone special stages audio routing and a HUD

Files:

- Modify `src/test/java/com/openggf/TestSpecialStageVisualTraceSession.java`
- Modify or add focused audio command-timeline coverage
- Modify HUD renderer tests beside `TraceHudOverlay`
- Add `src/main/java/com/openggf/testmode/TraceSessionOverlay.java`
- Add `src/main/java/com/openggf/testmode/SpecialStageTraceHudOverlay.java`
- Modify `src/main/java/com/openggf/testmode/TraceHudOverlay.java`
- Modify `src/main/java/com/openggf/TraceSessionLauncher.java`

Steps:

1. Add a red direct-launch audio test proving S1 `GameSound.RING` currently
   resolves to fallback. Expected behavior is alternating base-SMPS IDs after
   profile/ROM/map setup without starting level music.
2. Add overlay tests for cursor/row-count progress, current lag/play admission,
   and safe rendering without a comparator.
3. Introduce the minimal shared render interface; keep the existing level HUD
   behavior unchanged and install the special-stage HUD only for standalone
   typed special-stage rows.
4. During direct special-stage finish, configure audio profile, ROM-backed
   loader, module sound map, and ring alternation before provider entry. Leave
   special-stage music start at the existing reveal boundary.
5. Run S1/S2/S3K typed special-stage parser/lifecycle tests, HUD tests, and
   audio routing/timeline tests.

## Task 6: Drain S1 white-hold completion safely

Files:

- Modify `src/test/java/com/openggf/TestSpecialStageVisualTraceSession.java`
- Modify `src/test/java/com/openggf/TestTraceSessionLauncherFailureCleanup.java`
- Modify `src/main/java/com/openggf/TraceSessionLauncher.java`

Steps:

1. Add a red lifecycle test that puts the gameplay fade in `HOLD_WHITE`,
   advances the terminal standalone row inside the launcher's production
   wrapper, and asserts the session does not destroy the context before
   post-production publication.
2. Assert hardware replay closes first, `teardownPending` is armed, and the
   after-production/all-mode retry clears the active session and invokes the
   return-to-picker owner exactly once.
3. Add a red callback-bearing-fade test: terminal exit becomes pending without
   replacing the existing callback; the all-mode retry lets that callback run,
   re-evaluates the resulting fade state, and then reaches teardown/picker
   exactly once. This branch must have a terminal outcome, not remain pending
   indefinitely after the callback completes or changes mode.
4. Add a red unsupported-hold test: the preserved callback completes into a
   callback-free `HOLD_BLACK`; repeated all-mode retry detects the
   non-progressing unsupported hold, records `TraceLaunchStatus`, and performs
   deferred cleanup/return exactly once without replacing the callback or
   hanging.
5. Implement a fade-state-aware standalone terminal helper: idle uses the
   normal fade-to-black callback; callback-free `HOLD_WHITE` requests existing
   deferred teardown; an active callback-bearing fade is left untouched and an
   all-mode terminal-exit retry re-evaluates it after completion until it can
   take one of the supported terminal paths. Record/clean up a structural
   failure if completion leaves an unsupported non-progressing hold.
6. Retain run-special-stage boundary ownership and failure cleanup behavior.
7. Run special-stage lifecycle, failure cleanup, PLC publication, and master
   title return tests.

## Task 7: Focused, ROM-backed, guard, and full verification

1. Run `mvn -v` and record that Maven is using JDK 21. If it is not, locate the
   installed JDK 21 and set `JAVA_HOME` before any Maven command; rerun `mvn -v`
   to prove the correction.
2. Run focused non-ROM suites for every touched owner, including hardware
   authority/rewind, PLC/dynamic-art, launcher/failure cleanup, input bridge,
   special-stage lifecycle, HUD, audio timeline, Engine, and GameLoop.
3. Discover `.gen` files from the repository root without copying, renaming, or
   symlinking them. Verify each required image against the repository CRC32 and
   SHA-1 table, record its absolute path, and pass those discovered paths to the
   correct `sonic1.rom.path`, `sonic2.rom.path`, and `s3k.rom.path` properties.
4. Run the S1 GHZ1 complete-run headless regression and the visual input-bridge
   regression that reproduces the former frame-4998 opposing BK2/signpost
   ownership. Run the S1 standalone special-stage lifecycle/audio terminal
   canary.
5. Run representative S2 level/complete-run and S3K hardware-timed nonzero-
   ordinal canaries with the verified ROMs.
6. Run architecture guards for production singleton closure, hardware timing
   authority, trace comparison-only behavior, rewind coverage/static coverage,
   trace fixture compression, and documentation placement.
7. Run the complete JDK-21 Maven suite in the feature worktree and record the
   exact outcome. Any verification-driven code change repeats its relevant
   focused/ROM/guard tests and the final independent review below.

## Task 8: Result documentation, final review, and feature commit

Files:

- Modify `CHANGELOG.md`
- Modify `README.md`
- Modify `docs/status/trace-frontier-log.md`
- Retain this reviewed design and plan

Steps:

1. Record the visual trace single-load, input, dynamic-art, and standalone
   special-stage fixes in the changelog and README release section.
2. Record the exact verified S1 GHZ1 command/result and the visual-only
   frame-4998 regression/fix in the trace frontier log without claiming a
   headless gameplay frontier movement.
3. Delegate the complete implementation and result-documentation diff for
   independent code review. Fix every valid issue, rerun affected verification,
   refresh exact result docs, and repeat review until no blocking issue remains.
4. Stage every intended source/test/documentation file, commit the feature
   branch with required policy trailers, and verify the worktree is clean apart
   from classified generated output.

## Task 9: Updated-baseline integration and cleanup

1. Use the verified discovered S1 path for commands such as:

   ```bash
   mvn -Dmse=off \
     -Dsonic1.rom.path="$DISCOVERED_S1_ROM" \
     -Dtest=com.openggf.tests.trace.s1.TestS1Ghz1CompleteRunTraceReplay test
   ```

2. Fetch and fast-forward the main `develop` workspace without disturbing its
   user-owned changes. Record the updated full JDK-21 baseline result, then run
   the same full suite plus focused tests on the committed feature branch.
3. Merge the committed feature branch into the main workspace without switching
   its branch, reconcile any
   upstream conflicts, run the same full suite post-merge, and verify no new or
   worsened failures relative to baseline.
4. If conflict resolution or updated-baseline adaptation changes code, rerun the
   affected focused/ROM/guard/full verification and independent review before
   completing the merge commit.
5. Push only `develop`, verify the feature commit is fully merged and the
   worktree has no user/unmerged changes, remove its worktree, delete the local
   feature branch, and prune stale worktree metadata.
