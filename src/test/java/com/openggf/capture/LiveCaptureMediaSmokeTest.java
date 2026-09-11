package com.openggf.capture;

import com.openggf.tests.TestTempFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioManagerTestDiagnostics;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.DebugFailAfterFramesAudioHandle;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.GameMusic;
import com.openggf.audio.GameSound;
import com.openggf.audio.LiveCaptureAudioHandle;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.SegaPcmSpec;
import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.output.NoDeviceAudioSink;
import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.presentation.PresentationVoiceSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveCaptureMediaSmokeTest {
    private static final int WIDTH = 18;
    private static final int HEIGHT = 10;
    private static final int FRAME_RATE = 30;
    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_COUNT = 6;
    private static final int STEREO_FRAMES_PER_VIDEO_FRAME = SAMPLE_RATE / FRAME_RATE;

    /**
     * Frames recorded by the mixed-source media fixture.
     *
     * <p>Matroska timestamps are millisecond-quantized, so the probed audio
     * duration is rounded to whole milliseconds while the video side is exact.
     * The A/V tolerance is one sample (1/48000 s = 20.8 us), so this count must
     * make {@code frames / presentationFrameRate} a whole number of
     * milliseconds. 12 frames at 60 fps is 0.200 s exactly.
     */
    private static final int MIXED_FRAME_COUNT = 12;
    /**
     * Frames recorded by the mid-session tap-failure media fixture. Same
     * whole-millisecond constraint as {@link #MIXED_FRAME_COUNT}: 24 frames at
     * 60 fps is 0.400 s exactly.
     */
    private static final int FAILURE_FRAME_COUNT = 24;
    /**
     * Task 11's development-only injection hook. {@code N} means the tap fails
     * before drain {@code N + 1}, after exactly {@code N} successful drains, so
     * video frames {@code 0..N-1} carry real PCM and {@code N..} carry clocked
     * silence.
     */
    private static final String AUDIO_FAIL_PROPERTY =
            "openggf.debug.liveCaptureAudioFailAfterFrames";
    private static final int TAP_FAIL_AFTER_FRAMES = 17;

    private static final int SMPS_MUSIC = 0x82;
    private static final int SEGA_PCM_ADDRESS = 0x10;
    private static final int SEGA_PCM_LENGTH = 24_000;
    private static final int SEGA_PCM_RATE = 8_000;

    private AudioManager audio;

    @AfterEach
    void tearDownPresentation() {
        if (audio != null) {
            audio.endCaptureMode();
            audio.resetState();
            audio.setBackend(new NullAudioBackend());
            audio = null;
        }
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void liveRecorderProducesLosslessViewportVideoAndExactPresentationAudio() throws Exception {
        Optional<Path> ffmpeg = FfmpegEncoder.findFfmpeg();
        Optional<Path> ffprobe = findFfprobe();
        Assumptions.assumeTrue(ffmpeg.isPresent() && ffprobe.isPresent(),
                "ffmpeg and ffprobe must both be on PATH");

        Path directory = TestTempFiles.createTempDirectory("live-capture-media-smoke-");
        CaptureRecorder recorder = new CaptureRecorder(
                new FfmpegEncoder(ffmpeg.orElseThrow().toString(), 1),
                BackpressurePolicy.BLOCK, 8, directory, "live", "smoke");
        List<byte[]> submittedRgba = new ArrayList<>();
        short[] expectedPcm = presentationPcm();

        try {
            recorder.start(WIDTH, HEIGHT, FRAME_RATE, SAMPLE_RATE);
            for (int frame = 0; frame < FRAME_COUNT; frame++) {
                byte[] rgba = viewportFrame(frame);
                submittedRgba.add(rgba);
                short[] packet = Arrays.copyOfRange(expectedPcm,
                        frame * STEREO_FRAMES_PER_VIDEO_FRAME * 2,
                        (frame + 1) * STEREO_FRAMES_PER_VIDEO_FRAME * 2);
                recorder.submit(new CapturedFrame(rgba, WIDTH, HEIGHT, packet,
                        STEREO_FRAMES_PER_VIDEO_FRAME, frame));
            }
            Path output = recorder.stop();

            JsonNode probe = new ObjectMapper().readTree(run(ffprobe.orElseThrow().toString(),
                    "-v", "error", "-count_frames", "-show_streams", "-show_format",
                    "-of", "json", output.toString()));
            JsonNode video = stream(probe, "video");
            JsonNode audio = stream(probe, "audio");
            assertEquals("ffv1", video.path("codec_name").asText());
            assertEquals("flac", audio.path("codec_name").asText());
            assertEquals(2, audio.path("channels").asInt());
            assertEquals(WIDTH, video.path("width").asInt());
            assertEquals(HEIGHT, video.path("height").asInt());
            assertEquals(FRAME_COUNT, video.path("nb_read_frames").asInt());
            double expectedDuration = FRAME_COUNT / (double) FRAME_RATE;
            double probedAudioDuration = mediaDurationSeconds(audio, probe.path("format"));
            assertTrue(Math.abs(probedAudioDuration - expectedDuration) <= 1.0 / SAMPLE_RATE,
                    () -> "ffprobe audio duration " + probedAudioDuration + " differs from "
                            + expectedDuration + " by more than one sample");

            byte[] decodedRgba = run(ffmpeg.orElseThrow().toString(), "-v", "error",
                    "-i", output.toString(), "-map", "0:v:0", "-vf", "vflip",
                    "-pix_fmt", "rgba", "-f", "rawvideo", "pipe:1");
            int frameBytes = WIDTH * HEIGHT * 4;
            assertEquals(FRAME_COUNT * frameBytes, decodedRgba.length);
            for (int frame = 0; frame < FRAME_COUNT; frame++) {
                assertArrayEquals(submittedRgba.get(frame),
                        Arrays.copyOfRange(decodedRgba,
                                frame * frameBytes, (frame + 1) * frameBytes),
                        "decoded RGBA differs at frame " + frame);
            }

            byte[] decodedAudioBytes = run(ffmpeg.orElseThrow().toString(), "-v", "error",
                    "-i", output.toString(), "-map", "0:a:0", "-f", "s16le",
                    "-acodec", "pcm_s16le", "-ac", "2", "-ar",
                    Integer.toString(SAMPLE_RATE), "pipe:1");
            short[] decodedPcm = littleEndianShorts(decodedAudioBytes);
            assertArrayEquals(expectedPcm, decodedPcm,
                    "FLAC must preserve tone, silence, and reversed sample regions exactly");
            int decodedStereoFrames = decodedPcm.length / 2;
            double audioDuration = decodedStereoFrames / (double) SAMPLE_RATE;
            assertTrue(Math.abs(audioDuration - expectedDuration) <= 1.0 / SAMPLE_RATE,
                    () -> "audio duration " + audioDuration + " differs from "
                            + expectedDuration + " by more than one sample");
        } finally {
            recorder.abort();
            try (var files = Files.list(directory)) {
                files.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void forcedAudioAttachmentFailureStillProducesStereoTrackAndCompleteVideo() throws Exception {
        Optional<Path> ffmpeg = FfmpegEncoder.findFfmpeg();
        Optional<Path> ffprobe = findFfprobe();
        Assumptions.assumeTrue(ffmpeg.isPresent() && ffprobe.isPresent(),
                "ffmpeg and ffprobe must both be on PATH");

        Path directory = TestTempFiles.createTempDirectory("live-capture-silent-media-");
        AtomicReference<Path> output = new AtomicReference<>();
        CaptureRecorder recorder = new CaptureRecorder(
                new FfmpegEncoder(ffmpeg.orElseThrow().toString(), 1),
                BackpressurePolicy.BLOCK, 8, directory, "live", "silent") {
            @Override public Path stop() throws CaptureException {
                Path finished = super.stop();
                output.set(finished);
                return finished;
            }
        };
        ExecutorService finalizer = Executors.newSingleThreadExecutor();
        LiveCaptureController controller = new LiveCaptureController(
                new LiveCaptureController.Dependencies(
                        rate -> { throw new IllegalStateException("forced audio attach failure"); },
                        () -> SAMPLE_RATE,
                        viewport -> new VideoFrameGrabber() {
                            private int frame;
                            @Override public int width() { return viewport.width(); }
                            @Override public int height() { return viewport.height(); }
                            @Override public byte[] grab() { return viewportFrame(frame++); }
                        },
                        (viewport, rate) -> recorder,
                        finalizer,
                        Duration.ofSeconds(10)));
        try {
            CaptureViewport viewport = new CaptureViewport(0, 0, WIDTH, HEIGHT);
            controller.start(viewport, FRAME_RATE);
            assertEquals(LiveCaptureController.State.ACTIVE, controller.state());
            for (int frame = 0; frame < FRAME_COUNT; frame++) {
                controller.capturePresentedFrame(viewport);
            }
            controller.requestStop(LiveCaptureController.StopReason.USER);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (controller.state() == LiveCaptureController.State.STOPPING
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(LiveCaptureController.State.INACTIVE, controller.state());
            assertTrue(output.get() != null && Files.exists(output.get()));

            JsonNode probe = new ObjectMapper().readTree(run(ffprobe.orElseThrow().toString(),
                    "-v", "error", "-count_frames", "-show_streams", "-show_format",
                    "-of", "json", output.get().toString()));
            JsonNode video = stream(probe, "video");
            JsonNode audio = stream(probe, "audio");
            assertEquals(2, audio.path("channels").asInt());
            assertEquals(FRAME_COUNT, video.path("nb_read_frames").asInt());
            assertTrue(Math.abs(mediaDurationSeconds(audio, probe.path("format"))
                    - FRAME_COUNT / (double) FRAME_RATE) <= 1.0 / SAMPLE_RATE);

            byte[] decoded = run(ffmpeg.orElseThrow().toString(), "-v", "error",
                    "-i", output.get().toString(), "-map", "0:a:0", "-f", "s16le",
                    "-acodec", "pcm_s16le", "-ac", "2", "-ar",
                    Integer.toString(SAMPLE_RATE), "pipe:1");
            for (byte sample : decoded) assertEquals(0, sample);
            assertEquals(FRAME_COUNT * STEREO_FRAMES_PER_VIDEO_FRAME * 4,
                    decoded.length);
        } finally {
            controller.close();
            // close() only awaits the pending finalizer future; an externally
            // supplied executor stays alive unless the owner shuts it down.
            finalizer.shutdownNow();
            try (var files = Files.list(directory)) {
                files.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
            }
            Files.deleteIfExists(directory);
        }
    }

    // ---------------------------------------------------------------
    // Mixed real final audio: SMPS + fallback WAV + pitched WAV + raw PCM
    // ---------------------------------------------------------------

    @Test
    void mixedSmpsWavAndRawPcmFinalAudioProducesLosslessStereoFlacMkv()
            throws Exception {
        Optional<Path> ffmpeg = FfmpegEncoder.findFfmpeg();
        Optional<Path> ffprobe = findFfprobe();
        Assumptions.assumeTrue(ffmpeg.isPresent() && ffprobe.isPresent(),
                "ffmpeg and ffprobe must both be on PATH");

        int frameRate = installMixedPresentation();
        int packetFrames = SAMPLE_RATE / frameRate;
        admitMixedSources();

        Path directory = TestTempFiles.createTempDirectory("live-capture-mixed-media-");
        CaptureRecorder recorder = new CaptureRecorder(
                new FfmpegEncoder(ffmpeg.orElseThrow().toString(), 1),
                BackpressurePolicy.BLOCK, 8, directory, "live", "mixed");
        LiveCaptureAudioHandle tap = audio.beginLiveCaptureAudio(frameRate);
        short[] pcm = new short[tap.maxStereoFramesPerPacket() * 2];
        short[] expectedPcm =
                new short[MIXED_FRAME_COUNT * packetFrames * 2];
        List<byte[]> submittedRgba = new ArrayList<>();
        try {
            recorder.start(WIDTH, HEIGHT, frameRate, SAMPLE_RATE);
            for (int frame = 0; frame < MIXED_FRAME_COUNT; frame++) {
                audio.presentFrame(PresentationMode.FORWARD);
                int frames = tap.drainPresentationFrame(pcm);
                assertEquals(packetFrames, frames,
                        "the tap is clocked by the producer's frame clock");
                System.arraycopy(pcm, 0, expectedPcm,
                        frame * packetFrames * 2, frames * 2);
                byte[] rgba = viewportFrame(frame);
                submittedRgba.add(rgba);
                recorder.submit(new CapturedFrame(rgba, WIDTH, HEIGHT,
                        pcm, frames, frame));
            }
            tap.close();
            Path output = recorder.stop();

            for (int frame = 0; frame < MIXED_FRAME_COUNT; frame++) {
                int start = frame * packetFrames * 2;
                assertTrue(
                        nonZeroSamples(expectedPcm, start,
                                start + packetFrames * 2) > 0,
                        "mixed SMPS/WAV/pitched-WAV/raw-PCM final PCM must be "
                                + "audible in frame " + frame);
            }
            JsonNode probe = probeMedia(ffprobe.orElseThrow(), output);
            JsonNode video = stream(probe, "video");
            JsonNode audioStream = stream(probe, "audio");
            assertEquals("flac", audioStream.path("codec_name").asText());
            assertEquals(2, audioStream.path("channels").asInt());
            assertEquals(MIXED_FRAME_COUNT,
                    video.path("nb_read_frames").asInt(),
                    "every submitted video frame must reach the container");
            double audioDuration =
                    mediaDurationSeconds(audioStream, probe.path("format"));
            Map<Integer, Double> lastPts = lastPacketPtsByStream(
                    ffprobe.orElseThrow().toString(), output);
            double videoDuration = videoDurationSeconds(video, frameRate,
                    lastPts.getOrDefault(video.path("index").asInt(), -1.0));
            assertTrue(Math.abs(audioDuration - videoDuration)
                            <= 1.0 / SAMPLE_RATE,
                    () -> "audio duration " + audioDuration
                            + " differs from video duration " + videoDuration
                            + " by more than one sample");

            byte[] decodedAudioBytes = run(ffmpeg.orElseThrow().toString(),
                    "-v", "error", "-i", output.toString(), "-map", "0:a:0",
                    "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "2",
                    "-ar", Integer.toString(SAMPLE_RATE), "pipe:1");
            assertArrayEquals(expectedPcm,
                    littleEndianShorts(decodedAudioBytes),
                    "FLAC must preserve the mixed final PCM exactly");

            byte[] decodedRgba = run(ffmpeg.orElseThrow().toString(),
                    "-v", "error", "-i", output.toString(), "-map", "0:v:0",
                    "-vf", "vflip", "-pix_fmt", "rgba", "-f", "rawvideo",
                    "pipe:1");
            int frameBytes = WIDTH * HEIGHT * 4;
            assertEquals(MIXED_FRAME_COUNT * frameBytes, decodedRgba.length);
            for (int frame = 0; frame < MIXED_FRAME_COUNT; frame++) {
                assertArrayEquals(submittedRgba.get(frame),
                        Arrays.copyOfRange(decodedRgba, frame * frameBytes,
                                (frame + 1) * frameBytes),
                        "decoded RGBA differs at frame " + frame);
            }
        } finally {
            recorder.abort();
            deleteRecursively(directory);
        }
    }

    // ---------------------------------------------------------------
    // Mid-session tap failure: video continues with clocked silence
    // ---------------------------------------------------------------

    @Test
    void midRecordingTapFailureContinuesVideoWithExactClockedSilence()
            throws Exception {
        Optional<Path> ffmpeg = FfmpegEncoder.findFfmpeg();
        Optional<Path> ffprobe = findFfprobe();
        Assumptions.assumeTrue(ffmpeg.isPresent() && ffprobe.isPresent(),
                "ffmpeg and ffprobe must both be on PATH");

        int frameRate = installMixedPresentation();
        int packetFrames = SAMPLE_RATE / frameRate;
        admitMixedSources();

        String priorProperty = System.getProperty(AUDIO_FAIL_PROPERTY);
        Path directory =
                TestTempFiles.createTempDirectory("live-capture-tap-failure-media-");
        AtomicReference<Path> output = new AtomicReference<>();
        CaptureRecorder recorder = new CaptureRecorder(
                new FfmpegEncoder(ffmpeg.orElseThrow().toString(), 1),
                BackpressurePolicy.BLOCK, 8, directory, "live", "tapfail") {
            @Override public Path stop() throws CaptureException {
                Path finished = super.stop();
                output.set(finished);
                return finished;
            }
        };
        ExecutorService finalizer = Executors.newSingleThreadExecutor();
        List<byte[]> submittedRgba = new ArrayList<>();
        LiveCaptureController controller = new LiveCaptureController(
                new LiveCaptureController.Dependencies(
                        rate -> DebugFailAfterFramesAudioHandle.maybeWrap(
                                audio.beginLiveCaptureAudio(rate),
                                injectedFailAfterFrames()),
                        audio::outputSampleRate,
                        viewport -> new VideoFrameGrabber() {
                            private int frame;
                            @Override public int width() {
                                return viewport.width();
                            }
                            @Override public int height() {
                                return viewport.height();
                            }
                            @Override public byte[] grab() {
                                byte[] rgba = viewportFrame(frame++);
                                submittedRgba.add(rgba);
                                return rgba;
                            }
                        },
                        (viewport, rate) -> recorder,
                        finalizer,
                        Duration.ofSeconds(10)));
        try {
            // Set inside the try the finally restores: a throw during fixture
            // setup must never leak the injection hook into the rest of this
            // reused surefire fork. The handle factory above reads the
            // property lazily, when controller.start() invokes it.
            System.setProperty(AUDIO_FAIL_PROPERTY,
                    Integer.toString(TAP_FAIL_AFTER_FRAMES));
            CaptureViewport viewport = new CaptureViewport(0, 0, WIDTH, HEIGHT);
            controller.start(viewport, frameRate);
            assertEquals(LiveCaptureController.State.ACTIVE, controller.state());
            for (int frame = 0; frame < FAILURE_FRAME_COUNT; frame++) {
                audio.presentFrame(PresentationMode.FORWARD);
                controller.capturePresentedFrame(viewport);
                assertEquals(LiveCaptureController.State.ACTIVE,
                        controller.state(),
                        "an audio-only failure must never stop the recorder");
            }
            assertNotNull(controller.lastFailure(),
                    "the injected tap failure must be reported once");
            controller.requestStop(LiveCaptureController.StopReason.USER);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (controller.state() == LiveCaptureController.State.STOPPING
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(LiveCaptureController.State.INACTIVE,
                    controller.state());
            assertTrue(output.get() != null && Files.exists(output.get()));

            JsonNode probe = probeMedia(ffprobe.orElseThrow(), output.get());
            JsonNode video = stream(probe, "video");
            JsonNode audioStream = stream(probe, "audio");
            assertEquals("flac", audioStream.path("codec_name").asText());
            assertEquals(2, audioStream.path("channels").asInt());
            assertEquals(FAILURE_FRAME_COUNT,
                    video.path("nb_read_frames").asInt(),
                    "video frames must continue past the audio failure");
            double audioDuration =
                    mediaDurationSeconds(audioStream, probe.path("format"));
            Map<Integer, Double> lastPts = lastPacketPtsByStream(
                    ffprobe.orElseThrow().toString(), output.get());
            double videoDuration = videoDurationSeconds(video, frameRate,
                    lastPts.getOrDefault(video.path("index").asInt(), -1.0));
            assertTrue(Math.abs(audioDuration - videoDuration)
                            <= 1.0 / SAMPLE_RATE,
                    () -> "audio duration " + audioDuration
                            + " differs from video duration " + videoDuration
                            + " by more than one sample");

            short[] decoded = littleEndianShorts(run(
                    ffmpeg.orElseThrow().toString(), "-v", "error",
                    "-i", output.get().toString(), "-map", "0:a:0",
                    "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "2",
                    "-ar", Integer.toString(SAMPLE_RATE), "pipe:1"));
            assertEquals(FAILURE_FRAME_COUNT * packetFrames * 2,
                    decoded.length,
                    "the silent tail must keep the audio track clock-correct");
            int failureSample = TAP_FAIL_AFTER_FRAMES * packetFrames * 2;
            for (int frame = 0; frame < TAP_FAIL_AFTER_FRAMES; frame++) {
                int start = frame * packetFrames * 2;
                assertTrue(
                        nonZeroSamples(decoded, start, start + packetFrames * 2)
                                > 0,
                        "frame " + frame + " before the failure must be audible");
            }
            for (int sample = failureSample; sample < decoded.length; sample++) {
                assertEquals(0, decoded[sample],
                        "sample " + sample + " after the failure must be exact "
                                + "clocked silence");
            }

            byte[] decodedRgba = run(ffmpeg.orElseThrow().toString(),
                    "-v", "error", "-i", output.get().toString(),
                    "-map", "0:v:0", "-vf", "vflip", "-pix_fmt", "rgba",
                    "-f", "rawvideo", "pipe:1");
            int frameBytes = WIDTH * HEIGHT * 4;
            assertEquals(FAILURE_FRAME_COUNT, submittedRgba.size());
            assertEquals(FAILURE_FRAME_COUNT * frameBytes, decodedRgba.length);
            for (int frame = 0; frame < FAILURE_FRAME_COUNT; frame++) {
                assertArrayEquals(submittedRgba.get(frame),
                        Arrays.copyOfRange(decodedRgba, frame * frameBytes,
                                (frame + 1) * frameBytes),
                        "decoded RGBA differs at frame " + frame);
            }
        } finally {
            controller.close();
            // close() only awaits the pending finalizer future; an externally
            // supplied executor stays alive unless the owner shuts it down.
            finalizer.shutdownNow();
            if (priorProperty == null) {
                System.clearProperty(AUDIO_FAIL_PROPERTY);
            } else {
                System.setProperty(AUDIO_FAIL_PROPERTY, priorProperty);
            }
            deleteRecursively(directory);
        }
    }

    // ---------------------------------------------------------------
    // The A/V-alignment check must read the container, not the request
    // ---------------------------------------------------------------

    /**
     * Guards the media assertions themselves: {@link #videoDurationSeconds}
     * must fail when the container's video frame rate is not the rate the
     * recorder was started with. Without this the A/V comparison degenerates
     * into a check on the audio stream alone.
     */
    @Test
    void videoDurationRejectsAContainerTaggedAtTheWrongFrameRate() {
        ObjectMapper mapper = new ObjectMapper();
        // A well-formed stream: 24 frames at 60fps, last packet at 23/60.
        double alignedLastPts = (FAILURE_FRAME_COUNT - 1) / 60.0;
        ObjectNode requested = mapper.createObjectNode();
        requested.put("nb_read_frames", FAILURE_FRAME_COUNT);
        requested.put("avg_frame_rate", "60/1");
        requested.put("r_frame_rate", "60/1");
        assertEquals(FAILURE_FRAME_COUNT / 60.0,
                videoDurationSeconds(requested, 60, alignedLastPts), 1.0e-12);

        ObjectNode halved = mapper.createObjectNode();
        halved.put("nb_read_frames", FAILURE_FRAME_COUNT);
        halved.put("avg_frame_rate", "30/1");
        halved.put("r_frame_rate", "30/1");
        assertThrows(AssertionError.class,
                () -> videoDurationSeconds(halved, 60, alignedLastPts),
                "a video stream tagged at half the requested rate is a 2x A/V "
                        + "desync and must fail the alignment assertion");

        ObjectNode missing = mapper.createObjectNode();
        missing.put("nb_read_frames", FAILURE_FRAME_COUNT);
        missing.put("avg_frame_rate", "0/0");
        missing.put("r_frame_rate", "N/A");
        assertThrows(AssertionError.class,
                () -> videoDurationSeconds(missing, 60, alignedLastPts),
                "a container with no usable frame rate must not be treated as "
                        + "aligned");
    }

    /**
     * The case the frame-count form of this helper structurally could not
     * catch: a container with the right frame count and the right rate tag,
     * whose packets are actually written at twice that spacing. Both operands
     * of {@code nb_read_frames / containerFrameRate} are unchanged by such a
     * desync, so the old form returned the nominal duration and passed.
     */
    @Test
    void videoDurationRejectsPacketSpacingThatDisagreesWithTheRateTag() {
        ObjectNode desynced = new ObjectMapper().createObjectNode();
        desynced.put("nb_read_frames", FAILURE_FRAME_COUNT);
        desynced.put("avg_frame_rate", "60/1");
        desynced.put("r_frame_rate", "60/1");

        // 24 frames tagged 60fps but written at 30fps spacing: the frame count
        // and the rate tag are both exactly what a correct file would carry.
        double desyncedLastPts = (FAILURE_FRAME_COUNT - 1) / 30.0;
        assertThrows(AssertionError.class,
                () -> videoDurationSeconds(desynced, 60, desyncedLastPts),
                "packets spanning twice their tagged duration are a 2x A/V "
                        + "desync and must fail the alignment assertion");

        double missingPts = -1.0;
        assertThrows(AssertionError.class,
                () -> videoDurationSeconds(desynced, 60, missingPts),
                "a video stream with no usable packet timestamp must not be "
                        + "treated as aligned");
    }

    // ---------------------------------------------------------------
    // Mixed-source presentation fixture
    // ---------------------------------------------------------------

    /**
     * Installs a real {@link AudioManager} presentation over a no-device sink
     * and returns its presentation frame rate. Every packet the media tests
     * record is the producer's own final PCM.
     */
    private int installMixedPresentation() {
        audio = AudioManager.getInstance();
        audio.endCaptureMode();
        audio.resetState();
        SonicConfigurationService.getInstance().resetToDefaults();
        audio.setBackend(new MediaPresentationBackend(
                new NoDeviceAudioSink(SAMPLE_RATE)));

        AudioTestFixtures.StubSmpsLoader loader =
                new AudioTestFixtures.StubSmpsLoader();
        AbstractSmpsData smps = new AudioTestFixtures.StubSmpsData("smps");
        smps.setId(SMPS_MUSIC);
        loader.musicResults.put(SMPS_MUSIC, smps);
        Rom rom = mock(Rom.class);
        try {
            when(rom.readBytes(SEGA_PCM_ADDRESS, SEGA_PCM_LENGTH))
                    .thenReturn(rampPcm(SEGA_PCM_LENGTH));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        audio.setAudioProfile(new MediaAudioProfile(loader, new SegaPcmSpec(
                SEGA_PCM_ADDRESS, SEGA_PCM_LENGTH, SEGA_PCM_RATE)));
        audio.setRom(rom);
        AudioManagerTestDiagnostics.registerFallbackSfxAsset(
                audio, "sfx/jump.wav", rampPcm(SAMPLE_RATE), SAMPLE_RATE);
        AudioManagerTestDiagnostics.registerFallbackSfxAsset(
                audio, "sfx/skid.wav", rampPcm(SAMPLE_RATE), SAMPLE_RATE);

        assertEquals(SAMPLE_RATE, audio.outputSampleRate());
        return audio.presentationFrameRate();
    }

    /** Admits SMPS music, two WAV SFX (one pitched), and raw SEGA PCM. */
    private void admitMixedSources() {
        audio.playMusic(SMPS_MUSIC);
        audio.playSfx("JUMP", 1.0f);
        audio.playSfx("SKID", 2.0f);
        audio.playSegaPcm();
        audio.presentFrame(PresentationMode.SILENT);
        assertNotNull(AudioManagerTestDiagnostics.primeAdmittedSmpsMusic(audio),
                "the SMPS music voice must be admitted before it can sound");

        AudioPresentationSnapshot admitted =
                audio.captureLogicalSnapshot().presentation();
        assertNotNull(admitted.activeMusic(), "SMPS music owns the music slot");
        assertTrue(admitted.smpsLogical() != null
                        && !admitted.smpsLogical().sequencers().isEmpty(),
                "session-owned SMPS state is admitted");
        assertEquals(1, sampleVoiceCount(admitted, "sfx/jump.wav"));
        assertEquals(1, sampleVoiceCount(admitted, "sfx/skid.wav"));
        assertNotNull(admitted.rawPcmVoiceId(), "raw SEGA PCM is admitted");
    }

    private static long sampleVoiceCount(
            AudioPresentationSnapshot snapshot, String assetId) {
        return snapshot.voices().stream()
                .filter(PresentationVoiceSnapshot.Sample.class::isInstance)
                .map(PresentationVoiceSnapshot.Sample.class::cast)
                .filter(voice -> assetId.equals(voice.assetId()))
                .count();
    }

    /** Resolves the Task 11 injection hook exactly as {@code Engine} does. */
    private static int injectedFailAfterFrames() {
        try {
            int value = Integer.parseInt(
                    System.getProperty(AUDIO_FAIL_PROPERTY, "-1"));
            return value >= -1 ? value : -1;
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }

    private static byte[] rampPcm(int length) {
        byte[] pcm = new byte[length];
        for (int index = 0; index < length; index++) {
            pcm[index] = (byte) (index % 251);
        }
        return pcm;
    }

    private static int nonZeroSamples(short[] samples, int from, int to) {
        int nonZero = 0;
        for (int index = from; index < to; index++) {
            if (samples[index] != 0) {
                nonZero++;
            }
        }
        return nonZero;
    }

    /**
     * The video stream's real duration, derived from the frames ffprobe
     * actually decoded and the frame rate the container itself carries.
     *
     * <p>Matroska stores a video stream's {@code duration} as the last frame's
     * presentation timestamp, so the container reports one frame period less
     * than the played duration (0.199 s for 12 frames at 60 fps). The audio
     * stream duration, by contrast, is exact. Comparing the two raw container
     * fields would therefore always be one frame period apart regardless of
     * whether A/V are actually aligned, so the frame count supplies the video
     * side instead.
     *
     * <p>The divisor must still come from the file, not from the caller: if it
     * were the rate the test asked for, a regression that pushed the wrong fps
     * down {@code LiveCaptureController.start} ->
     * {@code CaptureRecorder.start} -> {@code FfmpegEncoder.phase1Command}'s
     * {@code -r} flag would tag the stream at, say, 30 fps while the FLAC track
     * still ran at 60 fps — a real 2x desync in the delivered file — and the
     * comparison would still pass. So the probed rate is asserted against the
     * requested rate first and then used as the divisor.
     */
    /**
     * The video stream's own timeline: the last frame's presentation time plus
     * the frame it occupies.
     *
     * <p>Returning {@code nb_read_frames / containerFrameRate} on its own would
     * make the caller's A/V alignment assertion unfalsifiable: both operands
     * are separately pinned to constants by the callers (frame count against
     * the number submitted, rate against the rate the recorder was started
     * with), so the quotient could not vary and the comparison degenerated
     * into a check on the audio stream alone. A container whose packets were
     * written at twice their tagged spacing — a real 2x A/V desync — kept the
     * same frame count and the same rate tag, and passed.
     *
     * <p>So the container's real timeline is measured here, from
     * {@code lastVideoPts}, and a disagreement with the tagged span fails
     * <em>inside</em> this helper. The measured value is not itself returned
     * as the duration because Matroska quantizes timestamps to milliseconds:
     * the last of 12 frames at 60fps is stored as 0.183s rather than
     * 11/60 = 0.18333s, a 0.33ms rounding error that is an order of magnitude
     * larger than the caller's one-sample (1/48000s ≈ 21µs) tolerance. The
     * tolerance below is half a frame — tight enough that any real desync
     * fails by a wide margin, loose enough to absorb millisecond quantization.
     */
    private static double videoDurationSeconds(
            JsonNode video, int requestedFrameRate, double lastVideoPts) {
        int frames = video.path("nb_read_frames").asInt();
        assertTrue(frames > 0, "ffprobe decoded no video frames");
        double containerFrameRate = containerFrameRate(video);
        assertEquals(requestedFrameRate, containerFrameRate, 1.0e-9,
                "the container's video frame rate must be the rate the "
                        + "recorder was started with");
        assertTrue(lastVideoPts >= 0,
                "the video stream carried no usable packet timestamp");
        double taggedSpan = frames / containerFrameRate;
        double measuredSpan = lastVideoPts + 1.0 / containerFrameRate;
        assertEquals(taggedSpan, measuredSpan, 0.5 / containerFrameRate,
                () -> "the video packets span " + measuredSpan + "s but the "
                        + frames + " frames tagged at " + containerFrameRate
                        + "fps should span " + taggedSpan
                        + "s: packet spacing disagrees with the rate tag");
        return taggedSpan;
    }

    /** The frame rate ffmpeg actually wrote into the video stream. */
    private static double containerFrameRate(JsonNode video) {
        double average = rationalRate(video.path("avg_frame_rate").asText());
        double real = rationalRate(video.path("r_frame_rate").asText());
        assertTrue(average > 0 || real > 0,
                "ffprobe reported no usable video frame rate: " + video);
        if (average > 0 && real > 0) {
            assertEquals(real, average, 1.0e-9,
                    "the container's average and base frame rates disagree");
        }
        return average > 0 ? average : real;
    }

    /** Parses an ffprobe {@code num/den} rate; 0 when unusable. */
    private static double rationalRate(String value) {
        if (value == null || value.isBlank() || "N/A".equals(value)) {
            return 0;
        }
        int slash = value.indexOf('/');
        if (slash < 0) {
            return Double.parseDouble(value);
        }
        double denominator = Double.parseDouble(value.substring(slash + 1));
        if (denominator == 0) {
            return 0;
        }
        return Double.parseDouble(value.substring(0, slash)) / denominator;
    }

    private static JsonNode probeMedia(Path ffprobe, Path media)
            throws Exception {
        return new ObjectMapper().readTree(run(ffprobe.toString(),
                "-v", "error", "-count_frames", "-show_streams", "-show_format",
                "-of", "json", media.toString()));
    }

    /**
     * Asserts per-stream timestamp monotonicity and returns the last
     * presentation time seen on each stream, keyed by stream index. The
     * returned values feed {@link #videoDurationSeconds}, so the A/V alignment
     * check is made against the container's real timeline.
     */
    private static Map<Integer, Double> lastPacketPtsByStream(
            String ffprobe, Path media) throws Exception {
        JsonNode probe = new ObjectMapper().readTree(run(ffprobe,
                "-v", "error", "-show_packets", "-of", "json",
                media.toString()));
        Map<Integer, Double> lastByStream = new java.util.HashMap<>();
        for (JsonNode packet : probe.path("packets")) {
            String rawPts = packet.path("pts_time").asText();
            if (rawPts.isBlank() || "N/A".equals(rawPts)) {
                continue;
            }
            int streamIndex = packet.path("stream_index").asInt();
            double pts = Double.parseDouble(rawPts);
            Double previous = lastByStream.get(streamIndex);
            assertTrue(previous == null || pts >= previous,
                    "stream " + streamIndex + " timestamps regressed from "
                            + previous + " to " + pts);
            lastByStream.put(streamIndex, pts);
        }
        assertEquals(2, lastByStream.size(),
                "both streams must carry timestamped packets");
        return lastByStream;
    }

    private static void assertMonotonicPacketTimestamps(
            String ffprobe, Path media) throws Exception {
        lastPacketPtsByStream(ffprobe, media);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            files.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
        Files.deleteIfExists(directory);
    }

    /** Backend whose only presentation surface is a no-device sink. */
    private static final class MediaPresentationBackend
            extends NullAudioBackend {
        private final AudioPresentationSink sink;

        private MediaPresentationBackend(AudioPresentationSink sink) {
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

    private record MediaAudioProfile(SmpsLoader loader, SegaPcmSpec spec)
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
            return Map.of();
        }
        @Override public SegaPcmSpec getSegaPcmSpec() { return spec; }
        @Override public byte[] loadSegaPcm(Object rom) throws java.io.IOException {
            return com.openggf.game.audio.SegaPcmRomReader.read(rom, getSegaPcmSpec());
        }
    }

    private static JsonNode stream(JsonNode probe, String codecType) {
        for (JsonNode stream : probe.path("streams")) {
            if (codecType.equals(stream.path("codec_type").asText())) {
                return stream;
            }
        }
        throw new AssertionError("missing " + codecType + " stream: " + probe);
    }

    private static double mediaDurationSeconds(JsonNode stream, JsonNode format) {
        JsonNode durationTs = stream.path("duration_ts");
        String timeBase = stream.path("time_base").asText();
        if (durationTs.canConvertToLong() && timeBase.contains("/")) {
            String[] fraction = timeBase.split("/", 2);
            return durationTs.asLong()
                    * Double.parseDouble(fraction[0])
                    / Double.parseDouble(fraction[1]);
        }
        String streamDuration = stream.path("duration").asText();
        if (!streamDuration.isBlank() && !"N/A".equals(streamDuration)) {
            return Double.parseDouble(streamDuration);
        }
        String taggedDuration = stream.path("tags").path("DURATION").asText();
        if (!taggedDuration.isBlank()) {
            String[] fields = taggedDuration.split(":");
            if (fields.length == 3) {
                return Double.parseDouble(fields[0]) * 3600
                        + Double.parseDouble(fields[1]) * 60
                        + Double.parseDouble(fields[2]);
            }
        }
        String formatDuration = format.path("duration").asText();
        if (!formatDuration.isBlank() && !"N/A".equals(formatDuration)) {
            return Double.parseDouble(formatDuration);
        }
        throw new AssertionError("ffprobe supplied no usable audio duration");
    }

    private static byte[] viewportFrame(int frame) {
        byte[] rgba = new byte[WIDTH * HEIGHT * 4];
        for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
            int offset = pixel * 4;
            rgba[offset] = (byte) (frame * 31 + pixel);
            rgba[offset + 1] = (byte) (frame * 17 + pixel * 3);
            rgba[offset + 2] = (byte) (255 - frame * 13 - pixel);
            rgba[offset + 3] = (byte) 255;
        }
        int marker = (frame * 2 % WIDTH) * 4;
        rgba[marker] = (byte) 255;
        rgba[marker + 1] = 0;
        rgba[marker + 2] = 0;
        return rgba;
    }

    private static short[] presentationPcm() {
        short[] pcm = new short[FRAME_COUNT * STEREO_FRAMES_PER_VIDEO_FRAME * 2];
        int forwardFrames = 2 * STEREO_FRAMES_PER_VIDEO_FRAME;
        for (int sample = 0; sample < forwardFrames; sample++) {
            short value = (short) ((sample * 97) % 20_000 - 10_000);
            pcm[sample * 2] = value;
            pcm[sample * 2 + 1] = (short) -value;
        }
        int reverseStart = 4 * STEREO_FRAMES_PER_VIDEO_FRAME;
        for (int sample = 0; sample < forwardFrames; sample++) {
            int source = forwardFrames - 1 - sample;
            pcm[(reverseStart + sample) * 2] = pcm[source * 2];
            pcm[(reverseStart + sample) * 2 + 1] = pcm[source * 2 + 1];
        }
        return pcm;
    }

    private static short[] littleEndianShorts(byte[] bytes) {
        assertEquals(0, bytes.length % 2);
        short[] result = new short[bytes.length / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (short) ((bytes[i * 2] & 0xff) | (bytes[i * 2 + 1] << 8));
        }
        return result;
    }

    private static byte[] run(String executable, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        int exit = process.waitFor();
        assertEquals(0, exit, () -> String.join(" ", command) + "\n" + output);
        return output.toByteArray();
    }

    private static Optional<Path> findFfprobe() {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        String executable = FfmpegEncoder.isWindows() ? "ffprobe.exe" : "ffprobe";
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory, executable);
            if (Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
