package com.openggf;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameMode;
import com.openggf.game.ResultsScreen;
import com.openggf.game.RomDetectionService;
import com.openggf.game.SpecialStageDebugCapabilities;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.SpecialStageViewport;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSpecialStageViewportContract {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        Engine.clearGlobalInstance();
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void geometryUsesExactOuterAndS2InnerOriginsAtCertifiedWidths() {
        assertGeometry(320, 0, 32);
        assertGeometry(400, 40, 72);
        assertGeometry(528, 104, 136);
    }

    @Test
    void geometryClampsInvalidWidthsToNativePresentation() {
        SpecialStageViewport viewport = SpecialStageViewport.fromLogicalWidth(0);

        assertEquals(320, viewport.logicalWidth());
        assertEquals(224, viewport.logicalHeight());
        assertEquals(0, viewport.outer().x());
        assertEquals(320, viewport.outer().width());
        assertEquals(32, viewport.innerH32().x());
        assertEquals(256, viewport.innerH32().width());
        assertEquals(SpecialStageViewport.nativeViewport(), viewport);
    }

    @Test
    void engineSetsFreshViewportBeforeEachActiveProviderDraw() throws Exception {
        EngineHarness harness = newHarness();
        SpecialStageProvider first = mock(SpecialStageProvider.class);
        SpecialStageProvider replacement = mock(SpecialStageProvider.class);
        AtomicReference<SpecialStageProvider> active = new AtomicReference<>(first);
        when(harness.gameLoop.getCurrentGameMode()).thenReturn(GameMode.SPECIAL_STAGE);
        when(harness.gameLoop.getActiveSpecialStageProvider()).thenAnswer(ignored -> active.get());

        drawAt(harness.engine, 320);
        active.set(replacement);
        drawAt(harness.engine, 400);
        drawAt(harness.engine, 352);
        when(harness.gameLoop.getCurrentGameMode()).thenReturn(GameMode.LEVEL_SELECT);
        drawAt(harness.engine, 320);
        when(harness.gameLoop.getCurrentGameMode()).thenReturn(GameMode.SPECIAL_STAGE);
        active.set(first);
        drawAt(harness.engine, 528);

        SpecialStageViewport nativeViewport = SpecialStageViewport.fromLogicalWidth(320);
        SpecialStageViewport wideViewport = SpecialStageViewport.fromLogicalWidth(400);
        SpecialStageViewport equivalentViewport = SpecialStageViewport.fromLogicalWidth(352);
        SpecialStageViewport ultrawideViewport = SpecialStageViewport.fromLogicalWidth(528);
        var firstViewport = forClass(SpecialStageViewport.class);
        var replacementViewport = forClass(SpecialStageViewport.class);
        verify(first, times(2)).setSpecialStageViewport(firstViewport.capture());
        verify(replacement, times(2)).setSpecialStageViewport(replacementViewport.capture());
        assertEquals(List.of(nativeViewport, ultrawideViewport), firstViewport.getAllValues());
        assertEquals(List.of(wideViewport, equivalentViewport), replacementViewport.getAllValues());
    }

    @Test
    void enginePublishesViewportBeforeProviderDraw() throws Exception {
        EngineHarness harness = newHarness();
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        when(harness.gameLoop.getCurrentGameMode()).thenReturn(GameMode.SPECIAL_STAGE);
        when(harness.gameLoop.getActiveSpecialStageProvider()).thenReturn(provider);

        drawAt(harness.engine, 400);

        var order = inOrder(provider);
        order.verify(provider).setSpecialStageViewport(SpecialStageViewport.fromLogicalWidth(400));
        order.verify(provider).draw();
    }

    @Test
    void alignmentDebugUsesLogicalSpecialStageDimensions() throws Exception {
        EngineHarness harness = newHarness();
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        when(harness.gameLoop.getCurrentGameMode()).thenReturn(GameMode.SPECIAL_STAGE);
        when(harness.gameLoop.getActiveSpecialStageProvider()).thenReturn(provider);
        when(provider.debugCapabilities()).thenReturn(new SpecialStageDebugCapabilities(
                false, false, false, false, false, true, false));
        when(provider.isAlignmentTestMode()).thenReturn(true);
        setField(harness.engine, "windowWidth", 1920);
        setField(harness.engine, "windowHeight", 1080);
        setField(harness.engine, "projectionWidth", 400.0);

        invokeDiagnosticOverlays(harness.engine);

        verify(provider).setSpecialStageViewport(SpecialStageViewport.fromLogicalWidth(400));
        verify(provider).renderAlignmentOverlay(400, 224);
    }

    @Test
    void lagCompensationDebugUsesLogicalSpecialStageDimensions() throws Exception {
        EngineHarness harness = newHarness();
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        when(harness.gameLoop.getCurrentGameMode()).thenReturn(GameMode.SPECIAL_STAGE);
        when(harness.gameLoop.getActiveSpecialStageProvider()).thenReturn(provider);
        when(provider.debugCapabilities()).thenReturn(new SpecialStageDebugCapabilities(
                false, false, false, false, false, false, true));
        when(provider.isLagCompensationDisplayEnabled()).thenReturn(true);
        setField(harness.engine, "windowWidth", 1920);
        setField(harness.engine, "windowHeight", 1080);
        setField(harness.engine, "projectionWidth", 528.0);

        invokeDiagnosticOverlays(harness.engine);

        verify(provider).setSpecialStageViewport(SpecialStageViewport.fromLogicalWidth(528));
        verify(provider).renderLagCompensationOverlay(528, 224);
    }

    @Test
    void specialStageResultsRemainOnIndependentResultsWidthPath() throws Exception {
        EngineHarness harness = newHarness();
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        ResultsScreen results = mock(ResultsScreen.class);
        when(harness.gameLoop.getCurrentGameMode()).thenReturn(GameMode.SPECIAL_STAGE_RESULTS);
        when(harness.gameLoop.getActiveSpecialStageProvider()).thenReturn(provider);
        when(harness.gameLoop.getResultsScreen()).thenReturn(results);

        drawAt(harness.engine, 400);

        verify(results).setViewportWidth(400);
        verify(provider, never()).setSpecialStageViewport(any());
    }

    private void assertGeometry(int width, int outerX, int innerX) {
        SpecialStageViewport viewport = SpecialStageViewport.fromLogicalWidth(width);

        assertEquals(width, viewport.logicalWidth());
        assertEquals(224, viewport.logicalHeight());
        assertEquals(outerX, viewport.outer().x());
        assertEquals(320, viewport.outer().width());
        assertEquals(224, viewport.outer().height());
        assertEquals(innerX, viewport.innerH32().x());
        assertEquals(256, viewport.innerH32().width());
        assertEquals(224, viewport.innerH32().height());
        assertEquals(viewport.outer().x() + 32, viewport.innerH32().x());
        assertNotSame(viewport.outer(), viewport.innerH32());
    }

    private EngineHarness newHarness() {
        GraphicsManager graphics = mock(GraphicsManager.class);
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        Engine engine = new Engine(new EngineContext(
                config,
                graphics,
                mock(AudioManager.class),
                mock(RomManager.class),
                mock(PerformanceProfiler.class),
                mock(DebugOverlayManager.class),
                mock(PlaybackDebugManager.class),
                mock(RomDetectionService.class),
                mock(CrossGameFeatureProvider.class)));
        GameLoop gameLoop = mock(GameLoop.class);
        setField(engine, "gameLoop", gameLoop);
        setField(engine, "camera", mock(Camera.class));
        return new EngineHarness(engine, gameLoop);
    }

    private static void drawAt(Engine engine, int width) throws Exception {
        setField(engine, "projectionWidth", (double) width);
        engine.draw();
    }

    private static void invokeDiagnosticOverlays(Engine engine) throws Exception {
        Method method = Engine.class.getDeclaredMethod(
                "renderDiagnosticOverlays", boolean.class, boolean.class,
                boolean.class, Class.forName("com.openggf.graphics.pipeline.RenderOrderRecorder"));
        method.setAccessible(true);
        method.invoke(engine, false, false, false, null);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private record EngineHarness(Engine engine, GameLoop gameLoop) {
    }
}
