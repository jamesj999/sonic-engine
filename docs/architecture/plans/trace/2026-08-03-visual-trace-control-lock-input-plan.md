# Visual trace control-lock input parity implementation plan

Date: 2026-08-03

**Goal:** Stop visual and rewind-forward trace action presses from bypassing
ROM control locks, while retaining exact one-shot action edges across
advance-only rows.

**Architecture:** Recorded input stays in `LogicalInputSnapshot` through the
playback bridge. Pending P1 action identity is carried as an action mask into
`SpriteManager`, where queued object control state and the control lock decide
whether movement receives the one-shot. The ordinary playback manager and the
visual rewind stepper use the same snapshot helper and never mutate the player
from their input-publication paths.

## Task 1: Specify pending action-mask behavior

Files:

- Modify `src/test/java/com/openggf/debug/playback/TestPlaybackDebugManagerPreparedInput.java`
- Modify `src/main/java/com/openggf/debug/playback/RecordedInputSnapshots.java`
- Modify `src/main/java/com/openggf/debug/playback/PlaybackDebugManager.java`

Steps:

1. Add a failing test whose first applied row introduces one action-button
   edge on an advance-only cursor, whose next applied row holds or releases it,
   and whose later logical snapshot must still expose the original
   `actionPressedMask` and abstract jump press.
2. Add a `RecordedInputSnapshots.fromBk2` overload that ORs a supplied pending
   P1 action-press mask into the naturally derived edge. Keep the two-argument
   API as a zero-pending delegate.
3. Replace `PlaybackDebugManager.currentForcedJumpPress` with a pending P1
   action-press mask. OR newly prepared edges, expose the mask through the
   snapshot overload, preserve the compatibility boolean query, and consume or
   reset it only at existing gameplay/seek/end/clear boundaries.
4. Run `TestPlaybackDebugManagerPreparedInput` and the recorded-input snapshot
   tests.

## Task 2: Put action admission at playable dispatch

Files:

- Modify `src/main/java/com/openggf/sprites/managers/InitialPlayableInput.java`
- Modify `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify `src/test/java/com/openggf/TestPlaybackAdvanceOnlyInputBridge.java`
- Modify or add a focused sprite-manager/input regression test if the bridge
  integration test cannot observe both raw and logical state reliably

Steps:

1. Extend the advance-only bridge test with a red control-lock case: publish a
   raw action edge, queue or set the player's lock before playable dispatch,
   and prove raw press visibility is retained while the sprite stays grounded
   and no movement press survives.
2. Extend `InitialPlayableInput` with `p1ActionPressedMask`. Populate it from
   `InputHandler.logical().player1().actionPressedMask()` in ordinary updates;
   initial native-neutral input supplies zero.
3. In the player-controlled branch, after queued control state is applied,
   admit a nonzero P1 action-press mask only when runtime input is enabled and
   control is unlocked. Publish it as the logical press and set the existing
   one-shot movement latch only when admitted. Never clear a latch on the
   negative path.
4. Keep initial assembly non-consuming, raw object input unchanged, forced
   direction precedence unchanged, and CPU/P2 behavior unchanged.
5. Run the bridge test plus focused sprite-manager and playable-movement tests.

## Task 3: Remove direct sprite ownership from the playback bridge

Files:

- Modify `src/main/java/com/openggf/debug/playback/PlaybackInputBridge.java`
- Modify `src/main/java/com/openggf/GameLoop.java`
- Modify `src/test/java/com/openggf/TestPlaybackAdvanceOnlyInputBridge.java`

Steps:

1. Change `sync` and `publishImmediately` so they no longer accept a playable
   sprite.
2. Remove all `setForcedJumpPress` publication and cleanup. Retain only
   logical-override and playback-suppression ownership.
3. Update `GameLoop` callers and assertions so the setup-only pass retains the
   pending manager edge and the first real unlocked gameplay dispatch consumes
   it exactly once.
4. Run playback bridge, GameLoop playback, Start/pause, and bonus-stage bridge
   tests.

## Task 4: Migrate visual rewind replay-forward

Files:

- Modify `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify `src/test/java/com/openggf/TestTraceSessionLauncherAdvanceOnlyRewind.java`

Steps:

1. Rewrite the existing rewind advance-only test so pending state is asserted
   through the logical snapshot rather than `player.forcedJumpPress`.
2. Add a red reconstruction test that restores at a later non-gameplay row,
   asserts the exact pending `actionPressedMask`, and proves the next unlocked
   gameplay dispatch consumes it exactly once.
3. Add a second reconstruction test that restores after a playable-gameplay
   boundary and proves an earlier action edge is not resurrected.
4. Add a locked-after-restore regression that first asserts the pending mask
   was reconstructed, then applies a control lock, steps gameplay, and proves
   the raw edge does not make the player jump.
5. Replace `pendingForcedJumpPress` with a pending P1 action-press mask. OR the
   applied row's exact new action bits and publish with the shared snapshot
   overload. Consume only after an admitted gameplay frame.
6. Remove all rewind-stepper reads/writes of `player.forcedJumpPress`.
7. On restore, reconstruct the mask by scanning applied BK2 rows in the
   contiguous non-gameplay trace interval up to the restored row, stopping at
   the latest playable-gameplay row. Publish the restored logical snapshot
   with that reconstructed mask.
8. Run all rewind-stepper, rewind seek, visual trace rewind, and trace-session
   launcher tests.

## Task 5: Verify exact parity and document the fix

Files:

- Modify `CHANGELOG.md`
- Modify `README.md`
- Append `docs/status/trace-frontier-log.md`

Steps:

1. Add a concise changelog and release-summary entry for visual trace input
   respecting control locks.
2. Append the exact S1 GHZ1 replay command/context and result to the frontier
   log; do not rewrite history.
3. Run the focused suites from Tasks 1-4.
4. Run S1 GHZ1 complete-run replay with the discovered S1 ROM and confirm its
   headless baseline remains green.
5. Run visual trace launcher/input/rewind suites together, then `mvn test` on
   JDK 21.
6. Review the final diff for trace hydration, game/zone/frame carve-outs,
   direct player writes in both publication paths, generated files, and docs
   obligations.

## Task 6: Integrate and compare

1. Fetch and fast-forward the main-workspace `develop` branch without touching
   unrelated user changes.
2. Record the full-suite result on that updated baseline.
3. Rebase or merge the updated baseline into the worktree branch if needed,
   rerun the full suite and focused tests, and resolve only attributable
   regressions.
4. Commit with required trailers, merge into main-workspace `develop`, rerun
   the full suite, and compare against the recorded baseline.
5. Push `develop`. After confirming the worktree is clean and merged, remove
   the worktree, delete the local implementation branch, and prune worktree
   metadata.
