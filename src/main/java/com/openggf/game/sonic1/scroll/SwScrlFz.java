package com.openggf.game.sonic1.scroll;

import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import static com.openggf.level.scroll.M68KMath.*;

/**
 * Final Zone scroll routine.
 * Reference: s1disasm/_inc/DeformLayers (JP1).asm - Deform_SBZ / Deform_SBZ2
 *            s1disasm/_inc/LevelSizeLoad &amp; BgScrollSpeed.asm - BgScroll_SBZ
 *
 * In the ROM, Final Zone has v_zone = 5 (SBZ) and v_act = 2. Deform_SBZ
 * checks act and branches to Deform_SBZ2 (simple uniform scroll) when
 * act != 0. This is the same path used by SBZ Act 2.
 *
 * Deform_SBZ2:
 * <ul>
 *   <li>BG X: 25% speed (scrshiftx &lt;&lt; 6 = 64/256)</li>
 *   <li>BG Y: 12.5% speed (scrshifty &lt;&lt; 4 &lt;&lt; 1 = 32/256)</li>
 *   <li>H-scroll: uniform (all 224 lines same)</li>
 * </ul>
 *
 * BgScroll_SBZ initial setup (from LevelSizeLoad &amp; BgScrollSpeed.asm):
 * <pre>
 *   asl.l  #4,d0          ; d0 = cameraY * 16 (long shift)
 *   asl.l  #1,d0          ; d0 = cameraY * 32
 *   asr.l  #8,d0          ; d0 = cameraY * 32 / 256
 *   move.w d0,bgscreenposy
 * </pre>
 */
public class SwScrlFz extends AbstractZoneScrollHandler {

    // Persistent BG camera (16.16 fixed point)
    private long bgXPos;
    private long bgYPos;

    private int lastCameraX;
    private int lastCameraY;
    private boolean initialized = false;

    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    public void init(int cameraX, int cameraY) {
        // BgScrollSpeed default: bgscreenposx = screenposx
        bgXPos = (long) cameraX << 16;
        // BgScroll_SBZ: asl.l #4,d0; asl.l #1,d0; asr.l #8,d0; move.w d0,bgscreenposy
        // = cameraY * 32 / 256 (12.5% of camera Y)
        int bgYInit = (cameraY * 32) >> 8;
        bgYPos = (long) bgYInit << 16;
        lastCameraX = cameraX;
        lastCameraY = cameraY;
        initialized = true;
    }

    @Override
    public void update(int[] horizScrollBuf,
            int cameraX,
            int cameraY,
            int frameCounter,
            int actId) {
        if (!initialized) {
            init(cameraX, cameraY);
        }

        resetScrollTracking();
        composer.reset();

        int deltaX = cameraX - lastCameraX;
        int deltaY = cameraY - lastCameraY;
        lastCameraX = cameraX;
        lastCameraY = cameraY;

        // Deform_SBZ2: d4 = scrshiftx << 6 = deltaX * 64 * 256
        bgXPos += (long) deltaX * 64 * 256;

        // Deform_SBZ2: d5 = scrshifty << 5 = deltaY * 32 * 256
        bgYPos += (long) deltaY * 32 * 256;

        int bgX = (int) (bgXPos >> 16);
        int bgY = (int) (bgYPos >> 16);

        composer.setVscrollFactorBG((short) bgY);

        // Uniform h-scroll (Deform_SBZ2: all 224 lines same)
        short fgScroll = negWord(cameraX);
        short bgScroll = negWord(bgX);

        composer.fillPackedScrollWords(0, VISIBLE_LINES, fgScroll, bgScroll);
        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    @Override
    public int getBgCameraX() {
        return (int) (bgXPos >> 16);
    }
}
