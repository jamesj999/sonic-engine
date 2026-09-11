# Touch-scan stale-position oracle (read-only, no engine change)

**Date:** 2026-08-21 · **Branch:** `bugfix/ai-touch-oracle-r2` · **Base:** `887320904`

Tests the hypothesis *"the ROM tests touch geometry against last frame's object positions,
while the engine tests against positions updated closer to the scan"* entirely from recorded
trace data plus the disassembly. No engine code was changed and none is proposed.

## 1. Recorder sampling phase (established before computing anything)

Both fixtures used here were produced by `recorder: native-bizhawk-headless` v3.0
(`metadata.json`). Its documented lifecycle is
`while true do on_frame_end(); … ; frameadvance end` — **all RAM reads are end-of-frame
state** (`tools/bizhawk-headless/docs/s3k-trace-recorder-behavior.md:344-346`; the Lua
ancestor states the same at `tools/bizhawk/s3k_complete_run_recorder.lua:1601-1603`, and
its loop at :5973-6025 calls `on_frame_end()` at the top of the iteration, i.e. after the
previous `emu.frameadvance()`).

So row *N* of `physics.csv` and the `object_state` events stamped `frame N` are the state
after the ROM's entire main loop for frame *N* has run.

Independent confirmation from the data itself: at every recorded badnik kill the row-*N*
`player_y_speed` already carries the `Touch_EnemyNormal` bounce (`±$100`, plus that frame's
gravity). `TouchResponse` is the last call in the player's routine
(`sonic3k.asm:22022-22026`), so row *N* is post-scan for frame *N*. Verified on all 24 S3K
events below.

## 2. What the ROM does (disassembly, not inference)

- Object slots run in ascending order. `Player_1` = slot 0, `Player_2` = slot 1,
  `Reserved_object_3` = slot 2 (`sonic3k.constants.asm:303-306`).
- Slot 2 is `Obj_ResetCollisionResponseList`, an unconditional
  `move.w #0,(Collision_response_list).w` (`sonic3k.asm:8467-8469`), installed by
  `SpawnLevelMainSprites` (`sonic3k.asm:8112`).
- Both player routines end with `jsr (TouchResponse).l` (`sonic3k.asm:22022`), i.e. **before**
  the list is cleared and before slots 3..109 run.
- `Touch_Loop` reads each candidate's **live** `x_pos(a1)`/`y_pos(a1)`
  (`sonic3k.asm:20686`, `:20703`).

Therefore, at the frame-*N* scan, no non-player object has moved this frame: their SST holds
end-of-frame-*N-1* values — exactly what the recorder wrote as row *N-1*. Both the *membership*
of the list and the *positions* are one frame stale.

The same holds for Sonic 1 by a simpler route: `ReactToItem` scans `v_lvlobjspace` (slot 1
onward) directly with no list at all (`_incObj/Sonic ReactToItem.asm:43-57`), from Sonic's own
slot-0 routine.

## 3. The oracle

