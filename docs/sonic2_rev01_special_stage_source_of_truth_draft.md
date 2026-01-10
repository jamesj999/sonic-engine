# Sonic 2 (Genesis) REV01 — Special Stage source of truth (draft)

Last updated: 2026-01-10 23:15:00Z

This document is being built iteratively across chat continuations. It is intended as a **source of truth** for reimplementing Sonic the Hedgehog 2 (Genesis) Special Stages, targeting the **REV01** ROM, and integrating into the Java engine at:
`https://github.com/jamesj999/sonic-engine/tree/ai-improvement-test-1`

The emphasis here is on verifiable facts (ROM offsets, raw byte matches, disassembly label references) and a strategy a coding agent can follow.

---

## Implementation readiness summary

### Engine capabilities available

| Capability | Status | Notes |
|---|:---:|---|
| ROM loading infrastructure | ✅ | `Rom` class supports slicing by offset |
| Kosinski decompression | ✅ | `KosinskiReader` exists |
| Nemesis decompression | ✅ | Used for normal level art |
| ENI decompression | ❌ | **Needed** for background mappings |
| H32 render mode (256px wide) | ❌ | **Needed** - VDP mode change |
| Per-scanline Plane B scroll | ❌ | **Needed** for skydome effect |
| Special Stage scene/state | ❌ | **Needed** - separate from normal levels |

### Documentation gaps (prioritized)

| Gap | Priority | Impact | Resolution path |
|---|:---:|---|---|
| Level layout format (`level layouts.nem`) | ✅ Done | Segment byte format documented | See "Level layout format" section |
| Object perspective data format | ✅ Done | 6-byte entry format documented | See "Object perspective data format" section |
| Track BIN mapping frame structure | ✅ Done | 3-segment bitstream format documented | See "Track mapping frame format" section |
| Skydome scroll table usage semantics | ✅ Done | Row/column selection documented | See "Skydome scroll table" section |
| `SSAnim_Base_Duration` index mapping | ✅ Done | Speed factor conversion documented | See "SSAnim_Base_Duration" section |
| Checkpoint gate (`Obj5A`) full behavior | ✅ Done | All routines documented | See "Checkpoint gate" section |

---

## Inputs used

ROM: `Sonic The Hedgehog 2 (W) (REV01) [!].gen`  
ROM SHA-256: `193bc4064ce0daf27ea9e908ed246d87ec576cc294833badebb590b6ad8e8f6b`

Disassembly zip: `s2disasm.zip` (extracted under `/mnt/data/s2disasm_extracted/s2disasm/`)

## Validation method for ROM offsets

For data assets that exist as external binary blobs in the disassembly (e.g., `*.kos`, `*.nem`, `*.eni`, `*.bin`), ROM offsets were validated by searching the entire raw blob as a byte sequence within the REV01 ROM and confirming the match is **unique**.

This yields offsets suitable for “pull directly from ROM” strategies without relying on a prebuilt `.lst`.

## Verified Special Stage data assets in ROM (REV01)

### Core stage data (layouts, object streams, perspective table)

| Asset (disassembly path) | ROM offset (REV01) | Size (bytes) | SHA-256 | Unique match |
|---|---:|---:|---|:--:|
| `misc/Special stage object perspective data.kos` | `0x0E24FE` | 4080 | `46829e76c1d568f0b3d11aeab2d0d52a1a257c72cffde572d0a04e1caf556dd3` | ✅ |
| `misc/Special stage level layouts.nem` | `0x0E34EE` | 260 | `61391a84bba62f1320d5df8e6b1d3b37cf08ca8bd4e9479da23822e1b23724ac` | ✅ |
| `misc/Special stage object location lists.kos` | `0x0E35F2` | 3216 | `6ceb98a57db970bed2a912efdc4cbd9327163316f643cd4c42fb0e3b297e31fd` | ✅ |

### Art and mapping assets (selected, high value)

| Asset (disassembly path) | ROM offset (REV01) | Size (bytes) | SHA-256 | Unique match |
|---|---:|---:|---|:--:|
| `art/kosinski/SpecStag.kos` | `0x0DCA38` | 816 | `aca5054e44d3e9823b5acdd2c801a0c004c40f85cdce02a9bf514f32fc76da81` | ✅ |
| `art/nemesis/Special stage ring art.nem` | `0x0DDA7E` | 1318 | `a82afe44dae1342625deb57ea8334e6c802ac3ab3635360ee67a46a4fb91e74a` | ✅ |
| `art/nemesis/Bomb from special stage.nem` | `0x0DE4BC` | 1008 | `b0ec8619e5d996fdcd3d571b6de332b72e7b7b6a32f5939d556aef06968fd8f3` | ✅ |
| `art/nemesis/Emerald from special stage.nem` | `0x0DE8AC` | 583 | `a1afdb7b01b044ae5ee2d7a727ddba080d0fbe67a8188ca81d8e357462df40d7` | ✅ |
| `art/nemesis/Special stage messages and icons.nem` | `0x0DEAF4` | 953 | `a7e9afeccabc1c6e99e89c3ffc1f9c5d06ef499435ceddad705016b8f010b964` | ✅ |
| `art/nemesis/Sonic and Tails animation frames in special stage.nem` | `0x0DEEAE` | 13775 | `6624e5a691dc645c2ed2cf5b553cd756f723d39674182db7ba7b85c4996fb26f` | ✅ |
| `art/nemesis/Horizontal shadow from special stage.nem` | `0x0DDFA4` | 181 | `408abaeae90f25409af225760ad004fbdba79c3c555872030ca04d37e22e3f34` | ✅ |
| `art/nemesis/Diagonal shadow from special stage.nem` | `0x0DE05A` | 198 | `c667a99b20c4b016fb68faccf29564b85016ca3c3a5695a850a9dfedc87d2b4e` | ✅ |
| `art/nemesis/Vertical shadow from special stage.nem` | `0x0DE120` | 103 | `bb4ae06b09e0a5b4cb968dcb3a36890fa0f7c043f558c21ef302b0293b33d2b1` | ✅ |
| `mappings/misc/Main background mappings for special stage.eni` | `0x0DD1DE` | 302 | `371c81e08f06cf0859e563f4989f9334dee7c467303b4e5bbcaeb2a0cfbd2326` | ✅ |
| `mappings/misc/Lower background mappings for special stage.eni` | `0x0DD30C` | 382 | `9f20c295ac287f8056718d694a6094eb81a3a41d00532f8e147a3cdfdb03febe` | ✅ |
| `art/nemesis/Background art for special stage.nem` | `0x0DCD68` | 1141 | `762b2571dae3166c70937c73e40411de99ea71253443233e632f0a6764588a2f` | ✅ |

