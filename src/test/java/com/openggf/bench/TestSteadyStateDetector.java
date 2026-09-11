package com.openggf.bench;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSteadyStateDetector {

    private static final int WINDOW = SteadyStateDetector.DEFAULT_WINDOW;

    @Test
    void aRunThatIsFastFromTheStartSettlesImmediately() {
        long[] frames = new long[2000];
        Arrays.fill(frames, 1_000_000L);

        assertEquals(0, SteadyStateDetector.framesToSteadyState(frames, frames.length));
    }

    @Test
    void findsTheFrameWhereWarmupEnds() {
        // Interpreted for the first 1000 frames, then compiled.
        long[] frames = new long[4000];
        Arrays.fill(frames, 0, 1000, 8_000_000L);
        Arrays.fill(frames, 1000, 4000, 1_000_000L);

        int settled = SteadyStateDetector.framesToSteadyState(frames, frames.length);

        // The reported frame is a window start, so it lands within one window of
        // the true transition rather than exactly on it.
        assertTrue(settled >= 1000 - WINDOW && settled <= 1000 + WINDOW,
                "expected settling near frame 1000 but got " + settled);
    }

    @Test
    void aTransientDipDuringWarmupDoesNotCountAsConvergence() {
        // Slow, then briefly fast, then slow again, then genuinely fast. A
        // detector that returned the first qualifying window would report the
        // dip and understate the warmup cost by thousands of frames.
        long[] frames = new long[6000];
        Arrays.fill(frames, 0, 1000, 8_000_000L);
        Arrays.fill(frames, 1000, 1400, 1_000_000L);
        Arrays.fill(frames, 1400, 3000, 8_000_000L);
        Arrays.fill(frames, 3000, 6000, 1_000_000L);

        int settled = SteadyStateDetector.framesToSteadyState(frames, frames.length);

        assertTrue(settled > 1500,
                "the dip at 1000-1400 must not be reported as steady state, got " + settled);
        assertTrue(settled <= 3000 + WINDOW,
                "expected settling near frame 3000 but got " + settled);
    }

    @Test
    void aRunStillChangingAtItsEndSettlesOnlyNearThatEnd() {
        // Cheap throughout, then a step change late. The reported settle point
        // sits near the end of the series, which is what "the run was still
        // changing when it stopped" looks like — the signal to give the run more
        // frames rather than to quote the figure.
        long[] frames = new long[3000];
        Arrays.fill(frames, 0, 2700, 1_000_000L);
        Arrays.fill(frames, 2700, 3000, 10_000_000L);

        int settled = SteadyStateDetector.framesToSteadyState(frames, frames.length);

        assertTrue(settled > frames.length - 2 * WINDOW,
                "expected a settle point close to the end of the series, got " + settled);
    }

    @Test
    void aRunShorterThanOneWindowIsNotMeasurable() {
        long[] frames = new long[WINDOW - 1];
        Arrays.fill(frames, 1_000_000L);

        assertEquals(-1, SteadyStateDetector.framesToSteadyState(frames, frames.length));
    }

    @Test
    void noiseWithinToleranceStillCountsAsSettled() {
        long[] frames = new long[3000];
        for (int i = 0; i < frames.length; i++) {
            // +/-1% jitter, inside the 2% default tolerance.
            frames[i] = 1_000_000L + (i % 2 == 0 ? 10_000 : -10_000);
        }

        assertEquals(0, SteadyStateDetector.framesToSteadyState(frames, frames.length));
    }

    @Test
    void rejectsNonPositiveWindowOrStride() {
        long[] frames = new long[1000];
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SteadyStateDetector.framesToSteadyState(frames, 1000, 0, 0.02, 10));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SteadyStateDetector.framesToSteadyState(frames, 1000, 300, 0.02, 0));
    }
}
