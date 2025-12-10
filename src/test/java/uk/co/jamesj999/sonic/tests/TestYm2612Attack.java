package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.Ym2612Chip;

import static org.junit.Assert.assertTrue;

public class TestYm2612Attack {

    @Test
    public void slowAttackIncreasesVolume() {
        Ym2612Chip chip = new Ym2612Chip();

        chip.write(0, 0xB0, 0xC7); // Algo 7
        chip.write(0, 0xA0, 0x00);
        chip.write(0, 0xA4, 0x22);

        // Slot 0
        chip.write(0, 0x30, 0x01);
        chip.write(0, 0x40, 0x00); // TL=0 (Max)
        chip.write(0, 0x50, 0x08); // AR=8 (Slow)
        chip.write(0, 0x60, 0x00); // D1R=0
        chip.write(0, 0x70, 0x00); // D2R=0
        chip.write(0, 0x80, 0x0F); // RR=15

        chip.write(0, 0x28, 0xF0); // Key On

        int[] l1 = new int[2000];
        int[] r1 = new int[2000];
        chip.renderStereo(l1, r1);
        int max1 = getMax(l1);

        int[] l2 = new int[2000];
        int[] r2 = new int[2000];
        chip.renderStereo(l2, r2);
        int max2 = getMax(l2);

        int[] l3 = new int[2000];
        int[] r3 = new int[2000];
        chip.renderStereo(l3, r3);
        int max3 = getMax(l3);

        // We expect volume to increase: max1 < max2 < max3
        // Note: initial samples might be 0.

        assertTrue("Volume should increase during attack. " + max1 + " -> " + max2 + " -> " + max3,
                   max3 > max1);
        assertTrue("Volume should increase during attack. " + max2 + " -> " + max3,
                   max3 >= max2);
    }

    private int getMax(int[] buf) {
        int m = 0;
        for (int s : buf) {
            int abs = Math.abs(s);
            if (abs > m) m = abs;
        }
        return m;
    }
}
