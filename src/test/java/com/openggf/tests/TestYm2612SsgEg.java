package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.audio.synth.Ym2612Chip;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestYm2612SsgEg {

    @Test
    public void ssgEgRepeatLoopsEnvelope() {
        Ym2612Chip chip = new Ym2612Chip();
        int restingLevel = restingLevel();

        // Configure Channel 0
        chip.write(0, 0xB0, 0xC7); // Algo 7, LR Pan

        chip.write(0, 0xA4, 0x22); // Freq: block/fnum high latch first (hardware order)
        chip.write(0, 0xA0, 0x00);

        // Configure Operator 0 (Slot 0)
        // Enable SSG-EG: Reg 0x90.
        // Bit 3 = Enable, Bit 0-2 = Mode.
        // Mode 000 (0) = Normal (Repeat) ?
        // Example: 0x08 = Enable(1) 000.
        // 0x90 write:
        // chip.write(0, 0x90, 0x08); // Slot 0, Enable SSG, Repeat.

        // Settings:
        chip.write(0, 0x30, 0x01); // MUL=1
        chip.write(0, 0x40, 0x00); // TL=0 (Max vol)
        chip.write(0, 0x50, 0x1F); // AR=31 (Max)
        chip.write(0, 0x60, 0x1F); // D1R=31 (Max) - Fast decay
        chip.write(0, 0x70, 0x00); // D2R=0
        chip.write(0, 0x80, 0xFF); // D1L=15 (Max attenuation), RR=15

        // With SSG-EG DISABLED (0x00), this should decay to silence quickly and stay there.
        // Let's verify that first.
        chip.write(0, 0x90, 0x00);
        chip.write(0, 0x28, 0xF0); // Key On Ch 0

        // Render enough to decay. D1R=31 is very fast.
        int[] left = new int[4000];
        int[] right = new int[4000];
        chip.renderStereo(left, right);

        // Check tail of buffer is relatively quiet (decay should have completed)
        // Note: With GPGX-style EG, other unconfigured operators on the channel
        // may still produce some residual output. We just verify it's much quieter
        // than the initial sound.
        int maxVal = 0;
        for (int i = 3000; i < 4000; i++) {
            int val = Math.abs(left[i] - restingLevel);
            if (val > maxVal) maxVal = val;
        }
        // The tail should be significantly quieter than a full-scale channel,
        // measured as the swing around the chip's resting level.
        assertTrue(maxVal < 4000, "Standard ADSR tail should be quiet after decay. Max value: " + maxVal);

        // Now RESET and try with SSG-EG
        chip.reset();
        chip.write(0, 0xB0, 0xC7);
        chip.write(0, 0xA4, 0x22);
        chip.write(0, 0xA0, 0x00);

        chip.write(0, 0x30, 0x01);
        chip.write(0, 0x40, 0x00);
        chip.write(0, 0x50, 0x10); // Slower Attack
        chip.write(0, 0x60, 0x10); // Slower Decay
        chip.write(0, 0x70, 0x00);
        chip.write(0, 0x80, 0xFF);

        // Enable SSG-EG Repeat (0x08)
        chip.write(0, 0x90, 0x08);

        chip.write(0, 0x28, 0xF0); // Key On

        left = new int[4000];
        right = new int[4000];
        chip.renderStereo(left, right);

        // With SSG-EG Repeat, it should bounce back and forth.
        // So tail should have some loud parts.
        boolean hasLoudSound = false;
        for (int i = 3000; i < 4000; i++) {
            if (Math.abs(left[i] - restingLevel) > 500) {
                hasLoudSound = true;
                break;
            }
        }
        assertTrue(hasLoudSound, "SSG-EG Repeat should produce continuous loud sound (looping)");
    }

    /** The level a silenced chip rests at (the discrete YM2612 model sits above zero). */
    private static int restingLevel() {
        Ym2612Chip silent = new Ym2612Chip();
        silent.silenceAll();
        int[] left = new int[256];
        silent.renderStereo(left, new int[left.length]);
        return left[left.length - 1];
    }
}


