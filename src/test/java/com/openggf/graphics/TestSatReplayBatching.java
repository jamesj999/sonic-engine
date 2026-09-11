package com.openggf.graphics;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpritePieceRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the SAT replay path routes through the instanced batcher when available:
 * one InstancedBatchCommand replaces N per-tile PatternRenderCommands, while
 * preserving the bucket-major replay order and per-tile VDP priority (carried as
 * per-instance data). Also proves the direct per-tile fallback is retained when
 * instanced batching is unavailable (headless default).
 */
public class TestSatReplayBatching {

    private static final int FLOATS_PER_INSTANCE = 10;

    private GraphicsManager graphicsManager;

    @BeforeEach
    public void setUp() {
        GraphicsManager.destroyForReinit();
        TestEnvironment.resetAll();
        graphicsManager = GraphicsManager.getInstance();
        graphicsManager.initHeadless();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
        GraphicsManager.destroyForReinit();
    }

    @Test
    public void satReplayBatchesIntoSingleInstancedCommandPreservingOrderAndPriority() throws Exception {
        installInstancedRenderer();
        cachePatterns(3);

        graphicsManager.beginSpriteSatCollection();
        graphicsManager.setCurrentSpriteSatBucket(2);
        graphicsManager.submitSpriteSatPiece(piece(10, 20, 0, false)); // bucket 2, low priority
        graphicsManager.setCurrentSpriteSatBucket(4);
        graphicsManager.submitSpriteSatPiece(piece(20, 20, 1, true));  // bucket 4, high priority
        graphicsManager.setCurrentSpriteSatBucket(2);
        graphicsManager.submitSpriteSatPiece(piece(30, 20, 2, false)); // bucket 2, low priority

        // Mirror the production sprite pass: the priority shader flag is set during
        // collection/replay but cleared again before the command queue flushes.
        graphicsManager.setUseSpritePriorityShader(true);
        graphicsManager.endSpriteSatCollectionAndReplay();

        assertEquals(1, graphicsManager.commands.size(),
                "all replay tiles should collapse into a single instanced batch command");
        Object batchCommand = graphicsManager.commands.get(0);
        assertEquals("InstancedBatchCommand", batchCommand.getClass().getSimpleName());

        assertEquals(3, getIntField(batchCommand, "instanceCount"));
        FloatBuffer instanceBuffer = (FloatBuffer) getField(batchCommand, "instanceBuffer");

        // Bucket-major order: bucket 4 first, then bucket 2 in submission order —
        // identical to the direct (unbatched) replay path's command order.
        assertEquals(20f, instanceBuffer.get(0), "first instance x");
        assertEquals(10f, instanceBuffer.get(FLOATS_PER_INSTANCE), "second instance x");
        assertEquals(30f, instanceBuffer.get(2 * FLOATS_PER_INSTANCE), "third instance x");

        // Per-instance tile-occlusion mask survives batching (instance float 9):
        // a high-priority tile bypasses every palette line, while ordinary tiles
        // remain occludable by all four lines.
        assertEquals(0f, instanceBuffer.get(9), "bucket-4 piece bypasses tile occlusion");
        assertEquals(0xF, instanceBuffer.get(FLOATS_PER_INSTANCE + 9));
        assertEquals(0xF, instanceBuffer.get(2 * FLOATS_PER_INSTANCE + 9));

        // Parity with the direct path: its commands resolve the shader at flush time,
        // after the sprite pass cleared the priority-shader flag, so the batch must
        // capture usePriorityShader=false even though the flag was set during replay.
        assertEquals(false, getField(batchCommand, "usePriorityShader"));
        assertTrue(graphicsManager.isUseSpritePriorityShader(),
                "replay must restore the caller's priority-shader flag");
    }

    @Test
    public void satReplayWithoutInstancedRendererFallsBackToDirectCommands() {
        // Headless default: no instanced renderer. The pre-existing direct path
        // (one PatternRenderCommand per tile, bucket-major order) must be preserved.
        cachePatterns(2);

        graphicsManager.beginSpriteSatCollection();
        graphicsManager.setCurrentSpriteSatBucket(1);
        graphicsManager.submitSpriteSatPiece(piece(40, 8, 0, false));
        graphicsManager.setCurrentSpriteSatBucket(3);
        graphicsManager.submitSpriteSatPiece(piece(50, 8, 1, false));

        graphicsManager.endSpriteSatCollectionAndReplay();

        assertEquals(2, graphicsManager.commands.size());
        assertTrue(graphicsManager.commands.get(0) instanceof PatternRenderCommand);
        assertTrue(graphicsManager.commands.get(1) instanceof PatternRenderCommand);
    }

