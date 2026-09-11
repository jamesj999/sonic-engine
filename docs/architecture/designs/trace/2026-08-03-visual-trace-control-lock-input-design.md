# Visual trace control-lock input parity

Date: 2026-08-03

## Problem

S1 GHZ1 remains green in headless replay but diverges in visual replay at trace
frame 4999. The reference has reached `Sign_SonicRun`: the signpost owns
`Ctrl_1_logical`, locks player control, and forces right. The recorded raw row
contains Left plus a new action press. The visual engine correctly suppresses
the direction but still jumps because `PlaybackInputBridge` writes the pending
action edge directly to `AbstractPlayableSprite.forcedJumpPress`. That field is
consumed by movement after `SpriteManager` has applied the control lock, so it
bypasses the same raw-to-logical gate that rejected Left.

The frame's S1 PLC state and every dynamic-art edge match. This is an input
ownership defect, not an art-timing defect.

## Source-of-truth behavior

S1 `Sign_SonicRun` sets `f_lockctrl` and writes forced Right to the logical pad.
Sonic control skips copying the raw controller word to the logical word while
that lock is active. Raw controller presses remain observable to object code,
but the locked player's movement cannot consume them. The engine already
models this distinction in `SpriteManager`; playback must enter through it.

## Constraints

- Recorded BK2 state remains raw controller input until `SpriteManager`
  resolves the ROM-visible logical word.
- A control lock suppresses a recorded action edge for player movement just as
  it suppresses recorded directions.
- A one-frame action press encountered on an advance-only row remains pending
  until the next real gameplay dispatch. A later held or released row must not
  erase it.
- P1 action identity is retained as a mask rather than collapsed to a generic
  jump boolean, including across the `SpriteManager` dispatch payload.
- Game-owned forced input and forced-jump latches are not cleared by playback
  publication or cleanup.
- Initial assembly does not consume the pending action edge.
- No trace, frame, game, zone, or object-specific runtime exception is added.

## Options considered

### Gate the bridge's sprite write on `isControlLocked()`

This would fix the reported frame, but the bridge would still mutate a
gameplay-owned one-shot latch before queued object control state is applied.
Its cleanup could also clear a signal owned by gameplay. The ownership and
ordering defect would remain.

### Remove pending action-edge support

This avoids the bypass but drops a legitimate one-frame press whenever an
advance-only row consumes the movie row without running gameplay. That is a
replay regression.

### Carry the edge in the logical snapshot and admit it in `SpriteManager`

This preserves the raw-input boundary and evaluates the press after queued
control state has been applied. It is the selected approach.

## Design

`PlaybackDebugManager` owns a pending P1 action-press mask. Preparing a row ORs
new action transitions into that mask. Advancing an input-only row does not
clear it; executing a gameplay dispatch, seeking, ending playback, or clearing
playback state does. The manager includes the pending mask in the prepared
`LogicalInputSnapshot`, so a press first seen on an advance-only row remains
present even if the later applied row only holds or releases the button.

`PlaybackInputBridge` owns only `InputHandler` logical-override publication and
sprite-manager playback suppression. It no longer receives a player sprite,
writes `forcedJumpPress`, or clears that field during teardown.

`InitialPlayableInput` gains a P1 action-pressed mask sourced from
`PlayerInputState.actionPressedMask()`. Its existing `p1Pressed` field remains
the ROM direction-plus-abstract-jump press word; it is not used as a substitute
for action identity.

`SpriteManager` consumes that P1 action-pressed mask for the human-controlled
slot after `applyQueuedControlStateForFrameStart()`. It publishes the raw
held/pressed controller state for objects, then derives the logical action edge
using the same control-lock decision as held movement:

```
logical action press = runtime input enabled
                    && !control locked
                    && recorded P1 action press
```

When admitted, the logical press is installed as the existing one-shot
movement signal immediately before physics. A locked press is not installed.
The manager never writes `false` to that latch, preserving independent
gameplay ownership. Initial assembly publishes input history without admitting
or consuming the pending movement press; the playback manager clears it only
after the first actual gameplay dispatch.

This is cross-game controller-word behavior. Per-game differences remain in
the existing `PlayerMovementRules` logical-input latch policy.

### Rewind replay-forward ownership

`VisualTraceRewindStepper` currently has a parallel direct-sprite workaround:
it stores `pendingForcedJumpPress` and writes it into the player. That path is
migrated in the same change. It instead stores a pending P1 action-press mask,
includes it when publishing `RecordedInputSnapshots`, and lets
`SpriteManager` perform the control-lock admission. A successful gameplay
dispatch consumes the pending mask; advance-only, VBlank-only,
animation-only, paused, and setup-only rows do not.

When a rewind snapshot is restored, the stepper reconstructs its pending mask
from the applied BK2 rows in the contiguous non-gameplay trace interval ending
at the restored row. It stops at the most recent row that admitted playable
gameplay, just as the forward path would have consumed the mask there. This
keeps rewind-forward behavior deterministic without storing replay state in a
playable sprite or hydrating gameplay from comparison data. The restored
logical override includes the reconstructed mask; it never reads or writes
`player.forcedJumpPress`.

## Verification

- A manager test carries an action press across an advance-only cursor move
  into a later held/released snapshot.
- A real playable-dispatch regression locks control, publishes a raw jump
  press, and proves movement remains grounded while the raw press is visible.
- The existing advance-only bridge test proves an unlocked pending press is
  delivered exactly once after the initial setup-only pass.
- Rewind-stepper tests prove the same unlocked delivery and prove a pending raw
  press remains suppressed when control is locked after rewind restore.
- S1 GHZ1 complete-run headless replay remains green.
- Focused playback, input, sprite movement, and visual trace launch suites pass,
  followed by the repository-wide regression comparison.
