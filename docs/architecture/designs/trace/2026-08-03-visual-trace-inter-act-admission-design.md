# Visual Trace Inter-Act Admission Design

## Problem

Visual whole-run playback can open a destination level segment before that
level owns production. In the S1 complete run, GHZ2 loads at BK2 row 6622
while `GameLoop` still reports `LEVEL`, but the new `LevelManager` also has a
pending initial title card. The run coordinator admits GHZ2 at the load seam,
opens its comparator and replay owners, then the next host step enters
`TITLE_CARD`. The coordinator correctly reports that segment 1 lost production
ownership because GHZ2 comparison was opened before GHZ2 gameplay began.

The production level load is not itself sufficient evidence that a level
destination is ready. A loaded level may still be owned by its initial title
card. Headless whole-run replay already waits for a pending title-card request
to settle before attaching the next level comparator.

## Constraints

- Preserve the engine-created level load and title-card choreography. Do not
  request a second load or skip the production title card.
- Keep trace data comparison-only. The readiness signal must come from live
  engine lifecycle state, not manifest identity, frame number, or CSV/aux data.
- Do not add game, zone, route, or frame carve-outs.
- Preserve immediate admission for loaded levels whose production presentation
  is suppressed or already complete, including seamless transitions.
- Keep visual and headless whole-run policy aligned through
  `TraceRunPlaybackCoordinator`.
- Do not weaken the source-ownership failure invariant. It remains valuable for
  detecting genuine comparator lifetime errors.

## Considered Approaches

### 1. Never admit from the level-load seam

`beforeLoadedLevelActivation` could only remember the load and always defer
admission to a later loop admission callback. This prevents the S1 failure but
unnecessarily changes seamless and presentation-suppressed transitions that
can legitimately enter destination production immediately. It also makes the
correctness of those paths depend on every caller reaching another admission
callback.

### 2. Gate the visual launcher directly

`TraceSessionLauncher` could avoid calling `beforeAdmission` whenever
`LevelManager.isTitleCardRequested()` is true. This is narrowly effective, but
it hides a structural readiness condition from the shared coordinator and
allows other adapters to repeat the same premature-admission bug.

### 3. Add title-card readiness to the structural observation

Add an `initialTitleCardPending` boolean to `RunPlaybackObservation`. Populate
it from `LevelManager.isTitleCardRequested()` and require it to be false when a
level destination is considered ready. This is the recommended approach: the
coordinator receives only value-free lifecycle state, visual and headless
adapters share the policy, and loads without pending presentation remain
immediately admissible.

## Design

### Structural observation

`RunPlaybackObservation` gains `boolean initialTitleCardPending`. This field
describes a live production ownership barrier; it does not carry gameplay
values or trace-derived data.

`TraceSessionLauncher.captureRunObservation` sets it from
`LevelManager.isTitleCardRequested()`. The synthetic owner observation created
by `withProductionOwner` keeps the current observation's barrier value because
the source identity is pinned only for final source publication; destination
readiness still belongs to the newly loaded live context.

Headless policy adapters and focused tests construct the same field from their
live level manager or explicitly provide a synthetic value. This keeps action
transcripts comparable between adapters.

### Destination admission

For a level segment, `TraceRunPlaybackCoordinator.destinationReady` continues
to require:

1. `GameMode.LEVEL`;
2. a matching remembered production level load and identity, or the existing
   same-level lag continuation contract; and
3. no pending initial title-card request.

The title-card condition is checked before either level-admission branch. A
matching load observed while the title card is pending remains remembered.
Nothing closes, seeks, or opens destination owners at that time.

When `GameLoop` consumes the request it enters `TITLE_CARD`, which also blocks
admission by mode. On title-card release, the existing
`prepareTraceRunAdmissionAndHardwareTiming` callback runs after the mode has
changed back to `LEVEL` and before the first destination production iteration.
At that point the request is no longer pending, so the remembered load is
admitted exactly once and row zero is attached at the production boundary.

For a presentation-suppressed load, no title-card request exists; the existing
load-seam or pre-production callback can admit immediately. No second level
load is introduced.

### Source ownership and failure behavior

The coordinator's `lost production ownership before source closure` guard is
unchanged. The fix prevents destination ownership from opening too early; it
does not teach the guard to tolerate incorrect ownership.

If the title card never releases, the coordinator stays in
`TRANSITION_GAP`. The existing admitted-step transition cap produces the same
user-visible failure path and diagnostic rather than hanging indefinitely.

## Data Flow

1. Production loads the next level and requests its initial title card.
2. `beforeRunLevelLoadPlaybackActivationIfActive` records the matching
   `LevelLoaded` signal and load generation.
3. The coordinator remembers the load but sees
   `initialTitleCardPending=true`, so it emits no `AdmitDestination` action.
4. The loop consumes and presents the title card while the BK2 cursor remains
   frozen in the transition gap.
5. Title-card release changes the mode to `LEVEL` and invokes the existing
   pre-production admission seam.
