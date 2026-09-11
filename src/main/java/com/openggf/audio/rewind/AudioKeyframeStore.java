package com.openggf.audio.rewind;

import com.openggf.audio.AudioManager;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public final class AudioKeyframeStore {
    private final NavigableMap<Long, AudioLogicalSnapshot> keyframes = new TreeMap<>();

    public void capture(long frame, AudioManager audio) {
        Objects.requireNonNull(audio, "audio");
        keyframes.put(frame, audio.captureLogicalSnapshot());
    }

    public AudioLogicalSnapshot keyframeAtOrBefore(long frame) {
        Map.Entry<Long, AudioLogicalSnapshot> entry = keyframes.floorEntry(frame);
        return entry != null ? entry.getValue() : null;
    }

    public int replayTo(AudioManager audio, long targetFrame, AudioReplayReason reason) {
        Objects.requireNonNull(audio, "audio");
        Objects.requireNonNull(reason, "reason");
        Map.Entry<Long, AudioLogicalSnapshot> keyframe = keyframes.floorEntry(targetFrame);
        if (keyframe == null) {
            return 0;
        }

        AudioLogicalSnapshot snapshot = keyframe.getValue();
        int replayed = 0;
        audio.restoreLogicalSnapshot(snapshot);
        try (AudioReplayScope ignored = audio.beginRewindReplay(
                Math.toIntExact(keyframe.getKey()),
                Math.toIntExact(targetFrame),
                reason)) {
            AudioCommandTimeline timeline = audio.commandTimeline();
            // The firstRetainedEntryIndex clamp is unreachable while pruning
            // stays in lockstep (the timeline is pruned to the EARLIEST
            // retained keyframe's commandEntryCount, so every retained
            // snapshot's count is >= it); it is a skip-don't-crash safety net
            // should that coupling ever break.
            int start = Math.max(snapshot.commandEntryCount(), timeline.firstRetainedEntryIndex());
            int end = timeline.entryCount();
            for (int i = start; i < end; i++) {
                AudioTimelineEntry entry = timeline.entryAt(i);
                if (entry.frame() <= targetFrame) {
                    audio.replayTimelineCommand(entry.command());
                    replayed++;
                }
            }
        }
        return replayed;
    }

    public int replayToLogicalState(AudioManager audio, long targetFrame) {
        Objects.requireNonNull(audio, "audio");
        Map.Entry<Long, AudioLogicalSnapshot> keyframe = keyframes.floorEntry(targetFrame);
        if (keyframe == null) {
            return 0;
        }

        AudioLogicalSnapshot snapshot = keyframe.getValue();
        audio.restoreLogicalSnapshot(snapshot);
        int replayed = 0;
        AudioCommandTimeline timeline = audio.commandTimeline();
        // Same skip-don't-crash safety net as replayTo: unreachable under
        // lockstep pruning, diagnosable if the prune coupling breaks.
        int start = Math.max(snapshot.commandEntryCount(), timeline.firstRetainedEntryIndex());
        int end = timeline.entryCount();
        for (int i = start; i < end; i++) {
            AudioTimelineEntry entry = timeline.entryAt(i);
            if (entry.frame() <= targetFrame) {
                audio.replayTimelineCommandLogically(entry.command());
                replayed++;
            }
        }
        return replayed;
    }

    public void discardAfter(long frame) {
        keyframes.tailMap(frame, false).clear();
    }

    public void discardBefore(long frame) {
        keyframes.headMap(frame, false).clear();
    }

    /** Keep the audio base needed by a gameplay checkpoint between audio boundaries. */
    public void discardBeforeRetainingFloor(long frame) {
        Long floor = keyframes.floorKey(frame);
        if (floor != null) {
            keyframes.headMap(floor, false).clear();
        }
    }

    /** Earliest retained keyframe snapshot, or {@code null} when empty. */
    public AudioLogicalSnapshot earliestSnapshot() {
        Map.Entry<Long, AudioLogicalSnapshot> first = keyframes.firstEntry();
        return first != null ? first.getValue() : null;
    }

    public void clear() {
        keyframes.clear();
    }
}
