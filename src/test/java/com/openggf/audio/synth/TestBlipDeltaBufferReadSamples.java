package com.openggf.audio.synth;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Equivalence harness for {@link BlipDeltaBuffer#readSamples(int[], int[], int)}.
 *
 * <p>The production tail logic was changed from an eager full-buffer zero fill to a
 * partial fill of only the vacated region. Both must be exactly equivalent under the
 * maintained invariant that no live data exists beyond {@code availableSamples + BUF_EXTRA}
 * at read time. This test pins production output and post-read buffer state against a
 * test-local copy of the original eager-fill reference logic across randomized
 * delta/read sequences, and asserts the invariant directly after every read.
 *
 * <p>Outside that invariant — deltas already written for a future frame before
 * endFrame — the implementations intentionally differ: the partial fill preserves
 * the pending deltas (matching blip_buf.c) while the eager fill destroyed them.
 * {@link #pendingFutureFrameDeltasSurviveReadUnlikeEagerFillReference} pins that
 * new behavior and proves the harness would detect a revert to the eager fill.
 */
class TestBlipDeltaBufferReadSamples {

    // Mirrors of BlipDeltaBuffer's private constants.
    private static final int DELTA_BITS = 15;
    private static final int FRAC_BITS = 20;
    private static final int FACTOR_FP_BITS = 20;
    private static final int OFFSET_SHIFT = FRAC_BITS + FACTOR_FP_BITS;
    private static final int BUF_EXTRA = 8 * 2 + 2;
    private static final int BASS_SHIFT = 9;

    private static final double CLOCK_RATE = 3579545.0;
    private static final double SAMPLE_RATE = 48000.0;

    /** Mutable reference state mirroring BlipDeltaBuffer's readSamples-relevant fields. */
    private static final class ReferenceState {
        long offsetFp;
        int[] bufferL;
        int[] bufferR;
        int size;
        int integL;
        int integR;

        static ReferenceState from(BlipDeltaBuffer.Snapshot snapshot) {
            ReferenceState state = new ReferenceState();
            state.offsetFp = snapshot.offsetFp();
            state.bufferL = snapshot.bufferL();
            state.bufferR = snapshot.bufferR();
            state.size = snapshot.size();
            state.integL = snapshot.integL();
            state.integR = snapshot.integR();
            return state;
        }
    }

    /**
     * Verbatim copy of the pre-change readSamples logic, including the original
     * eager {@code Arrays.fill(buffer, remain, size, 0)} tail and the original
     * (redundant) inner {@code remain > 0} guard.
     */
    private static void referenceReadSamples(ReferenceState s, int[] left, int[] right, int count) {
        int available = (int) (s.offsetFp >> OFFSET_SHIFT);
        if (count > available) {
            count = available;
        }
        if (count <= 0) {
            return;
        }

        for (int i = 0; i < count; i++) {
            int sL = s.integL >> DELTA_BITS;
            int sR = s.integR >> DELTA_BITS;

            s.integL += s.bufferL[i];
            s.integR += s.bufferR[i];
            s.bufferL[i] = 0;
            s.bufferR[i] = 0;

            if (sL > 32767) sL = 32767;
            else if (sL < -32768) sL = -32768;
            if (sR > 32767) sR = 32767;
            else if (sR < -32768) sR = -32768;

            left[i] += sL;
            right[i] += sR;

            s.integL -= sL << (DELTA_BITS - BASS_SHIFT);
            s.integR -= sR << (DELTA_BITS - BASS_SHIFT);
        }

        int remain = available + BUF_EXTRA - count;
        if (remain > 0 && count < s.size) {
            if (count + remain > s.size) {
                remain = s.size - count;
            }
            if (remain > 0) {
                System.arraycopy(s.bufferL, count, s.bufferL, 0, remain);
                System.arraycopy(s.bufferR, count, s.bufferR, 0, remain);
                Arrays.fill(s.bufferL, remain, s.size, 0);
                Arrays.fill(s.bufferR, remain, s.size, 0);
            }
        }

        s.offsetFp -= (long) count << OFFSET_SHIFT;
    }

    /**
     * Runs one readSamples call on the production buffer and the reference logic in
     * lockstep, asserting identical outputs and identical post-read state, plus the
     * zero-beyond-window invariant on the production buffer.
     */
    private static void readAndCompare(BlipDeltaBuffer buffer, int count, Random rng) {
        BlipDeltaBuffer.Snapshot before = buffer.captureSnapshot();
        ReferenceState reference = ReferenceState.from(before);

        int outLen = Math.max(count, 1);
        int[] base = new int[outLen];
        for (int i = 0; i < outLen; i++) {
            base[i] = rng.nextInt(2000) - 1000;
        }
        int[] productionLeft = base.clone();
        int[] productionRight = base.clone();
        int[] referenceLeft = base.clone();
        int[] referenceRight = base.clone();

        buffer.readSamples(productionLeft, productionRight, count);
        referenceReadSamples(reference, referenceLeft, referenceRight, count);

        assertArrayEquals(referenceLeft, productionLeft, "left output diverged from eager-fill reference");
        assertArrayEquals(referenceRight, productionRight, "right output diverged from eager-fill reference");

        BlipDeltaBuffer.Snapshot after = buffer.captureSnapshot();
        assertEquals(reference.offsetFp, after.offsetFp(), "offsetFp diverged");
        assertEquals(reference.integL, after.integL(), "integL diverged");
        assertEquals(reference.integR, after.integR(), "integR diverged");
        assertEquals(reference.size, after.size(), "size diverged");
        assertArrayEquals(reference.bufferL, after.bufferL(), "bufferL state diverged");
        assertArrayEquals(reference.bufferR, after.bufferR(), "bufferR state diverged");

        assertZeroBeyondWindow(after);
    }

    /** Asserts the maintained invariant: everything past available + BUF_EXTRA is zero. */
    private static void assertZeroBeyondWindow(BlipDeltaBuffer.Snapshot snapshot) {
        int available = (int) (snapshot.offsetFp() >> OFFSET_SHIFT);
        int liveEnd = Math.max(0, Math.min(snapshot.size(), available + BUF_EXTRA));
        int[] bufferL = snapshot.bufferL();
        int[] bufferR = snapshot.bufferR();
        for (int i = liveEnd; i < snapshot.size(); i++) {
            assertEquals(0, bufferL[i], "stale data in bufferL[" + i + "] beyond available+BUF_EXTRA=" + liveEnd);
            assertEquals(0, bufferR[i], "stale data in bufferR[" + i + "] beyond available+BUF_EXTRA=" + liveEnd);
        }
    }

    private static int availableSamples(BlipDeltaBuffer buffer) {
        return (int) (buffer.captureSnapshot().offsetFp() >> OFFSET_SHIFT);
    }

    /** Adds a frame of randomized deltas at increasing clock times, then ends the frame. */
    private static void addRandomFrame(BlipDeltaBuffer buffer, Random rng, int frameClocks) {
        // Callers must size the buffer for the samples a frame will make available
        // (readSamples assumes count <= size); mirror that contract here.
        BlipDeltaBuffer.Snapshot snapshot = buffer.captureSnapshot();
        long offsetAfter = snapshot.offsetFp() + (long) frameClocks * snapshot.factorFp();
        buffer.ensureCapacity((int) (offsetAfter >> OFFSET_SHIFT) + 1);
        int deltaCount = rng.nextInt(8);
        int clock = 0;
        for (int i = 0; i < deltaCount; i++) {
            clock += rng.nextInt(Math.max(1, frameClocks / Math.max(1, deltaCount)));
            clock = Math.min(clock, frameClocks - 1);
            int deltaL = rng.nextInt(8192) - 4096;
            int deltaR = rng.nextInt(8192) - 4096;
            if (rng.nextBoolean()) {
                buffer.addDelta(clock, deltaL, deltaR);
            } else {
                buffer.addDeltaFast(clock, deltaL, deltaR);
            }
        }
        buffer.endFrame(frameClocks);
    }

    @Test
    void randomizedSequencesMatchEagerFillReference() {
        for (long seed = 0; seed < 8; seed++) {
            Random rng = new Random(seed);
            BlipDeltaBuffer buffer = new BlipDeltaBuffer(CLOCK_RATE, SAMPLE_RATE);
            buffer.clear();

            for (int frame = 0; frame < 60; frame++) {
                addRandomFrame(buffer, rng, 1000 + rng.nextInt(20000));

                int reads = rng.nextInt(3);
                for (int r = 0; r < reads; r++) {
                    int available = availableSamples(buffer);
                    int count = switch (rng.nextInt(4)) {
                        case 0 -> rng.nextInt(Math.max(1, available)) + 1;   // partial drain
                        case 1 -> available;                                  // exact drain
                        case 2 -> available + 1 + rng.nextInt(64);            // over-request (clamped)
                        default -> rng.nextInt(4);                            // tiny / zero
                    };
                    readAndCompare(buffer, count, rng);
                }
            }
        }
    }

    @Test
    void sampleAccurateSingleSampleReadsMatchReference() {
        Random rng = new Random(0xB11BL);
        BlipDeltaBuffer buffer = new BlipDeltaBuffer(CLOCK_RATE, SAMPLE_RATE);
        buffer.clear();

        // Sample-accurate mode: end a one-sample frame, read one sample, repeat.
        int clocksPerSample = (int) Math.ceil(CLOCK_RATE / SAMPLE_RATE);
        for (int i = 0; i < 2000; i++) {
            if (rng.nextInt(3) == 0) {
                buffer.addDelta(rng.nextInt(clocksPerSample), rng.nextInt(4096) - 2048, rng.nextInt(4096) - 2048);
            }
            buffer.endFrame(clocksPerSample);
            readAndCompare(buffer, 1, rng);
        }
    }

    @Test
    void fullDrainAcrossEndFrameBoundariesMatchesReference() {
        Random rng = new Random(0xCAFEL);
        BlipDeltaBuffer buffer = new BlipDeltaBuffer(CLOCK_RATE, SAMPLE_RATE);
        buffer.clear();

        // Build up residue across several frames before draining.
        for (int frame = 0; frame < 5; frame++) {
            addRandomFrame(buffer, rng, 30000);
        }
        int available = availableSamples(buffer);
        assertTrue(available > 0, "expected accumulated samples before drain");

        // Partial read leaving residue, then drain fully past available.
        readAndCompare(buffer, available / 2, rng);
        readAndCompare(buffer, available * 2 + BUF_EXTRA, rng);
        assertEquals(0, availableSamples(buffer), "expected buffer fully drained");

        // Read on an empty buffer is a no-op in both implementations.
        readAndCompare(buffer, 16, rng);

        // New frame after the drain still matches.
        addRandomFrame(buffer, rng, 30000);
        readAndCompare(buffer, availableSamples(buffer), rng);
    }

    /**
     * Intentional-divergence pin: deltas written for a future frame (before endFrame)
     * land beyond {@code available + BUF_EXTRA} at read time. The partial fill must
     * PRESERVE them (the new, blip_buf.c-matching behavior) where the old eager fill
     * zeroed them. Asserting the eager-fill reference differs in that region proves
     * the equivalence harness's guard against a revert is live.
     */
    @Test
    void pendingFutureFrameDeltasSurviveReadUnlikeEagerFillReference() {
        BlipDeltaBuffer buffer = new BlipDeltaBuffer(CLOCK_RATE, SAMPLE_RATE);
        buffer.clear();
        buffer.endFrame(30000);
        int available = availableSamples(buffer);
        assertTrue(available > 0, "expected available samples before the pending delta");

        // Place a delta whose 16-tap kernel lands entirely beyond available + BUF_EXTRA.
        BlipDeltaBuffer.Snapshot pre = buffer.captureSnapshot();
        long targetFixed = ((long) (available + BUF_EXTRA + 32)) << OFFSET_SHIFT;
        int futureClock = (int) ((targetFixed - pre.offsetFp()) / pre.factorFp() + 1);
        int pos = (int) (((long) futureClock * pre.factorFp() + pre.offsetFp()) >> OFFSET_SHIFT);
        assertTrue(pos >= available + BUF_EXTRA,
                "pending delta position must lie beyond the live window");
        buffer.addDelta(futureClock, 1234, -567);

        BlipDeltaBuffer.Snapshot withPending = buffer.captureSnapshot();
        int[] pendingL = Arrays.copyOfRange(withPending.bufferL(), pos, pos + 16);
        int[] pendingR = Arrays.copyOfRange(withPending.bufferR(), pos, pos + 16);
        assertTrue(Arrays.stream(pendingL).anyMatch(v -> v != 0), "expected non-zero pending kernel taps");
        assertTrue(Arrays.stream(pendingR).anyMatch(v -> v != 0), "expected non-zero pending kernel taps");

        int count = Math.min(available, 8);
        ReferenceState reference = ReferenceState.from(withPending);
        int[] productionLeft = new int[count];
        int[] productionRight = new int[count];
        buffer.readSamples(productionLeft, productionRight, count);
        referenceReadSamples(reference, new int[count], new int[count], count);

        // Production preserves the pending deltas in place: the shift only moves the
        // remain = available + BUF_EXTRA - count live elements, and the partial fill
        // clears only the vacated [remain, remain + count), never touching [pos, pos+16).
        BlipDeltaBuffer.Snapshot after = buffer.captureSnapshot();
        assertArrayEquals(pendingL, Arrays.copyOfRange(after.bufferL(), pos, pos + 16),
                "partial fill must preserve pending future-frame deltas (left)");
        assertArrayEquals(pendingR, Arrays.copyOfRange(after.bufferR(), pos, pos + 16),
                "partial fill must preserve pending future-frame deltas (right)");

        // The eager-fill reference destroyed them — the harness guard is live.
        int[] referencePendingL = Arrays.copyOfRange(reference.bufferL, pos, pos + 16);
        int[] referencePendingR = Arrays.copyOfRange(reference.bufferR, pos, pos + 16);
        assertTrue(Arrays.stream(referencePendingL).allMatch(v -> v == 0),
                "eager-fill reference should zero pending deltas (left)");
        assertTrue(Arrays.stream(referencePendingR).allMatch(v -> v == 0),
                "eager-fill reference should zero pending deltas (right)");
        assertFalse(Arrays.equals(Arrays.copyOfRange(after.bufferL(), pos, pos + 16), referencePendingL),
                "production and eager-fill reference must differ for pending deltas");
    }
}
