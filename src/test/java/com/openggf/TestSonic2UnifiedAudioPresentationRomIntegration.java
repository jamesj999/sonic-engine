package com.openggf;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioManagerTestDiagnostics;
import com.openggf.audio.GameMusic;
import com.openggf.audio.GameSound;
import com.openggf.audio.LiveCaptureAudioHandle;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROM-backed final-PCM parity for Sonic 2: the real module, the real ROM SMPS
 * loader, the real level-load audio wiring, and the real presentation producer.
 *
 * <p>Every claim here is made about the PCM a consumer actually receives, never
 * about a queued {@code AudioCommand}: each phase presents real outer frames
 * through the {@code HeadlessTestFixture}'s {@code HeadlessTestRunner}, which
 * performs exactly one {@code AudioManager.presentFrame} per stepped frame, and
 * requires a non-zero final packet before it passes.
 *
 * <p>Two independent producer leases stand in for the speaker and the recorder.
 * The headless no-device sink discards its packets, so a second producer lease
 * is the observable form of "what the speaker was handed"; this is the same
 * substitution {@code AudioManagerCaptureModeTest} uses, and the equality
 * assertion below is exactly the acceptance criterion that both consumers see
 * independent views of one producer-selected packet.
 *
 * <p>The special stage is entered through {@link
 * SpecialStageProvider#initializeStage(int)}, the same production seam
 * {@code TestS1SpecialStageHeadlessBoot} and
 * {@code TestS3kSpecialStageHeadlessBoot} use. {@code
 * TraceSessionLauncher.enterSpecialStageTrace(...)} is deliberately not used:
 * it needs a live {@code GameLoop}, and constructing one here would reintroduce
 * the {@code Engine.currentGameLoop()} static leak that aborted the JVM before
 * Task 13 fixed it.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestSonic2UnifiedAudioPresentationRomIntegration {

    private static final int ZONE = 0;
    private static final int ACT = 0;
    private static final int SPECIAL_STAGE_INDEX = 0;
    private static final int MAX_FRAMES_PER_PHASE = 180;

    private AudioManager audio;
    private LiveCaptureAudioHandle speaker;
    private LiveCaptureAudioHandle recording;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.resetState();
        SonicConfigurationService config =
                SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
    }

    @AfterEach
    void tearDown() {
        if (recording != null) {
            recording.close();
            recording = null;
        }
        if (speaker != null) {
            speaker.close();
            speaker = null;
        }
        audio.resetState();
        // This class rewrites shared configuration singleton values, so hand
        // the next class in this fork a clean baseline rather than its
        // predecessor's team/intro settings.
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void sonic2TitleGameplayRingAndSpecialStageProduceFinalPcm()
            throws Exception {
        assertInstanceOf(Sonic2GameModule.class, GameServices.module(),
                "the injected ROM must boot the real Sonic 2 module");

        // Gameplay. The production level-load path installs the ROM audio
        // profile, the ROM SMPS loader and the zone's music.
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE, ACT)
                .build();
        assertNotNull(audio.getAudioProfile(),
                "loading a level must install the ROM audio profile");
        assertNotNull(audio.captureLogicalSnapshot().presentation(),
                "the presentation producer must be realized after the load");

        speaker = AudioManagerTestDiagnostics.attachPresentationCapture(
                audio, audio.presentationFrameRate());
        recording = audio.beginLiveCaptureAudio(audio.presentationFrameRate());

        assertTrue(presentUntilAudible(fixture),
                "ROM gameplay music must reach the final presentation packet");

        // Title.
        audio.playMusic(Sonic2Music.TITLE.id);
        assertTrue(presentUntilAudibleAndActive(fixture, Sonic2Music.TITLE.id),
                "ROM title music must become the active track and reach the"
                        + " final presentation packet");

        // Rings, twice, so both sides of the ROM's left/right alternation run.
        // The music slot is emptied first so a non-zero packet in this phase is
        // attributable to the ring SFX alone rather than to the music that
        // would otherwise mask it.
        audio.stopMusic();
        assertTrue(presentUntilSilent(fixture),
                "stopping the music must actually silence the final packet");
        boolean ringLeftBefore = audio.captureLogicalSnapshot().ringLeft();
        audio.playSfx(GameSound.RING);
        assertTrue(presentUntilAudible(fixture),
                "the first ROM ring SFX must reach the final packet on its own");
        // Sonic 2's mailbox toggles the ring alternation at dispatch
        // (zPlaySound_CheckRing, s2.sounddriver.asm:2127-2135), not at
        // submit, so the flip is only observable once the request has
        // actually been serviced — after presenting, not right after
        // playSfx.
        assertEquals(!ringLeftBefore, audio.captureLogicalSnapshot().ringLeft(),
                "the first ring must consume one side of the alternation");

        audio.stopAllSfx();
        assertTrue(presentUntilSilent(fixture),
                "stopping SFX must actually silence the final packet");
        audio.playSfx(GameSound.RING);
        assertTrue(presentUntilAudible(fixture),
                "the second ROM ring SFX must reach the final packet on its own");
        assertEquals(ringLeftBefore, audio.captureLogicalSnapshot().ringLeft(),
                "the second ring must restore the alternation");

        // Special stage.
        audio.stopAllSfx();
        assertTrue(presentUntilSilent(fixture),
                "the special stage phase starts from silence");
        SpecialStageProvider specialStage =
                GameServices.module().getSpecialStageProvider();
        assertNotNull(specialStage, "Sonic 2 provides a special stage");
        specialStage.initializeStage(SPECIAL_STAGE_INDEX);
        assertTrue(specialStage.isInitialized(),
                "the special stage must initialize from ROM data");
        assertTrue(audio.playMusic(GameMusic.SPECIAL_STAGE),
                "the module must map GameMusic.SPECIAL_STAGE");
        assertTrue(presentUntilAudibleAndActive(fixture, Sonic2Music.SPECIAL_STAGE.id),
                "ROM special stage music must become the active track and"
                        + " reach the final packet");
    }

    /**
     * Steps real outer frames until the presentation producer's active music
     * slot is the requested track AND its presented packet carries non-zero
     * final PCM.
     *
     * <p>A Sonic 2 {@code playMusic} call for a track not already resident
     * enters the real mailbox ({@code Sonic2SoundRequestService}) and cannot
     * be dispatched until any in-flight Saxman decompression for the
     * previous or a concurrent track releases it ({@code
     * SmpsDriverSession#blocksForwardRequestConsumption}, modelling {@code
     * s2.sounddriver.asm}'s {@code zPlayMusic}/{@code zSaxmanDec}). Until
     * that dispatch happens, the producer keeps rendering whatever was
     * already playing, so a bare non-zero-packet check can pass on the
     * previous track and prove nothing about the one just requested. This
     * also asserts the recorder/speaker packet identity {@link
     * #presentUntilAudible} does, on every stepped frame.
     */
    private boolean presentUntilAudibleAndActive(
            HeadlessTestFixture fixture, int expectedMusicId) {
        boolean ready = false;
        short[] speakerPacket = new short[speaker.maxStereoFramesPerPacket() * 2];
        short[] recordedPacket =
                new short[recording.maxStereoFramesPerPacket() * 2];
        for (int frame = 0; frame < MAX_FRAMES_PER_PHASE && !ready; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            Arrays.fill(speakerPacket, (short) 0);
            Arrays.fill(recordedPacket, (short) 0);
            int speakerFrames = speaker.drainPresentationFrame(speakerPacket);
            int recordedFrames =
                    recording.drainPresentationFrame(recordedPacket);
            assertEquals(speakerFrames, recordedFrames,
                    "speaker and recorder are clocked to the same packet");
            assertArrayEquals(speakerPacket, recordedPacket,
                    "speaker and recorder must receive identical copies of one"
                            + " producer packet at frame " + frame);
            var activeMusic = audio.captureLogicalSnapshot().presentation().activeMusic();
            boolean isRequestedTrack =
                    activeMusic != null && activeMusic.musicId() == expectedMusicId;
            ready = isRequestedTrack && !allZero(speakerPacket);
        }
        return ready;
    }

    /**
     * Steps real outer frames until one presented packet carries non-zero final
     * PCM, asserting on every frame that the recorder and the speaker received
     * byte-identical copies of that frame's single producer packet.
     *
     * <p>Presentation is driven solely by the fixture's runner, which performs
     * exactly one {@code presentFrame} per stepped frame; this method never
     * presents a second time.
     */
    private boolean presentUntilAudible(HeadlessTestFixture fixture) {
        boolean audible = false;
        short[] speakerPacket = new short[speaker.maxStereoFramesPerPacket() * 2];
        short[] recordedPacket =
                new short[recording.maxStereoFramesPerPacket() * 2];
        for (int frame = 0; frame < MAX_FRAMES_PER_PHASE && !audible; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            Arrays.fill(speakerPacket, (short) 0);
            Arrays.fill(recordedPacket, (short) 0);
            int speakerFrames = speaker.drainPresentationFrame(speakerPacket);
            int recordedFrames =
                    recording.drainPresentationFrame(recordedPacket);
            assertEquals(speakerFrames, recordedFrames,
                    "speaker and recorder are clocked to the same packet");
            assertArrayEquals(speakerPacket, recordedPacket,
                    "speaker and recorder must receive identical copies of one"
                            + " producer packet at frame " + frame);
            audible = !allZero(speakerPacket);
        }
        return audible;
    }

    /**
     * Steps real outer frames until one presented packet is entirely silent,
     * so the next phase's non-zero packet is attributable to the source that
     * phase starts, not to a source left sounding by the previous one.
     */
    private boolean presentUntilSilent(HeadlessTestFixture fixture) {
        short[] speakerPacket = new short[speaker.maxStereoFramesPerPacket() * 2];
        short[] recordedPacket =
                new short[recording.maxStereoFramesPerPacket() * 2];
        for (int frame = 0; frame < MAX_FRAMES_PER_PHASE; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            Arrays.fill(speakerPacket, (short) 0);
            Arrays.fill(recordedPacket, (short) 0);
            int speakerFrames = speaker.drainPresentationFrame(speakerPacket);
            int recordedFrames =
                    recording.drainPresentationFrame(recordedPacket);
            assertEquals(speakerFrames, recordedFrames);
            assertArrayEquals(speakerPacket, recordedPacket,
                    "speaker and recorder must receive identical copies of one"
                            + " producer packet at frame " + frame);
            if (allZero(speakerPacket)) {
                return true;
            }
        }
        return false;
    }

    private static boolean allZero(short[] packet) {
        for (short sample : packet) {
            if (sample != 0) {
                return false;
            }
        }
        return true;
    }
}
