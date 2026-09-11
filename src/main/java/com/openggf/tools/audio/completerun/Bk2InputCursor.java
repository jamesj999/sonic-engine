package com.openggf.tools.audio.completerun;

import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.RecordedInputSnapshots;
import java.util.List;
import java.util.Objects;

/**
 * Owns sequential BK2 input publication for a production audio capture.
 *
 * <p>The cursor position is the row ordinal in {@link Bk2Movie#getFrames()}.
 * The frame index stored inside a row is diagnostic data and never controls
 * playback. Publication and advancement deliberately form a strict two-phase
 * protocol so a caller cannot skip, repeat, or clamp a movie row.
 */
public final class Bk2InputCursor {
    private final List<Bk2FrameInput> frames;
    private int absoluteFrame;
    private boolean published;

    public Bk2InputCursor(Bk2Movie movie) {
        frames = Objects.requireNonNull(movie, "movie").getFrames();
    }

    /** Publishes the current row and its immediate predecessor. */
    public void publish(InputHandler inputHandler) {
        Objects.requireNonNull(inputHandler, "inputHandler");
        requireAvailable();
        if (published) {
            throw new IllegalStateException(
                    "BK2 row " + absoluteFrame + " was already published");
        }

        Bk2FrameInput current = frames.get(absoluteFrame);
        Bk2FrameInput previous = absoluteFrame == 0
                ? null
                : frames.get(absoluteFrame - 1);
        inputHandler.setLogicalOverride(
                RecordedInputSnapshots.fromBk2(current, previous));
        published = true;
    }

    /** Advances exactly one row after the current row has been published. */
    public void advance() {
        requireAvailable();
        if (!published) {
            throw new IllegalStateException(
                    "BK2 row " + absoluteFrame + " has not been published");
        }
        absoluteFrame++;
        published = false;
    }

    /** Returns the current immutable frame-list ordinal. */
    public int absoluteFrame() {
        return absoluteFrame;
    }

    /** Returns whether there is no row available at the current ordinal. */
    public boolean exhausted() {
        return absoluteFrame >= frames.size();
    }

    private void requireAvailable() {
        if (exhausted()) {
            throw new IllegalStateException(
                    "BK2 movie exhausted at row " + absoluteFrame);
        }
    }
}
