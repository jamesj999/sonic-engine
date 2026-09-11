# S3K Super Emerald Results Screen — Research Archive

> **Status:** Deferred. This documents the Super Emerald / Hyper form reveal path of the special stage results screen. Implement the Chaos Emerald path first.

## Overview

When the player collects all Blue Spheres in an S3K combined cart (not SK-alone) special stage, the results screen includes a Super Emerald reveal sequence with Hidden Palace Zone background, camera panning, shield orb animations, and Hyper form character display.

## ROM Location

`sonic3k.asm` lines 63010-64164 — the full `SpecialStage_Results` routine and `Obj_SpecialStage_Results` state machine.

## Trigger Conditions

The Super Emerald path activates when ALL of these are true:
- `SK_alone_flag` == 0 (not SK standalone)
- `SK_special_stage_flag` != 0 (S3K combined cart)
- `Special_stage_spheres_left` == 0 (player collected all spheres)
- Character is Sonic or Knuckles (Tails excluded from Super Emerald entry in most zones)

Additionally, zone-specific gating:
- ICZ (zone $4): Always qualifies for Super Emerald entry
- Zones $7+ (MHZ onward): Always qualifies
- Earlier zones: Sonic/Knuckles get Chaos Emerald reveal; Tails gets exit only

## HPZ Background Loading (sonic3k.asm lines 63119-63181)

When the Super Emerald path is active, the setup routine loads:
1. **Palette**: Pal_HPZIntro+$20 → palette line 3 (with $CCC0CCC fill for Normal, real data for Target)
2. **Level data**: Layout_HPZ → Level_layout_header ($1000 bytes)
3. **128x128 blocks**: HPZ_128x128_Primary_Kos + Secondary (Kosinski decompressed to RAM_start)
4. **16x16 chunks**: HPZ_16x16_Primary_Kos + Secondary → Block_table
5. **Patterns**: ArtKosM_HPZ_Primary → VRAM $000, ArtKosM_HPZ_Secondary at size offset
6. **PLC $48**: Additional HPZ art
7. **Zone/Act**: Set to $1701 (HPZ)
8. **Camera**: Per-stage from word_2E398 lookup table:
   - Stages 0-7: $15A0, $1540, $1600, $1500, $1640, $14B0, $1690, $15A0
   - Y positions: $368, $3A0, $3A0, $350, $350, $390, $390, $368
9. **Level setup**: Full j_LevelSetup + Load_Sprites

## Obj_SpecialStage_Results — Super Emerald Routines (Routines E-12)

### Routine E (loc_2E616) — Camera Pan + Shield Orbs
- Camera pans Y from $240 to $320 at 1px/frame
- At Y=$2A0: Creates 4-6 slide-out objects (loc_2EC1E) for text cleanup
  - If ≥50 rings collected, also sends continue icon off via loc_2EC4A
- Creates 2-4 additional countdown objects at object_size*44+
  - Extra 2 if Super_emerald_count >= 7
- Sets staggered $2E timers: 8, 12, 12, 16, 16, [20 if ≥50 rings]
- When Y reaches $320: Loads ArtUnc_Invincibility → ArtTile_Shield
- Creates 8 shield orb objects (loc_2ECD0) with sin/cos rotation
  - Initial angle offset: 0, $10, $20, ... $70
  - Rotation speed: $2000/frame (stored in $32)
  - Position: centered on stage-specific X from word_2E398 + $A0, Y from +$10 entries
- Plays sfx_Signpost

### Routine 10 (loc_2E746) — Hyper Emerald Check
- First frame: Plays sfx_SuperEmerald, sets $30 to -1 (flag)
- If Super_emerald_count < 7: Sets 60-frame wait, returns to routine A (exit)
- If Super_emerald_count >= 7:
  - Waits for $2E timer
  - Pans Camera_X_pos toward $15A0 at 1px/frame
  - When centered: Creates 8 MORE shield orb objects (second ring, $36 flag set)
    - Second ring centers on ($1640, $340) regardless of stage
    - Rotation speed: starts at 0 ($32 cleared)
  - Sets 120-frame wait, plays sfx_Signpost, advances to routine 12

### Routine 12 (loc_2E7DA) — Final Message
- Waits for $2E timer
- Creates 7 objects from ObjDat2_2E984 at object_size*61:
  - "CAN" label, character name, "NOW", "BECOME", middle text, character name, sentinel
  - Sentinel object (loc_2E9D8) watches for all text to reach position, then clears _unkFAC1 and plays sfx_Perfect
- Sets 360-frame wait, returns to routine A (exit)

## Shield Orb Object (loc_2ECD0 / loc_2ED2A)

