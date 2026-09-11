package com.openggf.graphics;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpritePieceRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestGraphicsManagerSpriteSatReplay {

    private GraphicsManager graphicsManager;

    @BeforeEach
    public void setUp() {
        GraphicsManager.destroyForReinit();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        graphicsManager = GraphicsManager.getInstance();
        graphicsManager.initHeadless();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
        GraphicsManager.destroyForReinit();
    }

    @Test
    public void spriteSatReplay_replaysBucketsBackToFrontWhilePreservingOrderWithinEachBucket() throws Exception {
        graphicsManager.cachePatternTexture(createSolidPattern((byte) 1), 0);
        graphicsManager.cachePatternTexture(createSolidPattern((byte) 2), 1);
        graphicsManager.cachePatternTexture(createSolidPattern((byte) 3), 2);

        graphicsManager.beginSpriteSatCollection();
        graphicsManager.setCurrentSpriteSatBucket(2);
        graphicsManager.submitSpriteSatPiece(new SpritePieceRenderer.PreparedPiece(
                10, 20,
                1, 1,
                0, 0,
                1,
                false, false,
                false, true,
                SpriteMaskReplayRole.NORMAL,
                0, 1,
                0, 1,
                "first"));
        graphicsManager.setCurrentSpriteSatBucket(4);
        graphicsManager.submitSpriteSatPiece(new SpritePieceRenderer.PreparedPiece(
                20, 20,
                1, 1,
                1, 1,
                1,
                false, false,
                false, true,
                SpriteMaskReplayRole.NORMAL,
                0, 1,
                0, 1,
                "second"));
        graphicsManager.setCurrentSpriteSatBucket(2);
        graphicsManager.submitSpriteSatPiece(new SpritePieceRenderer.PreparedPiece(
                30, 20,
                1, 1,
                2, 2,
                2,
                false, false,
                false, true,
                SpriteMaskReplayRole.NORMAL,
                0, 1,
                0, 1,
                "third"));

        graphicsManager.endSpriteSatCollectionAndReplay();

        assertEquals(3, graphicsManager.commands.size());
        assertTrue(graphicsManager.commands.get(0) instanceof PatternRenderCommand);
        assertTrue(graphicsManager.commands.get(1) instanceof PatternRenderCommand);
        assertTrue(graphicsManager.commands.get(2) instanceof PatternRenderCommand);
        assertEquals(20f, getFloatField(graphicsManager.commands.get(0), "x"));
        assertEquals(10f, getFloatField(graphicsManager.commands.get(1), "x"));
        assertEquals(30f, getFloatField(graphicsManager.commands.get(2), "x"));
    }

    private static Pattern createSolidPattern(byte color) {
        Pattern pattern = new Pattern();
        for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
            for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                pattern.setPixel(x, y, color);
            }
        }
        return pattern;
    }

    private static float getFloatField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getFloat(target);
    }
}