## Special Stage track mapping frames (56 frames)

The Special Stage track mapping frames are stored as 56 contiguous BINCLUDE blobs in the disassembly (`mappings/special stage/*.bin`), and they appear in the REV01 ROM as one contiguous region with **no gaps**:

Start: `0x0CA904`
End (exclusive): `0x0DCA38`

The next data immediately following this region is `art/kosinski/SpecStag.kos` which begins at `0x0DCA38` (this equals the track end), confirming the region boundary.

### Frame family counts

| Family | Frame count |
|---|---:|
| `Begin curve right` | 7 |
| `Curve right` | 11 |
| `Slope down` | 17 |
| `Slope up` | 17 |
| `Straight path` | 4 |

### Full frame index mapping (index → BINCLUDE file → ROM offset)

| Track frame index | Label | BINCLUDE file | ROM offset | Size |
|---:|---|---|---:|---:|
| `0x00` | `MapSpec_Rise1` | `Slope up - Frame 1.bin` | `0x0CA904` | 1188 |
| `0x01` | `MapSpec_Rise2` | `Slope up - Frame 2.bin` | `0x0CADA8` | 1486 |
| `0x02` | `MapSpec_Rise3` | `Slope up - Frame 3.bin` | `0x0CB376` | 1464 |
| `0x03` | `MapSpec_Rise4` | `Slope up - Frame 4.bin` | `0x0CB92E` | 1636 |
| `0x04` | `MapSpec_Rise5` | `Slope up - Frame 5.bin` | `0x0CBF92` | 1580 |
| `0x05` | `MapSpec_Rise6` | `Slope up - Frame 6.bin` | `0x0CC5BE` | 1724 |
| `0x06` | `MapSpec_Rise7` | `Slope up - Frame 7.bin` | `0x0CCC7A` | 1544 |
| `0x07` | `MapSpec_Rise8` | `Slope up - Frame 8.bin` | `0x0CD282` | 1342 |
| `0x08` | `MapSpec_Rise9` | `Slope up - Frame 9.bin` | `0x0CD7C0` | 1412 |
| `0x09` | `MapSpec_Rise10` | `Slope up - Frame 10.bin` | `0x0CDD44` | 1402 |
| `0x0a` | `MapSpec_Rise11` | `Slope up - Frame 11.bin` | `0x0CE2BE` | 1312 |
| `0x0b` | `MapSpec_Rise12` | `Slope up - Frame 12.bin` | `0x0CE7DE` | 1140 |
| `0x0c` | `MapSpec_Rise13` | `Slope up - Frame 13.bin` | `0x0CEC52` | 1130 |
| `0x0d` | `MapSpec_Rise14` | `Slope up - Frame 14.bin` | `0x0CF0BC` | 1220 |
| `0x0e` | `MapSpec_Rise15` | `Slope up - Frame 15.bin` | `0x0CF580` | 1152 |
| `0x0f` | `MapSpec_Rise16` | `Slope up - Frame 16.bin` | `0x0CFA00` | 1098 |
| `0x10` | `MapSpec_Rise17` | `Slope up - Frame 17.bin` | `0x0CFE4A` | 1090 |
| `0x11` | `MapSpec_Straight1` | `Straight path - Frame 1.bin` | `0x0D028C` | 1662 |
| `0x12` | `MapSpec_Straight2` | `Straight path - Frame 2.bin` | `0x0D090A` | 1436 |
| `0x13` | `MapSpec_Straight3` | `Straight path - Frame 3.bin` | `0x0D0EA6` | 1370 |
| `0x14` | `MapSpec_Straight4` | `Straight path - Frame 4.bin` | `0x0D1400` | 1532 |
| `0x15` | `MapSpec_Drop1` | `Slope down - Frame 1.bin` | `0x0D19FC` | 1200 |
| `0x16` | `MapSpec_Drop2` | `Slope down - Frame 2.bin` | `0x0D1EAC` | 1282 |
| `0x17` | `MapSpec_Drop3` | `Slope down - Frame 3.bin` | `0x0D23AE` | 1048 |
| `0x18` | `MapSpec_Drop4` | `Slope down - Frame 4.bin` | `0x0D27C6` | 1102 |
| `0x19` | `MapSpec_Drop5` | `Slope down - Frame 5.bin` | `0x0D2C14` | 1150 |
| `0x1a` | `MapSpec_Drop6` | `Slope down - Frame 6.bin` | `0x0D3092` | 1168 |
| `0x1b` | `MapSpec_Drop7` | `Slope down - Frame 7.bin` | `0x0D3522` | 1226 |
| `0x1c` | `MapSpec_Drop8` | `Slope down - Frame 8.bin` | `0x0D39EC` | 1420 |
| `0x1d` | `MapSpec_Drop9` | `Slope down - Frame 9.bin` | `0x0D3F78` | 1768 |
| `0x1e` | `MapSpec_Drop10` | `Slope down - Frame 10.bin` | `0x0D4660` | 1862 |
| `0x1f` | `MapSpec_Drop11` | `Slope down - Frame 11.bin` | `0x0D4DA6` | 1622 |
| `0x20` | `MapSpec_Drop12` | `Slope down - Frame 12.bin` | `0x0D53FC` | 1372 |
| `0x21` | `MapSpec_Drop13` | `Slope down - Frame 13.bin` | `0x0D5958` | 1450 |
| `0x22` | `MapSpec_Drop14` | `Slope down - Frame 14.bin` | `0x0D5F02` | 1684 |
| `0x23` | `MapSpec_Drop15` | `Slope down - Frame 15.bin` | `0x0D6596` | 1556 |
| `0x24` | `MapSpec_Drop16` | `Slope down - Frame 16.bin` | `0x0D6BAA` | 1156 |
| `0x25` | `MapSpec_Drop17` | `Slope down - Frame 17.bin` | `0x0D702E` | 1134 |
| `0x26` | `MapSpec_Turning1` | `Curve right - Frame 1.bin` | `0x0D749C` | 1142 |
| `0x27` | `MapSpec_Turning2` | `Curve right - Frame 2.bin` | `0x0D7912` | 1176 |
| `0x28` | `MapSpec_Turning3` | `Curve right - Frame 3.bin` | `0x0D7DAA` | 1190 |
| `0x29` | `MapSpec_Turning4` | `Curve right - Frame 4.bin` | `0x0D8250` | 936 |
| `0x2a` | `MapSpec_Turning5` | `Curve right - Frame 5.bin` | `0x0D85F8` | 1012 |
| `0x2b` | `MapSpec_Turning6` | `Curve right - Frame 6.bin` | `0x0D89EC` | 1080 |
| `0x2c` | `MapSpec_Unturn1` | `Curve right - Frame 7.bin` | `0x0D8E24` | 1170 |
| `0x2d` | `MapSpec_Unturn2` | `Curve right - Frame 8.bin` | `0x0D92B6` | 1218 |
| `0x2e` | `MapSpec_Unturn3` | `Curve right - Frame 9.bin` | `0x0D9778` | 1032 |
| `0x2f` | `MapSpec_Unturn4` | `Curve right - Frame 10.bin` | `0x0D9B80` | 1174 |
| `0x30` | `MapSpec_Unturn5` | `Curve right - Frame 11.bin` | `0x0DA016` | 1208 |
| `0x31` | `MapSpec_Turn1` | `Begin curve right - Frame 1.bin` | `0x0DA4CE` | 1618 |
| `0x32` | `MapSpec_Turn2` | `Begin curve right - Frame 2.bin` | `0x0DAB20` | 1382 |
| `0x33` | `MapSpec_Turn3` | `Begin curve right - Frame 3.bin` | `0x0DB086` | 1320 |
| `0x34` | `MapSpec_Turn4` | `Begin curve right - Frame 4.bin` | `0x0DB5AE` | 1460 |
| `0x35` | `MapSpec_Turn5` | `Begin curve right - Frame 5.bin` | `0x0DBB62` | 1522 |
| `0x36` | `MapSpec_Turn6` | `Begin curve right - Frame 6.bin` | `0x0DC154` | 1172 |
| `0x37` | `MapSpec_Turn7` | `Begin curve right - Frame 7.bin` | `0x0DC5E8` | 1104 |

