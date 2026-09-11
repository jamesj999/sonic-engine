package com.openggf.game.palette;

import com.openggf.game.rewind.snapshot.PaletteColorSnapshot;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestPaletteColorStateAdapter {

    private static Palette[] makePalettes(int lines, byte seed) {
        Palette[] palettes = new Palette[lines];
        for (int l = 0; l < lines; l++) {
            palettes[l] = new Palette();
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                palettes[l].colors[c].r = (byte) (seed + l * 16 + c);
                palettes[l].colors[c].g = (byte) (seed + l * 16 + c + 1);
                palettes[l].colors[c].b = (byte) (seed + l * 16 + c + 2);
            }
        }
        return palettes;
    }

    private static byte[] rgbOf(Palette[] palettes) {
        byte[] out = new byte[palettes.length * Palette.PALETTE_SIZE * 3];
        int i = 0;
        for (Palette p : palettes) {
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                out[i++] = p.colors[c].r;
                out[i++] = p.colors[c].g;
                out[i++] = p.colors[c].b;
            }
        }
        return out;
    }

    @Test
    public void captureRestoreRoundTripsBothSurfaces() {
        Palette[] normal = makePalettes(4, (byte) 0x10);
        Palette[] underwater = makePalettes(4, (byte) 0x60);
        byte[] normalBefore = rgbOf(normal);
        byte[] underwaterBefore = rgbOf(underwater);

        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> normal, () -> underwater, () -> null);
        PaletteColorSnapshot snap = adapter.capture();

        // Mutate every color after capture (simulates AIZ intro palette writes).
        for (Palette p : normal) {
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                p.colors[c].r = (byte) 0x7F;
            }
        }
        underwater[2].colors[15].g = (byte) 0x01;

        adapter.restore(snap);

        assertArrayEquals(normalBefore, rgbOf(normal), "normal colors must revert");
        assertArrayEquals(underwaterBefore, rgbOf(underwater), "underwater colors must revert");
    }

    @Test
    public void restoreWritesInPlaceWithoutReplacingColorObjects() {
        Palette[] normal = makePalettes(1, (byte) 0x20);
        Palette.Color aliased = normal[0].colors[5];

        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> normal, () -> null, () -> null);
        PaletteColorSnapshot snap = adapter.capture();
        normal[0].colors[5].r = (byte) 0x44;
        adapter.restore(snap);

        assertSame(aliased, normal[0].colors[5],
                "restore must write color fields in place - other code aliases Color refs");
        assertEquals((byte) 0x25, aliased.r);
    }

    @Test
    public void nullSurfacesCaptureEmptyAndRestoreNoops() {
        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> null, () -> null, () -> null);
        PaletteColorSnapshot snap = adapter.capture();
        assertEquals(0, snap.normalRgb().length);
        assertEquals(0, snap.underwaterRgb().length);
        adapter.restore(snap); // must not throw
    }

    @Test
    public void keyIsStable() {
        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> null, () -> null, () -> null);
        assertEquals("palette-colors", adapter.key());
    }

    @Test
    public void restoreRecachesNormalLinesAndUnderwaterWhenGlInitialized() {
        Palette[] normal = makePalettes(2, (byte) 0x10);
        Palette[] underwater = makePalettes(2, (byte) 0x60);
        GraphicsManager graphics = mock(GraphicsManager.class);
        when(graphics.isGlInitialized()).thenReturn(true);

        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> normal, () -> underwater, () -> graphics);
        PaletteColorSnapshot snap = adapter.capture();
        adapter.restore(snap);

        verify(graphics).cachePaletteTexture(normal[0], 0);
        verify(graphics).cachePaletteTexture(normal[1], 1);
        verify(graphics).cacheUnderwaterPaletteTexture(underwater, normal[0]);
    }

    @Test
    public void restoreRecachesUnderwaterEvenWhenNormalSurfaceMissing() {
        Palette[] underwater = makePalettes(2, (byte) 0x60);
        GraphicsManager graphics = mock(GraphicsManager.class);
        when(graphics.isGlInitialized()).thenReturn(true);

        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> null, () -> underwater, () -> graphics);
        PaletteColorSnapshot snap = adapter.capture();
        adapter.restore(snap);

        verify(graphics, never()).cachePaletteTexture(any(), anyInt());
        verify(graphics).cacheUnderwaterPaletteTexture(underwater, null);
    }

    @Test
    public void restoreDoesNotRecacheWhenGlIsNotInitialized() {
        Palette[] normal = makePalettes(2, (byte) 0x10);
        Palette[] underwater = makePalettes(2, (byte) 0x60);
        GraphicsManager graphics = mock(GraphicsManager.class);
        when(graphics.isGlInitialized()).thenReturn(false);

        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> normal, () -> underwater, () -> graphics);
        PaletteColorSnapshot snap = adapter.capture();
        adapter.restore(snap);

        verify(graphics, never()).cachePaletteTexture(any(), anyInt());
        verify(graphics, never()).cacheUnderwaterPaletteTexture(any(), any());
    }

    @Test
    public void restoreToleratesNullGraphicsSupplierResult() {
        Palette[] normal = makePalettes(1, (byte) 0x10);
        Palette[] underwater = makePalettes(1, (byte) 0x60);
        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> normal, () -> underwater, () -> null);
        PaletteColorSnapshot snap = adapter.capture();

        adapter.restore(snap); // must not throw
    }

    @Test
    public void resetForMissingSnapshotLeavesLiveColorsUnchanged() {
        Palette[] normal = makePalettes(1, (byte) 0x20);
        byte[] normalBefore = rgbOf(normal);
        PaletteColorStateAdapter adapter =
                new PaletteColorStateAdapter(() -> normal, () -> null, () -> null);

        adapter.resetForMissingSnapshot();

        assertArrayEquals(normalBefore, rgbOf(normal));
    }

    @Test
    public void snapshotDefensivelyCopiesConstructorInputs() {
        byte[] normal = new byte[] {1, 2, 3};
        byte[] underwater = new byte[] {4, 5, 6};

        PaletteColorSnapshot snap = new PaletteColorSnapshot(normal, underwater);
        normal[0] = 9;
        underwater[0] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, snap.normalRgb());
        assertArrayEquals(new byte[] {4, 5, 6}, snap.underwaterRgb());
    }
}
