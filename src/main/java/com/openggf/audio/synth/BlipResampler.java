package com.openggf.audio.synth;

/**
 * Band-limited resampler using windowed-sinc interpolation.
 * This provides proper anti-aliasing when downsampling from ~53kHz to 44.1kHz,
 * preventing the metallic/ringing artifacts caused by simple linear interpolation.
 * <p>
 * Based on the same principles as Blip Buffer used in Genesis Plus GX,
 * but adapted for sample-based (rather than delta-based) synthesis.
 */
import java.util.Arrays;

public class BlipResampler {

    // Filter parameters
    private static final int FILTER_TAPS = 16;       // 8 taps on each side of center
    private static final int PHASE_BITS = 5;         // 32 phases for sub-sample positioning
    private static final int PHASE_COUNT = 1 << PHASE_BITS;  // 32

    // Pre-computed windowed sinc coefficients [phase][tap]
    // Generated with Kaiser window, beta=5.0, cutoff at 0.9 * Nyquist
    private static final double[][] SINC_TABLE = new double[PHASE_COUNT + 1][FILTER_TAPS];

    static {
        // Generate windowed sinc filter coefficients
        double cutoff = 0.88;  // Slightly below Nyquist to avoid ringing
        double kaiserBeta = 5.0;

        for (int phase = 0; phase <= PHASE_COUNT; phase++) {
            double phaseOffset = (double) phase / PHASE_COUNT;
            double sum = 0.0;

            for (int tap = 0; tap < FILTER_TAPS; tap++) {
                // Center the filter
                double t = tap - ((double) FILTER_TAPS / 2 - 1) - phaseOffset;

                // Sinc function
                double sinc;
                if (Math.abs(t) < 1e-9) {
                    sinc = cutoff;
                } else {
                    sinc = Math.sin(Math.PI * cutoff * t) / (Math.PI * t);
                }

                // Kaiser window
                double windowArg = 2.0 * tap / (FILTER_TAPS - 1) - 1.0;
                double window = kaiserWindow(windowArg, kaiserBeta);

                SINC_TABLE[phase][tap] = sinc * window;
                sum += SINC_TABLE[phase][tap];
            }

            // Normalize to unity gain
            if (sum != 0.0) {
                for (int tap = 0; tap < FILTER_TAPS; tap++) {
                    SINC_TABLE[phase][tap] /= sum;
                }
            }
        }
    }

    /**
     * Kaiser window function for FIR filter design.
     */
    private static double kaiserWindow(double x, double beta) {
        if (Math.abs(x) > 1.0) return 0.0;
        return bessel0(beta * Math.sqrt(1.0 - x * x)) / bessel0(beta);
    }

    /**
     * Modified Bessel function of the first kind, order 0.
     * Used for Kaiser window calculation.
     */
    private static double bessel0(double x) {
        double sum = 1.0;
        double term = 1.0;
        double xHalf = x / 2.0;

        for (int k = 1; k < 25; k++) {
            term *= (xHalf / k) * (xHalf / k);
            sum += term;
            if (term < 1e-12 * sum) break;
        }
        return sum;
    }

    // Instance state
    private double ratio;  // inputRate / outputRate

    // Circular buffer for input samples (stereo)
    private static final int BUFFER_SIZE = 1 << 13;
    private static final int BUFFER_MASK = BUFFER_SIZE - 1;
    private final int[] historyL = new int[BUFFER_SIZE];
    private final int[] historyR = new int[BUFFER_SIZE];
    private int head = 0;
    private long inputIndex = 0;

    // Output sample position expressed in input sample units.
    private double outputPos = 0.0;

    public BlipResampler(double inputRate, double outputRate) {
        this.ratio = inputRate / outputRate;
    }

    /**
     * Reset the resampler state.
     */
    public void reset() {
        Arrays.fill(historyL, 0);
        Arrays.fill(historyR, 0);
        head = 0;
        inputIndex = 0;
        outputPos = 0.0;
        cachedPhaseOutputPos = Double.NaN;
    }

    /**
     * Reset the resampler state and update sample rates.
     * This avoids allocating new history buffers when only the rate changes.
     */
    public void reset(double inputRate, double outputRate) {
        this.ratio = inputRate / outputRate;
        reset();
    }

    /** Mutable owner-private transaction storage; never used by durable snapshots. */
    static final class MutationBackup {
        private double ratio;
        private int head;
        private long inputIndex;
        private double outputPos;
        private int[] left = new int[0], right = new int[0];
        private int count;
    }

    void captureMutation(MutationBackup backup) {
        backup.ratio = ratio;
        backup.head = head;
        backup.inputIndex = inputIndex;
        backup.outputPos = outputPos;
        long first = Math.max(inputIndex - BUFFER_SIZE,
                (long) outputPos - (FILTER_TAPS / 2 - 1));
        first = Math.min(inputIndex, Math.max(0, first));
        backup.count = (int) (inputIndex - first);
        if (backup.left.length < backup.count) {
            backup.left = new int[backup.count];
            backup.right = new int[backup.count];
        }
        for (int i = 0; i < backup.count; i++) {
            int pos = (head - backup.count + i) & BUFFER_MASK;
            backup.left[i] = historyL[pos];
            backup.right[i] = historyR[pos];
        }
    }