## Track animation sequences (validated from disassembly source)

In the disassembly, `Ani_SpecialStageTrack` defines five segment animation types, each as an explicit byte sequence of track-frame indices. These indices map into `Map_SpecialStageTrack` which contains 56 mapping frames.

Key definitions (from `s2.asm`):

TurnThenRise:
turning: `$26,$27,$28,$29,$2A,$2B,$26`
rise: `0..$10` (17 frames)

TurnThenDrop:
turning: `$26,$27,$28,$29,$2A,$2B,$26`
drop: `$15..$25` (17 frames)

TurnThenStraight:
turning: `$26,$27,$28,$29,$2A,$2B,$26`
exit: `$2C,$2D,$2E,$2F,$30` (5 frames)

Straight:
`$11,$12,$13,$14` repeated 4 times (16 frames)

StraightThenTurn:
straight: `$11,$12,$13,$14`
enter turn: `$31..$37` (7 frames)

This corrects common mistaken assumptions about “6/6/3” turn framing. The engine uses a 7-frame enter turn, 6-frame turning loop (plus repeat), and 5-frame unturn.

## Special Stage render mode (H32) and scroll configuration

During Special Stage gameplay initialization, the engine configures VDP for 32-cell horizontal resolution and 128×32 scroll size. From `s2.asm`:

`move.w #$8C08,(a6)` (H res 32 cells, no interlace, S/H enabled)  
`move.w #$9003,(a6)` (Scroll table size: 128x32)

This is a real VDP mode change: gameplay runs at 256 pixels wide.

## Background “skydome” horizontal scroll deltas (ROM validated)

The background scroll delta table `off_6DEE` is present in the ROM as a pointer table followed by 11 rows of 5 bytes each.
ROM offset of `off_6DEE` pointer table: `0x006DEE` (validated by unique byte-pattern search).

| Row index | Pointer word | Row data bytes (5) |
|---:|---:|---|
| 0 | `0x0016` | `02 02 02 02 02` |
| 1 | `0x001b` | `04 04 05 04 05` |
| 2 | `0x0020` | `0b 0b 0b 0b 0c` |
| 3 | `0x0025` | `00 00 01 00 00` |
| 4 | `0x002a` | `01 01 01 01 01` |
| 5 | `0x002f` | `09 09 08 09 09` |
| 6 | `0x0034` | `09 09 09 09 0a` |
| 7 | `0x0039` | `07 07 06 07 07` |
| 8 | `0x003e` | `00 01 01 01 00` |
| 9 | `0x0043` | `04 03 03 03 04` |
| 10 | `0x0048` | `00 00 ff 00 00` |

This table is referenced by `SSPlaneB_SetHorizOffset` and is used to apply per-scanline horizontal scroll deltas for Plane B, creating the illusion of a curved dome/sky background.

## Animation timer base durations (ROM validated)

`SSAnim_Base_Duration` appears in the ROM as the byte pattern:

`3C 1E 0F 0A 08 06 05 00` (decimal: 60,30,15,10,8,6,5,0)

ROM offset: `0x000B46` (unique match).

This supports the implementation strategy where animation cadence depends on speed/state rather than a fixed “15 fps” assumption.

## Ring requirement tables (ROM validated)

The Special Stage ring requirement tables are stored as 28 bytes each (7 stages × 4 quarters; the 4th quarter is unused but present in the table).

Team mode table (`SpecialStage_RingReq_Team`):
ROM offset: `0x007756`  
SHA-256: `7a312078734d1ddc9e504ee666673c2bf833915c0f51cddcdca5b32df75201ea`

Solo mode table (`SpecialStage_RingReq_Alone`):
ROM offset: `0x007772`  
SHA-256: `706c3a4aada72f1bcd67f32aef40d18a74e4d6e64bbdb48e9e9075d753a5adb9`

The disassembly labels for these tables are `SpecialStage_RingReq_Team` and `SpecialStage_RingReq_Alone`.

## Object stream format and marker handling (validated from disassembly source)

### Object record format

`SSObjectsManager` reads object entries from `(SS_CurrentLevelObjectLocations).w` (decompressed from `misc/Special stage object location lists.kos`). Each record begins with a byte in `d0`:

If `d0 >= 0` (non-negative):
the record is an object entry and is followed by one angle byte (0–255).

Interpretation of `d0`:
lower 6 bits: distance index (0–63)  
bit 6 (`$40`): object type  
if bit 6 is 0: ring (`ObjID_SSRing`)  
if bit 6 is 1: bomb (`ObjID_SSBomb`)

Depth basis:
the engine computes `objoff_30 = (distanceIndex * 4) + (segmentLength * 4)`.

### Marker byte handling

If the first byte read is negative (`d0 < 0`), `SSObjectsManager` uses a three step `addq.b #1,d0` ladder to distinguish specific marker values.

