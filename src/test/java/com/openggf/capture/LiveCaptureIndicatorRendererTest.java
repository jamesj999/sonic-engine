package com.openggf.capture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveCaptureIndicatorRendererTest {
    @Test
    void placesRedDotAndWhiteRecEightPixelsFromTopRightAt224High() {
        assertPlacement(224, 206, 208);
    }

    @Test
    void topRightPlacementTracksASecondProjectionHeight() {
        assertPlacement(240, 222, 224);
    }

    /**
     * The notice replaces the indicator, so it anchors to the same corner: the
     * player is already looking there. Red, because a vanishing indicator is
     * otherwise indistinguishable from a deliberate stop.
     */
    @Test
    void interruptionNoticeIsRedAndSharesTheIndicatorCorner() {
        List<String> calls = new ArrayList<>();
        LiveCaptureIndicatorRenderer renderer = renderer(calls);

        renderer.renderInterruption("REC STOPPED: RESIZED", 320, 224);

        // Same 8px margin as the indicator; right-aligned on the measured width.
        assertEquals(List.of(
                "text:REC STOPPED: RESIZED:297:208:1.0:0.0:0.0:1.0:0.8"), calls);
    }

    @Test
    void interruptionNoticeDrawsNoRecordingDot() {
        List<String> calls = new ArrayList<>();

        renderer(calls).renderInterruption("REC STOPPED: ERROR", 320, 224);

        assertTrue(calls.stream().noneMatch(call -> call.startsWith("dot:")),
                "the notice must not imply a recording is still running");
    }

    private static LiveCaptureIndicatorRenderer renderer(List<String> calls) {
        return new LiveCaptureIndicatorRenderer(
                (x, y, diameter, r, g, b, a) ->
                        calls.add("dot:" + x + ":" + y + ":" + diameter + ":" + r + ":" + g + ":" + b + ":" + a),
                (text, x, y, r, g, b, a, scale) ->
                        calls.add("text:" + text + ":" + x + ":" + y + ":" + r + ":" + g + ":" + b + ":" + a + ":" + scale),
                new LiveCaptureIndicatorRenderer.TextMeasurer() {
                    public int width(String text, float scale) { return 15; }
                    public int height(float scale) { return 8; }
                });
    }

    private static void assertPlacement(int projectionHeight, int expectedDotY, int expectedTextY) {
        List<String> calls = new ArrayList<>();
        LiveCaptureIndicatorRenderer renderer = new LiveCaptureIndicatorRenderer(
                (x, y, diameter, r, g, b, a) ->
                        calls.add("dot:" + x + ":" + y + ":" + diameter + ":" + r + ":" + g + ":" + b + ":" + a),
                (text, x, y, r, g, b, a, scale) ->
                        calls.add("text:" + text + ":" + x + ":" + y + ":" + r + ":" + g + ":" + b + ":" + a + ":" + scale),
                new LiveCaptureIndicatorRenderer.TextMeasurer() {
                    public int width(String text, float scale) { return 15; }
                    public int height(float scale) { return 8; }
                });

        renderer.render(320, projectionHeight);

        assertEquals(List.of(
                "dot:282:" + expectedDotY + ":10:1.0:0.0:0.0:1.0",
                "text:REC:297:" + expectedTextY + ":1.0:1.0:1.0:1.0:0.8"), calls);
    }
}
