package com.openggf.game.sonic1.scroll;

import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import static com.openggf.level.scroll.M68KMath.*;

/**
 * Uniform S1 background scrolling: quarter-speed X and eighth-speed Y.
 * SBZ and FZ currently share this implementation of Deform_SBZ2 and BgScroll_SBZ.
 * Each route owns a separate instance, including fractional camera state.
 */
public final class UniformQuarterSpeedScroll extends AbstractZoneScrollHandler {

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
