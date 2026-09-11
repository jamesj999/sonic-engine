# Phase 1: Utility Method Extractions

## Overview
Low-risk utility method extractions that consolidate duplicated code without changing behavior.
No class hierarchy changes — just static utility methods and delegation.

**Dependencies:** None

---

## Implementation Sequence
1. **1C — PatternLoader** (extend PatternDecompressor): smallest, zero deps
2. **1D — PaletteLoader**: standalone utility
3. **1B — CommonSpriteDataLoader**: uses PatternDecompressor.uncompressed from 1C
4. **1A — DestructionEffects**: most complex, touches most files

---

## 1A. DestructionEffects Utility

### Problem
40+ badnik files duplicate the explosion + animal + points + SFX destruction sequence.

### Three Variants Found

**S2 (AbstractBadnikInstance.destroyBadnik, line 101):**
- spawn ExplosionObjectInstance(0x27, x, y)
- spawn AnimalObjectInstance(0x28, x, y)
- player.incrementBadnikChain() → addScore → spawn PointsObjectInstance
- playSfx(Sonic2Sfx.EXPLOSION.id = 0xC1)
- removeFromActiveSpawns

**S1 (12 badnik files, all identical overrides):**
- Same sequence but: markRemembered (not removeFromActiveSpawns)
- Uses Sonic1PointsObjectInstance
- Uses Sonic1Sfx.BREAK_ITEM.id

**S3K (AbstractS3kBadnikInstance.defeat, line 71):**
- Spawn explosion only (NO animal, NO points popup)
- Uses Sonic3kSfx.BREAK.id
- Still does incrementBadnikChain + addScore

### API Design
```java
package com.openggf.level.objects;

public final class DestructionEffects {
    public record DestructionConfig(
        int sfxId,
        boolean spawnAnimal,
        boolean useRespawnTracking,
        PointsFactory pointsFactory  // null = no points popup
    ) {}

    @FunctionalInterface
    public interface PointsFactory {
        ObjectInstance create(ObjectSpawn spawn, LevelManager lm, int pointsValue);
    }

    public static void destroyBadnik(int x, int y, ObjectSpawn spawn,
            AbstractPlayableSprite player, LevelManager levelManager,
            DestructionConfig config) { ... }
}
```

Each game defines its config constant in its base badnik class:
- `AbstractBadnikInstance`: `S2_DESTROY_CONFIG` (default)
- S1 badniks override `getDestructionConfig()` → `S1_DESTROY_CONFIG`
- `AbstractS3kBadnikInstance`: `S3K_DESTROY_CONFIG`

### Files to Create
- `src/main/java/com/openggf/level/objects/DestructionEffects.java`

### Files to Modify
- `AbstractBadnikInstance.java` — replace destroyBadnik body, add getDestructionConfig()
- `AbstractS3kBadnikInstance.java` — replace defeat body
- 12 S1 badnik files — override getDestructionConfig() instead of full destroyBadnik
- `TurtloidBadnikInstance.java`, `RexonHeadObjectInstance.java` — use DestructionEffects with custom coords

### Risk: LOW
Pure extraction. Each call site produces identical explosion/animal/points/SFX.

---

## 1B. CommonSpriteDataLoader

### Problem
Animation script parsing and related utilities are byte-identical across S1, S2, S3K
but implemented 3+ times each.

### What's Consolidated (identical across all games)
- `parseAnimationScript(reader, addr)` — delay byte + frames + end-action codes
- `loadAnimationSet(reader, baseAddr, count)` — word-offset table + script parsing
- `resolveBankSize(dplcFrames, mappingFrames)` — max tile computation

### What Stays Game-Specific
- `loadDplcFrames` — S1 uses byte entry count, S2/S3K use word entry count
- `loadMappingFrames` — S1=5-byte pieces, S2=8-byte, S3K=6-byte

### Files to Create
- `src/main/java/com/openggf/game/common/CommonSpriteDataLoader.java`

