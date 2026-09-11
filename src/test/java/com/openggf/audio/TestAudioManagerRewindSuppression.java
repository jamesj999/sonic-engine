package com.openggf.audio;

import com.openggf.audio.rewind.AudioPresentationPolicy;
import com.openggf.audio.rewind.AudioReplayReason;
import com.openggf.audio.rewind.AudioReplayScope;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;

import static org.junit.jupiter.api.Assertions.*;

class TestAudioManagerRewindSuppression {
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
    void suppressesPlaybackCommandsInsideReplayScope() {
        AudioTestFixtures.StubSmpsLoader loader = new AudioTestFixtures.StubSmpsLoader();
        loader.musicResults.put(1, new AudioTestFixtures.StubSmpsData("music"));
        loader.sfxResults.put(2, new AudioTestFixtures.StubSmpsData("sfx"));
        loader.namedSfxResults.put("JUMP", new AudioTestFixtures.StubSmpsData("jump"));
        audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(
                loader, 0xF0, 0xF1, GameAudioProfile.SpeedMode.FRAME_MULTIPLY));
        audio.setRom(new Rom());
        audio.setSoundMap(new EnumMap<>(GameSound.class));

        try (AudioReplayScope ignored = audio.beginRewindReplay(10, 4, AudioReplayReason.SEEK)) {
            audio.playMusic(1);
            audio.playMusic(0xF0);
            audio.playMusic(0xF1);
            audio.playSfx("JUMP");
            audio.playSfx(GameSound.JUMP);
            audio.playSfx(2);
            audio.playDonorSfx("s3k", 7);
            audio.playDonorMusic("s3k", 9);
            audio.fadeOutMusic();
            audio.fadeOutMusic(8, 2);
            audio.stopAllSfx();
            audio.stopMusic();
            audio.endMusicOverride(5);
            audio.changeMusicTempo(6);
            audio.restoreMusic();
            audio.setSpeedShoes(false);
            audio.setSpeedMultiplier(1);
        }

