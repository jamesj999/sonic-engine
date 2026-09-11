package com.openggf.tests.objects;

import com.openggf.level.objects.ObjectPlayerRangeOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ObjectPlayerRangeOps} to {@code Obj28_ChkDel}
 * (docs/s2disasm/s2.asm:24720-24730) and its Sonic 1 spelling
 * {@code Anml_End_ChkDel} (docs/s1disasm/_incObj/28, 29 Animals and
 * Points.asm:300-311): player-relative, one-sided on both edges, and mixing an
 * unsigned near test with a signed far test.
 */
class TestObjectPlayerRangeOps {

    /** ROM: Sonic 2 {@code #$180}, Sonic 1 {@code #320+64}. */
    @Test
    void windowIsTheRomConstant() {
        assertEquals(384, ObjectPlayerRangeOps.PLAYER_DELETION_WINDOW);
        assertEquals(0x180, ObjectPlayerRangeOps.PLAYER_DELETION_WINDOW);
        assertEquals(320 + 64, ObjectPlayerRangeOps.PLAYER_DELETION_WINDOW);
    }

    /**
     * ROM: {@code bcs} on the raw subtraction. The near edge is the player's own
     * x and has no constant behind it: one pixel behind the player and the ROM
     * never reaches the render-flag test at all.
     */
    @Test
    void behindThePlayerIsOutsideTheWindow() {
        int player = 0x1000;
        assertFalse(ObjectPlayerRangeOps.withinPlayerDeletionWindow(0x0FFF, player));
        assertFalse(ObjectPlayerRangeOps.withinPlayerDeletionWindow(0x0000, player));
        assertTrue(ObjectPlayerRangeOps.withinPlayerDeletionWindow(player, player),
                "the player's own x is inside the window");
    }

    /** ROM: {@code subi.w #$180,d0 / bpl} — 384 ahead is already outside. */
    @Test
    void farEdgeIsTheWindowExclusive() {
        int player = 0x1000;
        assertTrue(ObjectPlayerRangeOps.withinPlayerDeletionWindow(player + 383, player));
        assertFalse(ObjectPlayerRangeOps.withinPlayerDeletionWindow(player + 384, player));
        assertFalse(ObjectPlayerRangeOps.withinPlayerDeletionWindow(player + 4096, player));
    }

    /**
     * Pixel-accurate: unlike the coarse camera predicates, neither operand is
     * quantised, so every pixel step can flip the verdict at the edges.
     */
    @Test
    void thereIsNoBlockQuantisation() {
        int player = 0x1003;
        assertFalse(ObjectPlayerRangeOps.withinPlayerDeletionWindow(0x1002, player));
        assertTrue(ObjectPlayerRangeOps.withinPlayerDeletionWindow(0x1003, player));
        assertTrue(ObjectPlayerRangeOps.withinPlayerDeletionWindow(0x1182, player));
        assertFalse(ObjectPlayerRangeOps.withinPlayerDeletionWindow(0x1183, player));
    }

    /**
     * The window follows the player, not the camera: the same object x flips
     * verdict purely because the player moved.
     */
    @Test
    void theWindowIsAnchoredToThePlayer() {
        int object = 0x1200;
        assertTrue(ObjectPlayerRangeOps.withinPlayerDeletionWindow(object, 0x1100));
        assertFalse(ObjectPlayerRangeOps.withinPlayerDeletionWindow(object, 0x1000));
    }

    /**
     * ROM: {@code bpl} tests bit 15 of a 16-bit register, so an extreme
     * separation re-enters the window. Pinned as ROM behaviour rather than
     * smoothed away.
     */
    @Test
    void theFarTestWrapsAt16Bits() {
        int player = 0x1000;
        assertFalse(ObjectPlayerRangeOps.withinPlayerDeletionWindow(0x1000 + 0x8000 - 1, player));
        assertTrue(ObjectPlayerRangeOps.withinPlayerDeletionWindow(0x1000 + 0x8180, player));
    }
}
