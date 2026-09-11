package com.openggf.audio;

import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioCommandTimeline;
import com.openggf.audio.rewind.AudioReplayReason;
import com.openggf.audio.rewind.AudioReplayScope;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.game.rewind.InputSource;
import com.openggf.game.rewind.InMemoryKeyframeStore;
import com.openggf.game.rewind.RewindController;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.RewindRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.*;

class TestAudioCommandTimeline {
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
    void recordsCommandsWithFrameAndStableIntraFrameOrder() {
        audio.beginCommandTimelineFrame(12);

        audio.playSfx("JUMP", 0.75f);
        audio.fadeOutMusic(8, 2);
        audio.stopAllSfx();

        var entries = audio.commandTimeline().entries();
        assertEquals(3, entries.size());
        assertEquals(12, entries.get(0).frame());
        assertEquals(0, entries.get(0).order());
        assertEquals(1, entries.get(1).order());
        assertEquals(2, entries.get(2).order());
        assertEquals(new AudioCommand.PlaySfx(
                -1,
                "JUMP",
                AudioCommand.SfxRoute.FALLBACK_NAME,
                0.75f,
                null),
                entries.get(0).command());
        assertEquals(new AudioCommand.FadeOutMusic(8, 2), entries.get(1).command());
        assertEquals(new AudioCommand.StopAllSfx(), entries.get(2).command());
    }

    @Test
    void recordsResolvedDonorCommandsWithDonorGameAndId() {
        AudioTestFixtures.StubSmpsLoader donor = new AudioTestFixtures.StubSmpsLoader();
        donor.sfxResults.put(0xA4, new AudioTestFixtures.StubSmpsData("donor-sfx"));
        donor.musicResults.put(0x21, new AudioTestFixtures.StubSmpsData("donor-music"));
        audio.registerDonorLoader(
                "s3k", donor, AudioTestFixtures.EMPTY_DAC,
                new SmpsSequencerConfig.Builder().build());
        audio.registerDonorSound(GameSound.SPINDASH_CHARGE, "s3k", 0xA4);
        audio.beginCommandTimelineFrame(4);

        audio.playSfx(GameSound.SPINDASH_CHARGE, 1.25f);
        audio.playDonorMusic("s3k", 0x21);

        var entries = audio.commandTimeline().entries();
        assertEquals(new AudioCommand.PlaySfx(
                0xA4,
                "SPINDASH_CHARGE",
                AudioCommand.SfxRoute.DONOR_SMPS,
                1.25f,
                "s3k"),
                entries.get(0).command());
        // override=false: donor music replaces the foreground like any other
        // song. Only the 1-up jingle is saved and restored, and it is never a
        // donor track.
        assertEquals(new AudioCommand.PlayMusic(
                0x21,
                AudioCommand.MusicRoute.DONOR_SMPS,
                false,
                "s3k"),
                entries.get(1).command());
    }

    @Test
    void gameAudioProfileCanNormalizeGameSoundPitchBeforeRouting() {
        audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(new AudioTestFixtures.StubSmpsLoader()) {
            @Override
            public java.util.Map<GameSound, Integer> getSoundMap() {
                return java.util.Map.of(GameSound.SPINDASH_CHARGE, 0xAB);
            }

            @Override
            public float adjustSfxPitch(GameSound sound, float requestedPitch) {
                return sound == GameSound.SPINDASH_CHARGE ? 1.0f : requestedPitch;
            }
        });
        audio.setSoundMap(audio.getAudioProfile().getSoundMap());
        audio.beginCommandTimelineFrame(5);

        audio.playSfx(GameSound.SPINDASH_CHARGE, 1.25f);

