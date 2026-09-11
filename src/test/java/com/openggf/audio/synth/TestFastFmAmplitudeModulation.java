package com.openggf.audio.synth;

import com.openggf.audio.synth.fast.FastYm2612Dsp;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TestFastFmAmplitudeModulation {
    @ParameterizedTest(name = "channel {0}: amplitude depths and every operator output boundary")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void amplitudeStepsMatchTheReferenceAtEveryCarrier(int channel) {
        for (int carrier = 0; carrier < 4; carrier++) {
            double[] referenceHeld = tone(Ym2612Chip::new, channel, carrier, 0);
            double[] candidateHeld = tone(() -> new FastYm2612Chip(new FastYm2612Dsp()), channel, carrier, 0);
            for (int sensitivity = 1; sensitivity <= 3; sensitivity++) {
                double[] referenceAm = tone(Ym2612Chip::new, channel, carrier, sensitivity);
                double[] candidateAm = tone(() -> new FastYm2612Chip(new FastYm2612Dsp()), channel, carrier, sensitivity);
                int compared = 0;
                for (int i = 0; i < referenceHeld.length; i++) {
                    double rh = Math.abs(referenceHeld[i]), ra = Math.abs(referenceAm[i]);
                    double fh = Math.abs(candidateHeld[i]), fa = Math.abs(candidateAm[i]);
                    if (rh < 8000 || fh < 8000 || ra < 1800 || fa < 1800) continue;
                    // Bound observed attenuation by public digital quantization:
                    // 48 units for the oracle mono sum, two for the fast facade.
                    double referenceLow = attenuation(ra + 48, rh - 48);
                    double referenceHigh = attenuation(ra - 48, rh + 48);
                    double candidateLow = attenuation(fa + 2, fh - 2);
                    double candidateHigh = attenuation(fa - 2, fh + 2);
                    assertTrue(candidateLow <= referenceHigh && candidateHigh >= referenceLow,
                            "carrier " + carrier + ", AMS " + sensitivity + ", sample " + i
                                    + ": attenuation " + attenuation(ra, rh) + "/" + attenuation(fa, fh));
                    compared++;
                }
                assertTrue(compared > 500, "must compare sustained AM across multiple cycles");
            }
        }
    }

    @Test
    void rewindRetainsAmplitudeHistoryAcrossDisable() {
        FastYm2612Chip chip = new FastYm2612Chip(new FastYm2612Dsp());
        prepare(chip, 0, 3, 3);
        chip.write(0, 0x22, 15);
        samples(chip, 129);
        chip.write(0, 0x22, 7);
        samples(chip, 1);
        FmChip.Snapshot snapshot = chip.captureSnapshot();
        double[] expected = samples(chip, 113);
        FastYm2612Chip restored = new FastYm2612Chip(new FastYm2612Dsp());
        restored.restoreSnapshot(snapshot);
        assertArrayEquals(expected, samples(restored, 113));
        restored.reset();
        assertArrayEquals(new double[113], samples(restored, 113));
    }

    private static double attenuation(double modulated, double held) {
        return -Math.log(modulated / held) / Math.log(2) * 64;
    }

    private static double[] tone(Supplier<FmChip> factory, int channel, int carrier, int sensitivity) {
        FmChip chip = factory.get();
        prepare(chip, channel, carrier, sensitivity);
        chip.write(0, 0x22, 15);
        return samples(chip, 2431);
    }

    private static void prepare(FmChip chip, int channel, int carrier, int sensitivity) {
        chip.setChipType(1);
        chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
        samples(chip, 137);
        int port = channel / 3, ch = channel % 3;
        chip.write(port, 0xb0 + ch, 7);
        chip.write(port, 0xb4 + ch, 0xc0 | (sensitivity << 4));
        for (int slot = 0; slot < 4; slot++) {
            int offset = ch + slot * 4;
            chip.write(port, 0x30 + offset, 9);
            chip.write(port, 0x40 + offset, slot == carrier ? 0 : 127);
            chip.write(port, 0x50 + offset, 31);
            chip.write(port, 0x60 + offset, 128);
            chip.write(port, 0x70 + offset, 0);
            chip.write(port, 0x80 + offset, 15);
        }
        chip.write(port, 0xa4 + ch, 35);
        chip.write(port, 0xa0 + ch, 209);
        chip.write(0, 0x28, 0xf0 | ch | (port == 0 ? 0 : 4));
        samples(chip, 920);
    }

    private static double[] samples(FmChip chip, int count) {
        int[] left = new int[count], right = new int[count];
        chip.renderStereo(left, right, count);
        double[] result = new double[count];
        for (int i = 0; i < count; i++) result[i] = left[i] + right[i];
        return result;
    }
}
