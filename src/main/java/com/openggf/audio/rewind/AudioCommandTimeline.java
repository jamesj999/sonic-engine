package com.openggf.audio.rewind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Append-only command history indexed by absolute entry position.
 *
 * <p>Entries are appended in non-decreasing frame order: forward stepping only
 * moves {@code currentFrame} forward, and every rewind path truncates the tail
 * with {@link #discardAfter(long)} before moving the cursor backward. Frame-local
 * operations therefore walk from the tail instead of scanning the full list.
 *
 * <p>Indices handed out to snapshots ({@link #entryCount()}) are ABSOLUTE: they
 * keep their meaning after old history is pruned via {@link #pruneBefore(int)},
 * which only advances {@link #firstRetainedEntryIndex()}.
 */
public final class AudioCommandTimeline {
    private final ArrayList<AudioTimelineEntry> entries = new ArrayList<>();
    private int prunedEntryCount;
    private long currentFrame;
    private int nextOrder;

    public final class PreparedAppend {
        private final long frame = currentFrame;
        private final int order = nextOrder;
        private final List<AudioTimelineEntry> prepared;
        private boolean committed;

        private PreparedAppend(List<AudioCommand> commands) {
            ArrayList<AudioTimelineEntry> entries =
                    new ArrayList<>(commands.size());
            int candidateOrder = order;
            for (AudioCommand command : commands) {
                entries.add(new AudioTimelineEntry(
                        frame, candidateOrder++, command));
            }
            prepared = List.copyOf(entries);
            AudioCommandTimeline.this.entries.ensureCapacity(
                    AudioCommandTimeline.this.entries.size()
                            + prepared.size());
        }

        public void commit() {
            if (committed) {
                return;
            }
            AudioCommandTimeline.this.entries.addAll(prepared);
            nextOrder = order + prepared.size();
            committed = true;
        }
    }

    public PreparedAppend prepareAppend(List<AudioCommand> commands) {
        return new PreparedAppend(List.copyOf(
                Objects.requireNonNull(commands, "commands")));
    }

    public void beginFrame(long frame) {
        if (frame != currentFrame) {
            currentFrame = frame;
            nextOrder = entriesOnFrame(frame);
        }
    }

    public AudioTimelineEntry record(AudioCommand command) {
        Objects.requireNonNull(command, "command");
        assert entries.isEmpty() || entries.get(entries.size() - 1).frame() <= currentFrame
                : "timeline entries must be appended in non-decreasing frame order";
        AudioTimelineEntry entry = new AudioTimelineEntry(currentFrame, nextOrder++, command);
        entries.add(entry);
        return entry;
    }

    public void discardAfter(long frame) {
        for (int i = entries.size() - 1; i >= 0 && entries.get(i).frame() > frame; i--) {
            entries.remove(i);
        }
        if (currentFrame > frame) {
            currentFrame = frame;
            nextOrder = entriesOnFrame(frame);
        }
    }

    /**
     * Drops retained entries with absolute index below {@code entryIndex}.
     * Later entries keep their absolute indices; callers key this off the
     * earliest retained audio keyframe's {@code commandEntryCount} so every
     * replayable keyframe still resolves its replay range.
     */
    public void pruneBefore(int entryIndex) {
        if (entryIndex <= prunedEntryCount) {
            return;
        }
        int clamped = Math.min(entryIndex, entryCount());
        entries.subList(0, clamped - prunedEntryCount).clear();
        prunedEntryCount = clamped;
    }

    /** Total entries ever recorded (absolute), including pruned ones. */
    public int entryCount() {
        return prunedEntryCount + entries.size();
    }

    /** Absolute index of the oldest retained entry. */
    public int firstRetainedEntryIndex() {
        return prunedEntryCount;
    }

    /** Returns the retained entry at the given ABSOLUTE index. */
    public AudioTimelineEntry entryAt(int entryIndex) {
        int retainedIndex = entryIndex - prunedEntryCount;
        if (retainedIndex < 0) {
            throw new IndexOutOfBoundsException(
                    "entry " + entryIndex + " was pruned (first retained: " + prunedEntryCount + ")");
        }
        return entries.get(retainedIndex);
    }

    /** Copied view of the retained entries; index 0 is {@link #firstRetainedEntryIndex()}. */
    public List<AudioTimelineEntry> entries() {
        return List.copyOf(entries);
    }

    public long currentFrame() {
        return currentFrame;
    }

    public int nextOrder() {
        return nextOrder;
    }

    public void restoreCursor(long frame, int nextOrder) {
        if (nextOrder < 0) {
            throw new IllegalArgumentException("nextOrder must be non-negative");
        }
        this.currentFrame = frame;
        this.nextOrder = nextOrder;
    }

    public void clear() {
        entries.clear();
        prunedEntryCount = 0;
        currentFrame = 0;
        nextOrder = 0;
    }

    /**
     * Counts retained entries on {@code frame} by walking back from the tail;
     * the non-decreasing frame invariant means matches are contiguous there.
     */
    int entriesOnFrame(long frame) {
        int count = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            long entryFrame = entries.get(i).frame();
            if (entryFrame == frame) {
                count++;
            } else if (entryFrame < frame) {
                break;
            }
        }
        return count;
    }
}