        AudioCommand.PlaySfx command = (AudioCommand.PlaySfx) audio.commandTimeline().entries().get(0).command();
        assertEquals(1.0f, command.pitch());
    }

    @Test
    void recordsRingAlternationAsResolvedLeftRightCommands() {
        audio.setSoundMap(new EnumMap<>(GameSound.class));
        audio.beginCommandTimelineFrame(7);

        audio.playSfx(GameSound.RING);
        audio.playSfx(GameSound.RING);

        var entries = audio.commandTimeline().entries();
        assertEquals(2, entries.size());
        AudioCommand.PlaySfx first = (AudioCommand.PlaySfx) entries.get(0).command();
        AudioCommand.PlaySfx second = (AudioCommand.PlaySfx) entries.get(1).command();
        assertEquals("RING_LEFT", first.sfxName());
        assertEquals(AudioCommand.SfxRoute.RING_RESOLVED, first.route());
        assertEquals("RING_RIGHT", second.sfxName());
        assertEquals(AudioCommand.SfxRoute.RING_RESOLVED, second.route());
    }

    @Test
    void observesRawRingRequestOnceBeforeResolvedAlternationWithoutEnteringSnapshots() {
        AudioTestFixtures.StubSmpsLoader loader = new AudioTestFixtures.StubSmpsLoader();
        loader.sfxResults.put(0xCE, new AudioTestFixtures.StubSmpsData("left-ring"));
        audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(loader));
        audio.setRom(null);
        EnumMap<GameSound, Integer> map = new EnumMap<>(GameSound.class);
        map.put(GameSound.RING_RIGHT, 0xB5);
        map.put(GameSound.RING_LEFT, 0xCE);
        audio.setSoundMap(map);
        var beforeObserver = audio.captureLogicalSnapshot();
        java.util.List<String> observations = new java.util.ArrayList<>();

        audio.setRequestObserver((requestClass, rawId) ->
                observations.add(requestClass + ":" + Integer.toHexString(rawId)));
        assertEquals(beforeObserver, audio.captureLogicalSnapshot(),
                "the observational callback must not enter logical rewind state");
        audio.playSfx(GameSound.RING);

        assertEquals(java.util.List.of("SFX:b5"), observations);
        AudioCommand.PlaySfx resolved = (AudioCommand.PlaySfx) audio.commandTimeline().entryAt(0).command();
        assertEquals(0xCE, resolved.sfxId());
    }

    @Test
    void requestObserverFailureCreatesNoCommandAndDoesNotAdvanceRing() {
        EnumMap<GameSound, Integer> map = new EnumMap<>(GameSound.class);
        map.put(GameSound.RING_RIGHT, 0xB5);
        map.put(GameSound.RING_LEFT, 0xCE);
        audio.setSoundMap(map);
        audio.setRequestObserver((requestClass, rawId) -> {
            throw new IllegalStateException("request failed");
        });

        assertThrows(IllegalStateException.class,
                () -> audio.playSfx(GameSound.RING));
        assertTrue(audio.commandTimeline().entries().isEmpty());

        audio.setRequestObserver(null);
        audio.playSfx(GameSound.RING);
        AudioCommand.PlaySfx command = (AudioCommand.PlaySfx)
                audio.commandTimeline().entryAt(0).command();
        assertEquals("RING_LEFT", command.sfxName());
    }

    @Test
    void recordsSpeedShoesAsSemanticCommands() {
        audio.beginCommandTimelineFrame(9);

        audio.setSpeedShoes(true);
        audio.setSpeedMultiplier(2);

        var entries = audio.commandTimeline().entries();
        assertEquals(new AudioCommand.SetSpeedShoes(true), entries.get(0).command());
        assertEquals(new AudioCommand.SetSpeedMultiplier(2), entries.get(1).command());
    }

    @Test
    void discardAfterDropsFutureBranchCommands() {
        AudioCommandTimeline timeline = audio.commandTimeline();
        audio.beginCommandTimelineFrame(2);
        audio.playSfx("A");
        audio.beginCommandTimelineFrame(3);
        audio.playSfx("B");
        audio.beginCommandTimelineFrame(4);
        audio.playSfx("C");

        timeline.discardAfter(3);

        assertEquals(2, timeline.entries().size());
        assertEquals("A", ((AudioCommand.PlaySfx) timeline.entries().get(0).command()).sfxName());
        assertEquals("B", ((AudioCommand.PlaySfx) timeline.entries().get(1).command()).sfxName());
    }

    @Test
    void suppressedReplayDoesNotRecordTimelineCommands() {
        audio.beginCommandTimelineFrame(6);

        try (AudioReplayScope ignored = audio.beginRewindReplay(8, 6, AudioReplayReason.SEEK)) {
            audio.playSfx("SUPPRESSED");
        }

        assertTrue(audio.commandTimeline().entries().isEmpty());
    }

    @Test
    void rewindControllerDiscardsFutureAudioCommandsAfterBranchRestore() {
        audio.beginCommandTimelineFrame(1);
        audio.playSfx("one");
        audio.beginCommandTimelineFrame(2);
        audio.playSfx("two");
        audio.beginCommandTimelineFrame(3);
        audio.playSfx("three");

        RewindRegistry registry = new RewindRegistry();
        registry.register(new CounterSnap());
        RewindController controller = new RewindController(
                registry,
                new InMemoryKeyframeStore(),
                new FixedInputSource(10),
                input -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                1,
                audio);
        controller.step();
        controller.step();
        controller.step();

        controller.seekTo(1);

        assertEquals(1, audio.commandTimeline().entries().size());
        assertEquals("one", ((AudioCommand.PlaySfx)
                audio.commandTimeline().entries().get(0).command()).sfxName());
    }

    @Test
    void pruneHistoryBoundsCommandTimelineAndKeepsRetainedReplayValid() {
        RewindRegistry registry = new RewindRegistry();
        registry.register(new CounterSnap());
        RewindController controller = new RewindController(
                registry,
                new InMemoryKeyframeStore(),
                new FixedInputSource(100),
                input -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                10,
                audio);
        for (int i = 0; i < 35; i++) {
            controller.step();
            audio.playSfx("sfx-" + controller.currentFrame());
        }

        AudioCommandTimeline timeline = audio.commandTimeline();
        assertEquals(35, timeline.entryCount());
        assertEquals(0, timeline.firstRetainedEntryIndex());

        int earliest = controller.pruneHistoryToRetainFrames(12);

        assertEquals(20, earliest);
        // Entries before the earliest retained audio keyframe (frame 20,
        // captured with 19 commands recorded) are pruned; absolute indices
        // are preserved for the survivors.
        assertEquals(19, timeline.firstRetainedEntryIndex());
        assertEquals(35, timeline.entryCount());
        for (int i = timeline.firstRetainedEntryIndex(); i < timeline.entryCount(); i++) {
            assertEquals("sfx-" + timeline.entryAt(i).frame(),
                    ((AudioCommand.PlaySfx) timeline.entryAt(i).command()).sfxName());
        }

        // Rewinding to a retained frame after pruning still replays cleanly.
        controller.seekTo(25);
        assertEquals(25, controller.currentFrame());
        assertEquals("sfx-25", ((AudioCommand.PlaySfx)
                timeline.entryAt(timeline.entryCount() - 1).command()).sfxName());
    }

    private static final class CounterSnap implements RewindSnapshottable<Integer> {
        private int value;

        @Override
        public String key() {
            return "counter";
        }

        @Override
        public Integer capture() {
            return value;
        }

        @Override
        public void restore(Integer snapshot) {
            value = snapshot;
        }
    }

    private static final class FixedInputSource implements InputSource {
        private final int frames;

        FixedInputSource(int frames) {
            this.frames = frames;
        }

        @Override
        public int frameCount() {
            return frames;
        }

        @Override
        public com.openggf.debug.playback.Bk2FrameInput read(int frameIndex) {
            return new com.openggf.debug.playback.Bk2FrameInput(frameIndex, 0, 0, false, "");
        }
    }
}
