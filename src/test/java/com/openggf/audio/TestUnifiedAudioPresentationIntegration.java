package com.openggf.audio;

import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.output.NoDeviceAudioSink;
import com.openggf.audio.presentation.AudioPresentationFrameView;
import com.openggf.audio.presentation.AudioPresentationProducer;
import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.presentation.PresentationVoiceSnapshot;
import com.openggf.audio.rewind.AudioPresentationPolicy;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end evidence for the unified audio presentation: real
 * {@link AudioManager} commands, the real presentation producer, and the real
 * final PCM that reaches the speaker sink and a live recording lease.
 *
 * <p>The claims here are deliberately made against <em>final PCM</em>, never
 * against a queued {@code AudioCommand}. Source presence is proved by
 * isolation: each source type is admitted alone through its production command
 * and asserted to make a fresh presentation packet non-zero, and the combined
 * case then proves all of them coexist in one packet.
 *
 * <p>The "speaker" in these tests is a real {@link AudioPresentationSink}
 * installed by the backend, so every speaker assertion reads the exact packet
 * the producer handed to the output device path — not a second capture lease
 * standing in for it.
 */
class TestUnifiedAudioPresentationIntegration {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_RATE = 60;
    private static final int PACKET_FRAMES = SAMPLE_RATE / FRAME_RATE;
    /** {@code AudioManager.REVERSE_RELEASE_CROSSFADE_MS} at 48 kHz. */
    private static final int CROSSFADE_FRAMES = SAMPLE_RATE * 45 / 1000;

    /**
     * Fallback-WAV music ids. These take the real {@code FALLBACK_WAV} route
     * and produce a durable looping sample voice, which is what the multi-frame
     * continuity claims below need: a music source that is still audible on the
     * tenth presented packet as well as the first.
     */
    private static final int TITLE_MUSIC = 0x8A;
    private static final int LEVEL_MUSIC = 0x81;
    private static final int SPECIAL_STAGE_MUSIC = 0x88;
    /** Music id resolved through the base SMPS route instead. */
    private static final int SMPS_MUSIC = 0x82;

    private static final int SEGA_PCM_ADDRESS = 0x10;
    private static final int SEGA_PCM_LENGTH = 24_000;
    private static final int SEGA_PCM_RATE = 8_000;

