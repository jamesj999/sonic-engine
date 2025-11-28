package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.Ym2612Chip;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestYm2612SsgEg {

    @Test
    public void ssgEgRepeatLoopsEnvelope() {
        Ym2612Chip chip = new Ym2612Chip();

        // Configure Channel 0
        chip.write(0, 0xB0, 0xC7); // Algo 7, LR Pan

        chip.write(0, 0xA0, 0x00);
        chip.write(0, 0xA4, 0x22); // Freq

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
        short[] buffer = new short[4000];
        chip.render(buffer);

        // Check tail of buffer is silent (or near silent)
        boolean tailSilent = true;
        int maxVal = 0;
        for (int i = 3000; i < 4000; i++) {
            int val = Math.abs(buffer[i]);
            if (val > maxVal) maxVal = val;
            if (val > 100) {
                tailSilent = false;
            }
        }
        if (!tailSilent) {
             org.junit.Assert.fail("Standard ADSR tail not silent. Max value: " + maxVal);
        }

        // Now RESET and try with SSG-EG
        chip.reset();
        chip.write(0, 0xB0, 0xC7);
        chip.write(0, 0xA0, 0x00);
        chip.write(0, 0xA4, 0x22);

        chip.write(0, 0x30, 0x01);
        chip.write(0, 0x40, 0x00);
        chip.write(0, 0x50, 0x10); // Slower Attack
        chip.write(0, 0x60, 0x10); // Slower Decay
        chip.write(0, 0x70, 0x00);
        chip.write(0, 0x80, 0xFF);

        // Enable SSG-EG Repeat (0x08)
        chip.write(0, 0x90, 0x08);

        chip.write(0, 0x28, 0xF0); // Key On

        buffer = new short[4000];
        chip.render(buffer);

        // With SSG-EG Repeat, it should bounce back and forth.
        // So tail should have some loud parts.
        boolean hasLoudSound = false;
        for (int i = 3000; i < 4000; i++) {
            if (Math.abs(buffer[i]) > 500) {
                hasLoudSound = true;
                break;
            }
        }
        assertTrue("SSG-EG Repeat should produce continuous loud sound (looping)", hasLoudSound);
    }
}
