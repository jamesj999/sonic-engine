package com.openggf.audio.synth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bit-exactness proof for the optimized {@link BlipResampler} interpolation.
 * {@link ReferenceBlipResampler} is a verbatim copy of the pre-optimization
 * algorithm (same sinc table generation, same per-tap arithmetic order); both
 * implementations are driven with identical input streams and every output
 * sample must match exactly, including early-stream taps before the history
 * fills and ring wrap-around past the 8192-sample buffer.
 */
class TestBlipResamplerBitExactness {

    private static final double INTERNAL_RATE = Ym2612Chip.getInternalRate();
    private static final int BUFFER_SIZE = 1 << 13;

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3})
    void randomStreamsMatchReferencePastWrapAround(long seed) {
        compareStreams(INTERNAL_RATE, 44100.0, seed, BUFFER_SIZE * 2 + 1024);
    }

    @Test
    void downsampleTo48kMatchesReference() {
        compareStreams(INTERNAL_RATE, 48000.0, 7, BUFFER_SIZE + 4096);
    }

    @Test
    void unityRatioMatchesReference() {
        compareStreams(44100.0, 44100.0, 11, 20000);
    }

    @Test
    void upsampleRatioMatchesReference() {
        compareStreams(32000.0, 44100.0, 13, 20000);
    }

    @Test
    void rateChangeMidStreamViaResetMatchesReference() {
        BlipResampler production = new BlipResampler(INTERNAL_RATE, 44100.0);
        ReferenceBlipResampler reference = new ReferenceBlipResampler(INTERNAL_RATE, 44100.0);
        long compared = pump(production, reference, new Random(17), 12000);
        assertTrue(compared > 0, "should compare some output before the rate change");

        production.reset(INTERNAL_RATE, 48000.0);
        reference.reset(INTERNAL_RATE, 48000.0);
        assertTrue(Double.isNaN(cachedPhaseOutputPos(production)),
                "reset() must restore the phase-cache NaN sentinel");
        compared = pump(production, reference, new Random(19), 12000);
        assertTrue(compared > 0, "should compare some output after the rate change");
    }

    private static double cachedPhaseOutputPos(BlipResampler resampler) {
        try {
            java.lang.reflect.Field field = BlipResampler.class.getDeclaredField("cachedPhaseOutputPos");
            field.setAccessible(true);
            return field.getDouble(resampler);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void earlyStreamTapsBeforeHistoryFillsMatchReference() {
        // The very first output samples read taps at negative input indices; both
        // implementations must agree on those too.
        compareStreams(INTERNAL_RATE, 44100.0, 23, 64);
    }

    private static void compareStreams(double inputRate, double outputRate, long seed, int inputSamples) {
        BlipResampler production = new BlipResampler(inputRate, outputRate);
        ReferenceBlipResampler reference = new ReferenceBlipResampler(inputRate, outputRate);
        long compared = pump(production, reference, new Random(seed), inputSamples);
        assertTrue(compared > 0, "stream should produce output samples to compare");
    }

    private static long pump(BlipResampler production, ReferenceBlipResampler reference, Random random, int inputSamples) {
        long compared = 0;
        for (int i = 0; i < inputSamples; i++) {
            // Mix typical chip-mix magnitudes with occasional large values to stress
            // double rounding in the tap accumulation.
            int left = random.nextInt(1 << 16) - (1 << 15);
            int right = random.nextInt(100) == 0
                    ? random.nextInt(1 << 21) - (1 << 20)
                    : random.nextInt(1 << 16) - (1 << 15);
            production.addInputSample(left, right);
            reference.addInputSample(left, right);

            assertEquals(reference.hasOutputSample(), production.hasOutputSample(),
                    "output readiness must match at input " + i);
            while (production.hasOutputSample()) {
                long index = compared;
                long packed = production.getOutputStereoPacked();
                assertEquals(reference.getOutputLeft(), (int) (packed >> 32),
                        "left output " + index + " diverged");
                assertEquals(reference.getOutputRight(), (int) packed,
                        "right output " + index + " diverged");
                production.advanceOutput();
                reference.advanceOutput();
                compared++;
                assertEquals(reference.hasOutputSample(), production.hasOutputSample(),
                        "output readiness must match after output " + index);
            }
        }
        return compared;
    }

    /**
     * Verbatim copy of the pre-optimization BlipResampler (sinc table generation,
     * interpolate, and sampleAt), kept as the bit-exact reference.
     */
    private static final class ReferenceBlipResampler {

        private static final int FILTER_TAPS = 16;
        private static final int PHASE_BITS = 5;
        private static final int PHASE_COUNT = 1 << PHASE_BITS;

        private static final double[][] SINC_TABLE = new double[PHASE_COUNT + 1][FILTER_TAPS];

        static {
            double cutoff = 0.88;
            double kaiserBeta = 5.0;

            for (int phase = 0; phase <= PHASE_COUNT; phase++) {
                double phaseOffset = (double) phase / PHASE_COUNT;
                double sum = 0.0;

                for (int tap = 0; tap < FILTER_TAPS; tap++) {
                    double t = tap - ((double) FILTER_TAPS / 2 - 1) - phaseOffset;

                    double sinc;
                    if (Math.abs(t) < 1e-9) {
                        sinc = cutoff;
                    } else {
                        sinc = Math.sin(Math.PI * cutoff * t) / (Math.PI * t);
                    }

                    double windowArg = 2.0 * tap / (FILTER_TAPS - 1) - 1.0;
                    double window = kaiserWindow(windowArg, kaiserBeta);

                    SINC_TABLE[phase][tap] = sinc * window;
                    sum += SINC_TABLE[phase][tap];
                }

                if (sum != 0.0) {
                    for (int tap = 0; tap < FILTER_TAPS; tap++) {
                        SINC_TABLE[phase][tap] /= sum;
                    }
                }
            }
        }

        private static double kaiserWindow(double x, double beta) {
            if (Math.abs(x) > 1.0) return 0.0;
            return bessel0(beta * Math.sqrt(1.0 - x * x)) / bessel0(beta);
        }

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

        private double ratio;

        private static final int BUFFER_SIZE = 1 << 13;
        private static final int BUFFER_MASK = BUFFER_SIZE - 1;
        private final int[] historyL = new int[BUFFER_SIZE];
        private final int[] historyR = new int[BUFFER_SIZE];
        private int head = 0;
        private long inputIndex = 0;

        private double outputPos = 0.0;

        ReferenceBlipResampler(double inputRate, double outputRate) {
            this.ratio = inputRate / outputRate;
        }

        void reset(double inputRate, double outputRate) {
            this.ratio = inputRate / outputRate;
            java.util.Arrays.fill(historyL, 0);
            java.util.Arrays.fill(historyR, 0);
            head = 0;
            inputIndex = 0;
            outputPos = 0.0;
        }

        void addInputSample(int left, int right) {
            historyL[head] = left;
            historyR[head] = right;
            head = (head + 1) & BUFFER_MASK;
            inputIndex++;
        }

        boolean hasOutputSample() {
            long center = (long) outputPos;
            return inputIndex > center + (FILTER_TAPS / 2);
        }

        void advanceOutput() {
            outputPos += ratio;
        }

        int getOutputLeft() {
            return interpolate(historyL);
        }

        int getOutputRight() {
            return interpolate(historyR);
        }

        private int interpolate(int[] history) {
            long center = (long) outputPos;
            double frac = outputPos - center;
            int phase = (int) (frac * PHASE_COUNT);
            if (phase >= PHASE_COUNT) phase = PHASE_COUNT - 1;
            double[] coeffs = SINC_TABLE[phase];

            long start = center - (FILTER_TAPS / 2) + 1;

            double sum = 0.0;
            for (int tap = 0; tap < FILTER_TAPS; tap++) {
                long idx = start + tap;
                int sample = sampleAt(history, idx);
                sum += sample * coeffs[tap];
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
    }
}
