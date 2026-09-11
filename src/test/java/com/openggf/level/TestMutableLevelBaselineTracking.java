package com.openggf.level;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMutableLevelBaselineTracking {

    @Test
    void snapshotStartsWithNoModifiedSinceBaselineBits() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());

        assertTrue(level.modifiedBlocksSinceBaseline().isEmpty());
        assertTrue(level.modifiedChunksSinceBaseline().isEmpty());
        assertTrue(level.modifiedMapCellsSinceBaseline().isEmpty());
        assertFalse(level.isModifiedSinceLastSave());
    }

    @Test
    void mutatingBlockChunkAndMapCellSetsPersistentDeltaBits() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());

        level.setChunkInBlock(1, 0, 0, new ChunkDesc(2));
        level.setPatternDescInChunk(2, 0, 0, new PatternDesc(7));
        level.setBlockInMap(1, 2, 1, 1);

        assertTrue(level.modifiedBlocksSinceBaseline().get(1));
        assertTrue(level.modifiedChunksSinceBaseline().get(2));
        assertTrue(level.modifiedMapCellsSinceBaseline().get(1 * 12 + 1 * 4 + 2));
        assertTrue(level.isModifiedSinceLastSave());

        level.consumeDirtyBlocks();
        level.consumeDirtyChunks();
        level.consumeDirtyMapCells();

        assertTrue(level.modifiedBlocksSinceBaseline().get(1));
        assertTrue(level.modifiedChunksSinceBaseline().get(2));
        assertTrue(level.modifiedMapCellsSinceBaseline().get(1 * 12 + 1 * 4 + 2));
    }

    @Test
    void savedMarkerClearsOnlySaveDirtyFlag() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());

        level.setBlockInMap(0, 1, 1, 2);
        level.markSaved();

        assertFalse(level.isModifiedSinceLastSave());
        assertTrue(level.modifiedMapCellsSinceBaseline().get(1 * 4 + 1));
    }

    @Test
    void revertingMapCellToBaselineClearsPersistentDeltaBitButKeepsSessionDirty() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());

        level.setBlockInMap(0, 1, 1, 2);
        level.setBlockInMap(0, 1, 1, 0);

        assertFalse(level.modifiedMapCellsSinceBaseline().get(1 * 4 + 1));
        assertTrue(level.modifiedMapCellsSinceBaseline().isEmpty());
        assertTrue(level.isModifiedSinceLastSave());
    }

    @Test
    void revertingBlockAndChunkToBaselineClearsPersistentDeltaBits() {
        MutableLevel level = MutableLevel.snapshot(new SyntheticLevel());

        level.setChunkInBlock(1, 0, 0, new ChunkDesc(2));
        level.setChunkInBlock(1, 0, 0, new ChunkDesc(0));
        level.setPatternDescInChunk(2, 0, 0, new PatternDesc(7));
        level.setPatternDescInChunk(2, 0, 0, new PatternDesc(0));

        assertFalse(level.modifiedBlocksSinceBaseline().get(1));
        assertFalse(level.modifiedChunksSinceBaseline().get(2));
        assertTrue(level.modifiedBlocksSinceBaseline().isEmpty());
        assertTrue(level.modifiedChunksSinceBaseline().isEmpty());
        assertTrue(level.isModifiedSinceLastSave());
    }

    private static final class SyntheticLevel extends AbstractLevel {
        private SyntheticLevel() {
            super(0);
            patternCount = 4;
            patterns = new Pattern[patternCount];
            for (int i = 0; i < patternCount; i++) {
                patterns[i] = new Pattern();
            }
            chunkCount = 4;
            chunks = new Chunk[chunkCount];
            for (int i = 0; i < chunkCount; i++) {
                chunks[i] = new Chunk();
            }
            blockCount = 3;
            blocks = new Block[blockCount];
            for (int i = 0; i < blockCount; i++) {
                blocks[i] = new Block(2);
            }
            solidTileCount = 0;
            solidTiles = new SolidTile[0];
            map = new Map(2, 4, 3);
            palettes = new Palette[PALETTE_COUNT];
            for (int i = 0; i < PALETTE_COUNT; i++) {
                palettes[i] = new Palette();
            }
            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = 128;
            minY = 0;
            maxY = 96;
        }

        @Override
        public int getChunksPerBlockSide() {
            return 2;
        }

        @Override
        public int getBlockPixelSize() {
            return 32;
        }

        @Override
        public List<ObjectSpawn> getObjects() {
            return List.of();
        }

        @Override
        public List<RingSpawn> getRings() {
            return List.of();
        }
    }
}
