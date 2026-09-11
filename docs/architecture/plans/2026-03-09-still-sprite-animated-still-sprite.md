# StillSprite & AnimatedStillSprite Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement the two S3K decorative sprite objects: StillSprite (0x2F, static, 51 subtypes) and AnimatedStillSprite (0x30, animated, 8 subtypes) across all zones.

**Architecture:** Both objects use shared mapping tables parsed from ROM. Each subtype references zone-specific level patterns via an art_tile base from a per-subtype data table. Art sheets are grouped by (artTileBase, palette) and registered per zone in Sonic3kPlcArtRegistry. Animation uses inline Animate_Sprite logic.

**Tech Stack:** Java 21, S3K ROM data parsing, existing level-art sprite sheet system

---

### Task 1: Add ROM constants

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`

**Step 1: Add ArtTile and mapping table constants**

Add these verified constants (ArtTile values from sonic3k.constants.asm, ROM addresses verified via search-rom):

```java
// ===== StillSprite / AnimatedStillSprite =====
// Mapping tables (ROM addresses verified via binary search)
public static final int MAP_STILL_SPRITES_ADDR = 0x02BA9A;
public static final int MAP_ANIMATED_STILL_SPRITES_ADDR = 0x02BFDA;

// ArtTile constants for zones not yet defined
public static final int ARTTILE_HCZ2_SLIDE = 0x035C;
public static final int ARTTILE_HCZ2_BLOCK_PLAT = 0x0028;
public static final int ARTTILE_MGZ_SIGNS = 0x0451;
public static final int ARTTILE_LBZ_MISC = 0x03C3;
public static final int ARTTILE_MHZ_MISC = 0x0347;
public static final int ARTTILE_FBZ_MISC = 0x0379;
public static final int ARTTILE_FBZ_MISC2 = 0x02D2;
public static final int ARTTILE_SOZ_MISC = 0x03C9;
public static final int ARTTILE_SOZ2_EXTRA = 0x03AF;
public static final int ARTTILE_LRZ_MISC = 0x03A1;
public static final int ARTTILE_DEZ_MISC = 0x034D;
```

**Step 2: Verify the mapping addresses work**

Run: `mvn test -Dtest=TestSonic3kLevelLoading -q`
Expected: PASS (constants are just static fields, no runtime impact)

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java
git commit -m "feat(s3k): add ROM constants for StillSprite/AnimatedStillSprite mapping tables and zone ArtTiles"
```

---

### Task 2: Add object IDs and art keys

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java`

**Step 1: Add object ID constants**

In `Sonic3kObjectIds.java`:
```java
public static final int STILL_SPRITE = 0x2F;
public static final int ANIMATED_STILL_SPRITE = 0x30;
```

**Step 2: Add art keys for per-zone sprite sheet groups**

In `Sonic3kObjectArtKeys.java`:
```java
// StillSprite per-zone art sheet keys
public static final String STILL_SPRITE_AIZ_MISC2 = "still_aiz_misc2";
public static final String STILL_SPRITE_AIZ_001 = "still_aiz_001";
public static final String STILL_SPRITE_HCZ_001 = "still_hcz_001";
public static final String STILL_SPRITE_HCZ_SLIDE = "still_hcz_slide";
public static final String STILL_SPRITE_HCZ_BLOCKPLAT = "still_hcz_blockplat";
public static final String STILL_SPRITE_MGZ = "still_mgz";
public static final String STILL_SPRITE_LBZ = "still_lbz";
public static final String STILL_SPRITE_MHZ = "still_mhz";
public static final String STILL_SPRITE_LRZ_MISC = "still_lrz_misc";
public static final String STILL_SPRITE_LRZ_D3 = "still_lrz_d3";
public static final String STILL_SPRITE_FBZ_MISC = "still_fbz_misc";
public static final String STILL_SPRITE_FBZ_MISC2 = "still_fbz_misc2";
public static final String STILL_SPRITE_SOZ_001 = "still_soz_001";
public static final String STILL_SPRITE_SOZ2_EXTRA = "still_soz2_extra";
public static final String STILL_SPRITE_DEZ = "still_dez";

// AnimatedStillSprite per-zone art sheet keys
// (AIZ already has ANIMATED_STILL_SPRITES)
public static final String ANIM_STILL_LRZ_D3 = "anim_still_lrz_d3";
public static final String ANIM_STILL_LRZ2 = "anim_still_lrz2";
public static final String ANIM_STILL_SOZ = "anim_still_soz";
```

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kObjectIds.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtKeys.java
git commit -m "feat(s3k): add StillSprite/AnimatedStillSprite object IDs and per-zone art keys"
```

---

### Task 3: Build StillSprite art sheets from ROM mappings

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java` (add builder dispatch)

**Step 1: Add StillSprite sheet builder**

In `Sonic3kObjectArt.java`, add a method that builds a sprite sheet containing only the mapping frames for a given set of subtypes:

```java
/**
 * Builds a StillSprite sheet for a subset of subtypes sharing the same artTileBase.
 * Parses Map_StillSprites from ROM and selects only the specified frame indices.
 *
 * @param artTileBase  VRAM tile base for this subtype group
 * @param palette      palette line (0-3)
 * @param frameIndices which mapping frames (= subtype indices) to include
 */
