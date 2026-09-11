package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.game.titlecard.TitleCardElement;
import org.junit.jupiter.api.Test;

/**
 * Verifies that title-card elements fully clear a wider-than-320 viewport on
 * exit. Drives the real {@link TitleCardElement} (pure arithmetic — no OpenGL,
 * ROM or singletons) through a full slide-in/slide-out cycle and checks the
 * rendered position against the viewport edges.
 *
 * <p>Without {@link TitleCardElement#setEdgeMargin(int)} the centred
 * (xOffset-shifted) exit endpoint lands back inside a wide viewport, so the
 * S1 elements and the S2 red/yellow blocks that follow them never leave the
 * screen. The edge margin extends each off-screen endpoint outward by
 * {@code viewportWidth - 320}. At native 320 the margin is 0 — byte-identical.
 */
class TestTitleCardEdgeMargin {

    private static final int NATIVE_WIDTH = 320;

    private static int xOffset(int viewportWidth) {
        return (viewportWidth - NATIVE_WIDTH) / 2;
    }

    /** Runs an element to its target, then all the way through its exit. */
    private static void runFullCycle(TitleCardElement e) {
        for (int i = 0; i < 1000 && !e.isAtTarget(); i++) {
            e.updateSlideIn();
        }
        e.startExit();
        for (int i = 0; i < 1000 && !e.hasExited(); i++) {
            e.updateSlideOut();
        }
        assertTrue(e.hasExited(), "element must reach its exit endpoint");
    }

    private static int renderedLeft(TitleCardElement e, int viewportWidth) {
        return e.getCurrentX() + xOffset(viewportWidth);
    }

    private static int renderedRight(TitleCardElement e, int viewportWidth) {
        return renderedLeft(e, viewportWidth) + e.getWidthPixels();
    }

    // -------------------------------------------------------------------------
    // Left-exiting elements must clear the left viewport edge (right edge <= 0).
    // -------------------------------------------------------------------------

    @Test
    void leftSwoosh_clearsLeftEdge_atUltrawide() {
        int vw = 528; // ULTRA_21_9
        TitleCardElement e = TitleCardElement.createLeftSwoosh();
        e.setEdgeMargin(vw - NATIVE_WIDTH);
        runFullCycle(e);
        assertTrue(renderedRight(e, vw) <= 0,
                "left swoosh must clear the left viewport edge; rendered right = "
                        + renderedRight(e, vw));
    }

    @Test
    void zoneText_clearsLeftEdge_atUltrawide() {
        int vw = 528;
        TitleCardElement e = TitleCardElement.createZoneText();
        e.setEdgeMargin(vw - NATIVE_WIDTH);
        runFullCycle(e);
        assertTrue(renderedRight(e, vw) <= 0,
                "ZONE text must clear the left viewport edge; rendered right = "
                        + renderedRight(e, vw));
    }

    // -------------------------------------------------------------------------
    // Right-exiting elements must clear the right viewport edge (left edge >= vw).
    // -------------------------------------------------------------------------

    @Test
    void bottomBar_clearsRightEdge_atUltrawide() {
        int vw = 528;
        TitleCardElement e = TitleCardElement.createBottomBar();
        e.setEdgeMargin(vw - NATIVE_WIDTH);
        runFullCycle(e);
        assertTrue(renderedLeft(e, vw) >= vw,
                "bottom bar must clear the right viewport edge; rendered left = "
                        + renderedLeft(e, vw) + ", vw = " + vw);
    }

    @Test
    void zoneName_clearsRightEdge_atUltrawide() {
        int vw = 528;
        TitleCardElement e = TitleCardElement.createZoneName(0);
        e.setEdgeMargin(vw - NATIVE_WIDTH);
        runFullCycle(e);
        assertTrue(renderedLeft(e, vw) >= vw,
                "zone name must clear the right viewport edge; rendered left = "
                        + renderedLeft(e, vw) + ", vw = " + vw);
    }

    // -------------------------------------------------------------------------
    // Native 320 is byte-identical: margin 0 leaves the exit endpoint at the
    // authored startX, and the element still clears the 320 frame.
    // -------------------------------------------------------------------------

    @Test
    void native_exitEndpointUnchanged_andClears320() {
        TitleCardElement swoosh = TitleCardElement.createLeftSwoosh();
        swoosh.setEdgeMargin(0); // native
        runFullCycle(swoosh);
        // Authored startX is -16 — exit endpoint must be unchanged at native.
        assertEquals(-16, swoosh.getCurrentX(),
                "native exit endpoint must equal the authored startX");
        assertTrue(renderedRight(swoosh, NATIVE_WIDTH) <= 0,
                "swoosh must clear the 320 frame at native width");

        TitleCardElement bar = TitleCardElement.createBottomBar();
        bar.setEdgeMargin(0);
        runFullCycle(bar);
        assertEquals(552, bar.getCurrentX(),
                "native bottom-bar exit endpoint must equal the authored startX");
        assertTrue(renderedLeft(bar, NATIVE_WIDTH) >= NATIVE_WIDTH,
                "bottom bar must clear the 320 frame at native width");
    }

    /**
     * The edge margin only extends off-screen travel — it must never change the
     * on-screen target (display) position. The element still reaches the same
     * targetX it would at native.
     */
    @Test
    void edgeMargin_doesNotShiftTargetPosition() {
        TitleCardElement nativeBar = TitleCardElement.createBottomBar();
        nativeBar.setEdgeMargin(0);
        for (int i = 0; i < 1000 && !nativeBar.isAtTarget(); i++) {
            nativeBar.updateSlideIn();
        }

        TitleCardElement wideBar = TitleCardElement.createBottomBar();
        wideBar.setEdgeMargin(528 - NATIVE_WIDTH);
        for (int i = 0; i < 1000 && !wideBar.isAtTarget(); i++) {
            wideBar.updateSlideIn();
        }

        assertEquals(nativeBar.getCurrentX(), wideBar.getCurrentX(),
                "on-screen target X must be identical regardless of edge margin");
    }
}