Marker `$FF`:
returns immediately from `SSObjectsManager` (no spawn).

Marker `$FE`:
spawns a checkpoint/message object via `SSLoadMessage` (`ObjID_SSMessage`). Before returning, the manager also:

1) writes `SS_NoCheckpoint_flag` into `(a1)` so the message object can branch its behavior, and
2) if `SS_NoCheckpoint_flag == 0`, writes the current segment’s required rings into `2(a1)` using `SS_Ring_Requirement[SS_CurrentSegment]`.
It then sets `subtype(a1) = SS_NoCheckpointMsg_flag` and finally sets `SS_NoCheckpointMsg_flag = 1`.

Marker `$FD`:
in 1P mode, attempts to allocate and spawn the emerald object (`ObjID_SSEmerald`) via `SSLoadEmerald`. In 2P mode, executes the separate 2P emerald handling path via `SSLoad2pEmlds` (no single emerald object).

Other negative markers (`$FC` and below):
sets `SS_NoCheckpoint_flag = 1`, clears `SS_NoCheckpointMsg_flag = 0`, then behaves like `$FE` (spawns `ObjID_SSMessage`).

The exact *semantic meaning* of each marker in the stage scripts (for example, which marker is used at which gate or at stage end) still requires direct inspection of the compressed object streams for each stage. The behavior above is the exact code path behavior in `SSObjectsManager`.

## Player objects and movement (validated from disassembly source)

This section covers the Special Stage specific player objects and the movement model they implement. The Special Stage uses a circular (projected as an ellipse) coordinate system driven by an 8 bit `angle` and a signed `inertia` value.

### Coordinate system and projection

Player “track space” is stored as fixed point pairs:

`ss_x_pos:ss_x_sub` and `ss_y_pos:ss_y_sub` (word position plus word sub). In practice the airborne integrator treats these as a long value, while most gameplay logic reads only the word position.

Grounded track position is re-derived from `angle` and the current radial distance `ss_z_pos`:

`SSObjectMove`:
reads `inertia`, updates `angle` by `abs(inertia) >> 8`, then calls `CalcSine(angle)` and computes:

`ss_x_pos = (cos(angle) * ss_z_pos) >> 8`  
`ss_y_pos = (sin(angle) * ss_z_pos) >> 8`

Screen space conversion is done by `SSAnglePos`:

`x_pos = ((ss_x_pos * $CC) >> 8) + SS_Offset_X`  
`y_pos = ss_y_pos + SS_Offset_Y`

The `$CC` multiplier horizontally squeezes the circle into an on-screen ellipse.

### Sonic in Special Stage (Obj09)

`Obj09` is Sonic’s Special Stage player object.

Initialization (`Obj09_Init`):
sets `angle = $40` (bottom of the loop), `ss_z_pos = $6E` (Sonic starts closer to the camera than Tails), resets `inertia` and jump state, then spawns the shadow object (`ObjID_SSShadow`) whose `(ss_parent)` points back at Sonic.

Grounded state (`Obj09_MdNormal`):
uses `SSPlayer_Move`, `SSPlayer_Traction`, `SSObjectMove`, `SSAnglePos`, then jump, animation, and collision.

Input to horizontal motion is processed as inertia changes:

`SSPlayer_Move`
if left is held: `inertia += $60` (clamped to `$600`)  
if right is held: `inertia -= $60` (clamped to `-$600`)  
if neither is held: sets a slowing flag and applies friction by subtracting `inertia >> 3` each frame. Releasing from a non-slowing state also sets `ss_slide_timer = $1E`, which counts down and temporarily suppresses the “traction” term described below.

`SSPlayer_Traction`
if `ss_slide_timer == 0`, adds a gravity-like component to inertia:

`inertia += ((cos(angle) * $50) >> 8)`

It also contains a “slip” rule near the top of the loop:
if the player is in the top region (`angle` negative in the signed byte sense) and moving slowly (`abs(inertia) < $100`), the routine switches Sonic into the airborne routine (`routine = 8`). This prevents the player from stalling at the top of the loop.

### Jumping and airborne motion (Obj09_MdJump, Obj09_MdAir)

Jump entry (`SSPlayer_Jump` via `Obj09_MdNormal`):
when A, B, or C is pressed and the player is not already jumping, sets the jumping status bit and:

calls `CalcSine(angle + $80)` and applies an impulse with magnitude `$780` into velocity:

`x_vel += (cos(angle + $80) * $780) >> 8`  
`y_vel += (sin(angle + $80) * $780) >> 7`

Then switches to the jump routine (`routine = 4`).

Airborne integration (`SSObjectMoveAndFall`):
updates the fixed point position using velocity (velocities are shifted left by 8 before adding) and applies gravity every frame:

`y_vel += $A8`

Air steering (`SSPlayer_ChgJumpDir`):
while airborne, holding left or right adjusts `x_vel` by `±$40` per frame.

Recomputing angle from airborne position (`SSPlayer_JumpAngle`):
after moving, Sonic’s `angle` is recomputed from the current `ss_x_pos/ss_y_pos` quadrant using divides and a small angle lookup to keep the angle consistent with the airborne arc.

Landing check (`SSPlayer_DoLevelCollision`):
when `ss_y_pos > 0` and the squared distance `(ss_x_pos^2 + ss_y_pos^2)` is greater than or equal to `ss_z_pos^2`, the player lands back onto the loop:

it clears jumping, zeros `x_vel`, `y_vel`, and `inertia`, sets the slowing flag, then calls `SSObjectMove` and `SSAnglePos` to snap the player back onto the track at the newly computed `angle`.

### Tails in Special Stage (Obj10) and CPU follow behavior

Special Stage Tails uses a dedicated object (`Obj10`), not the normal in-level Tails player object.

Initialization (`Obj10_Init`):
sets `angle = $40`, sets `ss_z_pos` to `$80` by default (Tails further from the camera than Sonic), and spawns both:

the shared shadow object (`ObjID_SSShadow`) for Tails, and
the separate “Tails’ tails” sprite object (`ObjID_SSTailsTails`, Obj88) whose `(ss_parent)` points back at the main Tails object.

Player mode differences:
if `Player_mode != 0`, Tails is initialized with `priority = 3` and `ss_z_pos = $6E` instead (so Tails is the “front” player in the relevant modes).

CPU follow control in 1P (`SSTailsCPU_Control` plus `loc_33908`):
each frame, Sonic’s object (`Obj09`) shifts a 16 word buffer `SS_Ctrl_Record_Buf` and inserts the current `(Ctrl_1_Logical)` (see `loc_33908`). This produces a 16 frame history of Sonic’s inputs.

