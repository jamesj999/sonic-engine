package com.openggf.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestOscillationManagerSnapshot {

    @Test
    void roundTripPreservesOscillatorPhase() {
        OscillationManager.reset();
        // Tick a few times to advance values away from the initial state
        for (int f = 0; f < 13; f++) {
            OscillationManager.update(f);
        }

        OscillationSnapshot snap = OscillationManager.snapshot();

        // Tick further to produce different state
        for (int f = 13; f < 50; f++) {
            OscillationManager.update(f);
        }
        int[] divergedValues = OscillationManager.valuesForTest();
        int[] divergedDeltas = OscillationManager.deltasForTest();

        OscillationManager.restore(snap);

        assertArrayEquals(snap.values(), OscillationManager.valuesForTest(),
                "values must restore exactly");
        assertArrayEquals(snap.deltas(), OscillationManager.deltasForTest(),
                "deltas must restore exactly");
        assertEquals(snap.control(), OscillationManager.controlForTest(),
                "control bitfield must restore exactly");
        // Sanity: the diverged state was not equal to the snapshot
        assertFalse(java.util.Arrays.equals(divergedValues, snap.values()));
        assertFalse(java.util.Arrays.equals(divergedDeltas, snap.deltas()));
    }

    @Test
    void snapshotIsImmutable() {
        OscillationManager.reset();
        OscillationSnapshot snap = OscillationManager.snapshot();
        int[] values = snap.values();
        values[0] = 0xDEAD;   // mutate the returned array
        assertNotEquals(0xDEAD, snap.values()[0],
                "Snapshot must defensively copy or expose immutable views");
    }

    @Test
    void restoreAlsoCarriesS1ResetFlavour() {
        OscillationManager.resetForSonic1();
        for (int f = 0; f < 7; f++) OscillationManager.update(f);
        OscillationSnapshot s1Snap = OscillationManager.snapshot();
        // Switch flavour
        OscillationManager.reset();
        for (int f = 0; f < 7; f++) OscillationManager.update(f);
        // Restoring the S1 snap must reproduce exact S1 state
        OscillationManager.restore(s1Snap);
        assertArrayEquals(s1Snap.values(), OscillationManager.valuesForTest());
        assertArrayEquals(s1Snap.deltas(), OscillationManager.deltasForTest());
        assertEquals(s1Snap.control(), OscillationManager.controlForTest());
    }
}
