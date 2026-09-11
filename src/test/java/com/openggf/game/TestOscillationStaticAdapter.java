package com.openggf.game;

import com.openggf.game.rewind.snapshot.OscillationStaticAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestOscillationStaticAdapter {
    private OscillationStaticAdapter adapter;

    @BeforeEach
    void setUp() {
        OscillationManager.reset();
        adapter = new OscillationStaticAdapter();
    }

    @Test
    void testOscillationAdapterSnapshotRoundTrip() {
        // Initialize to S2 (default)
        OscillationManager.reset();

        // Capture initial state
        OscillationSnapshot snapshot = adapter.capture();

        // Advance oscillation by updating with frame counter
        OscillationManager.update(1);

        // Verify state has changed
        int[] valuesAfterUpdate = OscillationManager.valuesForTest();
        int[] snapshotValues = snapshot.values();
        boolean changed = false;
        for (int i = 0; i < valuesAfterUpdate.length; i++) {
            if (valuesAfterUpdate[i] != snapshotValues[i]) {
                changed = true;
                break;
            }
        }
        assertTrue(changed, "Oscillation values should have changed after update");

        // Restore from snapshot
        adapter.restore(snapshot);

        // Verify state matches snapshot
        int[] restoredValues = OscillationManager.valuesForTest();
        int[] restoredDeltas = OscillationManager.deltasForTest();
        int control = OscillationManager.controlForTest();

        assertArrayEquals(snapshotValues, restoredValues);
        assertArrayEquals(snapshot.deltas(), restoredDeltas);
        assertEquals(snapshot.control(), control);
    }

    @Test
    void testOscillationAdapterKey() {
        assertEquals("oscillation", adapter.key());
    }

    @Test
    void testOscillationAdapterSonic1Flavor() {
        OscillationManager.resetForSonic1();

        // Capture S1 state
        OscillationSnapshot snapshot = adapter.capture();

        // Switch to S2
        OscillationManager.reset();

        // Restore S1 state
        adapter.restore(snapshot);

        // Verify S1 values are restored
        int[] restoredValues = OscillationManager.valuesForTest();
        assertArrayEquals(snapshot.values(), restoredValues);
    }

    @Test
    void testOscillationAdapterWithFrameUpdate() {
        OscillationManager.reset();

        // Tick several frames
        OscillationManager.update(0);
        OscillationManager.update(1);
        OscillationManager.update(2);

        // Capture state
        OscillationSnapshot snapshot = adapter.capture();
        int snapshotLastFrame = snapshot.lastFrame();

        // Continue ticking
        OscillationManager.update(3);
        OscillationManager.update(4);

        // Restore
        adapter.restore(snapshot);

        // Verify frame counter is restored
        OscillationSnapshot restored = adapter.capture();
        assertEquals(snapshotLastFrame, restored.lastFrame());
    }
}
