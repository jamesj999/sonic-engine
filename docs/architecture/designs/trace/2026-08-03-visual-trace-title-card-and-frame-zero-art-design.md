# Visual trace single-load replay and special-stage presentation parity

Date: 2026-08-03

## Problem

The visual trace launcher has accumulated several boundaries that differ from
the headless replay path and from ordinary production presentation:

1. level-backed traces load the selected zone once to show its title card,
   destroy that runtime, and load the same zone again to begin replay. This
   restarts music and repeats level, PLC, and dynamic-art initialization;
2. level playback writes BK2 input into `AbstractPlayableSprite`'s scripted
   forced-input latch. A later visual-frame bridge therefore overwrites
   game-owned input such as S1's signpost walk-off. In GHZ1 this first diverges
   at trace frame 4998 while the headless logical-input path remains green;
3. direct special-stage traces create no comparator and consequently install
   no HUD, even though the trace cursor and recorded input are active;
4. direct special-stage launch initializes the audio profile and ROM but not
   the game's `GameSound` routing map, so S1 ring collection falls through to
   the non-SMPS fallback instead of resolving the alternating ring IDs; and
5. standalone S1 special-stage completion can already be holding a white fade.
   The launcher's generic fade-to-black call cannot take ownership of that
   state, so its teardown callback never runs and the session remains white.

The fixes must retain exact PLC/DPLC and hardware-timing comparison. They must
not filter diagnostics, copy trace gameplay state into the engine, or add a
game/zone/frame exception.

## Constraints

- A level-backed visual trace loads its initial zone exactly once.
- The production title card is part of the visual prelude. It consumes no BK2
  rows and no comparison rows.
- Title-card work uses live hardware readiness. Recorded hardware timing begins
  only after every production-submitted prelude job has completed and been
  claimed; no pending work may be cleared to cross the boundary.
- The prepared level, player team, object manager, PLC owners, camera, and music
  remain in the same gameplay context across title-card release and replay
  activation.
- Playback input must use the same logical-input abstraction as headless replay
  and remain lower priority than game-owned forced input/control locks.
- Dynamic-art comparison remains atomic and exact. Prelude transitions live in
  the unobserved gap; comparison row zero starts in a fresh segment generation.
- Special-stage HUD and exit behavior must not invent a physics comparator.
- Standalone special stages return to the visual trace picker at the terminal
  trace row. Complete-run special-stage transfer remains owned by the run
  coordinator.

## Options considered

### Suppress the second load's music

This hides the audible symptom while retaining duplicate level initialization,
PLC submissions, object creation, and lifecycle ownership. It is rejected.

### Keep a disposable title-card context and reload into a replay context

This isolates recorded timing, but the same zone is loaded twice and the title
card the user sees is not the runtime that continues into replay. It is
rejected by the single-load invariant.

### Run the title card and replay in one context with an explicit timing epoch

This is the selected design. The initial context loads the level once under
live timing, displays the real title card, and continues into replay. At the
handoff, a checked timing operation proves the live prelude has no unclaimed
hardware jobs, retires its completed diagnostic epoch, and begins recorded
admission with fresh ordinals when the trace has a hardware schedule. No level,
queue facade, runtime-art coordinator, or gameplay manager is recreated.

## Design

### 1. One level load and two lifecycle phases

`TraceSessionLauncher` retains its structural launch phases but changes their
ownership:

- `TITLE_CARD_PRESENTATION`: reset per-level subsystems, register the recorded
  team, load zone/act once, consume the automatic request, and enter the normal
  production title card. Playback, comparator, rewind, and external comparison
  ownership remain absent.
- `REPLAY_BOOTSTRAP`: on the title card's production control-release step, the
  loop returns to `LEVEL` while the text/pieces may still be in their ordinary
  gameplay-phase exit tail. Adopt the already-loaded runtime at that boundary.
  Do not wait for `isComplete()`, reopen a gameplay context, reset the team, or
  call `loadZoneAndAct`.
- `ACTIVE`: install playback, comparison, HUD/ghost, rewind, and run-boundary
  ownership against that same runtime.

