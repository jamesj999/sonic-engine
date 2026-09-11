# Visual special-stage handoff audio and input parity implementation plan

## 1. Lock the design with regression tests

- Add focused tests for new-fade and HOLD_BLACK SFX ownership, plus the S1
  HOLD_WHITE path's zero additional generic calls.
- Add a playback/launcher regression that starts a synthetic special-stage
  segment, clears its logical override, rebinds at the GHZ2 absolute BK2 row,
  and asserts the destination row is applied through the persistent
  `BoundaryProbe`.
- Run the focused tests and confirm the new behavior is red before changing
  production code.

## 2. Implement the smallest lifecycle fixes

- Guard the generic entry SFX with the semantic `screenAlreadyFaded` state;
  retain S1's fade-time SFX.
- Ensure destination session activation resets prepared input/edge state and
  preserves the probe as the observer.  If the regression identifies a stale
  bridge owner, clear that owner at the special-stage-to-level boundary rather
  than adding a route-specific input branch.
- Publish the destination row immediately after a direct destination admission
  so a no-title-card load cannot fall through with stale input.
- Invoke the same destination admission at the shared title-card release seam,
  after the mode changes to `LEVEL` and before the release step can fall through
  to gameplay; pass the measured zero/one destination-row count so setup-only
  results returns cannot miss the handoff.
- Build the release observation with the explicit post-release predicate rather
  than a stale level-load request bit, while retaining all native level identity
  and production ownership values.
- Replace `RunLevelLoadTracker`'s `LevelData` reference comparison with a
  `LevelManager`-owned completed-load generation, incremented once after each
  successful production load. Cover same-instance reload, unchanged generation,
  and failed-load behavior.
- Keep deferred scheduling valid for `level_load` transitions whose boundary
  probe intentionally has no latch; the coordinator's accepted load and
  transition-gap phase are the structural guard.

## 3. Verify and integrate

- Run the visual trace focused suite and all affected playback/run tests.
- Run `mvn -q -Dmse=off test` on the feature worktree and compare its exact
  failures with the baseline recorded before implementation.
- Update the trace frontier log with the command, baseline/post-change counts,
  and the newly protected boundary invariant.
- Commit with required trailers, merge into `develop` without switching the
  main workspace, update the release README summary, run the focused suite on
  merged `develop`, push `develop`, and remove only a clean/known-generated
  feature worktree.

## 4. Correct special-stage return clock ownership

- Add a launcher regression whose shared playback cursor remains at the first
  S1 special-stage offset while the real special row driver reaches its end.
  Assert that the production return-load signal uses the local segment clock,
  is retained by the coordinator, and admits GHZ2 at destination row zero.
- Add the negative half of the contract: an out-of-window or otherwise
  rejected `LevelLoaded` signal must not arm or activate the pending level-load
  playback rebind.
- Retain the special driver's committed local cursor when closing the segment,
  and resolve boundary timestamps from the current segment's declared input
  clock rather than unconditionally reading `PlaybackDebugManager`. Apply the
  resolver to both the production load hook and `stage_exit` conversion in
  `forwardLatchedRunBoundary`; reset/re-key the retained local cursor at every
  special-stage admission.
- Expose only a structural coordinator query for whether the exact load signal
  was retained; use it to gate the existing deferred rebind. Do not hydrate
  gameplay, infer success from cursor position, or add S1/zone/run branches.
- Verify red then green with the focused launcher/coordinator tests, followed
  by the complete visual run handoff selection and the headless S1 emerald
  prefix test.

## 5. Converge return-boundary ring applicability

- Add a failing `TestTraceRunBoundaryComparator` case for a `NEXT_ACT` return
  with `rings_after = 55` and a settled destination ring count of `0`. Assert
  that next-act identity and emerald comparisons remain present while
  `run_boundary.rings` is absent.
- Strengthen the positional, checkpoint, and rings/emeralds-only cases to
  assert that `run_boundary.rings` remains present and exact. Give the latter
  two a mismatching actual count and assert the ring error is reported, so an
  accidental suppression of all `giant_ring` or all non-positional returns
  cannot pass.
- Add a visual-launcher regression in `TestTraceSessionLauncherRunBranch` using
  the canonical S1 emerald segment plans and a real `LiveTraceComparator` sink.
  Invoke the launcher's return-boundary publication seam with a settled
  destination ring count of `0`; assert that the emitted external comparison
  retains next-act/emerald fields and contains no `run_boundary.rings`
  mismatch. This proves the visual adapter consumes the common result without
  its own field policy.
- Move field applicability into `TraceRunBoundaryComparator.compare`: derive
  the return mode once, and compare `rings_after` for every mode except
  `NEXT_ACT`. Do not add a game, zone, route, frame, or trace-name predicate.
- Delete `TestS1GhzMazeRoundTripChain.requireSharedBoundaryField` and the base
  test's adapter-filter hook. Headless and visual must ingest the same complete
  `FrameComparison` without post-comparison filtering.
- Update `TraceRunReplayWalker.ReturnAssertionMode` documentation so
  `NEXT_ACT` no longer promises a post-load ring assertion; retain the ring
  claim on the three co-temporal return modes.
- Run the comparator test red before production changes, then green. Verify
  `TestS1GhzMazeRoundTripChain`, visual launcher return-boundary coverage, the
  real S1 emerald prefix, coordinator tests, and architecture guards.
