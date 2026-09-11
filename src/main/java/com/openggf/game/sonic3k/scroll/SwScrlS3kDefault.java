package com.openggf.game.sonic3k.scroll;

import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

import static com.openggf.level.scroll.M68KMath.VISIBLE_LINES;
import static com.openggf.level.scroll.M68KMath.asrWord;
import static com.openggf.level.scroll.M68KMath.negWord;

/**
 * Default fallback scroll handler for Sonic 3&K zones that don't have
 * a zone-specific implementation yet. Provides simple parallax:
 * BG X at 1/4 FG speed, BG Y at 1/4 FG speed.
 */
public class SwScrlS3kDefault extends AbstractZoneScrollHandler {

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
        short bgScroll = asrWord(fgScroll, 2); // BG at 1/4 FG speed

        composer.setVscrollFactorBG(asrWord(cameraY, 2)); // BG Y at 1/4 FG speed
        composer.fillPackedScrollWords(0, VISIBLE_LINES, fgScroll, bgScroll);

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

}
