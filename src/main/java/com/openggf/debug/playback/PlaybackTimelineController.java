package com.openggf.debug.playback;

/**
 * Stateful controller for BK2 timeline navigation during playback/debugging.
 */
public final class PlaybackTimelineController {
    private static final int[] RATES = {1, 2, 4, 8};

    private final int frameCount;
    private int cursorFrame;
    private boolean playing;
    private int rateIndex;

    public PlaybackTimelineController(int frameCount) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be > 0");
        }
        this.frameCount = frameCount;
        this.cursorFrame = 0;
        this.playing = false;
        this.rateIndex = 0;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public int getCursorFrame() {
        return cursorFrame;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
    }

    public void togglePlaying() {
        this.playing = !this.playing;
    }

    public int getRate() {
        return RATES[rateIndex];
    }

    public void cycleRate() {
        rateIndex = (rateIndex + 1) % RATES.length;
    }

    public void seek(int frame) {
        cursorFrame = clamp(frame);
    }

    public void resetTo(int frame) {
        seek(frame);
        playing = false;
    }

    public void seekAndPlay(int frame, boolean playing) {
        seek(frame);
        this.playing = playing;
    }

    public void stepForward() {
        seek(cursorFrame + 1);
    }

    public void stepBackward() {
        seek(cursorFrame - 1);
    }

    public void jumpForward(int frames) {
        seek(cursorFrame + Math.max(0, frames));
    }

    public void jumpBackward(int frames) {
        seek(cursorFrame - Math.max(0, frames));
    }

    /**
     * Advances playback cursor by current rate if playing.
     * Stops playback automatically at end-of-movie.
     */
    public void advanceIfPlaying() {
        if (!playing) {
            return;
        }
        if (cursorFrame >= frameCount - 1) {
            playing = false;
            return;
        }
        seek(cursorFrame + getRate());
    }

    private int clamp(int frame) {
        return Math.max(0, Math.min(frame, frameCount - 1));
    }
}