    private AudioManager audio;
    private RecordingSpeakerSink speaker;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        installFreshPresentation();
    }

    @AfterEach
    void tearDown() {
        audio.endCaptureMode();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    // ---------------------------------------------------------------
    // 1. Title and gameplay continuity across a recording toggle
    // ---------------------------------------------------------------

    @Test
    void titleAndGameplayPacketsRemainNonZeroBeforeDuringAndAfterCapture() {
        playLoopingMusic(TITLE_MUSIC);

        short[] beforeCapture = presentForward();
        assertAudible(beforeCapture, "title music before recording starts");

        LiveCaptureAudioHandle recording = audio.beginLiveCaptureAudio(
                audio.presentationFrameRate());

        short[] titleDuring = presentForward();
        assertAudible(titleDuring, "title music while recording");
        assertRecorderMatchesSpeaker(recording, titleDuring);

        playLoopingMusic(LEVEL_MUSIC);
        assertEquals(LEVEL_MUSIC,
                presentationSnapshot().activeMusic().musicId());
        short[] gameplayDuring = presentForward();
        assertAudible(gameplayDuring, "gameplay music while recording");
        assertRecorderMatchesSpeaker(recording, gameplayDuring);

        recording.close();

        short[] afterCapture = presentForward();
        assertAudible(afterCapture, "gameplay music after recording stops");
        assertEquals(LEVEL_MUSIC,
                presentationSnapshot().activeMusic().musicId(),
                "stopping recording must not reset the music slot");
    }

    // ---------------------------------------------------------------
    // 2. Special-stage rings and SFX stay audible and captured
    // ---------------------------------------------------------------

    @Test
    void specialStageRingAndSfxRemainInSpeakerAndCapturePcmDuringRecording() {
        playLoopingMusic(SPECIAL_STAGE_MUSIC);
        assertAudible(presentForward(), "special stage music");

        LiveCaptureAudioHandle recording = audio.beginLiveCaptureAudio(
                audio.presentationFrameRate());

        assertTrue(logicalSnapshotRingLeft(),
                "precondition: ring alternation starts on the left");
        audio.playSfx(GameSound.RING);
        audio.playSfx(GameSound.RING);
        audio.playSfx("JUMP");
        short[] withRings = presentForward();
        assertAudible(withRings, "special stage music plus rings and SFX");
        assertRecorderMatchesSpeaker(recording, withRings);
        assertTrue(logicalSnapshotRingLeft(),
                "two ring plays must return alternation to the left");
        assertEquals(2, sampleVoiceCount("sfx/ring.wav"),
                "both ring plays must own their own audible voice");
        assertEquals(1, sampleVoiceCount("sfx/jump.wav"));

        // Source attribution: with the music slot emptied the only remaining
        // audible sources are the ring/SFX voices, so a non-zero packet is
        // theirs alone rather than the special-stage music's.
        audio.stopMusic();
        audio.presentFrame(PresentationMode.SILENT);
        assertNull(presentationSnapshot().activeMusic(),
                "the music slot must be empty for the attribution step");
        assertEquals(3, presentationSnapshot().voices().size(),
                "only the two ring voices and the jump voice remain");

        short[] ringsOnly = presentForward();
        assertAudible(ringsOnly, "ring and SFX voices with no music playing");
        assertRecorderMatchesSpeaker(recording, ringsOnly);

        recording.close();

        audio.playSfx(GameSound.RING);
        short[] afterCapture = presentForward();
        assertAudible(afterCapture, "rings after recording stops");
    }

    // ---------------------------------------------------------------
    // 3. Every source family reaches the final packet
    // ---------------------------------------------------------------

    @Test
    void smpsWavPitchedSfxAndSegaPcmAllReachFinalPacket() {
        // Each family is proved alone, in its own presentation generation, so
        // "non-zero" cannot be borrowed from another source.
        playPrimedSmpsMusic(SMPS_MUSIC);
        assertAudible(presentForward(), "SMPS music alone");

        installFreshPresentation();
        audio.playSfx("JUMP", 1.0f);
        assertAudible(presentForward(), "fallback WAV SFX alone");

        installFreshPresentation();
        audio.playSfx("SKID", 2.0f);
        assertAudible(presentForward(), "pitched fallback WAV SFX alone");

        installFreshPresentation();
        audio.playSegaPcm();
        assertAudible(presentForward(), "raw SEGA PCM alone");

        // Combined: one registry, one mixer, one final packet.
        installFreshPresentation();
        LiveCaptureAudioHandle recording = audio.beginLiveCaptureAudio(
                audio.presentationFrameRate());
        audio.playMusic(SMPS_MUSIC);
        audio.playSfx("JUMP", 1.0f);
        audio.playSfx("SKID", 2.0f);
        audio.playSegaPcm();
        audio.presentFrame(PresentationMode.SILENT);
        assertNotNull(AudioManagerTestDiagnostics.primeAdmittedSmpsMusic(audio),
                "the SMPS music voice must be admitted before it can sound");

        AudioPresentationSnapshot admitted = presentationSnapshot();
        assertNotNull(admitted.activeMusic(), "SMPS music owns the music slot");
        assertTrue(hasSmpsVoice(admitted),
                "session-owned SMPS music is admitted");
        assertEquals(1, sampleVoiceCount("sfx/jump.wav"));
        assertEquals(1, sampleVoiceCount("sfx/skid.wav"));
        assertNotNull(admitted.rawPcmVoiceId(), "raw SEGA PCM is admitted");
        assertEquals(2 * sampleVoice("sfx/jump.wav").sourceStepQ32(),
                sampleVoice("sfx/skid.wav").sourceStepQ32(),
                "the pitched SFX advances its source twice as fast");

        short[] combined = presentForward();
        assertAudible(combined, "SMPS, WAV, pitched WAV and raw PCM together");
        assertRecorderMatchesSpeaker(recording, combined);
        recording.close();
    }

    // ---------------------------------------------------------------
    // 4. Toggling recording changes nothing the speaker can hear
    // ---------------------------------------------------------------

    @Test
    void togglePreservesEveryLogicalVoiceIdentityCursorAndSpeakerQueue() {
        playLoopingMusic(LEVEL_MUSIC);
        audio.playSfx("JUMP", 1.0f);
        audio.playSegaPcm();
        audio.setRewindHistoryArmed(true);
        presentForward();
        presentForward();

        AudioPresentationProducer.TransactionFingerprint before =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        Map<Long, Long> cursorsBefore = sampleCursors();
        long musicVoiceBefore = presentationSnapshot().activeMusic().voiceId();
        int speakerPacketsBefore = speaker.packets.size();
        assertFalse(cursorsBefore.isEmpty(),
                "precondition: sample-backed cursors exist to preserve");

        LiveCaptureAudioHandle recording = audio.beginLiveCaptureAudio(
                audio.presentationFrameRate());

        AudioPresentationProducer.TransactionFingerprint attached =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertEquals(before.voiceIdentities(), attached.voiceIdentities(),
                "attaching a recording lease must not rebind a voice");
        assertEquals(before.clock(), attached.clock(),
                "attaching a recording lease must not move the producer clock");
        assertEquals(before.history(), attached.history(),
                "attaching a recording lease must not move history");
        assertEquals(before.captureCount() + 1, attached.captureCount());
        assertEquals(cursorsBefore, sampleCursors(),
                "attaching a recording lease must not move a voice cursor");
        assertEquals(musicVoiceBefore,
                presentationSnapshot().activeMusic().voiceId());
        assertEquals(speakerPacketsBefore, speaker.packets.size(),
                "attaching a recording lease must not enqueue or consume a "
                        + "speaker packet");

        short[] whileRecording = presentForward();
        assertAudible(whileRecording, "audio during recording");
        assertRecorderMatchesSpeaker(recording, whileRecording);

        AudioPresentationProducer.TransactionFingerprint beforeDetach =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        Map<Long, Long> cursorsBeforeDetach = sampleCursors();
        int speakerPacketsBeforeDetach = speaker.packets.size();

        recording.close();

        AudioPresentationProducer.TransactionFingerprint detached =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertEquals(beforeDetach.voiceIdentities(), detached.voiceIdentities());
        assertEquals(beforeDetach.clock(), detached.clock(),
                "detaching must not flush or move the producer clock");
        assertEquals(beforeDetach.history(), detached.history());
        assertEquals(before.captureCount(), detached.captureCount());
        assertEquals(cursorsBeforeDetach, sampleCursors());
        assertEquals(speakerPacketsBeforeDetach, speaker.packets.size(),
                "detaching must not enqueue or consume a speaker packet");

        assertAudible(presentForward(), "audio after recording stops");
        assertEquals(speakerPacketsBeforeDetach + 1, speaker.packets.size(),
                "the speaker queue continues one packet per presented frame");
    }

    // ---------------------------------------------------------------
    // 5. Recording started during held rewind hears the reverse packet
    // ---------------------------------------------------------------

    @Test
    void startDuringHeldRewindImmediatelyMatchesSpeakerReversePcm() {
        audio.setRewindHistoryArmed(true);
        playLoopingMusic(LEVEL_MUSIC);
        for (int frame = 0; frame < 6; frame++) {
            assertAudible(presentForward(), "forward history frame " + frame);
        }

        audio.beginReverseAudioPresentation();
        AudioPresentationProducer.TransactionFingerprint beforeAttach =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertTrue(beforeAttach.reverseActive(),
                "precondition: held rewind is presenting reverse audio");

        LiveCaptureAudioHandle recording = audio.beginLiveCaptureAudio(
                audio.presentationFrameRate());

        AudioPresentationProducer.TransactionFingerprint attached =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertTrue(attached.reverseActive(),
                "attaching during held rewind must not end reverse playback");
        assertEquals(beforeAttach.reverseCursor(), attached.reverseCursor(),
                "attaching must not move the audible reverse cursor");
        assertEquals(beforeAttach.voiceIdentities(), attached.voiceIdentities());

        audio.presentFrame(PresentationMode.REVERSE);
        short[] reversePacket = speaker.last();
        assertEquals(PresentationMode.REVERSE, speaker.lastMode());
        assertAudible(reversePacket, "the first reverse packet after attaching");
        assertRecorderMatchesSpeaker(recording, reversePacket);

        recording.close();
    }

    // ---------------------------------------------------------------
    // 6. Pause / frame-step / modal frames submit fresh silence
    // ---------------------------------------------------------------

    @Test
    void pauseFrameStepAndModalSubmitFreshSilence() {
        playLoopingMusic(LEVEL_MUSIC);
        audio.setRewindHistoryArmed(true);
        assertAudible(presentForward(), "audible music before pausing");

        LiveCaptureAudioHandle recording = audio.beginLiveCaptureAudio(
                audio.presentationFrameRate());

        // Ordinary pause, paused frame-step and modal shader-picker frames all
        // resolve to the same SILENT producer mode in production
        // (GameLoop.presentationModeForOuterFrame), so all three are the same
        // assertion made three times over the real producer.
        for (int silentFrame = 0; silentFrame < 3; silentFrame++) {
            AudioPresentationProducer.TransactionFingerprint before =
                    AudioManagerTestDiagnostics.producerFingerprint(audio);
            Map<Long, Long> cursorsBefore = sampleCursors();

            audio.presentFrame(PresentationMode.SILENT);

            short[] silent = speaker.last();
            assertEquals(PresentationMode.SILENT, speaker.lastMode());
            assertEquals(PACKET_FRAMES * 2, silent.length,
                    "silence is still one clocked packet");
            assertSilent(silent, "paused presentation frame " + silentFrame);
            assertRecorderMatchesSpeaker(recording, silent);
            AudioPresentationProducer.TransactionFingerprint after =
                    AudioManagerTestDiagnostics.producerFingerprint(audio);
            assertEquals(before.history(), after.history(),
                    "a silent frame must not append history");
            assertEquals(cursorsBefore, sampleCursors(),
                    "a silent frame must not advance a voice cursor");
        }

        assertAudible(presentForward(), "audio resumes after pausing");
        recording.close();
    }

    // ---------------------------------------------------------------
    // 7. Rewind release applies the transient policy and one crossfade
    // ---------------------------------------------------------------

    @Test
    void releaseAppliesTransientPolicyAndOneCrossfade() {
        audio.setRewindHistoryArmed(true);
        playLoopingMusic(LEVEL_MUSIC);
        audio.playSfx("JUMP", 1.0f);
        for (int frame = 0; frame < 4; frame++) {
            presentForward();
        }
        assertEquals(1, sampleVoiceCount("sfx/jump.wav"),
                "precondition: a transient SFX voice is sounding");

        audio.beginReverseAudioPresentation();
        audio.presentFrame(PresentationMode.REVERSE);
        audio.presentFrame(PresentationMode.REVERSE);
        assertEquals(0, AudioManagerTestDiagnostics.producerFingerprint(audio)
                        .releaseCrossfadeRemaining(),
                "no crossfade is armed while rewind is still held");

        assertTrue(audio.afterRewindRestore(
                4, AudioPresentationPolicy.STOP_TRANSIENT_SFX));

        assertFalse(audio.isReverseAudioPresentationActive());
        assertEquals(0, sampleVoiceCount("sfx/jump.wav"),
                "the transient-SFX policy removes the restored one-shot SFX");
        assertNotNull(presentationSnapshot().activeMusic(),
                "durable music survives the transient-SFX policy");
        assertEquals(CROSSFADE_FRAMES,
                AudioManagerTestDiagnostics.producerFingerprint(audio)
                        .releaseCrossfadeRemaining(),
                "exactly one reverse-to-forward crossfade is armed");

        int expectedRemaining = CROSSFADE_FRAMES;
        while (expectedRemaining > 0) {
            presentForward();
            expectedRemaining = Math.max(0, expectedRemaining - PACKET_FRAMES);
            assertEquals(expectedRemaining,
                    AudioManagerTestDiagnostics.producerFingerprint(audio)
                            .releaseCrossfadeRemaining());
        }

        presentForward();
        assertEquals(0, AudioManagerTestDiagnostics.producerFingerprint(audio)
                        .releaseCrossfadeRemaining(),
                "the crossfade is applied once and never re-arms itself");
        assertTrue(audio.endReverseAudioPresentation(),
                "a redundant release is accepted");
        assertEquals(0, AudioManagerTestDiagnostics.producerFingerprint(audio)
                        .releaseCrossfadeRemaining(),
                "a release with no reverse output arms no second crossfade");
    }

    // ---------------------------------------------------------------
    // 8. Repeated recording leaks neither tap nor voice state
    // ---------------------------------------------------------------

    @Test
    void repeatedRecordingDoesNotLeakTapOrVoiceState() {
        playLoopingMusic(LEVEL_MUSIC);
        audio.playSfx("JUMP", 1.0f);
        presentForward();

        AudioPresentationProducer.TransactionFingerprint baseline =
                AudioManagerTestDiagnostics.producerFingerprint(audio);

        for (int session = 0; session < 4; session++) {
            LiveCaptureAudioHandle recording = audio.beginLiveCaptureAudio(
                    audio.presentationFrameRate());
            assertThrows(IllegalStateException.class,
                    () -> audio.beginLiveCaptureAudio(FRAME_RATE),
                    "session " + session
                            + ": a second simultaneous lease is refused");

            short[] packet = presentForward();
            assertAudible(packet, "recording session " + session);
            assertRecorderMatchesSpeaker(recording, packet);

            recording.close();
            recording.close();

            AudioPresentationProducer.TransactionFingerprint after =
                    AudioManagerTestDiagnostics.producerFingerprint(audio);
            assertEquals(baseline.captureCount(), after.captureCount(),
                    "session " + session + ": no lease is leaked");
            assertEquals(baseline.voiceIdentities(), after.voiceIdentities(),
                    "session " + session + ": no voice is rebound or dropped");
            assertThrows(IllegalStateException.class,
                    () -> recording.drainPresentationFrame(
                            new short[PACKET_FRAMES * 2]),
                    "session " + session
                            + ": a closed lease cannot drain any more");
        }

        assertEquals(1, sampleVoiceCount("sfx/jump.wav"),
                "four recording sessions must not duplicate or drop a voice");
        assertNotNull(presentationSnapshot().activeMusic());
    }

    // ---------------------------------------------------------------
    // 9. Losing the speaker device keeps everything else running
    // ---------------------------------------------------------------

    @Test
    void deviceFailurePreservesMixerHistoryRewindAndCapture() {
        audio.setRewindHistoryArmed(true);
        playLoopingMusic(LEVEL_MUSIC);
        audio.playSfx("JUMP", 1.0f);
        for (int frame = 0; frame < 3; frame++) {
            assertAudible(presentForward(), "forward frame " + frame);
        }
        LiveCaptureAudioHandle recording = audio.beginLiveCaptureAudio(
                audio.presentationFrameRate());
        audio.beginReverseAudioPresentation();
        audio.presentFrame(PresentationMode.REVERSE);
        short[] reverseBeforeFailure = new short[PACKET_FRAMES * 2];
        assertEquals(PACKET_FRAMES,
                recording.drainPresentationFrame(reverseBeforeFailure));
        assertAudible(reverseBeforeFailure, "reverse packet before the failure");

        AudioPresentationProducer.TransactionFingerprint before =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        Map<Long, Long> cursorsBefore = sampleCursors();

        audio.replaceFailedPresentationSink(
                new IllegalStateException("speaker device removed"));

        assertInstanceOf(NoDeviceAudioSink.class, presentationSink(),
                "a failed speaker is replaced by the silent no-device sink");
        assertTrue(speaker.closed, "the failed speaker sink is closed once");
        AudioPresentationProducer.TransactionFingerprint after =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertEquals(before, after,
                "losing the speaker must not disturb voices, history, the "
                        + "reverse cursor or the capture lease");
        assertEquals(cursorsBefore, sampleCursors());
        assertTrue(after.reverseActive(),
                "held rewind survives a speaker device failure");

        audio.presentFrame(PresentationMode.REVERSE);
        short[] reverseAfterFailure = new short[PACKET_FRAMES * 2];
        assertEquals(PACKET_FRAMES,
                recording.drainPresentationFrame(reverseAfterFailure));
        assertAudible(reverseAfterFailure,
                "recording keeps receiving reverse PCM with no speaker");

        assertTrue(audio.endReverseAudioPresentation());
        audio.presentFrame(PresentationMode.FORWARD);
        short[] forwardAfterFailure = new short[PACKET_FRAMES * 2];
        assertEquals(PACKET_FRAMES,
                recording.drainPresentationFrame(forwardAfterFailure));
        assertAudible(forwardAfterFailure,
                "the mixer keeps producing audible PCM with no speaker");
        recording.close();
    }

    // ---------------------------------------------------------------
    // 10. Ordinary engine exception: recorder first, then audio, in order
    // ---------------------------------------------------------------

    @Test
    void ordinaryEngineExceptionFinalizesRecorderAndCleansAudioInOrder() {
        CleanupOrderSink orderedSink = new CleanupOrderSink(audio);
        audio.setBackend(new SpeakerBackend(orderedSink));
        installProfileAndAssets();
        playLoopingMusic(LEVEL_MUSIC);
        audio.setRewindHistoryArmed(true);
        audio.presentFrame(PresentationMode.FORWARD);
        LiveCaptureAudioHandle recording = audio.beginLiveCaptureAudio(
                audio.presentationFrameRate());
        orderedSink.recording = recording;

        try {
            throw new IllegalStateException("ordinary engine failure");
        } catch (IllegalStateException expected) {
            audio.destroy();
        }

        assertTrue(orderedSink.closed, "the speaker sink is closed last");
        assertTrue(orderedSink.recordingClosedBeforeSink,
                "the recording tap is finalized before the speaker sink");
        assertTrue(orderedSink.registryEmptyBeforeSink,
                "the voice registry is torn down before the speaker sink");
        assertTrue(orderedSink.historyEmptyBeforeSink,
                "presentation history is released before the speaker sink");
        assertThrows(IllegalStateException.class,
                () -> recording.drainPresentationFrame(
                        new short[PACKET_FRAMES * 2]),
                "the recording lease does not outlive its producer");

        // The failure path is not terminal for the process: a fresh device can
        // be installed and a fresh recording started.
        installFreshPresentation();
        LiveCaptureAudioHandle restarted = audio.beginLiveCaptureAudio(
                audio.presentationFrameRate());
        playLoopingMusic(LEVEL_MUSIC);
        short[] packet = presentForward();
        assertAudible(packet, "audio after recovering from the failure");
        assertRecorderMatchesSpeaker(restarted, packet);
        restarted.close();
    }

    // ---------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------

    /**
     * Rebuilds the manager around a fresh recording speaker sink, profile, ROM
     * and decoded fallback assets. Used both by {@code @BeforeEach} and by the
     * source-isolation phases, which need a source to be the <em>only</em>
     * audible voice in its own presentation generation.
     */
    private void installFreshPresentation() {
        audio.endCaptureMode();
        audio.resetState();
        SonicConfigurationService.getInstance().resetToDefaults();
        speaker = new RecordingSpeakerSink(SAMPLE_RATE);
        audio.setBackend(new SpeakerBackend(speaker));
        installProfileAndAssets();
        assertEquals(FRAME_RATE, audio.presentationFrameRate());
        assertEquals(SAMPLE_RATE, audio.outputSampleRate());
    }

    private void installProfileAndAssets() {
        AudioTestFixtures.StubSmpsLoader loader =
                new AudioTestFixtures.StubSmpsLoader();
        loader.musicResults.put(SMPS_MUSIC, smpsData("smps", SMPS_MUSIC));
        Rom rom = mock(Rom.class);
        byte[] segaPcm = rampPcm(SEGA_PCM_LENGTH);
        try {
            when(rom.readBytes(SEGA_PCM_ADDRESS, SEGA_PCM_LENGTH))
                    .thenReturn(segaPcm);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        audio.setAudioProfile(new IntegrationAudioProfile(loader,
                new SegaPcmSpec(SEGA_PCM_ADDRESS, SEGA_PCM_LENGTH,
                        SEGA_PCM_RATE)));
        audio.setRom(rom);
        // Fallback WAV SFX have no packaged asset in the test classpath;
        // pre-decoding them is exactly what a shipped asset would have done,
        // and keeps the source-presence claims about real sample PCM.
        AudioManagerTestDiagnostics.registerFallbackSfxAsset(
                audio, "sfx/ring.wav", rampPcm(SAMPLE_RATE), SAMPLE_RATE);
        AudioManagerTestDiagnostics.registerFallbackSfxAsset(
                audio, "sfx/jump.wav", rampPcm(SAMPLE_RATE), SAMPLE_RATE);
        AudioManagerTestDiagnostics.registerFallbackSfxAsset(
                audio, "sfx/skid.wav", rampPcm(SAMPLE_RATE), SAMPLE_RATE);
        for (int musicId : new int[] {
                TITLE_MUSIC, LEVEL_MUSIC, SPECIAL_STAGE_MUSIC}) {
            AudioManagerTestDiagnostics.registerFallbackSfxAsset(
                    audio, fallbackMusicAsset(musicId),
                    rampPcm(SAMPLE_RATE), SAMPLE_RATE);
        }
    }

    private static String fallbackMusicAsset(int musicId) {
        return "music/" + Integer.toHexString(musicId).toUpperCase() + ".wav";
    }

    /**
     * Plays a durable looping fallback-WAV music voice through the production
     * command path and asserts the presentation admitted it.
     */
    private void playLoopingMusic(int musicId) {
        audio.playMusic(musicId);
        audio.presentFrame(PresentationMode.SILENT);
        assertNotNull(presentationSnapshot().activeMusic(),
                "the presentation must admit the fallback music voice for "
                        + Integer.toHexString(musicId));
        assertEquals(musicId, presentationSnapshot().activeMusic().musicId());
    }

    private void playPrimedSmpsMusic(int musicId) {
        audio.playMusic(musicId);
        // Admission is production work performed at the presentation boundary.
        // SILENT is the drain that admits without rendering, so the stub asset
        // is not swept as complete before its synthesis can be primed.
        audio.presentFrame(PresentationMode.SILENT);
        assertNotNull(AudioManagerTestDiagnostics.primeAdmittedSmpsMusic(audio),
                "the presentation must admit the SMPS music voice for "
                        + Integer.toHexString(musicId));
    }

    private short[] presentForward() {
        audio.presentFrame(PresentationMode.FORWARD);
        assertEquals(PresentationMode.FORWARD, speaker.lastMode());
        return speaker.last();
    }

    private void assertRecorderMatchesSpeaker(
            LiveCaptureAudioHandle recording, short[] speakerPacket) {
        short[] captured =
                new short[recording.maxStereoFramesPerPacket() * 2];
        int frames = recording.drainPresentationFrame(captured);
        assertEquals(speakerPacket.length / 2, frames,
                "speaker and recorder see the same clocked packet length");
        assertArrayEquals(speakerPacket, Arrays.copyOf(captured, frames * 2),
                "speaker and recorder are independent views of one packet");
    }

    private AudioPresentationSnapshot presentationSnapshot() {
        return audio.captureLogicalSnapshot().presentation();
    }

    private boolean logicalSnapshotRingLeft() {
        return audio.captureLogicalSnapshot().ringLeft();
    }

    private long sampleVoiceCount(String assetId) {
        return presentationSnapshot().voices().stream()
                .filter(PresentationVoiceSnapshot.Sample.class::isInstance)
                .map(PresentationVoiceSnapshot.Sample.class::cast)
                .filter(voice -> assetId.equals(voice.assetId()))
                .count();
    }

    private PresentationVoiceSnapshot.Sample sampleVoice(String assetId) {
        return presentationSnapshot().voices().stream()
                .filter(PresentationVoiceSnapshot.Sample.class::isInstance)
                .map(PresentationVoiceSnapshot.Sample.class::cast)
                .filter(voice -> assetId.equals(voice.assetId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no sample voice for " + assetId));
    }

    private Map<Long, Long> sampleCursors() {
        Map<Long, Long> cursors = new LinkedHashMap<>();
        for (PresentationVoiceSnapshot voice : presentationSnapshot().voices()) {
            if (voice instanceof PresentationVoiceSnapshot.Sample sample) {
                cursors.put(sample.voiceId(), sample.sourcePositionQ32());
            }
        }
        return cursors;
    }

    private static boolean hasSmpsVoice(AudioPresentationSnapshot snapshot) {
        return snapshot.smpsLogical() != null
                && snapshot.smpsLogical().sequencers().stream()
                .anyMatch(entry -> !entry.sfx());
    }

    private AudioPresentationSink presentationSink() {
        try {
            var field = AudioManager.class.getDeclaredField("presentationSink");
            field.setAccessible(true);
            return (AudioPresentationSink) field.get(audio);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertAudible(short[] packet, String what) {
        assertNotEquals(0, packet.length, what + ": packet must not be empty");
        for (short sample : packet) {
            if (sample != 0) {
                return;
            }
        }
        throw new AssertionError(what + ": final PCM was entirely silent");
    }

    private static void assertSilent(short[] packet, String what) {
        for (int index = 0; index < packet.length; index++) {
            assertEquals(0, packet[index],
                    what + ": sample " + index + " must be fresh silence");
        }
    }

    private static byte[] rampPcm(int length) {
        byte[] pcm = new byte[length];
        for (int index = 0; index < length; index++) {
            pcm[index] = (byte) (index % 251);
        }
        return pcm;
    }

    private static AbstractSmpsData smpsData(String name, int id) {
        AbstractSmpsData data = new AudioTestFixtures.StubSmpsData(name);
        data.setId(id);
        return data;
    }

    /** Backend whose only presentation surface is the speaker sink. */
    private static class SpeakerBackend extends NullAudioBackend {
        private final AudioPresentationSink sink;

        private SpeakerBackend(AudioPresentationSink sink) {
            this.sink = sink;
        }

        @Override
        public int outputSampleRate() {
            return sink.sampleRate();
        }

        @Override
        public AudioPresentationSink createPresentationSink(
                Consumer<Throwable> failureHandler,
                Consumer<String> warningHandler) {
            return sink;
        }
    }

    /** Speaker sink that records the exact final packets it is handed. */
    private static final class RecordingSpeakerSink
            implements AudioPresentationSink {
        private final int sampleRate;
        private final List<short[]> packets = new ArrayList<>();
        private final List<PresentationMode> modes = new ArrayList<>();
        private boolean closed;

        private RecordingSpeakerSink(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public void accept(AudioPresentationFrameView frame) {
            short[] copy = new short[frame.stereoFrames() * 2];
            frame.copyTo(copy, 0);
            packets.add(copy);
            modes.add(frame.mode());
        }

        @Override
        public void onReverseBoundary() {
        }

        @Override
        public void close() {
            closed = true;
        }

        private short[] last() {
            assertFalse(packets.isEmpty(), "no packet reached the speaker");
            return packets.get(packets.size() - 1);
        }

        private PresentationMode lastMode() {
            assertFalse(modes.isEmpty(), "no packet reached the speaker");
            return modes.get(modes.size() - 1);
        }
    }

    /**
     * Speaker sink that samples the recording tap, voice registry and history
     * at the moment the sink itself is closed, proving the shutdown order.
     */
    private static final class CleanupOrderSink
            implements AudioPresentationSink {
        private final AudioManager owner;
        private LiveCaptureAudioHandle recording;
        private boolean recordingClosedBeforeSink;
        private boolean registryEmptyBeforeSink;
        private boolean historyEmptyBeforeSink;
        private boolean closed;

        private CleanupOrderSink(AudioManager owner) {
            this.owner = owner;
        }

        @Override
        public int sampleRate() {
            return SAMPLE_RATE;
        }

        @Override
        public void accept(AudioPresentationFrameView frame) {
        }

        @Override
        public void onReverseBoundary() {
        }

        @Override
        public void close() {
            try {
                var producerField =
                        AudioManager.class.getDeclaredField("shadowProducer");
                producerField.setAccessible(true);
                Object producer = producerField.get(owner);
                var registryField = AudioPresentationProducer.class
                        .getDeclaredField("registry");
                registryField.setAccessible(true);
                var registry = (com.openggf.audio.presentation.AudioVoiceRegistry)
                        registryField.get(producer);
                var historyField = AudioPresentationProducer.class
                        .getDeclaredField("history");
                historyField.setAccessible(true);
                var history = (com.openggf.audio.runtime.PcmHistoryRing)
                        historyField.get(producer);
                recordingClosedBeforeSink = assertThrows(
                        IllegalStateException.class,
                        () -> recording.drainPresentationFrame(
                                new short[recording.maxStereoFramesPerPacket()
                                        * 2])) != null;
                registryEmptyBeforeSink = registry.orderedVoiceCount() == 0;
                historyEmptyBeforeSink =
                        history.diagnosticSnapshot().storedFrames() == 0;
                closed = true;
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private record IntegrationAudioProfile(SmpsLoader loader, SegaPcmSpec spec)
            implements GameAudioProfile {
        @Override public SmpsLoader createSmpsLoader(Rom rom) { return loader; }
        @Override public SmpsSequencerConfig getSequencerConfig() {
            return new SmpsSequencerConfig.Builder().build();
        }
        @Override public int getSpeedShoesOnCommandId() { return -1; }
        @Override public int getSpeedShoesOffCommandId() { return -1; }
        @Override public int getInvincibilityMusicId() { return -1; }
        @Override public int getExtraLifeMusicId() { return -1; }
        @Override public int getDrowningMusicId() { return -1; }
        @Override public Map<GameSound, Integer> getSoundMap() {
            return Map.of();
        }
        @Override public Map<GameMusic, Integer> getMusicMap() {
            return Map.of(GameMusic.SPECIAL_STAGE, SPECIAL_STAGE_MUSIC);
        }
        @Override public SegaPcmSpec getSegaPcmSpec() { return spec; }
        @Override public byte[] loadSegaPcm(Object rom) throws java.io.IOException {
            return com.openggf.game.audio.SegaPcmRomReader.read(rom, getSegaPcmSpec());
        }
    }
}
