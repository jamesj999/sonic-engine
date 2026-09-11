package com.openggf.audio.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3B: tail-only {@link BlipResampler} snapshots. The snapshot keeps only
 * the FIR-reachable history window behind {@code inputIndex}; these tests
 * prove bit-exact output equivalence between a restored resampler and the
 * live original it was captured from, including restores into dirty (reused)
 * instances and output-starved windows.
 */
class TestBlipResamplerTailSnapshot {

    private static final double INPUT_RATE = 53267.0;
    private static final double OUTPUT_RATE = 48000.0;

    @Test
    void tailOnlyRestoreIntoFreshResamplerIsBitExactWithLiveOriginal() {
        BlipResampler live = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        drive(live, 0, 2000, 12345L, null, null);

        BlipResampler.Snapshot snapshot = live.captureSnapshot();
        assertTrue(snapshot.historyTailL().length < 64,
                "steady-state tail must be a small window, was " + snapshot.historyTailL().length);

        BlipResampler restored = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        restored.restoreSnapshot(snapshot);

        assertIdenticalContinuation(live, restored, 2000, 3000, 12345L);
    }

    @Test
    void tailOnlyRestoreIntoDirtyResamplerMatchesFreshRestore() {
        BlipResampler live = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        drive(live, 0, 2000, 777L, null, null);
        BlipResampler.Snapshot snapshot = live.captureSnapshot();

        BlipResampler fresh = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        fresh.restoreSnapshot(snapshot);

        BlipResampler dirty = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        drive(dirty, 0, 9000, 31337L, null, null);
        dirty.restoreSnapshot(snapshot);

        assertIdenticalContinuation(fresh, dirty, 2000, 3000, 777L);
    }

    @Test
    void shortHistoryRestoreIntoDirtyResamplerIsBitExact() {
        // Fewer inputs than the FIR span: the pre-start (idx < 0) region must
        // read as zero after restore even into a previously used instance.
        BlipResampler live = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        drive(live, 0, 3, 99L, null, null);
        BlipResampler.Snapshot snapshot = live.captureSnapshot();

        BlipResampler dirty = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        drive(dirty, 0, 5000, 4242L, null, null);
        dirty.restoreSnapshot(snapshot);

        assertIdenticalContinuation(live, dirty, 3, 600, 99L);
    }

    @Test
    void wrappedRingCaptureIsBitExact() {
        // Drive inputIndex past the 8192-sample ring capacity so the history
        // buffer has wrapped before capture, then prove bit-exact continuation
        // after restoring into a dirty instance.
        BlipResampler live = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        drive(live, 0, 9000, 2024L, null, null);

        BlipResampler dirty = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        drive(dirty, 0, 3000, 1111L, null, null);
        dirty.restoreSnapshot(live.captureSnapshot());

        assertIdenticalContinuation(live, dirty, 9000, 10000, 2024L);
    }

    @Test
    void wrappedRingWithTailStraddlingIndexZeroIsBitExact() {
        // Drain normally just past the wrap, then feed an undrained backlog so
        // the captured tail is longer than `head` — restore positions must
        // wrap through the end of the ring (head - tailLen + i goes negative).
        BlipResampler live = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        drive(live, 0, 8200, 606L, null, null);
        java.util.Random rng = new java.util.Random(909L);
        for (int i = 0; i < 60; i++) {
            live.addInputSample(rng.nextInt(20001) - 10000, rng.nextInt(20001) - 10000);
        }
        BlipResampler.Snapshot snapshot = live.captureSnapshot();
        assertTrue(snapshot.historyTailL().length > snapshot.head(),
                "tail must straddle ring index 0: tail=" + snapshot.historyTailL().length
                        + " head=" + snapshot.head());

        BlipResampler dirty = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        drive(dirty, 0, 5000, 313L, null, null);
        dirty.restoreSnapshot(snapshot);

        java.util.List<Integer> liveOut = new java.util.ArrayList<>();
        java.util.List<Integer> restoredOut = new java.util.ArrayList<>();
        drainAvailable(live, liveOut);
        drainAvailable(dirty, restoredOut);
        assertTrue(liveOut.size() > 40, "backlog should drain, was " + liveOut.size());
        assertEquals(liveOut, restoredOut);
    }

    @Test
    void outputStarvedWindowSurvivesTailSnapshot() {
        // Feed input without draining output so inputIndex runs far ahead of
        // outputPos; the tail must cover the whole still-readable backlog.
        BlipResampler live = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        java.util.Random rng = new java.util.Random(55L);
        for (int i = 0; i < 500; i++) {
            live.addInputSample(rng.nextInt(20001) - 10000, rng.nextInt(20001) - 10000);
        }
        BlipResampler.Snapshot snapshot = live.captureSnapshot();
        assertTrue(snapshot.historyTailL().length >= 500,
                "starved backlog must be captured, was " + snapshot.historyTailL().length);

        BlipResampler dirty = new BlipResampler(INPUT_RATE, OUTPUT_RATE);
        drive(dirty, 0, 4000, 808L, null, null);
        dirty.restoreSnapshot(snapshot);

        // Drain the backlog from both and compare every produced sample.
        java.util.List<Integer> liveOut = new java.util.ArrayList<>();
        java.util.List<Integer> restoredOut = new java.util.ArrayList<>();
        drainAvailable(live, liveOut);
        drainAvailable(dirty, restoredOut);
        assertTrue(liveOut.size() > 400, "backlog should drain many samples, was " + liveOut.size());
        assertEquals(liveOut, restoredOut);
    }

    /**
     * Feeds deterministic pseudo-random input for sample indices
     * [from, to), draining output whenever available (normal usage pattern),
     * optionally collecting drained samples.
     */
    private static void drive(
            BlipResampler resampler,
            int from,
            int to,
            long seed,
            java.util.List<Integer> outLeft,
            java.util.List<Integer> outRight) {
        java.util.Random rng = new java.util.Random(seed);
        // Re-synchronise the deterministic stream to the requested window.
        for (int i = 0; i < from; i++) {
            rng.nextInt(20001);
            rng.nextInt(20001);
        }
        for (int i = from; i < to; i++) {
            int left = rng.nextInt(20001) - 10000;
            int right = rng.nextInt(20001) - 10000;
            resampler.addInputSample(left, right);
            while (resampler.hasOutputSample()) {
                int l = resampler.getOutputLeft();
                int r = resampler.getOutputRight();
                if (outLeft != null) {
                    outLeft.add(l);
                }
                if (outRight != null) {
                    outRight.add(r);
                }
                resampler.advanceOutput();
            }
        }
    }

    private static void drainAvailable(BlipResampler resampler, java.util.List<Integer> out) {
        while (resampler.hasOutputSample()) {
            out.add(resampler.getOutputLeft());
            out.add(resampler.getOutputRight());
            resampler.advanceOutput();
        }
    }

    private static void assertIdenticalContinuation(
            BlipResampler expected,
            BlipResampler actual,
            int from,
            int to,
            long seed) {
        java.util.List<Integer> expectedL = new java.util.ArrayList<>();
        java.util.List<Integer> expectedR = new java.util.ArrayList<>();
        java.util.List<Integer> actualL = new java.util.ArrayList<>();
        java.util.List<Integer> actualR = new java.util.ArrayList<>();
        drive(expected, from, to, seed, expectedL, expectedR);
        drive(actual, from, to, seed, actualL, actualR);
        assertTrue(expectedL.size() > 0, "continuation must produce output");
        assertEquals(expectedL, actualL, "left channel must continue bit-exactly");
        assertEquals(expectedR, actualR, "right channel must continue bit-exactly");
    }
}
