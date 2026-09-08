# Shared collision outputs and render sampling
## Sampled rendering flags

A sampled on-screen flag can be newer than the logic that just consumed it.
  Player/sidekick CPU code runs before `Draw_Sprite` and `Render_Sprites`; the
  trace recorder samples afterward. In S3K `Tails_Display`, the routine copies
  the pre-decrement invulnerability timer, decrements storage, and skips
  `Draw_Sprite` when copied bit 2 is clear. The `$01 -> $00` frame therefore
  retains the previous off-screen bit, while the following steady-zero frame
  enqueues the sprite and refreshes bit 7. FBZ f15414 is the diagnostic pattern:
  `sub_13EFC` increments shared `Tails_CPU_flight_timer` to `$002C` from retained
  `render_flags=$04`, then later rendering changes the sampled flag to `$84`;
  only f15415 resets the timer. Model the pre-decrement display decision through
  the post-camera render refresh instead of adding a zone/frame exception.

### Empty collision probes retain shared angle-output bytes


When a frontier differs only in a player's facing/balance status one frame
after landing, do not treat the final empty-side `SensorResult` angle (`$03`) as
a fresh write. The native `Primary_Angle`/`Secondary_Angle` bytes are shared by
all playable dispatches, and `FindFloor`/`FindWall` write them only after finding
a solid collision tile. An empty/unsolid path returns distance without touching
the target byte. Grounded `Player_AnglePos` seeds both bytes to `$03` (or clears
both to `$00` on `Status_OnObj`), while airborne floor/ceiling/wall checks do not
seed them. Each player tail then copies the retained shared pair into that
player's `next_tilt`/`tilt` fields. A later sidekick can therefore inherit a
value written by the main player earlier in the same frame.

Preserve write history, not just the selected final result. With
`Background_collision_flag`, native code scans FG, saves the post-FG angle byte,
scans BG, and restores the saved FG state only when FG distance wins strictly;
BG wins equality. FG may write an angle even when the selected BG result is
empty, so `tileId == 0` on the final result is insufficient. Carry explicit
FG/BG write metadata plus the restore decision through pooled sensor results;
publish wall-helper writes to `Primary_Angle` before possible early returns and
paired floor/ceiling writes right-to-Primary then left-to-Secondary. Keep the
shared bytes on the gameplay-scoped collision owner, snapshot them for rewind,
and copy them into each playable from a guaranteed player-routine tail after
every dispatch, including object-control/no-movement early returns. Route every
grounded `AnglePos` caller through the same publication step, including
spindash charge/release paths that bypass ordinary ground movement. Test a
sequential main-player -> grounded seed -> sidekick sequence, an object-controlled
sidekick that only runs the common tail, an on-object clear inherited by another
playable, spindash charge/release, a wall-write early return, and an
FG-write/BG-empty equality case.

FBZ complete-run f16682-f16687 is the reference pattern: Sonic's empty right
landing probe retained shared Primary `$00`; his next grounded `AnglePos` seeded
Primary `$03`; Tails landed later that frame and its empty right probe retained
that shared `$03`, causing the native facing clear on the following dispatch.
The write/no-write instructions are `FindFloor`/`sub_F264`/`sub_F30C` at
`docs/skdisasm/sonic3k.asm:19187-19310`; the paired player copy is at
`sonic3k.asm:26215-26244`.
