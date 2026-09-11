package com.openggf.level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for copy-on-write behavior in Chunk.
 */
class TestChunkCoW {

    @Test
    void cowClonesArrayOnFirstWriteSinceEpochBump() {
        Chunk c = new Chunk();
        // Initialize with a pattern
        c.setPatternDesc(0, 0, new PatternDesc(0xABCD));

        PatternDesc[] beforeArray = c.patternDescsArrayForTest();

        // Trigger CoW for epoch 1
        c.cowEnsureWritable(1L);

        PatternDesc[] afterArray = c.patternDescsArrayForTest();

        // Should be cloned (different reference)
        assertNotSame(beforeArray, afterArray);

        // But old array data should be unchanged (separate from live mutations)
        c.setPatternDesc(0, 0, new PatternDesc(0x9999));

        // The old array still has the original value
        assertEquals(0xABCD, beforeArray[0].get(), "Before array should not be mutated by CoW");
        // The new array has the mutation
        assertEquals(0x9999, afterArray[0].get(), "After array should have the mutation");
    }

    @Test
    void cowDoesNotCloneWithinSameEpoch() {
        Chunk c = new Chunk();
        c.cowEnsureWritable(1L);

        PatternDesc[] arr1 = c.patternDescsArrayForTest();

        // Second call with same epoch should NOT clone
        c.cowEnsureWritable(1L);

        PatternDesc[] arr2 = c.patternDescsArrayForTest();

        assertSame(arr1, arr2, "CoW within same epoch should not re-clone");
    }

    @Test
    void cowClonesAgainOnNewEpochBump() {
        Chunk c = new Chunk();
        c.setPatternDesc(0, 0, new PatternDesc(0x1111));

        c.cowEnsureWritable(1L);
        PatternDesc[] arr1 = c.patternDescsArrayForTest();

        c.setPatternDesc(0, 0, new PatternDesc(0x2222));

        // Bump to new epoch
        c.cowEnsureWritable(2L);
        PatternDesc[] arr2 = c.patternDescsArrayForTest();

        // Should be newly cloned
        assertNotSame(arr1, arr2, "CoW on new epoch should re-clone");

        // Old array should be unchanged
        assertEquals(0x2222, arr1[0].get(), "arr1 should have second mutation");
        // New array initially has same data
        assertEquals(0x2222, arr2[0].get(), "arr2 should start with same data as arr1");

        // Now mutate arr2
        c.setPatternDesc(0, 0, new PatternDesc(0x3333));

        // arr1 should be unchanged
        assertEquals(0x2222, arr1[0].get(), "arr1 should not see third mutation");
        // arr2 should have the new value
        assertEquals(0x3333, arr2[0].get(), "arr2 should have third mutation");
    }

    @Test
    void multipleChunksIndependentCoW() {
        Chunk c1 = new Chunk();
        Chunk c2 = new Chunk();

        c1.setPatternDesc(0, 0, new PatternDesc(0x1111));
        c2.setPatternDesc(0, 0, new PatternDesc(0x2222));

        c1.cowEnsureWritable(1L);
        // c2 is not touched yet

        PatternDesc[] arr1 = c1.patternDescsArrayForTest();
        PatternDesc[] arr2 = c2.patternDescsArrayForTest();

        // c1 should have cloned
        assertSame(arr1, c1.patternDescsArrayForTest());
        // c2 should still be original (no CoW yet)
        assertSame(arr2, c2.patternDescsArrayForTest());

        // Now CoW c2
        c2.cowEnsureWritable(1L);
        PatternDesc[] arr2After = c2.patternDescsArrayForTest();

        assertNotSame(arr2, arr2After, "c2 should have cloned on first CoW");
    }

    @Test
    void solidTileIndicesNotAffectedByCoW() {
        Chunk c = new Chunk();
        c.setSolidTileIndex(0x1111);
        c.setSolidTileAltIndex(0x2222);

        c.cowEnsureWritable(1L);

        // Solid indices should be unchanged (not CoW'd)
        assertEquals(0x1111, c.getSolidTileIndex());
        assertEquals(0x2222, c.getSolidTileAltIndex());

        // Changing them should not require CoW
        c.setSolidTileIndex(0x3333);
        assertEquals(0x3333, c.getSolidTileIndex());
    }
}