public ObjectSpriteSheet buildStillSpriteSheet(int artTileBase, int palette,
        int... frameIndices) {
    if (reader == null) return null;
    List<SpriteMappingFrame> allFrames = S3kSpriteDataLoader.loadMappingFrames(
            reader, Sonic3kConstants.MAP_STILL_SPRITES_ADDR);
    if (allFrames.isEmpty()) return null;

    List<SpriteMappingFrame> selected = new ArrayList<>(frameIndices.length);
    for (int idx : frameIndices) {
        if (idx >= 0 && idx < allFrames.size()) {
            selected.add(allFrames.get(idx));
        }
    }
    if (selected.isEmpty()) return null;

    int minTile = Integer.MAX_VALUE;
    int maxTile = Integer.MIN_VALUE;
    for (SpriteMappingFrame frame : selected) {
        for (SpriteMappingPiece piece : frame.pieces()) {
            minTile = Math.min(minTile, piece.tileIndex());
            int pieceTiles = piece.widthTiles() * piece.heightTiles();
            maxTile = Math.max(maxTile, piece.tileIndex() + pieceTiles);
        }
    }
    if (minTile == Integer.MAX_VALUE) return null;

    return buildLevelArtSheet(artTileBase, palette, selected, minTile, maxTile);
}
```

**Step 2: Add AnimatedStillSprite sheet builders for LRZ and SOZ**

```java
/**
 * Builds AnimatedStillSprite sheet for LRZ subtype 2 (ceiling rock flicker).
 * art_tile = $0D3, palette 2. Animation frames 9-10.
 */
public ObjectSpriteSheet buildAnimStillLrzD3Sheet() {
    return buildAnimStillSheet(0x00D3, 2, 9, 10);
}

/**
 * Builds AnimatedStillSprite sheet for LRZ2 subtype 3 (torch flame).
 * art_tile = LRZ2Misc ($040D), palette 1. Animation frames 11-13.
 */
public ObjectSpriteSheet buildAnimStillLrz2Sheet() {
    return buildAnimStillSheet(Sonic3kConstants.ARTTILE_LRZ2_MISC, 1, 11, 13);
}

/**
 * Builds AnimatedStillSprite sheet for SOZ subtypes 4-7 (torches).
 * art_tile = SOZMisc+$46, palette 2. Animation frames 14-29.
 */
public ObjectSpriteSheet buildAnimStillSozSheet() {
    return buildAnimStillSheet(Sonic3kConstants.ARTTILE_SOZ_MISC + 0x46, 2, 14, 29);
}

private ObjectSpriteSheet buildAnimStillSheet(int artTileBase, int palette,
        int firstFrame, int lastFrame) {
    if (reader == null) return null;
    List<SpriteMappingFrame> allFrames = S3kSpriteDataLoader.loadMappingFrames(
            reader, Sonic3kConstants.MAP_ANIMATED_STILL_SPRITES_ADDR);
    if (allFrames.isEmpty()) return null;

    List<SpriteMappingFrame> selected = new ArrayList<>();
    for (int i = firstFrame; i <= lastFrame && i < allFrames.size(); i++) {
        selected.add(allFrames.get(i));
    }
    if (selected.isEmpty()) return null;

    int minTile = Integer.MAX_VALUE;
    int maxTile = Integer.MIN_VALUE;
    for (SpriteMappingFrame frame : selected) {
        for (SpriteMappingPiece piece : frame.pieces()) {
            minTile = Math.min(minTile, piece.tileIndex());
            int pieceTiles = piece.widthTiles() * piece.heightTiles();
            maxTile = Math.max(maxTile, piece.tileIndex() + pieceTiles);
        }
    }
    if (minTile == Integer.MAX_VALUE) return null;

    return buildLevelArtSheet(artTileBase, palette, selected, minTile, maxTile);
}
```

**Step 3: Add builder dispatch entries in Sonic3kObjectArtProvider.invokeBuilder()**

Add new cases to the switch in `invokeBuilder()`:
```java
case "buildAnimStillLrzD3Sheet" -> art.buildAnimStillLrzD3Sheet();
case "buildAnimStillLrz2Sheet" -> art.buildAnimStillLrz2Sheet();
case "buildAnimStillSozSheet" -> art.buildAnimStillSozSheet();
```

Note: StillSprite sheets are built via a different path (not invokeBuilder) — they use buildStillSpriteSheet() called from a new method. See Task 4.

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java
git commit -m "feat(s3k): add StillSprite/AnimatedStillSprite ROM-parsed art sheet builders"
```

---

