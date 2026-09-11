package com.openggf.tools;

import com.openggf.GameLoop;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.capture.BackpressurePolicy;
import com.openggf.capture.CaptureRecorder;
import com.openggf.capture.CapturedFrame;
import com.openggf.capture.DrainPcmAudioTap;
import com.openggf.capture.FfmpegEncoder;
import com.openggf.capture.GlReadPixelsGrabber;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.catalog.TraceCatalog;
import com.openggf.trace.catalog.TraceEntry;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Command-line trace-capture driver. Boots a headless gameplay session against
 * a real ROM/GL context, deterministically replays a recorded trace using the
 * <em>same</em> bootstrap and per-frame drive that {@code AbstractTraceReplayTest}
 * uses (via {@link TraceReplaySessionBootstrap} + {@link RecordingFrameDriver} +
 * {@link TraceReplayBootstrap} phase logic), and records the rendered frames +
 * chip audio to a lossless MKV via {@link FfmpegEncoder} / {@link CaptureRecorder}.
 *
 * <p>The capture used to drive frames through the live {@code GameLoop}/
 * {@code PlaybackDebugManager} playback path, which omits the P2/sidekick input
 * plumbing and the explicit phase loop that the trace-replay tests use. That
 * produced a capture run that desynced from the recorded (and ROM) trajectory
 * — e.g. AIZ rings 19 instead of the recorded 97 by the battleship loop. This
 * driver now reproduces the exact trace-faithful trajectory the tests validate.
 *
 * <p>Invocation (Maven):
 * <pre>
 *   mvn exec:java "-Dexec.mainClass=com.openggf.tools.TraceCaptureTool" \
 *       "-Dexec.args=--trace aiz1 --out-dir target/trace-videos"
 * </pre>
 *
 * <p>Output: {@code &lt;out-dir&gt;/capture-&lt;label&gt;-&lt;UTC&gt;.mkv}. MKV frame index
 * equals trace gameplay-comparison index, so frame N of the MKV is trace frame N.
 *
 * <p>{@code --verify} runs the same bootstrap + drive headlessly with NO GL
 * capture and prints the player rings + camera_x at requested trace frames, so
 * trace-faithfulness can be confirmed before a full capture.
 */
public final class TraceCaptureTool {

    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    private static final DateTimeFormatter UTC_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private TraceCaptureTool() {
    }

    /**
     * Parsed CLI arguments. Unspecified {@code --scale}, {@code --fps},
     * {@code --codec}, and {@code --out-dir} fall back to the {@code CAPTURE_*}
     * config defaults.
     */
    public record Args(String trace, Path outDir, int scale, int fps, String codec,
                       boolean showGhosts, int[] verifyFrames,
                       String clip, int tailFrames) {

        public static Args parse(String[] argv) {
            SonicConfigurationService config = GameServices.configuration();
            String trace = null;
            String outDir = config.getString(SonicConfiguration.CAPTURE_OUTPUT_DIR);
            String diagnostics = System.getProperty("openggf.test.diagnostics");
            if (diagnostics != null && !diagnostics.isBlank()
                    && "target/trace-videos".equals(outDir)) {
                outDir = Path.of(diagnostics, "trace-videos").toString();
            }
            int scale = config.getInt(SonicConfiguration.CAPTURE_SCALE);
            int fps = config.getInt(SonicConfiguration.CAPTURE_FPS);
            String codec = config.getString(SonicConfiguration.CAPTURE_CODEC);
            boolean showGhosts = config.getBoolean(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS);
            int[] verifyFrames = null;
            String clip = null;
            int tailFrames = 150;

            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                switch (arg) {
                    case "--trace" -> trace = CliArguments.requireValue(argv, ++i, arg);
                    case "--out-dir" -> outDir = CliArguments.requireValue(argv, ++i, arg);
                    case "--scale" -> scale = CliArguments.parseInt(CliArguments.requireValue(argv, ++i, arg));
                    case "--fps" -> fps = CliArguments.parseInt(CliArguments.requireValue(argv, ++i, arg));
                    case "--codec" -> codec = CliArguments.requireValue(argv, ++i, arg);
                    case "--no-ghosts" -> showGhosts = false;
                    case "--ghosts" -> showGhosts = true;
                    case "--verify" -> verifyFrames = parseFrameList(CliArguments.requireValue(argv, ++i, arg));
                    case "--clip" -> clip = CliArguments.requireValue(argv, ++i, arg);
                    case "--tail-frames" -> tailFrames = CliArguments.parseInt(CliArguments.requireValue(argv, ++i, arg));
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            if (trace == null || trace.isBlank()) {
                throw new IllegalArgumentException("--trace <id|name|dir> is required");
            }
            return new Args(trace, Path.of(outDir), scale, fps, codec, showGhosts,
                    verifyFrames, clip, tailFrames);
        }

        private static int[] parseFrameList(String spec) {
            String[] parts = spec.split(",");
            int[] frames = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                frames[i] = Integer.parseInt(parts[i].trim());
            }
            return frames;
        }

    }

