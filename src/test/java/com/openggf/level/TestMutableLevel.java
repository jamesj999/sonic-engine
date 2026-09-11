package com.openggf.level;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MutableLevel snapshot, mutation, and dirty-region tracking.
 * All tests use synthetic data -- no ROM required.
 */
class TestMutableLevel {

    // ===== Helper: build a minimal synthetic Level =====

    /**
     * Creates a tiny synthetic level for testing.
     * 2 patterns, 2 chunks, 2 blocks (8x8 grid), 1 solid tile,
     * 2-layer 4x4 map, 1 object spawn, 1 ring spawn.
     */
    private static SyntheticLevel createSyntheticLevel() {
        return new SyntheticLevel();
    }

    /** Minimal AbstractLevel subclass for testing with synthetic data. */
    private static class SyntheticLevel extends AbstractLevel {
        SyntheticLevel() {
            super(0);

            // Patterns
            patternCount = 2;
            patterns = new Pattern[patternCount];
            for (int i = 0; i < patternCount; i++) {
                patterns[i] = new Pattern();
                patterns[i].setPixel(0, 0, (byte) (i + 1));
            }

            // Chunks: each chunk references pattern 0 via a PatternDesc
            chunkCount = 2;
            chunks = new Chunk[chunkCount];
            for (int i = 0; i < chunkCount; i++) {
                chunks[i] = new Chunk();
                // Set first pattern desc to a known value
                int[] state = new int[Chunk.PATTERNS_PER_CHUNK + 2];
                state[0] = i + 10; // pattern desc raw value
                chunks[i].restoreState(state);
            }

            // Blocks: each block 8x8, first chunk desc references chunk 0 and 1
            blockCount = 2;
            blocks = new Block[blockCount];
            for (int i = 0; i < blockCount; i++) {
                blocks[i] = new Block(8);
                if (i == 1) {
                    for (int y = 0; y < blocks[i].getGridSide(); y++) {
                        for (int x = 0; x < blocks[i].getGridSide(); x++) {
                            blocks[i].setChunkDesc(x, y, new ChunkDesc(1));
                        }
                    }
                } else {
                    // Set first ChunkDesc to reference chunk index i
                    blocks[i].setChunkDesc(0, 0, new ChunkDesc(i));
                }
            }

            // Solid tiles
            solidTileCount = 1;
            solidTiles = new SolidTile[solidTileCount];
            byte[] heights = new byte[SolidTile.TILE_SIZE_IN_ROM];
            byte[] widths = new byte[SolidTile.TILE_SIZE_IN_ROM];
            heights[0] = 5;
            widths[0] = 3;
            solidTiles[0] = new SolidTile(0, heights, widths, (byte) 42);

            // Map: 2 layers, 4x4
            map = new Map(2, 4, 4);
            map.setValue(0, 0, 0, (byte) 0);
            map.setValue(0, 1, 0, (byte) 1);
            map.setValue(1, 0, 0, (byte) 0);

            // Palettes
            palettes = new Palette[PALETTE_COUNT];
            for (int i = 0; i < PALETTE_COUNT; i++) {
                palettes[i] = new Palette();
                palettes[i].getColor(0).r = (byte) (i * 10);
            }

            // Object spawns
            objects = List.of(
                    new ObjectSpawn(100, 200, 0x01, 0, 0, false, 0)
            );

            // Ring spawns
            rings = List.of(
                    new RingSpawn(150, 250)
            );

            // Boundaries
            minX = 0;
            maxX = 512;
            minY = 0;
            maxY = 512;

            // No ring sprite sheet needed for tests
            ringSpriteSheet = null;
        }
    }

    // ===== Snapshot deep-copy tests =====

    @Test
    void snapshot_producesDeepCopy_patternsIndependent() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml = MutableLevel.snapshot(source);

        // Verify initial value matches
        assertEquals(source.getPattern(0).getPixel(0, 0), ml.getPattern(0).getPixel(0, 0));