The reset that gives the visual launch a deterministic base remains before the
single load. The replay driver gains an explicit prepared-level entry point.
Its ordinary `start(zone, act)` remains the headless/capture entry point and
continues to reset and load. Both paths converge on one activation method for
start-position policy, bootstrap state, counter alignment, playback, and
comparator installation.

Control release needs one structural admission barrier. S1/S3K commonly still
have a pending initial `Process_Sprites` token at release, which already makes
the release iteration setup-only. S2 can consume that token earlier because it
runs player physics during the locked card; its release would otherwise fall
through into one ordinary `LEVEL` frame before the between-iterations launcher
hook can install playback. While the visual launch phase owns the title-card
presentation, `LevelIterationAdmissionController` therefore converts the
single release iteration to `SETUP_ONLY` after all production release setup and
mode changes have completed. It claims that boundary whether the production
release result was already `SETUP_ONLY` (the usual S1/S3K path) or would have
been `GAMEPLAY_FRAME` (the S2 path). The barrier is consumed once and carries
no game, zone, trace, frame, input, or expected value. Replay activation then
runs between iterations, and the next host iteration is comparison/BK2 row
zero.

The prepared-level path tells bootstrap that the production title card's locked
prelude and release setup already ran. Synthetic title-card object, sidekick,
animated-tile, oscillation, and initial setup passes used by headless omitted-
presentation replay are not run a second time. Non-presentation bootstrap
policy, hardware schedule installation, allowed metadata start placement,
counter alignment, and comparator setup remain shared. The title-card provider
is neither reset nor fast-forwarded at handoff: its real exit tail continues to
update and render over the first compared gameplay rows. This matches the
headless provider's `beginOmittedPresentationExitTail` boundary without letting
unrecorded gameplay run while the launcher waits for the overlay to disappear.

Because `LevelManager.initAudio` executes only during the one load, level music
starts once and remains continuous when the card releases.

### 2. Checked live-to-recorded hardware epoch handoff

The gameplay context initially uses live hardware readiness so title-card PLC,
DPLC, and Kosinski work follows production timing without consulting a trace
schedule that does not describe the visual prelude.

For a trace with recorded timing, replay activation asks the existing
`HardwareTimingService` to begin a recorded epoch after its live prelude. The
operation is legal only when:

- recorded admission is not already active;
- every submitted live job has been claimed by its production coordinator; and
- no runtime-art owner reports an outstanding prepared/active transfer at the
  handoff boundary.

Only after those checks may the service discard completed live diagnostic jobs,
clear the live epoch's per-kind ordinal counters and serviced-boundary marker,
and install recorded admission. Before any replay production submission, the
normal `HardwareTimingReplayPort.install` initializes each recorded kind to the
schedule's declared first ordinal. That base is often nonzero in complete runs;
the new epoch is fresh, not universally zero-based. The service object and all
coordinators that reference it remain the same. The returned narrow
`RecordedCompletionAuthority` is stored by the current
`GameplayModeContext` and used by the normal replay port. The operation changes
only when future real jobs become ready; it cannot create work or mutate
gameplay.

Traces without a hardware schedule keep live admission. A failed drain check is
a launch error shown by the existing trace failure path; pending work is never
dropped to force activation.

### 3. Dynamic-art comparison boundary

Title-card and load-time dynamic-art events occur before external comparison
ownership. They are unobserved prelude diagnostics; the design does not require
them to be represented specifically as gap-journal edges because automatic
production windows may already have published them.

Segment zero reserves a fresh external comparison generation before the first
replay iteration. Reservation closes the last published automatic window,
increments the generation, and exposes a real unpublished origin snapshot, but
does not yet open the window for production. The launcher's pre-iteration pull
therefore observes this unpublished origin rather than the preceding published
automatic row. On the next claim, normal production service runs first. If any
work remains, the existing no-pending-work guard rejects activation; otherwise
the coordinator activates the reserved generation without incrementing it a
second time. Iteration closure publishes row zero in that same generation.

