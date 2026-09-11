package com.openggf.tests;

import com.openggf.audio.synth.BlipResampler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestBlipResampler {

    @Test
    void historyBuffersUseDocumentedCapacity() throws Exception {
        BlipResampler resampler = new BlipResampler(53267.041666666664, 44100.0);

        java.lang.reflect.Field historyL = BlipResampler.class.getDeclaredField("historyL");
        java.lang.reflect.Field historyR = BlipResampler.class.getDeclaredField("historyR");
        historyL.setAccessible(true);
        historyR.setAccessible(true);

        int[] left = (int[]) historyL.get(resampler);
        int[] right = (int[]) historyR.get(resampler);

        assertEquals(left.length, right.length, "Stereo histories should stay symmetric");
        assertTrue(left.length <= 8192, "History capacity should be reduced from the old 16384-sample footprint");
    }

    @Test
    void deterministicStereoSequenceRemainsBitExact() {
        BlipResampler resampler = new BlipResampler(53267.041666666664, 44100.0);
        for (int i = 0; i < 40; i++) {
            resampler.addInputSample(i * 100 - 500, 500 - i * 50);
            resampler.advanceInput();
        }

        int[] left = new int[8];
        int[] right = new int[8];
        int count = 0;
        while (count < left.length && resampler.hasOutputSample()) {
            left[count] = resampler.getOutputLeft();
            right[count] = resampler.getOutputRight();
            resampler.advanceOutput();
            count++;
        }

        assertEquals(8, count, "Test setup should yield the expected number of output samples");
        assertArrayEquals(new int[] {-467, -385, -262, -137, -21, 104, 222, 344}, left,
                "Left-channel interpolation should remain bit-exact for this deterministic sequence");
        assertArrayEquals(new int[] {469, 445, 382, 317, 262, 198, 139, 78}, right,
                "Right-channel interpolation should remain bit-exact for this deterministic sequence");
    }

    @Test
    void repeatedChannelReadsWithoutStateChangeRemainStable() {
        BlipResampler resampler = new BlipResampler(53267.041666666664, 44100.0);
        for (int i = 0; i < 24; i++) {
            resampler.addInputSample(i * 64, 1000 - i * 32);
            resampler.advanceInput();
        }

        assertTrue(resampler.hasOutputSample(), "Test setup should produce at least one output sample");

        int left1 = resampler.getOutputLeft();
        int left2 = resampler.getOutputLeft();
        int right1 = resampler.getOutputRight();
        int right2 = resampler.getOutputRight();

        assertEquals(left1, left2, "Left output should be stable when state has not advanced");
        assertEquals(right1, right2, "Right output should be stable when state has not advanced");
    }
}
