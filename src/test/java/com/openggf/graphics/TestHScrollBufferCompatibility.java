package com.openggf.graphics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestHScrollBufferCompatibility {

    @Test
    void shorterArrayZeroFillsEveryMissingScanlineLikeIndexedView() {
        HScrollBuffer buffer = new HScrollBuffer();

        int[] longFrame = new int[HScrollBuffer.VISIBLE_LINES];
        Arrays.fill(longFrame, 0x0000_4000);
        buffer.stageForUpload(longFrame);

        buffer.stageForUpload(new int[] {0x0000_2000});

        assertEquals(0x2000 / 32767.0f, buffer.stagedScrollAt(0));
        for (int line = 1; line < HScrollBuffer.VISIBLE_LINES; line++) {
            assertEquals(0.0f, buffer.stagedScrollAt(line), "missing scanline " + line + " must be zero");
        }
    }
}
