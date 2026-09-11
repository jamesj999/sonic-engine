# Mutable Level Data Abstraction — Design Spec

**Date:** 2026-03-24
**Status:** Draft
**Phase:** 3 of 5 (GameRuntime roadmap)
**Goal:** Enable the level editor to modify tiles, blocks, chunks, collision, object placements, and ring placements through a mutable level data layer with dirty-region tracking.

## Context

Level data is currently loaded from ROM into arrays in `AbstractLevel` (patterns, chunks, blocks, solidTiles, map) and treated as immutable. Object and ring spawns are stored as `List.copyOf()` immutable lists. The `Level` interface exposes read-only getters that `LevelManager`, rendering, and collision systems consume.

The in-game level editor (Phase 4) needs to mutate this data. This spec introduces a `MutableLevel` subclass that provides mutation methods and dirty-region tracking, allowing subsystems to incrementally update only what changed.

### Prerequisites (Complete)

- Phase 1: `GameRuntime` container — mutable state is explicitly owned
- Phase 2: `ObjectServices` migration — all object code routes through injectable services

## Design

### MutableLevel Class

`MutableLevel extends AbstractLevel` — created by snapshotting data from a ROM-loaded level.

**Construction:**
```java
MutableLevel ml = MutableLevel.snapshot(existingLevel);
```

`snapshot()` deep-copies all arrays from the source level: patterns, chunks, blocks, solidTiles, palettes, map data. Object and ring spawn lists are copied into `ArrayList` (mutable). Boundaries and zone metadata are copied by value.

**Data (inherited from AbstractLevel):**
```
Pattern[] patterns          (deep copy from source)
Chunk[] chunks              (deep copy from source)
Block[] blocks              (deep copy from source)
SolidTile[] solidTiles      (deep copy from source)
Map map                     (deep copy from source)
Palette[] palettes          (deep copy from source)
ArrayList<ObjectSpawn> objects    (mutable copy)
ArrayList<RingSpawn> rings       (mutable copy)
```

**Change tracking:**
```
BitSet dirtyPatterns        (which pattern indices were modified)
BitSet dirtyChunks          (which chunk indices were modified)
BitSet dirtyBlocks          (which block indices were modified)
BitSet dirtyMapCells        (linearized layer*width*height — which layout cells changed)
BitSet dirtySolidTiles      (which collision tiles were modified)
boolean objectsDirty        (spawn list was modified)
boolean ringsDirty          (ring spawn list was modified)
```

**Mutation API:**
```java
// Pattern editing
void setPattern(int index, Pattern pattern)

// Chunk editing (modify which pattern a chunk references)
void setPatternDescInChunk(int chunkIndex, int px, int py, PatternDesc desc)

// Block editing (modify which chunks compose a block)
void setChunkInBlock(int blockIndex, int chunkX, int chunkY, ChunkDesc desc)

// Map layout editing (place a different block at a position)
void setBlockInMap(int layer, int blockX, int blockY, int blockIndex)

// Collision editing
void setSolidTile(int index, SolidTile tile)

// Object spawn editing
void addObjectSpawn(ObjectSpawn spawn)
void removeObjectSpawn(ObjectSpawn spawn)
void moveObjectSpawn(ObjectSpawn oldSpawn, ObjectSpawn newSpawn)

// Ring spawn editing
void addRingSpawn(RingSpawn spawn)
void removeRingSpawn(RingSpawn spawn)
```

Each mutation method marks the affected dirty bit(s). Block-level mutations transitively dirty the map cells that reference that block.

**Dirty region consumption (read-once semantics):**
```java
BitSet consumeDirtyPatterns()       // returns current set, then clears
BitSet consumeDirtyBlocks()
BitSet consumeDirtyMapCells()
BitSet consumeDirtySolidTiles()
boolean consumeObjectsDirty()       // returns flag, then clears
boolean consumeRingsDirty()
```

### Level Interface

The `Level` interface does not change. `MutableLevel` satisfies it via `AbstractLevel` inheritance. `LevelManager` and all subsystems consume `Level` — they don't know whether the backing data is ROM-loaded or editor-mutated.

Only the editor holds the `MutableLevel` reference and calls mutation methods.

### Dirty Region Processing

Each frame, before `update()`, `LevelManager` processes dirty regions from the mutable level. A new method:

```java
void processDirtyRegions() {
    if (!(level instanceof MutableLevel ml)) return;

    BitSet dirtyPatterns = ml.consumeDirtyPatterns();
    if (!dirtyPatterns.isEmpty()) {
        graphicsManager.reuploadDirtyPatterns(dirtyPatterns, ml);
    }

    BitSet dirtyBlocks = ml.consumeDirtyBlocks();
    BitSet dirtyMapCells = ml.consumeDirtyMapCells();
    if (!dirtyBlocks.isEmpty() || !dirtyMapCells.isEmpty()) {
        tilemapManager.rebuildDirtyRegions(dirtyBlocks, dirtyMapCells, ml);
    }

    // Collision: no cache to rebuild — SolidTile data is read directly each probe

    if (ml.consumeObjectsDirty()) {
        objectManager.resyncSpawnList(ml.getObjects());
    }

    if (ml.consumeRingsDirty()) {
        ringManager.resyncSpawnList(ml.getRings());
    }
}
```

Called from `GameLoop.step()` at the start of each frame.

**Subsystem responsibilities:**

| Change | Subsystem | Action |
|--------|-----------|--------|
| Pattern pixels | GraphicsManager | Re-upload affected pattern textures to GPU atlas |
| Block chunk composition | TilemapGpuRenderer | Rebuild tilemap data for affected blocks + transitive map cells |
| Map layout cells | TilemapGpuRenderer | Update tilemap texture for changed cells |
| SolidTile heights/angles | TerrainCollisionManager | Nothing — collision reads SolidTile directly each probe |
| Object spawns | ObjectManager | Resync — `syncActiveSpawns()` diffs active vs expected |
| Ring spawns | RingManager | Rebuild placement window from new list, preserve collection state by position |

### Integration with Editor Mode (Phase 4 Preview)

**Enter editor:**
1. Editor takes current `level` from `LevelManager`
2. Calls `MutableLevel.snapshot(level)` to create mutable copy
3. Calls `LevelManager.setLevel(mutableLevel)` to swap it in
4. Subsystems now read from the mutable copy transparently

**During editing:**
- Editor calls mutation methods on `MutableLevel`
- Each frame, `processDirtyRegions()` picks up changes and updates subsystems incrementally

**Resume gameplay:**
- No swap needed — gameplay runs directly on the `MutableLevel`
- Dirty regions are processed before the first gameplay frame

**Revert to ROM:**
- Reload level from ROM (`LevelManager.loadZoneAndAct()`) — creates a fresh `Sonic2Level`/etc., replaces the `MutableLevel` entirely

### LevelManager Changes

Three concerns:

**`setLevel()` — swaps the current level:**
```java
public void setLevel(Level level) {
    this.level = level;
    // Re-initialise animation managers with the new level reference.
    // AnimatedPatternManager and AnimatedPaletteManager capture `level`
    // at construction time — they will read stale Pattern objects if not
    // re-initialised after a level swap.
    reinitAnimatedContent();
    invalidateForegroundTilemap();  // full rebuild since entire level changed
}
```

**`processDirtyRegions()` — incremental updates each frame:**
As described above. Called from `GameLoop.step()`.

**`reinitAnimatedContent()` — extracted from existing init path:**
The animated pattern/palette managers (`Sonic2PatternAnimator`, `Sonic2PaletteCycler`, etc.)
store a `level` reference at construction. When `setLevel()` swaps the level, these must be
rebuilt so they read Pattern objects from the new `MutableLevel`, not the old ROM-loaded one.
Extract the existing init logic from `loadZoneAndAct()` into a reusable method.

### RingManager Resync Strategy

`RingPlacement` uses an index-keyed `BitSet` for collection state (`collected`) and a sized
`sparkleStartFrames` array. When the spawn list changes (add/remove), indices shift and the
BitSet becomes invalid.

**Resolution:** `resyncSpawnList()` resets collection state entirely. In editor mode this is
acceptable — the editor is placing/removing rings, not playing the level. When gameplay resumes
after editing, all rings are uncollected (fresh start). This avoids complex index remapping.

If finer-grained preservation is needed later (e.g., undo ring removal during play-test),
it can be added as a position-keyed `Set<RingSpawn>` lookup. Not needed for Phase 3.

### Chunk/Block Deep Copy via saveState/restoreState

`Chunk` and `Block` have existing `saveState()`/`restoreState()` methods that produce
independent copies of their internal descriptor arrays. `snapshot()` must use this mechanism:

```java
for (int i = 0; i < chunks.length; i++) {
    chunks[i] = new Chunk();
    chunks[i].restoreState(sourceChunks[i].saveState());
}
```

This correctly replaces `PatternDesc.EMPTY` sentinel references with fresh instances,
avoiding shared mutable state between source and copy. Same pattern for `Block`.

`SolidTile` deep copy must copy the `public final byte[] heights` and `byte[] widths`
arrays explicitly (they are mutable despite being `final` fields).

### Map Deep Copy

Construct via `new Map(src.getLayerCount(), src.getWidth(), src.getHeight(), src.getData())`.
The `Map` constructor already copies the passed-in array via `System.arraycopy`.

### Map Byte Range Limitation

`Map` stores block indices as `byte` values (range 0–255 unsigned). Some S2 zones use >128
blocks. The existing `Map.setValue()` accepts `byte`. The `setBlockInMap()` mutation method
casts `int blockIndex` to `byte`, matching the existing ROM data model. This is a pre-existing
limitation — widening `Map` to `short[]` is out of scope for Phase 3.

### Chunk → Block Transitive Dirtying

The mutation hierarchy includes a missing level: `setPatternDescInChunk(int chunkIndex, int px, int py, PatternDesc desc)` — changes which pattern a chunk references. This dirties the chunk, which must transitively dirty all blocks referencing that chunk, which must transitively dirty all map cells referencing those blocks.

`MutableLevel` maintains reverse lookup tables built at snapshot time:
- `Map<Integer, Set<Integer>> chunkToBlocks` — which blocks reference each chunk index
- `Map<Integer, Set<Integer>> blockToMapCells` — which map cells reference each block index

These are built by scanning `Block.chunkDescs` and `Map.data` once during `snapshot()`.
Mutation methods use these for transitive dirtying. The scan cost is negligible (4096 map cells, ~200 blocks).

### File Changes

**New files (2):**
- `src/main/java/com/openggf/level/MutableLevel.java`
- `src/test/java/com/openggf/level/TestMutableLevel.java`

**Modified files (~6):**
- `LevelManager.java` — add `setLevel()`, `processDirtyRegions()`
- `GraphicsManager.java` — add `reuploadDirtyPatterns(BitSet, Level)`
- `TilemapGpuRenderer.java` — add `rebuildDirtyRegions(BitSet, BitSet, Level)`
- `RingManager.java` — add `resyncSpawnList(List<RingSpawn>)` with position-based collection state preservation
- `ObjectManager.java` — add `resyncSpawnList(List<ObjectSpawn>)` (leverages existing `syncActiveSpawns`)
- `GameLoop.java` — call `processDirtyRegions()` at frame start

**Not touched:**
- `Level.java` interface
- `AbstractLevel.java`
- `Sonic1Level`, `Sonic2Level`, `Sonic3kLevel`
- `Pattern`, `Chunk`, `Block`, `Map`, `SolidTile`, `PatternDesc`, `ChunkDesc`
- `ObjectServices`, `GameServices`, `GameRuntime`
- All object/ring instance code

**Estimated scope:** ~8-10 files.

### Testing Strategy

**MutableLevel unit tests:**
- `snapshot()` produces deep copy — mutating copy doesn't affect source
- Each setter marks correct dirty bit(s)
- `consumeDirtyX()` returns dirty set and clears it (read-once)
- Object/ring add/remove/move updates list and sets dirty flag
- Transitive dirtying: block change dirties referencing map cells

**Dirty region processing integration tests:**
- Modify pattern → verify GraphicsManager re-caches only that pattern
- Modify map cell → verify tilemap updates only that region
- Add object spawn → verify ObjectManager instantiates it next frame
- Remove ring spawn → verify RingManager drops it

**Round-trip test:**
- Load level from ROM → snapshot to MutableLevel → run 60 frames
- Verify identical behavior to ROM-loaded original (no mutations, so pixel-perfect)

**No-ROM unit tests:**
- Construct MutableLevel with synthetic data for tests that don't need a real level

### Success Criteria

1. `MutableLevel.snapshot(level)` produces a working level that gameplay can run on identically
2. Each mutation type is tracked by the correct dirty bit(s)
3. Dirty region processing updates only affected subsystems
4. Object/ring spawn changes are reflected within one frame
5. All existing tests pass unchanged (MutableLevel is additive — no existing code modified destructively)
6. No performance regression in normal gameplay (dirty processing is a no-op when level is not MutableLevel)
