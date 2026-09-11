package com.openggf.tests.playback;

import com.openggf.debug.playback.PlaybackTimelineController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestPlaybackTimelineController {

    @Test
    public void seekClampsToRange() {
        PlaybackTimelineController timeline = new PlaybackTimelineController(10);
        timeline.seek(-25);
        assertEquals(0, timeline.getCursorFrame());

        timeline.seek(999);
        assertEquals(9, timeline.getCursorFrame());
    }

    @Test
    public void steppingAndJumpingMoveCursor() {
        PlaybackTimelineController timeline = new PlaybackTimelineController(20);
        timeline.stepForward();
        timeline.stepForward();
        assertEquals(2, timeline.getCursorFrame());

        timeline.stepBackward();
        assertEquals(1, timeline.getCursorFrame());

        timeline.jumpForward(8);
        assertEquals(9, timeline.getCursorFrame());

        timeline.jumpBackward(3);
        assertEquals(6, timeline.getCursorFrame());
    }

    @Test
    public void playbackStopsAtEndOfMovie() {
        PlaybackTimelineController timeline = new PlaybackTimelineController(5);
        timeline.togglePlaying();
        assertTrue(timeline.isPlaying());

        timeline.advanceIfPlaying();
        assertEquals(1, timeline.getCursorFrame());

        timeline.cycleRate(); // 2x
        timeline.advanceIfPlaying();
        assertEquals(3, timeline.getCursorFrame());

        timeline.advanceIfPlaying();
        assertEquals(4, timeline.getCursorFrame());
        assertTrue(timeline.isPlaying());

        timeline.advanceIfPlaying();
        assertEquals(4, timeline.getCursorFrame());
        assertFalse(timeline.isPlaying());
    }

    @Test
    public void finalFrameRemainsPlayingUntilItHasBeenConsumed() {
        PlaybackTimelineController timeline = new PlaybackTimelineController(3);
        timeline.setPlaying(true);

        timeline.advanceIfPlaying();
        assertEquals(1, timeline.getCursorFrame());
        assertTrue(timeline.isPlaying());

        timeline.advanceIfPlaying();
        assertEquals(2, timeline.getCursorFrame());
        assertTrue(timeline.isPlaying(), "final frame must still be applied on the next gameplay tick");

        timeline.advanceIfPlaying();
        assertEquals(2, timeline.getCursorFrame());
        assertFalse(timeline.isPlaying());
    }

    @Test
    public void resetToPausesPlayback() {
        PlaybackTimelineController timeline = new PlaybackTimelineController(12);
        timeline.setPlaying(true);
        timeline.seek(9);
        timeline.resetTo(3);

        assertEquals(3, timeline.getCursorFrame());
        assertFalse(timeline.isPlaying());
    }

    @Test
    public void seekAndPlayMovesCursorAndUpdatesPlayingState() {
        PlaybackTimelineController timeline = new PlaybackTimelineController(12);
        timeline.seekAndPlay(8, true);

        assertEquals(8, timeline.getCursorFrame());
        assertTrue(timeline.isPlaying());

        timeline.seekAndPlay(4, false);

        assertEquals(4, timeline.getCursorFrame());
        assertFalse(timeline.isPlaying());
    }
}
