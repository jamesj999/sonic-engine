package com.openggf.audio.synth;

/** One coherent time shift for the whole waveform; never a pitch or per-window warp. */
final class FastFmWaveformAlignment {
    private static final int RADIUS = 16;
    private static final int FRACTIONS = 16;

    record Alignment(double correlation, double lag) { }

    private FastFmWaveformAlignment() { }

    /** Inputs use the caller's existing DC removal; integer results remain unchanged. */
    static Alignment integer(double[] reference, double[] candidate, int limit) {
        Alignment best = new Alignment(0, 0);
        for (int lag = -limit; lag <= limit; lag++) {
            double correlation = correlation(reference, candidate, lag);
            if (correlation > best.correlation()) best = new Alignment(correlation, lag);
        }
        return best;
    }

    /**
     * Refine within one sample of the winning integer shift and the original
     * search bound. A 32-tap Lanczos-windowed sinc supplies fractional samples;
     * zero padding at the signal edges retains the complete original overlap.
     * The caller still judges level on the unfiltered signals.
     */
    static Alignment refine(double[] reference, double[] candidate, int limit, Alignment integer) {
        Alignment best = integer;
        int centre = (int) integer.lag();
        for (int fraction = 1; fraction < FRACTIONS; fraction++) {
            double offset = fraction / (double) FRACTIONS;
            double[] shifted = interpolate(candidate, offset);
            for (int lag : new int[] {centre - 1, centre}) {
                double effectiveLag = lag + offset;
                if (Math.abs(effectiveLag) > limit) continue;
                double correlation = correlation(reference, shifted, lag);
                if (correlation > best.correlation()) best = new Alignment(correlation, effectiveLag);
            }
        }
        return best;
    }

    private static double[] interpolate(double[] samples, double offset) {
        double[] weights = new double[2 * RADIUS];
        double total = 0;
        for (int tap = 1 - RADIUS; tap <= RADIUS; tap++) {
            double distance = tap - offset;
            double weight = sinc(distance) * sinc(distance / RADIUS);
            weights[tap + RADIUS - 1] = weight;
            total += weight;
        }
        for (int index = 0; index < weights.length; index++) weights[index] /= total;
        double[] shifted = new double[samples.length];
        for (int index = 0; index < samples.length; index++) {
            double value = 0;
            for (int tap = 1 - RADIUS; tap <= RADIUS; tap++) {
                int source = index + tap;
                if (source >= 0 && source < samples.length) value += samples[source] * weights[tap + RADIUS - 1];
            }
            shifted[index] = value;
        }
        return shifted;
    }

    private static double sinc(double value) {
        return Math.abs(value) < 1e-12 ? 1 : Math.sin(Math.PI * value) / (Math.PI * value);
    }

    private static double correlation(double[] reference, double[] candidate, int lag) {
        double product = 0, referenceEnergy = 0, candidateEnergy = 0;
        for (int index = Math.max(0, -lag); index < reference.length && index + lag < candidate.length; index++) {
            double a = reference[index], b = candidate[index + lag];
            product += a * b;
            referenceEnergy += a * a;
            candidateEnergy += b * b;
        }
        return referenceEnergy == 0 || candidateEnergy == 0 ? 0
                : product / Math.sqrt(referenceEnergy * candidateEnergy);
    }
}
