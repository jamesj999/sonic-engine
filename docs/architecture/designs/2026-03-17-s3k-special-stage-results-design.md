# S3K Special Stage Results Screen Design (Chaos Emeralds)

## Overview

Implement the results screen shown after completing an S3K Blue Sphere special stage (entered via big rings). Displays ring bonus, time bonus (if perfect), emerald collection indicators, and "GOT A CHAOS EMERALD" message if earned. White background, no level backdrop.

This covers the **Chaos Emerald path only** (SK-alone / S3-alone / not-all-super-emeralds). The Super Emerald / Hyper form reveal with HPZ background is deferred (see `2026-03-17-s3k-super-emerald-results-screen-archive.md`).

## ROM Reference

- **S&K**: `sonic3k.asm` lines 63010-64164 (`SpecialStage_Results` + `Obj_SpecialStage_Results`)
- **S3**: `s3.asm` lines 53808-54256 (Chaos Emerald path, 6 routines)
- Uses S&K VRAM addresses (the engine runs S3K combined)

## Integration Point

`Sonic3kSpecialStageProvider.createResultsScreen(ringsCollected, gotEmerald, stageIndex, totalEmeraldCount)` currently returns `NoOpResultsScreen.INSTANCE`. Replace with new `S3kSpecialStageResultsScreen` implementing `ResultsScreen`.

**GameLoop lifecycle**:
- `GameMode.SPECIAL_STAGE_RESULTS` — camera set to (0,0), black GL clear
- Calls `update(frameCounter, null)` each frame
- Calls `appendRenderCommands(commands)` for rendering
- When `isComplete()` returns true, GameLoop fades to black and returns to level

**Background**: White — `Pal_Results` sets backdrop color (palette entry 0) to white. The GL clear color should be set to white when entering this mode, or the palette's backdrop color rendered as a full-screen fill.

## Class: S3kSpecialStageResultsScreen

