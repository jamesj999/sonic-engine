package com.openggf.game.session;

import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.RomDetectionService;
import com.openggf.graphics.GraphicsManager;

import java.util.Objects;

public final class EngineContext {
    private final SonicConfigurationService configuration;
    private final GraphicsManager graphics;
    private final AudioManager audio;
    private final RomManager roms;
    private final PerformanceProfiler profiler;
    private final DebugOverlayManager debugOverlay;
    private final PlaybackDebugManager playbackDebug;
    private final RomDetectionService romDetection;
    private final CrossGameFeatureProvider crossGameFeatures;

    public EngineContext(SonicConfigurationService configuration, GraphicsManager graphics,
                          AudioManager audio, RomManager roms, PerformanceProfiler profiler,
                          DebugOverlayManager debugOverlay, PlaybackDebugManager playbackDebug,
                          RomDetectionService romDetection, CrossGameFeatureProvider crossGameFeatures) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.graphics = Objects.requireNonNull(graphics, "graphics");
        this.audio = Objects.requireNonNull(audio, "audio");
        this.roms = Objects.requireNonNull(roms, "roms");
        this.profiler = Objects.requireNonNull(profiler, "profiler");
        this.debugOverlay = Objects.requireNonNull(debugOverlay, "debugOverlay");
        this.playbackDebug = Objects.requireNonNull(playbackDebug, "playbackDebug");
        this.romDetection = Objects.requireNonNull(romDetection, "romDetection");
        this.crossGameFeatures = Objects.requireNonNull(crossGameFeatures, "crossGameFeatures");
    }

    public static EngineContext fromLegacySingletonsForBootstrap() {
        return new EngineContext(SonicConfigurationService.getInstance(), GraphicsManager.getInstance(),
                AudioManager.getInstance(), RomManager.getInstance(), PerformanceProfiler.getInstance(),
                DebugOverlayManager.getInstance(), PlaybackDebugManager.getInstance(),
                RomDetectionService.getInstance(), CrossGameFeatureProvider.getInstance());
    }

    public SonicConfigurationService configuration() { return configuration; }
    public GraphicsManager graphics() { return graphics; }
    public AudioManager audio() { return audio; }
    public RomManager roms() { return roms; }
    public PerformanceProfiler profiler() { return profiler; }
    public DebugOverlayManager debugOverlay() { return debugOverlay; }
    public PlaybackDebugManager playbackDebug() { return playbackDebug; }
    public RomDetectionService romDetection() { return romDetection; }
    public CrossGameFeatureProvider crossGameFeatures() { return crossGameFeatures; }
}
