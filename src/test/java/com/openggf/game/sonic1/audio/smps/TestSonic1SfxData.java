package com.openggf.game.sonic1.audio.smps;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TestSonic1SfxData {

    @Test
    void zeroAddressVoiceUsesTheSameOperatorNormalizationAsS1Voices() {
        byte[] romVectorArea = new byte[50];
        for (int index = 0; index < romVectorArea.length; index++) {
            romVectorArea[index] = (byte) index;
        }
        Sonic1SfxData data = new Sonic1SfxData(new byte[] {0, 0}, 0);
        data.setZeroAddressVoiceBank(romVectorArea);

        byte[] expected = Arrays.copyOfRange(romVectorArea, 25, 50);
        for (int group = 1; group < expected.length; group += 4) {
            byte middle = expected[group + 1];
            expected[group + 1] = expected[group + 2];
            expected[group + 2] = middle;
        }

        assertArrayEquals(expected, data.getZeroAddressFmVoice(1));
    }
}
