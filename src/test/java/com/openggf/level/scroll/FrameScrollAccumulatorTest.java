package com.openggf.level.scroll;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the rewind-safety contract of {@link FrameScrollAccumulator}: the value
 * is a deterministic function of the frame counter (idempotent per frame), so a
 * handler that re-derives an earlier frame during rewind reproduces that frame's
 * value exactly, instead of drifting on the update-call count.
 */
class FrameScrollAccumulatorTest {

    @Test
    void valueIsFramesSinceAnchorTimesIncrement() {
        FrameScrollAccumulator acc = new FrameScrollAccumulator(0x8000);
        // First sample anchors here; default offset 0 => value 0 on the first frame.
        assertEquals(0, acc.valueAt(10));
        assertEquals(0x8000, acc.valueAt(11));
        assertEquals(5 * 0x8000, acc.valueAt(15));
    }

    @Test
    void firstFrameOffsetMatchesIncrementThenReadHandlers() {
        // Handlers that increment-then-read (GHZ/WFZ) show one increment on the
        // first sampled frame.
        FrameScrollAccumulator acc = new FrameScrollAccumulator(0x8000, 1);
        assertEquals(0x8000, acc.valueAt(10));
        assertEquals(2 * 0x8000, acc.valueAt(11));
    }

    @Test
    void reDerivingAPastFrameReproducesItsValue() {
        FrameScrollAccumulator acc = new FrameScrollAccumulator(0x2000);
        for (int f = 1; f <= 10; f++) {
            acc.valueAt(f);
        }
        int atFrame10 = acc.valueAt(10);
        // Run far forward past a keyframe...
        for (int f = 11; f <= 60; f++) {
            acc.valueAt(f);
        }
        // ...then rewind re-derives frame 10.
        assertEquals(atFrame10, acc.valueAt(10),
                "re-deriving frame 10 must reproduce its value after forward drift");
    }

    @Test
    void resetReArmsTheAnchor() {
        FrameScrollAccumulator acc = new FrameScrollAccumulator(0x1000);
        acc.valueAt(100);
        assertEquals(3 * 0x1000, acc.valueAt(103));
        // Zone/act re-entry: the counter zeroes at the new start frame.
        acc.reset();
        assertEquals(0, acc.valueAt(200));
        assertEquals(2 * 0x1000, acc.valueAt(202));
    }

    @Test
    void monotonicFreezeGateHoldsAtLastActiveFrame() {
        FrameScrollAccumulator acc = new FrameScrollAccumulator(0x800);
        // Active for frames 0..5.
        for (int f = 0; f <= 5; f++) {
            acc.valueAt(f, true);
        }
        int frozenValue = acc.valueAt(5, true); // 5 * 0x800
        assertEquals(5 * 0x800, frozenValue);
        // Freeze: subsequent frames are inactive; value holds.
        assertEquals(frozenValue, acc.valueAt(6, false));
        assertEquals(frozenValue, acc.valueAt(20, false));
        // Re-deriving a frozen frame still yields the held value (rewind-safe).
        assertEquals(frozenValue, acc.valueAt(15, false));
    }
}
