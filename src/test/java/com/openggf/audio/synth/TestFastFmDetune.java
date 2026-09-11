package com.openggf.audio.synth;

import com.openggf.audio.synth.fast.FastYm2612Dsp;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public-facade pitch measurements; no dependency on either DSP's tables or state. */
// Minutes-long oracle sweep: excluded from the -Psmoke fast lane, still run by
// the default suite on pull requests, the nightly schedule and release validation.
@Tag("slow-suite")
class TestFastFmDetune {
    private static final double RATE = Ym2612Chip.getInternalRate();
    private static final int FRAMES = 160_000;

    @ParameterizedTest(name = "keycode {0}: signed detune pitch matches accurate core")
    @ValueSource(ints = {4, 9, 12, 20, 28})
    void signedDetuneMatchesTheReferencePitch(int keycode) {
        int block = keycode / 4;
        int fnum = new int[] {0x200, 0x380, 0x400, 0x480}[keycode % 4];
        double accurateBase = frequency(new Ym2612Chip(), block, fnum, 0);
        double fastBase = frequency(new FastYm2612Chip(new FastYm2612Dsp()), block, fnum, 0);
        assertEquals(accurateBase, fastBase, 0.002, "unmodulated carrier pitch");
        for (int detune : new int[] {1, 2, 3, 5, 6, 7}) {
            double accurate = frequency(new Ym2612Chip(), block, fnum, detune);
            double fast = frequency(new FastYm2612Chip(new FastYm2612Dsp()), block, fnum, detune);
            // Resolve well below one phase-increment LSB (about 0.051 Hz).
            // Delta removes the base pitch; interpolated crossings avoid a
            // one-cycle counting ambiguity and ignore the analogue DC offset.
            double accurateDelta = (accurate - accurateBase) * (1 << 20) / RATE;
            double fastDelta = (fast - fastBase) * (1 << 20) / RATE;
            assertEquals(accurateDelta, fastDelta, 0.04,
                    "detune " + detune + " at keycode " + keycode + " (phase-increment LSB)");
        }
    }

    private static double frequency(FmChip chip, int block, int fnum, int detune) {
        chip.setOutputSampleRate(RATE);
        for (int slot = 0; slot < 4; slot++) {
            int offset = slot * 4;
            chip.write(0, 0x30 + offset, (detune << 4) | 1);
            chip.write(0, 0x40 + offset, slot == 0 ? 0 : 127);
            chip.write(0, 0x50 + offset, 31);
            chip.write(0, 0x60 + offset, 0);
            chip.write(0, 0x70 + offset, 0);
            chip.write(0, 0x80 + offset, 15);
        }
        chip.write(0, 0xb0, 7);
        chip.write(0, 0xb4, 0xc0);
        chip.write(0, 0xa4, (block << 3) | (fnum >> 8));
        chip.write(0, 0xa0, fnum & 255);
        chip.write(0, 0x28, 0x10);
        chip.renderStereo(new int[256], new int[256], 256);
        int[] samples = new int[FRAMES];
        chip.renderStereo(samples, new int[FRAMES], FRAMES);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int sample : samples) {
            min = Math.min(min, sample);
            max = Math.max(max, sample);
        }
        assertTrue(max - min > 1000, "the isolated carrier must be audible");
        double middle = (min + (double) max) / 2;
        double sumIndex = 0, sumTime = 0, sumIndexSquared = 0, sumIndexTime = 0;
        int crossings = 0;
        for (int i = 1; i < samples.length; i++) {
            if (samples[i - 1] < middle && samples[i] >= middle) {
                double time = i - 1 + (middle - samples[i - 1]) / (samples[i] - samples[i - 1]);
                sumIndex += crossings;
                sumTime += time;
                sumIndexSquared += (double) crossings * crossings;
                sumIndexTime += crossings * time;
                crossings++;
            }
        }
        assertTrue(crossings > 50, "enough cycles for a stable pitch estimate");
        return RATE * (crossings * sumIndexSquared - sumIndex * sumIndex)
                / (crossings * sumIndexTime - sumIndex * sumTime);
    }
}
