package com.openggf.game.sonic1.specialstage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static com.openggf.game.sonic1.constants.Sonic1Constants.ARTTILE_RING;
import static com.openggf.game.sonic1.constants.Sonic1Constants.SS_LAYOUT_STRIDE;

public class Sonic1SpecialStageRendererTest {
    private static final int TEST_PATTERN_BASE = 0x10000;

    private GraphicsManager graphicsManager;
    private Sonic1SpecialStageRenderer renderer;

    @BeforeEach
    public void setUp() {
        GraphicsManager.getInstance().resetState();
        graphicsManager = GraphicsManager.getInstance();
        graphicsManager.initHeadless();
        renderer = new Sonic1SpecialStageRenderer(graphicsManager);

        renderer.setPatternBases(
                TEST_PATTERN_BASE,
                TEST_PATTERN_BASE + 0x100,
                TEST_PATTERN_BASE + 0x200,
                TEST_PATTERN_BASE + 0x300,
                TEST_PATTERN_BASE + 0x400,
                TEST_PATTERN_BASE + 0x500,
                TEST_PATTERN_BASE + 0x600,
                TEST_PATTERN_BASE + 0x700,
                TEST_PATTERN_BASE + 0x800,
                TEST_PATTERN_BASE + 0x900,
                TEST_PATTERN_BASE + 0xA00,
                TEST_PATTERN_BASE + 0xB00,
                TEST_PATTERN_BASE + 0xC00,
                TEST_PATTERN_BASE + 0xD00,
                TEST_PATTERN_BASE + 0xD20,
                TEST_PATTERN_BASE + 0xD40,
                TEST_PATTERN_BASE + 0xD60,
                TEST_PATTERN_BASE + 0xD80,
                TEST_PATTERN_BASE + 0xDA0,
                TEST_PATTERN_BASE + 0xE00,
                TEST_PATTERN_BASE + 0xE80
        );
    }

    @AfterEach
    public void tearDown() {
        if (graphicsManager != null) {
            graphicsManager.cleanup();
        }
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void flushFailureExecutesArmedFboEndOnceAndDiscardsOrdinaryTail() {
        GraphicsManager graphics = new GraphicsManager();
        graphics.setGlInitialized(true);
        Sonic1SpecialStageManager.BackgroundCommandPool pool =
                new Sonic1SpecialStageManager.BackgroundCommandPool();
        Sonic1SpecialStageBackgroundRenderer recordingRenderer =
                mock(Sonic1SpecialStageBackgroundRenderer.class);
        TrackingDiscardCommand ordinaryTail = new TrackingDiscardCommand();

        graphics.registerCommand(pool.obtainBegin(recordingRenderer));
        graphics.registerCommand((cameraX, cameraY, cameraWidth, cameraHeight) -> {
            throw new IllegalStateException("middle");
        });
        graphics.registerCommand(pool.obtainEnd(recordingRenderer));
        graphics.registerCommand(ordinaryTail);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> graphics.flushWithCamera((short) 0, (short) 0, (short) 320, (short) 224));

        assertEquals("middle", failure.getMessage());
        verify(recordingRenderer, times(1)).beginTilePass(Sonic1SpecialStageRenderer.H32_HEIGHT);
        verify(recordingRenderer, times(1)).endTilePass();
        assertEquals(0, ordinaryTail.executions);
        assertEquals(1, ordinaryTail.discards);
    }

    @Test
    public void testRenderHandlesOutOfBoundsCameraWithoutException() {
        byte[] layout = new byte[SS_LAYOUT_STRIDE];
        layout[0] = 0x01;
        layout[1] = 0x34;
        layout[2] = 0x3A;

        try {
            renderer.render(layout, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
            renderer.render(layout, 0x4000, 0x7FFF, 0x7FFF, 0, 0, 0, 0, 0, 0, 0, false);
            renderer.render(layout, 0x8000, 0x7FFF_FFFF, 0x7FFF_FFFF, 0, 0, 0, 0, 0, 0, 0, true);
        } catch (Exception ex) {
            fail("Renderer should handle extreme camera values without exceptions: " + ex.getMessage());
        }
    }

    private static final class TrackingDiscardCommand implements GLCommandable {
        private int executions;
        private int discards;

        @Override
        public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            executions++;
        }

        @Override
        public void discard() {
            discards++;
        }
    }

    @Test
    public void testRenderHandlesEmptyLayoutWithoutException() {
        byte[] emptyLayout = new byte[0];
        try {
            renderer.render(emptyLayout, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        } catch (Exception ex) {
            fail("Renderer should handle empty layout without exceptions: " + ex.getMessage());
        }
    }

    @Test
    public void testRingArtTileMapsToRingPatternBase() throws Exception {
        Method mappingMethod = Sonic1SpecialStageRenderer.class
                .getDeclaredMethod("getPatternBaseForArtTile", int.class);
        mappingMethod.setAccessible(true);

        int ringBase = (int) mappingMethod.invoke(renderer, ARTTILE_RING);
        assertEquals(TEST_PATTERN_BASE + 0xC00, ringBase, "Ring art tile should resolve to dedicated ring pattern base");
    }

    @Test
    public void testSpecialStageUsesFullScreenViewport() {
        assertEquals(320, Sonic1SpecialStageRenderer.H32_WIDTH, "S1 special stage should use full-width viewport");
        assertEquals(224, Sonic1SpecialStageRenderer.H32_HEIGHT, "S1 special stage should use 224-line visible height");
        assertEquals(0, Sonic1SpecialStageRenderer.SCREEN_CENTER_OFFSET, "S1 special stage should not apply horizontal centering offset");
    }

    @Test
    void deferredBackgroundCommandsRetainFrameDataAndReuseBoundedStorageAcross600Frames() {
        Sonic1SpecialStageManager.BackgroundCommandPool pool =
                new Sonic1SpecialStageManager.BackgroundCommandPool();
        int[] frameNSource = new int[224];
        frameNSource[0] = 3;
        frameNSource[223] = 7;

        Sonic1SpecialStageManager.BackgroundCommand frameN =
                pool.obtainScroll(null, frameNSource, 11.0f);
        frameNSource[0] = 13;
        frameNSource[223] = 17;
        Sonic1SpecialStageManager.BackgroundCommand frameNPlusOne =
                pool.obtainScroll(null, frameNSource, 19.0f);

        assertEquals(3, frameN.hScrollAt(0));
        assertEquals(7, frameN.hScrollAt(223));
        assertEquals(11.0f, frameN.vScroll());
        assertEquals(13, frameNPlusOne.hScrollAt(0));
        assertEquals(17, frameNPlusOne.hScrollAt(223));
        assertEquals(19.0f, frameNPlusOne.vScroll());
        frameN.discard();
        frameNPlusOne.discard();

        Set<Object> commands = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> backings = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int frame = 0; frame < 600; frame++) {
            Sonic1SpecialStageManager.BackgroundCommand bg =
                    pool.obtainScroll(null, frameNSource, frame);
            Sonic1SpecialStageManager.BackgroundCommand fg =
                    pool.obtainUniform(null, -frame, frame + 0.5f);
            commands.add(bg);
            commands.add(fg);
            backings.add(bg.hScrollBackingIdentity());
            bg.discard();
            fg.discard();
        }

        assertEquals(2, commands.size(), "two same-frame commands must bound the steady command pool");
        assertEquals(2, backings.size(),
                "retained N/N+1 scroll snapshots require exactly two reusable scratch backings");
    }
}