In 1P special stages, Tails uses the *oldest* entry in that buffer as his controller input:
`Ctrl_2_Logical = SS_Ctrl_Record_Buf_End[-1]`

This makes Tails replay Sonic’s control inputs with a fixed delay and naturally “trail” him around the loop.

If the second controller is actively used (any directional or A, B, C input detected), `SSTailsCPU_Control` attempts to clear the record buffer and sets `Tails_control_counter = $B4`. Note that the original game contains a bug in the buffer clear loop (documented in the disassembly with `fixBugs`), where the pointer does not increment, so the buffer may not actually be cleared as intended. For accuracy to the original ROM, do not apply the `fixBugs` correction unless you are intentionally diverging.

### Swap positions and depth lane

When a player is hurt in special stage (`SSPlayer_Collision` sets `routine_secondary = 2`), the game toggles `SS_Swap_Positions_Flag` depending on which player was hit.

`SSPlayerSwapPositions` (1P only, `Player_mode == 0`) gradually moves each player’s `ss_z_pos` toward either `$6E` or `$80` depending on `SS_Swap_Positions_Flag`, effectively swapping which one is “closer” to the camera. When `ss_z_pos < $77` the sprite `priority` is set to 3, otherwise it is set to 2, which is part of the depth illusion.

## Rings and bombs: animation sizes and collision window (validated)

### Ring animation table (`Ani_obj5B_obj60`)

Rings in Special Stage use 10 perspective “sizes” (indices 0–9). Each size animation uses 5 frames and mirrors the spin:

`size -> size+$0A -> size+$14 -> size+$0A -> size`

Frame delay is 5.

The special animation `$A` is a short sparkle sequence.

### Bomb animation table (`Ani_obj61`)

Bombs use 10 perspective “sizes” (indices 0–9). Each size is a single frame with delay `$0B` (11). Animation `$A` is a multi-frame sequence (explosion).

### Collision window

`Obj61_TestCollision` only performs collision checks when `anim(a0) == 8`. This causes both rings and bombs to be collidable only when they reach a specific near-camera size threshold.

This matches the observed “closest point” interaction rule.

## Bomb hit behavior: ring loss and invulnerability (validated)

### Ring loss per hit (Obj5B_Init)

When a player is hit and has rings, the ring-spill object subtracts rings in BCD-like digits:

If tens digit is non-zero:
decrement tens; subtract 10 rings.

Else if hundreds digit is non-zero:
decrement hundreds; set tens to 9; subtract 10 rings.

Else:
subtract all remaining units (1–9), leaving 0.

Net effect:
if the player has 10+ rings, a hit removes exactly 10 rings.  
if the player has fewer than 10 rings, a hit removes all remaining rings.

### Hurt and invulnerability timing (SSHurt_Animation, LoadSSSonicDynPLC)

In `SSHurt_Animation`, `ss_hurt_timer` increments by 8 each frame and the hurt state ends when it wraps back to 0, which implies a 32-frame hurt duration.

When hurt ends, the engine sets `ss_dplc_timer` to `$1E` (30 frames). `LoadSSSonicDynPLC` decrements this timer each frame and returns early on odd values, producing a flicker and suppressing sprite display while invulnerable.

## Reimplementation strategy (high level)

The intent is to provide a coding agent with a concrete plan that pulls Special Stage data directly from the ROM and reproduces the state machine and projection logic faithfully.

### Data ingestion and caching

On Special Stage load, extract from ROM using validated offsets:

`misc/Special stage object perspective data.kos`  
`misc/Special stage level layouts.nem`  
`misc/Special stage object location lists.kos`  
Track mapping BINCLUDE region (`0x0CA904..0x0DCA38`)  
Special Stage art and background mappings as needed (see tables)

Decompress formats as required:
Kosinski for `*.kos`  
Nemesis for `*.nem`  
ENI for `*.eni`  
BINCLUDE mapping frames are raw mapping structures (no compression).

### Rendering model

Plane A:
track and HUD layers as per Special Stage PNT commands (double-buffered pattern name table updates).

Plane B:
background “skydome” plane with per-scanline H-scroll deltas derived from the `off_6DEE` table.

Sprites:
rings, bombs, shadows, player sprites.

### Track animation and segment state machine

Use `Ani_SpecialStageTrack` sequences to determine the active track mapping frame index each tick. Map those indices into the 56 mapping frames.

Respect the base duration table and the engine’s cadence control (see `SSAnim_Base_Duration`), rather than assuming a fixed framerate.

### Object stream spawning

Implement the `SSObjectsManager` record format and marker handling faithfully. Spawn objects into your engine’s object list at the appropriate segment transitions.

### Collision rules and interactions

Use the special-stage collision window (anim==8) and BCD-like ring subtraction behavior. Implement hurt and invulnerability timers matching the disassembly logic.

## Current gaps and next validation targets (updated)

The following items are explicitly not yet validated into ROM-offset form or fully mapped semantically in this draft:

- **Level layout format**: ✅ Now documented. Each byte is a segment type (0-4) with bit 7 as flip flag.
- **Object perspective data format**: ✅ Now documented. 56 word offset table + 6-byte entries per depth level.
- **Track BIN mapping frame structure**: ✅ Now documented. 3-segment bitstream with bitflags, uncompressed LUT, and RLE LUT.
- Semantic mapping between marker bytes (`$FF/$FE/$FD/$FC...`) and the *meaning* in each stage script (which marker appears at which gate or end point). Marker behaviors are now validated, but the scripts still need decompression and direct inspection.
- Full VRAM tile layout and PLC sequencing (which tiles each asset uses, and DMA order). Offsets are validated, but tile indices and DMA timing are not yet documented.
- Validate exact fixed point scaling for `ss_x_sub/ss_y_sub` and the airborne integrator (the code uses `<<8` before adding velocities). Confirm whether subpixel bits affect any collision or rendering paths beyond airborne movement.
- Checkpoint gate (`Obj5A`) full behavior and message ID mapping (phrase tables, flashing cadence, “rings to go” warning).
- Results screen flow and mode switches (H32/H40 transitions, palette switching) for end-of-stage.

This document will be updated in place on each continuation.

---

## Known implementation issues

### Track art Kosinski decompression incomplete

**Status:** To debug

**Symptom:** The track art at ROM offset `0x0DCA38` (`art/kosinski/SpecStag.kos`, 816 bytes compressed) decompresses to only 1492 bytes. The first word of decompressed data is `0x0174` (372), indicating 372 tiles expected. However, the actual decompressed tile data is only 1490 bytes (46 tiles).

