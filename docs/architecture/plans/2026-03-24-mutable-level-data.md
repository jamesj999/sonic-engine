# Mutable Level Data — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `MutableLevel` subclass of `AbstractLevel` that supports mutation methods with dirty-region tracking, enabling the level editor to modify tiles, blocks, chunks, collision, object placements, and ring placements.

**Architecture:** `MutableLevel` deep-copies all data from a ROM-loaded level, provides setter methods that mark dirty bits, and exposes read-once dirty region queries consumed by subsystems each frame via `LevelManager.processDirtyRegions()`.

**Tech Stack:** Java 21, Maven, JUnit 5

**Spec:** `docs/architecture/designs/2026-03-24-mutable-level-data-design.md`

---

## File Map

### New Files
| File | Responsibility |
|------|---------------|
| `src/main/java/com/openggf/level/MutableLevel.java` | Level subclass with mutation + dirty tracking |
| `src/test/java/com/openggf/level/TestMutableLevel.java` | Unit tests for snapshot, mutation, dirty tracking |

### Modified Files
| File | Changes |
|------|---------|
| `src/main/java/com/openggf/level/Block.java` | Add `saveState()` / `restoreState()` (matching Chunk pattern) |
| `src/main/java/com/openggf/level/LevelManager.java` | Add `setLevel()`, `processDirtyRegions()`, extract `reinitAnimatedContent()` |
| `src/main/java/com/openggf/graphics/GraphicsManager.java` | Add `reuploadDirtyPatterns(BitSet, Level)` |
| `src/main/java/com/openggf/level/objects/ObjectManager.java` | Add `resyncSpawnList(List<ObjectSpawn>)` |
| `src/main/java/com/openggf/level/rings/RingManager.java` | Add `resyncSpawnList(List<RingSpawn>)` |
| `src/main/java/com/openggf/GameLoop.java` | Call `processDirtyRegions()` at frame start |

---

## Task 1: Block saveState/restoreState

**Files:**
- Modify: `src/main/java/com/openggf/level/Block.java`

Block lacks the `saveState()`/`restoreState()` that Chunk already has. MutableLevel needs it for deep copy.

- [ ] **Step 1: Add saveState() and restoreState() to Block**

Follow the exact pattern from `Chunk.java:70-89`. Block stores `ChunkDesc[]` the same way Chunk stores `PatternDesc[]`:

```java
public int[] saveState() {
    int[] state = new int[chunkDescs.length];
    for (int i = 0; i < chunkDescs.length; i++) {
        state[i] = chunkDescs[i].get();
    }
    return state;
}

public void restoreState(int[] state) {
    for (int i = 0; i < state.length; i++) {
        chunkDescs[i] = new ChunkDesc(state[i]);
    }
}
```

Also expose `getGridSide()` getter (needed by MutableLevel snapshot):
```java
public int getGridSide() { return gridSide; }
```

- [ ] **Step 2: Compile check**

Run: `mvn compile -q`
Expected: PASS

- [ ] **Step 3: Commit**

```
feat: add saveState/restoreState to Block (matching Chunk pattern)
```

---

## Task 2: MutableLevel — Core Class with Snapshot and Dirty Tracking

**Files:**
- Create: `src/main/java/com/openggf/level/MutableLevel.java`
- Create: `src/test/java/com/openggf/level/TestMutableLevel.java`

This is the biggest task. Build the full MutableLevel class with snapshot factory, all mutation methods, dirty tracking, and reverse lookup tables.

- [ ] **Step 1: Write unit tests for snapshot and dirty tracking**

```java
// TestMutableLevel.java
class TestMutableLevel {

    // -- Snapshot tests --
    @Test void snapshot_producesDeepCopy_patternsIndependent()
    // Create a synthetic Level with known patterns, snapshot it,
    // mutate a pattern in the copy, verify original is unchanged.

    @Test void snapshot_producesDeepCopy_chunksIndependent()
    @Test void snapshot_producesDeepCopy_blocksIndependent()
    @Test void snapshot_producesDeepCopy_mapIndependent()
    @Test void snapshot_producesDeepCopy_solidTilesIndependent()
    @Test void snapshot_producesDeepCopy_objectSpawnsIndependent()
    @Test void snapshot_producesDeepCopy_ringSpawnsIndependent()

    // -- Dirty tracking tests --
    @Test void setPattern_marksDirtyBit()
    @Test void setPatternDescInChunk_marksDirtyChunkAndTransitiveBlocks()
    @Test void setChunkInBlock_marksDirtyBlockAndTransitiveMapCells()
    @Test void setBlockInMap_marksDirtyMapCell()
    @Test void setSolidTile_marksDirtyBit()
    @Test void addObjectSpawn_setsObjectsDirtyFlag()
    @Test void removeObjectSpawn_setsObjectsDirtyFlag()
    @Test void moveObjectSpawn_setsObjectsDirtyFlag()
    @Test void addRingSpawn_setsRingsDirtyFlag()
    @Test void removeRingSpawn_setsRingsDirtyFlag()

    // -- Consume (read-once) tests --
    @Test void consumeDirtyPatterns_returnsAndClears()
    @Test void consumeObjectsDirty_returnsAndClears()

    // -- Reverse lookup tests --
    @Test void chunkToBlockReverseLookup_correctAfterSnapshot()
    @Test void blockToMapCellReverseLookup_correctAfterSnapshot()
}
```