### Task 4: Register art sheets per zone in PlcArtRegistry

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`

**Step 1: Add StillSprite level-art entries per zone**

For StillSprite sheets, we need a new builder dispatch path since `buildStillSpriteSheet` takes dynamic parameters. The cleanest approach: add dedicated builder methods in Sonic3kObjectArt per zone group, then register them like existing level-art entries.

Add to `Sonic3kObjectArt.java` — one builder per zone group:
```java
// StillSprite zone builders — each calls buildStillSpriteSheet with the subtypes for that zone
public ObjectSpriteSheet buildStillSpriteAizMisc2() {
    return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_AIZ_MISC2, 2, 0, 1, 2, 5);
}
public ObjectSpriteSheet buildStillSpriteAiz001() {
    return buildStillSpriteSheet(1, 2, 3, 4, 8); // subtypes 3($001 pal2), 4($001 pal3), 8($001 pal2)
    // Note: subtype 4 uses palette 3, but shares tile base $001 — palette override at render time
}
// (Continue for all zone groups — see design doc for full subtype/zone mapping)
```

Actually, a cleaner approach: since many zones only need the ROM-parsed mapping at a specific art_tile base, add a new `LevelArtEntry` variant that specifies the mapping address directly along with which frames to include. But the current `LevelArtEntry` record doesn't support frame filtering.

**Simplest approach:** Add builder methods per zone group, one per unique (artTileBase, palette) combination, and register each in `invokeBuilder` dispatch. There are ~15 groups total.

Add all these builder methods to `Sonic3kObjectArt.java`:
```java
// AIZ
public ObjectSpriteSheet buildStillSpriteAizMisc2() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_AIZ_MISC2, 2, 0, 1, 2, 5); }
public ObjectSpriteSheet buildStillSpriteAiz001() { return buildStillSpriteSheet(1, 2, 3, 8); }
public ObjectSpriteSheet buildStillSpriteAiz001Pal3() { return buildStillSpriteSheet(1, 3, 4); }
// HCZ
public ObjectSpriteSheet buildStillSpriteHcz001() { return buildStillSpriteSheet(1, 2, 6, 7, 8, 9, 10); }
public ObjectSpriteSheet buildStillSpriteHczSlide() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_HCZ2_SLIDE, 2, 0x0F, 0x10, 0x11, 0x12); }
public ObjectSpriteSheet buildStillSpriteHczBlockPlat() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_HCZ2_BLOCK_PLAT, 2, 0x13); }
// MGZ
public ObjectSpriteSheet buildStillSpriteMgz() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_MGZ_SIGNS, 2, 0x0B, 0x0C, 0x0D, 0x0E); }
// LBZ
public ObjectSpriteSheet buildStillSpriteLbz() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_LBZ_MISC, 1, 0x14, 0x15, 0x16, 0x17); }
// MHZ
public ObjectSpriteSheet buildStillSpriteMhz() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_MHZ_MISC, 2, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E); }
// LRZ
public ObjectSpriteSheet buildStillSpriteLrzMisc() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_LRZ_MISC, 1, 0x1F, 0x20, 0x21, 0x23, 0x24, 0x25, 0x26); }
public ObjectSpriteSheet buildStillSpriteLrzD3() { return buildStillSpriteSheet(0x00D3, 2, 0x22); }
// FBZ
public ObjectSpriteSheet buildStillSpriteFbzMisc() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_FBZ_MISC, 2, 0x27, 0x28, 0x29, 0x2A); }
public ObjectSpriteSheet buildStillSpriteFbzMisc1() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_FBZ_MISC, 1, 0x2B); }
public ObjectSpriteSheet buildStillSpriteFbzMisc2() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_FBZ_MISC2, 1, 0x2C, 0x2D); }
// SOZ
public ObjectSpriteSheet buildStillSpriteSoz001() { return buildStillSpriteSheet(1, 2, 0x2E); }
public ObjectSpriteSheet buildStillSpriteSoz2Extra() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_SOZ2_EXTRA, 0, 0x2F); }
// DEZ
public ObjectSpriteSheet buildStillSpriteDez() { return buildStillSpriteSheet(Sonic3kConstants.ARTTILE_DEZ_MISC, 1, 0x30, 0x31, 0x32); }
```

**Step 2: Add all builder dispatch cases to invokeBuilder()**

In `Sonic3kObjectArtProvider.invokeBuilder()`:
```java
case "buildStillSpriteAizMisc2" -> art.buildStillSpriteAizMisc2();
case "buildStillSpriteAiz001" -> art.buildStillSpriteAiz001();
case "buildStillSpriteAiz001Pal3" -> art.buildStillSpriteAiz001Pal3();
case "buildStillSpriteHcz001" -> art.buildStillSpriteHcz001();
case "buildStillSpriteHczSlide" -> art.buildStillSpriteHczSlide();
case "buildStillSpriteHczBlockPlat" -> art.buildStillSpriteHczBlockPlat();
case "buildStillSpriteMgz" -> art.buildStillSpriteMgz();
case "buildStillSpriteLbz" -> art.buildStillSpriteLbz();
case "buildStillSpriteMhz" -> art.buildStillSpriteMhz();
case "buildStillSpriteLrzMisc" -> art.buildStillSpriteLrzMisc();
case "buildStillSpriteLrzD3" -> art.buildStillSpriteLrzD3();
case "buildStillSpriteFbzMisc" -> art.buildStillSpriteFbzMisc();
case "buildStillSpriteFbzMisc1" -> art.buildStillSpriteFbzMisc1();
case "buildStillSpriteFbzMisc2" -> art.buildStillSpriteFbzMisc2();
case "buildStillSpriteSoz001" -> art.buildStillSpriteSoz001();
case "buildStillSpriteSoz2Extra" -> art.buildStillSpriteSoz2Extra();
case "buildStillSpriteDez" -> art.buildStillSpriteDez();
case "buildAnimStillLrzD3Sheet" -> art.buildAnimStillLrzD3Sheet();
case "buildAnimStillLrz2Sheet" -> art.buildAnimStillLrz2Sheet();
case "buildAnimStillSozSheet" -> art.buildAnimStillSozSheet();
```

**Step 3: Register LevelArtEntry records per zone in Sonic3kPlcArtRegistry**

In each `add*Entries` method, add the appropriate LevelArtEntry records. Example for AIZ:

```java
// In addAizEntries():
levelArt.add(new LevelArtEntry(Sonic3kObjectArtKeys.STILL_SPRITE_AIZ_MISC2, -1,
        Sonic3kConstants.ARTTILE_AIZ_MISC2, 2, "buildStillSpriteAizMisc2"));