**Impact:** The track LUT (`SSPNT_UncLUT`) references tile indices up to 371 (0x173). With only 46 tiles available, most track tiles render as placeholders.

**Investigation notes:**
- The Kosinski decompressor hits a terminator marker after 1492 bytes
- The disassembly shows the game reads the first word as a tile count, then transfers `count × 32` bytes to VRAM
- Possible causes:
  1. Decompressor bug causing early termination
  2. Multiple Kosinski streams concatenated
  3. Different compression variant used for this asset
  4. ROM offset or size mismatch

**Workaround:** Placeholder patterns are generated for tile indices 46-371 so the track renders (with visual artifacts).

---

## Implementation phases

Effort labels: S (<1h), M (1-3h), L (1-2d), XL (>2d)

### Phase 0: Data access wiring (M) ✅ COMPLETE

**Goal:** Load all SS assets and tables with no rendering.

1. ✅ Add `Sonic2SpecialStageConstants` constants for all offsets in this document
2. ✅ Implement ENI decompressor (if missing)
3. ✅ Implement track BIN frames loader (slice region into 56 byte arrays)
4. ✅ Implement loaders for:
   - `object perspective data.kos` (decompress and buffer)
   - `level layouts.nem` (decompress; format analysis required)
   - `object location lists.kos` (decompress)
   - Skydome table (`off_6DEE`)
   - Base duration table
   - Ring requirement tables
5. ✅ Add test harness verifying lengths match documented sizes

### Phase 1: Static rendering (M-L) ✅ COMPLETE

**Goal:** Render static Special Stage background and one track frame in H32 mode.

1. ✅ Implement `Sonic2SpecialStageScene` / game mode:
   - Switch to H32 / 128x32 scroll config
   - Set up palette, planes, VRAM allocations
2. ✅ Decompress background art `.nem` + background `.eni` mappings into Plane B
3. ✅ Load one "Straight path" track frame (`MapSpec_Straight1`) and render on Plane A
4. ✅ Render background with zero scroll to validate H32 mode and plane layering

### Phase 2: Track animation + segment state machine (L) ✅ COMPLETE

**Goal:** Track animates with dummy stage layout.

1. ✅ Hard-code `Ani_SpecialStageTrack` sequences into Java enums/arrays
2. ✅ Implement `SSTrackAnimator`:
   - Track current segment type
   - Step through frame indices using fixed frame delay
   - Pick correct BIN frame and write mapping into Plane A
3. ✅ Use mocked stage layout sequence until layout `.nem` format is decoded

### Phase 3: Player physics (L) ✅ COMPLETE

**Goal:** Sonic (and Tails) can run and jump on the loop; no rings/bombs.

1. ✅ Implement SS player state fields (`angle`, `inertia`, `ss_x_pos/sub`, `ss_y_pos/sub`, `ss_z_pos`, `x_vel`, `y_vel`, `ss_slide_timer`)
2. ✅ Implement:
   - `SSObjectMove` (angle advance + track-space position)
   - `SSAnglePos` (projection to screen space)
   - `SSPlayer_Move` and `SSPlayer_Traction`
   - `SSPlayer_Jump`, `SSObjectMoveAndFall`, `SSPlayer_ChgJumpDir`, `SSPlayer_JumpAngle`, `SSPlayer_DoLevelCollision`
3. ✅ Draw Sonic's sprite at computed `x_pos,y_pos` using SS art
4. ✅ Add Tails with CPU follow using 16-word `SS_Ctrl_Record_Buf`
5. ✅ Implement swap positions + depth-lane logic

**Implementation notes:**
- Player mode respects `MAIN_CHARACTER_CODE` config: "sonic" = Sonic alone, "tails" = Tails alone, "sonic_and_tails" = both
- Real art loaded: 127 background patterns, 46 track patterns (+ 326 placeholders), 851 player patterns
- See "Known implementation issues" for track art decompression limitation

### Phase 4: Object stream + rings/bombs (L)

**Goal:** Full playable track with rings/bombs and correct hit behavior.

1. Parse `object location lists.kos` fully:
   - Build per-stage, per-segment object streams
   - Implement `SSObjectsManager` record/marker logic
2. Implement ring and bomb objects:
   - Perspective sizes 0-9, animation tables
   - Collision window: only test on `anim == 8`
3. Implement bomb-hit effect:
   - BCD-like ring loss
   - Hurt duration (32 frames) and invulnerability (30 frames flicker)
   - `SSPlayerSwapPositions` behavior

### Phase 5: Gates, requirements, results (L-XL)

**Goal:** Fully accurate Special Stage flow.

1. Decode stage script marker semantics
2. Wire ring requirement tables into gate logic
3. Implement checkpoint gate object (`Obj5A`) and message behavior
4. Implement results screen with H32/H40 transition

---

## Data loaders needed

| Loader | Format | Status | Priority |
|---|---|:---:|:---:|
| ENI decompressor | Enigma compression | Format documented | High |
| Track BIN frames | 3-segment bitstream | Format documented | High |
| Object perspective table | Kosinski -> 6-byte entries | Format documented | High |
| Level layout | Nemesis -> segment bytes | Format documented | High |
| Object location lists | Kosinski -> documented format | Missing | Medium |
| Skydome scroll table | Row/column indexed | Format documented | Medium |
| Ring requirement tables | Raw bytes | Missing | Low |
| Base duration table | Speed factor indexed | Format documented | Low |

---

## Risks and guardrails

| Risk | Mitigation |
|---|---|
| Misinterpreting binary formats (ENI, BIN, perspective data, layouts) | Cross-check against s2disasm; dump frames and compare to emulator screenshots |
| VDP/render mismatch (H32 + per-scanline scroll) | Write isolated visual tests before using SS data |
| ROM version mismatch | Hard-check ROM header/SHA before enabling SS content |
| Coupling SS too tightly to existing level code | Isolate SS mode in its own scene and helpers |

---

## Enigma compression format (validated)

Enigma is a run-length encoding variant used for plane mappings. Reference implementations exist in `docs/s2ssedit-0.2.0/src/lib/enigma.cc` and documentation in `docs/Enigma compression - Sega Retro.htm`.

### Header (6 bytes)

| Offset | Size | Description |
|---:|:---:|---|
| 0 | 1 byte | Number of bits in inline copy value (packet_length) |
| 1 | 1 byte | Bitfield `000PCCVH` - indicates which tile attribute bits may be set (P=priority, CC=palette, V=vflip, H=hflip). Number of set bits = length of inline render flags bitfield |
| 2 | 2 bytes | Incremental copy word (big-endian) |
| 4 | 2 bytes | Literal copy word (big-endian) |

