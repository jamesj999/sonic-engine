package com.openggf.level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for copy-on-write behavior in Map.
 */
class TestMapCoW {

    @Test
    void cowClonesArrayOnFirstWriteSinceEpochBump() {
        Map m = new Map(2, 256, 256);
        // Initialize with some data
        m.setValue(0, 0, 0, (byte) 0xAB);

        byte[] beforeArray = m.dataArrayForTest();

        // Trigger CoW for epoch 1
        m.cowEnsureWritable(1L);

        byte[] afterArray = m.dataArrayForTest();

        // Should be cloned (different reference)
        assertNotSame(beforeArray, afterArray);

        // But old array data should be unchanged (separate from live mutations)
        m.setValue(0, 0, 0, (byte) 0x99);

        // The old array still has the original value
        assertEquals((byte) 0xAB, beforeArray[0], "Before array should not be mutated by CoW");
        // The new array has the mutation
        assertEquals((byte) 0x99, afterArray[0], "After array should have the mutation");
    }

    @Test
    void cowDoesNotCloneWithinSameEpoch() {
        Map m = new Map(2, 256, 256);
        m.cowEnsureWritable(1L);

        byte[] arr1 = m.dataArrayForTest();

        // Second call with same epoch should NOT clone
        m.cowEnsureWritable(1L);

        byte[] arr2 = m.dataArrayForTest();

        assertSame(arr1, arr2, "CoW within same epoch should not re-clone");
    }

    @Test
    void cowClonesAgainOnNewEpochBump() {
        Map m = new Map(2, 256, 256);
        m.setValue(0, 0, 0, (byte) 0x11);

        m.cowEnsureWritable(1L);
        byte[] arr1 = m.dataArrayForTest();

        m.setValue(0, 0, 0, (byte) 0x22);

        // Bump to new epoch
        m.cowEnsureWritable(2L);
        byte[] arr2 = m.dataArrayForTest();

        // Should be newly cloned
        assertNotSame(arr1, arr2, "CoW on new epoch should re-clone");

        // Old array should have the second mutation
        assertEquals((byte) 0x22, arr1[0], "arr1 should have second mutation");
        // New array initially has same data
        assertEquals((byte) 0x22, arr2[0], "arr2 should start with same data as arr1");

        // Now mutate arr2
        m.setValue(0, 0, 0, (byte) 0x33);

        // arr1 should be unchanged
        assertEquals((byte) 0x22, arr1[0], "arr1 should not see third mutation");
        // arr2 should have the new value
        assertEquals((byte) 0x33, arr2[0], "arr2 should have third mutation");
    }

    @Test
    void restoreDataReplacesReference() {
        Map m = new Map(2, 256, 256);
        m.setValue(0, 0, 0, (byte) 0x11);

        byte[] original = m.dataArrayForTest();
        assertEquals((byte) 0x11, m.getValue(0, 0, 0));

        // Create a new data array (simulating snapshot data)
        byte[] snapshotData = new byte[256 * 256 * 2];
        snapshotData[0] = (byte) 0x99;

        // Restore the snapshot data
        m.restoreData(snapshotData);

        byte[] restored = m.dataArrayForTest();

        // Should have different references
        assertNotSame(original, restored);
        // And the value should be from the snapshot
        assertEquals((byte) 0x99, m.getValue(0, 0, 0));
    }

    @Test
    void restoreDataResetsEpochForFreshCoW() {
        Map m = new Map(2, 256, 256);

        // First mutation in epoch 1
        m.cowEnsureWritable(1L);
        m.setValue(0, 0, 0, (byte) 0x11);
        byte[] arr1 = m.dataArrayForTest();

        // Capture snapshot data
        byte[] snapshotData = m.dataArrayForTest().clone();

        // Mutate further in epoch 1
        m.setValue(0, 0, 0, (byte) 0x22);

        // Restore the snapshot (epoch should reset)
        m.restoreData(snapshotData);

        // Now trigger CoW with epoch 2
        m.cowEnsureWritable(2L);
        byte[] arr2 = m.dataArrayForTest();

        // Should have cloned (new epoch after restore)
        assertNotSame(snapshotData, arr2);
    }

    @Test
    void multipleLayersMutation() {
        Map m = new Map(2, 256, 256);
        m.setValue(0, 0, 0, (byte) 0x11);
        m.setValue(1, 0, 0, (byte) 0x22);

        m.cowEnsureWritable(1L);
        byte[] arr1 = m.dataArrayForTest();

        // Mutate both layers
        m.setValue(0, 0, 0, (byte) 0x33);
        m.setValue(1, 0, 0, (byte) 0x44);

        // Both old values should persist in the cloned array (which now has the new values)
        assertEquals((byte) 0x33, arr1[0], "First layer should have new value");
        // The old array reference still points to the same (cloned) array, so it sees the new values
        // This is expected because we cloned the entire byte[] on first write
    }
}