For tests that need a `Level` to snapshot, build a minimal synthetic level using `AbstractLevel`'s protected fields (create a test subclass or use reflection). No ROM required.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestMutableLevel -q`
Expected: FAIL (MutableLevel doesn't exist yet)

- [ ] **Step 3: Implement MutableLevel**

```java
package com.openggf.level;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import java.util.*;

public class MutableLevel extends AbstractLevel {

    // Dirty tracking
    private final BitSet dirtyPatterns;
    private final BitSet dirtyChunks;
    private final BitSet dirtyBlocks;
    private final BitSet dirtyMapCells;
    private final BitSet dirtySolidTiles;
    private boolean objectsDirty;
    private boolean ringsDirty;

    // Reverse lookup tables for transitive dirtying
    private final Map<Integer, Set<Integer>> chunkToBlocks;   // chunkIdx → set of blockIdx
    private final Map<Integer, Set<Integer>> blockToMapCells;  // blockIdx → set of linearized cell

    // Mutable spawn lists (override the immutable ones from AbstractLevel)
    private final ArrayList<ObjectSpawn> mutableObjects;
    private final ArrayList<RingSpawn> mutableRings;

    private MutableLevel() {
        // Private — use snapshot() factory
    }

    public static MutableLevel snapshot(Level source) {
        MutableLevel ml = new MutableLevel();

        // Deep copy patterns
        ml.patternCount = source.getPatternCount();
        ml.patterns = new Pattern[ml.patternCount];
        for (int i = 0; i < ml.patternCount; i++) {
            ml.patterns[i] = new Pattern();
            ml.patterns[i].copyFrom(source.getPattern(i));
        }

        // Deep copy chunks via saveState/restoreState
        ml.chunkCount = source.getChunkCount();
        ml.chunks = new Chunk[ml.chunkCount];
        for (int i = 0; i < ml.chunkCount; i++) {
            ml.chunks[i] = new Chunk();
            ml.chunks[i].restoreState(source.getChunk(i).saveState());
        }

        // Deep copy blocks via saveState/restoreState
        ml.blockCount = source.getBlockCount();
        ml.blocks = new Block[ml.blockCount];
        for (int i = 0; i < ml.blockCount; i++) {
            Block src = source.getBlock(i);
            ml.blocks[i] = new Block(src.getGridSide());
            ml.blocks[i].restoreState(src.saveState());
        }

        // Deep copy solid tiles (copy height/width arrays + angle)
        ml.solidTileCount = source.getSolidTileCount();
        ml.solidTiles = new SolidTile[ml.solidTileCount];
        for (int i = 0; i < ml.solidTileCount; i++) {
            SolidTile src = source.getSolidTile(i);
            ml.solidTiles[i] = new SolidTile(
                Arrays.copyOf(src.heights, src.heights.length),
                Arrays.copyOf(src.widths, src.widths.length),
                src.getAngle());
        }

        // Deep copy map
        Map srcMap = source.getMap();
        ml.map = new Map(srcMap.getLayerCount(), srcMap.getWidth(),
                         srcMap.getHeight(), srcMap.getData());

        // Deep copy palettes
        ml.palettes = new Palette[source.getPaletteCount()];
        for (int i = 0; i < source.getPaletteCount(); i++) {
            ml.palettes[i] = source.getPalette(i).deepCopy();
        }

        // Mutable spawn lists
        ml.mutableObjects = new ArrayList<>(source.getObjects());
        ml.mutableRings = new ArrayList<>(source.getRings());
        ml.objects = ml.mutableObjects;  // AbstractLevel.objects points to mutable list
        ml.rings = ml.mutableRings;

        // Copy metadata
        ml.zoneIndex = source.getZoneIndex();
        ml.minX = source.getMinX();
        ml.maxX = source.getMaxX();
        ml.minY = source.getMinY();
        ml.maxY = source.getMaxY();
        ml.ringSpriteSheet = source.getRingSpriteSheet();

        // Init dirty tracking
        ml.dirtyPatterns = new BitSet(ml.patternCount);
        ml.dirtyChunks = new BitSet(ml.chunkCount);
        ml.dirtyBlocks = new BitSet(ml.blockCount);
        ml.dirtyMapCells = new BitSet(srcMap.getLayerCount() * srcMap.getWidth() * srcMap.getHeight());
        ml.dirtySolidTiles = new BitSet(ml.solidTileCount);

        // Build reverse lookups
        ml.chunkToBlocks = buildChunkToBlocksMap(ml.blocks);
        ml.blockToMapCells = buildBlockToMapCellsMap(ml.map);

        return ml;
    }

    // -- Mutation methods (each marks dirty) --

    public void setPattern(int index, Pattern pattern) {
        patterns[index] = pattern;
        dirtyPatterns.set(index);
    }

    public void setPatternDescInChunk(int chunkIndex, int px, int py, PatternDesc desc) {
        chunks[chunkIndex].getPatternDescs()[py * 2 + px] = desc;  // or setter method
        dirtyChunks.set(chunkIndex);
        // Transitive: dirty all blocks referencing this chunk
        Set<Integer> affectedBlocks = chunkToBlocks.getOrDefault(chunkIndex, Set.of());
        for (int blockIdx : affectedBlocks) {
            dirtyBlocks.set(blockIdx);
            dirtyTransitiveMapCells(blockIdx);
        }
    }

    public void setChunkInBlock(int blockIndex, int cx, int cy, ChunkDesc desc) {
        blocks[blockIndex].setChunkDesc(cx, cy, desc);  // needs setter on Block
        dirtyBlocks.set(blockIndex);
        dirtyTransitiveMapCells(blockIndex);
    }

    public void setBlockInMap(int layer, int bx, int by, int blockIndex) {
        map.setValue(layer, bx, by, (byte) blockIndex);
        int cellIdx = layer * map.getWidth() * map.getHeight() + by * map.getWidth() + bx;
        dirtyMapCells.set(cellIdx);
    }

    public void setSolidTile(int index, SolidTile tile) {
        solidTiles[index] = tile;
        dirtySolidTiles.set(index);
    }

    public void addObjectSpawn(ObjectSpawn spawn) {
        mutableObjects.add(spawn);
        objectsDirty = true;
    }

    public void removeObjectSpawn(ObjectSpawn spawn) {
        mutableObjects.remove(spawn);
        objectsDirty = true;
    }

    public void moveObjectSpawn(ObjectSpawn oldSpawn, ObjectSpawn newSpawn) {
        int idx = mutableObjects.indexOf(oldSpawn);
        if (idx >= 0) mutableObjects.set(idx, newSpawn);
        objectsDirty = true;
    }

    public void addRingSpawn(RingSpawn spawn) {
        mutableRings.add(spawn);
        ringsDirty = true;
    }

    public void removeRingSpawn(RingSpawn spawn) {
        mutableRings.remove(spawn);
        ringsDirty = true;
    }

    // -- Dirty consumption (read-once) --

    public BitSet consumeDirtyPatterns() {
        BitSet copy = (BitSet) dirtyPatterns.clone();
        dirtyPatterns.clear();
        return copy;
    }

    public BitSet consumeDirtyBlocks() {
        BitSet copy = (BitSet) dirtyBlocks.clone();
        dirtyBlocks.clear();
        return copy;
    }

    public BitSet consumeDirtyMapCells() {
        BitSet copy = (BitSet) dirtyMapCells.clone();
        dirtyMapCells.clear();
        return copy;
    }

    public BitSet consumeDirtySolidTiles() {
        BitSet copy = (BitSet) dirtySolidTiles.clone();
        dirtySolidTiles.clear();
        return copy;
    }

    public boolean consumeObjectsDirty() {
        boolean was = objectsDirty;
        objectsDirty = false;
        return was;
    }

    public boolean consumeRingsDirty() {
        boolean was = ringsDirty;
        ringsDirty = false;
        return was;
    }

    // -- Helpers --

    private void dirtyTransitiveMapCells(int blockIndex) {
        Set<Integer> cells = blockToMapCells.getOrDefault(blockIndex, Set.of());
        for (int cellIdx : cells) {
            dirtyMapCells.set(cellIdx);
        }
    }

    private static Map<Integer, Set<Integer>> buildChunkToBlocksMap(Block[] blocks) {
        // Scan all blocks, record which chunk indices each block references
        ...
    }

    private static Map<Integer, Set<Integer>> buildBlockToMapCellsMap(Map map) {
        // Scan all map cells, record which block index each cell references
        ...
    }
}
```

**Implementation notes:**
- `Pattern.copyFrom(Pattern src)` may need to be added if it doesn't exist — copies `pixels[]` array.
- `Block` needs `setChunkDesc(int cx, int cy, ChunkDesc desc)` setter — add alongside saveState/restoreState in Task 1.
- `SolidTile` constructor that takes `(byte[] heights, byte[] widths, byte angle)` may need to be added.
- `Palette.deepCopy()` may need to be added — copies the color array.
- `Map` constructor already copies the data array, so `new Map(layers, w, h, src.getData())` is a deep copy.

Check each data class and add missing copy/setter methods as needed.

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=TestMutableLevel -q`
Expected: All pass

- [ ] **Step 5: Commit**

```
feat: add MutableLevel with snapshot, mutation, and dirty-region tracking
```

---

## Task 3: LevelManager — setLevel, processDirtyRegions, reinitAnimatedContent

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

- [ ] **Step 1: Extract reinitAnimatedContent()**

The animated pattern/palette managers are initialized in `initAnimatedContent()` (line 635). Extract the core logic (without the level-load guards) into a `reinitAnimatedContent()` method that can be called from both `initAnimatedContent()` and the new `setLevel()`.

- [ ] **Step 2: Add setLevel()**

```java
public void setLevel(Level level) {
    this.level = level;
    reinitAnimatedContent();
    invalidateForegroundTilemap();
}
```

- [ ] **Step 3: Add processDirtyRegions()**

```java
public void processDirtyRegions() {
    if (!(level instanceof MutableLevel ml)) return;

    BitSet dirtyPatterns = ml.consumeDirtyPatterns();
    if (!dirtyPatterns.isEmpty()) {
        graphicsManager.reuploadDirtyPatterns(dirtyPatterns, ml);
    }

    BitSet dirtyBlocks = ml.consumeDirtyBlocks();
    BitSet dirtyMapCells = ml.consumeDirtyMapCells();
    if (!dirtyBlocks.isEmpty() || !dirtyMapCells.isEmpty()) {
        if (tilemapManager != null) {
            tilemapManager.rebuildDirtyRegions(dirtyBlocks, dirtyMapCells, ml);
        }
    }

    if (ml.consumeObjectsDirty() && objectManager != null) {
        objectManager.resyncSpawnList(ml.getObjects());
    }

    if (ml.consumeRingsDirty() && ringManager != null) {
        ringManager.resyncSpawnList(ml.getRings());
    }
}
```

- [ ] **Step 4: Compile check**

Run: `mvn compile -q`
Expected: FAIL — `reuploadDirtyPatterns`, `rebuildDirtyRegions`, `resyncSpawnList` don't exist yet. That's OK — they're added in Tasks 4-6.

- [ ] **Step 5: Commit (WIP — compiles after Tasks 4-6)**

```
feat(wip): add setLevel, processDirtyRegions, reinitAnimatedContent to LevelManager
```

---

## Task 4: GraphicsManager — reuploadDirtyPatterns

**Files:**
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`

- [ ] **Step 1: Add reuploadDirtyPatterns method**

```java
public void reuploadDirtyPatterns(BitSet dirtyIndices, Level level) {
    if (headlessMode || !glInitialized) return;
    for (int i = dirtyIndices.nextSetBit(0); i >= 0; i = dirtyIndices.nextSetBit(i + 1)) {
        if (i < level.getPatternCount()) {
            updatePatternTexture(level.getPattern(i), i);
        }
    }
}
```

This reuses the existing `updatePatternTexture()` method which already handles GPU texture upload for a single pattern.

- [ ] **Step 2: Compile check**

Run: `mvn compile -q`
Expected: PASS (this method is self-contained)

- [ ] **Step 3: Commit**

```
feat: add reuploadDirtyPatterns to GraphicsManager for incremental pattern updates
```

---

## Task 5: ObjectManager — resyncSpawnList

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`

- [ ] **Step 1: Add resyncSpawnList method**

`ObjectManager.Placement` wraps the spawn list. When the editor modifies spawns, we need to update the placement's internal list. The simplest approach: replace the placement's spawn list and let `syncActiveSpawns()` (which runs every frame) handle instantiation/destruction diffs.

```java
public void resyncSpawnList(List<ObjectSpawn> newSpawns) {
    placement.replaceSpawns(newSpawns);
}
```

This requires adding `replaceSpawns()` to the `Placement` inner class (extends `AbstractPlacementManager`). Check `AbstractPlacementManager` for how spawns are stored — likely a sorted list with an index map. `replaceSpawns` should:
1. Replace the internal spawn list
2. Rebuild the sorted index
3. Clear the "remembered" set (destroyed objects should be respawnable after edit)
4. Let `syncActiveSpawns()` handle the rest on next frame

- [ ] **Step 2: Compile check**

Run: `mvn compile -q`
Expected: PASS

- [ ] **Step 3: Commit**

```
feat: add resyncSpawnList to ObjectManager for editor spawn changes
```

---

## Task 6: RingManager — resyncSpawnList

**Files:**
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java`

- [ ] **Step 1: Add resyncSpawnList method**

Per the spec, ring collection state is reset on resync (acceptable in editor mode). The simplest approach: replace the placement's internal data and reset collection state.

```java
public void resyncSpawnList(List<RingSpawn> newSpawns) {
    placement.replaceSpawns(newSpawns);
    // Collection state (BitSet collected, sparkleStartFrames) is reset
    // by replaceSpawns — all rings become uncollected.
}
```

`RingPlacement.replaceSpawns()` (extends `AbstractPlacementManager`):
1. Replace spawn list
2. Rebuild sorted index
3. Reset `collected` BitSet (new size)
4. Reset `sparkleStartFrames` array (new size)

- [ ] **Step 2: Compile check**

Run: `mvn compile -q`
Expected: PASS

- [ ] **Step 3: Commit**

```
feat: add resyncSpawnList to RingManager for editor ring changes
```

---

## Task 7: GameLoop — Wire processDirtyRegions

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`

- [ ] **Step 1: Call processDirtyRegions at frame start**

In `GameLoop.step()`, add `levelManager.processDirtyRegions()` at the start of the level update path (after input processing, before physics/sprites):

```java
// In step(), inside the LEVEL game mode block, before sprite/physics updates:
if (currentGameMode == GameMode.LEVEL || currentGameMode == GameMode.TITLE_CARD) {
    levelManager.processDirtyRegions();  // ← add this line
    // ... existing level update code
}
```

This is a no-op when the level is not a MutableLevel (the instanceof check returns early).

- [ ] **Step 2: Full test suite**

Run: `mvn test -q`
Expected: All 1896+ tests pass (processDirtyRegions is a no-op for non-MutableLevel)

- [ ] **Step 3: Commit**

```
feat: wire processDirtyRegions into GameLoop frame update
```

---

## Task 8: Integration Test — Round-Trip Verification

**Files:**
- Modify: `src/test/java/com/openggf/level/TestMutableLevel.java`

- [ ] **Step 1: Add round-trip test (requires ROM)**

If ROM is available, load a real level, snapshot to MutableLevel, verify the snapshot satisfies the Level interface identically:

```java
@Test void roundTrip_snapshotMatchesOriginal() {
    // Load EHZ1 from ROM
    // Snapshot to MutableLevel
    // Compare: patternCount, chunkCount, blockCount, map dimensions
    // Verify getPattern(0).getPixel(0,0) matches
    // Verify getObjects().size() matches
    // Verify getRings().size() matches
}
```

Mark as `@DisabledIf` ROM is absent.

- [ ] **Step 2: Add mutation + dirty consumption test**

```java
@Test void editPattern_dirtyProcessing_reuploadsOnlyChanged() {
    // Snapshot a level
    // Modify pattern 5
    // Call consumeDirtyPatterns()
    // Verify bit 5 is set, all others clear
    // Call again — verify empty (read-once)
}
```

- [ ] **Step 3: Run tests**

Run: `mvn test -Dtest=TestMutableLevel -q`
Expected: All pass

- [ ] **Step 4: Commit**

```
test: add round-trip and dirty-processing integration tests for MutableLevel
```

---

## Execution Notes

**Total scope:** ~8-10 files across 8 tasks. Tasks 1-2 are the core work. Tasks 3-7 are small additions to existing files. Task 8 is testing.

**Dependency order:** Task 1 (Block saveState) must come before Task 2 (MutableLevel). Tasks 3-6 can be done in parallel but all must be done before Task 7. Task 8 is last.

**Key risk:** The `snapshot()` deep copy. Each data type (Pattern, Chunk, Block, SolidTile, Map, Palette) needs a verified copy mechanism. Missing a shared reference means mutations leak between source and copy. The unit tests in Task 2 specifically test independence of each array.

**What this does NOT build:** The editor UI, editor input handling, or editor tool palette. Those are Phase 4. This phase builds the data layer the editor will operate on.