### Bitstream commands

After the header, data is a bitstream. Each entry begins with type bits:

| Type bits | Meaning |
|---|---|
| `00` | Copy incremental word (count+1) times, increment after each copy |
| `01` | Copy literal word (count+1) times |
| `100` | Copy inline value (count+1) times |
| `101` | Copy inline value (count+1) times, increment after each copy |
| `110` | Copy inline value (count+1) times, decrement after each copy |
| `111` | If count=15: terminate. Otherwise: copy (count+1) inline values verbatim |

For types 100/101/110: followed by inline render flags bitfield + copy value.
For type 111 (non-terminating): followed by (count+1) pairs of (flags, value).

### Decompression output

Output is big-endian 16-bit words representing VDP pattern name table entries.

---

## Level layout format (validated from disassembly)

The level layout is stored in `misc/Special stage level layouts.nem` (Nemesis compressed, 260 bytes compressed).

After decompression, layout data is indexed per-stage. The engine uses:
```
movea.l (SS_CurrentLevelLayout).w,a1
move.b (a1,segment_index.w),d3   ; read segment byte
andi.b #$7F,d3                   ; strip flip flag
```

### Segment byte format

| Bits | Meaning |
|---|---|
| Bit 7 | Horizontal flip flag (1 = flip track left/right) |
| Bits 0-6 | Segment animation type (0-4) |

### Segment animation types

| Type | Animation | Frame count |
|---:|---|---:|
| 0 | `TurnThenRise` - turning then slope up | 24 frames |
| 1 | `TurnThenDrop` - turning then slope down | 24 frames |
| 2 | `TurnThenStraight` - turning then exit curve | 12 frames |
| 3 | `Straight` - straight path (loops 4x) | 16 frames |
| 4 | `StraightThenTurn` - straight then enter curve | 11 frames |

Each stage layout is a sequence of these segment bytes, terminated implicitly by stage length.

---

## Track mapping frame format (validated from disassembly)

The 56 track mapping BIN frames (at `0x0CA904..0x0DCA38`) use a specialized 3-segment bitstream format:

### Frame structure

Each frame file contains 3 segments with identical structure:
```
4 bytes: segment length (only lower 2 bytes used)
N bytes: segment data (bitstream)
```

The engine parses segment offsets as:
```
segment1_data = file + 4
segment2_offset = read_word(file+2) + 4
segment2_data = file + segment2_offset + 4
segment3_offset = read_word(segment2_data-2) + 4
segment3_data = segment2_data + segment3_offset + 4
```

### Segment purposes

| Segment | Purpose |
|---:|---|
| 1 | Bitflags - determines whether each tile uses uncompressed (segment 2) or RLE (segment 3) lookup |
| 2 | Uncompressed mappings - indexed into `SSPNT_UncLUT` / `SSPNT_UncLUT_Part2` |
| 3 | RLE mappings - indexed into `SSPNT_RLELUT` / `SSPNT_RLELUT_Part2` |

### Segment 1 (bitflags)

Bitstream: each bit selects source for next tile:
- `0` = use segment 2 (uncompressed)
- `1` = use segment 3 (RLE)

### Segment 2 (uncompressed lookup)

Bitstream format:
- First bit determines index size:
  - `0` = next 6 bits index into `SSPNT_UncLUT` (first 64 entries)
  - `1` = next 10 bits index into `SSPNT_UncLUT_Part2` (additional entries)
- Tiles drawn in palette line 3

### Segment 3 (RLE lookup)

Bitstream format:
- First bit determines index size:
  - `0` = next 6 bits index into `SSPNT_RLELUT`
  - `1` = next 7 bits index into `SSPNT_RLELUT_Part2`
- Tiles drawn in palette line 3, high priority

The lookup tables (`SSPNT_UncLUT`, `SSPNT_RLELUT`, etc.) are hardcoded in the engine as pattern name table entries with tile indices, flip flags, and priority bits.

---

## Object perspective data format (validated from disassembly)

The perspective data (`misc/Special stage object perspective data.kos`, Kosinski compressed at `0x0E24FE`) provides screen-space projection parameters for rings and bombs based on their depth and the current track frame.

### Overall structure

After decompression (`$1AFC` bytes = 6908 bytes):

```
Offset table: 56 words (112 bytes)
  - Each word is an offset from the start of the file to a per-frame perspective block
  - Indexed by SSTrack_mapping_frame (0-55)

Per-frame blocks: variable size
  - One block for each of the 56 track frames
```

### Per-frame block structure

```
Word 0: max_depth_count (number of valid depth entries)
Byte 2+: array of 6-byte entries, one per depth level
```

### 6-byte entry format

| Offset | Size | Field | Description |
|---:|:---:|---|---|
| 0 | 1 byte | x_base | Base X screen position |
| 1 | 1 byte | y_base | Base Y screen position (signed; values >= $48 are sign-extended) |
| 2 | 1 byte | x_radius | X projection radius (multiplied by cos(angle)) |
| 3 | 1 byte | y_radius | Y projection radius (multiplied by sin(angle)) |
| 4 | 1 byte | angle_min | Minimum visible angle (for culling) |
| 5 | 1 byte | angle_max | Maximum visible angle (for culling); 0 = no culling |

### Usage in object positioning

The engine computes screen position as:
```
depth_index = objoff_30 - 1
entry_offset = depth_index * 6
x_pos = x_base + (cos(angle) * x_radius) >> 8
y_pos = y_base + (sin(angle) * y_radius) >> 8
```

When `SSTrack_Orientation` is set (track flipped), the x_base is negated relative to $100.

### Visibility culling

If `angle_max != 0`, the object is only visible when:
- Normal orientation: `angle < angle_min` OR `angle >= angle_max`
- Flipped orientation: angle range is inverted around $80

Objects outside the visible range have their `render_flags.on_screen` bit cleared and are not drawn.

### Depth indexing

Objects store their depth in `objoff_30`. The engine checks:
```
if objoff_30 == 0: object not visible
if objoff_30 > max_depth_count: object not visible
```

Depth values decrease as objects approach the player (perspective size increases).

---

## Skydome scroll table usage semantics (validated from disassembly)

The skydome background uses per-scanline horizontal scroll to create a curved dome illusion. The `off_6DEE` table and `SSPlaneB_SetHorizOffset` routine control this.

### Table structure at `0x006DEE`

