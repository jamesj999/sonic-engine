# Phase 2: Base Class Extractions

## Overview
Extract abstract base classes to eliminate structural duplication across game modules.
No behavioral changes — pure structural deduplication.

**Dependencies:** None (independent of Phase 1)

---

## 2A. AbstractLevel Base Class

### Problem
`Sonic1Level`, `Sonic2Level`, `Sonic3kLevel` all implement the `Level` interface with
17+ identical accessor methods and identical field declarations.

### Shared Fields (identical across all three)
```java
protected Palette[] palettes;           // always size 4
protected Pattern[] patterns;
protected Chunk[] chunks;
protected Block[] blocks;
protected SolidTile[] solidTiles;
protected Map map;
protected List<ObjectSpawn> objects;
protected List<RingSpawn> rings;
protected RingSpriteSheet ringSpriteSheet;
protected int zoneIndex;
protected int patternCount, chunkCount, blockCount, solidTileCount;
protected int minX, maxX, minY, maxY;
```

### Identical Methods (move to base)
`getPaletteCount()`, `getPalette(int)`, `setPalette(int, Palette)`, `getPatternCount()`,
`getPattern(int)`, `ensurePatternCapacity(int)`, `getChunkCount()`, `getChunk(int)`,
`getBlockCount()`, `getBlock(int)`, `getSolidTile(int)`, `getMap()`, `getObjects()`,
`getRings()`, `getRingSpriteSheet()`, `getMinX/MaxX/MinY/MaxY()`, `getZoneIndex()`

### Methods That Stay in Subclasses
- `getBlockPixelSize()`: S1 returns 256, S2/S3K return 128
- `getChunksPerBlockSide()`: S1 returns 16, S2/S3K return 8
- `getLayerWidthBlocks(int)` / `getLayerHeightBlocks(int)`: S3K overrides with per-layer dims
- `resolveCollisionBlockIndex()`: S1 overrides with loop-flag logic
- All `load*()` methods (entirely game-specific)
- S1: `hasLoopFlag()`, `getRawFgValue()`
- S3K: `applyPatternOverlay()`, `snapshotChunks()`, `restoreChunks()`

### Class Hierarchy
```
Level (interface, unchanged)
  └── AbstractLevel (NEW) ← shared state + 17 accessors
        ├── Sonic1Level
        ├── Sonic2Level
        └── Sonic3kLevel
```

### S3K ensurePatternCapacity Note
S3K's `ensurePatternCapacity` is `synchronized` (AIZ intro overlay threading). S3K
overrides the base method solely to add `synchronized`:
```java
@Override
public synchronized void ensurePatternCapacity(int minCount) {
    super.ensurePatternCapacity(minCount);
}
```

### File Changes
| File | Change |
|------|--------|
| `level/AbstractLevel.java` | **NEW** — shared state + 17 accessor methods (~110 lines) |
| `game/sonic1/Sonic1Level.java` | `extends AbstractLevel`; remove ~120 lines of duplicate fields/accessors |
| `game/sonic2/Sonic2Level.java` | `extends AbstractLevel`; remove ~130 lines |
| `game/sonic3k/Sonic3kLevel.java` | `extends AbstractLevel`; remove ~80 lines; keep `synchronized` override |

**Estimated savings:** ~330 lines removed, ~110 lines added = **-220 net**

**Risk: LOW.** Pure accessor deduplication. All loading logic stays in subclasses.

---

## 2B. AbstractSmpsLoader Base Class

### Problem
Three `SmpsLoader` implementations repeat identical caching boilerplate and `loadSfx(String)` parsing.

### What's Actually Shared
- `Rom rom` field
- `Map<Integer, AbstractSmpsData> musicCache`, `sfxCache`
- `loadSfx(String)` — parse hex string, validate range, delegate to `loadSfx(int)`
- Cache get/put boilerplate

