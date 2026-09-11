package com.openggf.game.sonic3k.scroll;

import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

import static com.openggf.level.scroll.M68KMath.negWord;

/**
 * Pachinko / Glowing Spheres bonus stage scroll handler.
 *
 * <p>ROM reference: {@code Pachinko_BGScroll} in sonic3k.asm.
 * Background vertical scroll follows camera Y at 1/8 speed. Background horizontal
 * scroll uses an anchored 5/8 parallax curve around screen center offset $A0.
 */
public class SwScrlPachinko extends AbstractZoneScrollHandler {
    private static final int VISIBLE_LINES = 224;

    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        resetScrollTracking();
        composer.reset();

        short fgScroll = negWord(cameraX);
        int bgWorldX = 0x60 + (((cameraX - 0xA0) * 5) >> 3);
        short bgScroll = negWord(bgWorldX);
        composer.setVscrollFactorBG((short) (cameraY >> 3));

        composer.fillPackedScrollWords(0, VISIBLE_LINES, fgScroll, bgScroll);

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }
}
