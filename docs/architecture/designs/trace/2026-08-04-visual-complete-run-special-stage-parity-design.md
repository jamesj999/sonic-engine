# Visual complete-run special-stage parity design

## Problem and observed boundaries

The complete-run visual path has three different owners at the GHZ1 → S1
special-stage → GHZ2 seam:

* `SpecialStageEntryPresentationController` owns the visible fade/reveal.
* `TraceRunSpecialStageRowDriver` owns special-stage row pacing and dynamic-art
  publication, but the run launcher leaves the normal trace HUD and playback
  observer attached to the previous level.
* `PlaybackDebugManager` owns level input/comparison callbacks, but destination
  level admission installs a new comparator without replacing the playback
  frame observer.

The S1 provider also reports its entry presentation ready unconditionally,
even when `TRACE_ACCURATE` deliberately leaves the ROM startup hold observable.
That makes the visual reveal occur before the recorded startup rows have been
consumed. Finally, live capture shortcut modifier checks read the logical
trace-input override, so a physical capture chord can be hidden by recorded
input.

The return load exposes a second clock-domain mismatch. Level and bonus
segments advance `PlaybackDebugManager`'s shared BK2 cursor, but special-stage
segments deliberately advance `TraceRunSpecialStageRowDriver`'s local cursor.
The production return-load hook nevertheless timestamps `StageExit` and
`LevelLoaded` with the parked shared cursor. For the first emerald route this
reports the completed special-stage exit at BK2 row 4,976 instead of the local
clock's row 8,704/8,705 boundary. The coordinator correctly rejects that
out-of-window signal, while the visual adapter currently rebinds playback to
GHZ2 anyway. That splits input ownership from comparison ownership: GHZ2 input
starts advancing without a destination admission receipt until the strict
zero-or-one-row overrun guard fires.

The first correctly admitted GHZ2 row exposes a third, independent harness
split. S1 records `stage_exit.rings_after` when the ROM first changes its
coarse mode back to level, while the Special Stage ring tally is still live.
The engine's finer presentation modes do not expose the return boundary until
after the fresh next-act load, whose native level initialization correctly
clears rings. The shared boundary comparator currently compares those two
different phases. Headless hides the resulting false mismatch in an S1-only
test override; visual replay publishes it as an error. Thus the apparent
visual-only failure is caused by adapter-specific field filtering around a
comparison that is not temporally valid for a `NEXT_ACT` return.

## Design

1. **Use the provider's native startup readiness.** S1's provider will report
   `Sonic1SpecialStageManager.isEntryPresentationReady()`. FAST startup already
   consumes the hold during initialization, while TRACE_ACCURATE keeps it
   opaque until the production loop reaches the ROM reveal boundary. No trace
   row will be used to set provider state.

2. **Make special-stage ownership explicit at admission.** When a run admits a
   special-stage segment, install a special-stage HUD model for that segment.
   It will use the same layout, labels, input glyphs, completion banner, and
   recent comparison summary as the normal trace HUD, with row-driver dynamic
   art comparisons contributing errors/warnings. The HUD's input supplier will
   read the segment's physical BK2 row, not the last level comparator.
   When the segment closes, destination level admission replaces this model
   with the normal HUD for the new comparator.

3. **Rebind the playback observer with every shared-clock destination.**
   The run's `BoundaryProbe` remains the sole
   `PlaybackDebugManager` frame observer: it forwards input/lag/comparison
   callbacks to its current delegate and also captures transient
   giant-ring/bonus requests that are visible only inside that callback.
   Destination admission will replace the probe's delegate with the new
   comparator before the destination's first production row. This restores
   input-offset calculation and live desync pause/reporting for GHZ2 and every
   later level without losing later transient boundary latches.

4. **Keep global capture shortcuts physical.** Add a raw physical modifier
   query to `InputHandler` and use it only for the live-capture chord. Logical
   trace overrides continue to drive gameplay, while the user's physical
   capture key is not hidden by a recorded modifier state during ordinary live
   capture. Existing trace/test-mode recording ownership guards remain in
   force; this change does not bypass them. Existing chord semantics (rising
   edge and exact modifiers) remain unchanged.