    void restoreMutation(MutationBackup backup) {
        ratio = backup.ratio;
        head = backup.head;
        inputIndex = backup.inputIndex;
        outputPos = backup.outputPos;
        Arrays.fill(historyL, 0);
        Arrays.fill(historyR, 0);
        for (int i = 0; i < backup.count; i++) {
            int pos = (head - backup.count + i) & BUFFER_MASK;
            historyL[pos] = backup.left[i];
            historyR[pos] = backup.right[i];
        }
        cachedPhaseOutputPos = Double.NaN;
    }

    /**
     * Captures only the history tail that interpolation can still read after a
     * restore. {@link #interpolate(int[])} reads taps in
     * {@code [floor(outputPos) - (FILTER_TAPS/2 - 1), floor(outputPos) + FILTER_TAPS/2]},
     * {@code sampleAt} zeroes anything older than {@code inputIndex - BUFFER_SIZE},
     * and {@code outputPos} only ever advances — so samples older than
     * {@code floor(outputPos) - (FILTER_TAPS/2 - 1)} can never be read again.
     */
    Snapshot captureSnapshot() {
        long center = (long) outputPos;
        long minIdx = Math.max(inputIndex - BUFFER_SIZE, center - (FILTER_TAPS / 2 - 1));
        minIdx = Math.max(minIdx, 0);
        minIdx = Math.min(minIdx, inputIndex);
        int tailLen = (int) (inputIndex - minIdx);
        int[] tailL = new int[tailLen];
        int[] tailR = new int[tailLen];
        for (int i = 0; i < tailLen; i++) {
            int pos = (head - tailLen + i) & BUFFER_MASK;
            tailL[i] = historyL[pos];
            tailR[i] = historyR[pos];
        }
        return new Snapshot(ratio, tailL, tailR, head, inputIndex, outputPos);
    }

    void restoreSnapshot(Snapshot snapshot) {
        ratio = snapshot.ratio();
        // Zero first so ring positions outside the captured tail read exactly
        // like a fresh resampler's untouched buffer (matters for the pre-wrap
        // idx < 0 window and for reused chip instances).
        Arrays.fill(historyL, 0);
        Arrays.fill(historyR, 0);
        head = snapshot.head();
        inputIndex = snapshot.inputIndex();
        outputPos = snapshot.outputPos();
        int[] tailL = snapshot.historyTailLRef();
        int[] tailR = snapshot.historyTailRRef();
        int tailLen = tailL.length;
        for (int i = 0; i < tailLen; i++) {
            int pos = (head - tailLen + i) & BUFFER_MASK;
            historyL[pos] = tailL[i];
            historyR[pos] = tailR[i];
        }
    }

    /**
     * Add one input sample to the history buffer.
     */
    public void addInputSample(int left, int right) {
        historyL[head] = left;
        historyR[head] = right;
        head = (head + 1) & BUFFER_MASK;
        inputIndex++;
    }

    /**
     * Check if an output sample is available.
     */
    public boolean hasOutputSample() {
        // Direct cast is equivalent to Math.floor() for positive values (outputPos is always >= 0)
        long center = (long) outputPos;
        return inputIndex > center + (FILTER_TAPS / 2);
    }

    /**
     * Consume input time for one output sample.
     */
    public void advanceOutput() {
        outputPos += ratio;
    }

    /**
     * Advance input time by one input sample.
     */
    public void advanceInput() {
        // No-op: output readiness is derived from inputIndex vs outputPos.
    }

    /**
     * Get interpolated output sample (left channel).
     */
    public int getOutputLeft() {
        return interpolate(historyL);
    }

    /**
     * Get interpolated output sample (right channel).
     */
    public int getOutputRight() {
        return interpolate(historyR);
    }