Uses `Map_Invincibility` mappings with `ArtTile_Shield` art tile.
- Position calculated via GetSineCosine with angle from $2E
- Rotation: angle += $2000/frame (or 0 for second ring initially)
- Radius and Y calculated from sin/cos with scaling
- 2 child sprites per orb
- Second ring ($36 flag): Different initial position ($1640, $340), starts stationary

## ObjDat2 Tables (element arrays)

### ObjDat2_2E918 (Chaos Emerald reveal — 6 entries)
| # | Function | TargetX | StartX | Y | Frame | Width |
|---|----------|---------|--------|---|-------|-------|
| 0 | loc_2EA10 | $C0 | $3C0 | $98 | $27 | $38 |
| 1 | loc_2EAF6 | $100 | $400 | $98 | $13 | $48 |
| 2 | loc_2EA3E | $150 | $450 | $98 | $3A | $30 |
| 3 | loc_2EA10 | $C0 | $440 | $B0 | $28 | $20 |
| 4 | loc_2E9F6 | $E8 | $468 | $B0 | $12 | $50 |
| 5 | loc_2EAF6 | $138 | $4B8 | $B0 | $13 | $48 |

### ObjDat2_2E960 (Super Emerald reveal — 3 entries)
| # | Function | TargetX | StartX | Y | Frame | Width |
|---|----------|---------|--------|---|-------|-------|
| 0 | loc_2EAF6 | $B8 | $3B8 | $98 | $13 | $48 |
| 1 | loc_2EA3E | $148 | $448 | $98 | $2E | $40 |
| 2 | loc_2EA50 | $120 | $4A0 | $B0 | $2F | $60 |

### ObjDat2_2E984 (Final "CAN NOW BECOME" — 7 entries)
| # | Function | TargetX | StartX | Y | Frame | Width |
|---|----------|---------|--------|---|-------|-------|
| 0 | loc_2EA10 | $C0 | $3C0 | $124 | $2C | $38 |
| 1 | loc_2EAF6 | $100 | $400 | $124 | $13 | $48 |
| 2 | loc_2EA3E | $150 | $450 | $124 | $2D | $30 |
| 3 | loc_2EA10 | $C0 | $440 | $13C | $35 | $20 |
| 4 | loc_2E9F6 | $E8 | $468 | $13C | $12 | $50 |
| 5 | loc_2EAF6 | $138 | $4B8 | $13C | $13 | $48 |
| 6 | loc_2E9D8 | 0 | 0 | 0 | 0 | 0 |

## Art Assets (S&K VRAM layout)

| Asset | VRAM Dest | Description |
|-------|-----------|-------------|
| ArtKosM_ResultsGeneral | $5B8 | General results text tiles |
| ArtKosM_SSResultsSUPER | $50F | Sonic Super form sprite |
| ArtKosM_SSResultsSUPERk | $50F | Knuckles Super form sprite |
| ArtKosM_SSResultsHYPER | $50F | Sonic Hyper form sprite |
| ArtKosM_SSResultsHYPERk | $50F | Knuckles Hyper form sprite |
| ArtKosM_ResultsSONIC/TAILS/MILES/KNUCKLES | $4F1 | Character name text |
| ArtKosM_SSResults | $523 | SS-specific results text |
| ArtNem_RingHUDText | ArtTile_Ring ($6BC) | Ring/HUD text via PLC |
| ArtUnc_Invincibility | ArtTile_Shield | Shield orb art (loaded during routine E) |
| Pal_Results | Full palette ($80 bytes) | 4 palette lines |

## Helper Subroutines

- **sub_2EC80**: Character position offset — Sonic: d0=0,d1=0; Knuckles: d0=-$18,d1=3; Miles: d0=0,d1=1; Tails: d0=4,d1=2
- **sub_2ECA8**: Emerald counter selection — returns pointer to Chaos_emerald_count or Super_emerald_count based on SK flags
- **sub_2ECBC**: Art tile palette selection — sets art_tile to palette_line_3 for SK-alone/not-combined; leaves default for S3K combined

## Palette Handling

S3K combined path overrides palette extensively:
- Lines 63119-63133: Copies Pal_Results to all 4 palette lines (Normal + Target, lines 2-3)
- Lines 63135-63142: Loads Pal_HPZIntro+$20 into palette line 3 Target (for fade-in)
- Normal palette line 3 filled with $CCC0CCC (gray, creates white flash)
- sub_2E2C0: Knuckles-specific palette overrides for hair colors

## Emerald Flicker (S&K version)

`Emerald_flicker_flag` is a 3-state counter (0, 1, 2) incremented each frame, wrapping at 3.
Emerald indicators (loc_2EAA6) draw only when flag != 0, creating a 2/3 visible, 1/3 hidden flicker.
(S3-only version uses `btst #0,(Level_frame_counter+1)` — simpler every-other-frame toggle.)
