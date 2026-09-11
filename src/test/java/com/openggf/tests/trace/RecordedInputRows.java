package com.openggf.tests.trace;

import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.RecordedInputSnapshots;

import java.util.List;
import java.util.Objects;

/** Maps local trace rows to their recorded BK2 input snapshots. */
public final class RecordedInputRows {

    private final Bk2Movie movie;
    private final int absoluteBaseOffset;

    public RecordedInputRows(Bk2Movie movie, int absoluteBaseOffset) {
        this.movie = Objects.requireNonNull(movie, "movie");
        this.absoluteBaseOffset = absoluteBaseOffset;
    }

    public LogicalInputSnapshot snapshotAt(int localRow) {
        long absoluteRow = (long) absoluteBaseOffset + localRow;
        List<Bk2FrameInput> frames = movie.getFrames();
        if (absoluteRow < 0 || absoluteRow >= frames.size()) {
            throw new IndexOutOfBoundsException(
                    "BK2 row " + absoluteRow + " out of range [0, " + frames.size() + ")");
        }
        Bk2FrameInput current = frames.get((int) absoluteRow);
        Bk2FrameInput previous = absoluteRow > 0 ? frames.get((int) absoluteRow - 1) : null;
        return RecordedInputSnapshots.fromBk2(current, previous);
    }

    public void withLogicalOverride(int localRow, InputHandler input, Runnable action) {
        LogicalInputSnapshot snapshot = snapshotAt(localRow);
        InputHandler inputHandler = Objects.requireNonNull(input, "input");
        Runnable callback = Objects.requireNonNull(action, "action");
        if (inputHandler.hasLogicalOverride()) {
            throw new IllegalStateException("Logical input override is already installed");
        }
        inputHandler.setLogicalOverride(snapshot);
        try {
            callback.run();
        } finally {
            inputHandler.clearLogicalOverride();
        }
    }
}
