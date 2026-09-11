package com.openggf.game.rewind;

import java.util.Objects;

/**
 * Strip cache between RewindController and KeyframeStore. Expands one
 * segment of {@code intervalFrames} on demand by stepping forward from
 * a keyframe and capturing per-frame snapshots. Subsequent backward
 * steps within the expanded segment are O(1) array lookups.
 *
 * <p>v1 keeps at most one expanded segment ("currentSegment"); a
 * follow-up plan will extend this to a small ring of expanded segments
 * around the rewind cursor.
 */
public final class SegmentCache {

    /** Drives the engine forward one frame and returns a fresh snapshot. */
    @FunctionalInterface
    public interface Stepper {
        CompositeSnapshot stepAndCapture();
    }

    private final int intervalFrames;
    private int currentBaseFrame = -1;
    private CompositeSnapshot[] strip = null;
    private int validUpTo = -1;   // strip[validUpTo] is the last valid entry

    public SegmentCache(int intervalFrames) {
        if (intervalFrames <= 0) {
            throw new IllegalArgumentException(
                    "intervalFrames must be > 0, got " + intervalFrames);
        }
        this.intervalFrames = intervalFrames;
    }

    /** Drops any currently-cached segment. */
    public void invalidate() {
        currentBaseFrame = -1;
        strip = null;
        validUpTo = -1;
    }

    /**
     * Returns the already-expanded snapshot for {@code frame}, or {@code null}
     * when the frame is outside the current strip. The returned snapshot is a
     * borrowed immutable value; this cache and its probe are gameplay-thread
     * confined.
     */
    public CompositeSnapshot cachedSnapshotAtOrNull(int frame) {
        if (strip == null || currentBaseFrame < 0) {
            return null;
        }
        long offset = (long) frame - currentBaseFrame;
        if (offset < 0 || offset >= intervalFrames || offset > validUpTo) {
            return null;
        }
        return strip[(int) offset];
    }

    /**
     * Base-aware form used by callers that have already resolved a keyframe.
     */
    public CompositeSnapshot cachedSnapshotAtOrNull(int frame, int keyframeFrame) {
        return currentBaseFrame == keyframeFrame ? cachedSnapshotAtOrNull(frame) : null;
    }

    /**
     * True when {@code frame} (in segment {@code keyframeFrame}) is already
     * expanded in the cached strip, i.e. the next {@link #snapshotAt} call
     * for it will be a pure array read with no {@code restoreKeyframe}/
     * {@code stepper} side effects on live engine state. Callers that need
     * to undo a {@code snapshotAt} result (e.g. rejecting a poisoned target
     * frame) use this to know whether any live-state rollback is needed.
     */
    public boolean containsFrame(int frame, int keyframeFrame) {
        return cachedSnapshotAtOrNull(frame, keyframeFrame) != null;
    }

    /**
     * Returns the snapshot at frame F, expanding segment [K, K+interval)
     * where K is the caller-supplied keyframe frame. If F lies in a
     * different segment than the currently cached one, the cache is
     * dropped and re-expanded from that keyframe (using
     * {@code restoreKeyframe} to bring the engine back to K, then
     * {@code stepper} to advance).
     */
    public CompositeSnapshot snapshotAt(
            int frame,
            CompositeSnapshot keyframeAt,   // base keyframe of segment containing F
            int keyframeFrame,
            Runnable restoreKeyframe,       // restores engine state from keyframeAt
            Stepper stepper) {
        Objects.requireNonNull(keyframeAt, "keyframeAt");
        Objects.requireNonNull(restoreKeyframe, "restoreKeyframe");
        Objects.requireNonNull(stepper, "stepper");
        if (keyframeFrame < 0) {
            throw new IllegalArgumentException("keyframeFrame must be >= 0, got " + keyframeFrame);
        }
        long requestedOffset = (long) frame - keyframeFrame;
        if (requestedOffset < 0 || requestedOffset >= intervalFrames) {
            throw new IllegalArgumentException(
                    "frame " + frame + " outside keyframe segment [" + keyframeFrame
                            + ", " + ((long) keyframeFrame + intervalFrames) + ")");
        }
        // If we've cached this segment already, lookup is O(1).
        CompositeSnapshot cached = cachedSnapshotAtOrNull(frame, keyframeFrame);
        if (cached != null) {
            return cached;
        }
        // Otherwise expand the segment.
        currentBaseFrame = keyframeFrame;
        strip = new CompositeSnapshot[intervalFrames];
        strip[0] = keyframeAt;
        validUpTo = 0;
        try {
            restoreKeyframe.run();
            for (int offset = 1; offset <= requestedOffset; offset++) {
                strip[offset] = Objects.requireNonNull(
                        stepper.stepAndCapture(), "stepper snapshot");
                validUpTo = offset;
            }
            return strip[(int) requestedOffset];
        } catch (RuntimeException | Error failure) {
            invalidate();
            throw failure;
        }
    }
}
