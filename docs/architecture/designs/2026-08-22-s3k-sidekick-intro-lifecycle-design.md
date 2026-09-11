# S3K intro sidekick presentation lifecycle

## Problem

Sonic 3&K creates Player 2 during `SpawnLevelMainSprites`, even when the
level's intro has not yet reached the point where Tails should appear. The ROM
does not remove that object. For AIZ1, the first ordinary Tails CPU dispatch
enters the `Current_zone_and_act == 0` dormant branch in
`loc_13A10` (`docs/skdisasm/sonic3k.asm:26389-26397`), which calls
`sub_13ECA` (`:26800-26809`) and writes the `$7F00/$0000` sentinel, routine
`$0A`, and `object_control=$83`. The AIZ resize routine later writes routine
`$02` at the palette handoff threshold `$1308`
(`docs/skdisasm/sonic3k.asm:38888-38905`).

The engine already models that gameplay lifecycle in
`SidekickCpuController`: AIZ's provider predicate is consumed on the first
ordinary CPU dispatch, and the AIZ event releases the marker at the same
threshold. The missing piece is the presentation boundary. The initial
setup-only `Process_Sprites` pass currently registers and positions Tails but
does not run the CPU routine; the generic renderer can therefore display the
normal Player 2 spawn before the ROM has emitted a display entry for it.
`Tails.draw()` also draws the independent Tails-tail child before checking its
own hidden/render state.

## Goals and invariants

1. AIZ Tails must be neither visible nor physically active during the setup
   presentation before the first ordinary CPU dispatch.
2. The first ordinary CPU dispatch must remain the sole owner of the AIZ
   gameplay marker. Setup must not move Tails to `$7F00`, change its CPU state,
   or manufacture `object_control=$83`.
3. The AIZ event release must be the sole owner of making the parked sidekick
   present again; it must clear only the presentation state introduced by this
   lifecycle.
4. The behavior must be selected by the existing semantic
   `LevelEventProvider.shouldEnterSidekickDormantMarker` predicate, not by a
   zone/name branch in shared sprite code.
5. A hidden Tails must suppress both the parent sprite and its independent
   tail child, matching the existing hidden contract used by Sonic and
   Knuckles.
6. The same setup seam must naturally cover ICZ's already-eager dormant
   marker path. It must not alter S2's zone suppression or respawn behavior.
7. Rewind and fresh-level reset state remains correct for both the existing
   playable `hidden` field and the controller's ownership latch.

## Proposed architecture

### Setup presentation decision

Add a controller-owned method that asks the existing level-event provider
whether this sidekick is entering a dormant-marker intro and, if so, sets the
sidekick's existing `hidden` presentation state. The S3K event manager invokes
that method after level-event initialization/zone-player setup, before the
initial `Process_Sprites` pass is consumed; the initial assembly hook invokes
it again after the `Tails_Init`-equivalent reset. The controller also records
`initialPresentationSuppressed` and the prior hidden value, so the matching
release can restore only the state this lifecycle owns. Both fields are part of
`SidekickCpuRewindExtra`; ordinary fresh-level reset clears both, while the
initial setup slot preserves an already-armed latch across its CPU reset. The
initial assembly call is made from
`SpriteManager.initializeInitialAssemblyPlayableSlot` after the normal Player
2 setup fields are restored and only for the first CPU sidekick slot.

This keeps the ownership split explicit:

| Concern | Owner | Effect |
| --- | --- | --- |
| Whether this intro uses a dormant marker | zone event provider | semantic predicate already used by CPU dispatch |
| Setup latch priming | S3K level-event manager | invokes the semantic controller hook before setup |
| Setup-only visual suppression | `SidekickCpuController` at event/setup boundaries | `hidden=true` only |
| AIZ/ICZ dormant gameplay state | `SidekickCpuController.updateInit` or existing bootstrap marker path | routine, sentinel position, control/status fields |
| Intro release | AIZ/ICZ event object/provider | existing `releaseDormantMarkerForLevelEvent` |
| Parent and tail-child rendering | `Tails.draw` | return before either draw path when hidden |