### Files to Modify
- `S1SpriteDataLoader.java` — delegate parseAnimationScript, loadAnimationSet, resolveBankSize
- `S3kSpriteDataLoader.java` — delegate same methods
- `Sonic2PlayerArt.java` — replace inline animation parsing with CommonSpriteDataLoader calls
- `Sonic2ObjectArt.java` — delegate loadAnimationSet
- `Sonic2DustArt.java` — delegate resolveBankSize

### Risk: LOW
Animation script format is byte-identical. resolveBankSize uses max() (conservative).

---

## 1C. PatternDecompressor.uncompressed() Extension

### Problem
6 files duplicate the same uncompressed art tile loading loop:
validate size%32, iterate, Pattern.fromSegaFormat(reader.slice(...)).

### Solution
Add one static method to existing `PatternDecompressor`:
```java
public static Pattern[] uncompressed(RomByteReader reader, int address, int size)
        throws IOException {
    int count = size / Pattern.PATTERN_SIZE_IN_ROM;
    Pattern[] patterns = new Pattern[count];
    for (int i = 0; i < count; i++) {
        patterns[i] = new Pattern();
        patterns[i].fromSegaFormat(reader.slice(
            address + i * Pattern.PATTERN_SIZE_IN_ROM, Pattern.PATTERN_SIZE_IN_ROM));
    }
    return patterns;
}
```

### Files to Modify
- `PatternDecompressor.java` — add uncompressed() method
- `S1SpriteDataLoader.java` — delegate loadArtTiles
- `S3kSpriteDataLoader.java` — delegate loadArtTiles
- `Sonic2PlayerArt.java` — delegate loadArtTiles
- `Sonic2DustArt.java` — delegate loadArtTiles
- `Sonic1PlayerArt.java` — delegate loadArtTiles
- `Sonic3kPlayerArt.java` — delegate loadArtTiles

### Risk: VERY LOW
Trivial one-liner delegations. Logic is byte-identical to existing code.

---

## 1D. PaletteLoader Utility

### Problem
7+ files duplicate: read 128 bytes → split into 4×32-byte lines → Palette.fromSegaFormat().

### API Design
```java
package com.openggf.data;

public final class PaletteLoader {
    /** Load 4 palette lines (128 bytes) from ROM. */
    public static Palette[] loadFullPalette(Rom rom, int address) { ... }

    /** Load 4 palette lines from raw byte array (128 bytes). */
    public static Palette[] fromBytes(byte[] paletteData) { ... }

    /** Load single palette line (32 bytes) from ROM. */
    public static Palette loadPaletteLine(Rom rom, int address) { ... }

    /** Load single palette line from raw bytes. */
    public static Palette fromLineBytes(byte[] lineData) { ... }
}
```

### Files to Create
- `src/main/java/com/openggf/data/PaletteLoader.java`

### Files to Modify
- `Sonic1WaterDataProvider.java` — replace 4-line loop with PaletteLoader.loadFullPalette
- `Sonic2WaterDataProvider.java` — replace with PaletteLoader.loadFullPalette
- `Sonic3kWaterDataProvider.java` — replace with PaletteLoader.fromBytes
- `WaterSystem.java` (2 locations) — replace with PaletteLoader
- `Sonic2SpecialStagePalette.java` (2 locations) — replace
- `Sonic3kSpecialStageDataLoader.java` — replace

### Risk: LOW
Pure extraction. 5-line pattern is identical everywhere.

---

## Summary

| Utility | New Files | Modified Files | Call Sites |
|---------|-----------|---------------|------------|
| 1A DestructionEffects | 1 | ~16 | ~37 |
| 1B CommonSpriteDataLoader | 1 | ~7 | ~15 |
| 1C PatternDecompressor ext. | 0 | 7 | 6 |
| 1D PaletteLoader | 1 | ~8 | ~10 |
| **Total** | **3** | **~35** | **~68** |
