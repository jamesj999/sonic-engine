# S3K Bonus Stage Title Card Framework

## Summary

Add "BONUS STAGE" title card display on bonus stage entry, and zone title card display on bonus stage exit, matching the original ROM behavior. This is common functionality for all three S3K bonus stages (Gumball Machine, Glowing Spheres, Slot Machine).

## Problem

The engine currently skips title cards for bonus stages (`consumeTitleCardRequest()` in both `doEnterBonusStage()` and `doExitBonusStage()`). The ROM shows a "BONUS STAGE" title card on entry and the zone's normal title card on exit.

## Disassembly Reference

### Entry Sequence (ROM)

1. Player touches bonus star → sets `Special_bonus_entry_flag = 2`, `Restart_level_flag = 1`
2. `Level:` routine runs → `Pal_FadeToBlack` (22 frames, always black for bonus stages)
3. Level loads (bonus zone $1300/$1400/$1500)
4. `Obj_TitleCard` spawns → loads art, creates child elements
5. Art loading:
   - RedAct → VRAM $500 (shared)
   - S3KZone text → VRAM $510 (shared letter tiles)
   - Act number → VRAM $53D (shared, but not displayed for bonus)
   - `ArtKosM_BonusTitleCard` → VRAM $54D (bonus-specific letters: B, N, U, S, T, A, G)
6. `ObjArray_TtlCardBonus` creates **2 elements** (not 4):
   - Element 1: "BONUS" (frame $13/19), slides from right
   - Element 2: "STAGE" (frame $14/20), slides from further right
7. Elements slide left at 16px/frame over black background
8. 90-frame hold while level palette fades from black (22 frames)
9. Both elements exit simultaneously at 32px/frame (both have priority=1, no stagger)
10. Player control released, gameplay begins

### Exit Sequence (ROM)

1. Exit condition met → restores `Saved_zone_and_act`, sets `Restart_level_flag = 1`
2. `Level:` routine runs → `Pal_FadeToBlack` (22 frames)
3. Original zone reloads
4. Normal title card displays (4 elements: banner, zone name, "ZONE", act number)
5. Palette fades from black during title card hold
6. Title card exits with staggered priorities (1, 3, 5, 7)
7. `Restore_LevelMusic` called during title card wait
8. Player control released, gameplay resumes

### Bonus Title Card Element Positions

Source: `ObjArray_TtlCardBonus` (sonic3k.asm line 62482)

| Element | Frame | Start X (VDP) | Target X (VDP) | Y (VDP) | Priority |
|---------|-------|---------------|----------------|---------|----------|
| "BONUS" | $13 (19) | $188 (264 screen) | $C8 (72 screen) | $E8 (104 screen) | 1 |
| "STAGE" | $14 (20) | $1E8 (360 screen) | $128 (168 screen) | $E8 (104 screen) | 1 |

VDP→screen conversion: subtract 128.

### Mapping Data

Source: `Map - Title Card.asm` lines 204-215

**Frame 19 — "BONUS"** (5 pieces):
```
piece 0: Y=0, size=2x3, tile=$53, X=$00    ; B (bonus art)
piece 1: Y=0, size=3x3, tile=$28, X=$10    ; O (shared)
piece 2: Y=0, size=2x3, tile=$5F, X=$28    ; N (bonus art)
piece 3: Y=0, size=2x3, tile=$71, X=$38    ; U (bonus art)
piece 4: Y=0, size=2x3, tile=$65, X=$48    ; S (bonus art)
```

**Frame 20 — "STAGE"** (5 pieces):
```
piece 0: Y=0, size=2x3, tile=$65, X=$00    ; S (bonus art)
piece 1: Y=0, size=2x3, tile=$6B, X=$10    ; T (bonus art)
piece 2: Y=0, size=2x3, tile=$4D, X=$20    ; A (bonus art)
piece 3: Y=0, size=2x3, tile=$59, X=$30    ; G (bonus art)
piece 4: Y=0, size=2x3, tile=$1C, X=$40    ; E (shared)
```

Frame 18 ("Competition") shares the same data as frame 19 ("Bonus").

### Art Address

`ArtKosM_BonusTitleCard` at ROM **0x0D726C** (verified by binary match). KosinskiM compressed, 354 bytes compressed → 1344 bytes decompressed (42 tiles). Loaded to VRAM $54D.

## Design

### Approach

Add a "bonus mode" to the existing `Sonic3kTitleCardManager` that switches between the 4-element zone layout and the 2-element bonus layout. This mirrors the ROM which uses the same `Obj_TitleCard` / `Obj_TitleCardElement` code for both — only the `ObjArray` differs.

Integrate via a "post-title-card destination" concept in `GameLoop`: after the title card completes, transition to either `BONUS_STAGE` or `LEVEL` depending on context.

### Component Changes

#### 1. Sonic3kTitleCardMappings

Add three new frame constants and piece arrays:

```
FRAME_COMPETITION = 18  (same data as BONUS)
FRAME_BONUS = 19        (5 pieces: "BONUS")
FRAME_STAGE = 20        (5 pieces: "STAGE")
```

Piece data transcribed directly from `Map - Title Card.asm` lines 204-215.

#### 2. Sonic3kConstants

