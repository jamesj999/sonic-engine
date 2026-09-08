package com.openggf.audio;

import com.openggf.audio.rewind.AudioKeyframeStore;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.rewind.AudioReplayReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestAudioKeyframeReplay {
    private AudioManager audio;
    private AudioTestFixtures.RecordingAudioBackend backend;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.resetState();
        backend = new AudioTestFixtures.RecordingAudioBackend();
        audio.setBackend(backend);
    }

    @AfterEach
    void tearDown() {
        audio.resetState();
    }

    @Test
    void replaysTimelineCommandsAfterNearestKeyframeWithoutRecordingDuplicates() {
        AudioKeyframeStore store = new AudioKeyframeStore();

        audio.beginCommandTimelineFrame(1);
        audio.playSfx("before");
        store.capture(1, audio);
        audio.beginCommandTimelineFrame(2);
        audio.playSfx("after");
        audio.beginCommandTimelineFrame(3);
        audio.fadeOutMusic(4, 1);
        int recordedEntries = audio.commandTimeline().entries().size();

        backend.clear();
        int replayed = store.replayTo(audio, 3, AudioReplayReason.SEEK);

        assertEquals(2, replayed);
        assertEquals(recordedEntries, audio.commandTimeline().entries().size());
        assertEquals("playSfxPitch:after:1.0", backend.calls.get(0));
        assertEquals("fadeOutMusic:4:1", backend.calls.get(1));
    }

    @Test
    void replaysCommandsAfterMidFrameKeyframeUsingCapturedEntryCount() {
        AudioKeyframeStore store = new AudioKeyframeStore();

        audio.beginCommandTimelineFrame(4);
        audio.playSfx("before");
        store.capture(4, audio);
        audio.playSfx("same-frame-after");

        backend.clear();
        int replayed = store.replayTo(audio, 4, AudioReplayReason.SEEK);

        assertEquals(1, replayed);
        assertEquals(2, audio.commandTimeline().entries().size());
        assertEquals("playSfxPitch:same-frame-after:1.0", backend.calls.get(0));
    }

    @Test
    void restoresNearestKeyframeBeforeReplayingCommands() {
        AudioKeyframeStore store = new AudioKeyframeStore();

        audio.beginCommandTimelineFrame(6);
        audio.playSfx(GameSound.RING);
        store.capture(6, audio);

        audio.beginCommandTimelineFrame(7);
        audio.resetRingSound();
        audio.playSfx("after");
        assertTrue(audio.captureLogicalSnapshot().ringLeft());

        backend.clear();
        int replayed = store.replayTo(audio, 7, AudioReplayReason.SEEK);

        assertEquals(2, replayed);
        assertTrue(audio.captureLogicalSnapshot().ringLeft(),
                "Replay should apply resetRingSound after restoring the keyframe state");
        assertEquals(6, audio.captureLogicalSnapshot().commandTimelineFrame());
        assertEquals("playSfxPitch:after:1.0", backend.calls.get(0));
    }

    @Test
    void discardAfterDropsFutureAudioKeyframes() {
        AudioKeyframeStore store = new AudioKeyframeStore();

        audio.beginCommandTimelineFrame(1);
        audio.playSfx(GameSound.RING);
        store.capture(1, audio);
        audio.beginCommandTimelineFrame(4);
        audio.resetRingSound();
        store.capture(4, audio);

        store.discardAfter(2);

        AudioLogicalSnapshot snapshot = store.keyframeAtOrBefore(4);
        assertNotNull(snapshot);
        assertEquals(1, snapshot.commandTimelineFrame());
    }
    @Test
    void pruningIntermediateGameplayCheckpointRetainsItsEarlierAudioBase() {
        AudioKeyframeStore store = new AudioKeyframeStore();
        for (int frame : new int[] {0, 60, 120}) {
            audio.beginCommandTimelineFrame(frame);
            store.capture(frame, audio);
        }
        store.discardBeforeRetainingFloor(70);
        assertNull(store.keyframeAtOrBefore(59));
        assertEquals(60, store.keyframeAtOrBefore(70).commandTimelineFrame());
        assertEquals(120, store.keyframeAtOrBefore(120).commandTimelineFrame());
        store.discardBeforeRetainingFloor(120);
        assertNull(store.keyframeAtOrBefore(119));
        assertEquals(120, store.earliestSnapshot().commandTimelineFrame());
    }

}
