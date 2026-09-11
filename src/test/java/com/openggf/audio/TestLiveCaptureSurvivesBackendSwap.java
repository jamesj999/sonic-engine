package com.openggf.audio;

import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.presentation.AudioPresentationFrameView;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.capture.BackpressurePolicy;
import com.openggf.capture.CaptureEncoder;
import com.openggf.capture.CaptureRecorder;
import com.openggf.capture.CaptureViewport;
import com.openggf.capture.CapturedFrame;
import com.openggf.capture.LiveCaptureController;
import com.openggf.capture.VideoFrameGrabber;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Starting a recording on the master title screen and then entering a game must
 * not silently kill the recording's audio.
 *
 * <p>{@code Engine.exitMasterTitleScreen} does two things to the audio manager,
 * in this order: {@code resetForGameplayFromMasterTitle()} calls {@code
 * resetState()}, then {@code initializeGame() ->
 * initializeGlobalGameplayServices()} calls {@code setBackend(new
 * LWJGLAudioBackend(...))}. Both rebuild the presentation producer, and either
 * one retiring the live lease left the recorder draining a dead handle: {@code
 * LiveCaptureController} caught the {@code IllegalStateException}, logged one
 * warning for the whole recording and substituted {@link
 * ClockedSilenceAudioHandle} permanently. The recording did not fail — it just
 * had no audio from the moment the player started a game, which is worse.
 *
 * <p>The claims below are made against the <em>PCM the recorder is handed</em>,
 * driven by a real music source through a real producer, because a frame count
 * is identical whether audio flows or not. Each phase is driven to silence
 * first, so a later non-zero packet is attributable to the source admitted
 * after it.
 */
class TestLiveCaptureSurvivesBackendSwap {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_RATE = 60;
    private static final int TITLE_MUSIC = 0x8A;
    private static final int LEVEL_MUSIC = 0x81;
    private static final CaptureViewport VIEWPORT = new CaptureViewport(0, 0, 4, 4);

