package com.openggf.debug;

import com.openggf.graphics.PixelFont;
import com.openggf.graphics.PixelFontTextRenderer;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestPerformancePanelRenderer {

    @Test
    void legendIncludesAudioWhenItRanksBelowSixth() {
        ProfileSnapshot snapshot = snapshot(Map.of(
                "render", 9_000_000L, "update", 8_000_000L,
                "physics", 7_000_000L, "sprites", 6_000_000L,
                "collision", 5_000_000L, "input", 4_000_000L,
                "audio", 1_000_000L));

        List<SectionStats> selected = renderer().legendSections(snapshot);

        assertEquals(List.of("render", "update", "physics", "sprites", "collision", "audio"),
                selected.stream().map(SectionStats::name).toList());
    }

    @Test
    void legendDoesNotDuplicateAudioWhenAlreadyInTopSix() {
        ProfileSnapshot snapshot = snapshot(Map.of(
                "render", 9_000_000L, "audio", 8_000_000L,
                "update", 7_000_000L, "physics", 6_000_000L,
                "sprites", 5_000_000L, "input", 4_000_000L,
                "collision", 3_000_000L));

        List<SectionStats> selected = renderer().legendSections(snapshot);

        assertEquals(List.of("render", "audio", "update", "physics", "sprites", "input"),
                selected.stream().map(SectionStats::name).toList());
    }

    @Test
    void legendKeepsOrdinaryTopSixWhenAudioIsAbsent() {
        ProfileSnapshot snapshot = snapshot(Map.of(
                "render", 9_000_000L, "update", 8_000_000L,
                "physics", 7_000_000L, "sprites", 6_000_000L,
                "collision", 5_000_000L, "input", 4_000_000L,
                "timers", 3_000_000L));

        List<SectionStats> selected = renderer().legendSections(snapshot);

        assertEquals(List.of("render", "update", "physics", "sprites", "collision", "input"),
                selected.stream().map(SectionStats::name).toList());
    }

    private static ProfileSnapshot snapshot(Map<String, Long> rollingSums) {
        ProfileSnapshot snapshot = new ProfileSnapshot();
        snapshot.populate(rollingSums, 1, new float[] { 16.67f }, 0, 1, 16_670_000L);
        return snapshot;
    }

    private static PerformancePanelRenderer renderer() {
        return new PerformancePanelRenderer(320, 224,
                mock(PixelFontTextRenderer.class),
                mock(GraphicsManager.class),
                PerformanceProfiler.getInstance());
    }

    @Test
    void performanceTextScale_isHalfSize() {
        assertEquals(0.5f, PerformancePanelRenderer.PERFORMANCE_TEXT_SCALE);
    }

    @Test
    void lineHeight_usesHalfScale() throws Exception {
        Method lineHeight = PerformancePanelRenderer.class.getDeclaredMethod("lineHeight", FontSize.class);
        lineHeight.setAccessible(true);

        assertEquals(5, lineHeight.invoke(null, FontSize.SMALL));
        assertEquals(6, lineHeight.invoke(null, FontSize.MEDIUM));
        assertEquals(7, lineHeight.invoke(null, FontSize.LARGE));
    }

    @Test
    void halfScaleUsesScaledPixelFontHeight() {
        assertEquals(5, PixelFont.scaledGlyphHeight(PerformancePanelRenderer.PERFORMANCE_TEXT_SCALE));
    }

    @Test
    void heapLine_isCompactedToFitRightColumn() throws Exception {
        Method formatHeapLine = PerformancePanelRenderer.class.getDeclaredMethod(
                "formatHeapLine", StringBuilder.class, double.class, double.class, int.class);
        formatHeapLine.setAccessible(true);

        String line = (String) formatHeapLine.invoke(null, new StringBuilder(), 123.0, 456.0, 78);

        assertEquals("Heap 123/456 78%", line);
        assertTrue(new PixelFont().measureWidth(line, PerformancePanelRenderer.PERFORMANCE_TEXT_SCALE) <= 85);
    }

    @Test
    void gcLine_isCompactedToFitRightColumn() throws Exception {
        Method formatGcLine = PerformancePanelRenderer.class.getDeclaredMethod(
                "formatGcLine", StringBuilder.class, long.class, long.class, double.class);
        formatGcLine.setAccessible(true);

        String line = (String) formatGcLine.invoke(null, new StringBuilder(), 12L, 345L, 6.7);

        assertEquals("GC 12 345ms 6.7M/s", line);
        assertTrue(new PixelFont().measureWidth(line, PerformancePanelRenderer.PERFORMANCE_TEXT_SCALE) <= 85);
    }

    @Test
    void appendPieSliceTriangles_emitsTriangleListForSingleBatchedDraw() throws Exception {
        Method append = PerformancePanelRenderer.class.getDeclaredMethod(
                "appendPieSliceTriangles", FloatBuffer.class, int.class, int.class, int.class,
                float.class, float.class, float[].class);
        append.setAccessible(true);
        FloatBuffer buffer = FloatBuffer.allocate(64 * 6);

        int vertices = (int) append.invoke(null, buffer, 100, 80, 16, 90.0f, 20.0f,
                new float[] { 0.2f, 0.6f, 1.0f });

        assertEquals(6, vertices);
        assertEquals(6 * 6, buffer.position());
    }

    @Test
    void appendGraphLineBatch_emitsMidlineAndBorderAsSingleLineBatch() throws Exception {
        Method append = PerformancePanelRenderer.class.getDeclaredMethod(
                "appendGraphLineBatch", FloatBuffer.class, int.class, int.class, int.class, int.class);
        append.setAccessible(true);
        FloatBuffer buffer = FloatBuffer.allocate(16 * 6);

        int vertices = (int) append.invoke(null, buffer, 10, 20, 80, 25);

        assertEquals(10, vertices);
        assertEquals(10 * 6, buffer.position());
    }
}