The comparator keeps its ordinary atomic contract: delivery serial advances,
the before snapshot is unpublished, the after snapshot is published, generation
is stable, and the published row is the expected row. No adjacent-generation
exception or one-shot comparator authorization is needed. Edges and outstanding
transfer identities are compared exactly. Cancelling before activation abandons
the reservation without publishing a row and restores automatic ownership.
Later complete-run segment boundaries remain immediate and production-driven.

Reserved/active/absent segment ownership is part of
`DynamicArtLifecycleService` rewind state. Capturing the initial visual rewind
frame occurs after reservation but before the first replay row, so restoring
that frame must restore the same unpublished reserved generation. Activation
after restore reuses that generation; it may not treat the reservation as
closed or increment again.

### 4. Replay input is logical input

`PlaybackDebugManager` exposes the prepared applied BK2 row and its physical
predecessor as a `LogicalInputSnapshot`. It preserves the existing latched
action-press behavior across advance-only/lag rows while retaining P1, P2,
Start, and debug modifier semantics.

An extracted `PlaybackInputBridge` installs that snapshot on `InputHandler`
and stops using `AbstractPlayableSprite.setForcedInputMask`. It retains the
existing playback-suppression marker so sprite publication cannot fall back to
live input, but `SpriteManager` explicitly admits the owned logical override as
the playback source. `SpriteManager` then resolves
the ROM-visible words explicitly: the recorded snapshot remains the raw
controller word, while game-owned control locks and forced latches produce the
logical movement word. A forced-right latch suppresses an opposing recorded
left bit in movement and logical history, while the raw word remains left as it
would on hardware. The resolved logical word is published once; the later
publisher must not OR the recorded conflict back into it. P2 and Start retain
their raw recorded semantics. When playback stops or leaves a driven mode, the
bridge clears only the logical override it owns and refreshes the live logical
snapshot before any same-step gameplay can consume it; it never clears a
scripted forced-input latch.

Recorded Start remains ROM input, not an engine UI command. While playback is
driving, the visible user-pause branch ignores Start from the published BK2
logical snapshot; the existing forced-Start admission path still delivers it
to ROM gameplay. The configured live pause key remains active so a user can
pause a visual replay deliberately.

The same helper is used at synchronous complete-run level-load activation,
where the destination's first gameplay tick can fall through before the next
loop-top bridge, and by `VisualTraceRewindStepper` while it deterministically
replays forward. Those paths must not retain the old forced-mask shortcut or a
signpost/control-lock divergence would reappear after a zone transition or
rewind. Standalone/run special-stage input already uses logical overrides and
keeps its current per-row ownership/cleanup.

This makes visual level/bonus playback use the same input channel as headless
replay and special-stage replay. S1 signpost forced-right therefore survives
the next BK2 sync without a signpost- or frame-specific branch.

The bridge is a small stateful collaborator rather than additional ownership
inside `GameLoop`: it tracks whether suppression and the logical override are
its own, allowing immediate same-step publication to be cleared reliably when
playback ends while keeping the release-critical loop below its source-size
ratchet.

### 5. Direct special-stage audio and HUD

Direct special-stage launch performs the same game-audio routing setup normally
completed by level loading: audio profile, ROM, the active module profile's
`GameSound` map, and ring alternation reset. It does not start level music.
Special-stage presentation remains responsible for starting its own music at
the recorded reveal boundary. S1 `GameSound.RING` consequently resolves to the
normal alternating SMPS ring commands.

The launcher's render owner is generalized behind a small trace-overlay
interface. Level traces keep `TraceHudOverlay`. Standalone special stages
install a dedicated overlay backed by their typed row source, movie offset,
and current cursor. It displays trace progress and whether the current row is
playable or lagged, without fabricating physics errors or a
`LiveTraceComparator`.

### 6. Standalone special-stage terminal state

At the standalone terminal row, hardware replay closes first. If the gameplay
fade is idle, the existing fade-to-black teardown is used. If S1 already owns
`HOLD_WHITE` with no completion callback, the launcher treats that as an
already-opaque terminal boundary and requests teardown through the existing
`teardownPending` path. It does not destroy the gameplay context from inside
the production iteration: post-iteration dynamic-art publication and run
boundary drains finish first, then the all-mode teardown retry returns to the
picker. Engine teardown recreates the master trace picker and starts its
graphics-owned fade from black, so the gameplay fade cannot strand the new
screen.