### What Differs (stays in subclasses)
- `loadMusic()` — entirely different ROM reading/decompression per game
- `loadSfx(int)` — different ROM formats
- `loadDacData()` — different DAC table formats and decoders
- S3K: additional `bankCache`, global voice data
- S2: additional `sfxMap` for named SFX lookup (overrides `loadSfx(String)`)

### API Design
```java
package com.openggf.audio.smps;

public abstract class AbstractSmpsLoader implements SmpsLoader {
    protected final Rom rom;
    protected final Map<Integer, AbstractSmpsData> musicCache = new HashMap<>();
    protected final Map<Integer, AbstractSmpsData> sfxCache = new HashMap<>();

    protected AbstractSmpsLoader(Rom rom) { this.rom = rom; }

    @Override
    public AbstractSmpsData loadSfx(String sfxName) {
        // Shared hex-parse + validate + delegate pattern
    }

    protected abstract boolean isValidSfxId(int id);

    protected AbstractSmpsData getCachedMusic(int id) { return musicCache.get(id); }
    protected void cacheMusic(int id, AbstractSmpsData data) { musicCache.put(id, data); }
    protected AbstractSmpsData getCachedSfx(int id) { return sfxCache.get(id); }
    protected void cacheSfx(int id, AbstractSmpsData data) { sfxCache.put(id, data); }
}
```

### File Changes
| File | Change |
|------|--------|
| `audio/smps/AbstractSmpsLoader.java` | **NEW** — rom, caches, loadSfx(String), cache helpers |
| `game/sonic1/audio/smps/Sonic1SmpsLoader.java` | `extends AbstractSmpsLoader`; remove rom/cache fields; add `isValidSfxId()` |
| `game/sonic2/audio/smps/Sonic2SmpsLoader.java` | `extends AbstractSmpsLoader`; override `loadSfx(String)` for sfxMap; add `isValidSfxId()` |
| `game/sonic3k/audio/smps/Sonic3kSmpsLoader.java` | `extends AbstractSmpsLoader`; remove rom/cache fields; keep bankCache locally; add `isValidSfxId()` |

**Estimated savings:** ~45 lines removed, ~50 added = structural improvement (consistency, not line count)

**Risk: LOW.** Only extracting mechanical boilerplate. No changes to ROM reading or decompression.

---

## 2C. Object Rendering Helpers in AbstractObjectInstance

### Problem
~150+ object files repeat 5-6 lines of null-check boilerplate in `appendRenderCommands()`:
```java
ObjectRenderManager rm = LevelManager.getInstance().getObjectRenderManager();
if (rm == null) return;
PatternSpriteRenderer renderer = rm.getRenderer(ART_KEY);
if (renderer == null || !renderer.isReady()) return;
renderer.drawFrameIndex(frame, x, y, hFlip, vFlip);
```

### Design Decision: Helper Methods, Not New Class
A separate `RenderableObjectBase` would require changing `extends` in 244+ files and
create awkward hierarchy conflicts (badniks extend `AbstractBadnikInstance`). Instead,
add helper methods to the existing `AbstractObjectInstance`.

### API Design
```java
// Add to AbstractObjectInstance:

/** Returns ObjectRenderManager or null. Eliminates repeated LevelManager lookups. */
protected static ObjectRenderManager getRenderManager() {
    LevelManager lm = LevelManager.getInstance();
    return (lm != null) ? lm.getObjectRenderManager() : null;
}

/** Returns ready PatternSpriteRenderer for key, or null if unavailable. */
protected static PatternSpriteRenderer getRenderer(String artKey) {
    ObjectRenderManager rm = getRenderManager();
    if (rm == null) return null;
    PatternSpriteRenderer renderer = rm.getRenderer(artKey);
    return (renderer != null && renderer.isReady()) ? renderer : null;
}
```

