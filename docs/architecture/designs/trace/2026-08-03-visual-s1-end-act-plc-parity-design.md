# Visual S1 replay-clock and end-act PLC parity

Date: 2026-08-03

## Problem

Two different defects surface as the same S1 Title Card PLC mismatch in visual
trace replay.

The non-emerald GHZ3 fixture already passes its standalone headless test, but
the visual run reaches the capsule exit with one extra Object 28 animal alive.
The failure log identifies the visual-only state difference: the ROM reports
`v_vblank_byte=0x4D21` while the visual engine reports `0x49AA`, a difference of
`0x377` (887) serviced VBlanks. Player physics still matches. The capsule uses
`v_vblank_byte & 7` to schedule explosions and animals, so the wrong object
clock changes allocation, motion, and deletion history before `Pri_EndAct`
scans for remaining animals. The idle PLC queue is a downstream symptom: the
capsule has not yet reached `GotThroughAct`.

The complete-with-emeralds GHZ1 route has a separate producer defect. Object
7C's ring flash represents deletion of the Sonic SST, but the engine directly
creates the results card from Object 7C. In the ROM, Object 7C only sets
`f_bigring` and deletes the Sonic SST. The already-spinning signpost observes
the empty player slot on its next execution, enters `Sign_LoadEndCards`, and
`GotThroughAct` creates `v_endcard` and executes `NewPLC(plcid_TitleCard)`.
Because the engine bypasses that signpost-owned handoff, its card exists while
the Title Card PLC queue remains idle.

## Source of truth

`TraceReplaySessionBootstrap.applyBootstrap` establishes the standalone
headless object clock before replay. It seeds `ObjectManager.vblaCounter` below
the trace's initial value, runs any omitted title-card object prelude, and
leaves the counter at `trace.initialVblankCounter() - 1`; the first driven
VBlank advances it to row zero. `applyPreparedLevelBootstrap`, used after the
real visual title card, omitted both the synthetic prelude and this final
rebase. That omission caused the initial prepared-level mode split.

A whole run has a second split. The headless chain's
`completeInterLevelVblankBudget` already accounts for ROM movie rows represented
by shortened engine act/title-card choreography. It derives the missing tick
budget from the source production clock plus manifest/BK2 row distances. The
visual `TraceSessionLauncher` previously inherited only the engine's shorter
transition clock and never applied that same run policy. This is why a
standalone GHZ3 bootstrap can pass while the non-emerald visual complete run
arrives at the capsule with a clock deficit.

The prepared visual context must preserve all state produced by the real title
card, but the trace owns the replay epoch's free-running object clock at the
same one-time bootstrap seam where the headless path establishes it. Rebasing
that clock once is equivalent to loading the BK2 start state; it is not
per-frame trace hydration and does not decide gameplay from comparison data.

For the emerald route, `Flash_Collect` in
`docs/s1disasm/_incObj/4B, 7C Giant Ring and Flash.asm` sets `f_bigring` at
flash frame 3 and clears the Sonic SST at flash frame 8. It does not create
`v_endcard` and does not call `GotThroughAct`. `Sign_SonicRun` in Object 0D
tests the player object ID, branches to `Sign_LoadEndCards` when it is zero,
then `GotThroughAct` claims `v_endcard` and calls `NewPLC(plcid_TitleCard)`.
The results card later reads `f_bigring` to choose the special-stage route.

## Design

### Prepared visual replay clock

Add one named replay-bootstrap operation in `TraceReplaySessionBootstrap` that
establishes the pre-row object clock:

`alignObjectVblankCounterForReplayStart(trace)` sets the current level's
`ObjectManager.vblaCounter` to `trace.initialVblankCounter() - 1`.

The subtraction deliberately uses the existing integer counter contract rather
than masking before the first tick. An initial recorded value of zero therefore
seeds `-1` and advances to exactly zero; nonzero and low-word wrap cases retain
the same ROM-visible low bits consumed by S1 object gates. Tests cover both a
zero start and a nonzero value near the 16-bit boundary.

Both bootstrap paths use the same operation at the point immediately before
the replay-start state and comparator are installed:

- the ordinary/headless path calls it after its synthetic object prelude, so
  it preserves the existing final value while making the invariant explicit;
- the prepared visual path calls it after the real title card has released,
  without replaying or discarding any title-card state.

The first driven logical iteration then advances to the recorded row-zero
value in both modes. Level frame counters, V-int phase offsets, PLC state,
dynamic-art state, RNG, and title-card objects retain their existing owners.
No game, zone, run, frame-number exception, or comparator field is consulted.
For later run destinations, add a run-scoped `TraceRunVblankClock`. When a
represented level source closes, it records the source-tail clock projected
from the production BK2 cursor. When a level destination is admitted, it uses
the same `TraceRunReplayWalker.interLevelVblankBudget` and
`uncomparedInteriorReturnVblankBudget` functions already used by the headless
chain, then initializes the newly loaded object manager to source tail plus the
manifest-derived tick budget. Disabled game profiles do nothing. The operation
does not read a physics/comparison row, and it cannot choose gameplay state: it
only preserves a free-running ROM clock across host choreography the engine
models synchronously or with fewer frames.

