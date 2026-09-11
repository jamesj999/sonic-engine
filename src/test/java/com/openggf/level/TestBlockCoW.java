package com.openggf.level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for copy-on-write behavior in Block.
 */
class TestBlockCoW {

    @Test
    void cowClonesArrayOnFirstWriteSinceEpochBump() {
        Block b = new Block();
        // Initialize with a pattern
        b.setChunkDesc(0, 0, new ChunkDesc(0xABCD));

        ChunkDesc[] beforeArray = b.chunkDescsArrayForTest();

        // Trigger CoW for epoch 1
        b.cowEnsureWritable(1L);

        ChunkDesc[] afterArray = b.chunkDescsArrayForTest();

        // Should be cloned (different reference)
        assertNotSame(beforeArray, afterArray);

        // But old array data should be unchanged (separate from live mutations)
        b.setChunkDesc(0, 0, new ChunkDesc(0x9999));

        // The old array still has the original value
        assertEquals(0xABCD, beforeArray[0].get(), "Before array should not be mutated by CoW");
        // The new array has the mutation
        assertEquals(0x9999, afterArray[0].get(), "After array should have the mutation");
    }

    @Test
    void cowDoesNotCloneWithinSameEpoch() {
        Block b = new Block();
        b.cowEnsureWritable(1L);

        ChunkDesc[] arr1 = b.chunkDescsArrayForTest();

        // Second call with same epoch should NOT clone
        b.cowEnsureWritable(1L);

        ChunkDesc[] arr2 = b.chunkDescsArrayForTest();

        assertSame(arr1, arr2, "CoW within same epoch should not re-clone");
    }

    @Test
    void cowClonesAgainOnNewEpochBump() {
        Block b = new Block();
        b.setChunkDesc(0, 0, new ChunkDesc(0x1111));

        b.cowEnsureWritable(1L);
        ChunkDesc[] arr1 = b.chunkDescsArrayForTest();

        b.setChunkDesc(0, 0, new ChunkDesc(0x2222));

        // Bump to new epoch
        b.cowEnsureWritable(2L);
        ChunkDesc[] arr2 = b.chunkDescsArrayForTest();

        // Should be newly cloned
        assertNotSame(arr1, arr2, "CoW on new epoch should re-clone");

        // Old array should be unchanged
        assertEquals(0x2222, arr1[0].get(), "arr1 should have second mutation");
        // New array initially has same data
        assertEquals(0x2222, arr2[0].get(), "arr2 should start with same data as arr1");

        // Now mutate arr2
        b.setChunkDesc(0, 0, new ChunkDesc(0x3333));

        // arr1 should be unchanged
        assertEquals(0x2222, arr1[0].get(), "arr1 should not see third mutation");
        // arr2 should have the new value
        assertEquals(0x3333, arr2[0].get(), "arr2 should have third mutation");
    }

    @Test
    void multipleBlocksIndependentCoW() {
        Block b1 = new Block();
        Block b2 = new Block();

        b1.setChunkDesc(0, 0, new ChunkDesc(0x1111));
        b2.setChunkDesc(0, 0, new ChunkDesc(0x2222));

        b1.cowEnsureWritable(1L);
        // b2 is not touched yet

        ChunkDesc[] arr1 = b1.chunkDescsArrayForTest();
        ChunkDesc[] arr2 = b2.chunkDescsArrayForTest();

        // b1 should have cloned
        assertSame(arr1, b1.chunkDescsArrayForTest());
        // b2 should still be original (no CoW yet)
        assertSame(arr2, b2.chunkDescsArrayForTest());

        // Now CoW b2
        b2.cowEnsureWritable(1L);
        ChunkDesc[] arr2After = b2.chunkDescsArrayForTest();

        assertNotSame(arr2, arr2After, "b2 should have cloned on first CoW");
    }
}
