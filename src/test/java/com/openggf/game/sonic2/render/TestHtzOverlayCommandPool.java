package com.openggf.game.sonic2.render;

import com.openggf.graphics.TilemapGpuRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestHtzOverlayCommandPool {
    @Test
    void retainedSnapshotsAreIndependentAndDiscardedLeaseIsReused() {
        HtzEarthquakeBgOverlayEffect effect = new HtzEarthquakeBgOverlayEffect();
        RecordingRenderer renderer = new RecordingRenderer(false);
        var first = effect.acquireCaptured(renderer, new int[]{1, 2, 3, 4}, 11);
        var second = effect.acquireCaptured(renderer, new int[]{5, 6, 7, 8}, 22);

        assertNotSame(first, second);
        first.execute(0, 0, 0, 0);
        second.discard();

        assertArrayEquals(new int[]{1, 2, 3, 4}, renderer.lastViewport);
        assertEquals(11f, renderer.lastOffsetX);
        assertEquals(0f, renderer.lastOffsetY);
        assertEquals(1, renderer.lastAtlasWidth);
        assertEquals(1, renderer.lastAtlasHeight);
        assertEquals(2, renderer.lastAtlasId);
        assertEquals(3, renderer.lastPaletteId);
        assertEquals(1, renderer.lastPriorityPass);
        assertSame(second, effect.acquireCaptured(renderer, new int[]{9, 10, 11, 12}, 33));
    }

    @Test
    void renderFailureRestoresWrapAndReleasesLease() {
        HtzEarthquakeBgOverlayEffect effect = new HtzEarthquakeBgOverlayEffect();
        RecordingRenderer renderer = new RecordingRenderer(true);
        renderer.setBgVdpWrapHeight(32f);
        var command = effect.acquireCaptured(renderer, new int[]{0, 0, 320, 224}, 1);

        assertThrows(IllegalStateException.class, () -> command.execute(0, 0, 0, 0));
        assertEquals(32f, renderer.getBgVdpWrapHeight());
        assertSame(command, effect.acquireCaptured(renderer, new int[]{0, 0, 320, 224}, 2));
    }

    private static final class RecordingRenderer extends TilemapGpuRenderer {
        private final boolean fail;
        private int[] lastViewport;
        private float lastOffsetX;
        private float lastOffsetY;
        private int lastAtlasWidth, lastAtlasHeight, lastAtlasId, lastPaletteId, lastPriorityPass;
        private RecordingRenderer(boolean fail) { this.fail = fail; }
        @Override public void render(Layer layer, int ww, int wh, int vx, int vy, int vw, int vh,
                float ox, float oy, int aw, int ah, int at, int pt, int upt, int pp,
                boolean wy, boolean mask, boolean uw, float water) {
            lastViewport = new int[]{vx, vy, vw, vh};
            lastOffsetX = ox;
            lastOffsetY = oy;
            lastAtlasWidth = aw; lastAtlasHeight = ah; lastAtlasId = at;
            lastPaletteId = pt; lastPriorityPass = pp;
            if (fail) throw new IllegalStateException("boom");
        }
    }
}