For each recorded kill event at frame *N* (slot's object code becomes `Obj_Explosion`), the
overlap predicate of `Touch_Width`/`Touch_Height` (`sonic3k.asm:20677-20712`) was evaluated
literally — 16-bit `sub`/`add` with the ROM's carry branches — twice:

- **stale model:** player row *N* vs. object row *N-1*
- **live model:** player row *N* vs. object row *N* (and, for the "fires early" test, player
  row *N-1* vs. object row *N-1*)

Touch radii come from `Touch_Sizes` (`sonic3k.asm:20713-20769`, 57 entries) indexed by
`collision_flags & $3F`, and `collision_flags` from each object's `ObjDat_*` attribute table
(last byte; `SetUp_ObjAttributes`, `sonic3k.asm:176901-176911`). Player box:
`x_pos-8 … +$10` wide, `y_pos ∓ (y_radius-3)` tall (`sonic3k.asm:20647-20655`), with
`y_radius` = `$13` walking / `$E` rolling for Sonic, `$F` / `$E` for Tails
(`sonic3k.asm:21904, 23261, 26103`).

Object identities were resolved from the recorded `object_code` by matching ROM bytes at that
address against the disassembly (each value is the object head **+6**, the address past
`jsr (Obj_WaitOffscreen).l`):

| `object_code` | object | `collision_flags` | `Touch_Sizes` index | box |
|---|---|---|---|---|
| `0x0878CE` | `Jawz_Main` | `$D7` | `$17` | 8 × 8 |
| `0x08792E` | `Obj_Blastoid`+6 | `$D7` | `$17` | 8 × 8 |
| `0x087BCA` | `Obj_TurboSpiker`+6 | `$1A` | `$1A` | 12 × 12 |
| `0x087F5C` | `Obj_MegaChopper`+6 | `$D7` | `$17` | 8 × 8 |
| `0x088282` | `Obj_Poindexter`+6 | `$0A` | `$0A` | 16 × 8 |

Sonic 1 (`ghz1_completerun`): `React_Sizes` entries `$06`/`$08`/`$09` for Crabmeat, Buzz Bomber
and Chopper (`_incObj/1F Badnik - Crabmeat.asm:29`,
`_incObj/22, 23 Badnik - Buzz Bomber and Missile.asm:26`,
`_incObj/2B Badnik - Chopper.asm:25`), stored as half-extents by the `hitbox` macro.

## 4. Results

**S3K `hcz_completerun` — 24 kill events, 25 (player, event) pairs.**
The stale model's first overlap equals the recorded kill frame in **25 of 25**. It never
overlaps at *N-1*, so it does not predict an earlier kill anywhere.

**Four events discriminate** (Jawz, moving 2 px/frame, crossing the box edge):

| kill frame | slot | stale overlaps at | live overlaps at | ROM killed at |
|---|---|---|---|---|
| 14034 | 22 | 14034 | 14033 | 14034 |
| 20393 | 11 | 20393 | 20392 | 20393 |
| 21540 | 27 | 21540 | 21539 | 21540 |
| 22861 | 14 | 22861 | 22860 | 22861 |

A live-position scan fires one frame early in each. Jawz uses `collision_flags $D7`
(`Touch_Special`, `sonic3k.asm:21162-21182`), which increments `collision_property`; Jawz's own
routine consumes it the *same* frame (slot > 2), so a frame-*N-1* touch would have produced a
frame-*N-1* explosion. It did not.

The other 21 pairs are non-discriminating: the badnik moved ≤ 2 px and never crossed the
boundary, so both models agree.

**S1 `ghz1_completerun` — 6 kill events.** Stale model overlaps at exactly the recorded kill
frame in 6 of 6, and at no earlier frame. None of the six discriminates (the badniks are static
or slow in the approach window), so this is corroboration, not a second independent proof.

## 5. Verdict

**The ROM half of the hypothesis is CONFIRMED.** The scan is against last frame's positions,
and the recording proves it independently of the disassembly reading.

**The engine half is FALSE, and this is the finding that matters.** The engine already models
the stale scan on the production path:

- `LevelFrameStep.java:299` takes `prepareTouchResponseSnapshots()` at frame start, `:302` runs
  player physics (which calls `applyTouchResponses`, `SpriteManager.java:1813`), and `:318-320`
  runs `ExecuteObjects` afterwards. All three games set `objectsExecuteAfterPlayerPhysics`
  (`GameRules.java:121, 276, 425`).
- `ObjectTouchResponseController.java:485-486` reads `getPreUpdateX()/getPreUpdateY()`.

So the ordering change this round was sent to justify **is not warranted**, and the two local
compensations named in the frontier log (a hover duration of 59 against the ROM's 60, and a
boss child's wait one above its ROM literal) are *not* explained by a global scan-phase error.
They need their own attribution.

## 6. Residual, named but not chased

The S3K previous-list is a wholesale end-of-frame copy of the slot-sorted provider cache
(`ObjectManager.java:751` → `ObjectCollisionResponseList.java:134-138`), filtered by
`publishesTouchResponseListEntryThisFrame()`, not an incremental publication at each object
routine's `Add_SpriteToCollisionResponseList` tail call. ROM *membership* can therefore differ
even though positions match. Separately, `usesCurrentTouchState()` (31 S3K objects) reads live
positions; at this phase live equals the frame *N-1* value for anything in the exec loop, so it
is observationally identical there — it only differs for objects whose cache is a pass older.
Neither was measured this round.

## 7. Oracle script

Kept for reproduction; comparison-only, reads fixtures and never writes engine state.

```python
W = 0xFFFF

def axis(objpos, rad, plo, span):
    """Touch_Width / Touch_Height, sonic3k.asm:20677-20712, literal 68k semantics."""
    d0 = (objpos - rad) & W
    borrow = d0 < (plo & W)          # C from `sub.w d2,d0`
    d0 = (d0 - plo) & W
    if not borrow:                   # bcc .checkrightside
        return d0 <= span            # bhi -> Touch_NextObj
    return (d0 + ((rad * 2) & W)) > W  # bcs -> inside

def touches(px, py, y_radius, ox, oy, w, h):
    d2 = (px - 8) & W                       # sonic3k.asm:20647
    d5 = (y_radius - 3) & 0xFF              # :20651
    d3 = (py - d5) & W
    return axis(ox & W, w, d2, 0x10) and axis(oy & W, h, d3, (d5 * 2) & W)
```

Sonic 1 uses the same predicate with `d4 = 16` and half-extent radii
(`_incObj/Sonic ReactToItem.asm:135-175`).