levelArt.add(new LevelArtEntry(Sonic3kObjectArtKeys.STILL_SPRITE_AIZ_001, -1,
        1, 2, "buildStillSpriteAiz001"));
```

Do this for ALL zones: HCZ, MGZ, LBZ, MHZ, LRZ (+ anim_still entries), FBZ, SOZ (+ anim_still), DEZ.

**Step 4: Build and test**

Run: `mvn test -Dtest=TestSonic3kLevelLoading -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArt.java src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java src/main/java/com/openggf/game/sonic3k/Sonic3kPlcArtRegistry.java
git commit -m "feat(s3k): register StillSprite/AnimatedStillSprite art sheets per zone in PlcArtRegistry"
```

---

### Task 5: Implement StillSpriteInstance

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/StillSpriteInstance.java`

**Step 1: Create the instance class**

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x2F - StillSprite.
 * Static decorative sprites used across all zones (bridge posts, waterfalls,
 * tube bends, rails, signposts, etc.).
 *
 * ROM reference: sonic3k.asm lines 60199-60374 (Obj_StillSprite + word_2B968)
 *
 * Subtype directly selects the mapping frame and data table entry.
 * Each entry provides art_tile, priority, width, height.
 * No animation - just Sprite_OnScreen_Test.
 */
public class StillSpriteInstance extends AbstractObjectInstance {

