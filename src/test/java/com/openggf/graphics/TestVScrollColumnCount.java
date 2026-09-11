package com.openggf.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class TestVScrollColumnCount {
    private static int columns(int width) { return (width + 15) / 16; }

    @Test void nativeIs20() { assertEquals(20, columns(320)); }
    @Test void widescreen() {
        assertEquals(25, columns(400));
        assertEquals(33, columns(528));
        assertEquals(50, columns(800));
    }

    @Test
    void tilemapRendererReconfiguresPerColumnVScrollCapacityForResolvedWidth() {
        TilemapGpuRenderer renderer = new TilemapGpuRenderer(320);

        assertEquals(20, renderer.getVScrollColumnCapacity());

        renderer.applyResolvedDisplayWidth(400);

        assertEquals(25, renderer.getVScrollColumnCapacity());
    }

    @Test
    void graphicsManagerReconfiguresExistingTilemapRendererForResolvedDisplayWidthWithoutGl() throws Exception {
        GraphicsManager graphics = new GraphicsManager();
        TilemapGpuRenderer renderer = new TilemapGpuRenderer(320);
        setPrivateField(graphics, "tilemapGpuRenderer", renderer);

        graphics.applyResolvedDisplayWidth(400);

        assertEquals(25, renderer.getVScrollColumnCapacity());
    }

    @Test
    void graphicsManagerResolvedDisplayWidthHookIsSafeBeforeTilemapRendererExists() {
        GraphicsManager graphics = new GraphicsManager();

        assertDoesNotThrow(() -> graphics.applyResolvedDisplayWidth(400));
    }

    @Test
    void perColumnVScrollCountDerivesFromVisibleWidthAsCeilWidthOver16() {
        // The per-column VScroll texture must hold ceil(width / 16) entries — one per
        // 16px screen column — for every supported display width, not a hardcoded 20.
        // getVScrollColumnCapacity() is the runtime value the shader's column count is
        // sourced from (TilemapGpuRenderer passes columnVScrollBuffer.getEntryCount()).
        int[] widths = {320, 400, 416, 528, 640, 800};
        for (int width : widths) {
            TilemapGpuRenderer renderer = new TilemapGpuRenderer(width);
            int expected = (width + 15) / 16; // ceil(width / 16)
            assertEquals(expected, renderer.getVScrollColumnCapacity(),
                    "VScroll column capacity must equal ceil(" + width + "/16) = " + expected);
        }

        // Reconfiguration after construction must also track ceil(width/16) and must
        // NOT stay pinned to the original native column count.
        TilemapGpuRenderer renderer = new TilemapGpuRenderer(320);
        assertEquals(20, renderer.getVScrollColumnCapacity(), "native 320 -> 20 columns");
        renderer.applyResolvedDisplayWidth(528);
        assertEquals(33, renderer.getVScrollColumnCapacity(),
                "resolving to 528px must yield ceil(528/16) = 33 columns, not the native 20");
    }

    @Test
    void tilemapRendererSuppliesColumnTextureEntryCountToShader() throws IOException {
        String renderer = Files.readString(Path.of("src/main/java/com/openggf/graphics/TilemapGpuRenderer.java"));

        assertTrue(renderer.contains("setVScrollColumnCount(columnVScrollBuffer.getEntryCount())"),
                "TilemapGpuRenderer must pass the VScroll column texture's actual entry count to the shader");
    }

    @Test
    void backgroundRendererSuppliesLogicalDisplayWidthToParallaxShader() throws IOException {
        String renderer = Files.readString(Path.of("src/main/java/com/openggf/level/render/BackgroundRenderer.java"));

        assertTrue(renderer.contains("setActiveDisplayWidth((float) GameServices.configuration()"),
                "BackgroundRenderer must pass the logical configured screen width to the parallax shader");
        assertTrue(renderer.contains("SonicConfiguration.SCREEN_WIDTH_PIXELS"),
                "The active display width should come from the logical screen-width configuration");
    }

    @Test
    void backgroundPerColumnVScrollIsOwnedByParallaxCompositingPassOnly() throws Exception {
        // FLAGGED: partial source guard. The decision point lives inside LevelRenderer's
        // private renderBackground() path, which registers GL commands and reads live
        // ParallaxManager/Camera/water state -- there is no exposed getter for the
        // pending-BG-pass fields and no headless harness drives the pass, so a purely
        // behavioral oracle is infeasible here. Behavioral anchor: assert via reflection
        // (compiles against the real class, so a rename of the owning field is caught)
        // that the parallax-pass field exists and no tile-pass per-column field exists,
        // backed by the source guard below.
        Class<?> lr = Class.forName("com.openggf.level.LevelRenderer");
        boolean hasColumnDataField = false;
        for (Field f : lr.getDeclaredFields()) {
            if (f.getName().equals("pendingBgVScrollColumnData")) {
                hasColumnDataField = true;
            }
            assertFalse(f.getName().equals("pendingBgTilePassPerColumnVScroll"),
                    "BG tile FBO pass must not own a per-column VScroll field; doing both doubles AIZ fire-wave offsets");
        }
        assertTrue(hasColumnDataField,
                "parallax compositing pass must own the per-column VScroll data field");

        String renderer = Files.readString(Path.of("src/main/java/com/openggf/level/LevelRenderer.java"));
        assertTrue(renderer.contains("pendingBgVScrollColumnData = vScrollColumnData;"),
                "BG per-column VScroll must still feed the parallax compositing pass");
        assertFalse(renderer.contains("pendingBgTilePassPerColumnVScroll"),
                "BG tile FBO pass must not also consume per-column VScroll; doing both doubles AIZ fire-wave offsets");
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
