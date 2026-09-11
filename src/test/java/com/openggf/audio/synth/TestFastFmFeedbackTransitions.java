package com.openggf.audio.synth;

import com.openggf.audio.synth.fast.FastYm2612Dsp;
import java.util.Arrays;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TestFastFmFeedbackTransitions {
    @ParameterizedTest(name = "channel {0}: high feedback across key-on and frequency changes")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void feedbackRetainsTheReferenceSequenceAcrossTransitions(int channel) {
        for (int decay : new int[] {0, 16}) {
            for (int padding = 0; padding < 24; padding++) {
                int[] accurate = sequence(new Ym2612Chip(), channel, decay, padding);
                int[] fast = sequence(new FastYm2612Chip(new FastYm2612Dsp()), channel, decay, padding);
                for (int index = 0; index < accurate.length; index++) {
                    // One isolated digital carrier: the public oracle outputs
                    // 48-unit mono quanta. Quantize only the observed output;
                    // all synthesis and feedback retain their original precision.
                    assertEquals(accurate[index], Math.floorDiv(fast[index], 48) * 48,
                            "decay " + decay + ", padding " + padding + ", sample " + index);
                }
            }
        }
    }

    private static int[] sequence(FmChip chip, int channel, int decay, int padding) {
        chip.setChipType(1);
        chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
        samples(chip, 887);
        int port = channel / 3, ch = channel % 3;
        chip.write(port, 0xb0 + ch, 7 | (7 << 3));
        chip.write(port, 0xb4 + ch, 0xc0);
        for (int slot = 0; slot < 4; slot++) {
            int offset = ch + slot * 4;
            chip.write(port, 0x30 + offset, 9);
            chip.write(port, 0x40 + offset, slot == 0 ? 9 : 127);
            chip.write(port, 0x50 + offset, 31);
            chip.write(port, 0x60 + offset, decay);
            chip.write(port, 0x70 + offset, 0);
            chip.write(port, 0x80 + offset, 255);
        }
        frequency(chip, port, ch, 1023);
        for (int count = 0; count < padding; count++) chip.write(0, 0x22, 0);
        chip.write(0, 0x28, 0xf0 | ch | (port == 0 ? 0 : 4));
        int[] first = samples(chip, 503);
        frequency(chip, port, ch, 900);
        int[] second = samples(chip, 509);
        frequency(chip, port, ch, 977);
        int[] third = samples(chip, 521);
        int[] result = Arrays.copyOf(first, first.length + second.length + third.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        System.arraycopy(third, 0, result, first.length + second.length, third.length);
        return result;
    }

    private static void frequency(FmChip chip, int port, int channel, int fnum) {
        chip.write(port, 0xa4 + channel, 56 | (fnum >> 8));
        chip.write(port, 0xa0 + channel, fnum & 255);
    }

    private static int[] samples(FmChip chip, int count) {
        int[] left = new int[count], right = new int[count];
        chip.renderStereo(left, right, count);
        for (int index = 0; index < count; index++) left[index] += right[index];
        return left;
    }
}
