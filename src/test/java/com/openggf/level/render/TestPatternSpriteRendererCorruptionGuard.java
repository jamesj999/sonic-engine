package com.openggf.level.render;

import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.ObjectSpriteSheet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestPatternSpriteRendererCorruptionGuard {

    @Test
    public void corruptMappingFrameIsLoggedAndSuppressedOnce() {
        List<SpriteMappingPiece> pieces = new ArrayList<>();
        for (int i = 0; i < 81; i++) {
            pieces.add(new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0));
        }
        RecordingGraphicsManager graphics = new RecordingGraphicsManager();
        PatternSpriteRenderer renderer = rendererFor(List.of(new SpriteMappingFrame(pieces)), new Pattern[]{new Pattern()},
                graphics);
        renderer.ensurePatternsCached(graphics, 0x400);

        ListHandler handler = new ListHandler();
        Logger logger = Logger.getLogger(PatternSpriteRenderer.class.getName());
        logger.addHandler(handler);
        try {
            renderer.drawFrameIndex(0, 32, 48);
            renderer.drawFrameIndex(0, 32, 48);
        } finally {
            logger.removeHandler(handler);
        }

        assertEquals(0, graphics.renderCalls, "Corrupt frame should be suppressed before tile rendering");
        assertEquals(1, handler.records.size(), "Invalid-frame cache should prevent per-frame log spam");
        assertTrue(handler.records.get(0).getMessage().contains("Suppressed suspicious sprite mapping frame"));
        assertTrue(handler.records.get(0).getMessage().contains("pieceCount=81"));
    }

    @Test
    public void normalMappingFrameStillRenders() {
        Pattern pattern = new Pattern();
        pattern.setPixel(0, 0, (byte) 1);
        RecordingGraphicsManager graphics = new RecordingGraphicsManager();
        PatternSpriteRenderer renderer = rendererFor(
                List.of(new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(-4, -4, 1, 1, 0, false, false, 0)))),
                new Pattern[]{pattern},
                graphics);
        renderer.ensurePatternsCached(graphics, 0x400);

        renderer.drawFrameIndex(0, 32, 48);

        assertEquals(1, graphics.renderCalls);
    }

    private static PatternSpriteRenderer rendererFor(
            List<SpriteMappingFrame> frames,
            Pattern[] patterns,
            RecordingGraphicsManager graphics) {
        return new PatternSpriteRenderer(new ObjectSpriteSheet(patterns, frames, 0, 1), graphics);
    }

    private static final class RecordingGraphicsManager extends GraphicsManager {
        int renderCalls;

        @Override
        public void cachePatternTexture(Pattern pattern, int patternId) {
            // No GL needed for renderer guard tests.
        }

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
            renderCalls++;
        }
    }

    private static final class ListHandler extends Handler {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
