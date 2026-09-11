package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.audio.synth.Ym2612Chip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Basic sanity tests for the YM2612 core to guard against regressions while full accuracy work continues.
 */
public class TestYm2612ChipBasics {

    @Test
    public void simpleToneProducesAudio() {
        Ym2612Chip chip = new Ym2612Chip();
        configureSimpleVoice(chip);

        int[] left = new int[512];
        int[] right = new int[512];
        chip.renderStereo(left, right);

        // The discrete YM2612 model rests at a constant positive level and the
        // resampler settles over its first taps, so measure the swing of the
        // steady state rather than testing for non-zero samples.
        int swing = peakToPeak(java.util.Arrays.copyOfRange(left, 64, left.length));
        assertTrue(swing > 1000, "Expected an audible FM tone, swing was " + swing);
    }

    @Test
    public void timerAFlagRaisesAfterOverflow() {
        Ym2612Chip chip = new Ym2612Chip();
        // Period = 0 -> max length, but still overflows within ~850 samples at 44.1 kHz using current timing
        chip.write(0, 0x24, 0x00); // Timer A high
        chip.write(0, 0x25, 0x00); // Timer A low
        chip.write(0, 0x27, 0x05); // Enable timer A run + flag

        int[] left = new int[900];
        int[] right = new int[900];
        chip.renderStereo(left, right);

        int status = chip.readStatus();
        assertNotEquals(0, status & 0x01, "Timer A flag should be raised after overflow");
    }

    @Test
    public void dacLatchProducesStereoOutput() {
        Ym2612Chip chip = new Ym2612Chip();
        chip.write(0, 0x2B, 0x80); // DAC enable
        // Pan both channels for channel 5 (port 1, reg B2)
        chip.write(1, 0xB2, 0xC0);
        chip.write(0, 0x2A, 0xFF); // Latch max unsigned PCM

        int[] left = new int[32];
        int[] right = new int[32];
        chip.renderStereo(left, right);

        boolean leftHas = false;
        for (int v : left) {
            if (v != 0) {
                leftHas = true;
                break;
            }
        }
        boolean rightHas = false;
        for (int v : right) {
            if (v != 0) {
                rightHas = true;
                break;
            }
        }
        assertTrue(leftHas, "DAC should produce left output");
        assertTrue(rightHas, "DAC should produce right output");
    }

    @Test
    public void multiChannelPanAndDacMixIsDeterministicAndBatchInvariant() {
        Ym2612Chip batched = new Ym2612Chip();
        Ym2612Chip single = new Ym2612Chip();
        configurePanAndDacMix(batched);
        configurePanAndDacMix(single);

        int[] left = new int[128];
        int[] right = new int[128];
        batched.renderStereo(left, right, 128);

        int[] leftSingle = new int[128];
        int[] rightSingle = new int[128];
        for (int frame = 0; frame < 128; frame++) {
            int[] l = new int[1];
            int[] r = new int[1];
            single.renderStereo(l, r, 1);
            leftSingle[frame] = l[0];
            rightSingle[frame] = r[0];
        }

        // Determinism and batch invariance: n frames in one call equal 1 frame n times.
        assertArrayEquals(left, leftSingle);
        assertArrayEquals(right, rightSingle);

        // Channel 0 pans left only, channel 1 and the DAC pan right only, so the
        // two sides carry different signals and both move.
        int[] leftSteady = java.util.Arrays.copyOfRange(left, 32, left.length);
        int[] rightSteady = java.util.Arrays.copyOfRange(right, 32, right.length);
        assertTrue(peakToPeak(leftSteady) > 100, "left side must carry channel 0");
        assertTrue(peakToPeak(rightSteady) > 100, "right side must carry channel 1 and the DAC");
        boolean sidesDiffer = false;
        for (int i = 32; i < left.length; i++) {
            sidesDiffer |= left[i] != right[i];
        }
        assertTrue(sidesDiffer, "pan must separate the sides");
    }

    private static void configurePanAndDacMix(Ym2612Chip chip) {
        configureSimpleVoice(chip, 0, 0, 0x80, 0x22, 0x00);
        configureSimpleVoice(chip, 0, 1, 0x40, 0x25, 0x34);

        chip.write(0, 0x28, 0xF0);
        chip.write(0, 0x28, 0xF1);

        chip.write(0, 0x2B, 0x80);
        chip.write(1, 0xB2, 0x40);
        chip.write(0, 0x2A, 0xD0);
    }

    /**
     * forceSilenceChannel is the SFX-steal policy: a channel looping under
     * SSG-EG must fall silent immediately and stay silent, with no key-off
     * write and no envelope tail, so the next voice load starts clean.
     */
    @Test
    public void forceSilenceChannelStopsAnSsgEgLoopingChannel() {
        Ym2612Chip chip = new Ym2612Chip();
        configureSimpleVoice(chip);

        // Enable SSG-EG repeat on all 4 operators of channel 0: the envelope
        // loops for ever instead of decaying.
        chip.write(0, 0x90, 0x08);
        chip.write(0, 0x94, 0x08);
        chip.write(0, 0x98, 0x08);
        chip.write(0, 0x9C, 0x08);

        int[] left = new int[2048];
        int[] right = new int[2048];
        chip.renderStereo(left, right);
        assertTrue(peakToPeak(java.util.Arrays.copyOfRange(left, 64, left.length)) > 100,
                "SSG-EG loop must be audible before the forced silence");

        chip.forceSilenceChannel(0);

        // A short settling window for the pipeline, then the tail must be flat
        // at the chip's resting level: no oscillation, no envelope release.
        int[] tail = new int[4096];
        chip.renderStereo(tail, new int[tail.length]);
        int[] settled = java.util.Arrays.copyOfRange(tail, 256, tail.length);
        assertEquals(0, peakToPeak(settled),
                "forceSilenceChannel must leave the channel at its resting level");
    }

    private static int peakToPeak(int[] samples) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int sample : samples) {
            min = Math.min(min, sample);
            max = Math.max(max, sample);
        }
        return max - min;
    }

    private static void configureSimpleVoice(Ym2612Chip chip) {
        configureSimpleVoice(chip, 0, 0, 0xC0, 0x22, 0x00);
        chip.write(0, 0x28, 0xF0);
    }

    private static void configureSimpleVoice(Ym2612Chip chip, int port, int channel, int pan, int a4, int a0) {
        // Algorithm 7 (all carriers), no feedback, pan L+R on channel 0
        chip.write(port, 0xB0 + channel, 0x07);
        chip.write(port, 0xB4 + channel, pan);

        // FNUM/BLOCK for a mid-range pitch: the high byte is a latch the low
        // byte write consumes, so it goes first (hardware order).
        chip.write(port, 0xA4 + channel, a4);
        chip.write(port, 0xA0 + channel, a0);

        // Set fast attack/decay and low TL on all operators
        int[] slots = {0x00, 0x04, 0x08, 0x0C}; // slot offsets within operator reg blocks
        for (int slot : slots) {
            chip.write(port, 0x30 + slot + channel, 0x01); // DT/MUL: minimal detune, mul=1
            chip.write(port, 0x40 + slot + channel, 0x00); // TL: loud
            chip.write(port, 0x50 + slot + channel, 0x1F); // RS/AR: AR max
            chip.write(port, 0x60 + slot + channel, 0x10); // AM/D1R: moderate decay
            chip.write(port, 0x70 + slot + channel, 0x08); // D2R
            chip.write(port, 0x80 + slot + channel, 0x05); // D1L/RR
        }
    }
}