    /**
     * Get both interpolated output samples with one traversal of the FIR window.
     * The left sample occupies the high 32 bits and the right sample the low 32
     * bits. Each channel retains the scalar interpolation's multiplication and
     * accumulation order so the packed path remains bit-exact.
     */
    public long getOutputStereoPacked() {
        final double pos = outputPos;
        // Direct cast is equivalent to Math.floor() for positive values (outputPos is always >= 0)
        long center = (long) pos;
        if (pos != cachedPhaseOutputPos) {
            double frac = pos - center;
            int phase = (int) (frac * PHASE_COUNT);
            if (phase >= PHASE_COUNT) phase = PHASE_COUNT - 1;
            cachedPhase = phase;
            cachedPhaseOutputPos = pos;
        }
        double[] coeffs = SINC_TABLE[cachedPhase];

        long start = center - (FILTER_TAPS / 2) + 1;
        double sumL = 0.0;
        double sumR = 0.0;
        if (start >= inputIndex - BUFFER_SIZE && center + (FILTER_TAPS / 2) < inputIndex) {
            int pos0 = (head - (int) (inputIndex - start)) & BUFFER_MASK;
            for (int tap = 0; tap < FILTER_TAPS; tap++) {
                sumL += historyL[pos0] * coeffs[tap];
                sumR += historyR[pos0] * coeffs[tap];
                pos0 = (pos0 + 1) & BUFFER_MASK;
            }
        } else {
            for (int tap = 0; tap < FILTER_TAPS; tap++) {
                long idx = start + tap;
                sumL += sampleAt(historyL, idx) * coeffs[tap];
                sumR += sampleAt(historyR, idx) * coeffs[tap];
            }
        }

        int left = (int) Math.round(sumL);
        int right = (int) Math.round(sumR);
        return ((long) left << 32) | (right & 0xFFFF_FFFFL);
    }

    // Phase index is a pure function of outputPos; cache it so the second channel of
    // each output sample (L then R at the same outputPos) skips the recomputation.
    private double cachedPhaseOutputPos = Double.NaN;
    private int cachedPhase;

    private int interpolate(int[] history) {
        final double pos = outputPos;
        // Direct cast is equivalent to Math.floor() for positive values (outputPos is always >= 0)
        long center = (long) pos;
        if (pos != cachedPhaseOutputPos) {
            double frac = pos - center;
            int phase = (int) (frac * PHASE_COUNT);
            if (phase >= PHASE_COUNT) phase = PHASE_COUNT - 1;
            cachedPhase = phase;
            cachedPhaseOutputPos = pos;
        }
        double[] coeffs = SINC_TABLE[cachedPhase];

        long start = center - (FILTER_TAPS / 2) + 1;

        double sum = 0.0;
        if (start >= inputIndex - BUFFER_SIZE && center + (FILTER_TAPS / 2) < inputIndex) {
            // Every tap lies inside the live ring window (callers gate on
            // hasOutputSample(), so this holds in steady state): sampleAt's range
            // check is dead here and the ring position can advance incrementally.
            // Same samples, same coefficients, same accumulation order — bit-exact.
            int pos0 = (head - (int) (inputIndex - start)) & BUFFER_MASK;
            for (int tap = 0; tap < FILTER_TAPS; tap++) {
                sum += history[pos0] * coeffs[tap];
                pos0 = (pos0 + 1) & BUFFER_MASK;
            }
        } else {
            for (int tap = 0; tap < FILTER_TAPS; tap++) {
                long idx = start + tap;
                int sample = sampleAt(history, idx);
                sum += sample * coeffs[tap];
            }
        }

        return (int) Math.round(sum);
    }

    private int sampleAt(int[] history, long idx) {
        long oldest = inputIndex - BUFFER_SIZE;
        if (idx < oldest || idx >= inputIndex) {
            return 0;
        }
        int pos = (head - (int) (inputIndex - idx)) & BUFFER_MASK;
        return history[pos];
    }

    /**
     * Tail-only history snapshot. {@code historyTailL}/{@code historyTailR}
     * hold the newest samples, ordered oldest-to-newest, ending at input index
     * {@code inputIndex - 1}.
     */
    public record Snapshot(
            double ratio,
            int[] historyTailL,
            int[] historyTailR,
            int head,
            long inputIndex,
            double outputPos) {
        public Snapshot {
            historyTailL = Arrays.copyOf(historyTailL, historyTailL.length);
            historyTailR = Arrays.copyOf(historyTailR, historyTailR.length);
        }

        @Override
        public int[] historyTailL() { return Arrays.copyOf(historyTailL, historyTailL.length); }

        @Override
        public int[] historyTailR() { return Arrays.copyOf(historyTailR, historyTailR.length); }

        /** Non-copying view for in-memory restore paths only. Do not mutate. */
        int[] historyTailLRef() { return historyTailL; }

        /** Non-copying view for in-memory restore paths only. Do not mutate. */
        int[] historyTailRRef() { return historyTailR; }

        @Override
        public boolean equals(Object candidate) {
            return candidate instanceof Snapshot other
                    && Double.compare(ratio, other.ratio) == 0
                    && Arrays.equals(historyTailL, other.historyTailL)
                    && Arrays.equals(historyTailR, other.historyTailR)
                    && head == other.head
                    && inputIndex == other.inputIndex
                    && Double.compare(outputPos, other.outputPos) == 0;
        }

        @Override
        public int hashCode() {
            int result = Double.hashCode(ratio);
            result = 31 * result + Arrays.hashCode(historyTailL);
            result = 31 * result + Arrays.hashCode(historyTailR);
            result = 31 * result + Integer.hashCode(head);
            result = 31 * result + Long.hashCode(inputIndex);
            return 31 * result + Double.hashCode(outputPos);
        }
    }
}