Implements `ResultsScreen` directly (not extending `AbstractResultsScreen` — the 6-state special stage flow doesn't map to the 5-state level results machine).

### Constructor Parameters

```java
S3kSpecialStageResultsScreen(int ringsCollected, boolean gotEmerald,
                              int stageIndex, int totalEmeraldCount,
                              PlayerCharacter character)
```

`character` obtained from `AbstractLevelEventManager.getInstance().getPlayerCharacter()` at creation time in the provider.

### State Machine

6 states matching ROM routines 0, 2, 4, 6, 8, A:

| State | ROM | Duration | Action |
|-------|-----|----------|--------|
| INIT | 0 | 1 frame | Create 19 elements, calculate bonuses, fade out music |
| PRE_TALLY | 2 | 360f wait → variable tally | Play mus_GotThroughAct at frame 71 (counter==289). Then decrement ring/time bonus by 10/frame. sfx_Switch every 4 frames (Level_frame_counter & 3). sfx_Register when done. |
| POST_TALLY | 4 | 120f wait → optional 270f | If ≥50 rings: spawn continue icon (blinking), play sfx_Continue, wait 270f. Otherwise advance immediately. |
| EMERALD_CHECK | 6 | variable | If `!gotEmerald` OR `totalEmeraldCount < 7`: exit (isComplete=true). If earned: create 5 cleanup slide-out objects, set childCounter=5, advance when all done. |
| EMERALD_REVEAL | 8 | 240f (4*60) | Create 6 new text elements from ObjDat2_2E918, wait 240 frames. |
| EXIT | A | wait → complete | Count down timer, then set `isComplete=true`. |

**Key timing detail**: The 360-frame pre-tally timer counts DOWN from 360. Music plays when counter == 289 (i.e., 71 frames into the wait: 360 - 289 = 71). This matches the level results screen timing.

**Tally differences from level results**:
- Ring bonus = `ringsCollected * 10`
- Time bonus = 5000 if `gotEmerald` (all spheres collected), else 0
- No time-based bonus table — it's binary (perfect or nothing)
- Tick sound uses `Level_frame_counter & 3` (global frame counter mod 4)

### Bonus Calculation (ROM lines 63320-63327)

```
Ring_bonus = Special_stage_ring_count × 10
Time_bonus = (Special_stage_rings_left == 0) ? 5000 : 0
```

Note: `gotEmerald` from the provider maps to `Special_stage_spheres_left == 0` (all spheres collected = earned emerald for current stage).

## Element Layout

### Phase 1: Initial 19 Elements (ObjDat2_2E834)

All positions in VDP coordinates. Screen coords = VDP - 128. Elements with startX ≠ targetX slide at 16px/frame. Elements with startX == targetX appear immediately.

| # | Type | TargetX | StartX | Y | Frame | Width | Behavior |
|---|------|---------|--------|---|-------|-------|----------|
| 0 | Label | $120 | $4E0 | $100 | $17 | $60 | "SCORE" — slides from right. S3K combined: frame += $1A; Knuckles: frame += 5 more |
| 1 | Label | $C0 | $4C0 | $118 | $18 | $58 | "RING BONUS" — same S3K frame adjustment as #0 |
| 2 | RingBonus | $178 | $578 | $118 | 1 | $40 | 7-digit ring bonus display |
| 3 | Label | $C0 | $500 | $128 | $19 | $40 | "TIME BONUS" — same S3K frame adjustment |
| 4 | TimeBonus | $178 | $5B8 | $128 | 1 | $40 | 7-digit time bonus display |
| 5 | Continue | $C0 | $540 | $138 | $1A | $48 | "CONTINUE" — only visible if ≥50 rings. S3K frame adjustment. |
| 6 | Emerald | $120 | $120 | $D0 | $1B | 0 | Emerald indicator slot 0. Flicker if collected. |
| 7 | Emerald | $110 | $110 | $E8 | $1C | 1 | Slot 1 |
| 8 | Emerald | $130 | $130 | $E8 | $1D | 2 | Slot 2 |
| 9 | Emerald | $100 | $100 | $D0 | $1E | 3 | Slot 3 |
| 10 | Emerald | $140 | $140 | $D0 | $1F | 4 | Slot 4 |
| 11 | Emerald | $F0 | $F0 | $E8 | $20 | 5 | Slot 5 |
| 12 | Emerald | $150 | $150 | $E8 | $21 | 6 | Slot 6 |
| 13 | FailMsg | $120 | $460 | $A0 | $22 | $60 | "ALL SPHERES TURNED INTO RINGS" — only if FAILED. S3K frame adjustment. |
| 14 | CharName | $D4 | $394 | $98 | $13 | $48 | Character name — only if SUCCEEDED |
| 15 | GotAll | $124 | $3E4 | $98 | $23 | $48 | "GOT THEM ALL" — only if SUCCEEDED |
| 16 | ChaosEm | $120 | $460 | $B0 | $24 | $64 | Emerald label — only if SUCCEEDED. All-7: shifts -8px. S3K combined: frame=$30 |
| 17 | NowText | $114 | $3D4 | $98 | $25 | $20 | "NOW" — only if SUCCEEDED + all 7 |
| 18 | SuperText | $118 | $458 | $B0 | $26 | $10 | "SUPER SONIC" — only if SUCCEEDED + all 7 |

**Emerald indicator behavior** (loc_2EAA6): Check `Collected_emeralds_array[width_pixels]`. If collected == 1, draw with flicker (3-state: draw 2 out of 3 frames using `Emerald_flicker_flag`). If not collected, delete (don't render).

**S3K combined frame adjustment** (loc_2EA1E): When `SK_special_stage_flag` is set (S3K combined, not SK-alone), label frames get +$1A added. Knuckles gets +5 more. This selects different text art for the combined cart. For our engine (always S3K combined), always apply the +$1A adjustment, plus +5 for Knuckles.

**Character name** (element 14, loc_2EAD8/loc_2EAF6): Uses sub_2EC80 for position offset:
- Sonic: d0=0, d1=0 (frame $13)
- Knuckles: d0=-$18, d1=3 (frame $16, shifted left $18)
- Miles: d0=0, d1=1 (frame $14)
- Tails: d0=4, d1=2 (frame $15, shifted right 4)

Also, if all 7 chaos emeralds: shifts -$10 on both x_pos and targetX.

**art_tile per element**: Different elements use different palette lines:
- Ring/time bonus digits: art_tile = `(_unkEF68)` which is always $98 (line 63191)
- Labels with sub_2ECBC: art_tile = `make_art_tile($000,3,0)` for SK-alone, default for S3K combined
- Character name (loc_2EAF6): art_tile = -$87 (= $FF79, maps to palette line + flags); Knuckles in S3K combined adds palette_line_1

### Phase 2: Emerald Reveal Text (ObjDat2_2E918, 6 elements)

Created after cleanup objects finish sliding phase 1 text off-screen.

| # | Type | TargetX | StartX | Y | Frame | Width | Behavior |
|---|------|---------|--------|---|-------|-------|----------|
| 0 | Label | $C0 | $3C0 | $98 | $27 | $38 | "GOT A" — position adjusted by sub_2EC80 |
| 1 | CharName | $100 | $400 | $98 | $13 | $48 | Character name (loc_2EAF6 — same as element 14 above) |
| 2 | Label | $150 | $450 | $98 | $3A | $30 | "CHAOS" — shifted left by sub_2EC80 offset |
| 3 | Label | $C0 | $440 | $B0 | $28 | $20 | "A" / second line — adjusted by sub_2EC80 |
| 4 | Middle | $E8 | $468 | $B0 | $12 | $50 | Middle text — Knuckles S3K combined: art_tile = palette_line_1 |
| 5 | CharName | $138 | $4B8 | $B0 | $13 | $48 | Character name again (loc_2EAF6) |

### Cleanup Objects (loc_2EC1E)

5 objects created in EMERALD_CHECK phase. Each has a $2E timer (0, 0, 4, 0, 4 for slots 0-4). After timer expires, slides right at 32px/frame ($20). When off-screen (render_flags bit 7 clear), decrements parent $30 counter and deletes self. When parent $30 reaches 0, EMERALD_REVEAL begins.

### Continue Icon (loc_2EBE8)

Spawned in POST_TALLY if ≥50 rings. Uses Map_Results mappings.
- Position: x=$17C, y=$14C (VDP coords)
- Frame: $29 + (Player_mode - 1), clamped to 0 minimum
  - Sonic: $29, Tails: $2A, Knuckles: $2B
- Blinks: drawn only when `(Level_frame_counter >> 3) & 1 != 0` (visible every 8 frames, hidden every 8)
- Plays sfx_Continue when spawned

## Art Loading

Called once during INIT. All art decompressed from ROM and cached as patterns.

| Asset | ROM Addr | VRAM Dest | Compression |
|-------|----------|-----------|-------------|
| ArtKosM_ResultsGeneral | Sonic3kConstants | $5B8 | KosinskiM |
| ArtKosM_ResultsSONIC/TAILS/MILES/KNUCKLES | Sonic3kConstants | $4F1 | KosinskiM |
| ArtKosM_SSResultsSUPER[k] | Sonic3kConstants | $50F | KosinskiM |
| ArtKosM_SSResults | Sonic3kConstants | $523 | KosinskiM |
| ArtNem_RingHUDText | Sonic3kConstants | $6BC (ArtTile_Ring) | Nemesis |
| Pal_Results | Sonic3kConstants.PAL_RESULTS_ADDR | Full palette | Raw 128 bytes |

**New ROM constants needed** (find via RomOffsetFinder):
- `ART_KOSM_SS_RESULTS_ADDR` — ArtKosM_SSResults
- `ART_KOSM_SS_RESULTS_SUPER_ADDR` — ArtKosM_SSResultsSUPER
- `ART_KOSM_SS_RESULTS_SUPER_K_ADDR` — ArtKosM_SSResultsSUPERk
- `MAP_RESULTS_ADDR` — already exists from level results

**Existing constants reused**: ART_KOSM_RESULTS_GENERAL_ADDR, ART_KOSM_RESULTS_SONIC_ADDR, ART_KOSM_RESULTS_TAILS_ADDR, ART_KOSM_RESULTS_KNUCKLES_ADDR, PAL_RESULTS_ADDR, MAP_RESULTS_ADDR, ART_NEM_RING_HUD_TEXT_ADDR.

## Audio

| Event | Sound | ROM ID |
|-------|-------|--------|
| On init | Fade out music | cmd_FadeOut |
| Pre-tally frame 71 | mus_GotThroughAct | $29 |
| Tally tick (every 4 frames) | sfx_Switch | $5B |
| Tally complete | sfx_Register | $B0 |
| Continue icon spawn | sfx_Continue | $3E |

## Rendering

- **Background**: White (from Pal_Results backdrop color)
- **Camera**: (0, 0) — set by Engine.java in SPECIAL_STAGE_RESULTS mode
- **Coordinate conversion**: VDP coords - 128 = screen coords = world coords (camera at origin)
- **Mapping frames**: All from Map_Results (same mapping table as level results)
- **Pattern caching**: Same approach as existing level results — high pattern IDs (0x60000+) to avoid conflicts
- **Digit display**: Same LevResults_DisplayScore approach — 7 digits, leading zero suppression, mapping frames 0-10
- **art_tile handling**: Elements use different `art_tile` values to select palette lines. The renderer must apply the correct palette when drawing each element's mapping frame pieces.

## Emerald Collection State

The engine must track which emeralds are collected. ROM uses `Collected_emeralds_array` (7 bytes, 0=uncollected, 1=collected) and `Chaos_emerald_count` (total count).

Check: Does `GameStateManager` already have emerald tracking? If so, use it. If not, add `boolean[] collectedEmeralds` and `int emeraldCount` fields.

## Exit Behavior

When the results screen signals `isComplete()`:
1. GameLoop fades to black via existing `doExitResultsScreen()`
2. Restores `Current_zone_and_act` from `Special_stage_zone_and_act` (ROM line 63231)
3. Returns player to big ring position (already handled by `Sonic3kSSEntryRingObjectInstance.saveBigRingReturnPosition()`)

The results screen itself just sets `complete = true`. All transition logic is in GameLoop.

## Files Changed

| File | Change |
|------|--------|
| `S3kSpecialStageResultsScreen.java` | **New** — full results screen implementation |
| `Sonic3kSpecialStageProvider.java` | Wire `createResultsScreen()` to new class |
| `Sonic3kConstants.java` | Add SS results ROM address constants |
| `Sonic3kObjectArt.java` | Add `loadSSResultsArt()` method |
| `Engine.java` | Set GL clear color to white for SS results mode |

## Files Untouched

- `AbstractResultsScreen.java` — not extended
- `GameLoop.java` — existing lifecycle works as-is
- `S3kResultsScreenObjectInstance.java` — level results, separate feature
- `Sonic3kSpecialStageManager.java` — already tracks rings/emeralds

## Testing

- Unit test for bonus calculation (ring bonus = rings×10, time bonus = 5000 if perfect)
- Unit test for emerald indicator visibility logic
- Unit test for continue icon threshold (≥50 rings)
- Unit test for state machine transitions