    // Data table from word_2B968: art_tile(16), priority(16), width(8), height(8)
    // art_tile = make_art_tile(tileBase, palette, priority)
    // Format: { artTileRaw, priority, widthPixels, heightPixels }
    private static final int[][] SUBTYPE_TABLE = {
        // Subtype  Description                        art_tile   pri    w    h
        /*  0 */ { 0x42E9, 0x0300, 0x0C, 0x0C },  // AIZ2 Bridge Post
        /*  1 */ { 0x42E9, 0x0300, 0x10, 0x08 },  // AIZ2 Large Rope Twist Tie
        /*  2 */ { 0x42E9, 0x0300, 0x08, 0x04 },  // AIZ2 Rope Twist Tie
        /*  3 */ { 0x4001, 0x0300, 0x08, 0x08 },  // AIZ2 Tie Top Sprite
        /*  4 */ { 0x6001, 0x0300, 0x08, 0x20 },  // AIZ2 Waterfall
        /*  5 */ { 0xC2E9, 0x0300, 0x0C, 0x0C },  // AIZ2 Bridge Post (priority)
        /*  6 */ { 0xC001, 0x0000, 0x40, 0x40 },  // HCZ 128x128 Waterfall
        /*  7 */ { 0xC001, 0x0000, 0x40, 0x20 },  // HCZ 128x64 Waterfall
        /*  8 */ { 0x4001, 0x0300, 0x40, 0x10 },  // HCZ 128x32 Waterfall
        /*  9 */ { 0xC001, 0x0000, 0x40, 0x40 },  // HCZ Stagger Down Waterfall
        /* 10 */ { 0xC001, 0x0000, 0x40, 0x60 },  // HCZ Stagger Up Waterfall
        /* 11 */ { 0x4451, 0x0300, 0x10, 0x18 },  // MGZ Signpost Left
        /* 12 */ { 0x4451, 0x0300, 0x10, 0x18 },  // MGZ Signpost Right
        /* 13 */ { 0x4451, 0x0300, 0x10, 0x18 },  // MGZ Signpost Up
        /* 14 */ { 0x4451, 0x0300, 0x10, 0x18 },  // MGZ Signpost Down
        /* 15 */ { 0xC36E, 0x0000, 0x08, 0x30 },  // HCZ2 Tube Bend 1
        /* 16 */ { 0xC37F, 0x0000, 0x30, 0x18 },  // HCZ2 Tube Bend 2
        /* 17 */ { 0xC39F, 0x0000, 0x0C, 0x10 },  // HCZ2 Tube Bend 3
        /* 18 */ { 0xC3AA, 0x0000, 0x20, 0x34 },  // HCZ2 Tube Crossover
        /* 19 */ { 0x4048, 0x0300, 0x04, 0x10 },  // HCZ2 Bridge Post
        /* 20 */ { 0x440D, 0x0300, 0x08, 0x08 },  // LBZ Cup Elevator Pole Top
        /* 21 */ { 0x2433, 0x0300, 0x10, 0x40 },  // LBZ Steel Girder Low Priority
        /* 22 */ { 0x2433, 0x0300, 0x10, 0x80 },  // LBZ Large Steel Girder
        /* 23 */ { 0x2433, 0x0080, 0x10, 0x40 },  // LBZ Steel Girder High Priority
        /* 24 */ { 0xC357, 0x0080, 0x04, 0x10 },  // MHZ Cliff Edge
        /* 25 */ { 0xC357, 0x0080, 0x04, 0x10 },  // MHZ Cliff Edge 2
        /* 26 */ { 0xC357, 0x0080, 0x10, 0x04 },  // MHZ Grass
        /* 27 */ { 0x740E, 0x0080, 0x10, 0x08 },  // MHZ Wood Column Bottom
        /* 28 */ { 0x740E, 0x0080, 0x10, 0x08 },  // MHZ Wood Column Top
        /* 29 */ { 0x441E, 0x0200, 0x10, 0x08 },  // MHZ Parachute Vines
        /* 30 */ { 0x0347, 0x0280, 0x08, 0x08 },  // Diagonal Spring Pedestal
        /* 31 */ { 0xC3A1, 0x0180, 0x10, 0x04 },  // LRZ Horizontal Gear Rail Small
        /* 32 */ { 0xC3A1, 0x0180, 0x20, 0x04 },  // LRZ Horizontal Gear Rail Medium
        /* 33 */ { 0xC3A1, 0x0180, 0x30, 0x04 },  // LRZ Horizontal Gear Rail Large
        /* 34 */ { 0xC0D3, 0x0080, 0x10, 0x10 },  // LRZ Foreground Rock Ceiling
        /* 35 */ { 0x23A1, 0x0180, 0x04, 0x10 },  // LRZ Gear Rail Top
        /* 36 */ { 0x23A1, 0x0180, 0x04, 0x20 },  // LRZ Gear Rail Small
        /* 37 */ { 0x23A1, 0x0180, 0x04, 0x40 },  // LRZ Gear Rail Large
        /* 38 */ { 0x23A1, 0x0180, 0x04, 0x10 },  // LRZ Gear Rail Bottom
        /* 39 */ { 0x4379, 0x0080, 0x08, 0x14 },  // FBZ Single Metal Hanger
        /* 40 */ { 0x4379, 0x0080, 0x18, 0x14 },  // FBZ Two Metal Hangers
        /* 41 */ { 0x4379, 0x0080, 0x28, 0x14 },  // FBZ Three Metal Hangers
        /* 42 */ { 0x4379, 0x0080, 0x38, 0x14 },  // FBZ Four Metal Hangers
        /* 43 */ { 0x228D, 0x0300, 0x04, 0x10 },  // FBZ Unknown
        /* 44 */ { 0x2339, 0x0000, 0x40, 0x04 },  // FBZ2 Spider Rail
        /* 45 */ { 0x2339, 0x0280, 0x40, 0x04 },  // FBZ2 Spider Rail Low Priority
        /* 46 */ { 0xC001, 0x0100, 0x10, 0x08 },  // SOZ Indoor Sloped Edge
        /* 47 */ { 0x03AF, 0x0000, 0x10, 0x04 },  // SOZ2 Sand Cork Holder
        /* 48 */ { 0x23FF, 0x0280, 0x08, 0x0C },  // DEZ Horizontal Beam Shooter
        /* 49 */ { 0x23FF, 0x0280, 0x0C, 0x08 },  // DEZ Vertical Beam Shooter
        /* 50 */ { 0x2385, 0x0080, 0x10, 0x24 },  // DEZ Light Tunnel Post
    };