Other active fades with pending completions are not overwritten. The launcher
marks standalone terminal exit pending and retries from its all-mode,
between-iterations owner, allowing the existing callback to run even if it
changes mode. It then re-evaluates the resulting state until it can take the
idle or callback-free-white terminal path. If the callback completes into an
unsupported non-progressing hold, the launcher records a structural failure
and uses normal deferred cleanup rather than leaving the session active. Run
special stages do not use this standalone terminal branch.

## Failure and loading presentation

Run parsing remains off the render thread only where already supported; this
change does not broaden asynchronous ownership. Existing launch status remains
the source for picker-visible errors and loading state. All newly checked
handoffs report their exact failure there and clean up playback, timing,
dynamic-art ownership, logical input overrides, audio routing state, and
configuration before returning to the picker.

## Testing

- Context-identity tests prove title-card presentation and replay activation
  retain the same gameplay context and managers. A driver/launcher structural
  guard pins the prepared activation path against level load, team reset,
  context reopen, or music commands.
- A launch-phase test proves activation occurs on the `TITLE_CARD`→`LEVEL`
  control-release step while `isOverlayActive()` is still true, and that the
  real tail continues over replay rather than consuming uncontrolled frames.
- An S2 release test consumes the initial setup token during the locked loop,
  proves the visual launch barrier still prevents same-step `LEVEL` gameplay,
  then proves the next iteration owns BK2/comparison row zero. S1/S3K coverage
  proves an already-`SETUP_ONLY` production release still arms replay without
  repeating their release setup pass.
- Hardware timing tests prove a fully claimed live prelude can begin a fresh
  recorded epoch, including a schedule whose first ordinal is nonzero, while
  pending/unclaimed production work rejects the handoff without mutation.
- Bootstrap tests prove the prepared-level path omits synthetic title-card
  preludes but retains shared start, counter, timing, and comparator setup.
- A launcher-through-coordinator dynamic-art test proves reservation exposes an
  unpublished generation before production, activates after service, and
  publishes exact row zero in the same generation; cancellation and pending
  work retain their strict guards.
- A rewind round-trip test captures a reserved unpublished generation,
  activates/mutates it, restores the snapshot, and proves the same generation
  is reserved and later activates without an increment.
- Input bridge tests prove BK2 directions reach ordinary movement, P2/Start and
  held action edges remain correct, and a scripted forced-right latch survives
  a later playback sync, synchronous destination load, and replayed rewind
  step. Cleanup refreshes live logical input in the same step, and a behavioral
  BK2 Start-edge test proves recorded Start cannot toggle the visible/audio
  pause while the configured live pause key still can. The S1 GHZ1 complete-run
  headless test remains green, and the logical bridge regression models the
  opposing BK2/signpost ownership that caused the former visual-only frame-4998
  divergence.
- Audio tests prove direct S1 special-stage launch installs the profile sound
  map and alternating ring commands use SMPS routing rather than fallback.
- Overlay tests prove a standalone special stage renders progress/input without
  a comparator.
- Terminal lifecycle tests place the fade in `HOLD_WHITE`, advance the terminal
  row inside a production iteration, prove the context remains valid through
  post-production publication, and then prove deferred teardown returns to the
  picker rather than remaining active.
- A callback-bearing terminal fade test proves the launcher never replaces the
  callback, retries after it executes (including a mode change), and reaches
  either the supported teardown path or a recorded structural failure rather
  than a permanent pending state.
- Cross-game level/run, PLC lifecycle, hardware-authority, special-stage, and
  failure-cleanup suites show no regression.

## Follow-up boundary

Initial level-backed standalone traces and complete runs use this single-load
handoff. End-to-end transitions between later zones and special stages remain
owned by the existing complete-run coordinator; extending the same visible
loading/error treatment and rewind support across every run boundary is a
separate task unless required by a focused regression uncovered here.
