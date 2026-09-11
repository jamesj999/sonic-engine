package com.openggf.tests;

import com.openggf.audio.synth.PsgChip;
import com.openggf.audio.synth.VirtualSynthesizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mixed FM + PSG render is deterministic, carries both chips, does not
 * depend on how the frames are batched, and places each chip at the level
 * documented in
 * {@code docs/architecture/validation/2026-08-29-audio-mix-calibration.md}.
 *
 * <p>The level assertions are stated in the mix's own units (16-bit output
 * after {@code MASTER_GAIN_SHIFT}) from the documented constants, not from a
 * pasted golden render: a change to any of the three numbers below is a
 * change to the calibration document, and the assertion messages say which.
 */
public class TestVirtualSynthesizerMix {

    private static final int FRAMES = 512;

    /** Calibration doc §2: one full-scale FM channel, 6144 at chip scale >> 1. */
    private static final int FM_FULL_SCALE_AT_MIX = 6144 >> 1;
    /** Calibration doc §2: one silent YM2612 pin cycle, {@code 3 << 3 >> 1}. */
    private static final int FM_SILENT_CYCLE_AT_MIX = (3 << 3) >> 1;
    /** Calibration doc §2: six silent channels x four cycles, {@code 72 << 3 >> 1}. */
    private static final int FM_RESTING_LEVEL_AT_MIX = (72 << 3) >> 1;
    /** Calibration doc §3: one full-scale PSG channel, 8191 x 38 % >> 1. */
    private static final int PSG_FULL_SCALE_AT_MIX = (PsgChip.FULL_SCALE * VirtualSynthesizer.PSG_PREAMP_PERCENT / 100) >> 1;
    /** Calibration doc §3: the FM:PSG ratio the mixer restores, 8191 / 4200 on develop. */
    private static final double DEVELOP_FM_TO_PSG_RATIO = 8191.0 / 4200.0;

    @Test
    public void mixedFmAndPsgOutputIsDeterministicAndBatchInvariant() {
        VirtualSynthesizer batched = new VirtualSynthesizer();
        VirtualSynthesizer again = new VirtualSynthesizer();
        VirtualSynthesizer fmOnly = new VirtualSynthesizer();
        VirtualSynthesizer fmSingle = new VirtualSynthesizer();
        VirtualSynthesizer psgOnly = new VirtualSynthesizer();
        configurePsg(batched);
        configureFm(batched);
        configurePsg(again);
        configureFm(again);
        configureFm(fmOnly);
        configureFm(fmSingle);
        configurePsg(psgOnly);

        short[] batchedOut = new short[FRAMES * 2];
        batched.render(batchedOut);
        short[] againOut = new short[FRAMES * 2];
        again.render(againOut);
        assertArrayEquals(batchedOut, againOut, "the mix must be deterministic");

        // The FM chip renders n frames in one call exactly as 1 frame n times.
        short[] fmOut = new short[FRAMES * 2];
        fmOnly.render(fmOut);
        short[] fmSingleOut = new short[FRAMES * 2];
        for (int frame = 0; frame < FRAMES; frame++) {
            fmSingle.renderFrames(fmSingleOut, frame, 1);
        }
        assertArrayEquals(fmOut, fmSingleOut, "one call of n frames must equal n calls of one frame");

        short[] psgOut = new short[FRAMES * 2];
        psgOnly.render(psgOut);
        assertTrue(peakToPeak(fmOut) > 1000, "FM channel must be audible on its own");
        assertTrue(peakToPeak(psgOut) > 1000, "PSG channel must be audible on its own");

        // Both chips accumulate into the same buffer before the master gain, so
        // the mix differs from either chip alone.
        boolean differsFromFm = false;
        boolean differsFromPsg = false;
        for (int i = 0; i < batchedOut.length; i++) {
            differsFromFm |= batchedOut[i] != fmOut[i];
            differsFromPsg |= batchedOut[i] != psgOut[i];
        }
        assertTrue(differsFromFm && differsFromPsg, "mix must carry both chips");
    }

