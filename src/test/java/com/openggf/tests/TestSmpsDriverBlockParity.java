package com.openggf.tests;

import com.openggf.audio.synth.VirtualSynthesizer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSmpsDriverBlockParity {
    @Test
    void virtualSynthesizerRenderFramesWritesExpectedSlice() {
        VirtualSynthesizer actual = new VirtualSynthesizer();
        VirtualSynthesizer expected = new VirtualSynthesizer();
        configurePsgTone(actual);
        configurePsgTone(expected);

        short[] expectedBuffer = new short[64];
        expected.render(expectedBuffer);
        short[] actualBuffer = new short[68];
        Arrays.fill(actualBuffer, (short) 0x1234);

        actual.renderFrames(actualBuffer, 1, expectedBuffer.length / 2);

        assertEquals((short) 0x1234, actualBuffer[0]);
        assertEquals((short) 0x1234, actualBuffer[1]);
        assertEquals((short) 0x1234, actualBuffer[66]);
        assertEquals((short) 0x1234, actualBuffer[67]);
        assertArrayEquals(expectedBuffer, Arrays.copyOfRange(actualBuffer, 2, 66));
    }

    private static void configurePsgTone(VirtualSynthesizer synth) {
        synth.writePsg(TestSmpsDriverBlockParity.class, 0x80);
        synth.writePsg(TestSmpsDriverBlockParity.class, 0x08);
        synth.writePsg(TestSmpsDriverBlockParity.class, 0x90);
    }
}
