# S3K PLC Art Registry Design

## Problem

S3K object art loading is hardcoded for 3 of 14 zones (AIZ, ICZ, LRZ). Objects in other zones spawn invisible. Adding a new zone requires manual code in 3+ files (`Sonic3kObjectArtProvider`, `Sonic3kObjectArt`, `Sonic3kObjectArtKeys`). There is no systematic registry mapping zone/act to object art, unlike S2's `Sonic2PlcArtRegistry`.

## Goal

Build a data-driven `Sonic3kPlcArtRegistry` that declares per-zone art entries (standalone badnik art + level-art objects) so that `Sonic3kObjectArtProvider` loads all 14 zones without zone-conditional `if` blocks.

## Art Sourcing Patterns in S3K

The disassembly validates three distinct art loading patterns:

### Pattern A: PLCKosM Badniks (KosinskiM → VRAM)
- Loaded during level load via `LoadEnemyArt` → `Queue_Kos_Module`
- Zone-indexed offset table (`Offs_LoadEnemyArt`) with per-act entries
- 37 entries across all zones (see PLCKosM inventory below)
- In our engine: standalone `loadKosinskiModuledPatterns()` + ROM-parsed mappings
- Examples: Bloominator, MonkeyDude, all zone badniks except DPLC types

### Pattern B: DPLC Badniks (Uncompressed + Perform_DPLC)
- Object calls `Perform_DPLC` each frame for dynamic tile loading
- Uses uncompressed art data from ROM
- In our engine: standalone `loadUncompressedPatterns()` + DPLC remap
- Known objects: Rhinobot (AIZ), BubblesBadnik (MGZ), Penguinator (ICZ)

### Pattern C: Level-Art Objects (Level Buffer References)
- Object init uses `make_art_tile(ArtTile_XXX, palette, priority)` to reference patterns already in the level buffer
- Patterns loaded by standard PLC system (Nemesis compressed) during level init
- In our engine: `buildLevelArtSheet()` extracts tiles from loaded level
- Examples: Spikes, Springs, FloatingPlatform, RideVine, all zone-specific platforms

## Registry Architecture

### Records

```java
// Standalone art (badniks, bosses) — decompressed independently from ROM
record StandaloneArtEntry(
    String key,              // art key for sheet lookup
    int artAddr,             // ROM address of compressed/uncompressed art
    CompressionType compression,  // KOSINSKI_MODULED, NEMESIS, or UNCOMPRESSED
    int artSize,             // byte size (only for UNCOMPRESSED)
    int mappingAddr,         // ROM address of S3K mapping table
    int palette,             // palette line (0-3)
    int dplcAddr             // -1 if no DPLC, else ROM address for DPLC remap
) {}

// Level-art object — references patterns already in the level buffer
record LevelArtEntry(
    String key,              // art key for sheet lookup
    int mappingAddr,         // ROM address of mapping table (-1 for hardcoded)
    int artTileBase,         // level buffer tile base index
    int palette              // palette line (0-3)
) {}

// Complete art plan for one zone+act
record ZoneArtPlan(
    List<StandaloneArtEntry> standaloneArt,
    List<LevelArtEntry> levelArt
) {}
```

### Registry Class

```java
public final class Sonic3kPlcArtRegistry {
    // Shared level-art entries (spikes, springs) present in all zones
    static final List<LevelArtEntry> SHARED_LEVEL_ART = List.of(
        // spikes, vertical/horizontal/diagonal springs (red+yellow)
    );

    // Returns the combined art plan for a zone+act
    static ZoneArtPlan getPlan(int zoneIndex, int actIndex);
}
```

### Integration with Sonic3kObjectArtProvider

```java
// Phase 1: loadArtForZone() — before level load
ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(zoneIndex, actIndex);
for (StandaloneArtEntry entry : plan.standaloneArt()) {
    ObjectSpriteSheet sheet = loadStandaloneSheet(entry);
    registerSheet(entry.key(), sheet);
}

// Phase 2: registerLevelArtSheets() — after level load
for (LevelArtEntry entry : plan.levelArt()) {
    ObjectSpriteSheet sheet = buildLevelArtSheet(entry);
    registerLevelArtSheet(entry.key(), sheet, art);
}
```