The method must not call `applyLevelEventDormantMarkerForBootstrap` for AIZ:
that would move the first CPU-owned gameplay transition earlier than the ROM
and would invalidate the existing trace timing contract. ICZ remains unchanged
in that respect: its event manager already applies the gameplay marker eagerly
because the ROM's ICZ startup path owns that transition.

### Release and reuse

When `releaseDormantMarkerForLevelEvent` accepts a marker release, restore the
prior hidden value only if `initialPresentationSuppressed` is set, then clear
the latch. The setup latch is the sole owner of the sidekick's presentation
state for this level-event window; other cutscene owners must not mutate the
same sidekick's hidden state until this release boundary. That ownership rule
keeps restoration deterministic while preserving a pre-existing hidden value.
It is safe for the existing S2 strategy because S2 does not request this intro
presentation latch. Reset/reload restores both the existing hidden field and
the controller latch through the playable/controller reset and rewind paths.

### Rendering

Move the hidden check to the beginning of `Tails.draw()`, before the
`TailsTailsController.draw()` call. This closes the child-sprite hole without
changing animation, physics, CPU state, or the render bucket architecture.

## Alternatives rejected

### Remove the sidekick during the intro

Rejected. The ROM keeps the Player 2 object and its CPU globals alive. Removing
it would change object ordering, CPU cadence, and later marker/release behavior.

### Eagerly apply the dormant marker for AIZ during setup

Rejected. Existing AIZ tests and the disassembly show that the marker belongs to
the first ordinary Tails CPU dispatch. Moving it into setup would alter the
observable first CPU state and can de-phase the level-frame/cutscene handoff.

### Suppress all CPU sidekicks in the renderer until the cutscene

Rejected. That would be a presentation rule disconnected from the ROM-owned
event predicate and would also hide sidekicks in unrelated levels or during
valid active phases. The existing `GameModule.isSidekickSuppressedForZone`
path is reserved for full zone suppression and is not the intro lifecycle.

### Reuse `hidden` without an ownership latch

Rejected. `hidden` is also used by other cutscenes and entry effects. An
unconditional clear at the dormant-marker release could reveal a sidekick that
another owner still intends to hide. The small controller-owned latch and
previous-value snapshot make the presentation ownership explicit without
changing gameplay state.

## Verification contract

Add regression assertions covering:

- AIZ setup: Tails remains in `INIT`, retains the normal spawn state for
  gameplay, and is hidden before the first ordinary visual replay tick.
- AIZ first CPU dispatch: Tails enters `DORMANT_MARKER`, reaches the ROM
  sentinel, and remains hidden.
- AIZ release: the existing `$1308` release transitions to catch-up flight and
  clears hidden presentation state.
- AIZ release with a pre-existing hidden sidekick: the release restores the
  pre-latch hidden value rather than unconditionally revealing it.
- Early release, rewind capture/restore, and fresh-level reset: the latch is
  unchanged by an early no-op, round-trips through rewind, and cannot leak into
  the next level.
- ICZ setup/release: the existing bootstrap marker timing remains intact (the
  setup slot still resets the controller to `INIT` and the first ordinary CPU
  pass re-establishes the marker), while presentation is hidden during the
  intro and restored after the existing crash release.
- Tails rendering: hidden Tails submits neither the parent nor child-tail
  rendering path; visible Tails retains the existing child-before-parent order.

The tests must also prove that the setup slot remains physically inert, that
the Knuckles `$918` spawn trigger does not release Tails, and that the shared
Tails rendering change preserves S2's ordinary visible and dust behavior. The
SSZ `$0A00` dormant branch is explicitly outside this change because no
provider-owned release path currently maps it to the new presentation latch.

The focused AIZ/ICZ headless suites and sidekick controller tests must pass,
followed by the full JDK 21 Maven suite.