        assertEquals(0, backend.totalCalls(), "suppressed replay must not dispatch live backend commands");
    }

    @Test
    void suppressionScopeNestsUntilOuterScopeCloses() {
        AudioReplayScope outer = audio.beginRewindReplay(8, 4, AudioReplayReason.STEP_BACKWARD);
        AudioReplayScope inner = audio.beginRewindReplay(4, 3, AudioReplayReason.SEGMENT_EXPANSION);

        assertTrue(audio.isRewindReplaySuppressed());
        inner.close();
        assertTrue(audio.isRewindReplaySuppressed(), "inner close must not disable outer suppression");
        audio.playSfx("INNER_CLOSED");
        assertEquals(0, backend.totalCalls());

        outer.close();
        assertFalse(audio.isRewindReplaySuppressed());
        audio.playSfx("AUDIBLE");
        assertEquals(1, audio.commandTimeline().entryCount());
    }

    @Test
    void replayScopeCloseIsIdempotent() {
        AudioReplayScope scope = audio.beginRewindReplay(5, 2, AudioReplayReason.SEEK);
        scope.close();
        scope.close();

        assertFalse(audio.isRewindReplaySuppressed());
        audio.playSfx("AUDIBLE");
        assertEquals(1, audio.commandTimeline().entryCount());
    }

    @Test
    void suppressedRingSoundDoesNotAdvanceRingAlternation() {
        audio.setSoundMap(new EnumMap<>(GameSound.class));

        try (AudioReplayScope ignored = audio.beginRewindReplay(3, 1, AudioReplayReason.SEEK)) {
            audio.playSfx(GameSound.RING);
        }

        audio.playSfx(GameSound.RING);

        assertEquals(1, audio.commandTimeline().entryCount());
        assertEquals("RING_LEFT",
                ((AudioCommand.PlaySfx) audio.commandTimeline().entryAt(0).command()).sfxName(),
                "first audible ring after suppressed replay must still be left");
    }

    @Test
    void suppressionDoesNotBlockSetupState() {
        AudioTestFixtures.StubSmpsLoader loader = new AudioTestFixtures.StubSmpsLoader();
        loader.sfxResults.put(0x90, new AudioTestFixtures.StubSmpsData("jump"));

        try (AudioReplayScope ignored = audio.beginRewindReplay(3, 1, AudioReplayReason.SEEK)) {
            audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(loader));
            audio.setRom(new Rom());
            EnumMap<GameSound, Integer> map = new EnumMap<>(GameSound.class);
            map.put(GameSound.JUMP, 0x90);
            audio.setSoundMap(map);
        }

        audio.playSfx(GameSound.JUMP);

        assertEquals(1, audio.commandTimeline().entryCount());
        assertEquals(AudioCommand.SfxRoute.BASE_SMPS_ID,
                ((AudioCommand.PlaySfx) audio.commandTimeline().entryAt(0).command()).route());
    }

    @Test
    void afterRestorePoliciesUseConservativePresentationCleanup() {
        audio.afterRewindRestore(7, AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);
        assertEquals(0, backend.totalCalls());

        audio.afterRewindRestore(7, AudioPresentationPolicy.STOP_TRANSIENT_SFX_RESYNC_MUSIC);
        assertEquals(java.util.List.of(), backend.calls);

        audio.afterRewindRestore(7, AudioPresentationPolicy.STOP_ALL_PRESENTATION);
        assertEquals(java.util.List.of(), backend.calls,
                "cleanup must remain on the sole presentation producer");
    }

    @Test
    void stopTransientSfxPolicyDoesNotForcePopAnAlreadyCorrectlyRestoredOverride() {
        // Unlike STOP_TRANSIENT_SFX_RESYNC_MUSIC, this policy must not call
        // restoreMusic(): its callers already landed a committed logical
        // restore (via commitDeferredAudioRestore()) that rebuilt the correct
        // override state for the committed frame, so an unconditional
        // restoreMusic() here would end an override (e.g. invincibility) that
        // is legitimately still active per that just-restored state.
        audio.afterRewindRestore(7, AudioPresentationPolicy.STOP_TRANSIENT_SFX);
        assertEquals(java.util.List.of(), backend.calls);
    }

    @Test
    void silentFrameStepDoesNotPollThePresentationBackend() {
        audio.presentFrame(PresentationMode.SILENT);

        assertEquals(java.util.List.of(), backend.calls);
    }

    @Test
    void afterRestoreEndsReversePresentationBeforeCleanupPolicy() {
        audio.beginReverseAudioPresentation();
        audio.afterRewindRestore(7, AudioPresentationPolicy.SUPPRESSED_INTERNAL_RESTORE);

        assertTrue(audio.isReverseAudioPresentationActive(),
                "internal step-back restores must not cancel held reverse presentation");
        assertTrue(AudioManagerTestDiagnostics.producerFingerprint(audio)
                .reverseActive());

        audio.afterRewindRestore(7, AudioPresentationPolicy.STOP_ALL_PRESENTATION);

        assertFalse(audio.isReverseAudioPresentationActive());
        assertFalse(AudioManagerTestDiagnostics.producerFingerprint(audio)
                .reverseActive());
        assertEquals(java.util.List.of(), backend.calls);
    }

    @Test
    void updateDuringReversePresentationDoesNotRenderNormalFrame() {
        audio.beginReverseAudioPresentation();
        var before = AudioManagerTestDiagnostics.producerFingerprint(audio);

        audio.update();

        var after = AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertEquals(before.clock(), after.clock(),
                "the device pump must not present a frame");
        assertEquals(before.history(), after.history(),
                "reverse presentation must drain history only, not append"
                        + " forward PCM into history");
        assertEquals(java.util.List.of(), backend.calls);
    }

    @Test
    void logicalRestoreIsDeferredUntilReverseReleaseAndReachesOnlyTheProducer() {
        AudioLogicalSnapshot snapshot = audio.captureLogicalSnapshot();

        audio.beginReverseAudioPresentation();
        audio.restoreLogicalSnapshot(snapshot);

        assertEquals(java.util.List.of(), backend.calls,
                "a held-reverse restore must never touch the backend");
        assertNotNull(audio.deferredReverseLogicalSnapshotForTesting(),
                "the restore target is deferred until reverse release");

        assertTrue(audio.endReverseAudioPresentation());
        assertEquals(java.util.List.of(), backend.calls);
    }

    @Test
    void reversePresentationLifecycleIsOwnedOnlyByProducer() {
        audio.beginReverseAudioPresentation();
        assertTrue(AudioManagerTestDiagnostics.producerFingerprint(audio)
                .reverseActive());

        audio.endReverseAudioPresentation();

        assertFalse(AudioManagerTestDiagnostics.producerFingerprint(audio)
                .reverseActive());
        assertEquals(java.util.List.of(), backend.calls,
                "reverse presentation is producer-owned; the backend sees"
                        + " nothing");
    }
}
