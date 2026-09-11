package com.openggf.audio.synth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestFastFmWaveformAlignment {
    @Test
    void coherentFractionalDelayPreservesAMixedWaveform() {
        double[] reference = signal(0, 1, false);
        double[] candidate = signal(2.375, 1, false);
        var integer = FastFmWaveformAlignment.integer(reference, candidate, 8);
        var refined = FastFmWaveformAlignment.refine(reference, candidate, 8, integer);
        assertTrue(integer.correlation() < 0.9, "positive control must expose integer-only alignment");
        assertTrue(refined.correlation() > 0.995, "one fractional shift restores the mixed waveform");
        assertEquals(2.375, refined.lag(), 1.0 / 16);
    }

    @Test
    void changingPitchOrCarrierRoutingCannotPassAsTiming() {
        double[] reference = signal(0, 1, false);
        for (double[] wrong : new double[][] {
                signal(2.375, Math.pow(2, 1.0 / 12), false), signal(2.375, 1, true)}) {
            var integer = FastFmWaveformAlignment.integer(reference, wrong, 8);
            var refined = FastFmWaveformAlignment.refine(reference, wrong, 8, integer);
            assertTrue(refined.correlation() < 0.9, "a wrong note or carrier must remain rejected");
        }
    }

    @Test
    void silentCandidateCannotAcquireCorrelationThroughInterpolation() {
        double[] reference = signal(0, 1, false);
        double[] silence = new double[reference.length];
        var integer = FastFmWaveformAlignment.integer(reference, silence, 8);
        assertEquals(0, FastFmWaveformAlignment.refine(reference, silence, 8, integer).correlation());
    }

    @Test
    void fractionalSearchCannotEscapeItsOriginalBound() {
        double[] reference = signal(0, 1, false);
        double[] candidate = signal(8.375, 1, false);
        var integer = FastFmWaveformAlignment.integer(reference, candidate, 8);
        var refined = FastFmWaveformAlignment.refine(reference, candidate, 8, integer);
        assertTrue(Math.abs(refined.lag()) <= 8);
    }

    private static double[] signal(double delay, double pitch, boolean omitCarrier) {
        double[] samples = new double[8192];
        for (int index = 0; index < samples.length; index++) {
            double time = (index - delay) * pitch;
            // Several incommensurate carriers make one coherent shift
            // distinguishable from independently aligning individual tones.
            samples[index] = Math.sin(2 * Math.PI * 0.0731 * time)
                    + (omitCarrier ? 0 : Math.sin(2 * Math.PI * 0.2713 * time))
                    + Math.sin(2 * Math.PI * 0.3617 * time);
        }
        return samples;
    }
}
