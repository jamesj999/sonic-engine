package com.openggf.capture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapturedFrameTest {

    @Test
    void acceptsConsistentDimensionsAndPcm() {
        CapturedFrame f = new CapturedFrame(new byte[2 * 3 * 4], 2, 3,
                new short[800 * 2], 800, 7L);
        assertEquals(2, f.width());
        assertEquals(3, f.height());
        assertEquals(800, f.sampleCount());
        assertEquals(7L, f.frameIndex());
    }

    @Test
    void rejectsRgbaLengthMismatch() {
        assertThrows(IllegalArgumentException.class, () ->
                new CapturedFrame(new byte[10], 2, 3, new short[0], 0, 0L));
    }

    @Test
    void rejectsPcmShorterThanSampleCount() {
        assertThrows(IllegalArgumentException.class, () ->
                new CapturedFrame(new byte[4], 1, 1, new short[2], 800, 0L));
    }

    @Test
    void defensivelyCopiesSourceArraysSoProducerCanReuseBuffers() {
        byte[] rgba = new byte[]{1, 2, 3, 4};
        short[] pcm = new short[]{10, 20};
        CapturedFrame f = new CapturedFrame(rgba, 1, 1, pcm, 1, 0L);

        // Producer reuses/overwrites its buffers after submitting the frame.
        rgba[0] = 99;
        pcm[0] = 99;

        assertEquals(1, f.rgba()[0], "frame holds its own rgba copy");
        assertEquals(10, f.pcm()[0], "frame holds its own pcm copy");
    }
}
