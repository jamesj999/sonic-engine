package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.Ym2612Chip;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class TestYm2612TimerCSM {

    @Test
    public void timerAOverflowTriggersKeyOnInCSMMode() {
        Ym2612Chip chip = new Ym2612Chip();

        // Configure Channel 2 (Port 0, Regs A2, A6, B2, etc.)
        // Note: Channel 2 is at index 2.
        // Algo 7 (all carriers), no feedback
        chip.write(0, 0xB2, 0xC7); // Channel 2

        // FNUM/BLOCK
        chip.write(0, 0xA2, 0x00); // Low bits for Ch 2
        chip.write(0, 0xA6, 0x22); // High bits + block for Ch 2

        // Configure operators for Channel 2
        // Slots for Ch 2 are 2, 6, 10, 14 (0x02, 0x06, 0x0A, 0x0E) ??
        // Let's check Ym2612Chip.java write logic for 0x30-0x9F
        /*
            int slot = (reg & 0x0C) >> 2;
            int ch = (port * 3) + (reg & 0x03);

            For Ch 2 (index 2):
            port = 0, reg & 0x03 must be 2.
            So regs are 0x32, 0x36, 0x3A, 0x3E etc.
         */
        int[] regOffsets = {0x02, 0x06, 0x0A, 0x0E};
        for (int off : regOffsets) {
            chip.write(0, 0x30 + off, 0x01); // MUL=1
            chip.write(0, 0x40 + off, 0x00); // TL=0 (max volume)
            chip.write(0, 0x50 + off, 0x1F); // AR=31 (fast attack)
            chip.write(0, 0x60 + off, 0x00); // DR=0
            chip.write(0, 0x70 + off, 0x00); // SR=0
            chip.write(0, 0x80 + off, 0x0F); // RR=15
        }

        // Verify it is silent initially (no Key On)
        int[] left = new int[100];
        int[] right = new int[100];
        chip.renderStereo(left, right);
        boolean silent = true;
        for (int s : left) {
            if (s != 0) {
                silent = false;
                break;
            }
        }
        assertTrue("Channel 2 should be silent before Key On", silent);

        // Configure Timer A
        chip.write(0, 0x24, 0x00); // Timer A High
        chip.write(0, 0x25, 0x01); // Timer A Low (short period)

        // Enable CSM (Bit 7) and Timer A (Bit 0) -> 0x81
        chip.write(0, 0x27, 0x81);

        // Render enough samples for Timer A to overflow
        // Timer Base is roughly 4096 / 144 * (Clock/Rate) per sample.
        // Timer A Load is (1024 - Period) << 12.
        // With Period ~1 (small), Load is large? No wait.
        // Timer A period value in register is 10 bits.
        // Reg 0x24 = 0x00, Reg 0x25 = 0x01 => Period = 1.
        // TimerALoad = (1024 - 1) * 4096 = 1023 * 4096 = 4190208
        // Tick per sample ~ (7670453 / 44100) * (4096 / 144) ~ 174 * 28.4 ~ 4948
        // Samples to overflow = 4190208 / 4948 ~ 846 samples.

        // Let's render 2000 samples to be sure.
        left = new int[2000];
        right = new int[2000];
        chip.renderStereo(left, right);

        boolean hasSound = false;
        for (int s : left) {
            if (s != 0) {
                hasSound = true;
                break;
            }
        }
        assertTrue("Channel 2 should produce sound after Timer A overflow in CSM mode", hasSound);
    }
}
