# Visual run presentation-clock parity implementation plan

Date: 2026-08-04

Design: `docs/architecture/designs/trace/2026-08-04-visual-run-presentation-clock-parity-design.md`

## 1. Lock the policy and physical-row contract

- Add segment-policy catalog tests for committed stage-exit destinations.
- Add red tests for gameplay, special-local, presentation, gap, handoff, and
  terminal-tail dispositions.
- Implement `TraceRunFrameDriver` and carry execution policy through the run
  plan, coordinator, and destination receipt.

## 2. Drive visual playback in every run phase

- Install the frame driver in the session-owned gameplay context.
- Route the visual production wrapper and physical BK2 advance through the
  driver.
- Keep logical gameplay/input dispatch disabled for non-gameplay dispositions.
- Continue movie playback in transition gaps and terminal tails without a seek
  or second level load.

## 3. Compare presentation rows structurally

- Add `TraceStructuralRowComparator` with physical-input, queue, and
  dynamic-art-only scope.
- Add queue-only binder support and terminal publication finalization.
- Bind the common trace HUD to structural counters and current physical input
  during presentation bridges.
- Accumulate bootstrap, gameplay, special-stage, gap, boundary, and terminal
  diagnostics in one run-sequenced ring; publish terminal DPLC fields as a
  delta so the terminal base comparison is counted once.
- Keep `TRACE COMPLETE` run-scoped rather than segment-scoped, including
  special-stage, transition-gap, and terminal-tail presentation.

## 4. Preserve native transition ownership

- Model recorded no-VBlank spans as suppressed PLC/VBlank closures.
- Defer a synchronously completed special-stage boundary until the first later
  recorded closure.
- Prevent title-card release from falling through into gameplay before the
  gameplay segment is admitted.
- Correct S1's special-stage finish-loop timer so the activation row does not
  also consume the first delay tick.

## 5. Converge dynamic-art and queue gaps

- Close source comparison before opening the structural gap.
- Compare gap edges and destination ledger identity over manifest-derived
  source/destination bounds.
- Make exact single-row gaps run production rather than cursor-only advance.
- Prime fresh playable setup art without publishing a runtime transfer edge.

## 6. Preserve global controls

- Read the live-capture chord from raw physical keys and modifiers while trace
  logical input remains active.
- Retain existing trace/session ownership policy for recording and rewind.

## 7. Verify and integrate

- Run the focused frame-driver, launcher, coordinator, structural comparator,
  PLC/dynamic-art, S1 special-stage, HUD, and capture tests.
- Run a bounded ROM-backed S1 maze round-trip through all 812 special-stage
  return-presentation rows and assert exact GHZ2 level/title-card handoff.
- Run a bounded end-to-end coordinator/driver lane through `CompleteRun` and
  its terminal dynamic-art gap. Keep exhaustive true-movie-end playback as a
  manual validation: the committed maze fixture has 186,000+ unrepresented
  tail rows and is not a suitable focused-test gate.
- Preserve the existing endpoint contract: `expected_movie_end_mode` describes
  actual BK2 EOF. Runtime must never infer an earlier cutoff from final-segment
  closure; only the automated verification is bounded.
- Run the full JDK-21 suite with all discovered ROM properties and compare it
  with an updated `develop` baseline.
- Update `CHANGELOG.md` and `docs/status/trace-frontier-log.md`, request code
  review, resolve all valid findings, merge into `develop`, rerun verification,
  push `develop`, and remove the clean feature worktree and branch.
