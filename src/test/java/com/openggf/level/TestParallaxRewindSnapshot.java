package com.openggf.level;

import com.openggf.game.rewind.snapshot.ParallaxSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestParallaxRewindSnapshot {

    @Test
    void roundTripTreatsParallaxAsDerivedState() {
        ParallaxManager pm = new ParallaxManager();
        ParallaxSnapshot initial = pm.capture();
        ParallaxSnapshot modified = new ParallaxSnapshot(
                7, -3,
                0x1234, 256,
                false, false, false,
                0, 0,
                (short) 0, (short) 0
        );
        pm.restore(modified);
        ParallaxSnapshot snap = pm.capture();
        assertEquals(initial, snap,
                "parallax snapshots are compact markers; dense/scalar state is recomputed after registry restore");
    }

    @Test
    void keyIsParallax() {
        assertEquals("parallax", new ParallaxManager().key());
    }

    @Test
    void legacyDenseConstructorDoesNotRetainArrays() {
        ParallaxManager pm = new ParallaxManager();
        ParallaxSnapshot snap = pm.capture();
        int[] newScroll = new int[224];
        java.util.Arrays.fill(newScroll, 999);
        ParallaxSnapshot other = new ParallaxSnapshot(
                0, 0, Integer.MIN_VALUE, 512,
                newScroll, new short[224], new short[20], new short[20],
                false, false, false, 0, 0, (short) 0, (short) 0
        );
        pm.restore(other);
        assertEquals(snap, pm.capture());
    }

    @Test
    void snapshotDoesNotRetainDenseScrollBuffers() {
        java.util.Set<String> components = java.util.Arrays.stream(ParallaxSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(components.contains("hScroll"));
        assertFalse(components.contains("vScrollPerLineBG"));
        assertFalse(components.contains("vScrollPerColumnBG"));
        assertFalse(components.contains("vScrollPerColumnFG"));
    }
}
