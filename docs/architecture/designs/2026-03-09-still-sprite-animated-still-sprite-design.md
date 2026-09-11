# StillSprite & AnimatedStillSprite Implementation Design

## Overview

Implement the two S3K decorative sprite objects:
- **Obj_StillSprite (ID 0x2F)**: Static sprites (bridge posts, waterfall decorations, tube bends, rails, etc.) across 9 zones with 51 subtypes.
- **Obj_AnimatedStillSprite (ID 0x30)**: Animated sprites (splashing water, flickering torches, rotating sand) across 3 zones with 8 subtypes and 8 animation scripts.

Both use shared mapping tables parsed from ROM. Each subtype specifies an art_tile base (zone-specific), priority, and dimensions from a per-subtype data table.

## Disassembly Reference

### Obj_StillSprite (sonic3k.asm:60199)
- Sets `mapping_frame = subtype`
- Loads art_tile, priority, width, height from `word_2B968` (6 bytes per entry, 51 entries)
- Calls `Sprite_OnScreen_Test` (no animation, no update)
- Uses `Map_StillSprites` (51 mapping frames)

### Obj_AnimatedStillSprite (sonic3k.asm:60377)
- Sets `anim = subtype`
- Loads art_tile, priority, width, height from `word_2BF6C` (6 bytes per entry, 8 entries)
- Calls `Animate_Sprite` with `Ani_AnimatedStillSprites` (8 scripts, 30 mapping frames)
- Then `Sprite_OnScreen_Test`

### S3-only variant
Fewer entries: 24 StillSprite subtypes, 9 AnimatedStillSprite mapping frames, 2 animation scripts. Since we run combined S3&K ROM, we use the full SK tables.

## Architecture

### Approach: Per-zone art sheet groups via PlcArtRegistry

1. Parse mapping tables from ROM (`Map_StillSprites`, `Map_AnimatedStillSprites`)
2. Hardcode the two subtype data tables as static data
3. Group subtypes by `(artTileBase, palette)` — each group = one sprite sheet
4. Register groups per zone in `Sonic3kPlcArtRegistry`
5. Two instance classes resolve their art key from subtype

### Art Sheet Grouping

Different subtypes reference different art_tile bases. Within a zone, subtypes sharing the same base tile index and palette share one sprite sheet. Each sheet contains only the mapping frames used by its subtypes.

**StillSprite subtype → zone mapping:**

| Zone | Subtypes | Art tile bases |
|------|----------|----------------|
| AIZ  | 0-5      | AIZMisc2 (pal 2), $001 (pal 2,3) |
| HCZ  | 6-10, F-13 | $001 (pal 2), HCZ2Slide (pal 2), HCZ2BlockPlat (pal 2) |
| MGZ  | B-E      | MGZSigns (pal 2) |
| LBZ  | 14-17    | LBZMisc (pal 1,2) |
| MHZ  | 18-1E    | MHZMisc (pal 0,2,3) |
| LRZ  | 1F-26    | LRZMisc (pal 1,2), $0D3 (pal 2) |
| FBZ  | 27-2D    | FBZMisc (pal 1,2), FBZMisc2 (pal 1) |
| SOZ  | 2E-2F    | $001 (pal 2), SOZ2Extra (pal 0) |
| DEZ  | 30-32    | DEZMisc (pal 1) |

**AnimatedStillSprite subtype → zone mapping:**

| Zone | Subtypes | Art tile base |
|------|----------|---------------|
| AIZ  | 0-1      | AIZMisc2 (pal 3) — already exists |
| LRZ  | 2-3      | $0D3 (pal 2), LRZ2Misc (pal 1) |
| SOZ  | 4-7      | SOZMisc+$46 (pal 2) |

### Components

1. **`Sonic3kObjectIds`**: `STILL_SPRITE = 0x2F`, `ANIMATED_STILL_SPRITE = 0x30`
2. **`Sonic3kObjectArtKeys`**: Per-zone-group keys
3. **`Sonic3kConstants`**: `MAP_STILL_SPRITES_ADDR`, `MAP_ANIMATED_STILL_SPRITES_ADDR` (via RomOffsetFinder)
4. **`Sonic3kObjectArt`**: Builders per art_tile group (shared mapping subset + level patterns)
5. **`Sonic3kPlcArtRegistry`**: Register groups per zone
6. **`StillSpriteInstance`**: Static subtype data, resolves art key, renders single frame
7. **`AnimatedStillSpriteInstance`**: Static subtype data + animation scripts, ObjectAnimationState
8. **`Sonic3kObjectRegistry`**: Register factories for both IDs

### Instance Class Design

**StillSpriteInstance:**
```
record SubtypeEntry(int artTileRaw, int priority, int widthPixels, int heightPixels)
static SubtypeEntry[] SUBTYPE_TABLE = { ... }  // 51 entries from word_2B968

- artTileBase = (artTileRaw & 0x07FF)
- palette = (artTileRaw >> 13) & 3
- priorityBit = (artTileRaw >> 15) & 1
- mappingFrame = subtype (direct index into mapping table)
- artKey = resolved from static lookup (subtype → zone-group key)
```

**AnimatedStillSpriteInstance:**
```
record SubtypeEntry(int artTileRaw, int priority, int widthPixels, int heightPixels)
static SubtypeEntry[] SUBTYPE_TABLE = { ... }  // 8 entries from word_2BF6C

- animIndex = subtype
- ObjectAnimationState with SpriteAnimationSet (8 scripts)
- update(): tick animation state
- appendRenderCommands(): render animationState.getMappingFrame()
```

### Animation Scripts (Ani_AnimatedStillSprites)

| Script | Delay | Frames | Notes |
|--------|-------|--------|-------|
| 0 | 3 | 0,1,2,3,4 | AIZ waterfall splash |
| 1 | 3 | 5,6,7,8 | AIZ waterfall splash variant |
| 2 | 7 | 9,10 | LRZ ceiling rock flicker |
| 3 | 4 | 11,12,13 | LRZ2 torch flame |
| 4 | 7 | 14,15,16,17 | SOZ torch 1-wide |
| 5 | 7 | 18,19,20,21 | SOZ torch 2-wide |
| 6 | 7 | 22,23,24,25 | SOZ torch 3-wide |
| 7 | 7 | 26,27,28,29 | SOZ torch 4-wide |

All loop (terminator 0xFF).