    private AudioManager audio;
    private RecordingSpeakerSink speaker;
    private RecordingEncoder encoder;
    private ExecutorService finalizer;
    private LiveCaptureController controller;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        resetManager();
        SonicConfigurationService.getInstance().resetToDefaults();
        installSpeaker(SAMPLE_RATE);
        installProfileAndAssets();
        assertEquals(FRAME_RATE, audio.presentationFrameRate());
        assertEquals(SAMPLE_RATE, audio.outputSampleRate());
    }

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.close();
        }
        if (finalizer != null) {
            finalizer.shutdownNow();
        }
        resetManager();
        audio.setBackend(new NullAudioBackend());
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    /**
     * {@code destroy()} first: it is the one teardown that retires a live lease,
     * so a test that left one attached cannot carry it into the next through
     * {@code resetState()}, which now treats a rebuild as a rebuild.
     */
    private void resetManager() {
        audio.endCaptureMode();
        audio.destroy();
        audio.resetState();
    }

    // ---------------------------------------------------------------
    // The reported bug, in production order
    // ---------------------------------------------------------------

    @Test
    void aRecordingStartedOnTheMasterTitleScreenHearsGameplayAudio() {
        playLoopingMusic(TITLE_MUSIC);
        startRecording();
        assertAudible(presentAndCapture(), "title music while recording");

        enterGameplayFromMasterTitle();

        // Nothing survives the transition's teardown, so this frame proves the
        // next one's audio belongs to the gameplay music and nothing else.
        assertNull(audio.captureLogicalSnapshot().presentation().activeMusic(),
                "the transition empties the music slot");
        assertSilent(presentAndCapture(), "the frame between the two sources");

        playLoopingMusic(LEVEL_MUSIC);
        short[] gameplay = presentAndCapture();

        assertAudible(gameplay, "gameplay music after entering a game");
        assertArrayEquals(speaker.last(), gameplay,
                "the recorder and the speaker are two views of one packet");
        assertNull(controller.lastFailure(),
                "the recording must not have degraded to clocked silence");
    }

    /**
     * The backend swap on its own — the narrower half of the transition, and
     * the only half the original carry covered.
     */
    @Test
    void aBackendSwapAloneKeepsTheRecordingAudible() {
        playLoopingMusic(TITLE_MUSIC);
        startRecording();
        assertAudible(presentAndCapture(), "music before the swap");

        // Installing a backend replaces the output device. That is not a reason
        // to stop recording what the engine is playing.
        installSpeaker(SAMPLE_RATE);
        installProfileAndAssets();
        playLoopingMusic(LEVEL_MUSIC);

        short[] afterSwap = presentAndCapture();
        assertAudible(afterSwap, "music after the backend swap");
        assertArrayEquals(speaker.last(), afterSwap,
                "the recorder and the speaker are two views of one packet");
        assertNull(controller.lastFailure());
    }

    /**
     * The capture clock must not restart, or the recorded audio would jump
     * relative to the video at the moment the player entered the game.
     */
    @Test
    void theCaptureClockContinuesAcrossTheTransition() {
        playLoopingMusic(TITLE_MUSIC);
        LiveCaptureAudioHandle recording =
                audio.beginLiveCaptureAudio(audio.presentationFrameRate());
        audio.presentFrame(PresentationMode.FORWARD);
        recording.drainPresentationFrame(new short[SAMPLE_RATE / FRAME_RATE * 2]);
        long framesBefore = recording.totalStereoFrames();

        enterGameplayFromMasterTitle();
        audio.presentFrame(PresentationMode.FORWARD);
        recording.drainPresentationFrame(new short[SAMPLE_RATE / FRAME_RATE * 2]);

        assertTrue(recording.totalStereoFrames() > framesBefore,
                "the clock must advance, not restart");
        assertEquals(framesBefore * 2, recording.totalStereoFrames(),
                "two equal frames either side of the transition");
        recording.close();
    }

    // ---------------------------------------------------------------
    // The two ways a lease legitimately ends
    // ---------------------------------------------------------------

    /**
     * A recording is muxed at the sample rate captured when it started (ffmpeg
     * {@code -ar}), so a producer rebuilt at a different rate cannot be
     * followed: continuing would write pitch-shifted audio, which looks like it
     * worked. Refusing is the honest answer, and it must be a refusal rather
     * than an exception — the rebind runs inside {@code
     * ensureShadowPresentation()}, which most of {@code AudioManager} reaches.
     */
    @Test
    void aSampleRateChangeRetiresTheLeaseInsteadOfThrowing() {
        playLoopingMusic(TITLE_MUSIC);
        LiveCaptureAudioHandle recording =
                audio.beginLiveCaptureAudio(audio.presentationFrameRate());

        installSpeaker(44_100);
        // Realizes the new producer through a path that does not swallow a
        // RuntimeException, so a throwing rebind fails here rather than being
        // logged away by the command mirror.
        assertEquals(FRAME_RATE, audio.presentationFrameRate());
        installProfileAndAssets();
        playLoopingMusic(LEVEL_MUSIC);
        audio.presentFrame(PresentationMode.FORWARD);

        assertThrows(IllegalStateException.class,
                () -> recording.drainPresentationFrame(new short[44_100 / FRAME_RATE * 2]),
                "a lease that cannot follow the producer is retired, not carried");
        // The refusal is confined to the recording: a fresh lease attaches.
        audio.beginLiveCaptureAudio(audio.presentationFrameRate()).close();
    }

    /** Tearing the engine down is a real close, not a rebuild. */
    @Test
    void destroyStillRetiresTheLease() {
        LiveCaptureAudioHandle recording =
                audio.beginLiveCaptureAudio(audio.presentationFrameRate());

        audio.destroy();

        assertThrows(IllegalStateException.class,
                () -> recording.drainPresentationFrame(new short[SAMPLE_RATE / FRAME_RATE * 2]),
                "a full teardown must genuinely retire the lease");
    }

    // ---------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------

    /** Exactly what {@code Engine.exitMasterTitleScreen} does to the audio. */
    private void enterGameplayFromMasterTitle() {
        audio.resetState();              // resetForGameplayFromMasterTitle()
        installSpeaker(SAMPLE_RATE);     // initializeGlobalGameplayServices()
        installProfileAndAssets();       // the selected game's ROM and profile
    }

    private void startRecording() {
        encoder = new RecordingEncoder();
        finalizer = Executors.newSingleThreadExecutor();
        controller = new LiveCaptureController(new LiveCaptureController.Dependencies(
                audio::beginLiveCaptureAudio,
                audio::outputSampleRate,
                StubGrabber::new,
                (viewport, fps) -> new CaptureRecorder(encoder, BackpressurePolicy.BLOCK, 8,
                        Path.of("target", "live-capture-swap"), "live", "fixed"),
                finalizer,
                Duration.ofSeconds(5)));
        controller.start(VIEWPORT, audio.presentationFrameRate());
        assertEquals(LiveCaptureController.State.ACTIVE, controller.state(),
                "the recording must be running before the transition");
    }

    /**
     * Presents one outer frame and captures it, in the order
     * {@code Engine.display()} uses, then returns the PCM the recorder was
     * handed for that frame.
     */
    private short[] presentAndCapture() {
        int framesBefore = encoder.frameCount();
        audio.presentFrame(PresentationMode.FORWARD);
        controller.capturePresentedFrame(VIEWPORT);
        return encoder.awaitPcmAfter(framesBefore);
    }

    private void installSpeaker(int sampleRate) {
        speaker = new RecordingSpeakerSink(sampleRate);
        audio.setBackend(new SpeakerBackend(speaker));
    }

    private void installProfileAndAssets() {
        audio.setAudioProfile(new SwapAudioProfile(
                new AudioTestFixtures.StubSmpsLoader()));
        audio.setRom(mock(Rom.class));
        // Fallback-WAV music ids take the real FALLBACK_WAV route and produce a
        // durable looping sample voice, which is what a claim about the tenth
        // presented packet needs as much as the first. There is no packaged
        // asset on the test classpath, so pre-decoding stands in for one.
        for (int musicId : new int[] {TITLE_MUSIC, LEVEL_MUSIC}) {
            AudioManagerTestDiagnostics.registerFallbackSfxAsset(audio,
                    "music/" + Integer.toHexString(musicId).toUpperCase() + ".wav",
                    rampPcm(SAMPLE_RATE), SAMPLE_RATE);
        }
    }

    private void playLoopingMusic(int musicId) {
        audio.playMusic(musicId);
        audio.presentFrame(PresentationMode.SILENT);
        assertNotNull(audio.captureLogicalSnapshot().presentation().activeMusic(),
                "the presentation must admit the fallback music voice for "
                        + Integer.toHexString(musicId));
    }

    private static void assertAudible(short[] pcm, String what) {
        assertTrue(pcm.length > 0, what + ": the recorder was handed no samples");
        for (short sample : pcm) {
            if (sample != 0) {
                return;
            }
        }
        throw new AssertionError(what + ": the recorded PCM was entirely silent");
    }

    private static void assertSilent(short[] pcm, String what) {
        for (int index = 0; index < pcm.length; index++) {
            assertEquals(0, pcm[index], what + ": sample " + index);
        }
    }

    private static byte[] rampPcm(int length) {
        byte[] pcm = new byte[length];
        for (int index = 0; index < length; index++) {
            pcm[index] = (byte) (index % 251);
        }
        return pcm;
    }

    /** Stands in for ffmpeg: keeps the PCM each submitted frame carried. */
    private static final class RecordingEncoder implements CaptureEncoder {
        private final List<short[]> pcm = new ArrayList<>();

        @Override public void open(Path output, int width, int height,
                                   int fps, int sampleRate) { }

        @Override public synchronized void encode(CapturedFrame frame) {
            pcm.add(Arrays.copyOf(frame.pcm(), frame.sampleCount() * 2));
            notifyAll();
        }

        @Override public Path finish() { return Path.of("recorded"); }

        @Override public void abort() { }

        private synchronized int frameCount() {
            return pcm.size();
        }

        /** Frames reach the encoder on its own thread; wait for this one. */
        private synchronized short[] awaitPcmAfter(int framesBefore) {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (pcm.size() <= framesBefore) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError(
                            "the recorder was never handed frame " + framesBefore);
                }
                try {
                    wait(Math.max(1, remaining / 1_000_000));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return pcm.get(framesBefore);
        }
    }

    private record StubGrabber(CaptureViewport viewport) implements VideoFrameGrabber {
        @Override public int width() { return viewport.width(); }
        @Override public int height() { return viewport.height(); }
        @Override public byte[] grab() { return new byte[viewport.rgbaByteSize()]; }
    }

    /** Backend whose only presentation surface is the speaker sink. */
    private static final class SpeakerBackend extends NullAudioBackend {
        private final AudioPresentationSink sink;

        private SpeakerBackend(AudioPresentationSink sink) {
            this.sink = sink;
        }

        @Override public int outputSampleRate() { return sink.sampleRate(); }

        @Override public AudioPresentationSink createPresentationSink(
                Consumer<Throwable> failureHandler, Consumer<String> warningHandler) {
            return sink;
        }
    }

    /** Speaker sink that keeps the exact final packets it is handed. */
    private static final class RecordingSpeakerSink implements AudioPresentationSink {
        private final int sampleRate;
        private final List<short[]> packets = new ArrayList<>();

        private RecordingSpeakerSink(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        @Override public int sampleRate() { return sampleRate; }

        @Override public void accept(AudioPresentationFrameView frame) {
            short[] copy = new short[frame.stereoFrames() * 2];
            frame.copyTo(copy, 0);
            packets.add(copy);
        }

        @Override public void onReverseBoundary() { }

        @Override public void close() { }

        private short[] last() {
            assertFalse(packets.isEmpty(), "no packet reached the speaker");
            return packets.get(packets.size() - 1);
        }
    }

    private record SwapAudioProfile(SmpsLoader loader) implements GameAudioProfile {
        @Override public SmpsLoader createSmpsLoader(Rom rom) { return loader; }
        @Override public SmpsSequencerConfig getSequencerConfig() {
            return new SmpsSequencerConfig.Builder().build();
        }
        @Override public int getSpeedShoesOnCommandId() { return -1; }
        @Override public int getSpeedShoesOffCommandId() { return -1; }
        @Override public int getInvincibilityMusicId() { return -1; }
        @Override public int getExtraLifeMusicId() { return -1; }
        @Override public int getDrowningMusicId() { return -1; }
        @Override public Map<GameSound, Integer> getSoundMap() { return Map.of(); }
        @Override public Map<GameMusic, Integer> getMusicMap() { return Map.of(); }
    }
}