This eliminates all zone-conditional `if (zoneIndex == 0x00)` blocks.

## Zone-Specific Overrides

### Spike Tile Override (FBZ)
`Obj_Spikes` checks `Current_zone == 4` (FBZ) at runtime and uses `ArtTile_FBZSpikes` ($0200) instead of `ArtTile_SpikesSprings+$8` ($049C). Handle via a zone-specific `LevelArtEntry` for FBZ spikes with `artTileBase=0x0200`.

### Diagonal Spring Override (MGZ, MHZ)
`Obj_Spring` checks `Current_zone == 2` (MGZ) and `Current_zone == 7` (MHZ) for diagonal springs, using `ArtTile_MGZMHZDiagonalSpring` ($0478) instead of `ArtTile_DiagonalSpring` ($043A). Handle via zone-specific `LevelArtEntry` overrides.

### Gray Button Zone Variants
`Obj_Button` has per-zone overrides:
- Default: `ArtTile_GrayButton` ($0456), `Map_Button`
- HCZ: `ArtTile_HCZButton` ($0426), `Map_HCZButton`
- CNZ: `ArtTile_CNZMisc+$C9`, `Map_CNZButton`
- FBZ: `ArtTile_FBZButton` ($0500), standalone KosinskiM
- LRZ Act 1: `ArtTile_LRZMisc`, LRZ Act 2: `ArtTile_LRZ2Misc+$1C`

### Collapsing Bridge / Breakable Wall
Fully zone-specific mappings. Each zone provides its own `LevelArtEntry` with zone-specific mapping address and art tile base. Most use `$001` as art tile base with palette 2.

## Validated PLCKosM Inventory

```
PLCKosM_AIZ:  MonkeyDude, Bloominator, CaterkillerJr
PLCKosM_HCZ1: Blastoid, TurboSpiker, MegaChopper, Pointdexter
PLCKosM_HCZ2: Jawz, TurboSpiker, MegaChopper, Pointdexter
PLCKosM_MGZ1: Spiker, MGZMiniboss, MGZMiniBossDebris
PLCKosM_MGZ2: Spiker, Mantis
PLCKosM_CNZ:  Sparkle, Batbot, ClamerShot(+$70), CNZBalloon
PLCKosM_FBZ:  Blaster, Technosqueek, FBZButton
PLCKosM_ICZ:  ICZSnowdust, StarPointer
PLCKosM_LBZ:  SnaleBlaster, Orbinaut, Ribot, Corkey
PLCKosM_MHZ1: Madmole, Mushmeanie, Dragonfly
PLCKosM_MHZ2: CluckoidArrow(+$22), Madmole, Mushmeanie, Dragonfly
PLCKosM_SOZ:  Skorp, Sandworm, Rockn
PLCKosM_LRZ:  FirewormSegments, Iwamodoki, Toxomister
PLCKosM_SSZ:  EggRoboBadnik
PLCKosM_DEZ:  Spikebonker, Chainspike
PLCKosM_DDZ:  EggRoboBadnik
```

DPLC badniks (not in PLCKosM — use Perform_DPLC):
- Rhinobot (AIZ): uncompressed art + DPLC
- BubblesBadnik (MGZ): uncompressed art + DPLC
- Penguinator (ICZ): uncompressed art + DPLC

## Per-Zone Level-Art Summary

Each zone's level objects use `ArtTile_XXXMisc` constants with offsets. Key base constants per zone:

| Zone | Primary Base | Secondary Base | Notes |
|------|-------------|----------------|-------|
| AIZ | ArtTile_AIZMisc1 ($0333) | ArtTile_AIZMisc2 ($02E9) | Act-specific floating platform tiles |
| HCZ | ArtTile_HCZMisc ($03CA) | ArtTile_HCZ2BlockPlat | Act-specific water splash art |
| MGZ | ArtTile_MGZMisc1 | ArtTile_MGZMisc2 | |
| CNZ | ArtTile_CNZMisc ($0351) | ArtTile_CNZPlatform | Many offset variants (+$13 to +$CF) |
| FBZ | ArtTile_FBZMisc ($0379) | ArtTile_FBZMisc2 ($02D2), ArtTile_FBZOutdoors ($02E5) | 3 misc tile regions |
| ICZ | ArtTile_ICZMisc1 | ArtTile_ICZMisc2 | Most objects share Map_ICZPlatforms |
| LBZ | ArtTile_LBZMisc | ArtTile_LBZ2Misc ($02EA) | |
| MHZ | ArtTile_MHZMisc ($0347) | — | Many offset variants (+$C to +$DD) |
| SOZ | ArtTile_SOZMisc | ArtTile_SOZ2Extra | Act 2 uses SOZ2Extra for darkness |
| LRZ | ArtTile_LRZMisc ($03A1) | ArtTile_LRZ2Misc ($040D) | Act-specific variants |
| SSZ | ArtTile_SSZMisc | — | Many offset variants (+$10 to +$1B4) |
| DEZ | ArtTile_DEZMisc | ArtTile_DEZMisc2, ArtTile_DEZ2Extra | 3 misc tile regions |
| DDZ | ArtTile_DDZMisc | — | Minimal objects (boss arena) |
| HPZ | — | — | Hidden Palace (minimal) |

## Implementation Strategy

### Phase 1: Registry + Provider Refactor
1. Create `Sonic3kPlcArtRegistry` with `StandaloneArtEntry`, `LevelArtEntry`, `ZoneArtPlan` records
2. Populate shared entries (spikes, springs) — already working, just move from hardcoded to registry
3. Populate AIZ entries (already working — migrate from hardcoded to registry)
4. Refactor `Sonic3kObjectArtProvider.loadArtForZone()` to iterate registry plan
5. Refactor `Sonic3kObjectArtProvider.registerLevelArtSheets()` to iterate registry plan
6. All zone-conditional blocks eliminated

### Phase 2: Populate Remaining Zones
7. Add HCZ, MGZ standalone+level entries
8. Add CNZ, FBZ, ICZ entries (FBZ spike override)
9. Add LBZ, MHZ, SOZ entries
10. Add LRZ, SSZ, DEZ, DDZ entries
11. Add missing `Sonic3kObjectArtKeys` constants
12. Add missing `Sonic3kConstants` ROM addresses (verified via RomOffsetFinder)

### Phase 3: Validation
13. Run existing S3K tests (TestS3kAiz1SkipHeadless, TestSonic3kLevelLoading)
14. Visually verify each zone loads without missing-art warnings
15. Add registry coverage test (each PLCKosM entry has a matching registry entry)

## Files Modified

| File | Change |
|------|--------|
| `Sonic3kPlcArtRegistry.java` | **NEW** — Zone-indexed art registry |
| `Sonic3kObjectArtProvider.java` | Refactor to iterate registry plans |
| `Sonic3kObjectArt.java` | Add new zone builder methods |
| `Sonic3kObjectArtKeys.java` | Add keys for all zones |
| `Sonic3kConstants.java` | Add mapping/art ROM addresses |

## Key Design Decisions

1. **Standalone over level-buffer for badniks**: PLCKosM art goes to VRAM on real hardware, but we decompress standalone to avoid overlap conflicts. This matches the existing pattern for AIZ badniks.

2. **Zone+act indexed**: The registry uses `(zoneIndex, actIndex)` since HCZ1/HCZ2, MGZ1/MGZ2, etc. have different badnik sets.

3. **Hardcoded mappings preserved**: Some objects (spikes, springs, AIZ1Tree) use hardcoded mapping frames because their ROM mappings aren't simple tables. The `LevelArtEntry.mappingAddr=-1` case triggers existing hardcoded builder methods.

4. **Incremental population**: The registry can be populated incrementally — zones without entries simply load no zone-specific art (shared spikes/springs still work).
