package com.openggf.audio.synth;

import com.openggf.audio.synth.fast.FastYm2612Dsp;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class TestFastFmFrequencyTransitions {
    static Stream<Arguments> channelsAndOctaves() {
        return IntStream.range(0, 6).boxed().flatMap(channel ->
                Stream.of(Arguments.of(channel, 4), Arguments.of(channel, 7)));
    }

    @ParameterizedTest(name = "channel {0}, block {1}: changing pitch preserves modulation phase")
    @MethodSource("channelsAndOctaves")
    void frequencyChangesKeepTheOperatorsInPhase(int channel, int block) {
        double[] accurate = sequence(new Ym2612Chip(), channel, block);
        double[] fast = sequence(new FastYm2612Chip(new FastYm2612Dsp()), channel, block);
        double rmsA = rms(accurate), rmsF = rms(fast);
        assertTrue(rmsA > 1000, "the reference must render a substantial signal");
        assertEquals(1, rmsF / rmsA, 0.05, "raw level remains within five percent");
        var alignment = FastFmWaveformAlignment.integer(accurate, fast, 64);
        assertTrue(alignment.correlation() > 0.99,
                "pitch changes must preserve phase through the modulation paths: " + alignment);
    }

    @Test
    void rewindRestoresDelayedModulatorHistory() {
        FastYm2612Chip original = new FastYm2612Chip(new FastYm2612Dsp());
        original.setOutputSampleRate(Ym2612Chip.getInternalRate());
        voice(original, 0);
        frequency(original, 0, 7, 881);
        original.write(0, 0x28, 0xf0);
        samples(original, 517);
        FmChip.Snapshot snapshot = original.captureSnapshot();
        int[] expected = samples(original, 96);
        assertTrue(Arrays.stream(expected).anyMatch(value -> value != 0));
        FastYm2612Chip restored = new FastYm2612Chip(new FastYm2612Dsp());
        restored.restoreSnapshot(snapshot);
        assertArrayEquals(expected, samples(restored, 96), "rewind must restore the signal feeding delayed paths");
    }

    private static double[] sequence(FmChip chip, int channel, int block) {
        chip.setChipType(1); // Digital reference output; analogue ladder is outside the fast-core contract.
        chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
        double[] output = new double[8000];
        append(output, 0, samples(chip, 101));
        voice(chip, channel);
        int[] times = {101, 1703, 2537, 3401, 4807, 5809, 6811};
        int[] pitches = {1023, 904, 577, 858, 683, 881};
        int key = channel % 3 | (channel >= 3 ? 4 : 0);
        for (int index = 0; index < pitches.length; index++) {
            frequency(chip, channel, block, pitches[index]);
            if (index == 0) chip.write(0, 0x28, 0xf0 | key);
            append(output, times[index], samples(chip, times[index + 1] - times[index]));
        }
        chip.write(0, 0x28, key);
        append(output, 6811, samples(chip, output.length - 6811));
        double mean = Arrays.stream(output).average().orElseThrow();
        for (int index = 0; index < output.length; index++) output[index] -= mean;
        return output;
    }

    private static void voice(FmChip chip, int channel) {
        int port = channel / 3, ch = channel % 3;
        chip.write(port, 0xb0 + ch, 5 | (5 << 3));
        chip.write(port, 0xb4 + ch, 0xc0);
        int[] multiples = {9, 0, 3, 0}, levels = {9, 18, 4, 14};
        for (int slot = 0; slot < 4; slot++) {
            int offset = ch + slot * 4;
            chip.write(port, 0x30 + offset, multiples[slot]);
            chip.write(port, 0x40 + offset, levels[slot]);
            chip.write(port, 0x50 + offset, 31);
            chip.write(port, 0x60 + offset, 0);
            chip.write(port, 0x70 + offset, 0);
            chip.write(port, 0x80 + offset, 15);
        }
    }

    private static void frequency(FmChip chip, int channel, int block, int fnum) {
        chip.write(channel / 3, 0xa4 + channel % 3, (block << 3) | (fnum >> 8));
        chip.write(channel / 3, 0xa0 + channel % 3, fnum & 255);
    }

    private static int[] samples(FmChip chip, int count) {
        int[] left = new int[count], right = new int[count];
        chip.renderStereo(left, right, count);
        for (int index = 0; index < count; index++) left[index] += right[index];
        return left;
    }

    private static void append(double[] output, int offset, int[] samples) {
        for (int index = 0; index < samples.length; index++) output[offset + index] = samples[index];
    }

    private static double rms(double[] samples) {
        return Math.sqrt(Arrays.stream(samples).map(value -> value * value).average().orElseThrow());
    }
}
