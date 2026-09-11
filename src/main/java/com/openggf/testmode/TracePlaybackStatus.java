package com.openggf.testmode;

/**
 * Transport summary pinned to the top-right corner of the visual trace HUD.
 * <p>
 * Replaces the legacy {@code == PLAYBACK ==} debug panel for trace sessions.
 * Its {@code Input} line is dropped — the trace HUD already draws the BK2 input
 * glyphs — and its status message is dropped as redundant with the trace HUD's
 * own state.
 *
 * @param movieName file name of the BK2 movie driving playback
 * @param mode last observed {@code GameMode}
 * @param frame current playback cursor frame
 * @param frameCount last frame index in the movie
 * @param rateLabel fast-forward ladder display, e.g. {@code < 1x >}
 */
public record TracePlaybackStatus(
        String movieName, String mode, int frame, int frameCount, String rateLabel) {
}
