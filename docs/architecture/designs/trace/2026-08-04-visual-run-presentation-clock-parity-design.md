# Visual run presentation-clock parity design

Date: 2026-08-04

## Problem

Whole-run replay treated represented level and special-stage rows as trace
time, but stopped advancing the BK2 movie while native results screens, fades,
level loads, and title cards owned the game loop. Headless replay already drove
those physical rows while the visual adapter waited for gameplay to return.
The split produced three related failures at the S1 special-stage return:

- the visual cursor resumed at the special-stage input window instead of the
  GHZ2 window;
- the queue and dynamic-art gap comparison accumulated work from the wrong
  physical rows; and
- destination comparison was not active while the incorrect input was being
  consumed, so the visible desync could escape detection.

Treating all intervening rows as gameplay is also wrong. Some recorded rows
own native presentation production, some are VBlank-starved closures, and
some are pure offset/gap rows. A second level load or a cursor seek would hide
the clock error while repeating music, level setup, and production submissions.

## Invariants

1. One physical BK2 row is prepared and advanced exactly once per outer trace
   run step, regardless of which game mode owns presentation.
2. Gameplay comparison runs only for gameplay segments. Presentation bridges
   compare physical input, production queue state, and dynamic-art publication
   without reading or hydrating playable physics.
3. PLC, DPLC, and hardware-timing work is always production-created and
   serviced through its ordinary lifecycle. Trace data can delay matching work
   under the existing timing contract, never create or select it.
4. A segment boundary closes its comparison generation before the structural
   gap opens; the destination generation opens only after coordinator
   admission.
5. The visual and headless adapters consume the same segment execution policy.
   They share physical-row driving for presentation, gap, and terminal-tail
   ownership plus the offset-handoff rejection contract; established headless
   gameplay retains its fixture step loop. UI and rendering remain adapter
   responsibilities.
6. Transition playback never reloads or reseeks an already loaded level.

## Design

### Segment execution policy

`TraceRunReplayWalker` classifies each planned segment as `GAMEPLAY`,
`SPECIAL_LOCAL`, or `LEVEL_PRESENTATION_BRIDGE`. The classification is derived
from manifest boundaries and recorded row structure, not from game, zone,
route, fixture name, or an error frame. A stage-exit destination whose initial
rows represent results/title/loading presentation uses the bridge policy; the
later playable segment remains ordinary gameplay.

`TraceRunPlaybackCoordinator` carries this policy in destination receipts and
uses the plan's BK2 offsets for current-segment, transition-gap, handoff, and
terminal-tail clocks.

### Shared physical-row driver

`TraceRunFrameDriver` owns the order of operations for one row:

1. prepare the exact physical BK2 input;
2. prepare matching recorded hardware-timing admission;
3. snapshot dynamic-art publication state;
4. run the policy-selected production lifecycle, if any;
5. advance the physical movie row once;
6. snapshot and compare production output; and
7. release row ownership.

`TraceSessionLauncher` drives every visual run row through this owner.
`AbstractRunChainTest` uses the same owner for presentation, gap, and
terminal-tail rows, plus the same rejection contract for the rowless offset
handoff, while retaining its established fixture loop for ordinary gameplay.
The dispositions distinguish gameplay, special-local, native
presentation, suppressed presentation closure, pure advance, structural gap,
offset handoff, and terminal movie tail. `GameLoop` delegates physical cursor
advance to the active visual driver so level-mode fall-through cannot advance
a row a second time.

### Presentation structural comparison

`TraceStructuralRowComparator` validates only data that remains meaningful
while native presentation owns the loop:

- the physical P1 BK2 input represented by the row;
- all advertised PLC/load-queue snapshots; and
- the exact dynamic-art publication heartbeat and edges.

It intentionally omits position, velocity, camera, player animation, and other
playable state. Its input, lag, error/warning counters, and mismatch ring
implement the common `TraceHudModel`, allowing the normal trace overlay to
remain live through results and title-card rows.

### Recorded no-VBlank spans

A presentation row whose recorded VBlank counter does not advance must not run
a second native mode body. `TraceRunPresentationClosure` executes the shared
PLC/VBlank closure with the lag phase while retaining the current mode owner.
When a mode boundary completed synchronously immediately before the no-VBlank
span, the driver defers that boundary until the first later recorded closure.
This preserves the ROM-observed order without copying a recorded state value.

### Queue and dynamic-art gaps

The source comparison window is closed before entering its gap. Gap journal
edges are compared across the manifest-owned `[source end, destination open)`
interval, including terminal movie tails, and the destination's initial ledger
fingerprint is checked at admission. A single represented gap row runs the
same production lifecycle as headless; it is not reduced to a cursor-only
advance.

Fresh playable warm-up primes the native ROM-backed mapping/DPLC bank without
publishing a runtime edge. This represents level setup that precedes the
compared destination stream and prevents setup art from being misclassified as
the bridge's final runtime transfer.

### Input, HUD, and capture

Gameplay input is admitted only for a `GAMEPLAY_SHARED` disposition. Every
non-gameplay disposition that owns a host row applies its exact physical BK2
snapshot to the native mode owner; an offset handoff owns no host row or input.
A run-wide HUD adapter follows that physical
clock and publishes one run-sequenced diagnostic ring across every comparison
owner, so completing GHZ1 or a Special Stage cannot freeze the overlay on
`TRACE COMPLETE` during results, title-card, gap, or terminal-tail rows. Exact
bootstrap totals seed the accumulator independently of its bounded display
ring. A terminal gameplay row publishes its base fields first and only the
newly available DPLC delta after production closes, preventing duplicate
counts. Only the whole-run coordinator may publish completion. When a bridge
closes, destination gameplay admission replaces its comparison delegate
atomically without replacing or seeking the movie timeline.

The live-capture chord reads raw physical key and modifier state. Logical BK2
overrides continue to drive gameplay but cannot hide the user's capture-toggle
key during a trace.

## Failure handling

Lifecycle overlap, missing publication, invalid offset, duplicate row advance,
or premature segment closure is a hard ownership failure presented by the
visual trace error screen. Wrong input, queue differences, and other validly
produced comparison mismatches use the ordinary trace HUD and first-error pause
so the user can inspect or continue them. Neither path silently seeks, reloads,
skips a comparison row, or returns to gameplay.

## Verification

- Driver unit tests cover every disposition, ordering, cleanup, boundary
  deferral, and offset rejection.
- Structural comparator tests cover physical input, queue-only comparison,
  atomic dynamic-art publication, terminal closure, HUD counters, and no
  playable-state fields.
- Visual launcher/coordinator tests cover admission, stage-exit clock
  translation, gap ownership, common HUD replacement, and failure cleanup.
- PLC/dynamic-art tests cover suppressed closures, exact gap rows, and native
  playable art priming.
- The ROM-backed S1 GHZ1 -> special stage -> GHZ2 chain runs through all 812
  shared bridge rows and proves that the final row owns `LEVEL` with the GHZ2
  title card cleared at the exact destination offset. Terminal-tail unit tests
  cover both non-empty replay and a zero-row tail already stopped on the final
  movie row.
