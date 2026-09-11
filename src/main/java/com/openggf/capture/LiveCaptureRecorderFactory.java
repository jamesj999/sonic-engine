package com.openggf.capture;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class LiveCaptureRecorderFactory {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
    private final SonicConfigurationService config;
    private final Clock clock;
    private final String ffmpeg;

    public LiveCaptureRecorderFactory(SonicConfigurationService config, Clock clock, String ffmpeg) {
        this.config = config;
        this.clock = clock;
        this.ffmpeg = ffmpeg;
    }

    /** Never fewer than this many frames, whatever the budget and window size. */
    static final int MIN_QUEUE_FRAMES = 8;
    /** Beyond this, a deeper queue only defers a sustained overload. */
    static final int MAX_QUEUE_FRAMES = 120;

    /**
     * Sizes the encoder queue in whole frames from a memory budget rather than
     * a fixed count, because a queued frame costs {@code width*height*4} bytes
     * and the recording viewport is the window, not the 320x224 native frame —
     * a fixed count that is comfortable at 1280x896 is four times the memory at
     * 2560x1792.
     * <p>
     * Depth matters because the sink is {@link BackpressurePolicy#BLOCK} and
     * submits on the game thread: once the queue fills, the game loop itself
     * stalls until the encoder drains. Lossless FFV1 falls behind on
     * high-motion content — fast-forwarded trace playback especially, where
     * consecutive recorded frames are several gameplay frames apart — so the
     * queue has to absorb that burst or the stutter is visible live.
     */
    static int queueFrames(int budgetMb, CaptureViewport viewport) {
        long budgetBytes = (long) Math.max(0, budgetMb) * 1024L * 1024L;
        long frameBytes = Math.max(1, viewport.rgbaByteSize());
        long frames = budgetBytes / frameBytes;
        return (int) Math.max(MIN_QUEUE_FRAMES, Math.min(MAX_QUEUE_FRAMES, frames));
    }

    public CaptureRecorder create(CaptureViewport viewport, int frameRate) {
        FfmpegEncoder encoder = new FfmpegEncoder(ffmpeg, 1);
        encoder.setCodecs(config.getString(SonicConfiguration.CAPTURE_CODEC),
                config.getString(SonicConfiguration.CAPTURE_AUDIO_CODEC),
                config.getInt(SonicConfiguration.CAPTURE_ENCODER_THREADS),
                config.getString(SonicConfiguration.CAPTURE_ENCODER_PRESET));
        encoder.setCommandOverrides(
                config.getString(SonicConfiguration.CAPTURE_FFMPEG_PASS1_ARGS),
                config.getString(SonicConfiguration.CAPTURE_FFMPEG_PASS2_ARGS));
        int queueFrames = queueFrames(
                config.getInt(SonicConfiguration.CAPTURE_QUEUE_BUDGET_MB), viewport);
        return new CaptureRecorder(encoder, BackpressurePolicy.BLOCK, queueFrames,
                Path.of(config.getString(SonicConfiguration.CAPTURE_OUTPUT_DIR)),
                "live", TIMESTAMP.format(clock.instant()),
                config.getString(SonicConfiguration.CAPTURE_CONTAINER));
    }
}