    public static void main(String[] argv) {
        // CLI composition root: wire process-wide services before any config is
        // read (Args.parse below), mirroring how Engine bootstraps EngineServices.
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        Args args = Args.parse(argv);
        TraceCaptureTool tool = new TraceCaptureTool();
        HeadlessGameBoot boot = null;
        try {
            boot = tool.run(args);
        } catch (Exception e) {
            System.err.println("Trace capture failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (boot != null) {
                try {
                    SessionManager.closeGameplaySession();
                } catch (Exception ignored) {
                    // best-effort teardown
                }
                boot.close();
            }
        }
    }

    /**
     * Resolves the trace, boots a headless session, replays + captures every
     * frame, and finalizes the MKV. Returns the boot so {@code main} can tear
     * it down in {@code finally}.
     */
    private HeadlessGameBoot run(Args args) throws Exception {
        // Apply the desync-ghost toggle so the shared LevelRenderer gate
        // (TraceRenderVisibility) honors it during capture. A trace-faithful
        // capture has no desync, so ghosts are off by default.
        GameServices.configuration().setConfigValue(
                SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS, args.showGhosts());

        // Capture fps must be the rate the presentation producer is clocked
        // at: it presents exactly one packet of sampleRate/frameRate stereo
        // frames per outer frame, so a container/lease clocked differently
        // would truncate or zero-pad every packet. Push the requested fps into
        // the engine frame rate BEFORE boot (the producer is realized from it
        // and reads it lazily), then capture at whatever rate that actually
        // resolves to — a PAL region, for instance, pins the engine to 50.
        GameServices.configuration().setConfigValue(
                SonicConfiguration.FPS, args.fps());

        // --- resolve trace -------------------------------------------------
        TraceEntry entry = resolveTrace(args.trace());
        System.out.println("Capturing trace: " + entry.dir()
                + " (" + entry.gameId() + " zone=" + entry.zone() + " act=" + entry.act() + ")");

        TraceData trace = TraceData.load(entry.dir());
        TraceMetadata meta = trace.metadata();
        Bk2Movie movie = new Bk2MovieLoader().load(entry.bk2Path());

        // --- pre-load configuration (must run BEFORE loadZoneAndAct) -------
        // Mirrors AbstractTraceReplayTest step 3: recorded team, cross-game
        // off, S3K intro-skip off for fresh-level traces. HeadlessGameBoot
        // loads the level AND registers the team, so this must precede boot().
        TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);

        // --- boot headless gameplay session -------------------------------
        Path romPath = TraceToolRomLocations.resolve(
                entry.gameId(), GameServices.configuration(), Path.of(""));
        HeadlessGameBoot boot = new HeadlessGameBoot(SCREEN_WIDTH, SCREEN_HEIGHT);
        try (BootOwnership<HeadlessGameBoot> ownership =
                new BootOwnership<>(
                        boot, SessionManager::closeGameplaySession)) {
        GameLoop loop = boot.boot(
                romPath,
                entry.zone(),
                entry.act(),
                trace.hardwareTimingSchedule().hasRecordedInput()
                        ? HardwareReadinessAdmissionPolicy.RECORDED
                        : HardwareReadinessAdmissionPolicy.LIVE);

        // --- deterministic trace replay bootstrap -------------------------
        // Mirror AbstractTraceReplayTest steps 4-5: start position + ground
        // snap, then the shared replay bootstrap (timing prelude, native
        // frame-0 state, replay cursor). Build the recording driver over the
        // already-spawned player sprite and wire the BK2 movie + start frame.
        RecordingFrameDriver frameDriver =
                new RecordingFrameDriver(GameServices.camera().getFocusedSprite());
        TraceReplayDrive.DriverFixture fixture = new TraceReplayDrive.DriverFixture(frameDriver);
        frameDriver.setBk2Movie(movie,
                TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace));

