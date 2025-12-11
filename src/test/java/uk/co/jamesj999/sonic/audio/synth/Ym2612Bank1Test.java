package uk.co.jamesj999.sonic.audio.synth;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class Ym2612Bank1Test {

    private Ym2612Chip chip;
    private int[] leftBuf;
    private int[] rightBuf;

    @Before
    public void setUp() {
        chip = new Ym2612Chip();
        chip.reset();
        leftBuf = new int[100];
        rightBuf = new int[100];
    }

    @Test
    public void testPanLeft_FM5() {
        // Channel 4 (FM5) setup
        chip.write(1, 0xB1, 0x07); // Algo 7
        chip.write(1, 0xB5, 0x80); // Pan Left
        chip.write(1, 0x31, 0x01); // MUL=1
        chip.write(1, 0x41, 0x00); // TL=0
        chip.write(1, 0x51, 0x1F); // AR=31
        chip.write(1, 0x81, 0x00); // SL=0
        chip.write(1, 0xA5, 0x20); // Block 4
        chip.write(1, 0xA1, 0x44); // FNum
        chip.write(0, 0x28, 0xF5); // Key On Ch4 (Index 4)

        chip.renderStereo(leftBuf, rightBuf);

        int leftSum = 0;
        int rightSum = 0;
        for (int i = 0; i < leftBuf.length; i++) {
            leftSum += Math.abs(leftBuf[i]);
            rightSum += Math.abs(rightBuf[i]);
        }

        assertTrue("Left Buffer should have sound", leftSum > 0);
        assertEquals("Right Buffer should be silent", 0, rightSum);
    }

    @Test
    public void testPanRight_FM5() {
        // Channel 4 (FM5) setup
        chip.write(1, 0xB1, 0x07);
        chip.write(1, 0xB5, 0x40); // Pan Right
        chip.write(1, 0x31, 0x01);
        chip.write(1, 0x41, 0x00);
        chip.write(1, 0x51, 0x1F);
        chip.write(1, 0x81, 0x00);
        chip.write(1, 0xA5, 0x20);
        chip.write(1, 0xA1, 0x44);
        chip.write(0, 0x28, 0xF5);

        chip.renderStereo(leftBuf, rightBuf);

        int leftSum = 0;
        int rightSum = 0;
        for (int i = 0; i < leftBuf.length; i++) {
            leftSum += Math.abs(leftBuf[i]);
            rightSum += Math.abs(rightBuf[i]);
        }

        assertEquals("Left Buffer should be silent", 0, leftSum);
        assertTrue("Right Buffer should have sound", rightSum > 0);
    }
}