    /**
     * Maps subtype to art key.
     * Based on which artTileBase group each subtype belongs to.
     */
    static String artKeyForSubtype(int subtype) {
        return switch (subtype) {
            case 0, 1, 2, 5 -> Sonic3kObjectArtKeys.STILL_SPRITE_AIZ_MISC2;
            case 3, 8 -> Sonic3kObjectArtKeys.STILL_SPRITE_AIZ_001;
            case 4 -> Sonic3kObjectArtKeys.STILL_SPRITE_AIZ_001; // pal 3 but shares base
            case 6, 7, 9, 10 -> Sonic3kObjectArtKeys.STILL_SPRITE_HCZ_001;
            case 0x0B, 0x0C, 0x0D, 0x0E -> Sonic3kObjectArtKeys.STILL_SPRITE_MGZ;
            case 0x0F, 0x10, 0x11, 0x12 -> Sonic3kObjectArtKeys.STILL_SPRITE_HCZ_SLIDE;
            case 0x13 -> Sonic3kObjectArtKeys.STILL_SPRITE_HCZ_BLOCKPLAT;
            case 0x14, 0x15, 0x16, 0x17 -> Sonic3kObjectArtKeys.STILL_SPRITE_LBZ;
            case 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> Sonic3kObjectArtKeys.STILL_SPRITE_MHZ;
            case 0x1F, 0x20, 0x21, 0x23, 0x24, 0x25, 0x26 -> Sonic3kObjectArtKeys.STILL_SPRITE_LRZ_MISC;
            case 0x22 -> Sonic3kObjectArtKeys.STILL_SPRITE_LRZ_D3;
            case 0x27, 0x28, 0x29, 0x2A -> Sonic3kObjectArtKeys.STILL_SPRITE_FBZ_MISC;
            case 0x2B -> Sonic3kObjectArtKeys.STILL_SPRITE_FBZ_MISC; // pal 1 but same base
            case 0x2C, 0x2D -> Sonic3kObjectArtKeys.STILL_SPRITE_FBZ_MISC2;
            case 0x2E -> Sonic3kObjectArtKeys.STILL_SPRITE_SOZ_001;
            case 0x2F -> Sonic3kObjectArtKeys.STILL_SPRITE_SOZ2_EXTRA;
            case 0x30, 0x31, 0x32 -> Sonic3kObjectArtKeys.STILL_SPRITE_DEZ;
            default -> null;
        };
    }

    /**
     * Returns the local frame index within the zone group's sprite sheet
     * for a given subtype. Each group's sheet contains only its subtypes'
     * frames in order, so we need to map the global subtype to a local index.
     */
    static int localFrameIndex(int subtype) {
        // Each art key group has its subtypes in order within the sheet
        // The frame index within the group = position among the subtypes in that group
        return switch (subtype) {
            case 0 -> 0; case 1 -> 1; case 2 -> 2; case 5 -> 3;  // AIZ_MISC2
            case 3 -> 0; case 8 -> 1;  // AIZ_001 (subtype 4 handled separately)
            case 4 -> 2;  // AIZ_001 pal 3
            case 6 -> 0; case 7 -> 1; case 9 -> 2; case 10 -> 3;  // HCZ_001
            case 0x0B -> 0; case 0x0C -> 1; case 0x0D -> 2; case 0x0E -> 3;  // MGZ
            case 0x0F -> 0; case 0x10 -> 1; case 0x11 -> 2; case 0x12 -> 3;  // HCZ_SLIDE
            case 0x13 -> 0;  // HCZ_BLOCKPLAT
            case 0x14 -> 0; case 0x15 -> 1; case 0x16 -> 2; case 0x17 -> 3;  // LBZ
            case 0x18 -> 0; case 0x19 -> 1; case 0x1A -> 2; case 0x1B -> 3;
            case 0x1C -> 4; case 0x1D -> 5; case 0x1E -> 6;  // MHZ
            case 0x1F -> 0; case 0x20 -> 1; case 0x21 -> 2;
            case 0x23 -> 3; case 0x24 -> 4; case 0x25 -> 5; case 0x26 -> 6;  // LRZ_MISC
            case 0x22 -> 0;  // LRZ_D3
            case 0x27 -> 0; case 0x28 -> 1; case 0x29 -> 2; case 0x2A -> 3;  // FBZ_MISC
            case 0x2B -> 4;  // FBZ_MISC (pal 1)
            case 0x2C -> 0; case 0x2D -> 1;  // FBZ_MISC2
            case 0x2E -> 0;  // SOZ_001
            case 0x2F -> 0;  // SOZ2_EXTRA
            case 0x30 -> 0; case 0x31 -> 1; case 0x32 -> 2;  // DEZ
            default -> 0;
        };
    }

    private final int subtype;
    private final String artKey;
    private final int localFrame;
    private final int priorityValue;
    private final boolean priorityBit;
    private PlaceholderObjectInstance placeholder;

    public StillSpriteInstance(ObjectSpawn spawn) {
        super(spawn, "StillSprite");
        this.subtype = spawn.subtype() & 0xFF;
        this.artKey = artKeyForSubtype(subtype);

        if (subtype >= 0 && subtype < SUBTYPE_TABLE.length) {
            int artTileRaw = SUBTYPE_TABLE[subtype][0];
            this.priorityValue = SUBTYPE_TABLE[subtype][1];
            this.priorityBit = (artTileRaw & 0x8000) != 0;
        } else {
            this.priorityValue = 0x0300;
            this.priorityBit = false;
        }
        this.localFrame = localFrameIndex(subtype);
    }

    @Override
    public int getPriorityBucket() {
        return priorityValue / 0x80;
    }

