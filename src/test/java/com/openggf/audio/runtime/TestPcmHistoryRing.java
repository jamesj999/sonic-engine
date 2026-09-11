package com.openggf.audio.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPcmHistoryRing {
    @Test
    void diagnosticFingerprintCopiesAllReadablePcmInWrapOrder() {
        PcmHistoryRing history = new PcmHistoryRing(3);
        history.write(new short[] {1, 10, 2, 20}, 2);
        history.write(new short[] {3, 30, 4, 40}, 2);

        PcmHistoryRing.DiagnosticSnapshot fingerprint =
                history.diagnosticSnapshot();
        assertArrayEquals(new short[] {2, 20, 3, 30, 4, 40},
                fingerprint.readablePcm().samples());

        short[] escaped = fingerprint.readablePcm().samples();
        escaped[0] = 99;
        assertArrayEquals(new short[] {2, 20, 3, 30, 4, 40},
                fingerprint.readablePcm().samples(),
                "diagnostic state must not expose the live/captured array");
        assertEquals(fingerprint, history.diagnosticSnapshot());
    }

    @Test
    void reverseCursorReadsNewestFramesFirst() {
        PcmHistoryRing history = new PcmHistoryRing(4);
        history.write(new short[] {1, 10, 2, 20, 3, 30}, 3);

        short[] target = new short[4];
        int read = history.createReverseCursor().readPrevious(target, 2);

        assertEquals(2, read);
        assertArrayEquals(new short[] {3, 30, 2, 20}, target);
    }

    @Test
    void boundedHistoryDropsOldestFrames() {
        PcmHistoryRing history = new PcmHistoryRing(2);
        history.write(new short[] {1, 10, 2, 20, 3, 30}, 3);

        short[] target = new short[6];
        int read = history.createReverseCursor().readPrevious(target, 3);

        assertEquals(2, read);
        assertArrayEquals(new short[] {3, 30, 2, 20, 0, 0}, target);
    }

    @Test
    void cursorContinuesFromPreviousReverseRead() {
        PcmHistoryRing history = new PcmHistoryRing(4);
        history.write(new short[] {1, 10, 2, 20, 3, 30}, 3);
        PcmHistoryRing.ReverseCursor cursor = history.createReverseCursor();

        short[] first = new short[2];
        short[] second = new short[2];

        assertEquals(1, cursor.readPrevious(first, 1));
        assertEquals(1, cursor.readPrevious(second, 1));
        assertArrayEquals(new short[] {3, 30}, first);
        assertArrayEquals(new short[] {2, 20}, second);
    }

    @Test
    void committingReverseCursorMakesNextReverseSessionResumeFromConsumedPosition() {
        PcmHistoryRing history = new PcmHistoryRing(4);
        history.write(new short[] {1, 10, 2, 20, 3, 30, 4, 40}, 4);
        PcmHistoryRing.ReverseCursor firstCursor = history.createReverseCursor();
        short[] first = new short[4];

        assertEquals(2, firstCursor.readPrevious(first, 2));
        history.commitReverseCursor(firstCursor);

        short[] second = new short[2];
        assertEquals(1, history.createReverseCursor().readPrevious(second, 1));
        assertArrayEquals(new short[] {2, 20}, second);
    }

    @Test
    void reverseCursorRateAboveOneSkipsSourceFramesPerOutputFrame() {
        PcmHistoryRing history = new PcmHistoryRing(8);
        history.write(new short[] {1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6}, 6);
        PcmHistoryRing.ReverseCursor cursor = history.createReverseCursor();
        cursor.setRate(2.0);

        short[] target = new short[6];
        int read = cursor.readPrevious(target, 3);

        assertEquals(3, read);
        // Newest frame is 6, then walk back by rate=2: pick frames 6, 4, 2.
        assertArrayEquals(new short[] {6, 6, 4, 4, 2, 2}, target);
    }

    @Test
    void reverseCursorRateBelowOneRepeatsSourceFramesForSlowMotion() {
        PcmHistoryRing history = new PcmHistoryRing(4);
        history.write(new short[] {1, 10, 2, 20, 3, 30}, 3);
        PcmHistoryRing.ReverseCursor cursor = history.createReverseCursor();
        cursor.setRate(0.5);

        short[] target = new short[8];
        int read = cursor.readPrevious(target, 4);

        assertEquals(4, read);
        // Newest frame is 3, advance by 0.5 each output frame: pick 3, 2.5->2, 2, 1.5->1.
        assertArrayEquals(new short[] {3, 30, 3, 30, 2, 20, 2, 20}, target);
    }

    @Test
    void committingRateAboveOneShrinksRingByConsumedSourceFrames() {
        PcmHistoryRing history = new PcmHistoryRing(8);
        history.write(new short[] {1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6}, 6);
        PcmHistoryRing.ReverseCursor cursor = history.createReverseCursor();
        cursor.setRate(2.0);
        short[] target = new short[4];
        cursor.readPrevious(target, 2); // consumes 4 source frames (6, 4 are emitted; cursor lands at srcFrame=1)

        history.commitReverseCursor(cursor);

        // Newest stored frame is now 2; next reverse read should start at sample {2,2}.
        short[] resumed = new short[2];
        assertEquals(1, history.createReverseCursor().readPrevious(resumed, 1));
        assertArrayEquals(new short[] {2, 2}, resumed);
    }

    @Test
    void clearInvalidatesHistoryForNewCursors() {
        PcmHistoryRing history = new PcmHistoryRing(4);
        history.write(new short[] {1, 10, 2, 20}, 2);

        history.clear();

        short[] target = new short[2];
        assertEquals(0, history.createReverseCursor().readPrevious(target, 1));
        assertArrayEquals(new short[] {0, 0}, target);
    }

    @Test
    void forkCopiesExactSourcePositionRateAndOldestBound() {
        PcmHistoryRing history = new PcmHistoryRing(6);
        history.write(new short[] {1, 10, 2, 20, 3, 30, 4, 40, 5, 50, 6, 60}, 6);
        PcmHistoryRing.ReverseCursor cursor = history.createReverseCursor();
        cursor.setRate(2.0);
        short[] first = new short[2];
        assertEquals(1, cursor.readPrevious(first, 1));
        assertArrayEquals(new short[] {6, 60}, first);

        PcmHistoryRing.ReverseCursor fork = cursor.fork();
        short[] originalRemainder = new short[6];
        short[] forkedRemainder = new short[6];

        assertEquals(2, cursor.readPrevious(originalRemainder, 3));
        assertEquals(2, fork.readPrevious(forkedRemainder, 3));
        assertArrayEquals(new short[] {4, 40, 2, 20, 0, 0}, originalRemainder);
        assertArrayEquals(originalRemainder, forkedRemainder);
    }

    @Test
    void clearInvalidatesExistingCursorEpochBeforeNewHistoryIsWritten() {
        PcmHistoryRing history = new PcmHistoryRing(4);
        history.write(new short[] {1, 10, 2, 20}, 2);
        PcmHistoryRing.ReverseCursor stale = history.createReverseCursor();
        PcmHistoryRing.ReverseCursor staleFork = stale.fork();

        history.clear();
        history.write(new short[] {9, 90, 8, 80}, 2);

        short[] staleTarget = new short[] {-1, -1, -1, -1};
        short[] staleForkTarget = new short[] {-1, -1, -1, -1};
        assertEquals(0, stale.readPrevious(staleTarget, 2));
        assertEquals(0, staleFork.readPrevious(staleForkTarget, 2));
        assertArrayEquals(new short[] {0, 0, 0, 0}, staleTarget);
        assertArrayEquals(new short[] {0, 0, 0, 0}, staleForkTarget);

        short[] freshTarget = new short[2];
        assertEquals(1, history.createReverseCursor().readPrevious(freshTarget, 1));
        assertArrayEquals(new short[] {8, 80}, freshTarget);
    }

    @Test
    void capacityFramesFor_timeMode_multipliesSampleRateBySeconds() {
        assertEquals(44100 * 10, PcmHistoryRing.capacityFramesFor(44100, "time", 10, 2));
        assertEquals(48000 * 5, PcmHistoryRing.capacityFramesFor(48000, "TIME", 5, 99));
    }

    @Test
    void capacityFramesFor_defaultLimitTypeFallsBackToTime() {
        assertEquals(44100 * 10, PcmHistoryRing.capacityFramesFor(44100, null, 10, 2));
        assertEquals(44100 * 10, PcmHistoryRing.capacityFramesFor(44100, "", 10, 2));
        assertEquals(44100 * 10, PcmHistoryRing.capacityFramesFor(44100, "unknown", 10, 2));
    }

    @Test
    void capacityFramesFor_sizeMode_divides16BitStereoBytesByFrame() {
        // 1 MB / (2 channels * 2 bytes per short) = 262144 frames per MB
        assertEquals(262144, PcmHistoryRing.capacityFramesFor(44100, "size", 999, 1));
        assertEquals(524288, PcmHistoryRing.capacityFramesFor(44100, "size", 999, 2));
        assertEquals(262144, PcmHistoryRing.capacityFramesFor(44100, "SIZE", 999, 1));
    }

    @Test
    void capacityFramesFor_clampsNonPositiveInputs() {
        // seconds and sizeMB both clamp to at least 1 before computing capacity
        assertEquals(44100, PcmHistoryRing.capacityFramesFor(44100, "time", 0, 2));
        assertEquals(44100, PcmHistoryRing.capacityFramesFor(44100, "time", -5, 2));
        assertEquals(262144, PcmHistoryRing.capacityFramesFor(44100, "size", 10, 0));
        assertEquals(262144, PcmHistoryRing.capacityFramesFor(44100, "size", 10, -1));
    }
}