    @Test
    public void multiTilePieceTilesAllJoinTheSameBatch() throws Exception {
        installInstancedRenderer();
        cachePatterns(8);

        graphicsManager.beginSpriteSatCollection();
        graphicsManager.setCurrentSpriteSatBucket(2);
        // 2x2-tile piece expands to 4 tiles
        graphicsManager.submitSpriteSatPiece(new SpritePieceRenderer.PreparedPiece(
                64, 32,
                2, 2,
                0, 0,
                1,
                false, false,
                false, false,
                SpriteMaskReplayRole.NORMAL,
                0, 2,
                0, 2,
                "quad"));
        graphicsManager.endSpriteSatCollectionAndReplay();

        assertEquals(1, graphicsManager.commands.size());
        assertEquals(4, getIntField(graphicsManager.commands.get(0), "instanceCount"));
    }

    @Test
    void atlasPageTransitionsFlushIntoOrderedInstancedBatches() throws Exception {
        installInstancedRenderer();
        PatternAtlas atlas = new PatternAtlas(8, 8); // one tile per page, two-page fallback
        int virtualId = PatternAtlasRange.OBJECTS.base();
        PatternAtlas.Entry pageZero = atlas.cachePatternHeadless(createSolidPattern((byte) 1), 0);
        PatternAtlas.Entry pageOne = atlas.cachePatternHeadless(createSolidPattern((byte) 2), virtualId);
        assertEquals(0, pageZero.atlasIndex());
        assertEquals(1, pageOne.atlasIndex());
        setField(graphicsManager, "patternAtlas", atlas);
        setField(graphicsManager, "combinedPaletteTextureId", 1);

        graphicsManager.beginSpriteSatCollection();
        graphicsManager.submitSpriteSatPiece(piece(10, 20, 0, false));
        graphicsManager.submitSpriteSatPiece(piece(20, 20, virtualId, false));
        graphicsManager.submitSpriteSatPiece(piece(30, 20, 0, false));
        graphicsManager.endSpriteSatCollectionAndReplay();

        assertEquals(3, graphicsManager.commands.size(),
                "page 0 -> 1 -> 0 must flush at each page boundary without direct fallback");
        assertEquals(List.of("InstancedBatchCommand", "InstancedBatchCommand", "InstancedBatchCommand"),
                graphicsManager.commands.stream().map(c -> c.getClass().getSimpleName()).toList());
        assertEquals(0, getIntField(graphicsManager.commands.get(0), "atlasIndex"));
        assertEquals(1, getIntField(graphicsManager.commands.get(1), "atlasIndex"));
        assertEquals(0, getIntField(graphicsManager.commands.get(2), "atlasIndex"));
    }

    @Test
    void interruptedSatReplayCancelsPartialInstancedBatchWithoutDrawing() throws Exception {
        InstancedPatternRenderer renderer = installInstancedRenderer();
        PatternAtlas.Entry entry = new PatternAtlas.Entry(1, 0, 1, 0, 0, 0, 0, 1, 1);
        setField(graphicsManager, "patternAtlas", new PatternAtlas(8, 8) {
            private int lookups;

            @Override
            public Entry getEntry(int patternId) {
                if (lookups++ == 0) {
                    return entry;
                }
                throw new IllegalStateException("interrupted replay");
            }
        });
        setField(graphicsManager, "combinedPaletteTextureId", 1);

        graphicsManager.beginSpriteSatCollection();
        graphicsManager.submitSpriteSatPiece(piece(10, 20, 0, false));
        graphicsManager.submitSpriteSatPiece(piece(20, 20, 1, false));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                graphicsManager::endSpriteSatCollectionAndReplay);

        assertEquals("interrupted replay", failure.getMessage());
        assertFalse(renderer.isBatchActive());
        assertEquals(0, getIntField(renderer, "instanceCount"));
        assertEquals(0, graphicsManager.commands.size(), "cancelling must not queue or draw the partial batch");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Installs a GL-free InstancedPatternRenderer into the headless manager and
     * marks it supported. beginBatch/addPattern/endBatch perform no GL calls
     * (only InstancedBatchCommand.execute does), so the batching decision logic
     * is fully testable headlessly.
     */
    private InstancedPatternRenderer installInstancedRenderer() throws Exception {
        InstancedPatternRenderer renderer = new InstancedPatternRenderer(
                graphicsManager, com.openggf.game.GameServices.configuration());
        setField(renderer, "supported", true);
        setField(graphicsManager, "instancedPatternRenderer", renderer);
        return renderer;
    }

    private void cachePatterns(int count) {
        for (int i = 0; i < count; i++) {
            graphicsManager.cachePatternTexture(createSolidPattern((byte) (i + 1)), i);
        }
    }

    private static SpritePieceRenderer.PreparedPiece piece(int x, int y, int patternIndex, boolean priority) {
        return new SpritePieceRenderer.PreparedPiece(
                x, y,
                1, 1,
                patternIndex, patternIndex,
                1,
                false, false,
                priority, false,
                SpriteMaskReplayRole.NORMAL,
                0, 1,
                0, 1,
                null);
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

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int getIntField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        if (value instanceof Boolean b) {
            field.setBoolean(target, b);
        } else {
            field.set(target, value);
        }
    }
}
