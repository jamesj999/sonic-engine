package uk.co.jamesj999.sonic.audio.synth;

/**
 * Band-limited resampler using windowed-sinc interpolation.
 * This provides proper anti-aliasing when downsampling from ~53kHz to 44.1kHz,
 * preventing the metallic/ringing artifacts caused by simple linear interpolation.
 *
 * Based on the same principles as Blip Buffer used in Genesis Plus GX,
 * but adapted for sample-based (rather than delta-based) synthesis.
 */
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
                double t = tap - (FILTER_TAPS / 2 - 1) - phaseOffset;

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
    private final double inputRate;
    private final double outputRate;
    private final double ratio;  // inputRate / outputRate

    // Circular buffer for input samples (stereo)
    private final int[] historyL = new int[FILTER_TAPS * 2];
    private final int[] historyR = new int[FILTER_TAPS * 2];
    private int historyPos = FILTER_TAPS;

    // Fractional position in input stream (0.0 to 1.0)
    private double inputFrac = 0.0;

    public BlipResampler(double inputRate, double outputRate) {
        this.inputRate = inputRate;
        this.outputRate = outputRate;
        this.ratio = inputRate / outputRate;
    }

    /**
     * Reset the resampler state.
     */
    public void reset() {
        for (int i = 0; i < historyL.length; i++) {
            historyL[i] = 0;
            historyR[i] = 0;
        }
        historyPos = FILTER_TAPS;
        inputFrac = 0.0;
    }

    /**
     * Add one input sample to the history buffer.
     */
    public void addInputSample(int left, int right) {
        historyL[historyPos] = left;
        historyR[historyPos] = right;

        // Mirror to handle wraparound without modulo in interpolation
        if (historyPos < FILTER_TAPS) {
            historyL[historyPos + FILTER_TAPS * 2 - FILTER_TAPS] = left;
            historyR[historyPos + FILTER_TAPS * 2 - FILTER_TAPS] = right;
        }

        historyPos++;
        if (historyPos >= FILTER_TAPS * 2) {
            // Shift buffer and reset position
            System.arraycopy(historyL, FILTER_TAPS, historyL, 0, FILTER_TAPS);
            System.arraycopy(historyR, FILTER_TAPS, historyR, 0, FILTER_TAPS);
            historyPos = FILTER_TAPS;
        }
    }

    /**
     * Check if an output sample is available.
     */
    public boolean hasOutputSample() {
        return inputFrac >= 1.0;
    }

    /**
     * Consume input time for one output sample.
     */
    public void advanceOutput() {
        inputFrac -= 1.0;
    }

    /**
     * Advance input time by one input sample.
     */
    public void advanceInput() {
        inputFrac += 1.0 / ratio;
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

    private int interpolate(int[] history) {
        // Calculate phase from fractional position
        double fracPos = inputFrac * ratio;  // Position within input samples
        int intPos = (int) fracPos;
        double frac = fracPos - intPos;

        // Select phase (0 to PHASE_COUNT)
        int phase = (int) (frac * PHASE_COUNT);
        if (phase >= PHASE_COUNT) phase = PHASE_COUNT - 1;

        // Get filter coefficients for this phase
        double[] coeffs = SINC_TABLE[phase];

        // Convolve with history
        double sum = 0.0;
        int startIdx = historyPos - FILTER_TAPS + intPos;
        if (startIdx < 0) startIdx += FILTER_TAPS * 2;

        for (int tap = 0; tap < FILTER_TAPS; tap++) {
            int idx = (startIdx + tap) % (FILTER_TAPS * 2);
            sum += history[idx] * coeffs[tap];
        }

        return (int) Math.round(sum);
    }

    /**
     * Simplified rendering method that handles the full resample loop.
     * Returns number of output samples generated.
     */
    public int resample(SampleProvider provider, int[] outLeft, int[] outRight, int maxOutput) {
        int outIdx = 0;

        while (outIdx < maxOutput) {
            // Generate input samples until we have enough for next output
            while (!hasOutputSample()) {
                int[] sample = provider.generateSample();
                addInputSample(sample[0], sample[1]);
                advanceInput();
            }

            // Output the interpolated sample
            outLeft[outIdx] = getOutputLeft();
            outRight[outIdx] = getOutputRight();
            advanceOutput();
            outIdx++;
        }

        return outIdx;
    }

    /**
     * Interface for sample generation callback.
     */
    public interface SampleProvider {
        /**
         * Generate one sample at the internal rate.
         * @return int[2] with [left, right] channels
         */
        int[] generateSample();
    }
}
