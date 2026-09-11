package com.openggf;

import com.openggf.audio.rewind.AudioPresentationPolicy;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.control.InputHandler;
import com.openggf.configuration.GlfwKeyNameResolver;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameMode;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameServices;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.SpecialStageStartupPolicy;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.session.GameplayTeamBootstrap;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.rewind.InputSource;
import com.openggf.game.rewind.PlaybackController;
import com.openggf.game.rewind.RewindController;
import com.openggf.game.rewind.RewindEffectEnvelope;
import com.openggf.game.rewind.RewindSeekAwareEngineStepper;
import com.openggf.graphics.PixelFontTextRenderer;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.shaderlib.RewindVhsEffectPass;
import com.openggf.sprites.ghost.GhostTraceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.level.objects.ObjectManager;
import com.openggf.testmode.TraceCameraFocusController;
import com.openggf.testmode.TracePlaybackSpeedController;
import com.openggf.testmode.TraceHudOverlay;
import com.openggf.testmode.SpecialStageTraceHudOverlay;
import com.openggf.testmode.TraceSessionOverlay;
import com.openggf.trace.TraceHudModel;
import com.openggf.testmode.TraceLaunchStatus;
import com.openggf.testmode.TraceRunFailureStatus;
import com.openggf.trace.SpecialStageTraceData;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.catalog.TraceEntry;
import com.openggf.trace.catalog.TraceCatalog;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.replay.TraceReplayDriver;
import com.openggf.trace.replay.TraceGhostHook;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.replay.TraceReplayRowPolicy;
import com.openggf.trace.replay.VisualTraceLaunchPhase;
import com.openggf.trace.replay.runs.ActiveSegmentPayload;
import com.openggf.trace.replay.runs.DestinationAdmissionReceipt;
import com.openggf.trace.replay.runs.RunBoundarySignal;
import com.openggf.trace.replay.runs.RunLevelLoadCause;
import com.openggf.trace.replay.runs.RunLevelLoadTracker;
import com.openggf.trace.replay.runs.RunPlaybackObservation;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator;
import com.openggf.trace.replay.runs.TraceRunFrameDriver;
import com.openggf.trace.replay.runs.TraceRunPresentationClosure;
import com.openggf.trace.replay.runs.TraceRunBoundaryComparator;
import com.openggf.trace.replay.runs.TraceRunDynamicArtGapJournal;
import com.openggf.trace.replay.runs.TraceRunExternalDiagnostics;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.trace.SpecialStageRunObjectsPassBinder;
import com.openggf.trace.replay.runs.SpecialStageRecordedPassPacing;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRows;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRowDriver;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRows.SpecialStageRowAdmission;
import com.openggf.trace.replay.runs.TraceStructuralRowComparator;
import com.openggf.trace.replay.runs.TraceRunVblankClock;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Drives a Trace Test Mode playback session. The picker calls
 * {@link #launch(TraceEntry)}; the launcher then asynchronously:
 * <ol>
 *   <li>asks {@link GameLoop#launchGameByEntry} to run the same
 *       master-title exit path as a user selecting the game,</li>
 *   <li>loads the trace's zone/act and shows the complete production title
 *       card without advancing replay, then</li>
 *   <li>adopts that same gameplay context, starts programmatic BK2 playback,
 *       applies {@link TraceReplaySessionBootstrap}, and attaches a
 *       {@link LiveTraceComparator} + HUD overlay.</li>
 * </ol>
 *
 * <p>The session ends either on BK2 exhaustion (after a 1-second hold
 * on {@code TRACE COMPLETE}) or on Esc, both converging on a single
 * fade-to-black → {@link #teardown()} → picker path.
 */
public final class TraceSessionLauncher {

    private static final Logger LOGGER =
            Logger.getLogger(TraceSessionLauncher.class.getName());

    /** Hold (in seconds at 60 Hz) on TRACE COMPLETE before auto-fade. */
    private static final double COMPLETION_HOLD_SECONDS = 1.0;

    private static TraceSessionLauncher activeSession;

    private final VisualTraceLaunchPhase launchPhase =
            new VisualTraceLaunchPhase();

    private final TraceEntry entry;
    /** Null for special-stage sessions (which use {@link #ssTrace} instead). */
    private final TraceData trace;
    private final Bk2Movie movie;
    /**
     * Special-stage trace payload; non-null only for an SS visual session
     * (parallel branch). Level sessions leave this null and drive the runtime
     * through the {@link TraceReplayDriver}/{@link LiveTraceComparator} stack
     * above. An SS session has no comparator/rewind machinery, so every
     * level-path method here is already null-guarded on {@link #comparator}.
     */
    private final TraceRunSpecialStageRows ssTrace;
    /** Absolute BK2 input-log row backing SS trace frame 0 (metadata offset). */
    private final int ssBk2FrameOffset;
    /** Last SS trace frame to play; the session fades out after it. */
    private final int ssStageFinishedFrame;
    /** Cursor into the SS trace; advances one row per engine frame (lag or not). */
    private int ssCursor;
    /**
     * Non-null only for a multi-segment run session (parallel branch). Each
     * entry is a compact descriptor; only {@link #activeRunPayload} owns the
     * eager graph for the represented segment. A run session drives
     * {@link #runCoordinator} from the all-mode GameLoop hook instead of the
     * LEVEL-only {@link #tick()} completion-hold. {@link #runAdvancer} remains
     * only for focused compatibility tests.
     */
    private List<TraceRunSegmentDescriptor> runSegments;
    private ActiveSegmentPayload activeRunPayload;

    @FunctionalInterface
    interface ActiveSegmentFactory {
        ActiveSegmentPayload open(TraceRunSegmentDescriptor descriptor,
                                  int segmentIndex) throws IOException;

        default void close(ActiveSegmentPayload payload) {
            payload.close();
        }
    }

    ActiveSegmentFactory activeSegmentFactory =
            TraceRunReplayWalker::openActiveSegment;
    private RunSegmentAdvancer runAdvancer;
    private TraceRunPlaybackCoordinator runCoordinator;
    private TraceRunFrameDriver runFrameDriver;
    private TraceRunFrameDriver.Disposition activeRunDisposition;
    /**
     * Latched once the source level's main loop has stopped owning the current
     * transition gap's rows. Monotone within a gap: a game's level-exit routine
     * never hands the rows back to the loop it left.
     */
    private boolean runGapSourceLevelMainLoopEnded;
    private TraceStructuralRowComparator runStructuralComparator;
    private TraceRunReplayWalker.BoundaryProbe runBoundaryProbe;
    private final List<TraceRunPlaybackCoordinator.Action>
            runCoordinatorTranscript = new ArrayList<>();
    private long runAdmittedStepOrdinal;
    private long runLevelLoadGeneration;
    private int runForwardedBoundarySegment = -1;
    private TraceRunSpecialStageRows runSpecialRows;
    private TraceRunSpecialStageRowDriver runSpecialRowDriver;
    private int runSpecialLocalRow;
    private int runSpecialVblankBefore;
    private boolean runSpecialInputApplied;
    /**
     * Recorded {@code RunObjects} pass stream for the active special-stage
     * segment. {@code SS_MainLoop} sets {@code VintID_S2SS}, waits on it, and
     * only then runs {@code RunObjects} (docs/s2disasm/s2.asm:6697-6698, 6721),
     * so the loop is paced by 68K pass duration: one V-blank observation owns
     * 0..n completed passes. Without this the production replay path ran
     * exactly one pass per admitted row and simply dropped the passes a slow
     * observation owned.
     */
    private SpecialStageRunObjectsPassBinder runSpecialPassBinder;
    private int runSpecialPassPacedFromRow = Integer.MAX_VALUE;
    private List<SpecialStageRunObjectsPassBinder.CompletedPass>
            runSpecialObservationPasses = List.of();
    private boolean runSpecialObservationPassesBound;
    private int runSpecialObservationRow = -1;
    private TraceRunReplayWalker.TerminalMovieTailPlan runTerminalTail;
    private boolean runTerminalRowAdvanced;
    private boolean runTerminalMovieEndReached;
    private boolean runTerminalTailCompared;
    /** True once the terminal-tail action has opened the final source gap. */
    private boolean runTerminalGapOpened;
    /**
     * The level production row that requests a Special Stage is also the
     * destination segment's BK2 offset. Keep the shared cursor on that row
     * until the destination admission installs its local clock.
     */
    private boolean runPhysicalRowAdvanceDeferred;
    private Bk2FrameInput runLastPhysicalInput;
    private InputHandler runOwnedInputHandler;
    private boolean runLevelLoadedDuringSourceProduction;
    private RunPlaybackObservation runProductionOwnerObservation;
    private Integer runProductionOwnerVblank;
    private TraceRunVblankClock runVblankClock;
    private TraceRunReplayWalker.HardwareTimingCoordinator runHardwareTiming;
    private TraceRunReplayWalker.DynamicArtSegmentController runDynamicArtSegments;
    private GameplayModeContext dynamicArtSegmentGameplayMode;
    private TraceRunDynamicArtGapJournal runDynamicArtGapJournal;
    private TraceRunExternalDiagnostics runExternalDiagnostics;
    private int runClosingDynamicArtSegment = -1;
    private int runSpecialTimingSegment = -1;
    private int runSpecialTimingRow;
    private int runSpecialDynamicArtPendingRow = -1;
    private long runSpecialDynamicArtTargetGeneration = -1;
    private TraceRunReplayWalker.DynamicArtSegmentComparison
            runSpecialDynamicArtComparison;
    private boolean runSpecialDynamicArtSegmentAnticipated;
    private int runAdvancerAnticipatedSegment = -1;
    /**
     * Snapshot of the user's gameplay-altering config taken before
     * {@link TraceReplaySessionBootstrap#prepareConfiguration} ran.
     * Restored in {@link #teardown()} so the picker returns to the
     * user's own team / cross-game / S3K_SKIP_INTROS preferences.
     */
    private final TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot;
    private LiveTraceComparator comparator;
    private TraceCameraFocusController cameraFocusController;
    private TraceSessionOverlay overlay;
    private final GhostTraceRenderer ghostRenderer = new GhostTraceRenderer();
    /** Stable hook ref so set/clear match by identity in {@link TraceGhostHook}. */
    private final TraceGhostHook.GhostLayerRenderer ghostHook = this::renderGhostsForLayer;
    private TraceReplayFixture fixture;
    private PlaybackController rewindPlaybackController;
    private RewindController rewindController;
    private int rewindMovieBaseFrame;
    private int rewindTraceBaseFrame;
    private boolean realtimeRewinding;
    private boolean realtimeReleasePending;
    private AudioPresentationPolicy pendingRealtimeReleasePolicy;

    private final TracePlaybackSpeedController playbackSpeed =
            new TracePlaybackSpeedController();
    private final RewindEffectEnvelope tapeEffectEnvelope =
            new RewindEffectEnvelope();
    private float tapeEffectScrollDirection =
            RewindVhsEffectPass.REWIND_SCROLL_DIRECTION;

    private boolean completionArmed;
    private int completionHoldFrames;
    private boolean fadeStarted;
    private boolean specialStageTerminalExitPending;
    private boolean teardownPending;
    private boolean productionIterationInProgress;
    private boolean runEndPending;
    private boolean runGapEntryPending;
    private boolean runSpecialVerificationPending;
    private RunSegmentAdvancer.AdvanceAction runAdvancePending;
    private boolean completionStartPending;
    private long dynamicArtDeliverySerialBeforeIteration;
    private DynamicArtDiagnosticsSnapshot dynamicArtSnapshotBeforeIteration;
    private LiveTraceComparator productionIterationComparator;

    private TraceSessionLauncher(TraceEntry entry, TraceData trace, Bk2Movie movie,
                                 TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot) {
        this.entry = entry;
        this.trace = trace;
        this.movie = movie;
        this.configSnapshot = configSnapshot;
        this.ssTrace = null;
        this.ssBk2FrameOffset = 0;
        this.ssStageFinishedFrame = 0;
        this.ssCursor = 0;
    }

    /**
     * Special-stage visual session constructor (parallel to the level
     * constructor). Package-private so the skip-gate test can build a session
     * from the committed MVP trace without a live engine. The stage-finished
     * boundary comes from the trace's {@code stage_finished} aux event (or the
     * final frame if absent).
     */
    TraceSessionLauncher(TraceEntry entry, Bk2Movie movie, SpecialStageTraceData ssTrace,
                         TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot) {
        this(entry, movie, TraceRunSpecialStageRows.forS2(ssTrace), configSnapshot);
    }

    TraceSessionLauncher(TraceEntry entry, Bk2Movie movie,
                         TraceRunSpecialStageRows ssTrace,
                         TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot) {
        this.entry = entry;
        this.trace = null;
        this.movie = movie;
        this.configSnapshot = configSnapshot;
        this.ssTrace = ssTrace;
        this.ssBk2FrameOffset = entry.metadata().bk2FrameOffset();
        this.ssStageFinishedFrame =
                ssTrace.terminalRow().orElse(ssTrace.rowCount() - 1);
        this.ssCursor = 0;
    }

    /**
     * Multi-segment run session constructor (parallel to the level/SS
     * constructors). {@link #trace} is left null — {@link #finishRunLaunch}
     * and each segment advance read eager data only through the active lease.
     */
    TraceSessionLauncher(TraceEntry entry, Bk2Movie movie,
                         List<TraceRunSegmentDescriptor> runSegments,
                         ActiveSegmentPayload activeRunPayload,
                         TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot) {
        this.entry = entry;
        this.trace = null;
        this.movie = movie;
        this.configSnapshot = configSnapshot;
        this.ssTrace = null;
        this.ssBk2FrameOffset = 0;
        this.ssStageFinishedFrame = 0;
        this.ssCursor = 0;
        this.runSegments = List.copyOf(runSegments);
        if (activeRunPayload != null
                && (this.runSegments.isEmpty()
                        || activeRunPayload.descriptor()
                                != this.runSegments.getFirst())) {
            throw new IllegalArgumentException(
                    "initial payload must own descriptor zero");
        }
        this.activeRunPayload = activeRunPayload;
    }

    public static TraceSessionLauncher active() {
        return activeSession;
    }

    public static boolean launch(TraceEntry entry) {
        GameLoop loop = Engine.currentGameLoop();
        if (loop == null) {
            LOGGER.severe("Cannot launch trace " + entry.dir()
                    + ": Engine is not initialised");
            TraceLaunchStatus.record(entry, "Engine is not initialised");
            return false;
        }
        // prepareConfiguration must run BEFORE launchGameByEntry
        // because the master-title exit handler calls
        // GameplayTeamBootstrap.registerActiveTeam, which reads
        // MAIN_CHARACTER_CODE / SIDEKICK_CHARACTER_CODE to build the
        // sprites for this session. If we deferred the write until
        // after the handler, the session would use the pre-trace team.
        //
        // Pre-flight the fade check via GameLoop so we don't mutate
        // config and then fail at launchGameByEntry with a
        // fade-active throw. GameServices.fade() isn't usable here —
        // no gameplay mode exists at master-title time — so go through
        // GameLoop which resolves the graphics-backed fade manager.
        if (!loop.canLaunchGameNow()) {
            LOGGER.severe("Cannot launch trace " + entry.dir()
                    + ": a master-title fade is already in flight");
            TraceLaunchStatus.record(entry,
                    "A master-title fade is already in progress");
            return false;
        }
        // Snapshot the user's gameplay config BEFORE prepareConfiguration
        // mutates it, so teardown can restore the team / cross-game /
        // S3K_SKIP_INTROS preferences the user actually had.
        clearLaunchSessionOverridesBeforeTraceSnapshot(GameServices.configuration());
        TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot =
                TraceReplaySessionBootstrap.snapshotGameplayConfig();
        // Multi-segment trace runs take their own branch BEFORE the
        // special-stage profile check below: a run's entry.metadata() is
        // segment 0's metadata, which may itself carry the SS trace_profile
        // (a run can start with a special-stage segment) and must not be
        // hijacked into the single-segment SS path.
        if (entry.isRun()) {
            return launchRun(entry, loop, configSnapshot);
        }
        // Special-stage traces (no meaningful zone/act) take a parallel branch:
        // they skip the level driver stack entirely and drive the SS runtime
        // directly through GameLoop's SPECIAL_STAGE update.
        if (isSpecialStageProfile(entry.metadata().traceProfile())) {
            return launchSpecialStage(entry, loop, configSnapshot);
        }
        boolean configMutated = false;
        try {
            TraceData trace = TraceData.load(entry.dir());
            Bk2Movie movie = new Bk2MovieLoader().load(entry.bk2Path());
            TraceSessionLauncher session = new TraceSessionLauncher(
                    entry, trace, movie, configSnapshot);
            TraceReplaySessionBootstrap.prepareConfiguration(trace, trace.metadata());
            configMutated = true;
            // This context owns both title-card presentation and replay. Its
            // live timing epoch is converted in place after control release.
            SessionManager.armNextGameplayAdmissionPolicy(
                    HardwareReadinessAdmissionPolicy.LIVE);
            loop.launchGameByEntry(
                    resolveGameEntry(entry.gameId()),
                    session::finishLaunchAfterGameBootstrap);
            return true;
        } catch (Exception e) {
            TraceLaunchStatus.record(entry, e);
            LOGGER.log(java.util.logging.Level.SEVERE,
                    "Failed to launch trace " + entry.dir(), e);
            // If we already mutated config before launchGameByEntry
            // threw, restore the user's settings so the picker
            // resumes with their preferences intact.
            restoreFailedLaunch(configSnapshot, configMutated);
            return false;
        }
    }

    /**
     * Parallel launch path for a multi-segment trace run. Boots the game
     * module against segment 0's zone/act exactly like the ordinary level
     * branch (segment 0's zone/act are already engine-converted by
     * {@link TraceEntry#forRun}), then {@link #finishRunLaunch} takes over
     * from the game-bootstrap callback and additionally stores the planned
     * segment list the shared {@link TraceRunPlaybackCoordinator} walks.
     */
    private static boolean launchRun(TraceEntry entry, GameLoop loop,
            TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot) {
        boolean configMutated = false;
        ActiveSegmentPayload localPayload = null;
        TraceSessionLauncher session = null;
        try {
            TraceCatalog.PreparedDescriptorRunLaunch prepared =
                    TraceCatalog.prepareDescriptorRunLaunch(entry);
            List<TraceRunSegmentDescriptor> segments = prepared.segments();
            localPayload = TraceRunReplayWalker.openActiveSegment(
                    segments.getFirst(), 0);
            TraceData seg0Trace = localPayload.trace();
            Bk2Movie movie = prepared.movie();
            session = new TraceSessionLauncher(
                    entry, movie, segments, localPayload, configSnapshot);
            localPayload = null;
            TraceReplaySessionBootstrap.prepareConfiguration(seg0Trace, seg0Trace.metadata());
            configMutated = true;
            // This context owns both title-card presentation and replay. Its
            // live timing epoch is converted in place after control release.
            SessionManager.armNextGameplayAdmissionPolicy(
                    HardwareReadinessAdmissionPolicy.LIVE);
            loop.launchGameByEntry(
                    resolveGameEntry(entry.gameId()),
                    session::finishRunLaunch);
            return true;
        } catch (Throwable failure) {
            if (session != null) {
                failure = session.detachAndCloseRunPayload(failure);
            } else if (localPayload != null) {
                localPayload.close();
            }
            TraceLaunchStatus.record(entry, failure);
            LOGGER.log(java.util.logging.Level.SEVERE,
                    "Failed to launch trace run " + entry.dir(), failure);
            restoreFailedLaunch(configSnapshot, configMutated);
            if (failure instanceof Error error) {
                throw error;
            }
            return false;
        }
    }

    /**
     * Parallel launch path for a Sonic 2 special-stage visual session. Boots
     * the game module the same way the level path does (so sprites / gameplay
     * mode / ROM are wired), then enters SPECIAL_STAGE mode directly from the
     * callback instead of loading a zone/act.
     */
    private static boolean launchSpecialStage(TraceEntry entry, GameLoop loop,
            TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot) {
        boolean configMutated = false;
        try {
            TraceRunSpecialStageRows ssTrace = TraceRunSpecialStageRows.load(
                    entry.metadata().traceProfile(), entry.dir());
            Bk2Movie movie = new Bk2MovieLoader().load(entry.bk2Path());
            TraceSessionLauncher session =
                    new TraceSessionLauncher(entry, movie, ssTrace, configSnapshot);
            prepareSpecialStageConfiguration(entry.metadata());
            configMutated = true;
            armSpecialStageAdmissionPolicy(ssTrace);
            loop.launchGameByEntry(
                    resolveGameEntry(entry.gameId()),
                    session::finishSpecialStageLaunch);
            return true;
        } catch (Exception e) {
            TraceLaunchStatus.record(entry, e);
            LOGGER.log(java.util.logging.Level.SEVERE,
                    "Failed to launch SS trace " + entry.dir(), e);
            restoreFailedLaunch(configSnapshot, configMutated);
            return false;
        }
    }

    static void restoreFailedLaunch(
            TraceReplaySessionBootstrap.ConfigSnapshot configSnapshot,
            boolean configMutated) {
        SessionManager.clearNextGameplayAdmissionPolicy();
        if (configMutated) {
            TraceReplaySessionBootstrap.restoreGameplayConfig(configSnapshot);
        }
    }

    static void armSpecialStageAdmissionPolicy(SpecialStageTraceData trace) {
        armSpecialStageAdmissionPolicy(TraceRunSpecialStageRows.forS2(trace));
    }

    static void armSpecialStageAdmissionPolicy(TraceRunSpecialStageRows trace) {
        SessionManager.armNextGameplayAdmissionPolicy(
                trace.hardwareTimingSchedule().hasRecordedInput()
                        ? HardwareReadinessAdmissionPolicy.RECORDED
                        : HardwareReadinessAdmissionPolicy.LIVE);
    }

    private static boolean isSpecialStageProfile(String profile) {
        return "s1_special_stage".equals(profile)
                || "s2_special_stage".equals(profile)
                || "s3k_special_stage".equals(profile);
    }

    /**
     * Applies per-game special-stage configuration for trace replay. Routes
     * through the static helper to centralize the shared team / cross-game /
     * S3K fresh-load policy. Reads the fresh_load metadata field to determine
     * whether to override the S3K intro-skip gate.
     *
     * @param meta the trace metadata (provides recorded team, game id, and fresh_load flag)
     */
    static void prepareSpecialStageConfiguration(TraceMetadata meta) {
        SonicConfigurationService config = GameServices.configuration();
        applyPerGameSpecialStageConfig(config, meta, meta.isFreshLoad());
    }

    /**
     * Static helper for per-game special-stage launch configuration. Applies
     * the shared team + cross-game settings, plus the S3K fresh-load branch
     * when applicable (mirroring {@link TraceReplaySessionBootstrap#prepareConfiguration}).
     *
     * <p>For S1: no special intro-skip behavior applies to special stages.
     *
     * @param config the configuration service to mutate
     * @param meta the trace metadata (provides recorded team and game id)
     * @param freshLoadSignal true when the trace requires a fresh level load
     *     (S3K only; false is the safe default until blue-spheres plan wires
     *     the real signal)
     */
    static void applyPerGameSpecialStageConfig(SonicConfigurationService config,
                                               TraceMetadata meta,
                                               boolean freshLoadSignal) {
        // Team: the recorded trace dictates the team.
        String main = meta.recordedMainCharacter();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                main == null || main.isBlank() ? "sonic" : main);
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                String.join(",", meta.recordedSidekicks()));

        // Cross-game donation wasn't recorded; always force it off so
        // trace physics/visuals match the base ROM.
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);

        // S3K: apply the fresh-load intro-skip branch when a trace
        // requires a fresh level load. This mirrors the level-mode branch
        // in TraceReplaySessionBootstrap.prepareConfiguration. S1 has no
        // intro-skip behavior in special stages.
        if (freshLoadSignal && "s3k".equals(meta.game())) {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        }
    }

    private void finishSpecialStageLaunch() {
        GameLoop loop = Engine.currentGameLoop();
        if (loop == null) {
            abortLaunchFailure(
                    null,
                    "special-stage trace callback followed engine teardown",
                    null,
                    "Failed to abort SS trace after engine teardown");
            return;
        }
        finishSpecialStageLaunch(loop);
    }

    void finishSpecialStageLaunch(GameLoop loop) {
        try {
            this.fixture = new LiveFixture(GameServices.playbackDebug(), loop);
            installSpecialStageHardwareTiming(fixture);
            configureStandaloneSpecialStageAudio();
            this.overlay = new SpecialStageTraceHudOverlay(
                    createStandaloneSpecialStageHudModel(),
                    () -> ssTrace.metadata().traceProfile()
                            .replace('_', ' ').toUpperCase(java.util.Locale.ROOT),
                    () -> null,
                    this::rewindStatusLabel);
            // Mark active before entering so any entry-time rewind recording is
            // suppressed (GameLoop gates SS capture on active() == null).
            becomeActiveSession();
            SpecialStageProvider provider = GameServices.module().getSpecialStageProvider();
            Integer index = ssTrace.metadata().specialStageIndex();
            enterSpecialStageTrace(loop, provider, index != null ? index : 0);
            launchPhase.markActive();
        } catch (Throwable launchFailure) {
            abortLaunchFailure(
                    launchFailure,
                    "special-stage visual trace launch failed",
                    loop,
                    "Failed to finish SS trace launch for " + entry.dir());
        }
    }

    void configureStandaloneSpecialStageAudio() throws IOException {
        var audio = GameServices.audio();
        var profile = GameServices.module().getAudioProfile();
        audio.setAudioProfile(profile);
        var rom = GameServices.rom().getRom();
        if (rom != null) {
            audio.setRom(rom);
        }
        audio.setSoundMap(profile.getSoundMap());
        audio.resetRingSound();
    }

    private boolean isCurrentSpecialStageRowLagged() {
        return ssCursor >= 0 && ssCursor < ssTrace.rowCount()
                && !ssTrace.admission(ssCursor).executeGameplay();
    }

    private TraceHudModel createStandaloneSpecialStageHudModel() {
        return new TraceHudModel() {
            @Override public int errorCount() { return 0; }
            @Override public int warningCount() { return 0; }
            @Override
            public int laggedFrames() {
                int count = 0;
                for (int i = 0; i < Math.min(ssCursor, ssTrace.rowCount()); i++) {
                    if (!ssTrace.admission(i).executeGameplay()) count++;
                }
                return count;
            }
            @Override
            public int recentActionMask() {
                return standaloneInput().p1ActionMask();
            }
            @Override
            public int recentInputMask() {
                return standaloneInput().p1InputMask();
            }
            @Override
            public boolean recentStartPressed() {
                return standaloneInput().p1StartPressed();
            }
            @Override public List<com.openggf.trace.live.MismatchEntry> recentMismatches() {
                return List.of();
            }
            @Override public boolean hasRecordingDesync() { return false; }
            @Override public boolean isComplete() {
                return ssCursor >= ssTrace.rowCount();
            }
            private Bk2FrameInput standaloneInput() {
                int index = ssBk2FrameOffset + Math.max(0, ssCursor - 1);
                return index >= 0 && index < movie.getFrameCount()
                        ? movie.getFrame(index)
                        : new Bk2FrameInput(index, 0, 0, false, "");
            }
        };
    }

    interface TitleCardPresentation {
        void prepareLevel() throws Exception;

        void enterTitleCard() throws Exception;
    }

    /** Starts the visible prelude before any replay observer is installed. */
    void beginTitleCardPresentation(TitleCardPresentation presentation)
            throws Exception {
        Objects.requireNonNull(presentation, "presentation");
        presentation.prepareLevel();
        launchPhase.beginTitleCardPresentation();
        becomeActiveSession();
        presentation.enterTitleCard();
    }

    boolean isPresentingTitleCard() {
        return launchPhase.isPresentingTitleCard();
    }

    static boolean claimTitleCardControlReleaseBarrierIfActive() {
        TraceSessionLauncher session = active();
        return session != null
                && session.launchPhase.claimTitleCardControlRelease();
    }

    boolean beginReplayBootstrapAfterTitleCardIfReady(GameMode mode) {
        return launchPhase.beginReplayBootstrapIfReady(mode);
    }

    private void beginTitleCardPresentationAfterGameBootstrap() {
        GameLoop loop = Engine.currentGameLoop();
        if (loop == null) {
            abortLaunchFailure(
                    null,
                    "visual trace title-card callback followed engine teardown",
                    null,
                    "Failed to abort trace after engine teardown");
            return;
        }
        try {
            beginTitleCardPresentation(new TitleCardPresentation() {
                @Override
                public void prepareLevel() throws Exception {
                    TraceReplaySessionBootstrap.resetLevelSubsystemsForReplay();
                    GameplayTeamBootstrap.registerActiveTeam(
                            GameServices.module(),
                            GameServices.sprites(),
                            GameServices.configuration());
                    GameServices.level().loadZoneAndActForFreshRuntime(
                            entry.zone(), entry.act());
                    loop.setGameMode(GameMode.LEVEL);
                    GameServices.level().consumeTitleCardRequest();
                    GameServices.level().consumeInLevelTitleCardRequest();
                }

                @Override
                public void enterTitleCard() {
                    loop.enterTitleCard(entry.zone(), entry.act());
                }
            });
        } catch (Throwable launchFailure) {
            abortLaunchFailure(
                    launchFailure,
                    "visual trace title-card presentation failed",
                    loop,
                    "Failed to present trace title card for " + entry.dir());
        }
    }

    static void enterSpecialStageTrace(
            GameLoop loop, SpecialStageProvider provider, int stageIndex) {
        loop.doEnterSpecialStage(provider, stageIndex, true,
                SpecialStageStartupPolicy.TRACE_ACCURATE);
        // Compatibility notification only; trace-row scheduling remains authoritative.
        provider.setLagCompensation(0);
    }

    private void finishLaunchAfterGameBootstrap() {
        beginTitleCardPresentationAfterGameBootstrap();
    }

    private void finishStandaloneReplayLaunch(GameLoop loop) {
        PlaybackDebugManager playback = GameServices.playbackDebug();
        try {
            // TraceReplayDriver owns shared playback/comparator activation;
            // this visual entry point adopts the already loaded production
            // level instead of running its reset/load entry point.
            this.fixture = new LiveFixture(playback, loop);
            installDynamicArtSegments(fixture.gameplayMode());
            TraceReplayDriver driver = new TraceReplayDriver(
                    trace, movie, fixture, loop, loop::getMainPlayableSprite);
            driver.startPreparedLevel();

            int startIndex = driver.recordingStartFrame();
            int initialCursor = driver.initialCursor();
            this.comparator = driver.comparator();
            playback.setFrameObserver(comparator);
            this.cameraFocusController = new TraceCameraFocusController(
                    comparator,
                    loop::getMainPlayableSprite,
                    () -> {
                        var sprites = GameServices.spritesOrNull();
                        if (sprites == null) return null;
                        var sks = sprites.getSidekicks();
                        return sks.isEmpty() ? null : sks.get(0);
                    },
                    GameServices::camera,
                    GameServices.configuration(),
                    loop::isPaused);
            loop.setTraceCameraFocusController(cameraFocusController);
            this.overlay = new TraceHudOverlay(comparator,
                    () -> cameraFocusController.currentLabel(),
                    this::rewindStatusLabel);
            // TraceReplayDriver.startPreparedLevel already attached the
            // comparator as the playback frame observer.
            installTraceRewindController(loop, startIndex, initialCursor);
            becomeActiveSession();
            TraceGhostHook.set(ghostHook);
            launchPhase.markActive();
        } catch (Throwable launchFailure) {
            abortLaunchFailure(
                    launchFailure,
                    "visual trace launch failed",
                    loop,
                    "Failed to finish trace launch for " + entry.dir());
        }
    }

    /**
     * Multi-segment run launch callback. Mirrors
     * {@link #finishLaunchAfterGameBootstrap} EXCEPT it never calls
     * {@link #installTraceRewindController}: the stepper/base-frame capture
     * assumes a single fixed segment and would silently rewind against the
     * wrong segment after a mid-run re-seek, so {@link #rewindController}
     * stays null for the whole run session (GameLoop's realtime-rewind
     * engagement then no-ops). Per-segment rewind support is a documented
     * follow-up, not silently-wrong behavior. Additionally installs the
     * shared run coordinator that walks {@link #runSegments} from the
     * all-mode GameLoop hook.
     */
    private void finishRunLaunch() {
        beginTitleCardPresentationAfterGameBootstrap();
    }

    private void finishRunReplayLaunch(GameLoop loop) {
        PlaybackDebugManager playback = GameServices.playbackDebug();
        try {
            TraceData seg0Trace = activeRunTrace(0);
            this.fixture = new LiveFixture(playback, loop);
            installDynamicArtSegments(fixture.gameplayMode());
            this.runExternalDiagnostics =
                    new TraceRunExternalDiagnostics(loop::toggleUserPause);
            TraceReplayDriver driver = new TraceReplayDriver(
                    seg0Trace, movie, fixture, loop, loop::getMainPlayableSprite,
                    loop::toggleUserPause,
                    TraceRunReplayWalker.hasDescriptorHardwareTimingStream(
                            runSegments),
                    runExternalDiagnostics::acceptDisplayed);
            driver.startPreparedLevel();

            this.comparator = driver.comparator();
            runExternalDiagnostics.acceptBootstrap(
                    comparator.bootstrapDivergences());
            this.runHardwareTiming =
                    new TraceRunReplayWalker.HardwareTimingCoordinator(
                            fixture,
                            TraceRunReplayWalker
                                    .descriptorHardwareTimingSegments(runSegments),
                            com.openggf.trace.timing
                                    .HardwareTimingInterstitialStreamLoader
                                    .load(entry.runDir()));
            this.runCoordinator = TraceRunPlaybackCoordinator.fromDescriptors(
                    entry.runManifest(),
                    GameServices.module().getTracePlaybackProfile(),
                    movie.getFrameCount(), runSegments);
            this.runFrameDriver = new TraceRunFrameDriver();
            fixture.gameplayMode().installTraceRunFrameDriver(runFrameDriver);
            this.runVblankClock = new TraceRunVblankClock(
                    GameServices.module().getTracePlaybackProfile());
            this.runBoundaryProbe = createRunBoundaryProbe(loop);
            runBoundaryProbe.setDelegate(comparator);
            playback.setFrameObserver(runBoundaryProbe);
            this.runLevelLoadGeneration =
                    GameServices.level().getCompletedProductionLoadGeneration();
            runCoordinatorTranscript.addAll(runCoordinator.activateInitialLevel(
                    captureRunObservation(loop.getCurrentGameMode(), 0, false)));
            fixture.gameplayMode().runLevelLoads()
                    .prime(GameServices.level());
            armCurrentRunBoundary();
            this.cameraFocusController = new TraceCameraFocusController(
                    comparator,
                    loop::getMainPlayableSprite,
                    () -> {
                        var sprites = GameServices.spritesOrNull();
                        if (sprites == null) return null;
                        var sks = sprites.getSidekicks();
                        return sks.isEmpty() ? null : sks.get(0);
                    },
                    GameServices::camera,
                    GameServices.configuration(),
                    loop::isPaused);
            loop.setTraceCameraFocusController(cameraFocusController);
            this.overlay = new TraceHudOverlay(createRunHudModel(comparator),
                    () -> cameraFocusController.currentLabel(),
                    this::rewindStatusLabel);
            // TraceReplayDriver.startPreparedLevel already attached the
            // comparator as the playback frame observer. No
            // installTraceRewindController call here — see method javadoc.
            becomeActiveSession();
            TraceGhostHook.set(ghostHook);
            launchPhase.markActive();
        } catch (Throwable launchFailure) {
            abortLaunchFailure(
                    launchFailure,
                    "visual trace run launch failed",
                    loop,
                    "Failed to finish trace run launch for " + entry.dir());
        }
    }

    private TraceRunReplayWalker.BoundaryProbe createRunBoundaryProbe(GameLoop loop) {
        return new TraceRunReplayWalker.BoundaryProbe(
                new TraceRunReplayWalker.EngineHooks() {
                    @Override
                    public int currentBk2Frame() {
                        return GameServices.playbackDebug().getCursorFrame();
                    }

                    @Override
                    public BonusStageType peekBonusRequest() {
                        return GameServices.level().getTransitions()
                                .peekBonusStageRequest();
                    }

                    @Override
                    public boolean isSpecialStageRequested() {
                        return GameServices.level().getTransitions()
                                .isSpecialStageRequested();
                    }

                    @Override
                    public GameMode currentMode() {
                        return loop.getCurrentGameMode();
                    }
                });
    }

    private void armCurrentRunBoundary() {
        if (runBoundaryProbe == null || runCoordinator == null) {
            return;
        }
        int index = runCoordinator.currentSegmentIndex();
        runBoundaryProbe.arm(index >= 0 && index < runSegments.size()
                ? runSegments.get(index).exitBoundary() : null);
        runForwardedBoundarySegment = -1;
    }

    private RunPlaybackObservation captureRunObservation(
            GameMode mode, int rowsConsumed, boolean lagOnlyContinuation) {
        var levelManager = GameServices.level();
        return captureRunObservation(mode, rowsConsumed, lagOnlyContinuation,
                levelManager.isTitleCardRequested());
    }

    private RunPlaybackObservation captureRunObservation(
            GameMode mode, int rowsConsumed, boolean lagOnlyContinuation,
            boolean initialTitleCardPending) {
        var levelManager = GameServices.level();
        RunPlaybackObservation.LevelIdentity levelIdentity =
                levelManager.getCurrentLevel() != null
                        ? new RunPlaybackObservation.LevelIdentity(
                                runLevelLoadGeneration,
                                levelManager.getCurrentZone(),
                                levelManager.getRomZoneId(),
                                levelManager.getCurrentAct())
                        : null;
        RunPlaybackObservation.BonusIdentity bonusIdentity = null;
        var bonus = GameServices.bonusStageOrNull();
        if (mode == GameMode.BONUS_STAGE && bonus != null
                && bonus.getActiveType() != BonusStageType.NONE) {
            bonusIdentity = new RunPlaybackObservation.BonusIdentity(
                    levelManager.getRomZoneId(), levelManager.getCurrentAct(),
                    bonus.getActiveType());
        }
        Integer specialStageIndex =
                RunPlaybackObservation.insideRecordedSpecialStageMode(mode)
                        ? getActiveSpecialStageIndex(mode) : null;
        long dynamicGeneration = GameServices.captureDynamicArtDiagnostics()
                .segmentGeneration();
        return new RunPlaybackObservation(
                mode,
                Math.max(0, GameServices.playbackDebug().getCursorFrame()),
                runAdmittedStepOrdinal,
                levelIdentity,
                initialTitleCardPending,
                bonusIdentity,
                specialStageIndex,
                productionIterationInProgress,
                isCurrentRunSegmentExhausted(),
                rowsConsumed,
                lagOnlyContinuation,
                Math.max(0, currentRunSegmentIndex()),
                dynamicGeneration);
    }

    private Integer getActiveSpecialStageIndex(GameMode mode) {
        // The stage identity a recorded segment is cut on, which the live
        // provider stops reporting once the engine's results phase
        // deinitialises it -- see GameLoop#recordedSpecialStageIdentity.
        GameLoop loop = Engine.currentGameLoop();
        return loop != null ? loop.recordedSpecialStageIdentity(mode) : null;
    }

    private int currentRunSegmentIndex() {
        if (runCoordinator != null) {
            return runCoordinator.currentSegmentIndex();
        }
        return runAdvancer != null ? runAdvancer.currentSegmentIndex() : -1;
    }

    private TraceData activeRunTrace(int segmentIndex) {
        return requireActiveRunPayload(segmentIndex).trace();
    }

    private TraceRunSpecialStageRows activeRunSpecialRows(int segmentIndex) {
        return requireActiveRunPayload(segmentIndex).specialStageRows();
    }

    private ActiveSegmentPayload requireActiveRunPayload(int segmentIndex) {
        ActiveSegmentPayload payload = activeRunPayload;
        if (payload == null) {
            throw new IllegalStateException(
                    "run segment " + segmentIndex + " has no active payload");
        }
        TraceRunSegmentDescriptor expected = runSegments.get(segmentIndex);
        if (payload.descriptor() != expected) {
            throw new IllegalStateException(
                    "active payload does not own run segment " + segmentIndex);
        }
        return payload;
    }

    private void openRunPayload(int segmentIndex) {
        if (activeRunPayload != null) {
            throw new IllegalStateException(
                    "cannot open run segment " + segmentIndex
                            + " while another payload is active");
        }
        TraceRunSegmentDescriptor descriptor = runSegments.get(segmentIndex);
        ActiveSegmentPayload opened;
        try {
            opened = activeSegmentFactory.open(descriptor, segmentIndex);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to open run segment " + segmentIndex, e);
        }
        if (opened == null) {
            throw new IllegalStateException(
                    "active segment factory returned null for " + segmentIndex);
        }
        try {
            if (opened.descriptor() != descriptor) {
                throw new IllegalStateException(
                        "active segment factory returned the wrong descriptor for "
                                + segmentIndex);
            }
            activeRunPayload = opened;
        } catch (RuntimeException | Error failure) {
            Throwable closed = cleanupFailure(failure,
                    () -> activeSegmentFactory.close(opened));
            throwUnchecked(closed);
        }
    }

    private boolean isCurrentRunSegmentExhausted() {
        int index = currentRunSegmentIndex();
        if (index < 0 || index >= runSegments.size()) {
            return false;
        }
        if ("special_stage".equals(runSegments.get(index).segment().kind())) {
            return runSpecialRowDriver != null
                    ? runSpecialRowDriver.isComplete()
                    : runSpecialRows != null
                            && runSpecialLocalRow >= runSpecialRows.rowCount();
        }
        if (runSegments.get(index).executionPolicy()
                == TraceRunReplayWalker.SegmentExecutionPolicy
                        .LEVEL_PRESENTATION_BRIDGE) {
            return runStructuralComparator != null
                    && runStructuralComparator.allRowsConsumed();
        }
        return comparator != null && comparator.isComplete();
    }

    private void abortLaunchFailure(
            Throwable primary,
            String reason,
            GameLoop fallbackLoop,
            String logMessage) {
        Throwable failure = abortIncompleteSession(primary, reason, fallbackLoop);
        if (entry != null) {
            if (failure != null) {
                TraceLaunchStatus.record(entry, failure);
            } else {
                TraceLaunchStatus.record(entry, reason);
            }
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        if (failure != null) {
            LOGGER.log(java.util.logging.Level.SEVERE, logMessage, failure);
        }
    }

    /** True for a special-stage visual session (parallel branch). */
    public boolean isSpecialStageSession() {
        return ssTrace != null;
    }

    /** True for a multi-segment run session (parallel branch). */
    public boolean isRunSession() {
        return runSegments != null;
    }

    /**
     * True when the current SS trace row is a lag frame, so GameLoop must not
     * run {@code updateSpecialStageInput()} / {@code ssProvider.update()} this
     * engine frame (the recorded input replaces live input; a lag row advances
     * nothing engine-side). Always false for level sessions.
     */
    public boolean shouldSkipCurrentSpecialStageTick() {
        if (fadeStarted) {
            return ssTrace != null && (specialStageTerminalExitPending
                    || teardownPending);
        }
        if (ssTrace != null) {
            return ssCursor >= 0 && ssCursor < ssTrace.rowCount()
                    && !ssTrace.admission(ssCursor).executeGameplay();
        }
        return currentRunSpecialAdmission()
                .map(admission -> !admission.executeGameplay()
                        && !currentRunSpecialRowOwnsCompletedPass())
                .orElse(false);
    }

    /** PLC lifecycle represented by a skipped visual special-stage row. */
    public Optional<com.openggf.game.resources.PlcLifecyclePhase>
            skippedSpecialStagePlcPhase() {
        if (ssTrace != null && shouldSkipCurrentSpecialStageTick()) {
            return Optional.of(com.openggf.game.resources.PlcLifecyclePhase.LAG);
        }
        return currentRunSpecialAdmission()
                .flatMap(SpecialStageRowAdmission::syntheticPlcPhase);
    }

    /**
     * Installs the recorded BK2 input for the current SS trace row as the
     * logical override, so GameLoop's {@code updateSpecialStageInput()} forwards
     * recorded input to the provider. Press-edge detection diffs against the
     * immediately preceding <em>physical</em> BK2 row (never the last stepped
     * row), matching the headless harness. No-op for level sessions.
     */
    public void applySpecialStageTraceInputIfActive(InputHandler input) {
        if (fadeStarted || input == null) {
            return;
        }
        int physicalRow;
        if (ssTrace != null) {
            physicalRow = ssBk2FrameOffset + ssCursor;
        } else if (currentRunSpecialAdmission().isPresent()) {
            physicalRow = runSegments.get(currentRunSegmentIndex()).segment()
                    .bk2FrameOffset() + currentRunSpecialRow();
            var objects = GameServices.level().getObjectManager();
            runSpecialVblankBefore = objects != null ? objects.getVblaCounter() : 0;
            runSpecialInputApplied = true;
            bindRunSpecialObservationPasses();
        } else {
            return;
        }
        Bk2FrameInput current = movie.getFrame(physicalRow);
        int prevIndex = physicalRow - 1;
        Bk2FrameInput previous = prevIndex >= 0 && prevIndex < movie.getFrameCount()
                ? movie.getFrame(prevIndex)
                : null; // fromBk2 synthesises a neutral prior row when null
        input.setLogicalOverride(RecordedInputSnapshots.fromBk2(current, previous));
        runOwnedInputHandler = input;
    }

    /**
     * Clears the per-frame input override and advances the SS trace cursor by
     * one row (lag or not). Ends the session via the normal fade/teardown path
     * once the recorded stage-finished frame has played. No-op for level
     * sessions.
     */
    public void advanceSpecialStageTraceCursorIfActive(InputHandler input) {
        if (input != null) {
            input.clearLogicalOverride();
        }
        if (runSpecialInputApplied) {
            applyRunSpecialPreservedVblankPolicy();
            runSpecialInputApplied = false;
            runSpecialObservationPasses = List.of();
            runSpecialObservationPassesBound = false;
            return;
        }
        if (ssTrace == null) {
            return;
        }
        if (fadeStarted) {
            return;
        }
        if (ssCursor >= ssStageFinishedFrame) {
            if (fixture != null) {
                fixture.closeHardwareTimingReplayRun();
            }
            beginSpecialStageTerminalExit();
            return;
        }
        ssCursor++;
    }

    /**
     * Whether the row being prepared belongs to a recorded special-stage
     * segment, and so to the segment-local row cursor rather than the shared
     * movie cursor. This is the segment-kind question the special-stage row
     * driver already asks of itself; asking it at the call site keeps the
     * driver from being invoked for a row it has no authority over.
     */
    private boolean currentRunSegmentIsRecordedSpecialStage() {
        int index = currentRunSegmentIndex();
        if (index < 0 || index >= runSegments.size()) {
            return false;
        }
        if ("special_stage".equals(runSegments.get(index).segment().kind())) {
            return true;
        }
        return index + 1 < runSegments.size()
                && "special_stage".equals(
                        runSegments.get(index + 1).segment().kind())
                && (isCurrentRunSegmentExhausted()
                        || (runAdvancer != null
                                && runAdvancer.inTransition()));
    }

    private Optional<SpecialStageRowAdmission> currentRunSpecialAdmission() {
        if (runCoordinator == null || runSpecialRowDriver == null || fadeStarted
                || runCoordinator.phase()
                        != TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT
                || runSpecialRowDriver.isComplete()) {
            return Optional.empty();
        }
        return Optional.of(runSpecialRowDriver.currentPolicy());
    }

    private void applyRunSpecialPreservedVblankPolicy() {
        SpecialStageRowAdmission admission = currentRunSpecialAdmission().orElseThrow();
        if (!admission.advancePreservedVblankIfUnchanged()) {
            return;
        }
        var objects = GameServices.level().getObjectManager();
        if (objects != null && objects.getVblaCounter() == runSpecialVblankBefore) {
            objects.initVblaCounter(runSpecialVblankBefore + 1);
        }
    }

    private int currentRunSpecialRow() {
        return runSpecialRowDriver != null
                ? runSpecialRowDriver.cursor() : runSpecialLocalRow;
    }

    /**
     * Opens the recorded object-pass stream for the special-stage segment just
     * admitted. Rows already consumed before the driver existed are advanced
     * over rather than skipped, because the binder rejects a skipped
     * observation.
     */
    private void armRunSpecialPassPacing(int rowsAlreadyConsumed) {
        runSpecialPassBinder = runSpecialRows.newRunObjectsPassBinder().orElse(null);
        runSpecialPassPacedFromRow = runSpecialPassBinder == null
                ? Integer.MAX_VALUE : runSpecialRows.passPacedFromRow();
        runSpecialObservationPasses = List.of();
        runSpecialObservationPassesBound = false;
        if (runSpecialPassBinder != null) {
            for (int row = 0; row < rowsAlreadyConsumed; row++) {
                runSpecialPassBinder.passesForObservation(row);
            }
        }
    }

    private void clearRunSpecialPassPacing() {
        runSpecialPassBinder = null;
        runSpecialPassPacedFromRow = Integer.MAX_VALUE;
        runSpecialObservationPasses = List.of();
        runSpecialObservationPassesBound = false;
    }

    /**
     * Binds the current observation's completed passes, exactly once per local
     * row and in row order (the binder rejects a skipped observation). Called
     * from the per-frame input hook, which GameLoop runs for every
     * special-stage frame, lag row or not.
     */
    private void bindRunSpecialObservationPasses() {
        if (runSpecialPassBinder == null || runSpecialObservationPassesBound) {
            return;
        }
        runSpecialObservationRow = currentRunSpecialRow();
        runSpecialObservationPasses =
                runSpecialPassBinder.passesForObservation(runSpecialObservationRow);
        runSpecialObservationPassesBound = true;
    }

    /**
     * Pass pacing for the special-stage observation about to run, or empty when
     * this session/row is still frame-paced.
     *
     * <p>Comparison-only: {@link SpecialStageRecordedPassPacing} documents which
     * side of the rule-4 line every consumed field sits on. This method decides
     * only <em>when</em> engine-owned object passes run.
     */
    public Optional<GameLoop.SpecialStageObservationPacing>
            currentSpecialStagePassPacing() {
        if (runSpecialPassBinder == null || !runSpecialObservationPassesBound
                || currentRunSpecialAdmission().isEmpty()
                || runSpecialObservationRow < runSpecialPassPacedFromRow) {
            return Optional.empty();
        }
        return Optional.of(SpecialStageRecordedPassPacing.forObservation(
                movie, runSpecialObservationPasses, runSpecialObservationRow));
    }

    /**
     * True when the recorded stream gives this observation a completed object
     * pass, so it cannot have been a lag V-blank whatever the recorder's lag
     * heuristic reports: {@code SS_MainLoop} sets {@code VintID_S2SS} and waits
     * on it immediately before the pass (docs/s2disasm/s2.asm:6697-6698, 6721),
     * and {@code V_Int} takes {@code Vint_Lag} only while {@code Vint_routine}
     * is still 0 (docs/s2disasm/s2.asm:483-484). Same rule the chain harness
     * and {@code S2SpecialStageReplayHarness.stepPasses} apply.
     */
    private boolean currentRunSpecialRowOwnsCompletedPass() {
        return !runSpecialObservationPasses.isEmpty();
    }

    /** Resolves the active run source's physical row in its declared input clock. */
    int currentRunBoundaryBk2Frame() {
        int segmentIndex = currentRunSegmentIndex();
        if (segmentIndex >= 0 && segmentIndex < runSegments.size()) {
            TraceRunManifest.Segment segment =
                    runSegments.get(segmentIndex).segment();
            if ("special_stage".equals(segment.kind())) {
                return Math.addExact(
                        segment.bk2FrameOffset(), currentRunSpecialRow());
            }
        }
        return Math.max(0, GameServices.playbackDebug().getCursorFrame());
    }

    /**
     * Called from {@link GameLoop} each LEVEL tick while active. A run
     * session early-returns here: this completion-hold arms a fade the
     * moment ANY comparator completes, which for a run would end the whole
     * session ~1s after its FIRST segment boundary. The shared run coordinator
     * owns run completion and terminal-tail admission.
     */
    public void tick() {
        if (comparator == null || fadeStarted || isRunSession()) {
            return;
        }
        if (comparator.isComplete() && !completionArmed) {
            completionStartPending = true;
            if (!productionIterationInProgress) {
                finishPendingCompletionStart();
            }
        }
        if (completionArmed) {
            if (completionHoldFrames > 0) {
                completionHoldFrames--;
            } else {
                startFadeOut();
            }
        }
    }

    /**
     * All-mode per-frame hook for a run session's segment-advance state
     * machine (called from GameLoop unconditionally, every mode, not just
     * LEVEL — {@link #tick()} alone goes blind the instant the mode leaves
     * LEVEL). No-op for a non-run session or once the fade has started. The
     * re-seek + comparator/HUD/camera rebind for a segment advance happen
     * here, between frames — never from inside a
     * {@link com.openggf.debug.playback.PlaybackDebugManager.PlaybackFrameObserver}
     * callback.
     */
    public void runAdvanceTickIfActive(GameMode mode, int cursorFrame) {
        if (retryPendingSpecialStageTerminalExit()) {
            return;
        }
        if (advanceTitleCardPresentationIfReady(mode)) {
            return;
        }
        if (runCoordinator != null) {
            try {
                runCoordinatorTick(mode);
            } catch (RuntimeException | Error failure) {
                clearRunOwnedInputOverride();
                String diagnostic = failure.getMessage() != null
                        ? failure.getMessage()
                        : failure.getClass().getSimpleName();
                boolean directAbort = false;
                List<TraceRunPlaybackCoordinator.Action> cleanupActions =
                        List.of();
                try {
                    cleanupActions = runCoordinator.abort(diagnostic);
                    directAbort = cleanupActions.isEmpty();
                } catch (RuntimeException | Error abortFailure) {
                    suppressCleanupFailure(failure, abortFailure);
                    directAbort = true;
                }
                if (!directAbort) {
                    try {
                        applyRunCoordinatorActions(cleanupActions);
                    } catch (RuntimeException | Error cleanupFailure) {
                        suppressCleanupFailure(failure, cleanupFailure);
                        directAbort = true;
                    }
                }
                if (directAbort) {
                    abortIncompleteSession(failure,
                            "visual trace run aborted after observed-step failure",
                            Engine.currentGameLoop());
                }
                if (failure instanceof Error fatal) {
                    throw fatal;
                }
            }
            return;
        }
        if (runAdvancer == null || fadeStarted) {
            return;
        }
        int currentSegment = runAdvancer.currentSegmentIndex();
        if (runSpecialDynamicArtComparison != null
                && runSpecialTimingSegment == currentSegment
                && mode != GameMode.SPECIAL_STAGE) {
            if (productionIterationInProgress) {
                runSpecialVerificationPending = true;
            } else {
                verifyRunSpecialDynamicArtComplete();
            }
        }
        if (runDynamicArtSegments != null
                && mode != TraceRunReplayWalker.expectedMode(
                        runSegments.get(runAdvancer.currentSegmentIndex())
                                .segment())
                && !(runSpecialDynamicArtSegmentAnticipated
                        && mode == GameMode.SPECIAL_STAGE)) {
            if (productionIterationInProgress) {
                runGapEntryPending = true;
            } else {
                enterRunDynamicArtGap();
            }
        }
        RunSegmentAdvancer.Event event = runAdvancer.onFrame(mode, cursorFrame);
        if (event instanceof RunSegmentAdvancer.AdvanceAction action) {
            if (productionIterationInProgress) {
                runAdvancePending = action;
            } else {
                applyRunAdvanceAfterProduction(action);
            }
        } else if (event instanceof RunSegmentAdvancer.EndOfRun) {
            runEndPending = true;
            if (!productionIterationInProgress) {
                finishPendingRunEnd();
            }
        }
    }

    private boolean advanceTitleCardPresentationIfReady(GameMode mode) {
        if (!launchPhase.isPresentingTitleCard()) {
            return false;
        }
        GameLoop loop = Engine.currentGameLoop();
        if (loop == null) {
            abortLaunchFailure(
                    null,
                    "visual trace title-card presentation lost its game loop",
                    null,
                    "Failed to continue trace title card for " + entry.dir());
            return true;
        }
        if (!launchPhase.beginReplayBootstrapIfReady(mode)) {
            return true;
        }
        try {
            if (isRunSession()) {
                finishRunReplayLaunch(loop);
            } else {
                finishStandaloneReplayLaunch(loop);
            }
        } catch (Throwable launchFailure) {
            abortLaunchFailure(
                    launchFailure,
                    "visual trace replay handoff failed",
                    loop,
                    "Failed to start trace replay after title card for "
                            + entry.dir());
        }
        return true;
    }

    private void runCoordinatorTick(GameMode mode) {
        if (fadeStarted || runCoordinator.phase()
                == TraceRunPlaybackCoordinator.Phase.COMPLETE
                || runCoordinator.phase()
                == TraceRunPlaybackCoordinator.Phase.FAILED) {
            clearRunOwnedInputOverride();
            return;
        }
        runAdmittedStepOrdinal++;
        if (runCoordinator.phase()
                == TraceRunPlaybackCoordinator.Phase.TERMINAL_TAIL) {
            finishRunTerminalTailStep(mode);
            return;
        }
        // A recorded special-stage segment is cut on the raw ROM Game_Mode
        // byte, and the ROM keeps GameModeID_SpecialStage well past the stage
        // proper: SS_MainLoop leaves its object loop when SS_Check_Rings_flag
        // rises, and the emerald/perfect accounting, Pal_FadeToWhite, the
        // results-screen build and the whole Obj6F tally loop below it all
        // still run under that mode; Game_Mode is only rewritten by the
        // move.b #GameModeID_Level,(Game_Mode).w at the very end
        // (docs/s2disasm/s2.asm:6721-6800; S1 GM_Special has the same shape at
        // docs/s1disasm/sonic.asm:3419-3421). The engine splits that one ROM
        // mode into SPECIAL_STAGE plus SPECIAL_STAGE_RESULTS, so a bare
        // == SPECIAL_STAGE test here read the engine's own internal results
        // boundary as a premature exit from the recorded segment -- the same
        // shared predicate the run coordinator and the chain adapter already
        // use for segment ownership.
        if (runSpecialRowDriver != null
                && !RunPlaybackObservation.insideRecordedSpecialStageMode(mode)
                && !runSpecialRowDriver.isComplete()) {
            applyRunCoordinatorActions(runCoordinator.abort(
                    "special-stage segment " + currentRunSegmentIndex()
                            + " exited after " + runSpecialRowDriver.cursor() + " of "
                            + runSpecialRowDriver.rowCount() + " rows"));
            clearRunOwnedInputOverride();
            return;
        }
        forwardLatchedRunBoundary(mode);
        int rowsConsumed = destinationRowsConsumedForAdmission();
        RunPlaybackObservation currentObservation = captureRunObservation(
                mode, rowsConsumed, isLagOnlySameLevelContinuation());
        int observationSegmentIndex = runCoordinator.currentSegmentIndex();
        applyRunCoordinatorActions(runCoordinator.beforeAdmission(
                currentObservation));
        if (runCoordinator.currentSegmentIndex() != observationSegmentIndex) {
            currentObservation = captureRunObservation(
                    mode, rowsConsumed, isLagOnlySameLevelContinuation());
        }
        RunPlaybackObservation productionObservation =
                runLevelLoadedDuringSourceProduction
                        && runProductionOwnerObservation != null
                        && runCoordinator.phase()
                                == TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT
                        ? withProductionOwner(
                                currentObservation, runProductionOwnerObservation)
                        : currentObservation;
        int productionSegmentIndex = runCoordinator.currentSegmentIndex();
        if (productionObservation.currentSegmentExhausted()) {
            applyRunCoordinatorActions(
                    runCoordinator.afterProduction(productionObservation));
            applyRunCoordinatorActions(runCoordinator.beforeAdmission(
                    captureRunObservation(mode, rowsConsumed,
                            isLagOnlySameLevelContinuation())));
        }
        RunPlaybackObservation stepObservation =
                runCoordinator.phase()
                                == TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT
                        && runCoordinator.currentSegmentIndex()
                                == productionSegmentIndex
                        ? productionObservation
                        : captureRunObservation(mode, rowsConsumed,
                                isLagOnlySameLevelContinuation());
        applyRunCoordinatorActions(runCoordinator.afterStep(stepObservation));
        runProductionOwnerObservation = null;
        runProductionOwnerVblank = null;
        clearRunOwnedInputOverride();
    }

    /** Returns destination rows consumed by a title-card release fall-through. */
    private int destinationRowsConsumedForAdmission() {
        if (runCoordinator.phase()
                != TraceRunPlaybackCoordinator.Phase.TRANSITION_GAP) {
            return 0;
        }
        int sourceIndex = runCoordinator.currentSegmentIndex();
        int destinationIndex = sourceIndex + 1;
        if (destinationIndex < 0 || destinationIndex >= runSegments.size()
                || !"level".equals(runSegments.get(destinationIndex).segment().kind())) {
            return 0;
        }
        int destinationOffset = runSegments.get(destinationIndex).segment()
                .bk2FrameOffset();
        return Math.max(0,
                GameServices.playbackDebug().getCursorFrame() - destinationOffset);
    }

    private static RunPlaybackObservation withProductionOwner(
            RunPlaybackObservation current,
            RunPlaybackObservation owner) {
        return new RunPlaybackObservation(
                owner.mode(), current.sharedBk2Cursor(),
                current.admittedStepOrdinal(), owner.level(),
                current.initialTitleCardPending(), owner.bonus(),
                owner.specialStageIndex(), current.productionOpen(),
                current.currentSegmentExhausted(), 0,
                current.lagOnlySameLevelContinuation(),
                current.timingScheduleGeneration(),
                current.dynamicArtGeneration());
    }

    private boolean isLagOnlySameLevelContinuation() {
        int index = currentRunSegmentIndex();
        return comparator != null && index >= 0
                && index + 1 < runSegments.size()
                && TraceRunReplayWalker.isLagOnlySameLevelContinuation(
                        runSegments.get(index).segment(),
                        runSegments.get(index + 1).segment(),
                        runSegments.get(index).segment().traceFrameCount(),
                        comparator.laggedFrames());
    }

    private void forwardLatchedRunBoundary(GameMode mode) {
        int index = currentRunSegmentIndex();
        if (index < 0 || index >= runSegments.size() - 1
                || runForwardedBoundarySegment == index
                || runBoundaryProbe == null || !runBoundaryProbe.latched()) {
            return;
        }
        var boundary = runSegments.get(index).exitBoundary();
        if (boundary == null) {
            return;
        }
        int frame = runBoundaryProbe.observation().observedBk2Frame();
        RunBoundarySignal signal = switch (boundary.entryKind()) {
            case "starpost_bonus" -> mode == GameMode.BONUS_STAGE
                    && GameServices.bonusStageOrNull() != null
                    ? new RunBoundarySignal.BonusRequest(frame,
                            GameServices.bonusStageOrNull().getActiveType()) : null;
            // The provider's current stage is the stage it has LOADED, not the
            // one a pending request will enter: the entry's index is chosen by
            // SpecialStageProvider#consumeStageIndexForEntry inside
            // GameLoop#enterSpecialStage, which runs when the level frame
            // consumes the request. Read while the level still owns the frame it
            // returns the previously played stage -- correct only for a run's
            // first entry, where nothing has been played yet. So, exactly as the
            // bonus branch above does, take the identity from the provider that
            // owns the entry once it owns it. The probe already latched the
            // boundary's own physical row, so waiting costs the signal nothing.
            case "giant_ring", "starpost_special" ->
                    mode == GameMode.SPECIAL_STAGE
                    && getActiveSpecialStageIndex(mode) != null
                    ? new RunBoundarySignal.SpecialStageRequest(
                            frame, getActiveSpecialStageIndex(mode)) : null;
            case "stage_exit" -> null; // emitted by the semantic results-entry seam
            default -> null; // level-load signals are forwarded at their load seam
        };
        if (signal != null) {
            runCoordinator.observeBoundary(signal);
            runForwardedBoundarySegment = index;
        }
    }

    private void applyRunCoordinatorActions(
            List<TraceRunPlaybackCoordinator.Action> actions) {
        runCoordinatorTranscript.addAll(actions);
        for (TraceRunPlaybackCoordinator.Action action : actions) {
            if (action instanceof TraceRunPlaybackCoordinator.AdmitDestination admit) {
                applyRunDestinationAdmission(admit.receipt());
            } else if (action instanceof TraceRunPlaybackCoordinator.CloseSegment close) {
                closeRunSegment(close.segmentIndex());
            } else if (action instanceof TraceRunPlaybackCoordinator.EnterTransitionGap gap) {
                enterRunUnrepresentedGap(gap.sourceSegmentIndex());
                runLevelLoadedDuringSourceProduction = false;
                runGapSourceLevelMainLoopEnded = false;
            } else if (action instanceof TraceRunPlaybackCoordinator.BeginTerminalTail tail) {
                if (!runTerminalGapOpened) {
                    enterRunUnrepresentedGap(runSegments.size() - 1);
                    runTerminalGapOpened = true;
                }
                beginRunTerminalTail(tail.plan());
            } else if (action instanceof TraceRunPlaybackCoordinator.CompleteRun complete) {
                if (!runTerminalGapOpened) {
                    enterRunUnrepresentedGap(complete.segmentIndex());
                    runTerminalGapOpened = true;
                }
                compareRunTerminalDynamicArtTail();
                finishPendingRunEnd();
            } else if (action instanceof TraceRunPlaybackCoordinator.FailRun failure) {
                failRun(failure.diagnostic());
            }
        }
    }

    /** Immutable structural transcript used to prove adapter parity in tests. */
    public List<TraceRunPlaybackCoordinator.Action> runCoordinatorTranscript() {
        return List.copyOf(runCoordinatorTranscript);
    }

    private void closeRunSegment(int segmentIndex) {
        Throwable failure = null;
        try {
            requireActiveRunPayload(segmentIndex);
            captureRunLevelSourceTail(segmentIndex);
            if (runBoundaryProbe != null) {
                runBoundaryProbe.setDelegate(null);
            }
            if (runSpecialRowDriver != null) {
                runSpecialRowDriver.verifyComplete();
                runSpecialLocalRow = runSpecialRowDriver.cursor();
                runSpecialRowDriver = null;
                runSpecialRows = null;
                clearRunSpecialPassPacing();
            } else if (runSpecialRows != null) {
                requireNoPendingRunSpecialDynamicArtRow();
                if (runSpecialDynamicArtComparison != null) {
                    runSpecialDynamicArtComparison.verifyComplete();
                }
                runSpecialRows = null;
                clearRunSpecialPassPacing();
            }
            if (runDynamicArtSegments != null) {
                closeRunDynamicArtWindowForSegment(segmentIndex);
            }
            if (runStructuralComparator != null) {
                FrameComparison terminal = runStructuralComparator.finalizeSegment(
                        GameServices.captureDynamicArtDiagnostics());
                ingestRunUndisplayedComparison(terminal);
                runStructuralComparator = null;
            } else if (comparator != null) {
                comparator.finalizeTerminalDynamicArtComparison();
            }
        } catch (RuntimeException | Error closeFailure) {
            failure = closeFailure;
        } finally {
            failure = detachAndCloseRunPayload(failure);
        }
        throwUnchecked(failure);
    }

    /** Enters a payload-free transition only after the source lease closed. */
    private void enterRunUnrepresentedGap(int sourceSegmentIndex) {
        if (activeRunPayload != null) {
            throw new IllegalStateException(
                    "run transition gap still owns a segment payload");
        }
        if (runHardwareTiming != null) {
            // The cursor may free-run through choreography and across the next
            // descriptor's offset; membership remains detached until the
            // destination admission hands off timing and opens its lease.
            runHardwareTiming.enterTransitionGap();
        }
        if (sourceSegmentIndex >= 0) {
            markRunDynamicArtGapOpened(sourceSegmentIndex);
        }
        if (fixture != null) {
            fixture.enterHardwareTimingGap();
        }
    }

    /**
     * Settles a player transfer the level routine staged for its counted
     * pre-main-loop tail. The row a destination is admitted on is that level's
     * first main-loop row, and the tail's own length is the game's, so the
     * transfer belongs that many rows earlier — before this admission opens
     * the destination's comparison window or reads the gap ledger.
     *
     * <p>The admitted row is taken from the receipt rather than the movie
     * clock. An admission that consumes a destination row has already moved
     * the clock onto that row, so projecting it forward would place the
     * transfer a row late.
     */
    private void settlePreMainLoopPlayerTransferAtAdmission(
            DestinationAdmissionReceipt receipt) {
        if (dynamicArtSegmentGameplayMode == null) {
            return;
        }
        var lifecycle = dynamicArtSegmentGameplayMode.dynamicArtLifecycle();
        if (lifecycle != null && lifecycle.isRunActive()) {
            lifecycle.settlePendingPlayerPreparationBeforeLevelMainLoop(
                    receipt.absoluteBk2Row() - receipt.rowsConsumed());
        }
    }

    private void applyRunDestinationAdmission(DestinationAdmissionReceipt receipt) {
        try {
            TraceRunSegmentDescriptor segment =
                    runSegments.get(receipt.segmentIndex());
            if (runHardwareTiming != null) {
                // The handoff verifies the source schedule. It must succeed
                // before any destination comparison, dynamic-art, or input
                // owner opens.
                runHardwareTiming.enterSegment(receipt.segmentIndex());
            }
            if (activeRunPayload == null) {
                openRunPayload(receipt.segmentIndex());
            } else {
                requireActiveRunPayload(receipt.segmentIndex());
            }
            ObjectManager objects = GameServices.level().getObjectManager();
            if (objects != null) {
                applyRunDestinationVblankAdmission(receipt, objects);
            }
            settlePreMainLoopPlayerTransferAtAdmission(receipt);
            FrameComparison gapComparison = null;
            boolean specialLocal = receipt.inputClock()
                    == DestinationAdmissionReceipt.InputClock.SPECIAL_LOCAL;
            if (specialLocal) {
                TraceRunSpecialStageRowDriver.requireFreshAdmission(
                        receipt.rowsConsumed());
            }
            if (runCoordinator == null) {
                armRunSpecialDynamicArtComparison(receipt.segmentIndex());
            }
            boolean adoptedOpeningRow = false;
            if (runDynamicArtSegments != null) {
                runDynamicArtSegments.beginSegment();
                if (receipt.rowsConsumed() == 1) {
                // Admission is polled between host steps, so a destination
                // whose readiness only became observable once its first row
                // had run reports that row consumed. Adopt it as the opening
                // segment's row zero instead of skipping past it, so the art
                // it produced is stamped and published as segment work rather
                // than left gap-resident.
                //
                // The count is organic here by construction:
                // destinationRowsConsumedForAdmission() derives it from the
                // playback cursor's own position relative to the destination's
                // bk2FrameOffset, so a non-zero count means the engine really
                // executed that row. Adoption is only correct on that footing;
                // a cursor re-anchored past rows the engine never ran has no
                // row zero to adopt.
                    dynamicArtSegmentGameplayMode.dynamicArtLifecycle()
                            .adoptGapResidentOpeningRow();
                    adoptedOpeningRow = true;
                } else {
                    dynamicArtSegmentGameplayMode.dynamicArtLifecycle()
                            .advanceComparisonCursor(receipt.rowsConsumed());
                }
                bindRunSpecialDynamicArtTargetGeneration();
                if (runDynamicArtGapJournal != null
                        && receipt.segmentIndex() > 0) {
                    gapComparison = runDynamicArtGapJournal.destinationOpened(
                            receipt.segmentIndex(), receipt.rowsConsumed());
                }
            }
            if (gapComparison != null) {
                ingestRunUndisplayedComparison(gapComparison);
            }
            if (specialLocal) {
                runSpecialRows = Objects.requireNonNull(
                        activeRunSpecialRows(receipt.segmentIndex()),
                        "prepared special-stage rows for segment "
                                + receipt.segmentIndex());
                runSpecialLocalRow = receipt.rowsConsumed();
                runSpecialRowDriver = new TraceRunSpecialStageRowDriver(
                        runSpecialRows, activeRunTrace(receipt.segmentIndex()));
                armRunSpecialPassPacing(receipt.rowsConsumed());
                runBoundaryProbe.setDelegate(null);
                TraceRunSpecialStageRows hudRows = runSpecialRows;
                overlay = new SpecialStageTraceHudOverlay(
                        createRunHudModel(createRunSpecialStageHudModel(segment)),
                        () -> hudRows.metadata().traceProfile()
                                .replace('_', ' ')
                                .toUpperCase(java.util.Locale.ROOT),
                        () -> null,
                        this::rewindStatusLabel);
            }
            if (receipt.executionPolicy()
                    == TraceRunReplayWalker.SegmentExecutionPolicy
                            .LEVEL_PRESENTATION_BRIDGE) {
                if (receipt.rowsConsumed() != 0) {
                    throw new IllegalStateException(
                            "presentation bridge must begin at row 0, got "
                                    + receipt.rowsConsumed());
                }
                runStructuralComparator = new TraceStructuralRowComparator(
                        activeRunTrace(receipt.segmentIndex()),
                        ToleranceConfig.DEFAULT, 0);
                TraceStructuralRowComparator structural = runStructuralComparator;
                overlay = new TraceHudOverlay(
                        createRunHudModel(createRunPresentationHudModel(structural)),
                        () -> null, this::rewindStatusLabel);
                runBoundaryProbe.setDelegate(null);
                if (GameServices.playbackDebug().getCursorFrame()
                        != receipt.absoluteBk2Row()) {
                    throw new IllegalStateException(
                            "presentation bridge cursor changed during admission");
                }
            } else if (receipt.inputClock()
                    == DestinationAdmissionReceipt.InputClock.SHARED) {
                LiveTraceComparator destinationComparator =
                        createRunComparator(receipt.segmentIndex(),
                                receipt.rowsConsumed(),
                                receipt.absoluteBk2Row());
                if (adoptedOpeningRow) {
                    // The live comparator starts at row one, so the adopted
                    // row zero would be published but never checked. Compare
                    // it here, through the ordinary binder and counters, so
                    // production and AbstractRunChainTest agree that row zero
                    // is compared.
                    destinationComparator.compareAdoptedOpeningRow(0,
                            dynamicArtSegmentGameplayMode.dynamicArtLifecycle()
                                    .latestSnapshot());
                }
                compareRunReturnBoundaryIfPresent(
                        receipt.segmentIndex(), destinationComparator);
                installRunComparator(destinationComparator);
                GameLoop destinationLoop = Engine.currentGameLoop();
                if (destinationLoop != null) {
                    // A direct level-load admission may continue into gameplay
                    // in the same host step without passing through the
                    // scheduled level-load activation callback. Publish the
                    // destination row at the rebuilt-player seam so it cannot
                    // read stale SS input.
                    destinationLoop.applyScheduledPlaybackInputImmediately();
                }
                adoptRunDestinationProductionIterationOwner(
                        destinationComparator);
            }
            armCurrentRunBoundary();
            runLevelLoadedDuringSourceProduction = false;
            completionArmed = false;
            completionHoldFrames = 0;
        } catch (RuntimeException | Error attachmentFailure) {
            Throwable failure = detachAndCloseRunPayload(attachmentFailure);
            throwUnchecked(failure);
        }
    }

    private TraceHudModel createRunSpecialStageHudModel(
            TraceRunSegmentDescriptor segment) {
        return new TraceHudModel() {
            @Override
            public int errorCount() {
                return runSpecialRowDriver != null
                        ? runSpecialRowDriver.errorCount() : 0;
            }

            @Override
            public int warningCount() {
                return runSpecialRowDriver != null
                        ? runSpecialRowDriver.warningCount() : 0;
            }

            @Override
            public int laggedFrames() {
                return runSpecialRowDriver != null
                        ? runSpecialRowDriver.laggedFrames() : 0;
            }

            @Override
            public int recentActionMask() {
                return currentRunSpecialInput(segment).p1ActionMask();
            }

            @Override
            public int recentInputMask() {
                return currentRunSpecialInput(segment).p1InputMask();
            }

            @Override
            public boolean recentStartPressed() {
                return currentRunSpecialInput(segment).p1StartPressed();
            }

            @Override
            public List<com.openggf.trace.live.MismatchEntry> recentMismatches() {
                return runSpecialRowDriver != null
                        ? runSpecialRowDriver.recentMismatches() : List.of();
            }

            @Override
            public boolean hasRecordingDesync() {
                return errorCount() > 0;
            }

            @Override
            public boolean isComplete() {
                return runSpecialRowDriver != null
                        && runSpecialRowDriver.isComplete();
            }

            private Bk2FrameInput currentRunSpecialInput(
                    TraceRunSegmentDescriptor plan) {
                int row = runSpecialRowDriver != null
                        ? Math.max(0, runSpecialRowDriver.cursor() - 1) : 0;
                int index = plan.segment().bk2FrameOffset() + row;
                return index >= 0 && index < movie.getFrameCount()
                        ? movie.getFrame(index)
                        : new Bk2FrameInput(index, 0, 0, false, "");
            }
        };
    }

    private TraceHudModel createRunPresentationHudModel(
            TraceStructuralRowComparator structural) {
        return new TraceHudModel() {
            @Override public int errorCount() {
                return structural.errorCount();
            }

            @Override public int warningCount() {
                return structural.warningCount();
            }

            @Override public int laggedFrames() {
                return structural.laggedFrames();
            }

            @Override public int recentActionMask() {
                return structural.recentActionMask();
            }

            @Override public int recentInputMask() {
                return structural.recentInputMask();
            }

            @Override public boolean recentStartPressed() {
                return structural.recentStartPressed();
            }

            @Override
            public List<com.openggf.trace.live.MismatchEntry> recentMismatches() {
                return structural.recentMismatches();
            }

            @Override public boolean hasRecordingDesync() {
                return structural.hasRecordingDesync();
            }

            @Override public boolean isComplete() {
                return structural.isComplete();
            }
        };
    }

    /**
     * Keeps the visible HUD attached to the physical movie clock while segment
     * diagnostic owners change. Segment completion is intentionally hidden:
     * only the whole-run coordinator may publish TRACE COMPLETE.
     */
    private TraceHudModel createRunHudModel(TraceHudModel delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new TraceHudModel() {
            @Override public int errorCount() {
                return runExternalErrorCount();
            }
            @Override public int warningCount() {
                return runExternalWarningCount();
            }
            @Override public int laggedFrames() { return delegate.laggedFrames(); }

            @Override public int recentActionMask() {
                return runLastPhysicalInput != null
                        ? runLastPhysicalInput.p1ActionMask()
                        : delegate.recentActionMask();
            }

            @Override public int recentInputMask() {
                return runLastPhysicalInput != null
                        ? runLastPhysicalInput.p1InputMask()
                        : delegate.recentInputMask();
            }

            @Override public boolean recentStartPressed() {
                return runLastPhysicalInput != null
                        ? runLastPhysicalInput.p1StartPressed()
                        : delegate.recentStartPressed();
            }

            @Override
            public List<com.openggf.trace.live.MismatchEntry> recentMismatches() {
                return runExternalDiagnostics != null
                        ? runExternalDiagnostics.recentMismatches()
                        : delegate.recentMismatches();
            }

            @Override public boolean hasRecordingDesync() {
                return delegate.hasRecordingDesync()
                        || runExternalErrorCount() > 0;
            }

            @Override public boolean isComplete() {
                return runCoordinator != null
                        ? runCoordinator.phase()
                                == TraceRunPlaybackCoordinator.Phase.COMPLETE
                        : delegate.isComplete();
            }
        };
    }

    private int runExternalErrorCount() {
        return runExternalDiagnostics != null
                ? runExternalDiagnostics.errorCount() : 0;
    }

    private int runExternalWarningCount() {
        return runExternalDiagnostics != null
                ? runExternalDiagnostics.warningCount() : 0;
    }

    private void captureRunLevelSourceTail(int segmentIndex) {
        if (runVblankClock == null) {
            return;
        }
        RunPlaybackObservation owner = runProductionOwnerObservation;
        Integer observedVblank = runProductionOwnerVblank;
        if (owner == null || observedVblank == null) {
            var objects = GameServices.level().getObjectManager();
            if (objects == null) {
                return;
            }
            owner = captureRunObservation(
                    Engine.currentGameLoop() != null
                            ? Engine.currentGameLoop().getCurrentGameMode()
                            : GameMode.LEVEL,
                    0, isLagOnlySameLevelContinuation());
            observedVblank = objects.getVblaCounter();
        }
        runVblankClock.captureLevelSourceTail(
                segmentIndex,
                runSegments.get(segmentIndex).segment(),
                owner.sharedBk2Cursor(),
                observedVblank);
    }

    void applyRunDestinationVblankAdmission(
            DestinationAdmissionReceipt receipt,
            ObjectManager objects) {
        if (runVblankClock == null) {
            return;
        }
        boolean presentation = receipt.identity()
                instanceof DestinationAdmissionReceipt.LevelPresentationIdentity;
        if (!presentation && !(receipt.identity()
                        instanceof DestinationAdmissionReceipt.LevelIdentity)) {
            return;
        }
        Objects.requireNonNull(objects, "objects");
        int destinationIndex = receipt.segmentIndex();
        TraceRunManifest.Segment destination =
                runSegments.get(destinationIndex).segment();
        if (destinationIndex <= 0) {
            return;
        }
        TraceRunManifest.Segment previous =
                runSegments.get(destinationIndex - 1).segment();
        if (presentation) {
            applyRunPresentationBridgeVblankAdmission(
                    destinationIndex, destination, previous, objects);
            return;
        }
        if ("level".equals(previous.kind())) {
            runVblankClock.levelDestinationTarget(
                    destinationIndex - 1, previous, destination,
                    receipt.rowsConsumed()).ifPresent(objects::initVblaCounter);
            return;
        }
        if (!"special_stage".equals(previous.kind())) {
            return;
        }
        TraceRunManifest.Transition entryBoundary =
                runSegments.get(destinationIndex - 1).entryBoundary();
        if (entryBoundary == null) {
            return;
        }
        int sourceIndex = entryBoundary.fromSegment();
        // Deliberately a literal 1, NOT receipt.rowsConsumed(). The budget now
        // carries the consumed-row term, but this production admission is not
        // reached by any test today -- the chain harness runs its own anchor and
        // never this one, and the visual harness self-pauses in the results
        // screen well before the return -- so the count this receipt carries
        // here has never been observed. One is the value the budget hardcoded
        // before the term existed, so passing it preserves this path's behaviour
        // exactly while the derivation itself becomes layout-independent.
        // Substituting the receipt's real count is the correct end state and
        // should be done once the path is reachable and the value measured;
        // until then it would be an unverified behaviour change, and a count of
        // 0 would now throw where it previously produced a silent answer.
        runVblankClock.uncomparedInteriorReturnTarget(
                sourceIndex,
                runSegments.get(sourceIndex).segment(),
                destination,
                1).ifPresent(objects::initVblaCounter);
    }

    /**
     * Seeds the object VBlank clock a special stage's results-screen bridge
     * plays on. Nothing on that bridge runs a level loop, so the production
     * counter is frozen for the whole of it and the level after it would
     * otherwise inherit an anchor short by the stage interior and the bridge
     * together. The seed derives only from the level that entered the stage and
     * the manifest's movie-row distance; the bridge's own tail is derived by
     * {@link TraceRunVblankClock} from the game's profiled non-advancing rows.
     */
    private void applyRunPresentationBridgeVblankAdmission(
            int destinationIndex,
            TraceRunManifest.Segment destination,
            TraceRunManifest.Segment previous,
            ObjectManager objects) {
        if (!"special_stage".equals(previous.kind())) {
            return;
        }
        TraceRunManifest.Transition entryBoundary =
                runSegments.get(destinationIndex - 1).entryBoundary();
        if (entryBoundary == null) {
            return;
        }
        int sourceIndex = entryBoundary.fromSegment();
        runVblankClock.presentationBridgeEntryTarget(
                sourceIndex,
                runSegments.get(sourceIndex).segment(),
                destinationIndex,
                destination).ifPresent(objects::initVblaCounter);
    }

    private LiveTraceComparator createRunComparator(
            int segmentIndex,
            int rowsConsumed,
            int absoluteBk2Row) {
        GameLoop loop = Engine.currentGameLoop();
        if (loop == null) {
            throw new IllegalStateException(
                    "run destination has no active GameLoop");
        }
        requireContinuousRunPlaybackAt(absoluteBk2Row);
        return new LiveTraceComparator(
                activeRunTrace(segmentIndex),
                ToleranceConfig.DEFAULT, rowsConsumed,
                loop::getMainPlayableSprite, loop::toggleUserPause,
                runExternalDiagnostics != null
                        ? runExternalDiagnostics::acceptDisplayed : null);
    }

    private void installRunComparator(
            LiveTraceComparator destinationComparator) {
        GameLoop loop = Engine.currentGameLoop();
        if (loop == null) {
            throw new IllegalStateException(
                    "run destination has no active GameLoop");
        }
        comparator = Objects.requireNonNull(
                destinationComparator, "destinationComparator");
        cameraFocusController = new TraceCameraFocusController(
                comparator, loop::getMainPlayableSprite, () -> {
                    var sprites = GameServices.spritesOrNull();
                    return sprites == null || sprites.getSidekicks().isEmpty()
                            ? null : sprites.getSidekicks().getFirst();
                }, GameServices::camera, GameServices.configuration(),
                loop::isPaused);
        loop.setTraceCameraFocusController(cameraFocusController);
        overlay = new TraceHudOverlay(
                createRunHudModel(comparator),
                () -> cameraFocusController.currentLabel(),
                this::rewindStatusLabel);
        runBoundaryProbe.setDelegate(comparator);
        GameServices.playbackDebug().setFrameObserver(runBoundaryProbe);
    }

    private void requireContinuousRunPlaybackAt(int expectedRow) {
        PlaybackDebugManager playback = GameServices.playbackDebug();
        if (!playback.isSessionPlaying()
                || playback.getMovieFrameCount() != movie.getFrameCount()
                || playback.getCursorFrame() != expectedRow) {
            throw new IllegalStateException(
                    "run destination must retain the active movie at row "
                            + expectedRow + ", got cursor="
                            + playback.getCursorFrame() + ", frames="
                            + playback.getMovieFrameCount());
        }
    }

    /**
     * Transfers the current host wrapper's deferred publication owner when a
     * destination is admitted before its first production row. The snapshot
     * is rebased after the destination segment opens so generation and row-zero
     * publication are compared within the same production window.
     */
    private void adoptRunDestinationProductionIterationOwner(
            LiveTraceComparator destinationComparator) {
        if (!productionIterationInProgress) {
            return;
        }
        productionIterationComparator = Objects.requireNonNull(
                destinationComparator, "destinationComparator");
        DynamicArtDiagnosticsSnapshot destinationBefore =
                GameServices.captureDynamicArtDiagnostics();
        dynamicArtSnapshotBeforeIteration = destinationBefore;
        dynamicArtDeliverySerialBeforeIteration =
                destinationBefore.deliverySerial();
    }

    private void enterRunDynamicArtGapForSegment(int segmentIndex) {
        closeRunDynamicArtWindowForSegment(segmentIndex);
        markRunDynamicArtGapOpened(segmentIndex);
    }

    private void closeRunDynamicArtWindowForSegment(int segmentIndex) {
        runClosingDynamicArtSegment = segmentIndex;
        try {
            runDynamicArtSegments.enterGap();
        } finally {
            runClosingDynamicArtSegment = -1;
        }
    }

    private void markRunDynamicArtGapOpened(int segmentIndex) {
        if (runDynamicArtGapJournal != null) {
            runDynamicArtGapJournal.gapOpened(segmentIndex);
        }
    }

    private void ingestRunUndisplayedComparison(FrameComparison comparison) {
        ingestRunUndisplayedComparison(comparison, comparator);
    }

    private void ingestRunUndisplayedComparison(
            FrameComparison comparison,
            LiveTraceComparator destinationComparator) {
        if (comparison == null) {
            return;
        }
        if (runExternalDiagnostics != null) {
            runExternalDiagnostics.accept(comparison);
            return;
        }
        if (destinationComparator == null) {
            throw new IllegalStateException(
                    "run comparison has no diagnostic sink");
        }
        destinationComparator.ingestExternalComparison(comparison);
    }

    private void compareRunReturnBoundaryIfPresent(int destinationIndex) {
        compareRunReturnBoundaryIfPresent(destinationIndex, comparator);
    }

    private void compareRunReturnBoundaryIfPresent(
            int destinationIndex,
            LiveTraceComparator destinationComparator) {
        if (destinationIndex < 2 || destinationComparator == null) {
            return;
        }
        TraceRunSegmentDescriptor interior =
                runSegments.get(destinationIndex - 1);
        if (interior.exitBoundary() == null
                || !"stage_exit".equals(interior.exitBoundary().entryKind())
                || interior.entryBoundary() == null) {
            return;
        }
        TraceRunSegmentDescriptor destination =
                runSegments.get(destinationIndex);
        TraceRunSegmentDescriptor preEntry =
                runSegments.get(interior.entryBoundary().fromSegment());
        Integer resolvedZone = destination.segment().zoneId() == null
                ? null
                : GameServices.module().getTracePlaybackProfile()
                        .resolveRecordedLevel(destination.segment().zoneId(),
                                destination.segment().act()).zone();
        AbstractPlayableSprite sprite = Engine.currentGameLoop()
                .getMainPlayableSprite();
        TraceRunBoundaryComparator.ExpectedBoundary expected =
                new TraceRunBoundaryComparator.ExpectedBoundary(
                        interior.entryBoundary(), interior.exitBoundary(),
                        preEntry.segment(), destination.segment(),
                        destination.openingFrame(), resolvedZone,
                        destination.metadata().startX() & 0xFFFF,
                        destination.metadata().startY() & 0xFFFF);
        TraceRunBoundaryComparator.ActualBoundary actual =
                new TraceRunBoundaryComparator.ActualBoundary(
                        sprite != null ? (int) sprite.getCentreX() : null,
                        sprite != null ? (int) sprite.getCentreY() : null,
                        GameServices.level().getCheckpointState()
                                .getLastCheckpointIndex(),
                        GameServices.level().getCurrentZone(),
                        GameServices.level().getCurrentAct(),
                        GameServices.level().getLevelGamestate().getRings(),
                        GameServices.gameState().getEmeraldCount(),
                        !TraceRunReplayWalker.isUncomparedInterior(
                                interior.segment()),
                        GameServices.playbackDebug().getCursorFrame()
                                - destination.segment().bk2FrameOffset());
        ingestRunUndisplayedComparison(
                TraceRunBoundaryComparator.compare(
                        interior.exitBoundary().modeChangeBk2Frame(),
                        expected, actual),
                destinationComparator);
    }

    private void beginRunTerminalTail(
            TraceRunReplayWalker.TerminalMovieTailPlan plan) {
        PlaybackDebugManager playback = GameServices.playbackDebug();
        boolean continuousClockRetained = plan.shouldReplay()
                ? playback.isSessionPlaying()
                        && playback.getCursorFrame() == plan.tailStart()
                : playback.hasLoadedMovie()
                        && playback.getMovieFrameCount() == movie.getFrameCount()
                        && plan.tailStart() == movie.getFrameCount()
                        && playback.getCursorFrame()
                                == movie.getFrameCount() - 1;
        if (!continuousClockRetained) {
            throw new IllegalStateException(
                    "terminal tail must retain continuous playback at row "
                            + plan.tailStart() + ", got "
                            + playback.getCursorFrame());
        }
        if (runBoundaryProbe != null) {
            runBoundaryProbe.setDelegate(null);
        }
        runTerminalTail = plan;
        runTerminalRowAdvanced = false;
        runTerminalMovieEndReached = false;
        runTerminalTailCompared = false;
        if (!plan.shouldReplay()) {
            compareRunTerminalDynamicArtTail();
            applyRunCoordinatorActions(runCoordinator.finishTerminalTail(
                    Engine.currentGameLoop().getCurrentGameMode()));
        }
    }

    private void finishRunTerminalTailStep(GameMode mode) {
        if (!runTerminalRowAdvanced) {
            return;
        }
        runTerminalRowAdvanced = false;
        if (runTerminalMovieEndReached) {
            runTerminalMovieEndReached = false;
            compareRunTerminalDynamicArtTail();
            applyRunCoordinatorActions(runCoordinator.finishTerminalTail(mode));
        }
    }

    private void compareRunTerminalDynamicArtTail() {
        if (runTerminalTailCompared || runDynamicArtGapJournal == null) {
            return;
        }
        runTerminalTailCompared = true;
        // The movie ends before this level reaches its main loop, so a
        // transfer still held for the pre-main-loop tail has to settle at the
        // earliest row that tail could occupy.
        if (dynamicArtSegmentGameplayMode != null
                && dynamicArtSegmentGameplayMode.dynamicArtLifecycle() != null) {
            dynamicArtSegmentGameplayMode.dynamicArtLifecycle()
                    .releaseUnclaimedPreMainLoopPlayerTransfer();
        }
        ingestRunUndisplayedComparison(
                runDynamicArtGapJournal.terminalTailClosed(
                        movie.getFrameCount()));
    }

    private void failRun(String diagnostic) {
        LOGGER.severe("Visual trace run failed: " + diagnostic);
        TraceRunFailureStatus.recordReason(
                Math.max(0, currentRunSegmentIndex()), diagnostic,
                Math.max(0, GameServices.playbackDebug().getCursorFrame()),
                runAdmittedStepOrdinal);
        Throwable failure = cleanupFailure(
                null, this::closeRunDynamicArtSegments);
        failure = detachAndCloseRunPayload(failure);
        if (fixture != null) {
            failure = cleanupFailure(
                    failure, fixture::closeHardwareTimingReplayRun);
        }
        failure = cleanupFailure(failure, this::startFadeOut);
        throwUnchecked(failure);
    }

    private void clearRunOwnedInputOverride() {
        if (runOwnedInputHandler != null) {
            runOwnedInputHandler.clearLogicalOverride();
            runOwnedInputHandler = null;
        }
    }

    /**
     * Attempts destination ownership after title-card/setup admission but
     * before production.
     *
     * <p>The observation reports the live initial-title-card barrier. A level
     * restart raises its card from inside the transition gap and the gap's own
     * rows carry the presentation, so this seam can run on a LEVEL row whose
     * card has been requested but not yet entered; forcing the barrier clear
     * here would admit the destination on that row.
     */
    static void admitRunDestinationBeforeProductionIfActive(GameMode mode) {
        TraceSessionLauncher session = active();
        if (session == null || session.runCoordinator == null) {
            return;
        }
        session.forwardLatchedRunBoundary(mode);
        int rowsConsumed = session.destinationRowsConsumedForAdmission();
        session.applyRunCoordinatorActions(session.runCoordinator.beforeAdmission(
                session.captureRunObservation(mode, rowsConsumed,
                        session.isLagOnlySameLevelContinuation())));
    }

    /**
     * Publishes a seamless in-level act advance production has already applied.
     * Session-independent, exactly like {@link #markNextRunLevelLoadCause}: the
     * headless run chain has no live session but shares the same
     * gameplay-session-owned tracker.
     */
    public static void observeRunSeamlessLevelAdvance(
            com.openggf.level.LevelManager levelManager) {
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        if (context != null) {
            context.runLevelLoads().observeSeamlessAdvance(levelManager);
        }
    }

    /** Classifies the next production-owned level load without requesting one. */
    static void markNextRunLevelLoadCause(RunLevelLoadCause cause) {
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        if (context != null) {
            context.runLevelLoads().markNext(cause);
        }
    }

    static void markNextRunInteriorReturnLoad() {
        markNextRunLevelLoadCause(RunLevelLoadCause.INTERIOR_RETURN);
    }

    /** Records the native special/bonus-stage exit before any return load starts. */
    public static void observeRunStageExitIfActive() {
        TraceSessionLauncher session = active();
        if (session == null || session.runCoordinator == null
                || session.runBoundaryProbe == null) {
            return;
        }
        int frame = session.currentRunBoundaryBk2Frame();
        RunBoundarySignal.StageExit signal = new RunBoundarySignal.StageExit(frame);
        session.runBoundaryProbe.observeSignal(signal);
        session.runCoordinator.observeBoundary(signal);
        session.runForwardedBoundarySegment = session.currentRunSegmentIndex();
    }

    static void runDeathRestartLoad(com.openggf.level.LevelManager levelManager) {
        markNextRunLevelLoadCause(RunLevelLoadCause.DEATH_RESTART);
        levelManager.restartCurrentLevelAfterDeath();
    }

    @FunctionalInterface
    public interface LevelLoadAction {
        void load() throws IOException;
    }

    public static void runLevelAdvanceLoad(LevelLoadAction action) throws IOException {
        markNextRunLevelLoadCause(RunLevelLoadCause.LEVEL_ADVANCE);
        action.load();
    }

    /** Reports a completed level load before any pending playback bridge activates. */
    public static void beforeRunLevelLoadPlaybackActivationIfActive() {
        var level = GameServices.level();
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        if (context == null) {
            return;
        }
        RunLevelLoadTracker.Receipt receipt = context.runLevelLoads()
                .observeLoaded(level).orElse(null);
        if (receipt == null) {
            return;
        }
        TraceSessionLauncher session = active();
        if (session == null || session.runCoordinator == null) {
            return;
        }
        session.runLevelLoadGeneration = receipt.identity().loadGeneration();
        int frame = session.currentRunBoundaryBk2Frame();
        RunPlaybackObservation.LevelIdentity identity = receipt.identity();
        RunLevelLoadCause cause = receipt.cause();
        RunBoundarySignal.LevelLoaded signal =
                new RunBoundarySignal.LevelLoaded(frame, cause, identity);
        session.runBoundaryProbe.observeSignal(signal);
        session.runCoordinator.observeBoundary(signal);
        session.runLevelLoadedDuringSourceProduction =
                session.productionIterationInProgress
                        && session.runCoordinator.phase()
                                == TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT;
        GameLoop loop = Engine.currentGameLoop();
        GameMode mode = loop != null ? loop.getCurrentGameMode() : GameMode.LEVEL;
        List<TraceRunPlaybackCoordinator.Action> actions =
                session.runCoordinator.beforeLoadedLevelActivation(
                        signal, session.captureRunObservation(mode,
                                session.destinationRowsConsumedForAdmission(),
                                false));
        session.applyRunCoordinatorActions(actions);
        session.scheduleAcceptedRunLevelDestinationIfNeeded(signal, actions);
    }

    /** Arms the level rebind only for the exact load accepted by run policy. */
    boolean scheduleAcceptedRunLevelDestinationIfNeeded(
            RunBoundarySignal.LevelLoaded signal,
            List<TraceRunPlaybackCoordinator.Action> actions) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(actions, "actions");
        if (!actions.isEmpty() || runCoordinator == null
                || !runCoordinator.remembersLevelLoad(signal)) {
            return false;
        }
        int destinationIndex = runCoordinator.currentSegmentIndex() + 1;
        if (destinationIndex < 0 || destinationIndex >= runSegments.size()) {
            return false;
        }
        TraceRunManifest.Segment destination =
                runSegments.get(destinationIndex).segment();
        if (!"level".equals(destination.kind())) {
            return false;
        }
        PlaybackDebugManager playback = GameServices.playbackDebug();
        if (!playback.isSessionPlaying()
                || playback.getCursorFrame() > destination.bk2FrameOffset()) {
            throw new IllegalStateException(
                    "accepted level load lost the continuous run clock before "
                            + destination.bk2FrameOffset() + ": cursor="
                            + playback.getCursorFrame());
        }
        return true;
    }

    /** Couples load notification with the existing pending playback activation. */
    public static boolean activateScheduledPlaybackForLoadedLevel(
            PlaybackDebugManager playback) {
        beforeRunLevelLoadPlaybackActivationIfActive();
        return playback.activateScheduledLevelLoadSession();
    }

    void installDynamicArtSegments(GameplayModeContext gameplayMode) {
        Objects.requireNonNull(gameplayMode, "gameplayMode");
        dynamicArtSegmentGameplayMode = gameplayMode;
        boolean[] firstWindow = {true};
        boolean[] ownershipAcquired = {false};
        TraceRunReplayWalker.DynamicArtSegmentController controller =
                new TraceRunReplayWalker.DynamicArtSegmentController(
                        new TraceRunReplayWalker.DynamicArtSegmentWindow() {
                            @Override
                            public void open() {
                                if (firstWindow[0]) {
                                    firstWindow[0] = false;
                                    gameplayMode.plcFrameLifecycle()
                                            .acquireExternalComparisonSegmentOwnershipAfterNextService();
                                    ownershipAcquired[0] = true;
                                } else {
                                    gameplayMode.dynamicArtLifecycle()
                                            .openComparisonSegment();
                                }
                            }

                            @Override
                            public void close() {
                                gameplayMode.plcFrameLifecycle()
                                        .closeExternallyManagedComparisonSegment();
                                if (runDynamicArtGapJournal != null
                                        && runClosingDynamicArtSegment >= 0) {
                                    runDynamicArtGapJournal.sourceClosed(
                                            runClosingDynamicArtSegment);
                                }
                            }
                        });
        runDynamicArtSegments = controller;
        try {
            controller.beginSegment();
            if (entry != null && entry.runManifest() != null) {
                runDynamicArtGapJournal = new TraceRunDynamicArtGapJournal(
                        entry.runManifest(), gameplayMode.dynamicArtLifecycle());
            }
        } catch (RuntimeException | Error failure) {
            runDynamicArtSegments = null;
            dynamicArtSegmentGameplayMode = null;
            if (ownershipAcquired[0]) {
                gameplayMode.plcFrameLifecycle()
                        .setComparisonSegmentsExternallyManaged(false);
            }
            throw failure;
        }
    }

    /** Compatibility seam retained for focused whole-run lifecycle tests. */
    void installRunDynamicArtSegments(GameplayModeContext gameplayMode) {
        installDynamicArtSegments(gameplayMode);
    }

    private void closeRunDynamicArtSegments() {
        GameplayModeContext gameplayMode = dynamicArtSegmentGameplayMode;
        try {
            if (runDynamicArtSegments != null) {
                runDynamicArtSegments.close();
            }
        } finally {
            if (gameplayMode != null) {
                gameplayMode.plcFrameLifecycle()
                        .setComparisonSegmentsExternallyManaged(false);
            }
            runDynamicArtSegments = null;
            dynamicArtSegmentGameplayMode = null;
            runDynamicArtGapJournal = null;
        }
    }

    private void abortRunDynamicArtSegments() {
        GameplayModeContext gameplayMode = dynamicArtSegmentGameplayMode;
        try {
            if (runDynamicArtSegments != null) {
                try {
                    runDynamicArtSegments.close();
                } catch (DynamicArtLifecycleService
                        .UnpublishedComparisonRowException incompleteWindow) {
                    if (gameplayMode == null) {
                        throw incompleteWindow;
                    }
                    gameplayMode.dynamicArtLifecycle()
                            .abandonComparisonSegment();
                }
            }
        } finally {
            if (gameplayMode != null) {
                gameplayMode.plcFrameLifecycle()
                        .setComparisonSegmentsExternallyManaged(false);
            }
            runDynamicArtSegments = null;
            dynamicArtSegmentGameplayMode = null;
            runDynamicArtGapJournal = null;
        }
    }

    /**
     * Marks the value-free production iteration boundary owned by
     * {@link GameLoop}. This carries no trace or gameplay value into a
     * production service; it only prevents terminal cleanup from running
     * before the matching post-finish snapshot pull.
     */
    void beforeProductionIteration() {
        productionIterationInProgress = true;
        productionIterationComparator = comparator;
        GameLoop loop = Engine.currentGameLoop();
        runProductionOwnerObservation = runCoordinator != null && loop != null
                ? captureRunObservation(
                        loop.getCurrentGameMode(), 0,
                        isLagOnlySameLevelContinuation())
                : null;
        var objects = GameServices.level().getObjectManager();
        runProductionOwnerVblank = runCoordinator != null && objects != null
                ? objects.getVblaCounter()
                : null;
        dynamicArtSnapshotBeforeIteration =
                GameServices.captureDynamicArtDiagnostics();
        dynamicArtDeliverySerialBeforeIteration =
                dynamicArtSnapshotBeforeIteration.deliverySerial();
    }

    static boolean isRunFrameDriverActive() {
        TraceSessionLauncher session = active();
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        boolean installed = context != null
                && context.traceRunFrameDriver().isPresent();
        return installed && (session == null || (session.runFrameDriver != null
                && session.runCoordinator != null
                && !session.fadeStarted
                && session.runCoordinator.phase()
                        != TraceRunPlaybackCoordinator.Phase.COMPLETE
                && session.runCoordinator.phase()
                        != TraceRunPlaybackCoordinator.Phase.FAILED));
    }

    public static boolean allowsRunLogicalGameplayInput() {
        TraceSessionLauncher session = active();
        if (!isRunFrameDriverActive()) {
            return true;
        }
        if (session != null) {
            return session.activeRunDisposition
                    == TraceRunFrameDriver.Disposition.GAMEPLAY_SHARED;
        }
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        return context != null
                && context.traceRunFrameDriver()
                        .map(TraceRunFrameDriver::currentDisposition)
                        .orElse(null)
                        == TraceRunFrameDriver.Disposition.GAMEPLAY_SHARED;
    }

    static boolean admitsRunLogicalGameplayInput(GameMode mode) {
        return (mode == GameMode.LEVEL || mode == GameMode.BONUS_STAGE)
                && allowsRunLogicalGameplayInput();
    }

    static boolean suppressesRunNativeLevelBody(GameMode mode) {
        return mode == GameMode.LEVEL
                && isRunFrameDriverActive()
                && !allowsRunLogicalGameplayInput();
    }

    /**
     * True while a shared transition gap's row is still owned by the source
     * level's own main loop, so the ordinary level body must run on it.
     *
     * <p>A gap is a gap in the <em>recording</em>, not in the ROM. A run
     * recorder finalizes a level segment on the first frame whose sampled game
     * mode has left the level
     * ({@code tools/tracechaser/bizhawk-headless/src/Recording/S1RunCaptureRunner.cs}:285-296,
     * {@code S2RunCaptureRunner.cs}:311), and that frame's level iteration ran:
     * the write that left the level came from inside its own object pass, and
     * the loop tests for it on the very next instruction
     * (docs/s1disasm/sonic.asm:3009-3018, docs/s2disasm/s2.asm:5095-5097,
     * docs/skdisasm/sonic3k.asm:7894-7896). So the first unrecorded row after a
     * segment is an ordinary level iteration, and suppressing it stops the
     * level from ever reaching the write that ends it — a Sonic 1 death's
     * sixtieth {@code Sonic_ResetLevel} decrement lands exactly there
     * (docs/s1disasm/_incObj/01 Sonic.asm:2062-2073).
     *
     * <p>It is exactly one row. The write that ends the loop is what stopped
     * the recorder, so it lands on the gap's FIRST row and no other; from the
     * second row on the gap belongs to the game's blocking exit/entry routine,
     * which never returns to the loop it left. And if the engine already holds
     * that write when the gap opens — a Special Stage entry arms one iteration
     * ahead, an end-of-act card starts its fade from the segment's last
     * recorded row — then even the first row is past the loop's end. Those rows
     * service only the exit routine's own mode transitions.
     *
     * @param levelExitWritten whether the level has already been asked to stop
     *                         being the level on this row
     */
    static boolean runGapRowContinuesSourceLevelMainLoop(
            GameMode mode, boolean levelExitWritten) {
        TraceSessionLauncher session = active();
        if (session != null) {
            if (session.activeRunDisposition
                    != TraceRunFrameDriver.Disposition.SHARED_GAP) {
                return false;
            }
            boolean firstGapRow = !session.runGapSourceLevelMainLoopEnded;
            session.runGapSourceLevelMainLoopEnded = true;
            return firstGapRow
                    && !levelExitWritten
                    && suppressesRunNativeLevelBody(mode);
        }
        // Driver-only run replay (no launcher session): the same rule, read
        // from the installed run frame driver. Both drivers implement one
        // contract and must not disagree about which gap rows belong to the
        // source level's own main loop.
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        TraceRunFrameDriver.Disposition disposition = context == null
                ? null
                : context.traceRunFrameDriver()
                        .map(TraceRunFrameDriver::currentDisposition)
                        .orElse(null);
        if (disposition != TraceRunFrameDriver.Disposition.SHARED_GAP) {
            return false;
        }
        boolean firstGapRow = context.consumeRunGapFirstRow();
        return firstGapRow
                && !levelExitWritten
                && suppressesRunNativeLevelBody(mode);
    }

    /**
     * Announces the start of a shared transition gap for a driver-only run
     * replay, the counterpart of the launcher's
     * {@code EnterTransitionGap} coordinator action
     * ({@link #runGapSourceLevelMainLoopEnded} reset above). It carries no
     * trace data and decides nothing about the gap's content: it only re-arms
     * the one-row latch that
     * {@link #runGapRowContinuesSourceLevelMainLoop} consumes.
     *
     * <p>The latch lives on the current session's {@link GameplayModeContext},
     * not in a static: a static one is armed at some gaps and consumed at
     * others, so the answer a gap gets depends on what ran before it -- across
     * gaps within a run, and across test classes sharing a surefire fork.
     */
    public static void beginDriverOnlyRunTransitionGap() {
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        if (context != null) {
            context.beginRunTransitionGap();
        }
    }

    static boolean shouldSkipRunGameplayTick(
            PlaybackDebugManager playback) {
        return allowsRunLogicalGameplayInput()
                && playback.shouldSkipCurrentGameplayTick();
    }

    static boolean commitDeferredRunModeBoundary(
            GameMode mode,
            SpecialStageProvider provider,
            Consumer<Boolean> enterResultsScreen) {
        TraceSessionLauncher session = active();
        if (mode != GameMode.SPECIAL_STAGE
                || provider == null
                || !provider.isFinished()
                || (session != null && session.isSpecialStageSession())) {
            return false;
        }
        enterResultsScreen.accept(provider.isEmeraldCollected());
        return true;
    }

    /**
     * True when the represented production row begins a recorded synchronous
     * overrun. Mode owners retain their completed boundary until the first
     * later production row while intervening physical rows advance only.
     */
    public static boolean shouldDeferRunModeBoundaryCommit() {
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        return context != null
                && context.traceRunFrameDriver()
                        .map(TraceRunFrameDriver
                                ::defersBoundaryCommitAfterCurrentRow)
                        .orElse(false);
    }

    /**
     * Marks the current run row as a mode handoff when production entered a
     * Special Stage at the destination segment's physical offset. The
     * destination's local row driver must consume that offset next; advancing
     * it here would make input and hardware timing one row late.
     */
    static void deferRunPhysicalRowForSpecialStageEntry(
            GameMode previousMode, GameMode nextMode) {
        if (previousMode != GameMode.LEVEL
                || nextMode != GameMode.SPECIAL_STAGE) {
            return;
        }
        TraceSessionLauncher session = active();
        if (session != null) {
            session.deferRunPhysicalRowForSpecialStageEntry();
        }
    }

    private void deferRunPhysicalRowForSpecialStageEntry() {
        if (runCoordinator == null || runFrameDriver == null
                || runFrameDriver.currentDisposition()
                        != TraceRunFrameDriver.Disposition.SHARED_GAP
                || runCoordinator.phase()
                        != TraceRunPlaybackCoordinator.Phase.TRANSITION_GAP) {
            return;
        }
        int sourceIndex = runCoordinator.currentSegmentIndex();
        int destinationIndex = sourceIndex + 1;
        if (sourceIndex < 0 || destinationIndex >= runSegments.size()
                || !"special_stage".equals(
                        runSegments.get(destinationIndex).segment().kind())) {
            return;
        }
        int cursor = GameServices.playbackDebug().getCursorFrame();
        int destinationOffset = runSegments.get(destinationIndex)
                .segment().bk2FrameOffset();
        if (cursor > destinationOffset) {
            throw new IllegalStateException(
                    "Special Stage entry crossed destination physical row: "
                            + "cursor=" + cursor + ", destinationOffset="
                            + destinationOffset);
        }
        if (cursor == destinationOffset) {
            runPhysicalRowAdvanceDeferred = true;
        }
    }

    private boolean consumeDeferredRunPhysicalRowAdvance() {
        if (!runPhysicalRowAdvanceDeferred) {
            return false;
        }
        runPhysicalRowAdvanceDeferred = false;
        return true;
    }

    static void runProductionIterationIfActive(
            Runnable productionIteration,
            Runnable advanceRunPhysicalRow) {
        TraceSessionLauncher session = active();
        if (session == null || !isRunFrameDriverActive()) {
            productionIteration.run();
            return;
        }
        Objects.requireNonNull(productionIteration, "productionIteration");
        Objects.requireNonNull(advanceRunPhysicalRow, "advanceRunPhysicalRow");
        Throwable primaryFailure = null;
        try {
            session.driveRunPhysicalRow(
                    productionIteration, advanceRunPhysicalRow);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
        }
        if (primaryFailure == null) {
            return;
        }
        Throwable contained = session.abortIncompleteSession(
                primaryFailure, "visual trace production iteration failed", null);
        if (session.entry != null) {
            TraceLaunchStatus.record(session.entry,
                    contained != null ? contained : primaryFailure);
        }
        if (contained instanceof Error fatal) {
            throw fatal;
        }
        LOGGER.log(java.util.logging.Level.SEVERE,
                "Visual trace session aborted after a replay failure", contained);
    }

    /** Compatibility seam for focused tests and standalone trace callers. */
    static void runProductionIterationIfActive(Runnable productionIteration) {
        runProductionIterationIfActive(productionIteration, () -> {
        });
    }

    private void driveRunPhysicalRow(
            Runnable productionIteration,
            Runnable advanceRunPhysicalRow) {
        GameLoop loop = Engine.currentGameLoop();
        GameMode mode = loop != null
                ? loop.getCurrentGameMode() : GameMode.LEVEL;
        TraceRunFrameDriver.Step step = currentRunFrameStep();
        runFrameDriver.execute(step,
                new TraceRunFrameDriver.Hooks<DynamicArtDiagnosticsSnapshot>() {
                    @Override
                    public void preparePhysicalRow(TraceRunFrameDriver.Step row) {
                        activeRunDisposition = row.disposition();
                        // Gap edges are stamped with the physical movie row, so
                        // the driver states it rather than letting the lifecycle
                        // infer it from production iterations it never runs
                        // during a suppressed gap.
                        if (dynamicArtSegmentGameplayMode != null) {
                            dynamicArtSegmentGameplayMode.dynamicArtLifecycle()
                                    .setMovieLogicalFrame(row.movieRow());
                        }
                        Bk2FrameInput physical;
                        if (row.disposition()
                                == TraceRunFrameDriver.Disposition.GAMEPLAY_SHARED) {
                            GameServices.playbackDebug().prepareCurrentFrame();
                            physical = GameServices.playbackDebug()
                                    .currentFrameOrThrow();
                        } else {
                            physical = GameServices.playbackDebug()
                                    .currentFrameOrThrow();
                        }
                        if (physical.frameIndex() != row.movieRow()) {
                            throw new IllegalStateException(
                                    "physical run row changed during admission: expected "
                                            + row.movieRow() + ", got "
                                            + physical.frameIndex());
                        }
                        runLastPhysicalInput = physical;
                        if (usesDriverOwnedPhysicalInput(row.disposition())) {
                            applyRunPhysicalInput(loop, physical);
                        }
                        if (row.disposition()
                                == TraceRunFrameDriver.Disposition
                                        .PRESENTATION_VBLANK
                                || row.disposition()
                                == TraceRunFrameDriver.Disposition
                                        .PRESENTATION_SUPPRESSED_CLOSURE
                                || row.disposition()
                                == TraceRunFrameDriver.Disposition
                                        .PRESENTATION_ADVANCE_ONLY) {
                            if (runStructuralComparator == null) {
                                throw new IllegalStateException(
                                        "presentation row has no structural comparator");
                            }
                            runStructuralComparator.prepareRow(
                                    physical);
                        }
                    }

                    @Override
                    public void prepareHardwareTiming(TraceRunFrameDriver.Step row) {
                        prepareRunFrameHardwareTiming(row.disposition(), mode);
                    }

                    @Override
                    public DynamicArtDiagnosticsSnapshot captureBefore(
                            TraceRunFrameDriver.Step row) {
                        if (row.disposition().runsProductionLifecycle()) {
                            beforeProductionIteration();
                        }
                        return GameServices.captureDynamicArtDiagnostics();
                    }

                    @Override
                    public void runProductionLifecycle(TraceRunFrameDriver.Step row) {
                        if (row.disposition()
                                == TraceRunFrameDriver.Disposition
                                        .PRESENTATION_SUPPRESSED_CLOSURE) {
                            TraceRunPresentationClosure.execute(loop, row);
                        } else {
                            productionIteration.run();
                        }
                    }

                    @Override
                    public boolean shouldAdvancePhysicalRow(
                            TraceRunFrameDriver.Step row) {
                        return row.disposition()
                                != TraceRunFrameDriver.Disposition.SHARED_GAP
                                || !consumeDeferredRunPhysicalRowAdvance();
                    }

                    @Override
                    public void advancePhysicalRow(TraceRunFrameDriver.Step row) {
                        advanceRunPhysicalRow.run();
                        if (row.disposition()
                                == TraceRunFrameDriver.Disposition.TERMINAL_TAIL) {
                            runTerminalRowAdvanced = true;
                            runTerminalMovieEndReached =
                                    row.terminalSegmentRow();
                        }
                    }

                    @Override
                    public DynamicArtDiagnosticsSnapshot captureAfter(
                            TraceRunFrameDriver.Step row) {
                        return GameServices.captureDynamicArtDiagnostics();
                    }

                    @Override
                    public void compare(
                            TraceRunFrameDriver.Step row,
                            DynamicArtDiagnosticsSnapshot before,
                            DynamicArtDiagnosticsSnapshot after) {
                        if (row.disposition().runsProductionLifecycle()) {
                            afterProductionIteration();
                        }
                        if (row.disposition()
                                == TraceRunFrameDriver.Disposition
                                        .PRESENTATION_VBLANK
                                || row.disposition()
                                == TraceRunFrameDriver.Disposition
                                        .PRESENTATION_SUPPRESSED_CLOSURE
                                || row.disposition()
                                == TraceRunFrameDriver.Disposition
                                        .PRESENTATION_ADVANCE_ONLY) {
                            FrameComparison result = runStructuralComparator
                                    .completePostProduction(
                                            before, after,
                                            row.disposition()
                                                    .runsProductionLifecycle(),
                                            !row.observedVblankCounterAdvance());
                            ingestRunUndisplayedComparison(result);
                        }
                    }

                    @Override
                    public void afterStep(TraceRunFrameDriver.Step row) {
                        if (usesDriverOwnedPhysicalInput(row.disposition())) {
                            clearRunOwnedInputOverride();
                        }
                        activeRunDisposition = null;
                    }
                });
    }

    private static boolean usesDriverOwnedPhysicalInput(
            TraceRunFrameDriver.Disposition disposition) {
        return switch (disposition) {
            case PRESENTATION_VBLANK, PRESENTATION_SUPPRESSED_CLOSURE,
                    PRESENTATION_ADVANCE_ONLY, SHARED_GAP, TERMINAL_TAIL -> true;
            case GAMEPLAY_SHARED, SPECIAL_LOCAL, OFFSET_HANDOFF -> false;
        };
    }

    private void applyRunPhysicalInput(
            GameLoop loop, Bk2FrameInput current) {
        if (loop == null) {
            throw new IllegalStateException(
                    "physical run input has no active GameLoop");
        }
        int previousIndex = current.frameIndex() - 1;
        Bk2FrameInput previous = previousIndex >= 0
                ? movie.getFrame(previousIndex) : null;
        InputHandler input = loop.getInputHandler();
        input.setLogicalOverride(
                RecordedInputSnapshots.fromBk2(current, previous));
        runOwnedInputHandler = input;
    }

    private TraceRunFrameDriver.Step currentRunFrameStep() {
        GameLoop loop = Engine.currentGameLoop();
        int movieRow = Math.max(0,
                GameServices.playbackDebug().getCursorFrame());
        TraceRunPlaybackCoordinator.Phase phase = runCoordinator.phase();
        int segmentIndex = runCoordinator.currentSegmentIndex();
        TraceRunReplayWalker.SegmentExecutionPolicy policy =
                segmentIndex >= 0 && segmentIndex < runSegments.size()
                        ? runSegments.get(segmentIndex).executionPolicy()
                        : TraceRunReplayWalker.SegmentExecutionPolicy.GAMEPLAY;
        TraceExecutionPhase rowPhase = TraceExecutionPhase.VBLANK_ONLY;
        boolean observedVblankCounterAdvance = true;
        boolean previousObservedVblankCounterAdvance = true;
        boolean nextRowCarriesDeferredVblank = false;
        boolean terminalRow = false;
        boolean deferBoundaryCommit = false;
        if (phase == TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT
                && segmentIndex >= 0 && segmentIndex < runSegments.size()) {
            TraceRunSegmentDescriptor plan = runSegments.get(segmentIndex);
            int localRow = policy
                            == TraceRunReplayWalker.SegmentExecutionPolicy.SPECIAL_LOCAL
                    ? runSpecialLocalRow
                    : movieRow - plan.segment().bk2FrameOffset();
            if (policy
                    == TraceRunReplayWalker.SegmentExecutionPolicy
                            .LEVEL_PRESENTATION_BRIDGE) {
                if (localRow < 0 || localRow >= plan.rowCount()) {
                    throw new IllegalStateException(
                            "presentation row " + localRow + " outside segment "
                                    + segmentIndex);
                }
                // One implementation of the bridge-stepping contract, shared
                // with the headless chain harness. terminalSegmentRow stays
                // this caller's own: it comes from the manifest's segment row
                // count here and from the loaded fixture's row count there.
                return TraceRunFrameDriver.presentationBridgeStep(
                        activeRunTrace(segmentIndex), localRow, movieRow, policy,
                        loop != null
                                && loop.getCurrentGameMode() == GameMode.LEVEL,
                        localRow == plan.segment().traceFrameCount() - 1);
            }
            terminalRow = localRow == plan.segment().traceFrameCount() - 1;
        } else if (phase
                == TraceRunPlaybackCoordinator.Phase.TERMINAL_TAIL) {
            terminalRow = movieRow == movie.getFrameCount() - 1;
        }
        TraceRunFrameDriver.Disposition disposition =
                TraceRunFrameDriver.selectDisposition(
                        phase, policy, rowPhase,
                        observedVblankCounterAdvance,
                        previousObservedVblankCounterAdvance,
                        loop != null
                                && loop.getCurrentGameMode() == GameMode.LEVEL,
                        nextRowCarriesDeferredVblank,
                        // Only a presentation-bridge row can be a recorded lag
                        // V-int, and that path returned above.
                        false);
        boolean commitDeferredBoundaryAfterClosure = TraceRunFrameDriver
                .shouldCommitDeferredBoundaryAfterClosure(
                        previousObservedVblankCounterAdvance,
                        observedVblankCounterAdvance);
        return new TraceRunFrameDriver.Step(
                disposition, movieRow, terminalRow,
                deferBoundaryCommit, commitDeferredBoundaryAfterClosure,
                observedVblankCounterAdvance);
    }

    private void prepareRunFrameHardwareTiming(
            TraceRunFrameDriver.Disposition disposition,
            GameMode mode) {
        if (fixture == null || runHardwareTiming == null) {
            return;
        }
        switch (disposition) {
            case GAMEPLAY_SHARED, PRESENTATION_VBLANK,
                    PRESENTATION_SUPPRESSED_CLOSURE,
                    PRESENTATION_ADVANCE_ONLY ->
                    runHardwareTiming.beginPlaybackFrame(
                            GameServices.playbackDebug().currentFrameOrThrow());
            case SPECIAL_LOCAL -> prepareRunSpecialStageHardwareTimingRow();
            case SHARED_GAP, TERMINAL_TAIL ->
                    fixture.enterHardwareTimingGap();
            case OFFSET_HANDOFF -> throw new IllegalStateException(
                    "offset handoff cannot prepare hardware timing");
        }
    }

    /**
     * Pulls one immutable diagnostics snapshot after the production
     * coordinator has finished publishing the logical iteration, then drains
     * terminal actions requested by the iteration body.
     */
    void afterProductionIteration() {
        if (!productionIterationInProgress) {
            throw new IllegalStateException(
                    "dynamic-art post-iteration hook has no active iteration");
        }
        productionIterationInProgress = false;
        LiveTraceComparator iterationComparator = productionIterationComparator;
        DynamicArtDiagnosticsSnapshot before = dynamicArtSnapshotBeforeIteration;
        productionIterationComparator = null;
        dynamicArtSnapshotBeforeIteration = null;
        if (iterationComparator != null && before != null) {
            iterationComparator.consumePostProductionPlayableAnimationAction();
            DynamicArtDiagnosticsSnapshot after =
                    GameServices.captureDynamicArtDiagnostics();
            iterationComparator.publishPendingDynamicArtComparison(
                    before, after);
        }
        publishPendingRunSpecialStageRow();
        drainPendingRunBoundaryActions();
        if (runSpecialDynamicArtPendingRow >= 0) {
            DynamicArtDiagnosticsSnapshot snapshot =
                    GameServices.captureDynamicArtDiagnostics();
            if (snapshot.deliverySerial()
                    <= dynamicArtDeliverySerialBeforeIteration) {
                throw new IllegalStateException(
                        "advertised visual special-stage DPLC row "
                                + runSpecialDynamicArtPendingRow
                                + " was admitted but production published no snapshot");
            }
            if (!snapshot.published()
                    || snapshot.segmentGeneration()
                            != runSpecialDynamicArtTargetGeneration
                    || snapshot.frame() != runSpecialDynamicArtPendingRow) {
                throw new IllegalStateException(
                        "advertised visual special-stage DPLC row "
                                + runSpecialDynamicArtPendingRow
                                + " did not receive an atomic publication for "
                                + "generation "
                                + runSpecialDynamicArtTargetGeneration);
            }
            comparePublishedRunSpecialDynamicArtRow(snapshot);
        }
        if (runEndPending) {
            finishPendingRunEnd();
        }
        if (completionStartPending) {
            finishPendingCompletionStart();
        }
        retryPendingTeardown();
    }

    private void publishPendingRunSpecialStageRow() {
        if (runSpecialRowDriver == null || !runSpecialRowDriver.hasPendingRow()) {
            return;
        }
        runSpecialRowDriver.publishAdmittedRow(
                        GameServices.captureDynamicArtDiagnostics())
                .ifPresent(this::ingestRunSpecialDynamicArtComparison);
    }

    private void drainPendingRunBoundaryActions() {
        if (runSpecialVerificationPending) {
            runSpecialVerificationPending = false;
            verifyRunSpecialDynamicArtComplete();
        }
        if (runGapEntryPending) {
            runGapEntryPending = false;
            enterRunDynamicArtGap();
        }
        RunSegmentAdvancer.AdvanceAction advance = runAdvancePending;
        runAdvancePending = null;
        if (advance != null) {
            applyRunAdvanceAfterProduction(advance);
        }
    }

    private void verifyRunSpecialDynamicArtComplete() {
        if (runSpecialRowDriver != null) {
            runSpecialRowDriver.verifyComplete();
            return;
        }
        requireNoPendingRunSpecialDynamicArtRow();
        runSpecialDynamicArtComparison.verifyComplete();
    }

    private void enterRunDynamicArtGap() {
        Throwable failure = null;
        try {
            runDynamicArtSegments.enterGap();
            if (comparator != null) {
                comparator.finalizeTerminalDynamicArtComparison();
            }
        } catch (RuntimeException | Error gapFailure) {
            failure = gapFailure;
        } finally {
            failure = detachAndCloseRunPayload(failure);
        }
        throwUnchecked(failure);
    }

    private void applyRunAdvanceAfterProduction(
            RunSegmentAdvancer.AdvanceAction action) {
        try {
            boolean anticipated = runAdvancerAnticipatedSegment
                    == action.nextSegmentIndex();
            if (anticipated) {
                requireActiveRunPayload(action.nextSegmentIndex());
            } else {
                Throwable closeFailure = detachAndCloseRunPayload(null);
                throwUnchecked(closeFailure);
                if (runHardwareTiming != null) {
                    // The advancer drive's counterpart to the coordinator
                    // drive's admission receipt: this is where it enters the
                    // next segment, so this is where it declares membership.
                    // Like the receipt path, the handoff completes before any
                    // destination owner opens below.
                    runHardwareTiming.enterSegment(action.nextSegmentIndex());
                }
                openRunPayload(action.nextSegmentIndex());
            }
            applyRunSegmentAdvance(action);
            armRunSpecialDynamicArtComparison(action.nextSegmentIndex());
            if (runDynamicArtSegments == null) {
                runAdvancerAnticipatedSegment = -1;
                return;
            }
            if (runSpecialDynamicArtSegmentAnticipated
                    && runSpecialTimingSegment == action.nextSegmentIndex()) {
                runSpecialDynamicArtSegmentAnticipated = false;
                runAdvancerAnticipatedSegment = -1;
                return;
            }
            runDynamicArtSegments.beginSegment();
            bindRunSpecialDynamicArtTargetGeneration();
            runAdvancerAnticipatedSegment = -1;
        } catch (RuntimeException | Error attachmentFailure) {
            runAdvancerAnticipatedSegment = -1;
            Throwable failure = detachAndCloseRunPayload(attachmentFailure);
            throwUnchecked(failure);
        }
    }

    private void finishPendingCompletionStart() {
        completionStartPending = false;
        if (fixture != null) {
            fixture.runTerminalDynamicArtIteration();
            closeRunDynamicArtSegments();
            comparator.finalizeTerminalDynamicArtComparison();
            fixture.closeHardwareTimingReplayRun();
        }
        completionArmed = true;
        completionHoldFrames =
                (int) Math.round(COMPLETION_HOLD_SECONDS * 60.0);
    }

    private void finishPendingRunEnd() {
        runEndPending = false;
        Throwable failure = cleanupFailure(
                null, this::closeRunDynamicArtSegments);
        if (comparator != null) {
            failure = cleanupFailure(failure,
                    comparator::finalizeTerminalDynamicArtComparison);
        }
        if (fixture != null) {
            failure = cleanupFailure(
                    failure, fixture::closeHardwareTimingReplayRun);
        }
        failure = detachAndCloseRunPayload(failure);
        failure = cleanupFailure(failure, this::startFadeOut);
        throwUnchecked(failure);
    }

    void installSpecialStageHardwareTiming(TraceReplayFixture replayFixture) {
        fixture = replayFixture;
        if (ssTrace != null
                && ssTrace.hardwareTimingSchedule().hasRecordedInput()) {
            TraceReplaySessionBootstrap.installHardwareTimingReplay(
                    ssTrace.hardwareTimingSchedule(), replayFixture);
        }
    }

    /**
     * Selects represented hardware-timing authority before iteration
     * admission, so paused VBlank service and transition/title-card scans
     * cannot observe the previous row's latch.
     */
    public void prepareHardwareTimingForAdmission(GameMode mode) {
        if (fixture == null) {
            return;
        }
        if (fadeStarted) {
            fixture.enterHardwareTimingGap();
            return;
        }
        if (comparator != null
                && (mode == GameMode.LEVEL || mode == GameMode.BONUS_STAGE)) {
            comparator.activatePreparedRow();
        }
        if ((runAdvancer != null || runCoordinator != null)
                && runHardwareTiming != null) {
            if (mode == GameMode.LEVEL
                    || mode == GameMode.BONUS_STAGE) {
                runHardwareTiming.beginPlaybackFrame(
                        GameServices.playbackDebug().currentFrameOrThrow());
            } else if (RunPlaybackObservation
                    .insideRecordedSpecialStageMode(mode)
                    && currentRunSegmentIsRecordedSpecialStage()) {
                // The recorded segment owns the ROM's whole
                // GameModeID_SpecialStage span, results tail included
                // (s2.asm:6721-6800) -- so its rows keep being driven by the
                // segment's own row driver across the engine's internal
                // SPECIAL_STAGE -> SPECIAL_STAGE_RESULTS boundary. The driver
                // falls back to a hardware-timing gap once it is complete.
                prepareRunSpecialStageHardwareTimingRow();
            } else if (RunPlaybackObservation
                    .insideRecordedSpecialStageMode(mode)) {
                // A special-stage MODE row inside a non-special-stage segment
                // is an ordinary shared-playback row: it advances one row per
                // frame against the shared movie cursor, like LEVEL and
                // BONUS_STAGE above, so it takes the same authority rather
                // than a segment-local cursor.
                runHardwareTiming.beginPlaybackFrame(
                        GameServices.playbackDebug().currentFrameOrThrow());
            } else {
                fixture.enterHardwareTimingGap();
            }
            return;
        }
        if (ssTrace != null) {
            if (mode == GameMode.SPECIAL_STAGE
                    && ssTrace.hardwareTimingSchedule().hasRecordedInput()
                    && ssCursor >= 0
                    && ssCursor < ssTrace.rowCount()) {
                fixture.beginTraceRow(ssCursor, ssCursor);
            } else {
                fixture.enterHardwareTimingGap();
            }
            return;
        }
        if (trace != null
                && trace.hardwareTimingSchedule().hasRecordedInput()
                && mode == GameMode.LEVEL
                && comparator != null) {
            int cursor = comparator.cursor();
            if (cursor >= 0 && cursor < trace.frameCount()) {
                fixture.beginTraceRow(
                        cursor, trace.getFrame(cursor).frame());
                return;
            }
        }
        fixture.enterHardwareTimingGap();
    }

    public void deactivateHardwareTimingForAdmission() {
        if (fixture != null) {
            fixture.enterHardwareTimingGap();
        }
    }

    /** Commits replay production classification for an admitted pause VBlank. */
    static void activateProductionMarkerForPausedBoundaryIfActive() {
        if (activeSession != null && activeSession.comparator != null) {
            activeSession.comparator.activatePreparedProductionMarker();
        }
    }

    /**
     * Latches metadata-only special-stage rows before their production update.
     * The run advancer changes segments after the update, so the first stage
     * frame anticipates the immediately following structural segment.
     */
    private void prepareRunSpecialStageHardwareTimingRow() {
        try {
            prepareRunSpecialStageHardwareTimingRowOwned();
        } catch (RuntimeException | Error preparationFailure) {
            runAdvancerAnticipatedSegment = -1;
            runSpecialDynamicArtSegmentAnticipated = false;
            Throwable failure = detachAndCloseRunPayload(preparationFailure);
            throwUnchecked(failure);
        }
    }

    private void prepareRunSpecialStageHardwareTimingRowOwned() {
        requireNoPendingRunSpecialDynamicArtRow();
        int segmentIndex = currentRunSegmentIndex();
        if (runCoordinator != null) {
            if (currentRunSpecialAdmission().isEmpty()) {
                fixture.enterHardwareTimingGap();
                return;
            }
            if (!runSpecialRowDriver.hasPendingRow()) {
                runSpecialRowDriver.admitCurrentRow(
                        GameServices.captureDynamicArtDiagnostics());
            }
            SpecialStageRowAdmission admission =
                    runSpecialRowDriver.currentPolicy();
            if (admission.admitHardwareTiming()) {
                runHardwareTiming.beginSegmentRow(
                        segmentIndex, runSpecialRowDriver.cursor());
            } else {
                fixture.enterHardwareTimingGap();
            }
            return;
        }
        if (!"special_stage".equals(runSegments.get(segmentIndex).segment().kind())
                && segmentIndex + 1 < runSegments.size()
                && "special_stage".equals(runSegments.get(segmentIndex + 1)
                        .segment().kind())) {
            segmentIndex++;
        }
        if (!"special_stage".equals(
                runSegments.get(segmentIndex).segment().kind())) {
            fixture.enterHardwareTimingGap();
            return;
        }
        if (currentRunSegmentIndex() != segmentIndex
                && runAdvancerAnticipatedSegment != segmentIndex) {
            runHardwareTiming.enterSegment(segmentIndex);
            openRunPayload(segmentIndex);
            runAdvancerAnticipatedSegment = segmentIndex;
        }
        if (runSpecialTimingSegment != segmentIndex) {
            armRunSpecialDynamicArtComparison(segmentIndex);
        }
        if (currentRunSegmentIndex() != segmentIndex
                && runDynamicArtSegments != null
                && !runSpecialDynamicArtSegmentAnticipated) {
            runDynamicArtSegments.beginSegment();
            bindRunSpecialDynamicArtTargetGeneration();
            runSpecialDynamicArtSegmentAnticipated = true;
        }
        int rowCount =
                runSegments.get(segmentIndex).segment().traceFrameCount();
        if (runSpecialTimingRow < rowCount) {
            runSpecialDynamicArtPendingRow = runSpecialTimingRow;
            runHardwareTiming.beginSegmentRow(
                    segmentIndex, runSpecialTimingRow++);
        } else {
            fixture.enterHardwareTimingGap();
        }
    }

    private void armRunSpecialDynamicArtComparison(int segmentIndex) {
        if (!"special_stage".equals(
                runSegments.get(segmentIndex).segment().kind())) {
            return;
        }
        if (runSpecialTimingSegment == segmentIndex
                && runSpecialDynamicArtComparison != null) {
            return;
        }
        requireNoPendingRunSpecialDynamicArtRow();
        if (runSpecialDynamicArtComparison != null) {
            runSpecialDynamicArtComparison.verifyComplete();
        }
        runSpecialTimingSegment = segmentIndex;
        runSpecialTimingRow = 0;
        runSpecialDynamicArtPendingRow = -1;
        runSpecialDynamicArtTargetGeneration = -1;
        runSpecialDynamicArtComparison =
                new TraceRunReplayWalker.DynamicArtSegmentComparison(
                        activeRunTrace(segmentIndex),
                        runSegments.get(segmentIndex).segment()
                                .traceFrameCount());
        if (currentRunSegmentIndex() == segmentIndex
                && runDynamicArtSegments != null) {
            bindRunSpecialDynamicArtTargetGeneration();
        }
    }

    private void bindRunSpecialDynamicArtTargetGeneration() {
        if (runSpecialDynamicArtComparison != null) {
            runSpecialDynamicArtTargetGeneration =
                    GameServices.captureDynamicArtDiagnostics()
                            .segmentGeneration();
        }
    }

    /**
     * Receives one immutable row only after the production lifecycle has
     * published it. The run advancer already ran inside the logical iteration,
     * so row zero reaches the comparator installed by that iteration's
     * {@link RunSegmentAdvancer.AdvanceAction}.
     */
    private void comparePublishedRunSpecialDynamicArtRow(
            DynamicArtDiagnosticsSnapshot published) {
        if (runSpecialDynamicArtPendingRow < 0
                || runSpecialDynamicArtComparison == null) {
            return;
        }
        int row = runSpecialDynamicArtPendingRow;
        int rowCount = runSegments.get(runSpecialTimingSegment)
                .segment().traceFrameCount();
        runSpecialDynamicArtPendingRow = -1;
        DynamicArtDiagnosticsSnapshot actual = published;
        if (row == rowCount - 1 && runDynamicArtSegments != null) {
            enterRunDynamicArtGapForSegment(runSpecialTimingSegment);
            actual = GameServices.captureDynamicArtDiagnostics();
        }
        FrameComparison result = runSpecialDynamicArtComparison.compareRow(
                row, actual);
        if (row == rowCount - 1) {
            runSpecialDynamicArtComparison.verifyComplete();
        }
        ingestRunSpecialDynamicArtComparison(result);
    }

    private void requireNoPendingRunSpecialDynamicArtRow() {
        if (runSpecialDynamicArtPendingRow >= 0) {
            throw new IllegalStateException(
                    "advertised visual special-stage DPLC row "
                            + runSpecialDynamicArtPendingRow
                            + " was admitted but production published no snapshot");
        }
    }

    private void ingestRunSpecialDynamicArtComparison(
            FrameComparison result) {
        if (result == null) {
            return;
        }
        if (runExternalDiagnostics != null) {
            runExternalDiagnostics.accept(result);
            return;
        }
        if (comparator == null) {
            throw new IllegalStateException(
                    "advertised visual special-stage DPLC row has no "
                            + "live comparison report sink");
        }
        comparator.ingestExternalComparison(result);
    }

    /**
     * Applies a segment-advance {@link RunSegmentAdvancer.AdvanceAction}: keeps
     * playback at the next segment's BK2 offset and rebinds EVERYTHING that
     * captured the old comparator — the comparator field itself (read by
     * ghost rendering), the camera focus controller and HUD overlay (rebuilt
     * and re-registered, mirroring {@link #finishRunLaunch}), and the
     * playback frame observer. A stale binding would keep showing the
     * previous segment's counts.
     */
    private void applyRunSegmentAdvance(RunSegmentAdvancer.AdvanceAction action) {
        GameLoop loop = Engine.currentGameLoop();
        if (loop == null) {
            return;
        }
        TraceRunSegmentDescriptor segment = runSegments.get(action.nextSegmentIndex());
        // firstErrorCallback = loop::toggleUserPause, matching the live
        // TraceReplayDriver constructor's onComparatorPause (segment 0's
        // comparator, built via TraceReplayDriver.start, gets the same
        // callback) -- every segment must pause on its first divergence,
        // not just segment 0.
        this.comparator = new LiveTraceComparator(
                activeRunTrace(action.nextSegmentIndex()),
                ToleranceConfig.DEFAULT, 0,
                loop::getMainPlayableSprite, loop::toggleUserPause,
                runExternalDiagnostics != null
                        ? runExternalDiagnostics::acceptDisplayed : null);
        this.cameraFocusController = new TraceCameraFocusController(
                comparator,
                loop::getMainPlayableSprite,
                () -> {
                    var sprites = GameServices.spritesOrNull();
                    if (sprites == null) return null;
                    var sks = sprites.getSidekicks();
                    return sks.isEmpty() ? null : sks.get(0);
                },
                GameServices::camera,
                GameServices.configuration(),
                loop::isPaused);
        loop.setTraceCameraFocusController(cameraFocusController);
        this.overlay = new TraceHudOverlay(createRunHudModel(comparator),
                () -> cameraFocusController.currentLabel(),
                this::rewindStatusLabel);
        if (runBoundaryProbe != null) {
            runBoundaryProbe.setDelegate(comparator);
            GameServices.playbackDebug().setFrameObserver(runBoundaryProbe);
        } else {
            GameServices.playbackDebug().setFrameObserver(comparator);
        }
        requireContinuousRunPlaybackAt(action.reseekOffset());
        completionArmed = false;
        completionHoldFrames = 0;
    }

    /**
     * Opens an outer frame of visual Trace Test Mode fast-forward, and reports
     * how many EXTRA gameplay steps {@link GameLoop} should pump into it.
     * <p>
     * Called once per outer frame, before the frame's first step, so the
     * Left/Right ladder input is read while the key edges are still fresh and
     * the audio rate is in place before the frame's one audio presentation.
     * Left/Right only move the ladder while playback is actually running:
     * paused, they belong to {@link TraceCameraFocusController}'s focus cycle,
     * and during a held rewind the rewind transport owns the frame.
     * <p>
     * This also owns the tape-effect envelope for every frame it returns
     * from — except a rewinding one, where
     * {@link #handleRealtimeRewindInput} ticks it instead, since only that
     * method knows whether the rewind actually engaged.
     */
    public int beginFastForwardOuterFrame(InputHandler input, boolean paused) {
        if (fadeStarted || teardownPending) {
            resetFastForward();
            return 0;
        }
        playbackSpeed.handleInput(input, paused || realtimeRewinding,
                GameServices.configuration().getInt(SonicConfiguration.LEFT),
                GameServices.configuration().getInt(SonicConfiguration.RIGHT));
        if (realtimeRewinding) {
            applyForwardRate(1.0);
            return 0;
        }
        if (paused) {
            applyForwardRate(1.0);
            tapeEffectEnvelope.frameInactive();
            return 0;
        }
        double rate = playbackSpeed.rate();
        applyForwardRate(rate);
        if (rate > 1.0) {
            tapeEffectScrollDirection =
                    RewindVhsEffectPass.FAST_FORWARD_SCROLL_DIRECTION;
            tapeEffectEnvelope.frameActive(rate);
        } else {
            tapeEffectEnvelope.frameInactive();
        }
        return playbackSpeed.consumeExtraSteps();
    }

    /**
     * Whether another fast-forward step may still be pumped into this outer
     * frame. Re-checked between pumped steps because any of them can end the
     * session, engage a rewind, or start the completion fade.
     */
    public boolean isFastForwardPumpAllowed() {
        return activeSession == this
                && !realtimeRewinding
                && !fadeStarted
                && !teardownPending;
    }

    /** Current VHS tape-effect presentation intensity, 0..1. */
    public float tapeEffectIntensity() {
        return tapeEffectEnvelope.intensity();
    }

    /** Latched tape speed for the VHS presentation, 0.25..4.0. */
    public float tapeEffectSpeed() {
        return tapeEffectEnvelope.speed();
    }

    /** Tear-band scroll direction for the VHS presentation. */
    public float tapeEffectScrollDirection() {
        return tapeEffectScrollDirection;
    }

    /** Speed ladder display for the trace HUD, e.g. {@code < 1.5x >}. */
    public String playbackRateDisplay() {
        return playbackSpeed.rateDisplay();
    }

    /**
     * The single choke point for becoming the active session. Every entry path
     * routes through here so the legacy {@code == PLAYBACK ==} panel handoff
     * cannot be missed by one of them — this HUD renders that information
     * itself, top-right, for the session's lifetime.
     */
    private void becomeActiveSession() {
        activeSession = this;
        GameServices.playbackDebug().setOverlayOwnedExternally(true);
    }

    /** The matching choke point for standing down; hands the panel back. */
    private void releaseActiveSession() {
        activeSession = null;
        GameServices.playbackDebug().setOverlayOwnedExternally(false);
    }

    /**
     * Drops playback back to real time and kills the tape effect. The forward
     * rate outlives this session on the shared producer, so every exit path
     * has to come through here.
     */
    private void resetFastForward() {
        playbackSpeed.reset();
        tapeEffectEnvelope.reset();
        applyForwardRate(1.0);
    }

    /**
     * Restated every outer frame rather than only on change, the way
     * {@link com.openggf.game.rewind.LiveRewindManager} restates the reverse
     * rate: the producer can be rebuilt mid-session and comes back at real
     * time, which a change-guarded write would never notice.
     */
    private void applyForwardRate(double rate) {
        GameServices.audio().setForwardPlaybackRate(rate);
    }

    /**
     * Handles the held real-time rewind key in visual Trace Test Mode.
     *
     * @param rewindBlocked true while rewind engagement must be rejected: either
     *     the level is mid a special/bonus-stage/ending transition or a pending
     *     zone/act transition, OR a fade is in flight with a completion
     *     callback that has not yet run (see {@code GameLoop.isRewindBlocked()}).
     *     {@code currentGameMode} stays {@code GameMode.LEVEL} throughout both
     *     kinds of window -- a fade only flips the mode (or otherwise acts on
     *     its result) once its completion callback runs -- so a held Trace
     *     Test Mode rewind could otherwise keep walking backward through
     *     pre-transition/pre-fade history the same way
     *     {@link com.openggf.game.rewind.LiveRewindManager} could before it was
     *     gated on this same composite predicate (commit {@code 26fb7debd} for
     *     the transition-flag half; the fade half added later). Widens this
     *     method's existing "not applicable this frame" rejection, reusing its
     *     {@link #cleanupRealtimeRewindPresentation} teardown rather than
     *     needing a separate mid-hold cancel path. Note this is a STRICT
     *     SUPERSET of {@code GameLoop.isNonRewindableTransitionPending()} (the
     *     predicate that also freezes gameplay) -- the fade term must never be
     *     folded into that narrower predicate, since ROM gameplay keeps
     *     ticking during an ordinary callback-bearing fade even though rewind
     *     must still be rejected. See ssentry-rewind-report.md.
     * @return true when the frame was consumed by rewind and normal gameplay
     *         should not advance
     */
    public boolean handleRealtimeRewindInput(boolean rewindBlocked, InputHandler input) {
        if (rewindBlocked || input == null || rewindPlaybackController == null
                || rewindController == null || comparator == null || fadeStarted) {
            return !cleanupRealtimeRewindPresentation(
                    AudioPresentationPolicy.STOP_ALL_PRESENTATION);
        }
        if (realtimeReleasePending) {
            if (!cleanupRealtimeRewindPresentation(
                    pendingRealtimeReleasePolicy)) {
                return true;
            }
            rewindPlaybackController.play();
            syncVisualRewindCursors(true);
            return false;
        }
        int rewindKey = GameServices.configuration().getInt(SonicConfiguration.TRACE_REWIND_KEY);
        boolean held = input.isKeyDown(rewindKey);
        if (held) {
            if (!realtimeRewinding) {
                GameServices.audio().beginReverseAudioPresentation();
                beginReverseFadePresentation();
            }
            realtimeRewinding = true;
            // Trace rewind walks a fixed one step per frame, so the tape speed
            // the effect latches is the base speed rather than a controller
            // reading like live rewind's.
            tapeEffectScrollDirection = RewindVhsEffectPass.REWIND_SCROLL_DIRECTION;
            tapeEffectEnvelope.frameActive(1.0);
            rewindPlaybackController.rewind();
            rewindPlaybackController.tick();
            syncVisualRewindCursors(false);
            if (cameraFocusController != null) {
                cameraFocusController.syncDefaultCameraToCurrentPosition();
            }
            completionArmed = false;
            return true;
        }
        if (realtimeRewinding) {
            // Release lands a committed logical restore via
            // cleanupRealtimeRewindPresentation's commitDeferredAudioRestore()
            // before this cleanup runs, so the RESYNC_MUSIC variant's extra
            // music-stack pop is not needed and would incorrectly end an
            // override (e.g. invincibility) the just-restored state says
            // should still be active.
            if (!cleanupRealtimeRewindPresentation(
                    AudioPresentationPolicy.STOP_TRANSIENT_SFX)) {
                return true;
            }
            rewindPlaybackController.play();
            syncVisualRewindCursors(true);
        }
        return false;
    }

    /** Records a normal visual replay frame after {@link GameLoop} has advanced it. */
    public void recordExternalRewindFrame() {
        recordExternalRewindFrame(false);
    }

    /**
     * Records a completed visual replay frame, then optionally makes it the
     * first frame of a new seamless-transition rewind segment.
     */
    public void recordExternalRewindFrame(boolean seamlessTransitionCompleted) {
        if (realtimeRewinding || rewindController == null || fadeStarted) {
            return;
        }
        rewindController.recordExternalStep();
        if (seamlessTransitionCompleted) {
            rewindController.resetBufferAtCurrentFrame();
        }
    }

    /**
     * Records a transition-only replay frame, then reroots trace rewind so
     * realtime rewind cannot cross the just-applied level boundary.
     */
    public void recordExternalRewindFrameAtBoundary() {
        recordExternalRewindFrame(true);
    }

    /** Called when Esc is pressed during a LEVEL tick. */
    public void requestEarlyExit() {
        if (fadeStarted
                || (!isRunSession() && comparator == null
                        && !launchPhase.ownsEarlyExit())) {
            return;
        }
        Throwable failure = abortIncompleteSession(
                null, "visual trace session exited by user", null);
        if (failure != null) {
            LOGGER.log(java.util.logging.Level.SEVERE,
                    "Visual trace session cleanup failed during early exit", failure);
        }
    }

    private Throwable abortIncompleteSession(
            Throwable primary, String reason, GameLoop fallbackLoop) {
        Throwable failure = primary;
        releaseActiveSession();
        launchPhase.abort();
        teardownPending = false;
        completionStartPending = false;
        runEndPending = false;
        runAdvancePending = null;
        runGapEntryPending = false;
        runSpecialVerificationPending = false;
        runTerminalGapOpened = false;
        SessionManager.clearNextGameplayAdmissionPolicy();
        if (fixture != null) {
            failure = cleanupFailure(failure,
                    fixture::abortHardwareTimingReplayRun);
        }
        failure = cleanupFailure(failure, this::abortRunDynamicArtSegments);
        failure = detachAndCloseRunPayload(failure);
        failure = cleanupFailure(failure, () -> TraceGhostHook.clear(ghostHook));
        failure = cleanupFailure(failure, this::resetFastForward);
        failure = cleanupFailure(failure,
                () -> GameServices.audio().setRewindHistoryArmed(false));
        failure = cleanupFailure(failure,
                () -> GameServices.playbackDebug().endSession());
        failure = cleanupFailure(failure,
                () -> TraceReplaySessionBootstrap.restoreGameplayConfig(configSnapshot));
        GameLoop currentLoop = Engine.currentGameLoop();
        GameLoop cleanupLoop = currentLoop != null ? currentLoop : fallbackLoop;
        if (cleanupLoop != null) {
            failure = cleanupFailure(failure,
                    () -> cleanupLoop.setTraceCameraFocusController(null));
        }
        GameplayModeContext failedContext =
                SessionManager.getCurrentGameplayMode();
        if (failedContext != null) {
            failure = cleanupFailure(failure, failedContext::destroy);
        }
        if (cleanupLoop != null) {
            failure = cleanupFailure(failure, cleanupLoop::returnToMasterTitle);
        }
        comparator = null;
        overlay = null;
        cameraFocusController = null;
        fixture = null;
        LOGGER.info(reason);
        return failure;
    }

    private static void suppressCleanupFailure(
            Throwable primary, Throwable cleanupFailure) {
        if (cleanupFailure != primary) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    /** Detaches every payload-backed alias before closing the sole lease. */
    private Throwable detachAndCloseRunPayload(Throwable primary) {
        ActiveSegmentPayload payload = activeRunPayload;
        if (payload == null) {
            return primary;
        }
        Throwable failure = primary;
        clearRunOwnedInputOverride();
        if (runBoundaryProbe != null) {
            runBoundaryProbe.detachDelegate();
        }
        try {
            GameServices.playbackDebug().setFrameObserver(null);
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure != null) {
                failure.addSuppressed(cleanupFailure);
            } else {
                failure = cleanupFailure;
            }
        }
        GameLoop loop = Engine.currentGameLoop();
        if (loop != null) {
            try {
                loop.setTraceCameraFocusController(null);
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    failure = cleanupFailure;
                }
            }
        }
        comparator = null;
        productionIterationComparator = null;
        runStructuralComparator = null;
        runSpecialRowDriver = null;
        runSpecialRows = null;
        clearRunSpecialPassPacing();
        runSpecialDynamicArtComparison = null;
        runSpecialDynamicArtPendingRow = -1;
        runSpecialTimingSegment = -1;
        runSpecialTimingRow = 0;
        runSpecialDynamicArtSegmentAnticipated = false;
        runAdvancerAnticipatedSegment = -1;
        runProductionOwnerObservation = null;
        runProductionOwnerVblank = null;
        runLastPhysicalInput = null;
        overlay = null;
        cameraFocusController = null;

        activeRunPayload = null;
        failure = cleanupFailure(failure,
                () -> activeSegmentFactory.close(payload));
        return failure;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure);
    }

    private static Throwable cleanupFailure(
            Throwable primary, Runnable cleanup) {
        try {
            cleanup.run();
            return primary;
        } catch (RuntimeException | Error cleanupFailure) {
            if (primary != null) {
                primary.addSuppressed(cleanupFailure);
                return primary;
            }
            return cleanupFailure;
        }
    }

    public void render(PixelFontTextRenderer textRenderer) {
        if (overlay != null) {
            overlay.render(textRenderer);
        }
    }

    public void renderGhosts() {
        renderGhostsForLayer(Integer.MIN_VALUE, false, false);
    }

    public void renderGhostsForLayer(int bucket, boolean highPriority) {
        renderGhostsForLayer(bucket, highPriority, true);
    }

    private void renderGhostsForLayer(int bucket, boolean highPriority, boolean filterLayer) {
        if (comparator == null) {
            return;
        }
        GameLoop loop = Engine.currentGameLoop();
        if (loop == null) {
            return;
        }
        var sprites = GameServices.spritesOrNull();
        if (filterLayer) {
            ghostRenderer.renderForLayer(
                    comparator.metadata(),
                    comparator.currentVisualFrame(),
                    loop.getMainPlayableSprite(),
                    sprites != null ? sprites.getRegisteredSidekicks() : java.util.List.of(),
                    bucket,
                    highPriority);
        } else {
            ghostRenderer.render(
                    comparator.metadata(),
                    comparator.currentVisualFrame(),
                    loop.getMainPlayableSprite(),
                    sprites != null ? sprites.getRegisteredSidekicks() : java.util.List.of());
        }
    }

    private void startFadeOut() {
        fadeStarted = true;
        GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode == null || gameplayMode.getFadeManager() == null) {
            throw new IllegalStateException(
                    "trace fade requires an active gameplay fade manager");
        }
        gameplayMode.getFadeManager().startFadeToBlack(this::teardown);
    }

    /** Arms standalone special-stage completion without replacing a native fade. */
    void beginSpecialStageTerminalExit() {
        fadeStarted = true;
        specialStageTerminalExitPending = true;
        retryPendingSpecialStageTerminalExit();
    }

    /** Resolves the terminal fade at the all-mode frame owner. */
    boolean retryPendingSpecialStageTerminalExit() {
        if (!specialStageTerminalExitPending) {
            return false;
        }
        GameplayModeContext gameplayMode =
                SessionManager.getCurrentGameplayMode();
        if (gameplayMode == null || gameplayMode.getFadeManager() == null) {
            specialStageTerminalExitPending = false;
            teardown();
            return true;
        }
        FadeManager fade = gameplayMode.getFadeManager();
        if (fade.hasPendingCompletion()) {
            return true;
        }
        switch (fade.getState()) {
            case HOLD_WHITE -> {
                specialStageTerminalExitPending = false;
                teardown();
            }
            case NONE -> {
                specialStageTerminalExitPending = false;
                fade.startFadeToBlack(this::teardown);
            }
            case HOLD_BLACK -> {
                specialStageTerminalExitPending = false;
                String diagnostic =
                        "unsupported non-progressing special-stage terminal fade: HOLD_BLACK";
                LOGGER.severe(diagnostic);
                if (entry != null) {
                    TraceLaunchStatus.record(entry, diagnostic);
                }
                teardown();
            }
            default -> {
                // A callback-free native fade is still progressing. Retry
                // without overwriting its presentation state.
            }
        }
        return true;
    }

    private void installTraceRewindController(GameLoop loop, int movieBaseFrame, int traceBaseFrame) {
        var gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode == null) {
            return;
        }
        this.rewindMovieBaseFrame = movieBaseFrame;
        this.rewindTraceBaseFrame = traceBaseFrame;
        this.rewindPlaybackController = gameplayMode.installPlaybackController(
                new OffsetMovieInputSource(movie, movieBaseFrame),
                new VisualTraceRewindStepper(
                        loop, movie, trace, fixture,
                        movieBaseFrame, traceBaseFrame),
                60);
        this.rewindController = gameplayMode.getRewindController();
        // A Trace Test Mode session is the whole reason held rewind exists
        // here; arm PCM recording for its lifetime so held rewind has audio
        // history, and disarm it in teardown()/the bootstrap failure path
        // below so no session leaves it running afterward.
        GameServices.audio().setRewindHistoryArmed(true);
    }

    private void syncVisualRewindCursors(boolean playing) {
        int relativeFrame = rewindController.currentFrame();
        GameServices.playbackDebug().seekSessionFrame(
                rewindMovieBaseFrame + relativeFrame,
                playing);
        comparator.seekForRewind(rewindTraceBaseFrame + relativeFrame);
    }

    private void beginReverseFadePresentation() {
        var fadeManager = GameServices.fadeOrNull();
        if (fadeManager != null) {
            fadeManager.beginReversePresentation();
        }
    }

    private void endReverseFadePresentationIfNeeded() {
        var fadeManager = GameServices.fadeOrNull();
        if (fadeManager != null && fadeManager.isReversePresentationActive()) {
            fadeManager.endReversePresentation();
        }
    }

    private boolean cleanupRealtimeRewindPresentation(
            AudioPresentationPolicy policy) {
        if (!realtimeRewinding && !realtimeReleasePending) {
            endReverseFadePresentationIfNeeded();
            return true;
        }
        boolean released;
        if (rewindController != null) {
            // Land the single deferred logical restore at the committed frame
            // before the presentation cleanup acts on backend logical state.
            rewindController.commitDeferredAudioRestore();
            released = GameServices.audio().afterRewindRestore(
                    rewindController.currentFrame(), policy);
        } else {
            released = GameServices.audio().endReverseAudioPresentation();
        }
        if (!released) {
            realtimeReleasePending = true;
            pendingRealtimeReleasePolicy = policy;
            return false;
        }
        realtimeRewinding = false;
        realtimeReleasePending = false;
        pendingRealtimeReleasePolicy = null;
        endReverseFadePresentationIfNeeded();
        return true;
    }

    private String rewindStatusLabel() {
        // Fast-forward state is not reported here — the top-right transport
        // block's Rate line is its single display, and it stays visible for
        // run sessions, which never install a rewind controller.
        if (rewindController == null) {
            return null;
        }
        int rewindKey = GameServices.configuration().getInt(SonicConfiguration.TRACE_REWIND_KEY);
        String key = GlfwKeyNameResolver.nameOf(rewindKey);
        if (realtimeRewinding) {
            return "REWIND " + rewindController.currentFrame();
        }
        return "Hold " + key + " Rewind";
    }

    private void teardown() {
        teardownPending = true;
        retryPendingTeardown();
    }

    /**
     * Retries teardown at the all-mode frame owner. The active session remains
     * the retry host until audio release has committed.
     *
     * @return true when teardown owned and consumed this frame
     */
    public boolean retryPendingTeardown() {
        if (!teardownPending) {
            return false;
        }
        if (productionIterationInProgress) {
            return true;
        }
        if (rewindController != null) {
            rewindController.commitDeferredAudioRestore();
            if (!GameServices.audio().afterRewindRestore(
                    rewindController.currentFrame(),
                    AudioPresentationPolicy.STOP_ALL_PRESENTATION)) {
                return true;
            }
        }
        Throwable cleanupFailure = null;
        releaseActiveSession();
        GameplayModeContext runContext = SessionManager.getCurrentGameplayMode();
        if (runContext != null && runFrameDriver != null) {
            TraceRunFrameDriver installedDriver = runFrameDriver;
            cleanupFailure = cleanupFailure(cleanupFailure,
                    () -> runContext.clearTraceRunFrameDriver(installedDriver));
        }
        runFrameDriver = null;
        activeRunDisposition = null;
        runStructuralComparator = null;
        runLastPhysicalInput = null;
        runExternalDiagnostics = null;
        cleanupFailure = cleanupFailure(
                cleanupFailure, this::closeRunDynamicArtSegments);
        cleanupFailure = detachAndCloseRunPayload(cleanupFailure);
        cleanupFailure = cleanupFailure(cleanupFailure,
                () -> TraceGhostHook.clear(ghostHook));
        cleanupFailure = cleanupFailure(cleanupFailure, this::resetFastForward);
        cleanupFailure = cleanupFailure(cleanupFailure,
                () -> GameServices.audio().setRewindHistoryArmed(false));
        cleanupFailure = cleanupFailure(cleanupFailure,
                () -> GameServices.playbackDebug().endSession());
        // Restore the user's gameplay-altering config before we
        // rebuild the master title. If the user re-launches the
        // picker immediately, they see their own preferences rather
        // than whatever the trace dictated.
        cleanupFailure = cleanupFailure(cleanupFailure,
                () -> TraceReplaySessionBootstrap.restoreGameplayConfig(
                        configSnapshot));
        GameLoop loop = Engine.currentGameLoop();
        if (loop != null) {
            cleanupFailure = cleanupFailure(cleanupFailure,
                    () -> loop.setTraceCameraFocusController(null));
            cleanupFailure = cleanupFailure(
                    cleanupFailure, loop::returnToMasterTitle);
        }
        this.cameraFocusController = null;
        teardownPending = false;
        throwUnchecked(cleanupFailure);
        return true;
    }

    private static final class OffsetMovieInputSource implements InputSource {
        private final Bk2Movie movie;
        private final int baseFrame;

        private OffsetMovieInputSource(Bk2Movie movie, int baseFrame) {
            this.movie = movie;
            this.baseFrame = Math.max(0, baseFrame);
        }

        @Override
        public int frameCount() {
            return Math.max(1, movie.getFrameCount() - baseFrame + 1);
        }

        @Override
        public Bk2FrameInput read(int frame) {
            int movieFrame = baseFrame + Math.max(0, frame - 1);
            return movie.getFrame(movieFrame);
        }
    }

    private static final class VisualTraceRewindStepper implements RewindSeekAwareEngineStepper {
        private final GameLoop loop;
        private final Bk2Movie movie;
        private final TraceData trace;
        private final TraceReplayFixture fixture;
        private final int movieBaseFrame;
        private final int traceBaseFrame;
        private int pendingP1ActionPressMask;
        private boolean pendingPlayableAnimationAfterClosure;
        private boolean pendingVblankStarvedProductionMarker;

        private VisualTraceRewindStepper(
                GameLoop loop,
                Bk2Movie movie,
                TraceData trace,
                TraceReplayFixture fixture,
                int movieBaseFrame,
                int traceBaseFrame) {
            this.loop = loop;
            this.movie = movie;
            this.trace = trace;
            this.fixture = fixture;
            this.movieBaseFrame = movieBaseFrame;
            this.traceBaseFrame = traceBaseFrame;
        }

        @Override
        public LevelFrameResult step(Bk2FrameInput inputs) {
            var gameplayMode = SessionManager.getCurrentGameplayMode();
            LevelFrameResult result = gameplayMode.plcFrameLifecycle().runReplayedLogicalIteration(
                    gameplayMode.getFadeManager()::update,
                    frame -> step(inputs, frame));
            if (pendingPlayableAnimationAfterClosure) {
                pendingPlayableAnimationAfterClosure = false;
                var sprites = GameServices.spritesOrNull();
                if (sprites != null) {
                    sprites.advancePlayableSlotPrefix();
                }
            }
            return result;
        }

        private LevelFrameResult step(
                Bk2FrameInput inputs,
                com.openggf.game.resources.PlcFrameLifecycleCoordinator.PlcLifecycleFrame
                        lifecycleFrame) {
            int traceIndex = traceIndexForInput(inputs);
            TraceReplayRowPolicy rowPolicy = rowPolicy(traceIndex, inputs.frameIndex());
            Bk2FrameInput appliedInput = rowPolicy != null
                    ? movie.getFrame(rowPolicy.appliedBk2Index()) : inputs;
            if (traceIndex >= 0 && traceIndex < trace.frameCount()) {
                fixture.beginTraceRow(
                        traceIndex, trace.getFrame(traceIndex).frame());
            } else {
                fixture.enterHardwareTimingGap();
            }
            TraceExecutionPhase phase = rowPolicy != null
                    ? rowPolicy.phase() : TraceExecutionPhase.FULL_LEVEL_FRAME;
            if (phase == TraceExecutionPhase.ADVANCE_ONLY) {
                activateProductionMarker(rowPolicy);
                publishPlaybackInput(appliedInput, rowPolicy);
                return LevelFrameResult.GAMEPLAY_FRAME;
            }
            if (phase == TraceExecutionPhase.VBLANK_ONLY
                    || phase == TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY) {
                var level = GameServices.levelOrNull();
                if (level != null && level.getObjectManager() != null) {
                    activateProductionMarker(rowPolicy);
                    com.openggf.trace.replay.TraceSuppressedRowClosure.execute(
                            LevelFrameContext.from(
                                    SessionManager.getCurrentGameplayMode()),
                            lifecycleFrame,
                            level,
                            loop::startPendingInLevelTitleCard,
                            loop::applyTitleCardControlLock);
                }
                if (phase == TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY) {
                    pendingPlayableAnimationAfterClosure = true;
                }
                return LevelFrameResult.GAMEPLAY_FRAME;
            }

            if (phase == TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD) {
                var sidekickSprites = GameServices.spritesOrNull();
                if (sidekickSprites != null && !sidekickSprites.getSidekicks().isEmpty()) {
                    sidekickSprites.getSidekicks().getFirst()
                            .getAnimationManager().suppressNextUpdate();
                }
            }

            var sprites = GameServices.spritesOrNull();
            var level = GameServices.levelOrNull();
            var camera = GameServices.cameraOrNull();
            if (sprites == null || level == null || camera == null) {
                return LevelFrameResult.PAUSED;
            }

            FrameAdmission admission = LevelFrameStep.admit(
                    LevelFrameContext.from(SessionManager.getCurrentGameplayMode()),
                    level,
                    isAppliedStartPressEdge(rowPolicy, appliedInput));
            if (admission.result() == LevelFrameResult.PAUSED) {
                activateProductionMarker(rowPolicy);
                LevelFrameStep.serviceVBlankOnly(
                        LevelFrameContext.from(SessionManager.getCurrentGameplayMode()),
                        lifecycleFrame,
                        com.openggf.game.resources.PlcLifecyclePhase.NORMAL_PAUSE);
                return admission.result();
            }
            if (admission.result() == LevelFrameResult.SETUP_ONLY) {
                return admission.result();
            }
            activateProductionMarker(rowPolicy);
            publishPlaybackInput(appliedInput, rowPolicy);
            sprites.setPlaybackInputSuppressed(true);
            sprites.publishHeldInputForLevelEvents(loop.getInputHandler());
            LevelFrameResult result = LevelFrameStep.execute(
                    LevelFrameContext.from(SessionManager.getCurrentGameplayMode()),
                    lifecycleFrame,
                    com.openggf.game.resources.PlcLifecyclePhase.ORDINARY_LEVEL,
                    level, camera, () -> sprites.update(loop.getInputHandler()),
                    LevelFrameStep.DIRECT_WRAPPER);
            if (result == LevelFrameResult.GAMEPLAY_FRAME) {
                pendingP1ActionPressMask = 0;
            }
            return result;
        }

        @Override
        public void restoreToFrame(int frame, Bk2FrameInput inputAtFrame) {
            int traceIndex = traceIndexForInput(inputAtFrame);
            pendingP1ActionPressMask = restorePendingP1ActionPressMask(traceIndex);
            if (inputAtFrame != null) {
                TraceReplayRowPolicy policy = rowPolicy(
                        traceIndex, inputAtFrame.frameIndex());
                Bk2FrameInput applied = policy != null
                        ? movie.getFrame(policy.appliedBk2Index()) : inputAtFrame;
                int predecessorIndex = policy != null
                        ? policy.appliedPredecessorBk2Index()
                        : applied.frameIndex() - 1;
                Bk2FrameInput previous = predecessorIndex >= 0
                        ? movie.getFrame(predecessorIndex) : null;
                loop.getInputHandler().setLogicalOverride(
                        RecordedInputSnapshots.fromBk2(
                                applied, previous, pendingP1ActionPressMask));
            }
            pendingVblankStarvedProductionMarker =
                    restorePendingProductionMarker(traceIndex);
        }

        private void publishPlaybackInput(
                Bk2FrameInput inputs,
                TraceReplayRowPolicy rowPolicy) {
            int predecessorIndex = rowPolicy != null
                    ? rowPolicy.appliedPredecessorBk2Index()
                    : inputs.frameIndex() - 1;
            Bk2FrameInput previous = predecessorIndex >= 0
                    ? movie.getFrame(predecessorIndex) : null;
            pendingP1ActionPressMask |= newP1ActionPressMask(inputs, previous);
            loop.getInputHandler().setLogicalOverride(
                    RecordedInputSnapshots.fromBk2(
                            inputs, previous, pendingP1ActionPressMask));

            var sprites = GameServices.spritesOrNull();
            if (sprites != null) {
                sprites.setPlaybackInputSuppressed(true);
            }
        }

        private int restorePendingP1ActionPressMask(int restoredTraceIndex) {
            if (restoredTraceIndex < traceBaseFrame
                    || restoredTraceIndex >= trace.frameCount()) {
                return 0;
            }
            int pending = 0;
            for (int index = restoredTraceIndex; index >= traceBaseFrame; index--) {
                int validationBk2Index = movieBaseFrame + index - traceBaseFrame;
                if (validationBk2Index < 0
                        || validationBk2Index >= movie.getFrameCount()) {
                    break;
                }
                TraceReplayRowPolicy policy = TraceReplayRowPolicy.resolve(
                        trace, index, validationBk2Index);
                if (policy.phase() == TraceExecutionPhase.FULL_LEVEL_FRAME
                        || policy.phase()
                        == TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD) {
                    break;
                }
                if (policy.phase() != TraceExecutionPhase.ADVANCE_ONLY) {
                    continue;
                }
                Bk2FrameInput applied = movie.getFrame(policy.appliedBk2Index());
                Bk2FrameInput previous = policy.appliedPredecessorBk2Index() >= 0
                        ? movie.getFrame(policy.appliedPredecessorBk2Index()) : null;
                pending |= newP1ActionPressMask(applied, previous);
            }
            return pending;
        }

        private static int newP1ActionPressMask(
                Bk2FrameInput current,
                Bk2FrameInput previous) {
            int previousAction = previous != null ? previous.p1ActionMask() : 0;
            return current.p1ActionMask() & ~previousAction;
        }

        private boolean isAppliedStartPressEdge(
                TraceReplayRowPolicy rowPolicy,
                Bk2FrameInput appliedInput) {
            int predecessorIndex = rowPolicy != null
                    ? rowPolicy.appliedPredecessorBk2Index()
                    : appliedInput.frameIndex() - 1;
            boolean predecessorHeld = predecessorIndex >= 0
                    && movie.getFrame(predecessorIndex).p1StartPressed();
            return appliedInput.p1StartPressed() && !predecessorHeld;
        }

        private TraceReplayRowPolicy rowPolicy(
                int traceIndex, int validationBk2Index) {
            if (traceIndex < 0 || traceIndex >= trace.frameCount()) {
                return null;
            }
            return TraceReplayRowPolicy.resolve(
                    trace, traceIndex, validationBk2Index);
        }

        private void activateProductionMarker(TraceReplayRowPolicy rowPolicy) {
            if (rowPolicy == null) {
                return;
            }
            boolean vblankStarved = isVblankStarved(rowPolicy.traceIndex());
            if (!rowPolicy.productionPublicationClaim()) {
                pendingVblankStarvedProductionMarker |= vblankStarved;
                return;
            }
            if (pendingVblankStarvedProductionMarker || vblankStarved) {
                TraceReplayBootstrap.markReplayProductionIterationWithoutVblank();
            }
            pendingVblankStarvedProductionMarker = false;
        }

        private boolean isVblankStarved(int traceIndex) {
            TraceFrame current = trace.getFrame(traceIndex);
            TraceFrame previous = traceIndex > 0 ? trace.getFrame(traceIndex - 1) : null;
            return TraceReplayBootstrap.isVblankStarvedIterationForReplay(
                    previous, current);
        }

        private boolean restorePendingProductionMarker(int restoredTraceIndex) {
            boolean pending = false;
            for (int index = restoredTraceIndex;
                    index >= 0 && index < trace.frameCount(); index--) {
                TraceReplayRowPolicy policy = TraceReplayRowPolicy.resolve(
                        trace, index, 0);
                if (policy.productionPublicationClaim()) {
                    break;
                }
                pending |= isVblankStarved(index);
            }
            return pending;
        }

        private int traceIndexForInput(Bk2FrameInput input) {
            if (input == null) {
                return -1;
            }
            int relative = Math.max(
                    0, input.frameIndex() - movieBaseFrame + 1);
            return traceBaseFrame + relative - 1;
        }
    }

    private static MasterTitleScreen.GameEntry resolveGameEntry(String gameId) {
        return MasterTitleScreen.GameEntry.fromGameId(gameId);
    }

    static void clearLaunchSessionOverridesBeforeTraceSnapshot(SonicConfigurationService config) {
        config.clearSessionOverrides();
        config.resolveDisplayAspect();
    }

    /** Thin live-engine implementation of {@link TraceReplayFixture}. */
    private static final class LiveFixture implements TraceReplayFixture {
        private final PlaybackDebugManager playback;
        private final GameLoop gameLoop;
        private HardwareTimingReplayPort hardwareTimingReplayPort;
        private TraceHardwareTimingBoundaryObserver hardwareTimingObserver;
        private GameplayModeContext hardwareTimingGameplayMode;
        private boolean hardwareTimingReplayClosed;

        private LiveFixture(PlaybackDebugManager playback, GameLoop gameLoop) {
            this.playback = playback;
            this.gameLoop = gameLoop;
        }

        @Override
        public AbstractPlayableSprite sprite() {
            return gameLoop.getMainPlayableSprite();
        }

        @Override
        public GameplayModeContext gameplayMode() {
            return SessionManager.getCurrentGameplayMode();
        }

        @Override
        public void installHardwareTimingReplay(HardwareTimingReplayPort replayPort) {
            if (hardwareTimingReplayPort != null) {
                throw new IllegalStateException("hardware timing replay is already installed");
            }
            hardwareTimingReplayPort = replayPort;
            hardwareTimingObserver = new TraceHardwareTimingBoundaryObserver(replayPort);
            hardwareTimingGameplayMode = gameplayMode();
            hardwareTimingReplayClosed = false;
            hardwareTimingGameplayMode.getRewindRegistry().register(replayPort);
            hardwareTimingGameplayMode.setHardwareTimingBoundaryObserver(
                    hardwareTimingObserver);
            hardwareTimingGameplayMode.setHardwareTimingReplayCloseHook(
                    this::closeHardwareTimingReplayRun);
        }

        @Override
        public void beginTraceRow(int traceIndex, int rawFrame) {
            if (hardwareTimingObserver != null) {
                hardwareTimingObserver.beginRawFrame(rawFrame);
            }
        }

        @Override
        public void enterHardwareTimingGap() {
            if (hardwareTimingObserver != null) {
                hardwareTimingObserver.enterUnrepresentedGap();
            }
        }

        @Override
        public void verifyHardwareTimingSegmentEdges() {
            if (hardwareTimingReplayPort != null) {
                hardwareTimingReplayPort.verifySegmentEdges();
            }
        }

        @Override
        public void handoffHardwareTimingReplay(HardwareTimingSchedule nextSchedule) {
            handoffHardwareTimingReplay(nextSchedule, java.util.Map.of());
        }

        @Override
        public void handoffHardwareTimingReplay(
                HardwareTimingSchedule nextSchedule,
                java.util.Map<com.openggf.game.timing.HardwareWorkKind,
                        com.openggf.game.timing.RecordedOrdinalSpan> interstitialSpans) {
            if (hardwareTimingReplayPort != null) {
                hardwareTimingReplayPort.handoffTo(nextSchedule, interstitialSpans);
            }
        }

        @Override
        public void closeHardwareTimingReplayRun() {
            if (hardwareTimingReplayPort == null || hardwareTimingReplayClosed) {
                return;
            }
            hardwareTimingReplayClosed = true;
            try {
                hardwareTimingReplayPort.verifyRunComplete();
            } finally {
                if (hardwareTimingGameplayMode != null) {
                    hardwareTimingGameplayMode.setHardwareTimingBoundaryObserver(
                            null);
                    if (hardwareTimingGameplayMode.getRewindRegistry() != null) {
                        hardwareTimingGameplayMode.getRewindRegistry()
                                .deregister(HardwareTimingReplayPort.REWIND_KEY);
                    }
                    hardwareTimingGameplayMode.clearHardwareTimingReplayCloseHook();
                }
                hardwareTimingObserver = null;
                hardwareTimingGameplayMode = null;
            }
        }

        @Override
        public void abortHardwareTimingReplayRun() {
            if (hardwareTimingReplayClosed) {
                return;
            }
            hardwareTimingReplayClosed = true;
            if (hardwareTimingGameplayMode != null) {
                hardwareTimingGameplayMode.setHardwareTimingBoundaryObserver(null);
                if (hardwareTimingGameplayMode.getRewindRegistry() != null) {
                    hardwareTimingGameplayMode.getRewindRegistry()
                            .deregister(HardwareTimingReplayPort.REWIND_KEY);
                }
                hardwareTimingGameplayMode.clearHardwareTimingReplayCloseHook();
            }
            hardwareTimingObserver = null;
            hardwareTimingGameplayMode = null;
        }

        @Override
        public int stepFrameFromRecording() {
            Bk2FrameInput frame = playback.currentFrameOrThrow();
            int mask = toReplayValidationMask(frame);
            gameLoop.step();
            return mask;
        }

        @Override
        public int skipFrameFromRecording() {
            Bk2FrameInput frame = playback.currentFrameOrThrow();
            int mask = toReplayValidationMask(frame);
            playback.advanceCurrentFrameWithoutGameplay();
            return mask;
        }

        @Override
        public void suppressFirstSidekickAnimationOnce() {
            var sprites = GameServices.spritesOrNull();
            if (sprites != null && !sprites.getSidekicks().isEmpty()) {
                sprites.getSidekicks().getFirst().getAnimationManager().suppressNextUpdate();
            }
        }

        @Override
        public int consumeRecordingFrameInputOnly() {
            Bk2FrameInput frame = playback.currentFrameOrThrow();
            int mask = toReplayValidationMask(frame);
            playback.advanceCurrentFrameWithoutGameplay();
            return mask;
        }

        @Override
        public void advanceRecordingCursor(int frameCount) {
            for (int i = 0; i < frameCount; i++) {
                playback.advanceCurrentFrameWithoutGameplay();
            }
        }

        @Override
        public int peekRecordingInputAt(int offset) {
            return playback.peekInputMaskAt(offset);
        }

        private static int toReplayValidationMask(Bk2FrameInput frame) {
            int mask = frame.p1InputMask();
            if (frame.p1ActionMask() != 0) {
                mask |= AbstractPlayableSprite.INPUT_JUMP;
            }
            return mask;
        }
    }
}