    @Override
    public boolean isHighPriority() {
        return priorityBit;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (artKey == null) {
            renderPlaceholder(commands);
            return;
        }
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager != null) {
            PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
            if (renderer != null && renderer.isReady()) {
                boolean hFlip = spawn.hFlip();
                boolean vFlip = spawn.vFlip();
                renderer.drawFrameIndex(localFrame, spawn.x(), spawn.y(), hFlip, vFlip);
                return;
            }
        }
        renderPlaceholder(commands);
    }

    private void renderPlaceholder(List<GLCommand> commands) {
        if (placeholder == null) {
            placeholder = new PlaceholderObjectInstance(spawn, name);
        }
        placeholder.appendRenderCommands(commands);
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/StillSpriteInstance.java
git commit -m "feat(s3k): implement StillSpriteInstance with per-zone art sheet resolution"
```

---

### Task 6: Implement AnimatedStillSpriteInstance

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/AnimatedStillSpriteInstance.java`

**Step 1: Create the instance class**

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x30 - AnimatedStillSprite.
 * Animated decorative sprites: waterfall splashes (AIZ), ceiling rock flicker (LRZ),
 * torch flames (LRZ2, SOZ).
 *
 * ROM reference: sonic3k.asm lines 60377-60427 (Obj_AnimatedStillSprite + word_2BF6C)
 *
 * Subtype selects animation script and data table entry.
 * Uses Animate_Sprite to cycle through mapping frames.
 */
public class AnimatedStillSpriteInstance extends AbstractObjectInstance {

    // Data table from word_2BF6C: art_tile(16), priority(16), width(8), height(8)
    private static final int[][] SUBTYPE_TABLE = {
        /*  0 */ { 0x62E9, 0x0300, 0x08, 0x0C },  // AIZ waterfall splash
        /*  1 */ { 0x62E9, 0x0300, 0x08, 0x0C },  // AIZ waterfall splash variant
        /*  2 */ { 0xC0D3, 0x0200, 0x10, 0x04 },  // LRZ ceiling rock flicker
        /*  3 */ { 0x240D, 0x0300, 0x10, 0x04 },  // LRZ2 torch flame
        /*  4 */ { 0x440F, 0x0300, 0x04, 0x04 },  // SOZ torch 1-wide
        /*  5 */ { 0x440F, 0x0300, 0x14, 0x04 },  // SOZ torch 2-wide
        /*  6 */ { 0x440F, 0x0300, 0x34, 0x04 },  // SOZ torch 3-wide
        /*  7 */ { 0x440F, 0x0300, 0x54, 0x04 },  // SOZ torch 4-wide
    };

    // Animation scripts from Ani_AnimatedStillSprites (sonic3k.asm:60424)
    // All use LOOP end action (0xFF terminator)
    private static final SpriteAnimationSet ANIMATIONS = createAnimations();

    private static SpriteAnimationSet createAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();
        set.addScript(0, new SpriteAnimationScript(3, List.of(0, 1, 2, 3, 4), SpriteAnimationEndAction.LOOP, 0));
        set.addScript(1, new SpriteAnimationScript(3, List.of(5, 6, 7, 8), SpriteAnimationEndAction.LOOP, 0));
        set.addScript(2, new SpriteAnimationScript(7, List.of(9, 10), SpriteAnimationEndAction.LOOP, 0));
        set.addScript(3, new SpriteAnimationScript(4, List.of(11, 12, 13), SpriteAnimationEndAction.LOOP, 0));
        set.addScript(4, new SpriteAnimationScript(7, List.of(14, 15, 16, 17), SpriteAnimationEndAction.LOOP, 0));
        set.addScript(5, new SpriteAnimationScript(7, List.of(18, 19, 20, 21), SpriteAnimationEndAction.LOOP, 0));
        set.addScript(6, new SpriteAnimationScript(7, List.of(22, 23, 24, 25), SpriteAnimationEndAction.LOOP, 0));
        set.addScript(7, new SpriteAnimationScript(7, List.of(26, 27, 28, 29), SpriteAnimationEndAction.LOOP, 0));
        return set;
    }

    // Maps global mapping frame indices (0-29) to local frame indices within
    // each zone group's sprite sheet.
    // AIZ sheet (ANIMATED_STILL_SPRITES): frames 0-8 → local 0-8
    // LRZ_D3 sheet: frames 9-10 → local 0-1
    // LRZ2 sheet: frames 11-13 → local 0-2
    // SOZ sheet: frames 14-29 → local 0-15
    private static int toLocalFrame(int globalFrame, int subtype) {
        return switch (subtype) {
            case 0, 1 -> globalFrame;          // AIZ: frames 0-8 are local 0-8
            case 2 -> globalFrame - 9;          // LRZ: frames 9-10 → local 0-1
            case 3 -> globalFrame - 11;         // LRZ2: frames 11-13 → local 0-2
            case 4, 5, 6, 7 -> globalFrame - 14; // SOZ: frames 14-29 → local 0-15
            default -> globalFrame;
        };
    }

    static String artKeyForSubtype(int subtype) {
        return switch (subtype) {
            case 0, 1 -> Sonic3kObjectArtKeys.ANIMATED_STILL_SPRITES;
            case 2 -> Sonic3kObjectArtKeys.ANIM_STILL_LRZ_D3;
            case 3 -> Sonic3kObjectArtKeys.ANIM_STILL_LRZ2;
            case 4, 5, 6, 7 -> Sonic3kObjectArtKeys.ANIM_STILL_SOZ;
            default -> null;
        };
    }

    private final int subtype;
    private final String artKey;
    private final int priorityValue;
    private final boolean priorityBit;

    // Animate_Sprite state
    private int animId;
    private int animPrevId = -1;
    private int animScriptFrame;
    private int animTimeFrame;
    private int mappingFrame;

    private PlaceholderObjectInstance placeholder;

    public AnimatedStillSpriteInstance(ObjectSpawn spawn) {
        super(spawn, "AnimatedStillSprite");
        this.subtype = spawn.subtype() & 0xFF;
        this.artKey = artKeyForSubtype(subtype);
        this.animId = subtype;

        if (subtype >= 0 && subtype < SUBTYPE_TABLE.length) {
            this.priorityValue = SUBTYPE_TABLE[subtype][1];
            int artTileRaw = SUBTYPE_TABLE[subtype][0];
            this.priorityBit = (artTileRaw & 0x8000) != 0;
        } else {
            this.priorityValue = 0x0300;
            this.priorityBit = false;
        }
    }

    @Override
    public int getPriorityBucket() {
        return priorityValue / 0x80;
    }

    @Override
    public boolean isHighPriority() {
        return priorityBit;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        updateAnimation();
    }

    /**
     * Animate_Sprite implementation matching ROM behavior.
     * ROM: sonic3k.asm line 36157
     */
    private void updateAnimation() {
        SpriteAnimationScript script = ANIMATIONS.getScript(animId);
        if (script == null || script.frames().isEmpty()) return;

        if (animId != animPrevId) {
            animPrevId = animId;
            animScriptFrame = 0;
            animTimeFrame = 0;
        }

        animTimeFrame--;
        if (animTimeFrame >= 0) return;

        animTimeFrame = script.delay();

        if (animScriptFrame >= script.frames().size()) {
            // Handle end action
            if (script.endAction() == SpriteAnimationEndAction.LOOP) {
                animScriptFrame = 0;
            }
        }

        if (animScriptFrame >= 0 && animScriptFrame < script.frames().size()) {
            mappingFrame = script.frames().get(animScriptFrame);
        }
        animScriptFrame++;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (artKey == null) {
            renderPlaceholder(commands);
            return;
        }
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager != null) {
            PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
            if (renderer != null && renderer.isReady()) {
                int localFrame = toLocalFrame(mappingFrame, subtype);
                boolean hFlip = spawn.hFlip();
                boolean vFlip = spawn.vFlip();
                renderer.drawFrameIndex(localFrame, spawn.x(), spawn.y(), hFlip, vFlip);
                return;
            }
        }
        renderPlaceholder(commands);
    }

    private void renderPlaceholder(List<GLCommand> commands) {
        if (placeholder == null) {
            placeholder = new PlaceholderObjectInstance(spawn, name);
        }
        placeholder.appendRenderCommands(commands);
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/AnimatedStillSpriteInstance.java
git commit -m "feat(s3k): implement AnimatedStillSpriteInstance with Animate_Sprite logic"
```

---

### Task 7: Register factories in Sonic3kObjectRegistry

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`

**Step 1: Add factory registrations**

In the `ensureLoaded()` method (or wherever factories are registered), add:

```java
factories.put(Sonic3kObjectIds.STILL_SPRITE,
        (spawn, registry) -> new StillSpriteInstance(spawn));
factories.put(Sonic3kObjectIds.ANIMATED_STILL_SPRITE,
        (spawn, registry) -> new AnimatedStillSpriteInstance(spawn));
```

Add the necessary imports.

**Step 2: Build and run tests**

Run: `mvn test -Dtest=TestSonic3kLevelLoading -q`
Expected: PASS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java
git commit -m "feat(s3k): register StillSprite and AnimatedStillSprite factories"
```

---

### Task 8: Verify with full test suite

**Step 1: Run all S3K tests**

Run: `mvn test -q`
Expected: All tests PASS. Key tests:
- `TestSonic3kLevelLoading`
- `TestS3kAiz1SkipHeadless`
- `TestSonic3kBootstrapResolver`
- `TestSonic3kDecodingUtils`

**Step 2: Verify subtype table against ROM hex dump**

Cross-reference the SUBTYPE_TABLE entries against the ROM data at 0x2B968 (StillSprite) and 0x2BF6C (AnimatedStillSprite). Each 6-byte entry should match: art_tile(2 BE), priority(2 BE), width(1), height(1).

From the ROM hex dump at 0x2B968:
```
42 E9 03 00 0C 0C  → subtype 0: art=0x42E9, pri=0x0300, w=0x0C, h=0x0C ✓
42 E9 03 00 10 08  → subtype 1: art=0x42E9, pri=0x0300, w=0x10, h=0x08 ✓
42 E9 03 00 08 04  → subtype 2: art=0x42E9, pri=0x0300, w=0x08, h=0x04 ✓
40 01 03 00 08 08  → subtype 3: art=0x4001, pri=0x0300, w=0x08, h=0x08 ✓
60 01 03 00 08 20  → subtype 4: art=0x6001, pri=0x0300, w=0x08, h=0x20 ✓
C2 E9 03 00 0C 0C  → subtype 5: art=0xC2E9, pri=0x0300, w=0x0C, h=0x0C ✓
```

**Step 3: If any test fails, debug and fix before proceeding**

**Step 4: Final commit (if any fixes needed)**
