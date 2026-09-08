package com.openggf.audio.synth;

import com.openggf.audio.synth.fast.FastYm2612Dsp;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TestFastFmOutputTiming {
    static Stream<Arguments> mixtures() {
        return IntStream.range(0, 6).boxed().flatMap(index ->
                Stream.of(Arguments.of(index, false), Arguments.of(index, true)));
    }

    @ParameterizedTest(name = "voice {0}, combine channels {1}")
    @MethodSource("mixtures")
    void preservesRelativePhaseWhenMixingCarriersAndChannels(int index, boolean channels) {
        double[] accurate = mixture(new Ym2612Chip(), index, channels);
        double[] fast = mixture(new FastYm2612Chip(new FastYm2612Dsp()), index, channels);
        assertEquals(1, rms(fast) / rms(accurate), 0.01, "raw mixed level");
        assertTrue(FastFmWaveformAlignment.integer(accurate, fast, 64).correlation() > 0.995,
                "one common alignment must preserve the whole mixture");
    }

    @ParameterizedTest(name = "channel {0}: key-on across all write offsets")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void keyOnRetainsTheSamePhaseAcrossBusOffsets(int channel) {
        // A full register transaction costs 35 internal cycles. Twenty-four
        // repetitions visit every residue modulo the 24-cycle output frame.
        for (int padding = 0; padding < 24; padding++) {
            double[] accurate = paddedKeyOn(new Ym2612Chip(), channel, padding);
            double[] fast = paddedKeyOn(new FastYm2612Chip(new FastYm2612Dsp()), channel, padding);
            var alignment = FastFmWaveformAlignment.integer(accurate, fast, 64);
            assertTrue(alignment.correlation() > 0.995, "padding " + padding + ": " + alignment);
            assertEquals(0, alignment.lag(), 0, "common digital-output latency, padding " + padding);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5})
    void rewindRestoresThePendingChannelOutput(int channel) {
        FastYm2612Chip chip = new FastYm2612Chip(new FastYm2612Dsp());
        prepare(chip);
        voice(chip, channel, true);
        pitchAndKey(chip, channel, 937);
        samples(chip, 517);
        FmChip.Snapshot snapshot = chip.captureSnapshot();
        double[] expected = samples(chip, 64);
        assertTrue(Arrays.stream(expected).anyMatch(value -> value != 0));
        FastYm2612Chip restored = new FastYm2612Chip(new FastYm2612Dsp());
        restored.restoreSnapshot(snapshot);
        assertArrayEquals(expected, samples(restored, 64));
        chip.reset();
        restored.reset();
        assertArrayEquals(samples(chip, 8), samples(restored, 8));
        assertTrue(Arrays.stream(samples(chip, 8)).allMatch(value -> value == 0));
    }

    @Test
    void dacSelectionAndFmReachTheSameOutputBoundary() {
        double[] accurate = alternateFmAndDac(new Ym2612Chip());
        double[] fast = alternateFmAndDac(new FastYm2612Chip(new FastYm2612Dsp()));
        var alignment = FastFmWaveformAlignment.integer(accurate, fast, 64);
        assertTrue(alignment.correlation() > 0.995, "FM and DAC must share one output timeline: " + alignment);
        assertEquals(0, alignment.lag(), 0, "the pipeline terminates at DAC selection");
    }

    @ParameterizedTest(name = "DAC register {0}: all data-strobe offsets")
    @ValueSource(ints = {0x2a, 0x2b})
    void dacSamplingMatchesEveryBusOffset(int register) {
        for (int padding = 0; padding < 24; padding++) {
            double[] accurate = dacStep(new Ym2612Chip(), register, padding);
            double[] fast = dacStep(new FastYm2612Chip(new FastYm2612Dsp()), register, padding);
            assertArrayEquals(accurate, fast, "DAC data/selection sampling, padding " + padding);
        }
    }

    @Test
    void snapshotPreservesPartiallySampledDacWrites() {
        FastYm2612Dsp original = new FastYm2612Dsp();
        original.writeRegister(0, 0x2b, 0x80);
        original.writeRegister(0, 0x2a, 224, 5);
        var restored = original.newInstance();
        original.copyStateTo(restored);
        int[] expected = new int[6], actual = new int[6];
        original.renderFrame(expected);
        restored.renderFrame(actual);
        assertTrue(expected[5] > 0);
        assertArrayEquals(expected, actual);
        original.renderFrame(expected);
        restored.renderFrame(actual);
        assertArrayEquals(expected, actual);
    }

    @Test
    void rewindRetainsWritesAwaitingOperatorSampling() {
        FastYm2612Chip original = new FastYm2612Chip(new FastYm2612Dsp());
        prepare(original);
        voice(original, 1, true);
        pitchAndKey(original, 1, 937);
        samples(original, 513);
        original.write(0, 0xa5, 59);
        original.write(0, 0xa1, 37);
        original.write(0, 0x41, 40);
        original.write(0, 0x28, 1);
        // Drain enough bus work to queue operator updates, while later
        // operator and key sampling boundaries are still in the future.
        samples(original, 3);
        FmChip.Snapshot snapshot = original.captureSnapshot();
        double[] expected = samples(original, 128);
        assertTrue(Arrays.stream(expected).anyMatch(value -> value != 0));
        FastYm2612Chip restored = new FastYm2612Chip(new FastYm2612Dsp());
        restored.restoreSnapshot(snapshot);
        assertArrayEquals(expected, samples(restored, 128));
        original.restoreSnapshot(snapshot);
        original.reset();
        assertTrue(Arrays.stream(samples(original, 16)).allMatch(value -> value == 0),
                "reset must discard pending operator writes");
    }

    private static double[] dacStep(FmChip chip, int register, int padding) {
        prepare(chip);
        chip.write(0, 0x2b, register == 0x2a ? 0x80 : 0);
        chip.write(0, 0x2a, register == 0x2a ? 128 : 224);
        samples(chip, 500);
        for (int count = 0; count < padding; count++) chip.write(0, 0x22, 0);
        chip.write(0, register, register == 0x2a ? 224 : 128);
        return samples(chip, 100);
    }

    private static double[] alternateFmAndDac(FmChip chip) {
        prepare(chip);
        samples(chip, 113);
        voice(chip, 5, false);
        pitchAndKey(chip, 5, 727);
        java.util.List<double[]> chunks = new java.util.ArrayList<>();
        chunks.add(samples(chip, 300));
        for (int repetition = 0; repetition < 4; repetition++) {
            chip.write(0, 0x2b, 0x80);
            for (int value : new int[] {32, 192, 64, 224, 48, 208}) {
                chip.write(0, 0x2a, value);
                chunks.add(samples(chip, 17 + repetition));
            }
            chip.write(0, 0x2b, 0);
            chunks.add(samples(chip, 400));
        }
        return centre(chunks.stream().flatMapToDouble(Arrays::stream).toArray());
    }

    private static double[] mixture(FmChip chip, int index, boolean channels) {
        prepare(chip);
        samples(chip, 113);
        for (int channel = channels ? 0 : index; channel < (channels ? 6 : index + 1); channel++) {
            voice(chip, channel, !channels);
            pitchAndKey(chip, channel, 577 + index * 57 + channel * 13);
        }
        return centre(samples(chip, 8087));
    }

    private static double[] paddedKeyOn(FmChip chip, int channel, int padding) {
        prepare(chip);
        samples(chip, 101);
        voice(chip, channel, false);
        // Override the isolated carrier multiple so pitch is identical on
        // every channel and a timing difference cannot hide in pitch.
        int port = channel / 3, ch = channel % 3;
        chip.write(port, 0x3c + ch, 7);
        chip.write(port, 0xa4 + ch, 59);
        chip.write(port, 0xa0 + ch, 37);
        for (int count = 0; count < padding; count++) chip.write(0, 0x22, 0);
        chip.write(0, 0x28, 0xf0 | ch | (port == 0 ? 0 : 4));
        return centre(samples(chip, 5899));
    }

    private static void prepare(FmChip chip) {
        chip.setChipType(1); // Compare digital output; analogue ladder is outside this contract.
        chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
    }

    private static void voice(FmChip chip, int channel, boolean carriers) {
        int port = channel / 3, ch = channel % 3;
        chip.write(port, 0xb0 + ch, 7);
        chip.write(port, 0xb4 + ch, 0xc0);
        int[] multiples = {3, 7, 11, 13};
        for (int slot = 0; slot < 4; slot++) {
            int offset = ch + slot * 4;
            chip.write(port, 0x30 + offset, carriers ? multiples[slot] : 3 + channel);
            chip.write(port, 0x40 + offset, carriers ? 16 + slot : slot == 3 ? 12 : 127);
            chip.write(port, 0x50 + offset, 31);
            chip.write(port, 0x60 + offset, 0);
            chip.write(port, 0x70 + offset, 0);
            chip.write(port, 0x80 + offset, 15);
        }
    }

    private static void pitchAndKey(FmChip chip, int channel, int fnum) {
        int port = channel / 3, ch = channel % 3;
        chip.write(port, 0xa4 + ch, 56 + (fnum >> 8));
        chip.write(port, 0xa0 + ch, fnum & 255);
        chip.write(0, 0x28, 0xf0 | ch | (port == 0 ? 0 : 4));
    }

    private static double[] samples(FmChip chip, int count) {
        int[] left = new int[count], right = new int[count];
        chip.renderStereo(left, right, count);
        double[] mono = new double[count];
        for (int index = 0; index < count; index++) mono[index] = left[index] + right[index];
        return mono;
    }

    private static double[] centre(double[] samples) {
        double mean = Arrays.stream(samples).average().orElseThrow();
        for (int index = 0; index < samples.length; index++) samples[index] -= mean;
        return samples;
    }

    private static double rms(double[] samples) {
        return Math.sqrt(Arrays.stream(samples).map(value -> value * value).average().orElseThrow());
    }
}
