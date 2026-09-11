package com.openggf.debug.playback;

import com.openggf.game.GameMode;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Visual Trace Test Mode renders the transport summary in its own HUD, so the
 * legacy {@code == PLAYBACK ==} panel must stand down for the session's
 * lifetime rather than painting a second panel over the same frame.
 */
class TestPlaybackDebugManagerOverlayOwnership {
    private final PlaybackDebugManager playback =
            PlaybackDebugManager.getInstance();

    @BeforeEach
    void resetPlayback() {
        playback.setOverlayOwnedExternally(false);
        playback.endSession();
    }

    @AfterEach
    void tearDown() {
        playback.setOverlayOwnedExternally(false);
        playback.endSession();
    }

    @Test
    void externalOwnershipSilencesTheLegacyPanelAndHandsItBack() {
        playback.startSession(movie(), 0);
        assertTrue(playback.isHudVisible());
        assertFalse(playback.buildOverlayLines().isEmpty());

        playback.setOverlayOwnedExternally(true);

        assertFalse(playback.isHudVisible(),
                "the legacy panel must not reserve overlay state while handed over");
        assertEquals(List.of(), playback.buildOverlayLines());

        playback.setOverlayOwnedExternally(false);

        assertTrue(playback.isHudVisible());
        assertFalse(playback.buildOverlayLines().isEmpty());
    }

    @Test
    void transportAccessorsFeedTheReplacementHudWhileThePanelIsSilent() {
        playback.startSession(movie(), 0);
        playback.setObservedMode(GameMode.LEVEL);
        playback.setOverlayOwnedExternally(true);

        assertEquals("overlay-ownership.bk2", playback.movieName());
        assertEquals(GameMode.LEVEL, playback.observedMode());
        assertEquals(0, playback.getCursorFrame());
        assertEquals(3, playback.getMovieFrameCount());
    }

    @Test
    void movieNameIsNullWithNoLoadedMovie() {
        playback.endSession();
        assertNull(playback.movieName());
    }

    private static Bk2Movie movie() {
        return new Bk2Movie(
                Path.of("overlay-ownership.bk2"), "logkey", Map.of(),
                List.of(
                        new Bk2FrameInput(0,
                                AbstractPlayableSprite.INPUT_RIGHT,
                                0, false, "right"),
                        new Bk2FrameInput(1,
                                AbstractPlayableSprite.INPUT_LEFT,
                                0, false, "left"),
                        new Bk2FrameInput(2,
                                AbstractPlayableSprite.INPUT_DOWN,
                                0, false, "down")),
                1);
    }
}
