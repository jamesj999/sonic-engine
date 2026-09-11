package com.openggf.audio.synth;

import com.openggf.audio.synth.fast.FastYm2612Dsp;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TestFastFmEnvelopeSampling {
    @ParameterizedTest(name = "channel {0}: every carrier samples the decay at the same output boundary")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void decayStepsReachTheOutputAtTheReferenceBoundary(int channel) {
        for (int slot = 0; slot < 4; slot++) {
            double[] accurateHeld = tone(Ym2612Chip::new, channel, slot, 0);
            double[] accurateDecay = tone(Ym2612Chip::new, channel, slot, 31);
            double[] fastHeld = tone(() -> new FastYm2612Chip(new FastYm2612Dsp()), channel, slot, 0);
            double[] fastDecay = tone(() -> new FastYm2612Chip(new FastYm2612Dsp()), channel, slot, 31);
            int compared = 0;
            for (int index = 0; index < accurateHeld.length; index++) {
                int fastIndex = index;
                // Compare attenuation where both waveforms have enough signal
                // to resolve a decay step despite the oracle's 9-bit output.
                if (Math.abs(accurateHeld[index]) < 8000 || Math.abs(fastHeld[fastIndex]) < 8000
                        || Math.abs(accurateDecay[index]) < 1000 || Math.abs(fastDecay[fastIndex]) < 1000) continue;
                double reference = attenuation(accurateDecay[index], accurateHeld[index]);
                double actual = attenuation(fastDecay[fastIndex], fastHeld[fastIndex]);
                if (reference < 16) continue;
                // The oracle's mono digital output has 48-unit quanta. Bound
                // both observed ratios by one quantization unit, rather than
                // mistaking a quiet sample's rounding for envelope movement.
                double referenceLow = attenuation(Math.abs(accurateDecay[index]) + 48,
                        Math.abs(accurateHeld[index]) - 48);
                double referenceHigh = attenuation(Math.abs(accurateDecay[index]) - 48,
                        Math.abs(accurateHeld[index]) + 48);
                double fastLow = attenuation(Math.abs(fastDecay[fastIndex]) + 2,
                        Math.abs(fastHeld[fastIndex]) - 2);
                double fastHigh = attenuation(Math.abs(fastDecay[fastIndex]) - 2,
                        Math.abs(fastHeld[fastIndex]) + 2);
                assertTrue(fastLow <= referenceHigh && fastHigh >= referenceLow,
                        "slot " + slot + ", output sample " + index + ": attenuation " + reference + "/" + actual);
                compared++;
            }
            assertTrue(compared >= 20, "must observe a substantial portion of the decay, slot " + slot);
        }
    }

    private static double attenuation(double decaying, double held) {
        // Public digital envelope scale: 64 attenuation units halve amplitude.
        return -Math.log(Math.abs(decaying / held)) / Math.log(2) * 64;
    }

    private static double[] tone(Supplier<FmChip> factory, int channel, int carrier, int decay) {
        FmChip chip = factory.get();
        chip.setChipType(1);
        chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
        samples(chip, 53);
        int port = channel / 3, ch = channel % 3;
        chip.write(port, 0xb0 + ch, 7);
        chip.write(port, 0xb4 + ch, 0xc0);
        for (int slot = 0; slot < 4; slot++) {
            int offset = ch + slot * 4;
            chip.write(port, 0x30 + offset, 13);
            chip.write(port, 0x40 + offset, slot == carrier ? 0 : 127);
            chip.write(port, 0x50 + offset, 31);
            chip.write(port, 0x60 + offset, decay);
            chip.write(port, 0x70 + offset, 0);
            chip.write(port, 0x80 + offset, 255);
        }
        chip.write(port, 0xa4 + ch, 27);
        chip.write(port, 0xa0 + ch, 51);
        chip.write(0, 0x28, 0xf0 | ch | (port == 0 ? 0 : 4));
        return samples(chip, 700);
    }

    private static double[] samples(FmChip chip, int count) {
        int[] left = new int[count], right = new int[count];
        chip.renderStereo(left, right, count);
        double[] result = new double[count];
        for (int index = 0; index < count; index++) result[index] = left[index] + right[index];
        return result;
    }
}
