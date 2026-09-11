package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * White-box guard for S2 title-card widescreen wiring.
 *
 * <p>The pure arithmetic tests cover the intended behavior, but a later merge
 * dropped the actual TitleCardManager calls while leaving those tests green.
 * This guard pins the manager to the viewport-aware helpers and edge-margin
 * plumbing that keep the 320-space composition centered and fully clearable.
 */
class TestS2TitleCardManagerWidescreenGuard {

    private static String source() throws IOException {
        return Files.readString(Path.of(
                "src/main/java/com/openggf/game/sonic2/titlecard/TitleCardManager.java"));
    }

    @Test
    void managerKeepsViewportAwareCenteringHelpers() throws IOException {
        String src = source();

        assertTrue(src.contains("private int viewportWidth()"),
                "S2 title cards must read GraphicsManager projection width");
        assertTrue(src.contains("private int xOffset()"),
                "S2 title cards must center the native 320-wide composition");
        assertTrue(src.contains("GameServices.graphics().getProjectionWidth()"),
                "viewportWidth() must use the active projection width");
        assertTrue(src.contains("element.getCurrentX() + xOffset()"),
                "sprite elements must render in centered 320-space");
    }

    @Test
    void managerKeepsViewportSpanningRectsAndExitMargin() throws IOException {
        String src = source();

        assertTrue(src.contains("element.setEdgeMargin(edgeMargin)"),
                "S2 title-card elements must exit far enough to clear widescreen side bands");
        assertTrue(src.contains("0, 0, viewportWidth(), SCREEN_HEIGHT"),
                "slide-in blackout must cover the full viewport");
        assertTrue(src.contains("0, blueTop, vw, blueBottom"),
                "blue background must span the full viewport");
        assertTrue(src.contains("int yellowRight = vw + 50"),
                "yellow title-card plane must extend past the full viewport");
        assertTrue(src.contains("leftSwooshElement.getCurrentX() + xOff"),
                "red plane must follow the centered swoosh position");
    }
}