The run clock's public authority is intentionally narrow: it accepts only the
game's `TracePlaybackProfile`, individual `TraceRunManifest.Segment` timing
descriptors, segment indices, and observed production BK2/VBlank counters.
Run topology remains the launcher's responsibility. The clock cannot accept a
`SegmentPlan`, `TraceData`, `TraceFrame`, comparator, auxiliary-state stream,
dynamic-art journal, or hardware-timing schedule. A structural guard locks this
API and dependency boundary so a later parity fix cannot turn clock pacing into
trace-driven gameplay hydration.

Destination admission also aligns the newly opened dynamic-art publication
window with the coordinator receipt's `rowsConsumed` value before adopting the
production wrapper. This is the same cursor-only operation used by the headless
chain: it does not advance the shared movie clock or manufacture an art edge.
Both zero-row and one-row admission are covered at the production visual
launcher seam.

### Giant-ring handoff

Remove results-card creation and act-clear music ownership from Object 7C.
At flash completion it continues to mark the retained engine player as the
structural equivalent of an absent Sonic SST. If a results card already exists
because of a fast/glitched ordering, Object 7C may still set its
`specialStageAfter` route bit, matching the card's later read of `f_bigring`.

Teach `Sonic1SignpostObjectInstance.updateWalkOff` to recognize the engine's
specific retained-player representation of the ROM's empty Sonic SST: the S1
big-ring flag is set, the player is hidden, and native bit-7 object control is
holding movement with CPU control disabled. This semantic conjunction cannot
match ordinary object-control users. It branches directly to the existing
signpost `triggerGotThroughAct`, just as the ROM branches around the airborne
and position checks.

When the signpost creates a new results card, it initializes
`specialStageAfter` from the live S1 big-ring flag. The existing-card guard
remains idempotent: it neither creates a second fixed card nor resubmits cue
16.

Model `v_endcard` as the reserved absolute S1 SST slot 23, not as an allocated
dynamic child. A small S1-owned `Sonic1FixedEndCardSlot` operation finds or
installs `Sonic1ResultsScreenObjectInstance` at that exact slot through the
approved `ObjectLifetimeOps` lifecycle owner, which delegates to the object
manager's explicit-slot registration path. A marker distinguishes this
game-owned fixed runtime execution class during rewind restore, so an unrelated
dynamic object that happens to occupy a low explicit slot is never reclassified.
The S1 fixed-object pass
executes the slot before `v_lvlobjspace`, while the ordinary object collection
continues to render it. The object remains a normal rewind-recreatable entry
with its absolute slot identity; it is not an auxiliary/uncaptured overlay.
Because slot 23 is outside S1's allocatable range (32-127), a full dynamic pool
cannot reject it. An occupied live slot is treated as the existing fixed card;
construction failure propagates and leaves the producer pending.

The signpost and prison claim the fixed card in an explicitly uncommitted state,
then call `Sonic1PlcService.replaceQueued(16)` before setting their local
completion bit. `replaceQueued` preflights parsing/capacity and rejects an
active decoder before mutating the queue. A rejection leaves both the card and
caller pending so the next update retries. Fixed-slot claim is synchronous and
cannot fail from dynamic-pool pressure; an unexpected occupied slot or
constructor failure is fail-closed before cue 16 is touched.

No new mutable global or rewind state is introduced. Existing player control,
game-state big-ring, results-card route, object routine, and PLC queue state are
already captured.

## Verification

- A prepared-bootstrap test starts with an arbitrary title-card-era object
  counter and proves activation rebases it to initial VBlank minus one, while
  the ordinary bootstrap still ends at the identical pre-row value.
- A visual-launch integration test proves the first driven row observes the
  trace VBlank value and the standalone headless and prepared paths agree.
- The existing non-emerald `ghz3_completerun` headless replay remains green;
  run-clock tests prove the visual launcher uses the same two GHZ transition
  budgets (230 and 229 ticks) as the headless chain and applies both targets to
  the destination object manager before the capsule results PLC boundary;
- launcher coverage applies an uncompared-interior return target to the loaded
  destination object manager, and an authority guard rejects comparison,
  auxiliary, dynamic-art, or hardware-timing inputs to the run clock.
- Object 7C tests prove flash completion does not create a results card or play
  act-clear music when the signpost handoff is pending.
- Signpost producer tests prove the retained deleted-SST representation submits
  exact ROM cue 16 once, creates one special-stage-bound results card, and
  leaves queue/card ownership unchanged when a card already exists.
- Existing signpost, prison, GHZ1, GHZ3, PLC producer, rewind guard, and trace
  replay suites remain green. Full continuous emerald-route coverage is
  deferred: isolation showed its second special-stage fixture already diverges
  in the standalone S1 special-stage comparator at frame 2162, so it cannot be
  used as evidence for this visual/headless lifecycle fix until that independent
  gameplay discrepancy is resolved.
