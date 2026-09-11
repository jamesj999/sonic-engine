package com.openggf.tests;

import com.openggf.level.Pattern;
import com.openggf.level.rings.RingFrame;
import com.openggf.level.rings.RingFramePiece;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestRingSparkleDelay {

    @Test
    public void testS1SparkleDelayIs6() {
        RingSpriteSheet sheet = buildSheet(8, 6, 4, 4);
        assertEquals(6, sheet.getSparkleFrameDelay());
    }

    @Test
    public void testS2SparkleDelayIs8() {
        RingSpriteSheet sheet = buildSheet(8, 8, 4, 4);
        assertEquals(8, sheet.getSparkleFrameDelay());
    }

    @Test
    public void testS3kSparkleDelayIs5() {
        RingSpriteSheet sheet = buildSheet(8, 5, 4, 4);
        assertEquals(5, sheet.getSparkleFrameDelay());
    }

    @Test
    public void testDefaultConstructorUsesSpinDelayForSparkle() {
        Pattern pattern = new Pattern();
        RingFramePiece piece = new RingFramePiece(0, 0, 1, 1, 0, false, false, 0);
        RingFrame frame = new RingFrame(List.of(piece));
        List<RingFrame> frames = List.of(frame, frame, frame, frame, frame, frame, frame, frame);
        RingSpriteSheet sheet = new RingSpriteSheet(new Pattern[]{pattern}, frames, 1, 8, 4, 4);
        assertEquals(8, sheet.getSparkleFrameDelay());
    }

    private RingSpriteSheet buildSheet(int spinDelay, int sparkleDelay, int spinFrames, int sparkleFrames) {
        Pattern pattern = new Pattern();
        RingFramePiece piece = new RingFramePiece(0, 0, 1, 1, 0, false, false, 0);
        RingFrame frame = new RingFrame(List.of(piece));
        List<RingFrame> frames = new java.util.ArrayList<>();
        for (int i = 0; i < spinFrames + sparkleFrames; i++) {
            frames.add(frame);
        }
        return new RingSpriteSheet(new Pattern[]{pattern}, frames, 1, spinDelay, sparkleDelay, spinFrames, sparkleFrames);
    }
}


