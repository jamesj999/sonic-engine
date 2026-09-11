package com.openggf.game.sonic1.scroll;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SwScrlGhzTest {

    @Test
    public void cloudsAutoScrollAtDifferentSpeedsWhenCameraIsStationary() {
        SwScrlGhz handler = new SwScrlGhz();
        int[] hScroll = new int[224];

        int cameraX = 0x400;
        // Use a Y where d4=0 so all three cloud bands are fully visible.
        int cameraY = 0x400;

        handler.update(hScroll, cameraX, cameraY, 0, 0);

        short cloud1Start = unpackBG(hScroll[0]);   // top cloud band (32 lines)
        short cloud2Start = unpackBG(hScroll[40]);  // middle cloud band (16 lines)
        short cloud3Start = unpackBG(hScroll[56]);  // lower cloud band (16 lines)
        short mountainStart = unpackBG(hScroll[80]); // mountain band (48 lines)
        short hillsStart = unpackBG(hScroll[120]);   // section 2 (BG2)

        for (int frame = 1; frame <= 8; frame++) {
            handler.update(hScroll, cameraX, cameraY, frame, 0);
        }

        short cloud1End = unpackBG(hScroll[0]);
        short cloud2End = unpackBG(hScroll[40]);
        short cloud3End = unpackBG(hScroll[56]);
        short mountainEnd = unpackBG(hScroll[80]);
        short hillsEnd = unpackBG(hScroll[120]);

        // Auto-scroll is leftward in screen space, so BG scroll words become more negative.
        assertEquals(-8, delta(cloud1Start, cloud1End), "Cloud layer 1 should move 8 px in 8 stationary frames");
        assertEquals(-6, delta(cloud2Start, cloud2End), "Cloud layer 2 should move 6 px in 8 stationary frames");
        assertEquals(-4, delta(cloud3Start, cloud3End), "Cloud layer 3 should move 4 px in 8 stationary frames");

        // Non-cloud sections should remain unchanged when camera does not move.
        assertEquals(0, delta(mountainStart, mountainEnd), "Mountain band should not auto-scroll");
        assertEquals(0, delta(hillsStart, hillsEnd), "Hills band should not auto-scroll");
    }

    @Test
    public void mountainAndHillBandSizesStayFixed() {
        SwScrlGhz handler = new SwScrlGhz();
        int[] hScroll = new int[224];

        int cameraX = 0x400;
        int cameraY = 0x400; // d4=0 => canonical full cloud layout
        handler.update(hScroll, cameraX, cameraY, 0, 0);

        short mountain = unpackBG(hScroll[64]); // first mountain line
        short hills = unpackBG(hScroll[112]);   // first hills line

        // Mountain occupies lines [64..111] (48 lines)
        assertEquals(mountain, unpackBG(hScroll[64]));
        assertEquals(mountain, unpackBG(hScroll[111]));
        // Hills start at line 112 and occupy 40 lines [112..151]
        assertEquals(hills, unpackBG(hScroll[112]));
        assertEquals(hills, unpackBG(hScroll[151]));
    }

    /**
     * The cloud auto-scroll bands are pure per-frame accumulators (ROM
     * Deform_GHZ adds a fixed increment every frame). Their on-screen position
     * must therefore be a deterministic function of the frame counter, not of
     * how many times {@link SwScrlGhz#update} happens to have run.
     *
     * <p>Rewind restores an earlier {@code frameCounter} and re-derives parallax
     * by calling {@code update()} again (see
     * {@code LevelManager.recomputeParallaxAfterRewindRestore}). If the cloud
     * counters instead free-run on the update call count, they keep drifting
     * during a rewind and only line up at keyframe boundaries. Re-running
     * {@code update()} at a past frame must reproduce that frame's cloud
     * positions exactly.
     */
    @Test
    public void cloudOffsetsAreADeterministicFunctionOfFrameCounter() {
        SwScrlGhz handler = new SwScrlGhz();
        int[] hScroll = new int[224];

        int cameraX = 0x400;
        int cameraY = 0x400; // d4=0 => all three cloud bands visible

        // Normal forward play up to frame 10.
        for (int frame = 0; frame <= 10; frame++) {
            handler.update(hScroll, cameraX, cameraY, frame, 0);
        }
        short cloud1AtFrame10 = unpackBG(hScroll[0]);
        short cloud2AtFrame10 = unpackBG(hScroll[40]);
        short cloud3AtFrame10 = unpackBG(hScroll[56]);

        // Keep playing forward well past a would-be keyframe.
        for (int frame = 11; frame <= 60; frame++) {
            handler.update(hScroll, cameraX, cameraY, frame, 0);
        }

        // Rewind recompute-after-restore: re-derive parallax at frame 10.
        handler.update(hScroll, cameraX, cameraY, 10, 0);

        assertEquals(cloud1AtFrame10, unpackBG(hScroll[0]),
                "cloud layer 1 must return to its frame-10 position when frame 10 is re-derived");
        assertEquals(cloud2AtFrame10, unpackBG(hScroll[40]),
                "cloud layer 2 must return to its frame-10 position when frame 10 is re-derived");
        assertEquals(cloud3AtFrame10, unpackBG(hScroll[56]),
                "cloud layer 3 must return to its frame-10 position when frame 10 is re-derived");
    }

    private short unpackBG(int packed) {
        return (short) (packed & 0xFFFF);
    }

    private int delta(short start, short end) {
        return (short) (end - start);
    }
}


