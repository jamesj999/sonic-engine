package com.openggf.game.sonic2.scroll;

import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.M68KMath;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

/**
 * ROM-accurate implementation of SwScrl_MTZ (Metropolis Zone scroll routine).
 * Reference: s2.asm SwScrl_MTZ
 *
 * MTZ uses a simple uniform parallax:
 * - All 224 scanlines share the same BG scroll (-Camera_BG_X_pos)
 * - BG Y scrolls from Camera_BG_Y_pos
 *
 * Per s2.asm:15605/15613-15615, the routine reads the pre-computed Camera_BG_X_pos /
 * Camera_BG_Y_pos (BG camera), NOT the foreground camera. The 1/8 X and 1/4 Y ratios
 * (InitCam_Std) live entirely in {@link BackgroundCamera} tracking; this handler only
 * negates/copies the BG-camera words into the scroll buffers. The foreground horizontal
 * scroll word is -Camera_X_pos.
 */
public class SwScrlMtz extends AbstractZoneScrollHandler {

    private final BackgroundCamera bgCamera;
    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    // Mirrors BackgroundCamera.ZONE_MTZ so per-frame BG tracking selects the MTZ branch.
    private static final int ZONE_MTZ = 7;

    // Previous foreground camera position, used to compute per-frame BG-camera diffs.
    private int lastCameraX;
    private int lastCameraY;
    private boolean haveLast;

    public SwScrlMtz(BackgroundCamera bgCamera) {
        this.bgCamera = bgCamera;
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        resetScrollTracking();
        composer.reset();

        // Drive the BG camera each frame with the MTZ 1/8 X / 1/4 Y ratios. The ratios
        // live ONLY in BackgroundCamera.updateFromForeground (ZONE_MTZ branch); this
        // handler just reads the resulting Camera_BG_X_pos / Camera_BG_Y_pos words.
        // Driving it here keeps the BG camera live regardless of which provider update
        // overload runs, so the background never freezes at its init value.
        int fgXDiff = haveLast ? (cameraX - lastCameraX) : 0;
        int fgYDiff = haveLast ? (cameraY - lastCameraY) : 0;
        bgCamera.updateFromForeground(cameraX, cameraY, fgXDiff, fgYDiff, ZONE_MTZ);
        lastCameraX = cameraX;
        lastCameraY = cameraY;
        haveLast = true;

        // FG horizontal scroll word: -Camera_X_pos (s2.asm SwScrl_MTZ).
        short fgScroll = M68KMath.negWord(cameraX);
        // BG horizontal scroll word: -Camera_BG_X_pos. The 1/8 X ratio is applied
        // when BackgroundCamera tracks the foreground; we only negate the BG word here.
        short bgScroll = M68KMath.negWord(bgCamera.getBgXPos());

        // BG vertical scroll factor comes straight from Camera_BG_Y_pos (1/4 ratio
        // already applied in BackgroundCamera tracking).
        composer.setVscrollFactorBG((short) bgCamera.getBgYPos());
        composer.fillPackedScrollWords(0, M68KMath.VISIBLE_LINES, fgScroll, bgScroll);

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }
}
