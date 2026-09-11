package com.openggf.audio.synth;

import com.openggf.audio.synth.fast.FastYm2612Dsp;
import java.util.Arrays;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TestFastFmFrequencySampling {
    @ParameterizedTest(name = "channel {0}: pitch changes across every operator sampling boundary")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void pitchChangesPreservePhaseAcrossBusOffsets(int channel) {
        for (int slot = 0; slot < 4; slot++) {
            for (int padding = 0; padding < 24; padding++) {
                double[] accurate = sequence(new Ym2612Chip(), channel, slot, padding);
                double[] fast = sequence(new FastYm2612Chip(new FastYm2612Dsp()), channel, slot, padding);
                var alignment = FastFmWaveformAlignment.integer(accurate, fast, 64);
                assertTrue(alignment.correlation() > 0.995,
                        "slot " + slot + ", padding " + padding + ": " + alignment);
            }
        }
    }

    private static double[] sequence(FmChip chip, int channel, int carrier, int padding) {
        chip.setChipType(1);
        chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
        samples(chip, 101);
        int port = channel / 3, ch = channel % 3;
        chip.write(port, 0xb0 + ch, 7);
        chip.write(port, 0xb4 + ch, 0xc0);
        for (int slot = 0; slot < 4; slot++) {
            int offset = ch + slot * 4;
            chip.write(port, 0x30 + offset, 9);
            chip.write(port, 0x40 + offset, slot == carrier ? 12 : 127);
            chip.write(port, 0x50 + offset, 31);
            chip.write(port, 0x60 + offset, 0);
            chip.write(port, 0x70 + offset, 0);
            chip.write(port, 0x80 + offset, 15);
        }
        frequency(chip, port, ch, 700);
        chip.write(0, 0x28, 0xf0 | ch | (port == 0 ? 0 : 4));
        double[] first = samples(chip, 1500);
        // Padding changes the write offset without changing the voice. Since
        // gcd(35 bus cycles, 24 frame cycles) = 1, all residues are visited.
        for (int count = 0; count < padding; count++) chip.write(0, 0x22, 0);
        frequency(chip, port, ch, 900);
        double[] second = samples(chip, 2500);
        double[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        double mean = Arrays.stream(result).average().orElseThrow();
        for (int index = 0; index < result.length; index++) result[index] -= mean;
        return result;
    }

    private static void frequency(FmChip chip, int port, int channel, int fnum) {
        chip.write(port, 0xa4 + channel, 48 | (fnum >> 8));
        chip.write(port, 0xa0 + channel, fnum & 255);
    }

    private static double[] samples(FmChip chip, int count) {
        int[] left = new int[count], right = new int[count];
        chip.renderStereo(left, right, count);
        double[] result = new double[count];
        for (int index = 0; index < count; index++) result[index] = left[index] + right[index];
        return result;
    }
}