        // Mirror HeadlessTestFixture.Builder.build steps 7-12: re-anchor
        // sidekicks, wire GroundSensor, re-run camera + level-event init,
        // re-apply S3K zone player state, refresh sidekick CPU bounds, and
        // ground-snap. The fixture runs these unconditionally at build time;
        // HeadlessGameBoot.boot only does loadZoneAndAct, so without these the
        // replay starts from default-load-derived bounds and drifts physics by
        // the first collision (e.g. AIZ x diverges ~400px by trace 2800). For
        // pre-level-intro-prefix traces applyStartPositionAndGroundSnap below
        // is a no-op, so this is the only place these run.
        TraceReplaySessionBootstrap.applyPostLoadLevelInit(trace);

        TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
        TraceReplaySessionBootstrap.BootstrapResult bootResult =
                TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);
        TraceReplayBootstrap.ReplayStartState replayStart = bootResult.replayStart();

        int startIndex = replayStart.startingTraceIndex();
        TraceFrame previousDriveFrame = replayStart.hasSeededTraceState()
                ? trace.getFrame(replayStart.seededTraceIndex())
                : startIndex > 0 ? trace.getFrame(startIndex - 1) : null;
        TraceReplaySessionBootstrap.alignFrameCountersForReplayStart(
                previousDriveFrame,
                startIndex < trace.frameCount() ? trace.getFrame(startIndex) : null);

        if (args.verifyFrames() != null) {
            runVerify(trace, meta, frameDriver, replayStart, args.verifyFrames());
            int maxRequested = java.util.Arrays.stream(args.verifyFrames()).max().orElse(-1);
            if (maxRequested >= trace.getFrame(trace.frameCount() - 1).frame()) {
                fixture.closeHardwareTimingReplayRun();
            }
            return ownership.transfer();
        }

        // --- capture pipeline ---------------------------------------------
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(UTC_STAMP);
        String label = entry.dir().getFileName().toString();
        FfmpegEncoder encoder = new FfmpegEncoder(resolveFfmpeg(), args.scale());
        // --codec / capture.codec was parsed and then never reached the
        // encoder, so selecting one silently did nothing.
        SonicConfigurationService captureConfig = GameServices.configuration();
        encoder.setCodecs(args.codec(),
                captureConfig.getString(SonicConfiguration.CAPTURE_AUDIO_CODEC),
                captureConfig.getInt(SonicConfiguration.CAPTURE_ENCODER_THREADS),
                captureConfig.getString(SonicConfiguration.CAPTURE_ENCODER_PRESET));
        encoder.setCommandOverrides(
                captureConfig.getString(SonicConfiguration.CAPTURE_FFMPEG_PASS1_ARGS),
                captureConfig.getString(SonicConfiguration.CAPTURE_FFMPEG_PASS2_ARGS));
        CaptureRecorder recorder = new CaptureRecorder(
                encoder, BackpressurePolicy.BLOCK, /* queueCapacity */ 8,
                args.outDir(), label, timestamp);

        GlReadPixelsGrabber grabber = new GlReadPixelsGrabber(SCREEN_WIDTH, SCREEN_HEIGHT);
        DrainPcmAudioTap audioTap = new DrainPcmAudioTap(GameServices.audio());
        // The offline lease is a non-consuming view of the already-authoritative
        // presentation producer, so both its rate and the container's rate are
        // the producer's rates. Take it before the recorder opens so the first
        // presented packet is observable.
        int sampleRate = GameServices.audio().outputSampleRate();
        int frameRate = GameServices.audio().presentationFrameRate();
        if (frameRate != args.fps()) {
            System.out.println("capture: requested " + args.fps()
                    + " fps but the presentation producer is clocked at "
                    + frameRate + " fps; capturing at " + frameRate
                    + " so audio and video stay in sync");
        }
        GameServices.audio().beginCaptureMode(sampleRate, frameRate);
        try {
            recorder.start(SCREEN_WIDTH, SCREEN_HEIGHT, frameRate, sampleRate);
        } catch (Throwable failedToOpen) {
            // The recorder never opened, so nothing will stop it and run the
            // finally below: release the lease here or it is leaked onto the
            // producer for the rest of the process.
            GameServices.audio().endCaptureMode();
            throw failedToOpen;
        }
        HeadlessOuterAudioFrames audioFrames =
                new HeadlessOuterAudioFrames(audioTap);

        long captured = 0;
        try {
            captured = args.clip() != null
                    ? driveClip(trace, frameDriver, replayStart, grabber, audioFrames,
                            recorder, args.clip(), args.tailFrames())
                    : driveAndCapture(trace, meta, frameDriver, replayStart,
                            loop, grabber, audioFrames, recorder);
            if (args.clip() == null) {
                fixture.closeHardwareTimingReplayRun();
            }
        } finally {
            try {
                Path out = recorder.stop();
                System.out.println("Captured " + captured + " frames -> " + out.toAbsolutePath());
                if (Files.isRegularFile(out)) {
                    System.out.println("Output size: " + Files.size(out) + " bytes");
                }
            } finally {
                try {
                    GameServices.audio().endCaptureMode();
                } finally {
                    // The grabber holds a frame-sized native read buffer for
                    // its lifetime now that it reuses one per grab.
                    grabber.close();
                }
            }
        }
        return ownership.transfer();
        }
    }

    /**
     * Keeps ownership of a boot from the instant it is constructed until a
     * successful run returns it to {@link #main(String[])}. This closes both
     * the replay session and native boot if any post-construction step throws.
     */
    static final class BootOwnership<T extends AutoCloseable>
            implements AutoCloseable {
        private final T boot;
        private final Runnable closeSession;
        private boolean transferred;

        BootOwnership(T boot, Runnable closeSession) {
            this.boot = java.util.Objects.requireNonNull(boot, "boot");
            this.closeSession = java.util.Objects.requireNonNull(
                    closeSession, "closeSession");
        }

        T transfer() {
            transferred = true;
            return boot;
        }

        @Override
        public void close() throws Exception {
            if (transferred) {
                return;
            }
            Throwable failure = null;
            try {
                closeSession.run();
            } catch (Throwable sessionFailure) {
                failure = sessionFailure;
            }
            try {
                boot.close();
            } catch (Throwable bootFailure) {
                if (failure == null) {
                    failure = bootFailure;
                } else {
                    failure.addSuppressed(bootFailure);
                }
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure != null) {
                throw new IllegalStateException(
                        "capture teardown failed", failure);
            }
        }
    }

    /**
     * Owns the headless capture loop's audio cadence: exactly one final-PCM
     * presentation and exactly one drain of that packet per outer framebuffer
     * frame the driver treats as presented.
     *
     * <p>Simulation-only fast-forward steps must not touch this object — they
     * may enqueue audio commands, but presenting per simulation step would
     * multiply the audio cadence by the number of steps. Conversely every
     * presented frame must be drained exactly once (captured during the window,
     * discarded outside it) so no stale packet is carried into the clip.
     *
     * <p>That contract is enforced here rather than merely documented: present
     * and drain must strictly alternate. Wiring a present into a per-simulation
     * -step body (the cadence-multiplying regression) therefore fails loudly on
     * the first fast-forward frame that runs more than one simulation step,
     * instead of silently emitting several packets per captured frame.
     */
    static final class HeadlessOuterAudioFrames {
        private final DrainPcmAudioTap audioTap;
        private final short[] discardBuffer = new short[16384];
        private boolean presentedUndrained;
        private int presentedFrames;
        private int drainedFrames;

        HeadlessOuterAudioFrames(DrainPcmAudioTap audioTap) {
            this.audioTap = audioTap;
        }

        /** Presents this outer frame's packet. Call once per presented frame. */
        void presentOuterFrame() {
            if (presentedUndrained) {
                throw new IllegalStateException(
                        "the previously presented packet has not been drained:"
                                + " exactly one presentation and one drain"
                                + " belong to each presented outer frame");
            }
            HeadlessGameBoot.presentHeadlessOuterAudioFrame();
            presentedUndrained = true;
            presentedFrames++;
        }

        /** Drains the presented packet into the recorder's PCM buffer. */
        int drainCaptured(short[] target) {
            beginDrain();
            return audioTap.drain(target);
        }

        /** Drains and discards the presented packet during fast-forward. */
        int discardPresented() {
            beginDrain();
            return audioTap.drain(discardBuffer);
        }

        private void beginDrain() {
            if (!presentedUndrained) {
                throw new IllegalStateException(
                        "no presented packet to drain: each drain must follow"
                                + " exactly one presentOuterFrame()");
            }
            presentedUndrained = false;
            drainedFrames++;
        }

        /** Test observation point: presented outer frames so far. */
        int presentedFrames() {
            return presentedFrames;
        }

        /** Test observation point: drained (captured or discarded) packets. */
        int drainedFrames() {
            return drainedFrames;
        }
    }

    /**
     * Drives the S3K phase loop (mirroring AbstractTraceReplayTest.replayS3kTrace)
     * and captures every gameplay-comparison frame. Returns the captured frame
     * count.
     */
    private long driveAndCapture(TraceData trace, TraceMetadata meta,
                                 RecordingFrameDriver frameDriver,
                                 TraceReplayBootstrap.ReplayStartState replayStart,
                                 GameLoop loop,
                                 GlReadPixelsGrabber grabber,
                                 HeadlessOuterAudioFrames audioFrames,
                                 CaptureRecorder recorder) throws Exception {
        short[] pcmBuffer = new short[16384];
        long frameIndex = 0;

        int driveTraceIndex = replayStart.startingTraceIndex();
        TraceFrame previousDriveFrame = replayStart.hasSeededTraceState()
                ? trace.getFrame(replayStart.seededTraceIndex())
                : driveTraceIndex > 0 ? trace.getFrame(driveTraceIndex - 1) : null;

        while (driveTraceIndex < trace.frameCount()) {
            TraceFrame driveFrame = trace.getFrame(driveTraceIndex);
            TraceExecutionPhase phase =
                    TraceReplayBootstrap.phaseForReplay(trace, previousDriveFrame, driveFrame);

            TraceReplayDrive.DriveOutcome outcome =
                    TraceReplayDrive.driveOneFrame(trace, frameDriver, replayStart, phase, driveTraceIndex);
            if (!outcome.consumedRow()) {
                continue;
            }

            if (outcome.gameplayFrame()
                    && TraceReplayBootstrap.shouldCompareGameplayStateForReplay(phase)) {
                audioFrames.presentOuterFrame();
                TraceReplayDrive.renderFrame();
                byte[] rgba = grabber.grab();
                int sampleCount = audioFrames.drainCaptured(pcmBuffer);
                recorder.submit(new CapturedFrame(rgba, SCREEN_WIDTH, SCREEN_HEIGHT,
                        pcmBuffer, sampleCount, frameIndex++));
            }

            driveTraceIndex++;
            previousDriveFrame = driveFrame;
        }
        return frameIndex;
    }

    /**
     * Clip capture: fast-forwards (drives the trace without rendering/recording)
     * until the clip's start event, records only the window, and stops a tail
     * after the clip's stop event. Supports {@code aiz-battleship-to-boss}: start
     * recording when the AIZ2 battleship auto-scroll activates
     * ({@link com.openggf.game.sonic3k.events.Sonic3kAIZEvents#isBattleshipAutoScrollActive()}),
     * stop when the engine issues the AIZ2 end-boss music fade
     * ({@code AizEndBossInstance} → {@link com.openggf.audio.AudioManager#fadeOutMusic()}),
     * then record {@code tailFrames} more so the fade is visible/audible. Returns
     * the captured (submitted) frame count.
     */
    private long driveClip(TraceData trace, RecordingFrameDriver frameDriver,
                           TraceReplayBootstrap.ReplayStartState replayStart,
                           GlReadPixelsGrabber grabber,
                           HeadlessOuterAudioFrames audioFrames,
                           CaptureRecorder recorder, String clipName, int tailFrames)
            throws Exception {
        if (!"aiz-battleship-to-boss".equals(clipName)) {
            throw new IllegalArgumentException("Unknown --clip '" + clipName
                    + "' (supported: aiz-battleship-to-boss)");
        }
        // The aiz1_to_hcz trace transitions AIZ1 -> AIZ2 mid-run, and the act load
        // recreates the Sonic3kAIZEvents instance (Sonic3kLevelEventManager:189), so
        // the live handler must be re-resolved each frame (never cached). The
        // battleship (and its auto-scroll flag) live on the AIZ2 instance.
        if (resolveAizEvents() == null) {
            throw new IllegalStateException(
                    "--clip aiz-battleship-to-boss requires an S3K AIZ trace");
        }

        short[] pcmBuffer = new short[16384];
        long frameIndex = 0;          // captured (submitted) frame count
        boolean capturing = false;
        long fadeBaseline = -1;
        long stopAtFrame = -1;        // captured-frame index at which to stop (after tail)

        int driveTraceIndex = replayStart.startingTraceIndex();
        TraceFrame previousDriveFrame = replayStart.hasSeededTraceState()
                ? trace.getFrame(replayStart.seededTraceIndex())
                : driveTraceIndex > 0 ? trace.getFrame(driveTraceIndex - 1) : null;

        System.out.println("clip aiz-battleship-to-boss: fast-forwarding to battleship start...");
        while (driveTraceIndex < trace.frameCount()) {
            TraceFrame driveFrame = trace.getFrame(driveTraceIndex);
            TraceExecutionPhase phase =
                    TraceReplayBootstrap.phaseForReplay(trace, previousDriveFrame, driveFrame);
            TraceReplayDrive.DriveOutcome outcome =
                    TraceReplayDrive.driveOneFrame(trace, frameDriver, replayStart, phase, driveTraceIndex);
            if (!outcome.consumedRow()) {
                continue;
            }

            if (outcome.gameplayFrame()
                    && TraceReplayBootstrap.shouldCompareGameplayStateForReplay(phase)) {
                if (!capturing) {
                    Sonic3kAIZEvents live = resolveAizEvents();
                    if (live != null && live.isBattleshipAutoScrollActive()) {
                        capturing = true;
                        fadeBaseline = GameServices.audio().musicFadeOutCount();
                        System.out.println("clip: battleship start -> capture begins at trace frame "
                                + driveFrame.frame());
                    }
                }
                // Exactly one presentation per outer frame the clip treats as
                // presented, whether or not the capture window is open.
                audioFrames.presentOuterFrame();
                if (capturing) {
                    TraceReplayDrive.renderFrame();
                    byte[] rgba = grabber.grab();
                    int sampleCount = audioFrames.drainCaptured(pcmBuffer);
                    recorder.submit(new CapturedFrame(rgba, SCREEN_WIDTH, SCREEN_HEIGHT,
                            pcmBuffer, sampleCount, frameIndex++));
                    if (stopAtFrame < 0
                            && GameServices.audio().musicFadeOutCount() > fadeBaseline) {
                        stopAtFrame = frameIndex + tailFrames;
                        System.out.println("clip: boss music fade at trace frame "
                                + driveFrame.frame() + " -> recording " + tailFrames
                                + " more frame(s)");
                    }
                    if (stopAtFrame >= 0 && frameIndex >= stopAtFrame) {
                        break;
                    }
                } else {
                    // Fast-forward: the packet was presented above, so drain and
                    // discard it. Leaving it undrained would carry a stale
                    // packet into the first captured frame of the clip.
                    audioFrames.discardPresented();
                }
            }

            driveTraceIndex++;
            previousDriveFrame = driveFrame;
        }
        if (!capturing) {
            System.out.println("clip: WARNING battleship start never detected; nothing captured");
        } else if (stopAtFrame < 0) {
            System.out.println("clip: WARNING boss music fade never detected; captured to end of "
                    + "trace (" + frameIndex + " frames)");
        }
        return frameIndex;
    }

    private Sonic3kAIZEvents resolveAizEvents() {
        if (GameServices.module().getLevelEventProvider()
                instanceof Sonic3kLevelEventManager s3kEvents) {
            return s3kEvents.getAizEvents();
        }
        return null;
    }

    /**
     * Headless self-verify: drive the trace to the requested frames and print
     * rings + camera_x. No GL capture. Confirms trace-faithfulness (e.g. AIZ
     * rings=97 / camera_x=0x443C at trace frame 16507).
     */
    private void runVerify(TraceData trace, TraceMetadata meta,
                           RecordingFrameDriver frameDriver,
                           TraceReplayBootstrap.ReplayStartState replayStart,
                           int[] verifyFrames) {
        int maxFrame = 0;
        for (int f : verifyFrames) {
            maxFrame = Math.max(maxFrame, f);
        }
        java.util.Set<Integer> wanted = new java.util.HashSet<>();
        for (int f : verifyFrames) {
            wanted.add(f);
        }

        int driveTraceIndex = replayStart.startingTraceIndex();
        TraceFrame previousDriveFrame = replayStart.hasSeededTraceState()
                ? trace.getFrame(replayStart.seededTraceIndex())
                : driveTraceIndex > 0 ? trace.getFrame(driveTraceIndex - 1) : null;

        System.out.println("=== VERIFY trajectory (rings, camera_x at requested frames) ===");
        while (driveTraceIndex < trace.frameCount() && driveTraceIndex <= maxFrame) {
            TraceFrame driveFrame = trace.getFrame(driveTraceIndex);
            TraceExecutionPhase phase =
                    TraceReplayBootstrap.phaseForReplay(trace, previousDriveFrame, driveFrame);
            TraceReplayDrive.DriveOutcome outcome =
                    TraceReplayDrive.driveOneFrame(trace, frameDriver, replayStart, phase, driveTraceIndex);
            if (!outcome.consumedRow()) {
                continue;
            }

            if (wanted.contains(driveFrame.frame())) {
                var sprite = GameServices.camera().getFocusedSprite();
                int rings = sprite.getRingCount();
                int camX = GameServices.camera().getX() & 0xFFFF;
                System.out.printf(
                        "VERIFY frame=%d rings=%d (0x%02X) camera_x=0x%04X x=%d y=%d phase=%s%n",
                        driveFrame.frame(), rings, rings, camX,
                        sprite.getCentreX(), sprite.getCentreY(), phase);
            }
            driveTraceIndex++;
            previousDriveFrame = driveFrame;
        }
        System.out.println("=== VERIFY complete ===");
    }

    /**
     * Resolves {@code --trace} against the catalog by directory name, or by
     * 0-based catalog index, or as a direct filesystem path to a trace dir.
     *
     * <p>Multi-segment trace <em>runs</em> ({@link TraceEntry#isRun()}) share
     * the catalog with ordinary single-segment traces but are not capturable:
     * a run drives the engine through mode changes (level to special/bonus stage
     * and back), which this single-scene capture pipeline does not follow. When
     * a spec resolves to a run, {@link #requireCapturable} rejects it up front
     * with a clear message instead of failing opaquely mid-capture — capture the
     * run's individual segments (each an ordinary trace) instead.
     */
    static TraceEntry resolveTrace(String spec) {
        Path catalogDir = Path.of(GameServices.configuration()
                .getString(SonicConfiguration.TRACE_CATALOG_DIR));
        List<TraceEntry> entries = TraceCatalog.scan(catalogDir);

        // by 0-based catalog index
        try {
            int index = Integer.parseInt(spec.trim());
            if (index >= 0 && index < entries.size()) {
                return requireCapturable(entries.get(index));
            }
        } catch (NumberFormatException ignored) {
            // not an index
        }

        // by trace directory name
        for (TraceEntry e : entries) {
            if (e.dir().getFileName().toString().equalsIgnoreCase(spec)) {
                return requireCapturable(e);
            }
        }

        // as a direct filesystem path to a trace dir
        Path asPath = Path.of(spec);
        for (TraceEntry e : TraceCatalog.scan(asPath.getParent() != null
                ? asPath.getParent() : asPath)) {
            if (e.dir().equals(asPath) || e.dir().toAbsolutePath().equals(asPath.toAbsolutePath())) {
                return requireCapturable(e);
            }
        }

        throw new IllegalArgumentException("No trace matched '" + spec
                + "' (catalog " + catalogDir + " has " + entries.size() + " entries)");
    }

    /**
     * Rejects a multi-segment trace run, which cannot be captured by this
     * single-scene pipeline; returns any ordinary trace entry unchanged.
     */
    static TraceEntry requireCapturable(TraceEntry entry) {
        if (entry.isRun()) {
            throw new IllegalArgumentException(
                    "Trace run '" + entry.dir().getFileName() + "' is a multi-segment run and is "
                    + "not capturable; capture its segments individually (each segment directory "
                    + "under the run is an ordinary trace).");
        }
        return entry;
    }

    private static String resolveFfmpeg() {
        return FfmpegEncoder.findFfmpeg()
                .map(Path::toString)
                .orElse("ffmpeg");
    }
}
