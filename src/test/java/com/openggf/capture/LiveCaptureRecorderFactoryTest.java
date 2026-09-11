package com.openggf.capture;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveCaptureRecorderFactoryTest {

    /**
     * Pins every capture key the factory reads, not just the ones an assertion
     * names. {@code SonicConfigurationService.getInstance()} resolves against
     * the developer's real {@code config.yaml}, so anything left unpinned is
     * ambient: {@code capture.container} decides the asserted file extension
     * outright, and a non-default codec or preset would make
     * {@code create()} throw. The result was a test that passed or failed on
     * whoever's machine ran it.
     */
    private static void pinCaptureConfig(SonicConfigurationService config) {
        config.setSessionOverride(SonicConfiguration.CAPTURE_OUTPUT_DIR, "target/my-live");
        config.setSessionOverride(SonicConfiguration.CAPTURE_CONTAINER, "mkv");
        config.setSessionOverride(SonicConfiguration.CAPTURE_CODEC, "ffv1");
        config.setSessionOverride(SonicConfiguration.CAPTURE_AUDIO_CODEC, "flac");
        config.setSessionOverride(SonicConfiguration.CAPTURE_ENCODER_THREADS, 0);
        config.setSessionOverride(SonicConfiguration.CAPTURE_ENCODER_PRESET, "");
        config.setSessionOverride(SonicConfiguration.CAPTURE_QUEUE_BUDGET_MB, 192);
        config.setSessionOverride(SonicConfiguration.CAPTURE_FFMPEG_PASS1_ARGS, "default");
        config.setSessionOverride(SonicConfiguration.CAPTURE_FFMPEG_PASS2_ARGS, "default");
    }

    @Test void usesConfiguredDirectoryFixedUtcTimestampAndLiveLabel() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        try {
            pinCaptureConfig(config);
            Clock clock = Clock.fixed(Instant.parse("2026-07-23T10:11:12.345Z"), ZoneOffset.UTC);
            CaptureRecorder recorder = new LiveCaptureRecorderFactory(config, clock, "ffmpeg")
                    .create(new CaptureViewport(4, 5, 320, 224), 60);
            assertEquals(Path.of("target/my-live/capture-live-20260723-101112-345.mkv"),
                    recorder.outputFile());
        } finally {
            config.clearSessionOverrides();
        }
    }

    @Test void containerComesFromConfigRatherThanAFixedExtension() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        try {
            pinCaptureConfig(config);
            config.setSessionOverride(SonicConfiguration.CAPTURE_CONTAINER, "mp4");
            config.setSessionOverride(SonicConfiguration.CAPTURE_CODEC, "h264");
            Clock clock = Clock.fixed(Instant.parse("2026-07-23T10:11:12.345Z"), ZoneOffset.UTC);

            CaptureRecorder recorder = new LiveCaptureRecorderFactory(config, clock, "ffmpeg")
                    .create(new CaptureViewport(4, 5, 320, 224), 60);

            assertEquals(Path.of("target/my-live/capture-live-20260723-101112-345.mp4"),
                    recorder.outputFile());
        } finally {
            config.clearSessionOverrides();
        }
    }

    @Test void queueDepthIsBudgetedInBytesSoLargeWindowsHoldFewerFrames() {
        // 192MB against a 1280x896 window (4.59MB/frame) is ~41 frames; the
        // same budget at 2560x1792 is a quarter of that.
        CaptureViewport window = new CaptureViewport(0, 0, 1280, 896);
        CaptureViewport bigWindow = new CaptureViewport(0, 0, 2560, 1792);

        int frames = LiveCaptureRecorderFactory.queueFrames(192, window);
        int bigFrames = LiveCaptureRecorderFactory.queueFrames(192, bigWindow);

        assertEquals(192 * 1024 * 1024 / (1280 * 896 * 4), frames);
        assertEquals(frames / 4, bigFrames,
                "four times the pixels must buy a quarter of the depth");
    }

    @Test void queueDepthClampsAtBothEnds() {
        CaptureViewport huge = new CaptureViewport(0, 0, 3840, 2160);
        CaptureViewport tiny = new CaptureViewport(0, 0, 320, 224);

        assertEquals(LiveCaptureRecorderFactory.MIN_QUEUE_FRAMES,
                LiveCaptureRecorderFactory.queueFrames(1, huge),
                "a budget that cannot fund the floor still gets the floor");
        assertEquals(LiveCaptureRecorderFactory.MIN_QUEUE_FRAMES,
                LiveCaptureRecorderFactory.queueFrames(0, tiny));
        assertEquals(LiveCaptureRecorderFactory.MAX_QUEUE_FRAMES,
                LiveCaptureRecorderFactory.queueFrames(4096, tiny),
                "past the ceiling a deeper queue only defers a sustained overload");
    }
}