6. The coordinator observes the same remembered load with
   `initialTitleCardPending=false`, emits `AdmitDestination`, and the launcher
   opens GHZ2 comparison/input/timing/dynamic-art ownership before its first
   production row.

## Testing

- Extend the source-tail load regression with the reported ordering: observe a
  matching destination load during `CURRENT_SEGMENT`, publish and close the
  source, remain in `TRANSITION_GAP` while the initial title card is pending,
  and admit only after the barrier clears. This must not start from an already
  closed source because that would miss the premature load-seam admission.
- Extend launcher observation tests to prove the pending flag comes from the
  live `LevelManager` and survives source-owner pinning as destination state.
- Add a launcher-level action/owner regression proving the pending interval
  opens no destination comparator, playback input session, hardware-timing
  schedule, or dynamic-art comparison segment. This verifies the visual
  adapter behavior that the independently waiting headless chain cannot prove.
- Keep the existing test that rejects matching identity in `TITLE_CARD` mode.
- Keep immediate admission coverage for ordinary no-card and lag-only
  continuations.
- Run the focused coordinator, launcher, and admission-controller suites.
- Run the S1 GHZ1 and GHZ2 complete-run segment replays and the S1 whole-run
  chain coverage available in the repository.
- Run all `*TraceReplay` tests because the coordinator is shared across games.

## Documentation

Record the reproduced failure and fixed boundary in
`docs/status/trace-frontier-log.md`, including the worktree/commit context and
the exact focused and cross-game verification commands.

## Post-Delivery Row-Zero Publication Correction

### Observed failure

The title-card readiness barrier allows GHZ2 to reach its first production
iteration, but the following host step aborts with:

```text
dynamic-art comparison row was not drained after production: 0
Suppressed: dynamic-art row 0 was not published atomically after production
```

This is not a duplicate row-zero observation. `GameLoop.stepInternal()` calls
`TraceSessionLauncher.beforeProductionIteration()` before entering the shared
PLC lifecycle. At that instant the launcher's `comparator` still refers to the
closed GHZ1 segment, so `productionIterationComparator` captures GHZ1. During
the iteration, title-card release reaches the pre-production admission seam,
opens the GHZ2 dynamic-art generation, installs the GHZ2 comparator, and then
executes GHZ2 row zero. The post-production hook nevertheless drains the GHZ1
comparator captured at iteration entry. GHZ2 row zero remains pending until
row one attempts to queue another comparison and triggers the reported error.

### Correct ownership transfer

Destination admission already transfers timing, dynamic-art, comparator, and
input ownership before destination production. When that admission happens
inside an active production wrapper, it must also transfer the wrapper's
deferred post-production comparison owner to the newly installed comparator.

The transfer rebases `dynamicArtSnapshotBeforeIteration` immediately after the
destination dynamic-art segment has opened and the destination comparator has
been installed. This matters because opening the segment allocates a new
generation. Reusing the wrapper-entry snapshot would compare the destination's
publication against the preceding gap/source generation and correctly fail
the atomic-generation invariant.

The transfer is structural and value-free:

- it occurs only while `productionIterationInProgress` is true;
- it selects the comparator that destination admission just created;
- it captures only the immutable production diagnostics snapshot at that
  ownership seam; and
- it does not read expected trace rows, manifest values, game identity, zone,
  route, or frame number.

Admissions outside a production wrapper keep the existing behavior. Special
local comparison remains on its dedicated post-production path. Source
terminal comparison still finalizes before destination ownership opens.

### Revised production sequence

1. The host wrapper starts while GHZ1 remains the launcher's comparator and
   captures the wrapper-entry diagnostics snapshot.
2. The title card releases; the coordinator admits GHZ2 before level
   production.
3. Source timing handoff completes and the GHZ2 dynamic-art generation opens.
4. The launcher installs the GHZ2 comparator and transfers the active
   post-production owner to it, rebasing the before-snapshot in the GHZ2
   generation.
5. GHZ2 row zero executes and publishes its production snapshot.
6. `afterProductionIteration()` drains GHZ2 row zero against the rebased
   before-snapshot. Row one begins with no pending dynamic-art comparison.

### Additional regression coverage

Add a production-shaped launcher test with an already-published source
segment. Close the source window and enter the dynamic-art gap before the next
host production wrapper, deliberately leaving the closed source comparator
installed as the launcher does. Then begin that wrapper, open the destination
generation, install/adopt the destination comparator, observe destination row
zero, and finish the PLC lifecycle. Assert that the destination row is drained
in the same wrapper, its comparison is non-divergent, and a following
destination row can be queued without the `row was not drained` invariant
firing. This exact ordering prevents the test from passing only because source
close and destination admission happened within one wrapper.

Retain the existing negative coverage that a comparator row queued without a
matching production publication aborts the session. The fix transfers the
right owner and snapshot; it does not weaken atomic-publication validation.