    private static int peakToPeak(short[] samples) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (short sample : samples) {
            min = Math.min(min, sample);
            max = Math.max(max, sample);
        }
        return max - min;
    }

    @Test
    public void restingLevelIsTheDocumentedYm2612Offset() {
        // Calibration doc §2: a silenced synthesiser rests at +288 on both
        // sides (the discrete YM2612 model's +3 per silent pin cycle, 24
        // cycles, << 3, >> MASTER_GAIN_SHIFT); the PSG rests at 0.
        VirtualSynthesizer synth = new VirtualSynthesizer();
        short[] out = new short[FRAMES * 2];
        synth.render(out);
        assertEquals(288, FM_RESTING_LEVEL_AT_MIX, "documented resting level");
        assertEquals(FM_RESTING_LEVEL_AT_MIX, out[out.length - 2], "resting level, left");
        assertEquals(FM_RESTING_LEVEL_AT_MIX, out[out.length - 1], "resting level, right");
    }

    @Test
    public void fullScaleFmChannelLandsAtTheDocumentedLevel() {
        // Calibration doc §2: one full-scale FM channel is 6144 at chip scale,
        // 3072 at the mix. The DAC is the one way to hold a channel at a known
        // constant: 0x2A = 0x00 is exactly -256 (full-scale negative) and
        // 0x2A = 0xFF is +255 (one 9-bit step under full-scale positive), and
        // in YM2612 mode the channel's three sign cycles follow the sign
        // (+3 -> -3 each for a negative sample).
        assertEquals(3072, FM_FULL_SCALE_AT_MIX, "documented FM full scale at the mix");
        VirtualSynthesizer synth = new VirtualSynthesizer();
        synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0x2B, 0x80);
        short[] out = new short[FRAMES * 2];

        synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0x2A, 0x00);
        synth.render(out);
        int negative = FM_RESTING_LEVEL_AT_MIX - FM_FULL_SCALE_AT_MIX
                - FM_SILENT_CYCLE_AT_MIX - 3 * 2 * FM_SILENT_CYCLE_AT_MIX;
        assertEquals(-2868, negative, "derivation of the DAC full-scale negative level");
        assertEquals(negative, out[out.length - 2], 1, "DAC -256: resting - full scale - the amplified cycle's"
                + " silent +3 - three sign cycles swinging +3 -> -3");

        synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0x2A, 0xFF);
        synth.render(out);
        int positive = FM_RESTING_LEVEL_AT_MIX - FM_SILENT_CYCLE_AT_MIX
                + FM_FULL_SCALE_AT_MIX * 255 / 256;
        assertEquals(3336, positive, "derivation of the DAC +255 level");
        assertEquals(positive, out[out.length - 2], 1, "DAC +255: resting - the amplified cycle's silent +3"
                + " + 255/256 of full scale");
    }

    @Test
    public void fullScalePsgChannelLandsAtTheDocumentedLevel() {
        // Calibration doc §3: PSG_PREAMP_PERCENT = 38 places one full-scale
        // PSG channel at 8191 x 38 % = 3112 at chip scale, 1556 at the mix.
        assertEquals(38, VirtualSynthesizer.PSG_PREAMP_PERCENT, "documented PSG preamp");
        assertEquals(1556, PSG_FULL_SCALE_AT_MIX, "documented PSG full scale at the mix");

        // The configured amplitude is exact: the mixer's preamp is what the
        // chip snapshot carries.
        VirtualSynthesizer synth = new VirtualSynthesizer();
        assertEquals(VirtualSynthesizer.PSG_PREAMP_PERCENT, synth.captureSynthSnapshot().psg().preamp(),
                "the mixer configures the chip's output-stage preamp");
        assertEquals(PSG_FULL_SCALE_AT_MIX,
                (PsgChip.attenuationLevel(0) * synth.captureSynthSnapshot().psg().preamp() / 100) >> 1,
                "attenuation 0 through the configured preamp and MASTER_GAIN_SHIFT");

        // The rendered level: tone 0 held high (period 0, §3.2 of the SN76489
        // spec) at attenuation 0 is a DC step of the full-scale level on top
        // of the FM resting level. The PSG path is band-limited and AC-coupled
        // (BlipDeltaBuffer, bass shift 9), so the step's peak carries the
        // kernel's overshoot and then droops; the peak is asserted to within
        // 0.5 % of the documented level rather than 1 LSB.
        synth.writePsg(TestVirtualSynthesizerMix.class, 0x80);
        synth.writePsg(TestVirtualSynthesizerMix.class, 0x00);
        synth.writePsg(TestVirtualSynthesizerMix.class, 0x90);
        short[] out = new short[FRAMES * 2];
        synth.render(out);
        int peak = Integer.MIN_VALUE;
        for (int i = 0; i < out.length; i += 2) {
            peak = Math.max(peak, out[i]);
        }
        assertEquals(FM_RESTING_LEVEL_AT_MIX + PSG_FULL_SCALE_AT_MIX, peak, PSG_FULL_SCALE_AT_MIX * 0.005,
                "full-scale PSG DC step on top of the FM resting level");
    }

    @Test
    public void fmToPsgRatioIsTheDocumentedPreRewriteParity() {
        // Calibration doc §3: 6144 / (8191 x 38 %) = 1.974 (+5.90 dB), the
        // closest whole-percent preamp to develop's 8191 / 4200 = 1.950
        // (+5.80 dB); the residual is the +0.10 dB of percent granularity.
        double ratio = (double) FM_FULL_SCALE_AT_MIX / PSG_FULL_SCALE_AT_MIX;
        assertEquals(1.974, ratio, 0.001, "documented FM:PSG ratio");
        double residualDb = 20 * Math.log10(ratio / DEVELOP_FM_TO_PSG_RATIO);
        assertEquals(0.10, residualDb, 0.01, "residual against the develop balance, dB");
        assertTrue(Math.abs(residualDb) < 20 * Math.log10(
                (double) FM_FULL_SCALE_AT_MIX / ((PsgChip.FULL_SCALE * 37 / 100) >> 1) / DEVELOP_FM_TO_PSG_RATIO)
                && Math.abs(residualDb) < Math.abs(20 * Math.log10(
                (double) FM_FULL_SCALE_AT_MIX / ((PsgChip.FULL_SCALE * 39 / 100) >> 1) / DEVELOP_FM_TO_PSG_RATIO)),
                "38 % is the whole percent closest to the develop ratio");
    }

    private static void configurePsg(VirtualSynthesizer synth) {
        synth.writePsg(TestVirtualSynthesizerMix.class, 0x80);
        synth.writePsg(TestVirtualSynthesizerMix.class, 0x20);
        synth.writePsg(TestVirtualSynthesizerMix.class, 0x90);
    }

    private static void configureFm(VirtualSynthesizer synth) {
        synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0xB0, 0x07);
        synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0xB4, 0xC0);
        synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0xA4, 0x22);
        synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0xA0, 0x00);

        int[] slots = {0x00, 0x04, 0x08, 0x0C};
        for (int slot : slots) {
            synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0x30 + slot, 0x01);
            synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0x40 + slot, 0x00);
            synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0x50 + slot, 0x1F);
            synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0x60 + slot, 0x10);
            synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0x70 + slot, 0x08);
            synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0x80 + slot, 0x05);
        }

        synth.writeFm(TestVirtualSynthesizerMix.class, 0, 0x28, 0xF0);
    }
}
