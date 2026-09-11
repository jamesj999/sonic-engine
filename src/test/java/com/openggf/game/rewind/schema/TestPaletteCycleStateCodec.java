package com.openggf.game.rewind.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for {@link PaletteCycleStateCodec}. The codec backs the
 * cycler-state snapshot used by the combined level-animation managers
 * (Sonic2/3k), so its round-trip behavior must be precise even for opaque cycle
 * subclasses.
 */
class TestPaletteCycleStateCodec {

    /** A representative cycle-like target with mixed primitive state. */
    static class FakeCycle {
        int timer = 42;
        int counter = 7;
        boolean dirty = true;
        byte flags = 0x12;
        // The reference field should be ignored by the codec.
        @SuppressWarnings("unused")
        String unrelated = "ignore me";
        // The final primitive should also be ignored.
        @SuppressWarnings("unused")
        final int constantField = 99;
    }

    static class FakeCycleSubclass extends FakeCycle {
        int subClassCounter = 3;
        boolean subClassFlag = false;
    }

    @Test
    void roundTripPreservesMutablePrimitives() {
        FakeCycle original = new FakeCycle();
        original.timer = 12345;
        original.counter = -7;
        original.dirty = false;
        original.flags = (byte) 0xAB;

        byte[] state = PaletteCycleStateCodec.capture(original);
        assertEquals(state.length, PaletteCycleStateCodec.sizeOf(original));

        FakeCycle restored = new FakeCycle();
        // Ensure restored differs from original before restore.
        restored.timer = 0;
        restored.counter = 0;
        restored.dirty = true;
        restored.flags = 0;

        assertNotEquals(original.timer, restored.timer);

        PaletteCycleStateCodec.restore(restored, state);

        assertEquals(original.timer, restored.timer);
        assertEquals(original.counter, restored.counter);
        assertEquals(original.dirty, restored.dirty);
        assertEquals(original.flags, restored.flags);
    }

    @Test
    void roundTripIncludesSuperclassFields() {
        FakeCycleSubclass original = new FakeCycleSubclass();
        original.timer = 1;
        original.counter = 2;
        original.subClassCounter = 99;
        original.subClassFlag = true;

        byte[] state = PaletteCycleStateCodec.capture(original);

        FakeCycleSubclass restored = new FakeCycleSubclass();
        PaletteCycleStateCodec.restore(restored, state);

        assertEquals(original.timer, restored.timer);
        assertEquals(original.counter, restored.counter);
        assertEquals(original.subClassCounter, restored.subClassCounter);
        assertEquals(original.subClassFlag, restored.subClassFlag);
    }

    @Test
    void captureSizeMatchesReportedSize() {
        FakeCycle target = new FakeCycle();
        assertEquals(PaletteCycleStateCodec.sizeOf(target),
                PaletteCycleStateCodec.capture(target).length);
    }

    @Test
    void nullInputsAreSafe() {
        assertEquals(0, PaletteCycleStateCodec.capture(null).length);
        // restore(null, ...) must not throw
        PaletteCycleStateCodec.restore(null, new byte[] {1, 2, 3});
        // restore(target, null) must not throw
        PaletteCycleStateCodec.restore(new FakeCycle(), null);
    }

    @Test
    void shortInputDoesNotCorruptTarget() {
        FakeCycle target = new FakeCycle();
        int originalTimer = target.timer;
        // Pass less data than the codec expects — should be ignored, not partially applied.
        PaletteCycleStateCodec.restore(target, new byte[] {0x01});
        assertEquals(originalTimer, target.timer,
                "short input must not partially write the target");
    }

    @Test
    void sizeIsAtLeastSumOfMutablePrimitives() {
        FakeCycle target = new FakeCycle();
        // 2 ints (8) + 1 boolean (1) + 1 byte (1) = 10 minimum.
        assertTrue(PaletteCycleStateCodec.sizeOf(target) >= 10);
    }
}
