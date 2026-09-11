package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestS3kSlotMachinePanelAnimator {

    @Test
    void reelDestinationsMatchSonLvlAnimatedTileMetadata() {
        assertArrayEquals(new int[] {0x200, 0x210, 0x220},
                S3kSlotMachinePanelAnimator.destinationPatternBasesForTest());
    }

    @Test
    void buildVisibleWindowProducesSixteenPatternsForSingleReel() {
        byte[][] faces = new byte[8][32 * 32];
        fillFace(faces[1], (byte) 0x04);
        fillFace(faces[2], (byte) 0x09);

        Pattern[] patterns = S3kSlotMachinePanelAnimator.buildVisibleWindowPatternsForTest(
                faces, 1, 2, 0.0f);

        assertEquals(16, patterns.length);
        assertEquals(0x04, patterns[0].getPixel(0, 0));
        assertEquals(0x04, patterns[15].getPixel(7, 7));
    }

    @Test
    void buildVisibleWindowWrapsBottomRowsIntoNextFace() {
        byte[][] faces = new byte[8][32 * 32];
        fillFace(faces[3], (byte) 0x02);
        fillFace(faces[4], (byte) 0x0C);

        Pattern[] patterns = S3kSlotMachinePanelAnimator.buildVisibleWindowPatternsForTest(
                faces, 3, 4, 31 / 32f);

        assertEquals(0x02, patterns[0].getPixel(0, 0));
        assertEquals(0x0C, patterns[0].getPixel(0, 1));
        assertEquals(0x0C, patterns[15].getPixel(7, 7));
    }

    private static void fillFace(byte[] facePixels, byte value) {
        for (int i = 0; i < facePixels.length; i++) {
            facePixels[i] = value;
        }
    }
}