Add constant:
```java
public static final int ART_KOSM_BONUS_TITLE_CARD_ADDR = 0x0D726C;
```

#### 3. Sonic3kTitleCardManager

**New method:** `initializeBonus()` — sets bonus mode with 2 horizontal elements.

**Bonus mode differences from normal mode:**
- Element count: 2 (not 4)
- No vertical banner element
- Element 0 = "BONUS" (frame 19), Element 1 = "STAGE" (frame 20)
- Both elements horizontal, same Y position
- Both exit priority = 1 (exit simultaneously)
- Art loading: `ArtKosM_BonusTitleCard` to VRAM $54D instead of zone-specific art
- Act number art still loaded (shared blocks) but not displayed

**Unchanged behavior:**
- Black background during SLIDE_IN and DISPLAY
- Slide speed: 16px/frame in, 32px/frame out
- Hold duration: 90 frames
- State machine: SLIDE_IN → DISPLAY → EXIT → COMPLETE
- `shouldRunPlayerPhysics()` returns false

**Implementation:** Add `bonusMode` boolean field. In the state machine methods (`updateSlideIn`, `updateExit`, `draw`), conditionally use 2-element or 4-element layout based on `bonusMode`. In `loadAllArt()`, load bonus art when `bonusMode` is true.

#### 4. GameLoop — Post-Title-Card Destination

**New enum:** `PostTitleCardDestination { LEVEL, BONUS_STAGE }`

**New field:** `postTitleCardDestination` (defaults to `LEVEL`)

**Entry flow (`doEnterBonusStage`):**
1. Load bonus zone via `loadZoneAndAct()` (existing)
2. `consumeTitleCardRequest()` to eat the default zone card request that `loadZoneAndAct` generates
3. Call `titleCardManager.initializeBonus()` to set up the 2-element bonus card
4. Set `postTitleCardDestination = BONUS_STAGE`
5. Set `currentGameMode = TITLE_CARD`
6. `fadeManager.startFadeFromBlack(null)` — level + title card fade in together

**`exitTitleCard()` changes:**
When `postTitleCardDestination == BONUS_STAGE`:
- Apply bonus-stage-specific setup (moved from current `doEnterBonusStage`):
  - Pause HUD timer
  - Restore ring count from saved state
  - Set player high priority
  - Play bonus stage music
  - Set `currentGameMode = BONUS_STAGE`
- Reset `postTitleCardDestination = LEVEL`

When `postTitleCardDestination == LEVEL`:
- Existing behavior (transition to `LEVEL` mode)

**Exit flow (`doExitBonusStage`):**
1. Load original zone via `loadZoneAndAct()` (existing)
2. Restore player position, camera, event routines, rings, shields (existing)
3. Consume the title card request generated by `loadZoneAndAct()`, capture zone/act
4. Initialize normal zone title card via `titleCardManager.initialize(zone, act)`
5. Set `postTitleCardDestination = LEVEL`
6. Set `currentGameMode = TITLE_CARD`
7. `fadeManager.startFadeFromBlack(null)`
8. Play zone music during title card (matching ROM `Restore_LevelMusic` timing)

### Timing Summary

**Entry:**
```
Frame 0-21:   Fade to black (existing, before doEnterBonusStage callback)
              Load bonus zone
Frame 22+:    Bonus title card SLIDE_IN (2 elements slide from right, ~12 frames)
              Fade from black begins (22 frames, level appears behind card)
              DISPLAY hold (90 frames)
              EXIT (both elements slide right at 32px/frame, ~5 frames)
              → BONUS_STAGE mode, player control released
```

**Exit:**
```
Frame 0-21:   Fade to black (existing, before doExitBonusStage callback)
              Reload original zone, restore state
Frame 22+:    Zone title card SLIDE_IN (4 elements)
              Fade from black (22 frames)
              DISPLAY hold (90 frames)
              EXIT (staggered by priority 1,3,5,7)
              → LEVEL mode, player control released
```

### Fade Details

- All bonus stages use fade to **black** (not white). White fade is only for zone $1701 (Death Egg boss arena).
- Fade duration: 22 V-syncs (matching `Pal_FadeToBlack` / `Pal_FadeFromBlack`).
- The existing `FadeManager` 21-frame fade matches this closely.

### Files Modified

| File | Change |
|------|--------|
| `Sonic3kTitleCardMappings.java` | Add FRAME_COMPETITION/BONUS/STAGE constants + piece arrays |
| `Sonic3kTitleCardManager.java` | Add `bonusMode`, `initializeBonus()`, conditional element layout, bonus art loading |
| `Sonic3kConstants.java` | Add `ART_KOSM_BONUS_TITLE_CARD_ADDR` |
| `GameLoop.java` | Add `PostTitleCardDestination`, modify `doEnterBonusStage`, `doExitBonusStage`, `exitTitleCard` |
| `TitleCardProvider.java` | Add `initializeBonus()` default method (no-op for S1/S2) |

### Testing

- Visual verification against ROM footage: bonus entry shows "BONUS STAGE" text sliding in over black, fading to level, sliding out
- Visual verification: bonus exit shows zone title card
- Existing title card tests should remain green (no regression for normal zone cards)
- Verify all three bonus stage types (Gumball, Glowing Spheres, Slot Machine) show the same entry card