        // Mutate the copy
        ml.getPattern(0).setPixel(0, 0, (byte) 99);

        // Source should be unaffected
        assertNotEquals(99, source.getPattern(0).getPixel(0, 0));
    }

    @Test
    void snapshot_producesDeepCopy_chunksIndependent() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml = MutableLevel.snapshot(source);

        int originalDesc = source.getChunk(0).getPatternDesc(0, 0).get();
        assertEquals(originalDesc, ml.getChunk(0).getPatternDesc(0, 0).get());

        // Mutate the copy's chunk via setPatternDesc
        ml.getChunk(0).setPatternDesc(0, 0, new PatternDesc(999));

        // Source should be unaffected
        assertEquals(originalDesc, source.getChunk(0).getPatternDesc(0, 0).get());
    }

    @Test
    void snapshot_producesDeepCopy_blocksIndependent() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml = MutableLevel.snapshot(source);

        int originalChunkIdx = source.getBlock(0).getChunkDesc(0, 0).getChunkIndex();
        assertEquals(originalChunkIdx, ml.getBlock(0).getChunkDesc(0, 0).getChunkIndex());

        // Mutate the copy
        ml.getBlock(0).setChunkDesc(0, 0, new ChunkDesc(999));

        // Source should be unaffected
        assertEquals(originalChunkIdx, source.getBlock(0).getChunkDesc(0, 0).getChunkIndex());
    }

    @Test
    void snapshot_producesDeepCopy_mapIndependent() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml = MutableLevel.snapshot(source);

        byte original = source.getMap().getValue(0, 0, 0);
        assertEquals(original, ml.getMap().getValue(0, 0, 0));

        // Mutate the copy
        ml.getMap().setValue(0, 0, 0, (byte) 77);

        // Source should be unaffected
        assertEquals(original, source.getMap().getValue(0, 0, 0));
    }

    @Test
    void snapshot_producesDeepCopy_solidTilesIndependent() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml = MutableLevel.snapshot(source);

        assertEquals(5, ml.getSolidTile(0).heights[0]);

        // Mutate the copy's height array
        ml.getSolidTile(0).heights[0] = 99;

        // Source should be unaffected
        assertEquals(5, source.getSolidTile(0).heights[0]);
    }

    @Test
    void snapshot_producesDeepCopy_objectSpawnsIndependent() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml = MutableLevel.snapshot(source);

        assertEquals(1, ml.getObjects().size());

        // Add to the copy
        ml.addObjectSpawn(new ObjectSpawn(300, 400, 0x02, 0, 0, false, 0));

        // Source should be unaffected
        assertEquals(1, source.getObjects().size());
        assertEquals(2, ml.getObjects().size());
    }

    @Test
    void snapshot_producesDeepCopy_ringSpawnsIndependent() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml = MutableLevel.snapshot(source);

        assertEquals(1, ml.getRings().size());

        ml.addRingSpawn(new RingSpawn(500, 600));

        assertEquals(1, source.getRings().size());
        assertEquals(2, ml.getRings().size());
    }

    @Test
    void snapshot_preservesMetadata() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml = MutableLevel.snapshot(source);

        assertEquals(source.getZoneIndex(), ml.getZoneIndex());
        assertEquals(source.getMinX(), ml.getMinX());
        assertEquals(source.getMaxX(), ml.getMaxX());
        assertEquals(source.getMinY(), ml.getMinY());
        assertEquals(source.getMaxY(), ml.getMaxY());
        assertEquals(source.getPatternCount(), ml.getPatternCount());
        assertEquals(source.getChunkCount(), ml.getChunkCount());
        assertEquals(source.getBlockCount(), ml.getBlockCount());
        assertEquals(source.getSolidTileCount(), ml.getSolidTileCount());
    }

    @Test
    void snapshot_producesDeepCopy_palettesIndependent() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml = MutableLevel.snapshot(source);

        byte originalR = source.getPalette(0).getColor(0).r;
        assertEquals(originalR, ml.getPalette(0).getColor(0).r);

        ml.getPalette(0).getColor(0).r = (byte) 0xFF;

        assertEquals(originalR, source.getPalette(0).getColor(0).r);
    }

    // ===== Dirty tracking tests =====

    @Test
    void setPattern_marksDirtyBit() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ml.setPattern(1, new Pattern());

        BitSet dirty = ml.consumeDirtyPatterns();
        assertTrue(dirty.get(1));
        assertFalse(dirty.get(0));
    }

    @Test
    void setPatternDescInChunk_marksDirtyChunkAndTransitiveBlocks() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        // Chunk 0 is referenced by block 0 (at position 0,0)
        ml.setPatternDescInChunk(0, 0, 0, new PatternDesc(555));

        BitSet dirtyChunks = ml.consumeDirtyChunks();
        assertTrue(dirtyChunks.get(0));

        // Block 0 references chunk 0, so it should be transitively dirty
        BitSet dirtyBlocks = ml.consumeDirtyBlocks();
        assertTrue(dirtyBlocks.get(0));
    }

    @Test
    void setChunkInBlock_marksDirtyBlockAndTransitiveMapCells() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        // Block 0 is at map position (0,0) layer 0 and (0,0) layer 1
        ml.setChunkInBlock(0, 0, 0, new ChunkDesc(777));

        BitSet dirtyBlocks = ml.consumeDirtyBlocks();
        assertTrue(dirtyBlocks.get(0));

        BitSet dirtyMapCells = ml.consumeDirtyMapCells();
        // Block 0 is placed at (0,0) in layer 0 and (0,0) in layer 1
        assertFalse(dirtyMapCells.isEmpty());
    }

    @Test
    void setChunkInBlock_updatesReverseLookupForFutureChunkDirtying() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ml.setChunkInBlock(1, 0, 0, new ChunkDesc(0));
        ml.consumeDirtyBlocks();
        ml.consumeDirtyMapCells();

        ml.setPatternDescInChunk(0, 0, 0, new PatternDesc(555));

        BitSet dirtyBlocks = ml.consumeDirtyBlocks();
        BitSet dirtyMapCells = ml.consumeDirtyMapCells();
        assertTrue(dirtyBlocks.get(1));
        assertTrue(dirtyMapCells.get(1));
    }

    @Test
    void setBlockInMap_marksDirtyMapCell() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ml.setBlockInMap(0, 2, 1, 1);

        BitSet dirty = ml.consumeDirtyMapCells();
        // Cell index: layer=0, w=4, h=4 -> 0*4*4 + 1*4 + 2 = 6
        assertTrue(dirty.get(6));
    }

    @Test
    void setBlockInMap_updatesReverseLookupForFutureBlockDirtying() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ml.setBlockInMap(0, 2, 1, 1);
        ml.consumeDirtyMapCells();

        ml.setChunkInBlock(1, 0, 0, new ChunkDesc(777));

        BitSet dirtyMapCells = ml.consumeDirtyMapCells();
        assertTrue(dirtyMapCells.get(6));
    }

    @Test
    void setSolidTile_marksDirtyBit() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        byte[] h = new byte[SolidTile.TILE_SIZE_IN_ROM];
        byte[] w = new byte[SolidTile.TILE_SIZE_IN_ROM];
        ml.setSolidTile(0, new SolidTile(0, h, w, (byte) 0));

        BitSet dirty = ml.consumeDirtySolidTiles();
        assertTrue(dirty.get(0));
    }

    @Test
    void addObjectSpawn_setsObjectsDirtyFlag() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ml.addObjectSpawn(new ObjectSpawn(10, 20, 0x05, 0, 0, false, 0));

        assertTrue(ml.consumeObjectsDirty());
    }

    @Test
    void removeObjectSpawn_setsObjectsDirtyFlag() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ObjectSpawn spawn = ml.getObjects().get(0);
        ml.removeObjectSpawn(spawn);

        assertTrue(ml.consumeObjectsDirty());
        assertEquals(0, ml.getObjects().size());
    }

    @Test
    void moveObjectSpawn_setsObjectsDirtyFlag() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ObjectSpawn oldSpawn = ml.getObjects().get(0);
        ObjectSpawn newSpawn = new ObjectSpawn(999, 888, oldSpawn.objectId(),
                oldSpawn.subtype(), oldSpawn.renderFlags(), oldSpawn.respawnTracked(),
                oldSpawn.rawYWord());
        ml.moveObjectSpawn(oldSpawn, newSpawn);

        assertTrue(ml.consumeObjectsDirty());
        assertEquals(999, ml.getObjects().get(0).x());
    }

    @Test
    void addRingSpawn_setsRingsDirtyFlag() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ml.addRingSpawn(new RingSpawn(10, 20));

        assertTrue(ml.consumeRingsDirty());
    }

    @Test
    void removeRingSpawn_setsRingsDirtyFlag() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        RingSpawn ring = ml.getRings().get(0);
        ml.removeRingSpawn(ring);

        assertTrue(ml.consumeRingsDirty());
        assertEquals(0, ml.getRings().size());
    }

    // ===== Consume (read-once) tests =====

    @Test
    void consumeDirtyPatterns_returnsAndClears() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ml.setPattern(0, new Pattern());
        BitSet first = ml.consumeDirtyPatterns();
        assertTrue(first.get(0));

        BitSet second = ml.consumeDirtyPatterns();
        assertTrue(second.isEmpty(), "Second consume should be empty (read-once)");
    }

    @Test
    void consumeObjectsDirty_returnsAndClears() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ml.addObjectSpawn(new ObjectSpawn(1, 2, 3, 0, 0, false, 0));
        assertTrue(ml.consumeObjectsDirty());
        assertFalse(ml.consumeObjectsDirty(), "Second consume should be false (read-once)");
    }

    @Test
    void consumeRingsDirty_returnsAndClears() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ml.addRingSpawn(new RingSpawn(1, 2));
        assertTrue(ml.consumeRingsDirty());
        assertFalse(ml.consumeRingsDirty(), "Second consume should be false (read-once)");
    }

    // ===== Reverse lookup tests =====

    @Test
    void chunkToBlockReverseLookup_correctAfterSnapshot() {
        SyntheticLevel source = createSyntheticLevel();
        // In our synthetic level, block 0 references chunk 0 and block 1 references chunk 1
        Block[] blocks = new Block[source.getBlockCount()];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = source.getBlock(i);
        }

        java.util.Map<Integer, Set<Integer>> lookup = MutableLevel.buildChunkToBlocksMap(blocks);
        assertTrue(lookup.containsKey(0));
        assertTrue(lookup.get(0).contains(0));
    }

    @Test
    void blockToMapCellReverseLookup_correctAfterSnapshot() {
        SyntheticLevel source = createSyntheticLevel();
        // Map cell (0,0) in layer 0 has block 0, cell (1,0) has block 1
        java.util.Map<Integer, Set<Integer>> lookup =
                MutableLevel.buildBlockToMapCellsMap(source.getMap());

        assertTrue(lookup.containsKey(0));
        // Block 0 is at position (0,0) layer 0 => cell 0, and (0,0) layer 1 => cell 16
        assertTrue(lookup.get(0).contains(0));
    }

    // ===== Integration: mutation + dirty consumption =====

    @Test
    void editPattern_dirtyProcessing_reuploadsOnlyChanged() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        // Modify pattern 1
        Pattern newPat = new Pattern();
        newPat.setPixel(0, 0, (byte) 42);
        ml.setPattern(1, newPat);

        // Consume
        BitSet dirty = ml.consumeDirtyPatterns();
        assertTrue(dirty.get(1));
        assertFalse(dirty.get(0));
        assertEquals(1, dirty.cardinality());

        // Consume again -- should be empty
        BitSet second = ml.consumeDirtyPatterns();
        assertTrue(second.isEmpty());
    }

    @Test
    void multipleEdits_accumulateDirtyBits() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        ml.setPattern(0, new Pattern());
        ml.setPattern(1, new Pattern());
        ml.setBlockInMap(0, 3, 3, 1);

        BitSet dirtyPats = ml.consumeDirtyPatterns();
        assertEquals(2, dirtyPats.cardinality());

        BitSet dirtyCells = ml.consumeDirtyMapCells();
        assertFalse(dirtyCells.isEmpty());
    }

    // ===== Round-trip and edge-case tests =====

    @Test
    void doubleSnapshot_producesIndependentCopies() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml1 = MutableLevel.snapshot(source);
        MutableLevel ml2 = MutableLevel.snapshot(source);

        // Mutate ml1
        ml1.getPattern(0).setPixel(0, 0, (byte) 77);
        ml1.addObjectSpawn(new ObjectSpawn(1, 2, 3, 0, 0, false, 0));

        // ml2 and source should be unaffected
        assertNotEquals(77, ml2.getPattern(0).getPixel(0, 0));
        assertNotEquals(77, source.getPattern(0).getPixel(0, 0));
        assertEquals(1, ml2.getObjects().size());
        assertEquals(1, source.getObjects().size());
    }

    @Test
    void snapshotOfMutableLevel_producesIndependentCopy() {
        SyntheticLevel source = createSyntheticLevel();
        MutableLevel ml1 = MutableLevel.snapshot(source);

        // Mutate ml1
        ml1.getPattern(0).setPixel(0, 0, (byte) 55);

        // Snapshot the mutable level itself
        MutableLevel ml2 = MutableLevel.snapshot(ml1);

        // Verify ml2 has the mutated value
        assertEquals(55, ml2.getPattern(0).getPixel(0, 0));

        // Further mutation of ml2 doesn't affect ml1
        ml2.getPattern(0).setPixel(0, 0, (byte) 99);
        assertEquals(55, ml1.getPattern(0).getPixel(0, 0));
    }

    @Test
    void noMutations_allDirtyRegionsEmpty() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        assertTrue(ml.consumeDirtyPatterns().isEmpty());
        assertTrue(ml.consumeDirtyChunks().isEmpty());
        assertTrue(ml.consumeDirtyBlocks().isEmpty());
        assertTrue(ml.consumeDirtyMapCells().isEmpty());
        assertTrue(ml.consumeDirtySolidTiles().isEmpty());
        assertFalse(ml.consumeObjectsDirty());
        assertFalse(ml.consumeRingsDirty());
    }

    @Test
    void transitiveChunkDirty_propagatesToBlocksAndMapCells() {
        // Build a level where chunk 0 is used by block 0, and block 0 is at map (0,0)
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        // Edit chunk 0's pattern
        ml.setPatternDescInChunk(0, 0, 0, new PatternDesc(42));

        // Chunk 0 should be dirty
        BitSet dirtyChunks = ml.consumeDirtyChunks();
        assertTrue(dirtyChunks.get(0));

        // Block 0 should be transitively dirty (references chunk 0)
        BitSet dirtyBlocks = ml.consumeDirtyBlocks();
        assertTrue(dirtyBlocks.get(0));

        // Map cells referencing block 0 should be transitively dirty
        BitSet dirtyMapCells = ml.consumeDirtyMapCells();
        assertFalse(dirtyMapCells.isEmpty());
    }

    @Test
    void levelInterface_satisfied_byMutableLevel() {
        MutableLevel ml = MutableLevel.snapshot(createSyntheticLevel());

        // Verify all Level interface methods work
        Level level = ml; // implicit upcast
        assertNotNull(level.getMap());
        assertEquals(2, level.getPatternCount());
        assertEquals(2, level.getChunkCount());
        assertEquals(2, level.getBlockCount());
        assertEquals(1, level.getSolidTileCount());
        assertEquals(4, level.getPaletteCount());
        assertFalse(level.getObjects().isEmpty());
        assertFalse(level.getRings().isEmpty());
    }
}


