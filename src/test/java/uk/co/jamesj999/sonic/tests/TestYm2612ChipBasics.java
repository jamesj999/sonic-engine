package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.Ym2612Chip;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Basic sanity tests for the YM2612 core to guard against regressions while full accuracy work continues.
 */
public class TestYm2612ChipBasics {

    @Test
    public void simpleToneProducesAudio() {
        Ym2612Chip chip = new Ym2612Chip();
        configureSimpleVoice(chip);

        short[] buffer = new short[512];
        chip.render(buffer);

        boolean hasSignal = false;
        for (short v : buffer) {
            if (v != 0) {
                hasSignal = true;
                break;
            }
        }
        assertTrue("Expected rendered FM samples to be non-zero", hasSignal);
    }

    @Test
    public void timerAFlagRaisesAfterOverflow() {
        Ym2612Chip chip = new Ym2612Chip();
        // Period = 0 -> max length, but still overflows within ~850 samples at 44.1 kHz using current timing
        chip.write(0, 0x24, 0x00); // Timer A high
        chip.write(0, 0x25, 0x00); // Timer A low
        chip.write(0, 0x27, 0x01); // Enable timer A

        short[] buffer = new short[900];
        chip.render(buffer);

        int status = chip.readStatus();
        assertNotEquals("Timer A flag should be raised after overflow", 0, status & 0x01);
    }

    @Test
    public void dacLatchProducesStereoOutput() {
        Ym2612Chip chip = new Ym2612Chip();
        chip.write(0, 0x2B, 0x80); // DAC enable
        // Pan both channels for channel 5 (port 1, reg B2)
        chip.write(1, 0xB2, 0xC0);
        chip.write(0, 0x2A, 0xFF); // Latch max unsigned PCM

        short[] left = new short[32];
        short[] right = new short[32];
        chip.renderStereo(left, right);

        boolean leftHas = false;
        for (short v : left) {
            if (v != 0) {
                leftHas = true;
                break;
            }
        }
        boolean rightHas = false;
        for (short v : right) {
            if (v != 0) {
                rightHas = true;
                break;
            }
        }
        assertTrue("DAC should produce left output", leftHas);
        assertTrue("DAC should produce right output", rightHas);
    }

    private static void configureSimpleVoice(Ym2612Chip chip) {
        // Algorithm 7 (all carriers), no feedback, pan L+R on channel 0
        chip.write(0, 0xB0, 0xC7);

        // FNUM/BLOCK for a mid-range pitch
        chip.write(0, 0xA0, 0x00); // low bits
        chip.write(0, 0xA4, 0x22); // high bits + block

        // Set fast attack/decay and low TL on all operators
        int[] slots = {0x00, 0x04, 0x08, 0x0C}; // slot offsets within operator reg blocks
        for (int slot : slots) {
            chip.write(0, 0x30 + slot, 0x01); // DT/MUL: minimal detune, mul=1
            chip.write(0, 0x40 + slot, 0x00); // TL: loud
            chip.write(0, 0x50 + slot, 0x1F); // RS/AR: AR max
            chip.write(0, 0x60 + slot, 0x10); // AM/D1R: moderate decay
            chip.write(0, 0x70 + slot, 0x08); // D2R
            chip.write(0, 0x80 + slot, 0x05); // D1L/RR
        }

        // Key on all operators for channel 0
        chip.write(0, 0x28, 0xF0);
    }
}
