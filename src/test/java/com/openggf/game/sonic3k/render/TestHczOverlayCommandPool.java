package com.openggf.game.sonic3k.render;

import com.openggf.graphics.TilemapGpuRenderer;
import com.openggf.level.render.BackgroundRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestHczOverlayCommandPool {
    @Test
    void twoOutstandingCommandsKeepTheirOwnViewportAndPerLineScrollSnapshots() {
        RecordingRenderer renderer = new RecordingRenderer();
        RecordingBackgroundRenderer backgroundRenderer = new RecordingBackgroundRenderer();
        int[] firstScroll = scrollData(101, 102);
        int[] secondScroll = scrollData(201, 202);
        var first = new HczBgHighPriorityTileRenderer.OverlayCommand().configureCaptured(
                renderer, backgroundRenderer, 320, 224, firstScroll, 0, 64, 12, 13, 14, 15, 16, 17, true, 18,
                new int[]{1, 2, 3, 4});
        var second = new HczBgHighPriorityTileRenderer.OverlayCommand().configureCaptured(
                renderer, backgroundRenderer, 420, 244, secondScroll, 0, 40, 23, 24, 25, 26, 27, 28, false, 29,
                new int[]{5, 6, 7, 8});
        firstScroll[0] = -1;
        secondScroll[0] = -2;

        assertNotSame(first, second);
        second.execute(0, 0, 0, 0);
        first.execute(0, 0, 0, 0);

        assertEquals(List.of(
                "420,244:5,6,7,8:0.0,23.0:24,25,26,27,28:false,29.0",
                "320,224:1,2,3,4:0.0,12.0:13,14,15,16,17:true,18.0"), renderer.calls);
        assertEquals(List.of("201,202", "101,102"), backgroundRenderer.uploads,
                "each queued overlay must retain its frame's full per-line HScroll snapshot");
        assertEquals(List.of(77, 77), renderer.hScrollTextureIds);
        assertEquals(List.of(40.0f, 64.0f), renderer.vdpWrapWidths,
                "each overlay must replay its captured plane-period wrap width");
        var reused = HczBgHighPriorityTileRenderer.acquireCaptured(
                renderer, new int[]{9, 10, 11, 12}, 33);
        assertTrue(reused == first || reused == second);
        reused.discard();
    }

    @Test
    void overlayBiasesBgScrollWordsByWindowBaseAndPreservesFgWords() {
        RecordingRenderer renderer = new RecordingRenderer();
        RecordingBackgroundRenderer backgroundRenderer = new RecordingBackgroundRenderer();
        int[] scroll = new int[224];
        // FG word 0x0007, BG word -200 (0xFF38): with a 512px window base the
        // replay must sample window-local X, i.e. BG word -200 + 512 = 312.
        scroll[0] = 0x0007FF38;
        // FG word 0x0001, BG word 100: 100 + 512 = 612.
        scroll[223] = 0x00010064;

        var command = new HczBgHighPriorityTileRenderer.OverlayCommand().configureCaptured(
                renderer, backgroundRenderer, 320, 224, scroll, 512, 64, 0, 1, 1, 2, 3, 0, false, 0,
                new int[]{0, 0, 320, 224});
        command.execute(0, 0, 0, 0);

        int expectedFirst = 0x00070000 | ((-200 + 512) & 0xFFFF);
        int expectedLast = 0x00010000 | ((100 + 512) & 0xFFFF);
        assertEquals(List.of(expectedFirst + "," + expectedLast), backgroundRenderer.uploads,
                "BG scroll words must be biased into window-local coordinates; FG words untouched");
    }

    @Test
    void planePeriodWrapMirrorsTheMainTilePassRenderWidth() {
        // Full-width HCZ2 layout (320 tiles = 2560px) with the default 512px
        // plane period: wrap at 64 tiles like the main tile-pass FBO.
        assertEquals(64, HczBgHighPriorityTileRenderer.computePlanePeriodWrapTiles(320, 320, 512));
        // 512px window tilemap: wrap width equals the window itself.
        assertEquals(64, HczBgHighPriorityTileRenderer.computePlanePeriodWrapTiles(320, 64, 512));
        // Tilemap narrower than the screen: renderWidth grows to the screen.
        assertEquals(40, HczBgHighPriorityTileRenderer.computePlanePeriodWrapTiles(320, 30, 512));
    }

    private static int[] scrollData(int first, int last) {
        int[] data = new int[224];
        data[0] = first;
        data[data.length - 1] = last;
        return data;
    }

    private static final class RecordingBackgroundRenderer extends BackgroundRenderer {
        private final List<String> uploads = new ArrayList<>();

        @Override public void uploadHScroll(int[] hScroll) {
            uploads.add(hScroll[0] + "," + hScroll[hScroll.length - 1]);
        }

        @Override public int getHScrollTextureId() {
            return 77;
        }
    }

    private static final class RecordingRenderer extends TilemapGpuRenderer {
        private final List<String> calls = new ArrayList<>();
        private final List<Integer> hScrollTextureIds = new ArrayList<>();
        private final List<Float> vdpWrapWidths = new ArrayList<>();

        @Override public void enablePerLineScroll(int hScrollTextureId, float screenHeight,
                float vdpWrapWidth, float nametableBase, float sampleYOffsetPx) {
            hScrollTextureIds.add(hScrollTextureId);
            vdpWrapWidths.add(vdpWrapWidth);
        }

        @Override public void render(Layer layer, int ww, int wh, int vx, int vy, int vw, int vh,
                float ox, float oy, int aw, int ah, int at, int pt, int upt, int pp,
                boolean wy, boolean mask, boolean uw, float water) {
            calls.add(ww + "," + wh + ":" + vx + "," + vy + "," + vw + "," + vh
                    + ":" + ox + "," + oy + ":" + aw + "," + ah + "," + at + "," + pt
                    + "," + upt + ":" + uw + "," + water);
        }
    }
}