5. **Translate boundary observations through the active segment clock.** The
   launcher will derive the physical BK2 boundary row from the coordinator's
   active segment. Shared-clock level/bonus segments continue to use
   `PlaybackDebugManager`; a special-stage source uses its segment offset plus
   the committed local row cursor, retained when the strict row driver closes.
   Both the immediate production return-load signal and any later
   boundary-probe `stage_exit` forwarding use this resolver; the probe's
   shared-clock observation cannot overwrite the source segment's clock.
   This value is observation-only: it timestamps an engine-created load and
   never writes gameplay state or selects a route outcome.

6. **Couple level rebind to coordinator acceptance.** A completed production
   load may arm the destination BK2 rebind only when the coordinator retained
   that exact `LevelLoaded` signal as the pending destination receipt. An
   identity, cause, generation, or boundary-window rejection leaves playback
   parked. Accepted loads may rebind while the source is either closing or
   already in `TRANSITION_GAP`; the title card does not drive playback, so the
   accepted destination row remains at zero until the common release seam
   admits comparison and input ownership together.

7. **Make return-field applicability a comparator contract.** Three approaches
   were considered: keep separate adapter filters, add explicit ring-sampling
   phase metadata and regenerate every run, or derive applicability from the
   existing manifest-owned `ReturnAssertionMode`. The third is the smallest
   shared correction. `TraceRunBoundaryComparator` will omit the post-load ring
   field for `NEXT_ACT`, because that mode structurally identifies a fresh act
   rather than a positional return. It will retain exact ring comparison for
   positional, checkpoint, and rings/emeralds-only returns, where the recorded
   value and engine snapshot describe the same settled return phase. The
   headless S1 subclass filter will be removed so both harnesses consume the
   comparator result unchanged. Emerald progression and next-act identity
   checks remain exact. This is keyed on manifest semantics, not game, zone,
   route, or frame identity, and it never writes gameplay state.

## Error and transition handling

The existing run coordinator remains the single transition authority. A HUD
switch is performed only by destination admission; if admission fails, the
existing failure path returns to the trace picker and records the diagnostic.
The S1 readiness change preserves normal interactive startup because FAST mode
finishes the same manager-owned hold before `begin()` is called.

## Verification

Add focused tests for:

* S1 TRACE_ACCURATE readiness staying opaque until manager startup completes,
  while FAST remains immediately ready; an integration step asserts the
  44-tick hold, provider reveal/music/fade ordering, and row cursor alignment.
* run destination comparator installation replacing the boundary probe's
  delegate while the probe remains installed, including a later transient
  boundary latch;
* special-stage HUD progress/input/completion state and dynamic-art mismatch
  counts;
* capture chord matching physical modifiers while a logical trace override is
  active, without bypassing trace/test-mode ownership guards.
* a special-stage local cursor at its terminal row producing an in-window
  `stage_exit` signal even while the shared level cursor remains parked at the
  special-stage entry, including the later latched-boundary forwarding path;
* rejected return loads being unable to activate a destination playback
  rebind, and an accepted return load admitting GHZ2 at row zero.
* a `NEXT_ACT` return whose manifest carries a Special Stage exit-ring tally
  omitting the temporally invalid post-load ring comparison in both harnesses;
* positional, checkpoint, and rings/emeralds-only returns retaining exact ring
  comparison, preventing the shared rule from weakening any other return
  policy;
* the visual launcher ingesting the common `NEXT_ACT` comparison unchanged and
  publishing no ring mismatch for the settled destination snapshot;
* removal of the headless-only S1 field filter.

Run the existing complete-run visual launcher tests, S1 special-stage replay
tests, run coordinator tests, capture tests, and the full `*TraceReplay` sweep.
Record any frontier movement in `docs/status/trace-frontier-log.md`.
