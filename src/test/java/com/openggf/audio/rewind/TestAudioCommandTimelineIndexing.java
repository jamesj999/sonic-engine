package com.openggf.audio.rewind;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Indexing and pruning contract for {@link AudioCommandTimeline}: absolute
 * entry indices survive pruning, frame-local operations work off the tail,
 * and the count accessor does not require materializing a copied list.
 */
class TestAudioCommandTimelineIndexing {

    private static AudioCommand command(int id) {
        return new AudioCommand.SetSpeedMultiplier(id);
    }

    private static AudioCommandTimeline timelineWithFrames(long... frames) {
        AudioCommandTimeline timeline = new AudioCommandTimeline();
        for (int i = 0; i < frames.length; i++) {
            timeline.beginFrame(frames[i]);
            timeline.record(command(i));
        }
        return timeline;
    }

    @Test
    void entryCountMatchesRecordedEntriesWithoutCopyingList() {
        AudioCommandTimeline timeline = new AudioCommandTimeline();
        assertEquals(0, timeline.entryCount());

        timeline.beginFrame(1);
        timeline.record(command(0));
        timeline.record(command(1));
        timeline.beginFrame(2);
        timeline.record(command(2));

        assertEquals(3, timeline.entryCount());
        // entryCount() is the absolute total; the copied entries() view is
        // only equal while nothing has been pruned.
        assertEquals(timeline.entries().size(), timeline.entryCount());
        assertEquals(0, timeline.firstRetainedEntryIndex());
    }

    @Test
    void entriesOnFrameWalksFromTheTailAndCountsTheMatchingRun() {
        AudioCommandTimeline timeline = timelineWithFrames(1, 1, 2, 3, 3);

        // Non-decreasing append order keeps same-frame entries contiguous, so
        // the tail walk stops at the first older frame instead of scanning
        // the whole list, while counts match the historical full scan.
        assertEquals(2, timeline.entriesOnFrame(3));
        assertEquals(1, timeline.entriesOnFrame(2));
        assertEquals(2, timeline.entriesOnFrame(1));
        assertEquals(0, timeline.entriesOnFrame(9));
    }

    @Test
    void beginFrameResumesIntraFrameOrderFromTailEntries() {
        AudioCommandTimeline timeline = timelineWithFrames(4, 5, 5);

        // Re-entering the current tail frame resumes after its two entries.
        timeline.beginFrame(4);
        timeline.beginFrame(5);
        AudioTimelineEntry next = timeline.record(command(99));

        assertEquals(5, next.frame());
        assertEquals(2, next.order());
    }

    @Test
    void discardAfterTruncatesFromTheTail() {
        AudioCommandTimeline timeline = timelineWithFrames(1, 2, 3, 4, 5);

        timeline.discardAfter(3);

        assertEquals(3, timeline.entryCount());
        List<AudioTimelineEntry> retained = timeline.entries();
        assertEquals(3, retained.size());
        assertEquals(3, retained.get(retained.size() - 1).frame());
        assertEquals(3, timeline.currentFrame());
        assertEquals(1, timeline.nextOrder());
    }

    @Test
    void pruneBeforeKeepsAbsoluteIndicesStable() {
        AudioCommandTimeline timeline = timelineWithFrames(1, 2, 3, 4, 5);
        AudioTimelineEntry third = timeline.entryAt(2);

        timeline.pruneBefore(2);

        assertEquals(2, timeline.firstRetainedEntryIndex());
        assertEquals(5, timeline.entryCount());
        assertEquals(third, timeline.entryAt(2));
        assertEquals(5, timeline.entryAt(4).frame());
        assertThrows(IndexOutOfBoundsException.class, () -> timeline.entryAt(1));
        assertEquals(3, timeline.entries().size());
    }

    @Test
    void pruneBeforeIsIdempotentAndNeverMovesBackward() {
        AudioCommandTimeline timeline = timelineWithFrames(1, 2, 3, 4);

        timeline.pruneBefore(3);
        timeline.pruneBefore(1);

        assertEquals(3, timeline.firstRetainedEntryIndex());
        assertEquals(4, timeline.entryCount());

        // Pruning beyond the recorded range clamps to entryCount.
        timeline.pruneBefore(99);
        assertEquals(4, timeline.firstRetainedEntryIndex());
        assertEquals(4, timeline.entryCount());
        assertTrue(timeline.entries().isEmpty());
    }

    @Test
    void recordingAfterPruneContinuesAbsoluteIndexing() {
        AudioCommandTimeline timeline = timelineWithFrames(1, 2, 3);
        timeline.pruneBefore(2);

        timeline.beginFrame(4);
        AudioTimelineEntry appended = timeline.record(command(42));

        assertEquals(4, timeline.entryCount());
        assertEquals(appended, timeline.entryAt(3));
        assertEquals(command(42), timeline.entryAt(3).command());
    }

    @Test
    void pruningByEarliestRetainedKeyframeKeepsReplayRangesValid() {
        AudioCommandTimeline timeline = new AudioCommandTimeline();
        // Frames 1..6, one command per frame; keyframe snapshots record the
        // absolute entry count at capture time, exactly like
        // AudioManager.captureLogicalSnapshot.
        int[] keyframeEntryCounts = new int[7];
        for (int frame = 1; frame <= 6; frame++) {
            timeline.beginFrame(frame);
            timeline.record(command(frame));
            keyframeEntryCounts[frame] = timeline.entryCount();
        }

        // Retention drops keyframes below frame 4; the earliest retained
        // keyframe's entry count keys the prune.
        int earliestRetainedKeyframeEntryCount = keyframeEntryCounts[4];
        timeline.pruneBefore(earliestRetainedKeyframeEntryCount);

        assertEquals(4, timeline.firstRetainedEntryIndex());
        assertEquals(6, timeline.entryCount());
        // Replay from the earliest retained keyframe (frame 4) still resolves:
        // entries after its commandEntryCount are exactly frames 5..6.
        for (int i = keyframeEntryCounts[4]; i < timeline.entryCount(); i++) {
            AudioTimelineEntry entry = timeline.entryAt(i);
            assertTrue(entry.frame() > 4);
            assertEquals(command((int) entry.frame()), entry.command());
        }
        // A later retained keyframe's range stays valid too.
        assertEquals(6, timeline.entryAt(keyframeEntryCounts[5]).frame());
    }

    @Test
    void clearResetsPruneBase() {
        AudioCommandTimeline timeline = timelineWithFrames(1, 2, 3);
        timeline.pruneBefore(2);

        timeline.clear();

        assertEquals(0, timeline.entryCount());
        assertEquals(0, timeline.firstRetainedEntryIndex());
        timeline.beginFrame(1);
        timeline.record(command(7));
        assertEquals(command(7), timeline.entryAt(0).command());
    }
}
