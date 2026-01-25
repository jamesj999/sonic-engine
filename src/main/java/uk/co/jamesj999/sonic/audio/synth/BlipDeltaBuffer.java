package uk.co.jamesj999.sonic.audio.synth;

import java.util.Arrays;

/**
 * Delta-based band-limited step buffer (BLIP-style).
 * Add deltas at clock timestamps; read samples by integrating impulses.
 */
public class BlipDeltaBuffer {
    private static final int PHASE_BITS = 5;
    private static final int PHASE_COUNT = 1 << PHASE_BITS;
    private static final int KERNEL_TAPS = 16;
    private static final int KERNEL_HALF = KERNEL_TAPS / 2;
    private static final double CUTOFF = 0.90;
    private static final double[][] IMPULSE = new double[PHASE_COUNT][KERNEL_TAPS];

    static {
        double kaiserBeta = 5.0;
        for (int phase = 0; phase < PHASE_COUNT; phase++) {
            double phaseOffset = (double) phase / PHASE_COUNT;
            double sum = 0.0;
            for (int tap = 0; tap < KERNEL_TAPS; tap++) {
                double t = (tap - KERNEL_HALF) - phaseOffset;
                double sinc;
                if (Math.abs(t) < 1e-9) {
                    sinc = CUTOFF;
                } else {
                    sinc = Math.sin(Math.PI * CUTOFF * t) / (Math.PI * t);
                }
                double windowArg = 2.0 * tap / (KERNEL_TAPS - 1) - 1.0;
                double window = kaiserWindow(windowArg, kaiserBeta);
                double value = sinc * window;
                IMPULSE[phase][tap] = value;
                sum += value;
            }
            if (sum != 0.0) {
                for (int tap = 0; tap < KERNEL_TAPS; tap++) {
                    IMPULSE[phase][tap] /= sum;
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

    private double clockRate;
    private double sampleRate;
    private double clocksPerSample;

    private double[] bufferL;
    private double[] bufferR;
    private int size;
    private int mask;
    private int readIndex;
    private double accumL;
    private double accumR;
    private double time;

    public BlipDeltaBuffer(double clockRate, double sampleRate) {
        setRates(clockRate, sampleRate);
        ensureCapacity(1024);
    }

    public void setRates(double clockRate, double sampleRate) {
        this.clockRate = clockRate;
        this.sampleRate = sampleRate;
        this.clocksPerSample = clockRate / sampleRate;
    }

    public void reset(double clockRate, double sampleRate) {
        setRates(clockRate, sampleRate);
        clear();
    }

    public void clear() {
        if (bufferL != null) {
            Arrays.fill(bufferL, 0.0);
            Arrays.fill(bufferR, 0.0);
        }
        readIndex = 0;
        accumL = 0.0;
        accumR = 0.0;
        time = 0.0;
    }

    public void ensureCapacity(int neededSamples) {
        int needed = neededSamples + KERNEL_TAPS + 2;
        if (bufferL != null && size >= needed) {
            return;
        }
        int newSize = 1;
        while (newSize < needed) {
            newSize <<= 1;
        }
        double[] newL = new double[newSize];
        double[] newR = new double[newSize];
        if (bufferL != null) {
            for (int i = 0; i < size; i++) {
                int idx = (readIndex + i) & mask;
                newL[i] = bufferL[idx];
                newR[i] = bufferR[idx];
            }
        }
        bufferL = newL;
        bufferR = newR;
        size = newSize;
        mask = newSize - 1;
        readIndex = 0;
    }

    public void addDelta(double clockTime, int deltaL, int deltaR) {
        double offsetSamples = (clockTime - time) / clocksPerSample + KERNEL_HALF;
        if (offsetSamples < 0) {
            offsetSamples = 0;
        }
        int index = (int) Math.floor(offsetSamples);
        double frac = offsetSamples - index;
        int phase = (int) (frac * PHASE_COUNT);
        if (phase >= PHASE_COUNT) {
            phase = PHASE_COUNT - 1;
        }

        int base = (readIndex + index) & mask;
        double[] kernel = IMPULSE[phase];
        for (int tap = 0; tap < KERNEL_TAPS; tap++) {
            int idx = (base + tap) & mask;
            double k = kernel[tap];
            bufferL[idx] += deltaL * k;
            bufferR[idx] += deltaR * k;
        }
    }

    public void readSamples(int[] left, int[] right, int count) {
        for (int i = 0; i < count; i++) {
            accumL += bufferL[readIndex];
            accumR += bufferR[readIndex];
            bufferL[readIndex] = 0.0;
            bufferR[readIndex] = 0.0;

            left[i] += (int) Math.round(accumL);
            right[i] += (int) Math.round(accumR);

            readIndex = (readIndex + 1) & mask;
            time += clocksPerSample;
        }
    }

    public void endFrame(double clocks) {
        time -= clocks;
    }
}