### Migration Example
**Before (5-6 lines):**
```java
ObjectRenderManager rm = LevelManager.getInstance().getObjectRenderManager();
if (rm == null) { super.appendRenderCommands(commands); return; }
PatternSpriteRenderer renderer = rm.getRenderer(KEY);
if (renderer == null || !renderer.isReady()) { return; }
renderer.drawFrameIndex(frame, x, y, hFlip, vFlip);
```
**After (2-3 lines):**
```java
PatternSpriteRenderer renderer = getRenderer(KEY);
if (renderer == null) return;
renderer.drawFrameIndex(frame, x, y, hFlip, vFlip);
```

### Migration Strategy
1. Add helpers to `AbstractObjectInstance` (non-breaking, 1 file)
2. Migrate simple objects first (checkpoint, spike, signpost) — ~60% of objects
3. Migrate badniks
4. Skip complex multi-part objects (bosses with multiple renderers)

### File Changes
| File | Change |
|------|--------|
| `level/objects/AbstractObjectInstance.java` | Add 2 static helper methods (~15 lines) |
| ~150 object instance files | Replace 5-6 line boilerplate with 2-3 line helper call (incremental) |

**Estimated savings:** ~1,200 lines removed across all objects, ~500 added = **-700 net** (full migration)

**Risk: LOW.** Additive helpers. Migration is mechanical and incremental. Each file change is trivial.

---

## 2D. AbstractLevelAnimationManager

### Problem
`Sonic2LevelAnimationManager` and `Sonic3kLevelAnimationManager` are nearly identical
combined delegates implementing both `AnimatedPatternManager` and `AnimatedPaletteManager`.
S1 has no combined manager.

### Design
```java
package com.openggf.level.animation;

public abstract class AbstractLevelAnimationManager
        implements AnimatedPatternManager, AnimatedPaletteManager {

    private final AnimatedPatternManager patternAnimator;
    private final AnimatedPaletteManager paletteCycler;

    protected AbstractLevelAnimationManager(AnimatedPatternManager patternAnimator,
                                             AnimatedPaletteManager paletteCycler) {
        this.patternAnimator = patternAnimator;
        this.paletteCycler = paletteCycler;
    }

    @Override
    public void update() {
        if (patternAnimator != null) patternAnimator.update();
        if (paletteCycler != null) paletteCycler.update();
    }
}
```

### File Changes
| File | Change |
|------|--------|
| `level/animation/AbstractLevelAnimationManager.java` | **NEW** — combined delegate base |
| `game/sonic1/Sonic1LevelAnimationManager.java` | **NEW** — extends base, composes S1 cycler + animator |
| `game/sonic2/Sonic2LevelAnimationManager.java` | `extends AbstractLevelAnimationManager`; remove duplicate `update()` |
| `game/sonic3k/Sonic3kLevelAnimationManager.java` | `extends AbstractLevelAnimationManager`; remove duplicate `update()` |
| `game/sonic1/Sonic1.java` | Return `Sonic1LevelAnimationManager` from provider methods |

**Estimated savings:** ~30 lines removed, ~40 added = structural improvement (consistency)

**Risk: LOW.** Trivial delegation pattern. Internal cycler/animator classes untouched.

---

## Implementation Sequencing

1. **2A (AbstractLevel)** — highest line reduction (~220), lowest risk, isolated to 4 files
2. **2C (Object render helpers)** — add helpers first (1 file), then migrate incrementally
3. **2D (AbstractLevelAnimationManager)** — small, creates pattern consistency
4. **2B (AbstractSmpsLoader)** — modest gain, touches audio subsystem

## Summary

| Extraction | New Files | Modified Files | Net Line Change |
|-----------|-----------|---------------|-----------------|
| 2A AbstractLevel | 1 | 3 | -220 |
| 2B AbstractSmpsLoader | 1 | 3 | +5 (structural) |
| 2C Object render helpers | 0 | 1 + ~150 incremental | -700 |
| 2D AbstractLevelAnimationManager | 2 | 3 | +10 (structural) |
| **Total** | **4** | **~160** | **~-905** |