```
11 word offsets (22 bytes) pointing to 11 rows
11 rows of 5 bytes each (55 bytes)
Total: 77 bytes
```

### Row selection logic

The row is selected based on track animation type and current animation frame:

| Animation types | Frame range | Row index (d1) |
|---|---|---:|
| TurnThenRise, TurnThenDrop, TurnThenStraight | frame < 1 | 0 |
| TurnThenRise, TurnThenDrop, TurnThenStraight | frame == 1 | 2 |
| TurnThenRise, TurnThenDrop, TurnThenStraight | 2 <= frame < $A | 4 |
| TurnThenRise, TurnThenDrop, TurnThenStraight | frame == $A | 2 |
| TurnThenRise, TurnThenDrop, TurnThenStraight | frame == $B | 0 |
| TurnThenRise, TurnThenDrop, TurnThenStraight | frame >= $C | (return, no scroll) |
| Straight, StraightThenTurn | any | (return, no scroll) |

### Column selection

Within the selected row, the column (0-4) is `SSTrack_drawing_index` (the sub-frame index 0-4 within each animation frame).

### Scroll delta application

```
delta = row[column]  ; signed byte
if SS_Alternate_HorizScroll_Buf:
    delta = -delta
for each scanline:
    Plane_B_scroll[line] -= delta
```

The delta is subtracted from the Plane B horizontal scroll for all 224 scanlines, creating uniform horizontal movement of the background that varies based on track curvature phase.

### Row data values (from ROM)

| Row | Bytes (hex) | Description |
|---:|---|---|
| 0 | `02 02 02 02 02` | Slow uniform scroll |
| 1 | `04 04 05 04 05` | Medium alternating |
| 2 | `0B 0B 0B 0B 0C` | Fast uniform |
| 3 | `00 00 01 00 00` | Near-static |
| 4 | `01 01 01 01 01` | Very slow uniform |
| 5 | `09 09 08 09 09` | Medium-fast |
| 6 | `09 09 09 09 0A` | Medium-fast |
| 7 | `07 07 06 07 07` | Medium |
| 8 | `00 01 01 01 00` | Near-static edges |
| 9 | `04 03 03 03 04` | Medium edges faster |
| 10 | `00 00 FF 00 00` | Reverse center ($FF = -1) |

---

## SSAnim_Base_Duration index mapping (validated from disassembly)

The animation duration table controls how many frames each track animation step lasts, based on the current speed factor.

### Table at `0x000B46`

```
Index:    0    1    2    3    4    5    6    7
Value:   60   30   15   10    8    6    5    0
```

### Speed factor to index conversion

```
SS_Cur_Speed_Factor is a 32-bit value (high word unused)
index = (SS_Cur_Speed_Factor & 0xFFFF) >> 1
duration = SSAnim_Base_Duration[index]
```

### Known speed factor values

| Speed factor | Index | Duration (frames) | Context |
|---:|---:|---:|---|
| `$C0000` | 6 | 5 | Initial/normal gameplay |
| `$00000` | 0 | 60 | Stage end (slowing down) |

The speed factor is set to `$C0000` at stage start and `$0` when the stage ends (e.g., after collecting emerald or failing).

### Timer usage

- `SSTrack_duration_timer`: counts down from duration; when it reaches 0, advance to next track animation frame
- `SS_player_anim_frame_timer`: set to `duration - 1`; used for player sprite animation timing

---

## Checkpoint gate (Obj5A) full behavior (validated from disassembly)

Object 5A handles checkpoint messages, ring requirement displays, and the rainbow effect at checkpoints.

### Object routines

| Routine | Name | Purpose |
|---:|---|---|
| 0 | `Obj5A_Init` | Initialize checkpoint or rings message |
| 2 | `Obj5A_CheckpointRainbow` | Animate rainbow arc at checkpoint |
| 4 | `Obj5A_TextFlyoutInit` | Initialize text flyout |
| 6 | `Obj5A_Handshake` | 2P handshake animation |
| 8 | `Obj5A_TextFlyout` | Animate text flying out |
| $A | `Obj5A_MostRingsWin` | 2P "most rings wins" message |
| $C | `Obj5A_RingCheckTrigger` | Check if ring requirement met |
| $E | `Obj5A_RingsNeeded` | Display "XX RINGS TO GO" counter |
| $10 | `Obj5A_FlashMessage` | Flash message text |
| $12 | `Obj5A_MoveAndFlash` | Move and flash (for animated text) |
| $14 | `Obj5A_FlashOnly` | Flash only (for "S" in "RINGS") |

### Initialization behavior

When `SS_NoCheckpoint_flag == 0` (true checkpoint):
1. Check if track is on straight segment approaching checkpoint (MapSpec_Straight4 range)
2. Set `SS_Checkpoint_Rainbow_flag`
3. Spawn 7 rainbow ring objects with routine=2
4. Apply rainbow palette colors

When `SS_NoCheckpoint_flag == 1` (rings message only):
1. Clear `SS_NoCheckpoint_flag`
2. Skip if 2P mode
3. Clear `SS_HideRingsToGo` and `SS_TriggerRingsToGo`
4. Delete self

### Rainbow checkpoint effect

Rainbow rings animate through 10 frame indices:
```
Obj5A_Rainbow_Frames: 0, 1, 1, 1, 2, 4, 6, 8, 9, $FF (end)
```

Positions are stored in `Obj5A_Rainbow_Positions` as (x, y) byte pairs, with 7 arcs of ring positions.

### "RINGS TO GO" display

Text components:
- "RING" - static text
- "!OGOT" (reversed "TO GO!") - animated flyout
- "S" - only shown when rings >= 2

Ring count display:
1. Calculate: `remaining = SS_Ring_Requirement - current_rings`
2. Convert to BCD (3 digits max)
3. Update sub-sprite frames for digit display
4. If remaining <= 0, increment `SS_NoRingsTogoLifetime`
5. If lifetime >= 12 frames, hide the display

### Flash timing

Messages flash at 7/8 duty cycle:
```
if (Vint_runcount & 7) < 6:
    display sprite
else:
    hide
```

### Character encoding for messages

Custom character set mapping:
```
A=$D, B=$11, C=$7, D=$11, E=$1, F=$11
G=$0, H=$B, I=$4, J=$11, K=$11, L=$9, M=$F, N=$5, O=$8, P=$C
Q=$11, R=$3, S=$6, T=$2, U=$A, V=$11, W=$10, X=$11, Y=$E, Z=$11
!=$11, .=$12
```

Art tile base: `ArtTile_ArtNem_SpecialMessages` (palette line 2)
