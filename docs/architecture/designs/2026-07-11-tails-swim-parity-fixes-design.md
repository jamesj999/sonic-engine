# Tails Swimming Parity Fixes

## Scope

Correct two regressions in S3K-capable manual Tails swimming:

1. A stray tail-art overlay appears in front of the swimming body.
2. With no jump input, swimming accelerates upward instead of gently sinking.

The donor capability matrix, flight timing, carry rules, CPU recovery, water-transition velocity scaling, and body art addresses remain unchanged.

## ROM evidence

### Tail overlay

`Obj_Tails_Tail_AniSelection` maps flying body animations `$20-$24` to tail animations `$0B/$0C`, but maps swimming animations `$25-$28` to `$00` (blank). The swim body mappings already contain the complete tails. The engine currently maps `$25-$28` to `$0B/$0C`, causing the extra overlay.

Reference: `docs/skdisasm/sonic3k.asm:30076-30107`.

### Idle swim velocity

`Tails_Stand_Freespace` applies the ordinary underwater `y_vel -= $28` correction only on the non-flight path. When `double_jump_flag` is nonzero, `Tails_FlyingSwimming` instead runs `Tails_Move_FlySwim` followed by `MoveSprite_TestGravity2`; no generic underwater correction follows. In ready state with no button press, `Tails_Move_FlySwim` adds `$08`, so idle swimming gently sinks.

The engine currently runs the correct flight update and movement, then unconditionally applies the non-flight underwater `-$28`, changing a resting swimmer from `$0000` to `-$0020` per frame.

Reference: `docs/skdisasm/sonic3k.asm:27553-27644`.

## Design

### Rendering correction

Change the S3K parent-animation selection entries in `TailsTailsController`:

- `$20-$24`: retain the existing flying tail animations.
- `$25-$28`: select blank animation `$00`.

Do not add water-state checks to rendering. The parent animation byte is the ROM-owned source of truth and handles transitions without a separate state branch.

### Physics correction

Do not call `applyUnderwaterAirGravityReduction()` while `isTailsFlightPhysicsActive(sprite)` is true. The flight controller already owns `y_vel` for that frame and mirrors `Tails_Move_FlySwim`.

Do not compensate by adding `$28` inside `TailsFlightController`; that would couple the shared flight routine to a correction belonging only to the non-flight movement branch and risk transition-frame double adjustments.

## Tests

Use red-green tests before production edits:

- Update `TestTailsTailsFlightSelection` to assert a tail overlay for `$20-$24` and no renderer interaction for `$25-$28`.
- Add an underwater no-input movement test starting from `y_vel=$0000`; after one frame expect `$0008`, and after a second frame expect `$0010`.
- Retain existing tests covering body animation selection `$25-$28`, flight/carry motion, CPU recovery, and donor gating.

Verification includes the focused flight/swim/carry/CPU suites, Maven policy validation, diff checks, and an independent review loop with automatic correction until approved.

## Non-goals

- No changes to swim animation body frames, DPLCs, art offsets, or palettes.
- No changes to manual-flight availability across S1, S2, or donor combinations.
- No changes to water-entry/exit speed quartering or drowning behavior.
