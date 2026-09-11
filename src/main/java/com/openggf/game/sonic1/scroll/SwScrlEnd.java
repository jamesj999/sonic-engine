package com.openggf.game.sonic1.scroll;

import java.util.logging.Logger;

/**
 * ROM-accurate BG scroll initialization for the Sonic 1 ending zone.
 * <p>
 * The ending zone uses the same per-frame Deform_GHZ routine as Green Hill,
 * but its BgScroll_End initialization sets different starting BG camera
 * positions to create a pre-applied parallax offset:
 * <pre>
 *   bg2screenposx = cameraX / 2          (= cameraX * 128/256)
 *   bg3screenposx = 3 * cameraX / 8      (= cameraX * 96/256)
 * </pre>
 * This matches the accumulation ratios (128/256 and 96/256) as if the camera
 * had scrolled from position 0 to the current cameraX at normal speed.
 * <p>
 * Compare to BgScroll_GHZ which leaves bg2/bg3 at cameraX (no initial offset).
 * <p>
 * Reference: s1disasm/_inc/LevelSizeLoad &amp; BgScrollSpeed (JP1).asm - BgScroll_End
 */
public class SwScrlEnd extends SwScrlGhz {

    private static final Logger LOG = Logger.getLogger(SwScrlEnd.class.getName());

    /**
     * Initialize BG camera positions per BgScroll_End.
     * <p>
     * ROM sequence:
     * <pre>
     *   move.w  (v_screenposx).w,d0    ; d0 = cameraX
     *   asr.w   #1,d0                   ; d0 = cameraX / 2
     *   move.w  d0,(v_bgscreenposx).w
     *   move.w  d0,(v_bg2screenposx).w  ; bg2 = cameraX/2
     *   asr.w   #2,d0                   ; d0 = cameraX / 8
     *   move.w  d0,d1
     *   add.w   d0,d0                   ; d0 = cameraX / 4
     *   add.w   d1,d0                   ; d0 = 3*cameraX/8
     *   move.w  d0,(v_bg3screenposx).w  ; bg3 = 3*cameraX/8
     * </pre>
     */
    @Override
    public void init(int cameraX) {
        // BgScroll_End: bg2 = cameraX/2, bg3 = 3*cameraX/8
        // These match the accumulation ratios (128/256 and 96/256),
        // as if the camera scrolled from 0 to cameraX.
        bg2XPos = (long) (cameraX / 2) << 16;
        bg3XPos = (long) (3 * cameraX / 8) << 16;
        cloudLayer1Counter = 0;
        cloudLayer2Counter = 0;
        cloudLayer3Counter = 0;
        // Re-arm the frame-anchored cloud baseline (see SwScrlGhz) so a reused
        // instance re-derives the auto-scroll clouds from the frame counter
        // rather than carrying a stale anchor across re-inits.
        cloudBaseFrameSet = false;
        lastCameraX = cameraX;
        initialized = true;
        LOG.info("SwScrlEnd.init: cameraX=" + cameraX +
                " bg2XPos=" + (bg2XPos >> 16) + " bg3XPos=" + (bg3XPos >> 16));
    }
}
