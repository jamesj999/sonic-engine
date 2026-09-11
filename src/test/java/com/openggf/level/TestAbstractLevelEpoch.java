package com.openggf.level;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for snapshot epoch tracking in AbstractLevel.
 */
class TestAbstractLevelEpoch {

    /**
     * Minimal concrete stub for testing AbstractLevel without a full level load.
     */
    static class StubLevel extends AbstractLevel {
        StubLevel() {
            super(0);
            // Initialize required fields to non-null values
            this.palettes = new Palette[PALETTE_COUNT];
            this.patterns = new Pattern[256];
            this.chunks = new Chunk[256];
            this.blocks = new Block[256];
            this.solidTiles = new SolidTile[256];
            this.map = new Map(2, 256, 256);
            this.objects = new ArrayList<>();
            this.rings = new ArrayList<>();
            this.patternCount = 256;
            this.chunkCount = 256;
            this.blockCount = 256;
            this.solidTileCount = 256;
            this.minX = 0;
            this.maxX = 1024;
            this.minY = 0;
            this.maxY = 1024;
        }
    }

    @Test
    void epochStartsAtZero() {
        AbstractLevel level = new StubLevel();
        assertEquals(0L, level.currentEpoch());
    }

    @Test
    void bumpEpochIncrementsValue() {
        AbstractLevel level = new StubLevel();
        level.bumpEpoch();
        assertEquals(1L, level.currentEpoch());
        level.bumpEpoch();
        assertEquals(2L, level.currentEpoch());
        level.bumpEpoch();
        assertEquals(3L, level.currentEpoch());
    }

    @Test
    void epochDoesNotWrapToNegativeUnderReasonableBumps() {
        AbstractLevel level = new StubLevel();
        for (int i = 0; i < 1000; i++) {
            level.bumpEpoch();
        }
        assertEquals(1000L, level.currentEpoch());
        // Verify it's positive and monotonically increasing
        for (int i = 0; i < 1000; i++) {
            long before = level.currentEpoch();
            level.bumpEpoch();
            long after = level.currentEpoch();
            assertEquals(before + 1, after);
            assertEquals(1001 + i, after);
        }
    }

    @Test
    void blocksReferenceReturnsLiveArray() {
        AbstractLevel level = new StubLevel();
        Block[] ref1 = level.blocksReference();
        Block[] ref2 = level.blocksReference();
        // Same reference (not a copy)
        assert ref1 == ref2;
    }

    @Test
    void chunksReferenceReturnsLiveArray() {
        AbstractLevel level = new StubLevel();
        Chunk[] ref1 = level.chunksReference();
        Chunk[] ref2 = level.chunksReference();
        // Same reference (not a copy)
        assert ref1 == ref2;
    }

    @Test
    void replaceBlocksUpdatesArrayAndCount() {
        AbstractLevel level = new StubLevel();
        int originalBlockCount = level.getBlockCount();

        Block[] newBlocks = new Block[512];
        level.replaceBlocks(newBlocks);

        assertEquals(512, level.getBlockCount());
        assertEquals(newBlocks, level.blocksReference());
    }

    @Test
    void replaceChunksUpdatesArrayAndCount() {
        AbstractLevel level = new StubLevel();
        int originalChunkCount = level.getChunkCount();

        Chunk[] newChunks = new Chunk[512];
        level.replaceChunks(newChunks);

        assertEquals(512, level.getChunkCount());
        assertEquals(newChunks, level.chunksReference());
    }
}
