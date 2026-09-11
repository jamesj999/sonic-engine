# CNZ2 Knuckles Switch Cutscene Stall Design

## Goal

Correct the first Carnival Night Zone Act 2 rival-Knuckles cutscene so it
advances from the pre-switch laugh into the jump sequence and Knuckles presses
the lights-off switch. The result must follow the locked-on Sonic 3 & Knuckles
ROM state machine as closely as possible without broad camera changes that are
not supported by evidence.

## ROM Reference

The source of truth is the locked-on path in `docs/skdisasm/sonic3k.asm`:

- `CutsceneKnux_CNZ2A` and `CutsceneKnux_CNZ2A_Index`
- `word_6228E`, the cutscene camera activation window
- `word_62296`, the requested camera lock bounds
- `loc_622E4`, cutscene setup
- `loc_62332` and `loc_85CA4`, the pre-switch laugh and camera gate
- `loc_6233E` through `loc_623FE`, the wait, jump, switch, laugh, and exit flow

During the pre-switch laugh, `loc_85CA4` waits for three independent facts:

1. The 120-frame fade/music delay has elapsed.
2. The vertical camera lock has completed at the bounds in `word_62296`.
3. The horizontal camera lock has completed at the bounds in `word_62296`.

Only when all three bits are set does the callback at `loc_6233E` advance the
object to routine `$04` and begin the timed lead-in to the jump sequence.

The committed CNZ reference trace demonstrates the expected route behavior:
the camera approaches from approximately X `$1BC5`, Y `$02AC`, converges to
X `$1D00`, Y `$0280`, and remains there while the sequence proceeds.

## Diagnosis Strategy

Add a focused headless regression that runs the first encounter through the
normal object/camera update path from its pre-lock approach state. The test will
record which of the three native completion conditions remains false when the
sequence stalls. It will assert the externally meaningful outcome as well:
Knuckles leaves the pre-switch laugh, reaches the button proximity, and the
button arms the CNZ2 water target `$0350`.

The test must not directly force a routine, timer, camera coordinate, or button
impact after the sequence starts. Such seams remain acceptable in small unit
tests, but they cannot prove this reported lifecycle bug is fixed.

## Implementation Boundary

Use the smallest owner justified by the failing test and ROM comparison:

1. Prefer a correction inside `CutsceneKnucklesCnz2AInstance` when the CNZ2
   setup, activation-window refresh, or callback ordering differs from the
   generic boss-camera contract.
2. Change `S3kSharedBossCameraGate` only if the failing evidence proves that
   its translation of `Check_CameraInRange` / `loc_85CA4` is generally wrong.
   In that case, preserve existing callers through an explicit mode or narrowly
   corrected shared semantic, with focused tests for affected callers.
3. Do not add a timeout, force a phase transition, press the switch directly,
   or branch on route/frame identity. The actual ROM state must drive progress.

No unrelated CNZ event, water, palette, rendering, or camera refactor is in
scope.

## Coordinate and State Semantics

ROM `x_pos` / `y_pos` values remain centre coordinates. Camera positions and
camera bounds remain world coordinates. Tests and implementation must not use
top-left sprite bounds as substitutes for the cutscene object's native
position fields.

The existing `Cnz2CutsceneButtonInstance` remains responsible for the switch
proximity test and its lights-off, screen-shake, palette, and water effects.
The Knuckles object remains responsible for reaching that proximity through
its native movement sequence.

## Testing

Follow a red-green cycle:

1. Add the lifecycle regression and confirm it fails at the pre-switch laugh.
2. Make the smallest ROM-backed correction.
3. Confirm the new regression passes.
4. Temporarily revert the production correction and confirm the regression
   fails again, then restore it and rerun.
5. Run the focused CNZ cutscene/button/water tests.
6. Run relevant camera-gate and boss tests if shared code changes.
7. Run the S3K CNZ trace replay only if the fix or regression touches the trace
   path; if its frontier changes, update `docs/status/trace-frontier-log.md` according
   to repository policy.
8. Run the project build or an appropriately broad S3K regression set before
   completion.

## Success Criteria

- The reported pre-switch laugh cannot remain indefinitely under the reproduced
  normal CNZ2 approach.
- Knuckles advances through the ROM camera gate and physically reaches the
  cutscene button.
- The button triggers the existing lights-off path and water target `$0350`.
- The fix contains no timeout, route/frame carve-out, or direct switch trigger.
- Existing focused CNZ2 cutscene, camera, button, water, and rewind tests remain
  green.
- Any shared change is supported by direct tests showing the shared semantic,
  not merely by the CNZ symptom.
