package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelState;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.Palette;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestHudRenderManager {

    @Test
    public void bonusStageLayoutShowsOnlyRingsOnTopRow() {
        GraphicsManager graphicsManager = mock(GraphicsManager.class);
        Camera camera = mock(Camera.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        when(camera.getXWithShake()).thenReturn((short) 0);
        when(camera.getYWithShake()).thenReturn((short) 0);
        when(levelState.getRings()).thenReturn(23);
        when(levelState.getFlashCycle()).thenReturn(false);

        HudStaticArt staticArt = new HudStaticArt(
                new Pattern[] {
                        new Pattern(), new Pattern(), new Pattern(),
                        new Pattern(), new Pattern(), new Pattern(), new Pattern()
                },
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 2, false, false, 0))),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 6, false, false, 0))),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 1, false, false, 0))));

        HudRenderManager hud = new HudRenderManager(graphicsManager, camera, gameState);
        hud.setDigitPatternIndex(200);
        hud.setLivesNumbersPatternIndex(220);
        hud.setStaticHudArt(0x28020, staticArt);
        hud.setBonusStageHudLayout(true);

        hud.draw(levelState, null);

        verify(graphicsManager).renderPatternWithId(eq(0x28026), any(), eq(16), eq(8));
        verify(graphicsManager, never()).renderPatternWithId(eq(0x28022), any(), eq(16), eq(24));
        verify(graphicsManager, never()).renderPatternWithId(eq(0x28026), any(), eq(16), eq(40));
        verify(graphicsManager, never()).renderPatternWithId(intThat(id -> id >= 0x28020 && id < 0x28022),
                any(), anyInt(), anyInt());
    }

    @Test
    void mappingDrivenScoreTimeAndRingsLabelsRenderFromStaticFrames() {
        GraphicsManager graphicsManager = mock(GraphicsManager.class);
        Camera camera = mock(Camera.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        when(camera.getXWithShake()).thenReturn((short) 0);
        when(camera.getYWithShake()).thenReturn((short) 0);
        when(levelState.getRings()).thenReturn(7);
        when(levelState.getFlashCycle()).thenReturn(false);
        when(levelState.shouldFlashTimer()).thenReturn(false);
        when(levelState.getDisplayTime()).thenReturn("0:10");
        when(gameState.getScore()).thenReturn(0);
        when(gameState.getLives()).thenReturn(3);

        HudStaticArt staticArt = new HudStaticArt(
                new Pattern[] { new Pattern(), new Pattern(), new Pattern() },
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0))),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 1, false, false, 0))),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 2, false, false, 0))),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()));

        HudRenderManager hud = new HudRenderManager(graphicsManager, camera, gameState);
        hud.setDigitPatternIndex(200);
        hud.setLivesNumbersPatternIndex(220);
        hud.setStaticHudArt(0x28020, staticArt);

        hud.draw(levelState, null);

        verify(graphicsManager).renderPatternWithId(eq(0x28020), any(), eq(16), eq(8));
        verify(graphicsManager).renderPatternWithId(eq(0x28021), any(), eq(16), eq(24));
        verify(graphicsManager).renderPatternWithId(eq(0x28022), any(), eq(16), eq(40));
        verify(graphicsManager, never()).renderPatternWithId(eq(100), any(), eq(16), eq(8));
        verify(graphicsManager, never()).renderPatternWithId(eq(116), any(), eq(16), eq(24));
        verify(graphicsManager, never()).renderPatternWithId(eq(106), any(), eq(16), eq(40));
    }

    @Test
    void normalHudDrawUsesSinglePatternBatchAroundHudTiles() {
        GraphicsManager graphicsManager = mock(GraphicsManager.class);
        Camera camera = mock(Camera.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        when(camera.getXWithShake()).thenReturn((short) 0);
        when(camera.getYWithShake()).thenReturn((short) 0);
        when(levelState.getRings()).thenReturn(7);
        when(levelState.getFlashCycle()).thenReturn(false);
        when(levelState.shouldFlashTimer()).thenReturn(false);
        when(levelState.getDisplayTime()).thenReturn("0:10");
        when(gameState.getScore()).thenReturn(123);
        when(gameState.getLives()).thenReturn(3);

        HudStaticArt staticArt = new HudStaticArt(
                new Pattern[] { new Pattern(), new Pattern(), new Pattern() },
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0))),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 1, false, false, 0))),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 2, false, false, 0))),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()));

        HudRenderManager hud = new HudRenderManager(graphicsManager, camera, gameState);
        hud.setDigitPatternIndex(200);
        hud.setLivesNumbersPatternIndex(220);
        hud.setStaticHudArt(0x28020, staticArt);

        hud.draw(levelState, null);

        var order = inOrder(graphicsManager);
        order.verify(graphicsManager).beginPatternBatch();
        order.verify(graphicsManager, atLeastOnce()).renderPatternWithId(anyInt(), any(), anyInt(), anyInt());
        order.verify(graphicsManager).flushPatternBatch();
    }

    @Test
    void mappingDrivenLivesFramePreservesPiecePalettesWithoutPaletteUpload() {
        GraphicsManager graphicsManager = mock(GraphicsManager.class);
        Camera camera = mock(Camera.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        when(camera.getXWithShake()).thenReturn((short) 0);
        when(camera.getYWithShake()).thenReturn((short) 0);
        when(levelState.getRings()).thenReturn(10);
        when(levelState.getFlashCycle()).thenReturn(false);
        when(levelState.shouldFlashTimer()).thenReturn(false);
        when(levelState.getDisplayTime()).thenReturn("0:10");
        when(gameState.getScore()).thenReturn(0);
        when(gameState.getLives()).thenReturn(3);

        HudStaticArt staticArt = new HudStaticArt(
                new Pattern[] { new Pattern(), new Pattern(), new Pattern(), new Pattern() },
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0),
                        new SpriteMappingPiece(8, 0, 1, 1, 1, false, false, 1))));

        HudRenderManager hud = new HudRenderManager(graphicsManager, camera, gameState);
        hud.setDigitPatternIndex(200);
        hud.setLivesNumbersPatternIndex(220);
        hud.setStaticHudArt(0x28020, staticArt);

        // HudRenderManager reuses one mutable PatternDesc across pieces (the
        // renderer consumes it synchronously), so record the palette index at
        // invocation time instead of verifying the captured reference later.
        List<int[]> staticPieceCalls = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            int patternId = invocation.getArgument(0);
            com.openggf.level.PatternDesc desc = invocation.getArgument(1);
            staticPieceCalls.add(new int[] {
                    patternId, desc.getPaletteIndex(),
                    invocation.getArgument(2), invocation.getArgument(3) });
            return null;
        }).when(graphicsManager).renderPatternWithId(anyInt(), any(), anyInt(), anyInt());

        hud.draw(levelState, null);

        org.junit.jupiter.api.Assertions.assertTrue(staticPieceCalls.stream().anyMatch(
                call -> call[0] == 0x28020 && call[1] == 0 && call[2] == 16 && call[3] == 200),
                "expected lives piece 0x28020 rendered with palette 0 at (16,200)");
        org.junit.jupiter.api.Assertions.assertTrue(staticPieceCalls.stream().anyMatch(
                call -> call[0] == 0x28021 && call[1] == 1 && call[2] == 24 && call[3] == 200),
                "expected lives piece 0x28021 rendered with palette 1 at (24,200)");
        verify(graphicsManager, never()).cachePaletteTexture(any(), anyInt());
    }

    @Test
    void mappingDrivenLivesDigitsKeepPreRefactorHudAnchor() {
        GraphicsManager graphicsManager = mock(GraphicsManager.class);
        Camera camera = mock(Camera.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        when(camera.getXWithShake()).thenReturn((short) 0);
        when(camera.getYWithShake()).thenReturn((short) 0);
        when(levelState.getRings()).thenReturn(10);
        when(levelState.getFlashCycle()).thenReturn(false);
        when(levelState.shouldFlashTimer()).thenReturn(false);
        when(levelState.getDisplayTime()).thenReturn("0:10");
        when(gameState.getScore()).thenReturn(0);
        when(gameState.getLives()).thenReturn(3);

        HudStaticArt staticArt = new HudStaticArt(
                new Pattern[] { new Pattern(), new Pattern(), new Pattern(), new Pattern(), new Pattern(), new Pattern() },
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(0, 0, 2, 2, 0, false, false, 0),
                        new SpriteMappingPiece(16, 0, 4, 2, 2, false, false, 1))));

        HudRenderManager hud = new HudRenderManager(graphicsManager, camera, gameState);
        hud.setDigitPatternIndex(200);
        hud.setLivesNumbersPatternIndex(220);
        hud.setStaticHudArt(0x28020, staticArt);

        hud.draw(levelState, null);

        verify(graphicsManager).renderPatternWithId(eq(223), any(), eq(56), eq(208));
    }

    @Test
    void livesPaletteOverrideSupplierRefreshesBetweenDraws() throws Exception {
        GraphicsManager graphicsManager = mock(GraphicsManager.class);
        Camera camera = mock(Camera.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        when(camera.getXWithShake()).thenReturn((short) 0);
        when(camera.getYWithShake()).thenReturn((short) 0);
        when(levelState.getRings()).thenReturn(10);
        when(levelState.getFlashCycle()).thenReturn(false);
        when(levelState.shouldFlashTimer()).thenReturn(false);
        when(levelState.getDisplayTime()).thenReturn("0:10");
        when(gameState.getScore()).thenReturn(0);
        when(gameState.getLives()).thenReturn(3);

        HudStaticArt staticArt = new HudStaticArt(
                new Pattern[] { new Pattern(), new Pattern(), new Pattern(), new Pattern(), new Pattern(), new Pattern() },
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(0, 0, 2, 2, 0, false, false, 0),
                        new SpriteMappingPiece(16, 0, 4, 2, 2, false, false, 0))));

        HudRenderManager hud = new HudRenderManager(graphicsManager, camera, gameState);
        hud.setDigitPatternIndex(200);
        hud.setLivesNumbersPatternIndex(220);
        hud.setStaticHudArt(0x28020, staticArt);

        Method setSupplier = HudRenderManager.class.getDeclaredMethod(
                "setLivesPaletteOverrideSupplier", java.util.function.Supplier.class);
        Palette first = new Palette();
        setColor(first, 12, 0, 146, 0);
        Palette second = new Palette();
        setColor(second, 12, 255, 73, 109);
        AtomicReference<Palette> current = new AtomicReference<>(first);
        java.util.function.Supplier<Palette> supplier = current::get;
        setSupplier.invoke(hud, supplier);

        hud.draw(levelState, null);
        current.set(second);
        hud.draw(levelState, null);

        verify(graphicsManager, times(1)).cachePaletteTexture(argThat(paletteMatches(0, 146, 0)), eq(0));
        verify(graphicsManager, times(1)).cachePaletteTexture(argThat(paletteMatches(255, 73, 109)), eq(0));
        verify(graphicsManager, times(4)).flushPatternBatch();
        verify(graphicsManager, times(4)).flush();
    }

    @Test
    void unchangedLivesPaletteOverrideDoesNotReuploadOrImmediateFlush() throws Exception {
        GraphicsManager graphicsManager = mock(GraphicsManager.class);
        Camera camera = mock(Camera.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        when(camera.getXWithShake()).thenReturn((short) 0);
        when(camera.getYWithShake()).thenReturn((short) 0);
        when(levelState.getRings()).thenReturn(10);
        when(levelState.getFlashCycle()).thenReturn(false);
        when(levelState.shouldFlashTimer()).thenReturn(false);
        when(levelState.getDisplayTime()).thenReturn("0:10");
        when(gameState.getScore()).thenReturn(0);
        when(gameState.getLives()).thenReturn(3);

        HudStaticArt staticArt = new HudStaticArt(
                new Pattern[] { new Pattern(), new Pattern(), new Pattern(), new Pattern(), new Pattern(), new Pattern() },
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(0, 0, 2, 2, 0, false, false, 0),
                        new SpriteMappingPiece(16, 0, 4, 2, 2, false, false, 0))));

        HudRenderManager hud = new HudRenderManager(graphicsManager, camera, gameState);
        hud.setDigitPatternIndex(200);
        hud.setLivesNumbersPatternIndex(220);
        hud.setStaticHudArt(0x28020, staticArt);

        Method setSupplier = HudRenderManager.class.getDeclaredMethod(
                "setLivesPaletteOverrideSupplier", java.util.function.Supplier.class);
        Palette override = new Palette();
        setColor(override, 12, 255, 73, 109);
        java.util.function.Supplier<Palette> supplier = () -> override;
        setSupplier.invoke(hud, supplier);

        hud.draw(levelState, null);
        hud.draw(levelState, null);

        verify(graphicsManager, times(1)).cachePaletteTexture(argThat(paletteMatches(255, 73, 109)), eq(0));
        verify(graphicsManager, times(3)).flushPatternBatch();
        verify(graphicsManager, times(2)).flush();
    }

    @Test
    void nullLivesPaletteOverrideSupplierDoesNotUploadPaletteOrImmediateFlush() throws Exception {
        GraphicsManager graphicsManager = mock(GraphicsManager.class);
        Camera camera = mock(Camera.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        when(camera.getXWithShake()).thenReturn((short) 0);
        when(camera.getYWithShake()).thenReturn((short) 0);
        when(levelState.getRings()).thenReturn(10);
        when(levelState.getFlashCycle()).thenReturn(false);
        when(levelState.shouldFlashTimer()).thenReturn(false);
        when(levelState.getDisplayTime()).thenReturn("0:10");
        when(gameState.getScore()).thenReturn(0);
        when(gameState.getLives()).thenReturn(3);

        HudStaticArt staticArt = new HudStaticArt(
                new Pattern[] { new Pattern(), new Pattern(), new Pattern(), new Pattern(), new Pattern(), new Pattern() },
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(
                        new SpriteMappingPiece(0, 0, 2, 2, 0, false, false, 0),
                        new SpriteMappingPiece(16, 0, 4, 2, 2, false, false, 0))));

        HudRenderManager hud = new HudRenderManager(graphicsManager, camera, gameState);
        hud.setDigitPatternIndex(200);
        hud.setLivesNumbersPatternIndex(220);
        hud.setStaticHudArt(0x28020, staticArt);

        Method setSupplier = HudRenderManager.class.getDeclaredMethod(
                "setLivesPaletteOverrideSupplier", java.util.function.Supplier.class);
        java.util.function.Supplier<Palette> supplier = () -> null;
        setSupplier.invoke(hud, supplier);

        hud.draw(levelState, null);

        verify(graphicsManager, never()).cachePaletteTexture(any(), anyInt());
        verify(graphicsManager, never()).flush();
    }

    @Test
    void mappingDrivenRingsFrameUsesFlashVariantInsteadOfHudString() {
        GraphicsManager graphicsManager = mock(GraphicsManager.class);
        Camera camera = mock(Camera.class);
        GameStateManager gameState = mock(GameStateManager.class);
        LevelState levelState = mock(LevelState.class);
        when(camera.getXWithShake()).thenReturn((short) 0);
        when(camera.getYWithShake()).thenReturn((short) 0);
        when(levelState.getRings()).thenReturn(0);
        when(levelState.getFlashCycle()).thenReturn(true);
        when(levelState.shouldFlashTimer()).thenReturn(false);
        when(levelState.getDisplayTime()).thenReturn("0:10");
        when(gameState.getScore()).thenReturn(0);
        when(gameState.getLives()).thenReturn(3);

        HudStaticArt staticArt = new HudStaticArt(
                new Pattern[] { new Pattern(), new Pattern() },
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of()),
                new SpriteMappingFrame(List.of(new SpriteMappingPiece(0, 0, 1, 1, 1, false, false, 0))),
                new SpriteMappingFrame(List.of()));

        HudRenderManager hud = new HudRenderManager(graphicsManager, camera, gameState);
        hud.setDigitPatternIndex(200);
        hud.setLivesNumbersPatternIndex(220);
        hud.setStaticHudArt(0x28020, staticArt);

        hud.draw(levelState, null);

        verify(graphicsManager).renderPatternWithId(eq(0x28021), any(), eq(16), eq(40));
        verify(graphicsManager, never()).renderPatternWithId(eq(100), any(), eq(16), eq(40));
    }

    private static org.mockito.ArgumentMatcher<Palette> paletteMatches(int r, int g, int b) {
        return palette -> {
            if (palette == null) {
                return false;
            }
            Palette.Color color = palette.getColor(12);
            return (color.r & 0xFF) == r
                    && (color.g & 0xFF) == g
                    && (color.b & 0xFF) == b;
        };
    }

    private static void setColor(Palette palette, int index, int r, int g, int b) {
        Palette.Color color = palette.getColor(index);
        color.r = (byte) r;
        color.g = (byte) g;
        color.b = (byte) b;
    }
}
