package com.openggf;

import com.openggf.game.GameOverExit;
import com.openggf.game.ContinueScreenProvider;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugColor;
import com.openggf.editor.EditorInputHandler;
import com.openggf.game.*;

import com.openggf.control.InputHandler;
import com.openggf.audio.AudioManager;
import com.openggf.audio.presentation.OuterFramePresentation;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugObjectArtViewer;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic1.dataselect.S1DataSelectImageCacheManager;
import com.openggf.game.sonic1.dataselect.S1DataSelectImageGenerator;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.dataselect.S2DataSelectImageCacheManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.mode.BootScreenModeController;
import com.openggf.game.launch.MasterTitleLaunchCoordinator;
import com.openggf.game.launch.MasterTitleExitCoordinator;
import com.openggf.game.mode.MenuScreenModeController;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.rewind.LiveRewindManager;
import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.startup.DataSelectPresentationResolution;
import com.openggf.game.startup.StartupRouteResolver;
import com.openggf.game.startup.TitleActionRoute;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator.PlcLifecycleFrame;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.graphics.PixelFontTextRenderer;
import com.openggf.level.BigRingReturnState;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectSpawn;
import static org.lwjgl.glfw.GLFW.*;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.timer.TimerManager;
import com.openggf.graphics.FadeManager;

import com.openggf.game.DemoLamppostState;
import com.openggf.level.WaterSystem;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.debug.playback.PlaybackInputBridge;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.data.RomManager;
import com.openggf.data.Rom;
import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SessionSaveRequests;
import com.openggf.game.SpecialStageReturnSpawn;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.integration.presence.PresenceFormatter;
import com.openggf.integration.presence.PresenceManager;
import com.openggf.integration.presence.RuntimePresenceSnapshotProvider;
import com.openggf.integration.presence.discord.DiscordIpcPresenceClient;
import com.openggf.integration.presence.discord.DiscordIpcTransports;
import com.openggf.game.recording.RecordingLaunchContext;
import com.openggf.game.patch.ModuleResolutionService;
import com.openggf.game.patch.DeterministicPatchLaunches;
import com.openggf.game.recording.UserRecordingHudState;
import com.openggf.game.recording.UserRecordingRuntimeControls;
import com.openggf.game.recording.UserRecordingSessionLauncher;
import com.openggf.game.recording.UserRecordingStopReason;
import com.openggf.game.recording.UserRecordingPlaybackState;
import com.openggf.game.recording.UserRecordingVerificationResult;
import com.openggf.game.recording.menu.UserRecordingMenu;
import com.openggf.game.timeattack.GhostStore;
import com.openggf.game.timeattack.TimeAttackHudOverlay;
import com.openggf.game.timeattack.TimeAttackDebugInput;
import com.openggf.game.timeattack.TimeAttackLaunchRequest;
import com.openggf.game.timeattack.TimeAttackLevelEndRouting;
import com.openggf.game.timeattack.TimeAttackMenu;
import com.openggf.game.timeattack.TimeAttackRuntime;
import com.openggf.game.timeattack.mp.MultiplayerRaceCoordinator;
import com.openggf.game.timeattack.mp.MultiplayerHudRenderer;
import com.openggf.testmode.TraceCameraFocusController;
import com.openggf.trace.replay.TraceSuppressedRowClosure;

import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Standalone game loop that can run independently of the rendering system.
 * This enables headless testing of game logic without requiring OpenGL context.
 *
 * <p>
 * The GameLoop manages:
 * <ul>
 * <li>Audio updates</li>
 * <li>Timer updates</li>
 * <li>Input processing</li>
 * <li>Game mode transitions (level ↔ special stage)</li>
 * <li>Sprite collision and movement</li>
 * <li>Camera updates</li>
 * <li>Level updates</li>
 * </ul>
 *
 * <p>
 * For headless testing, create a GameLoop with a mock InputHandler
 * and call {@link #step()} to advance one frame.
 */
@com.openggf.game.ModApi
public class GameLoop {
    static final int STATUS_FIRE_SHIELD_BIT = 4;
    static final int STATUS_LIGHTNING_SHIELD_BIT = 5;
    static final int STATUS_BUBBLE_SHIELD_BIT = 6;
    static final int SAVED_SHIELD_MASK =
            (1 << STATUS_FIRE_SHIELD_BIT)
                    | (1 << STATUS_LIGHTNING_SHIELD_BIT)
                    | (1 << STATUS_BUBBLE_SHIELD_BIT);
    private static final int USER_RECORDING_FAST_FORWARD_EXTRA_STEPS_PER_FRAME = 8;

    private static final Logger LOGGER = Logger.getLogger(GameLoop.class.getName());
    private final EngineContext engineServices;
    final SonicConfigurationService configService;
    private final AudioManager audioManager;
    private final OuterFramePresentation outerFramePresentation;
    private final RomManager romManager;
    private final DebugOverlayManager debugOverlayManager;
    SpriteManager spriteManager;
    Camera camera;
    private TimerManager timerManager;
    LevelManager levelManager;
    GameStateManager gameState;
    FadeManager fadeManager;
    private WaterSystem waterSystem;
    private final PerformanceProfiler profiler;
    private final PlaybackDebugManager playbackDebugManager;
    private final PlaybackInputBridge playbackInputBridge = new PlaybackInputBridge();
    private final ModuleResolutionService moduleResolutionService;
    private final LiveRewindManager liveRewindManager;
    private final StartupRouteResolver startupRouteResolver = new StartupRouteResolver();
    private final BootScreenModeController bootScreenModeController = new BootScreenModeController();
    private final GameLoopContinueCoordinator continueScreen = new GameLoopContinueCoordinator(this);

    private final MenuScreenModeController menuScreenModeController = new MenuScreenModeController();
    private final BonusStageTransitionCoordinator bonusStageTransitionCoordinator =
            new BonusStageTransitionCoordinator();
    private final PresenceManager presenceManager;
    private final EscapeToMasterTitleController escapeToMasterTitleController;
    private MasterTitleLaunchCoordinator masterTitleLaunchCoordinator;
    private final MasterTitleExitCoordinator masterTitleExitCoordinator;

    // The active session-owned gameplay mode. Cached fields above are sourced from this context.
    private GameplayModeContext gameplayMode;
    private SpecialStageProvider activeSpecialStageProvider = NoOpSpecialStageProvider.INSTANCE;

    /** Comparison-only pacing for special-stage object passes observed per V-blank. */
    interface SpecialStageObservationPacing {
        int passCount();

        void applyPassInput(int index, SpecialStageProvider provider);

        /**
         * Executes pass {@code index}.
         *
         * <p>{@code SpecialStage_MainLoop} is two loops, not one
         * (docs/s2disasm/s2.asm:6674-6721), and one observation can own a pass
         * from each: the pre-start loop copies {@code Ctrl_1}/{@code Ctrl_2}
         * <em>before</em> its {@code WaitForVint} (s2.asm:6675-6676), so its
         * terminal pass owns no post-V-int controller sample for the recurring
         * loop's binding path to consume. A driver that can tell the two apart
         * overrides this to publish such a pass through the startup boundary
         * instead, exactly as {@code S2SpecialStageReplayHarness.stepPasses}
         * already does. The default treats every pass as a recurring-loop pass.
         */
        default void runPass(int index, SpecialStageProvider provider) {
            applyPassInput(index, provider);
            provider.update();
        }

        /**
         * Runs after pass {@code index}'s object scan, for a pass the ROM
         * finished BEFORE this observation's V-int.
         *
         * <p>{@code SS_MainLoop} waits for the V-int and only then runs
         * {@code RunObjects} (docs/s2disasm/s2.asm:6694-6721), so a pass that
         * returned during the preceding frame had its queued work carried
         * through the V-blank that opened this observation, and that V-blank
         * already ran {@code ProcessDMAQueue} (s2.asm:1769) over it. Work
         * queued by a later pass in the same observation stays pending. The
         * standalone harness models the same boundary
         * ({@code S2SpecialStageReplayHarness.stepPasses}).
         *
         * <p>Default no-op: a driver that cannot distinguish the two cases
         * leaves every pass's work pending until the next observation.
         */
        default void afterPass(int index) {
        }
    }

    private SpecialStageObservationPacing specialStageObservationPacing;

    void setSpecialStageObservationPacing(SpecialStageObservationPacing pacing) {
        this.specialStageObservationPacing = pacing;
    }

    // Title card provider - lazily initialized when GameModule is available
    private TitleCardProvider titleCardProvider;

    private InputHandler inputHandler;
    private EditorInputHandler editorInputHandler;
    private Runnable editorPlaytestToggleHandler;
    private Runnable editorFreshStartHandler;
    private Runnable applicationExitHandler = () -> {};
    private GameMode currentGameMode = GameMode.LEVEL;
    private Runnable editorStateSyncHandler;
    private Supplier<MasterTitleScreen> masterTitleScreenSupplier;
    private Supplier<LegalDisclaimerScreen> legalDisclaimerSupplier;
    private Runnable legalDisclaimerExitHandler;
    private Supplier<com.openggf.game.NativeModNoticeScreen> nativeModNoticeSupplier;
    private Runnable nativeModNoticeExitHandler;
    private Consumer<com.openggf.game.dataselect.DataSelectAction> dataSelectActionHandler;
    private GameplayTeamBootstrapContext gameplayTeamBootstrapContext =
            GameplayTeamBootstrapContext.registryOnly();
    private final UserRecordingSessionLauncher userRecordingSessionLauncher;
    private final UserRecordingRuntimeControls userRecordingControls;
    private final TimeAttackRuntime timeAttackRuntime;
    private final TimeAttackHudOverlay timeAttackHudOverlay;
    private final MultiplayerHudRenderer multiplayerHudRenderer;
    private MultiplayerRaceCoordinator multiplayerRaceCoordinator;
    private UserRecordingMenu.PlaybackStarter userRecordingPlaybackStarter;
    private TimeAttackMenu.LaunchStarter timeAttackLaunchHandler =
            request -> LOGGER.warning("Time attack launch handler not configured.");
    private TimeAttackMenu.NetworkStarter timeAttackNetworkHandler = TimeAttackMenu.NetworkStarter.NONE;
    private int lastAppliedUserRecordingPlaybackFrame = -1;
    private long gameplayAudioFrame;
    private boolean audioUpdatedThisStep;

    // Special stage results screen
    private ResultsScreen resultsScreen;
    int ssRingsCollected;
    boolean ssEmeraldCollected;
    int ssStageIndex;
    private EmeraldRewardKind activeSpecialStageRewardKind = EmeraldRewardKind.CHAOS_EMERALD;
    private int resultsFrameCounter = 0;

    // Flag to track when returning from special stage (for title card exit
    // handling)
    private boolean returningFromSpecialStage = false;

    // Flag to freeze level updates during special stage entry transition
    private boolean specialStageTransitionPending = false;
    private boolean specialStageRewindBoundaryThisFrame;
    private final SpecialStageEntryPresentationController specialStageEntryPresentation =
            new SpecialStageEntryPresentationController();

    // Bonus stage entry/exit state
    private boolean bonusStageTransitionPending;
    /** The results-exit fade completed this iteration; the exit body waits one more. */
    private boolean resultsExitFadeCompleted;
    /** The results-exit body runs at the start of this iteration's mode update. */
    private boolean resultsExitReady;
    /** Remaining game-owned pre-level fade frames; -1 while no exit is in flight. */
    private int resultsExitPreLevelFadeFramesRemaining = -1;
    private BonusStageProvider activeBonusStageProvider;
    // Star-post activation high-water captured at bonus entry (ROM: the star post's
    // respawn bit, kept across the reload by Respawn_table_keep). Restored on bonus
    // exit so the return star post stays used even though Last_star_post_hit was
    // zeroed (sonic3k.asm:61924). -1 when no bonus entry is in flight.
    private int pendingBonusReturnStarPostMark = -1;

    // Flag to freeze level updates during the final-boss fade into ending mode.
    private boolean endingTransitionPending;

    private PostTitleCardDestination postTitleCardDestination = PostTitleCardDestination.LEVEL;
    private LevelFrameResult titleReleaseResult = LevelFrameResult.GAMEPLAY_FRAME;
    private final LevelIterationAdmissionController levelIterationAdmission =
            new LevelIterationAdmissionController();

    // Deferred bonus stage setup — applied when title card exits with BONUS_STAGE destination
    private BonusStageProvider deferredBonusProvider;
    private BonusStageType deferredBonusType;
    private BonusStageState deferredBonusState;
    private ShieldType pendingBonusStageShieldRestore;

    // Game-agnostic ending/credits provider (wraps S1 CreditsManager, S2 credits, etc.)
    private EndingProvider endingProvider;

    // Listener for game mode changes (used by Engine to update projection)
    private GameModeChangeListener gameModeChangeListener;

    // Optional trace camera focus controller — ticked at the top of every stepInternal()
    private TraceCameraFocusController traceCameraFocusController;
    private GameplayModeContext liveRewindBoundaryReporterContext;

    private final class LiveUserRecordingRuntime implements UserRecordingRuntimeControls.Runtime {
        @Override
        public int recordKey() {
            return configService.getInt(SonicConfiguration.RECORDING_RECORD_KEY);
        }

        @Override
        public GameMode currentGameMode() {
            return GameLoop.this.currentGameMode;
        }

        @Override
        public boolean traceOrDebugSurfaceOwnsRecordingInput() {
            return TraceSessionLauncher.active() != null
                    || configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)
                    || (debugShortcutsEnabled()
                    && debugOverlayManager.isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER));
        }

        @Override
        public boolean hasActiveRecording() {
            return userRecordingSessionLauncher.hasActiveRecordingSession();
        }

        @Override
        public void beginRecordingFromCurrentLevel() {
            userRecordingSessionLauncher.beginRecordingFromCurrentLevel();
        }

        @Override
        public void stopActiveRecording(UserRecordingStopReason reason) {
            userRecordingSessionLauncher.stopActiveRecording(reason);
        }

        @Override
        public void beforeActiveRecordingLevelFrame(InputHandler input) {
            userRecordingSessionLauncher.beforeActiveRecordingLevelFrame(input);
        }

        @Override
        public void afterActiveRecordingLevelFrame() {
            userRecordingSessionLauncher.afterActiveRecordingLevelFrame();
        }

        @Override
        public UserRecordingHudState activeRecordingHudState() {
            return userRecordingSessionLauncher.activeRecordingHudState();
        }

        @Override
        public com.openggf.game.recording.UserRecordingPlaybackOptions activePlaybackOptions() {
            return userRecordingSessionLauncher.currentPlaybackOptions();
        }

        @Override
        public UserRecordingPlaybackState activePlaybackState() {
            return userRecordingSessionLauncher.currentPlaybackState();
        }

        @Override
        public boolean playbackHasDesynced() {
            return userRecordingSessionLauncher.activePlaybackHasDesynced();
        }

        @Override
        public UserRecordingVerificationResult activePlaybackVerificationResult() {
            return userRecordingSessionLauncher.currentPlaybackVerificationResult();
        }

        @Override
        public int currentPlaybackFrame() {
            return playbackDebugManager.getCursorFrame();
        }

        @Override
        public int playbackFrameCount() {
            return playbackDebugManager.getMovieFrameCount();
        }

        @Override
        public void updatePlaybackState(UserRecordingPlaybackState state) {
            userRecordingSessionLauncher.updateActivePlaybackState(state);
        }

        @Override
        public void pauseEngineForPlayback() {
            userPaused = true;
            updateAudioPauseState();
        }

        @Override
        public void endPlaybackDebugSession() {
            userRecordingSessionLauncher.endPlaybackSession();
            levelIterationAdmission.resetLastAppliedPlaybackFrame();
        }

    }

    /** @deprecated use {@link com.openggf.GameModeChangeListener}. */
    @Deprecated
    @com.openggf.game.ModApi
    public interface GameModeChangeListener extends com.openggf.GameModeChangeListener {
    }

    private volatile boolean paused = false;      // Window focus pause
    private volatile boolean userPaused = false;  // Keyboard toggle pause
    private PlcLifecycleFrame activePlcLifecycleFrame;

    public GameLoop() {
        this(EngineServices.current());
    }

    public GameLoop(EngineContext engineServices) {
        this.engineServices = Objects.requireNonNull(engineServices, "engineServices");
        EngineServices.configure(this.engineServices);
        this.configService = this.engineServices.configuration();
        this.audioManager = this.engineServices.audio();
        this.outerFramePresentation = new OuterFramePresentation(this.audioManager);
        this.romManager = this.engineServices.roms();
        this.debugOverlayManager = this.engineServices.debugOverlay();
        this.profiler = this.engineServices.profiler();
        this.playbackDebugManager = this.engineServices.playbackDebug();
        this.moduleResolutionService = this.engineServices.moduleResolutionService();
        this.liveRewindManager = new LiveRewindManager(
                configService,
                () -> currentGameMode,
                this::getActiveSpecialStageProvider);
        this.userRecordingSessionLauncher = new UserRecordingSessionLauncher(this);
        this.userRecordingControls = new UserRecordingRuntimeControls(
                new LiveUserRecordingRuntime(), this::returnToMasterTitle);
        this.timeAttackRuntime = new TimeAttackRuntime(new GhostStore(java.nio.file.Path.of("ghosts")),
                java.nio.file.Path.of("identity"),
                () -> TraceSessionLauncher.active() != null
                        || configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)
                        || playbackDebugManager.isDriving(GameMode.LEVEL));
        this.timeAttackHudOverlay = new TimeAttackHudOverlay(timeAttackRuntime::hudState, configService);
        this.multiplayerHudRenderer = new MultiplayerHudRenderer(configService);
        this.userRecordingPlaybackStarter = withPlaybackAppliedFrameReset(userRecordingSessionLauncher::beginPlayback);
        this.masterTitleLaunchCoordinator = new MasterTitleLaunchCoordinator(configService);
        this.masterTitleExitCoordinator = new MasterTitleExitCoordinator(
                () -> masterTitleLaunchCoordinator,
                () -> currentGameMode == GameMode.MASTER_TITLE_SCREEN, this::hasReadyGameplayRuntime,
                () -> changeGameModeForBoundary(GameMode.LEVEL), () -> resolveFadeManager().startFadeFromBlack(null),
                () -> requestSessionSave(SaveReason.PROGRESSION_SAVE), pending -> endingTransitionPending = pending,
                audioManager::fadeOutMusic, callback -> fadeManager.startFadeToBlack(callback));
        this.escapeToMasterTitleController = new EscapeToMasterTitleController(
                () -> resolveFadeManager().isActive(),
                this::startEscapeToMasterTitleTransition,
                this::startEscapeApplicationExitTransition);
        this.presenceManager = new PresenceManager(
                configService.getBoolean(SonicConfiguration.DISCORD_RICH_PRESENCE_ENABLED),
                configService.getBoolean(SonicConfiguration.DISCORD_RICH_PRESENCE_SHOW_TIMER),
                configService.getBoolean(SonicConfiguration.DISCORD_RICH_PRESENCE_SHOW_ZONE),
                new RuntimePresenceSnapshotProvider(this, configService),
                new PresenceFormatter(),
                new DiscordIpcPresenceClient(DiscordIpcTransports.defaultFactory()),
                System::currentTimeMillis);
        refreshRuntimeBindings();
    }

    public GameLoop(InputHandler inputHandler) {
        this(EngineServices.current(), inputHandler);
    }

    public GameLoop(EngineContext engineServices, InputHandler inputHandler) {
        this(engineServices);
        this.inputHandler = inputHandler;
    }

    public void setGameplayMode(GameplayModeContext gameplayMode) {
        this.gameplayMode = gameplayMode;
        refreshRuntimeBindings();
        installLiveRewindBoundaryReporter();
    }

    private void refreshRuntimeBindings() {
        GameplayModeContext currentGameplayMode = resolveGameplayModeContext();
        if (currentGameplayMode == null || !currentGameplayMode.isGameplayRuntimeReady()) {
            // Gameplay mode has been torn down (e.g. trace teardown returning to
            // master title). Clear cached references so resolveFadeManager()
            // falls back to the graphics-owned bootstrap manager rather than
            // a destroyed gameplay FadeManager that the UI pipeline no longer
            // ticks — which would otherwise leave fade callbacks orphaned.
            this.gameplayMode = null;
            this.spriteManager = null;
            this.camera = null;
            this.timerManager = null;
            this.levelManager = null;
            this.gameState = null;
            this.fadeManager = null;
            this.waterSystem = null;
            this.liveRewindBoundaryReporterContext = null;
            engineServices.graphics().clearRuntimeManagedReferences();
            return;
        }
        this.gameplayMode = currentGameplayMode;
        this.spriteManager = currentGameplayMode.getSpriteManager();
        this.camera = currentGameplayMode.getCamera();
        this.timerManager = currentGameplayMode.getTimerManager();
        this.levelManager = currentGameplayMode.getLevelManager();
        this.gameState = currentGameplayMode.getGameStateManager();
        this.fadeManager = currentGameplayMode.getFadeManager();
        this.waterSystem = currentGameplayMode.getWaterSystem();
        engineServices.graphics().bindRuntimeManagedReferences(this.camera, this.fadeManager);
        if (currentGameplayMode != liveRewindBoundaryReporterContext) {
            installLiveRewindBoundaryReporter();
        }
    }

    GameplayModeContext resolveGameplayModeContext() {
        return gameplayMode != null ? gameplayMode : SessionManager.getCurrentGameplayMode();
    }

    public void resetModuleScopedProviders() {
        continueScreen.reset();
        titleCardProvider = null;
    }

    public void setInputHandler(InputHandler inputHandler) {
        this.inputHandler = inputHandler;
    }

    public void setEditorInputHandler(EditorInputHandler editorInputHandler) {
        this.editorInputHandler = editorInputHandler;
    }

    void renderLiveRewindHud(PixelFontTextRenderer textRenderer, int viewportWidth) {
        liveRewindManager.renderHud(currentGameMode, textRenderer, viewportWidth);
    }

    public void renderLiveRewindHud(PixelFontTextRenderer textRenderer) {
        renderLiveRewindHud(textRenderer, engineServices.graphics().getProjectionWidth());
    }

    public float liveRewindEffectIntensity() {
        return liveRewindManager.effectIntensity();
    }

    public float liveRewindEffectSpeed() {
        return liveRewindManager.effectSpeed();
    }

    public void renderUserRecordingHud(PixelFontTextRenderer textRenderer) {
        if (textRenderer == null) {
            return;
        }
        UserRecordingHudState state = userRecordingControls.hudState();
        if (state == null || !state.visible()) {
            return;
        }
        DebugColor color = state.redWarning()
                ? DebugColor.RED
                : state.amberWarning() ? DebugColor.ORANGE : DebugColor.WHITE;
        textRenderer.beginBatch();
        textRenderer.drawShadowedText(state.primaryText(), 8, 8, color, 0.7f);
        if (state.secondaryText() != null && !state.secondaryText().isBlank()) {
            textRenderer.drawShadowedText(state.secondaryText(), 8, 18, color, 0.6f);
        }
        textRenderer.endBatch();
    }

    public void renderTimeAttackHud(PixelFontTextRenderer textRenderer) {
        if (textRenderer == null) {
            return;
        }
        timeAttackHudOverlay.render(textRenderer);
        if (multiplayerRaceCoordinator != null) {
            multiplayerHudRenderer.render(textRenderer, multiplayerRaceCoordinator.hudState());
        }
    }

    public boolean shouldSuppressUserRecordingSceneRendering() {
        return !isPaused() && userRecordingControls.shouldSuppressSceneRendering();
    }

    public EscapeToMasterTitleController getEscapeToMasterTitleController() {
        return escapeToMasterTitleController;
    }

    public void setEditorPlaytestToggleHandler(Runnable editorPlaytestToggleHandler) {
        this.editorPlaytestToggleHandler = editorPlaytestToggleHandler;
    }

    public void setDataSelectActionHandler(Consumer<com.openggf.game.dataselect.DataSelectAction> dataSelectActionHandler) {
        this.dataSelectActionHandler = dataSelectActionHandler;
    }

    public void setEditorFreshStartHandler(Runnable editorFreshStartHandler) {
        this.editorFreshStartHandler = editorFreshStartHandler;
    }

    public void setApplicationExitHandler(Runnable applicationExitHandler) {
        this.applicationExitHandler = applicationExitHandler != null ? applicationExitHandler : () -> {};
    }

    public void setEditorStateSyncHandler(Runnable editorStateSyncHandler) {
        this.editorStateSyncHandler = editorStateSyncHandler;
    }

    public void setMasterTitleScreenSupplier(Supplier<MasterTitleScreen> masterTitleScreenSupplier) {
        this.masterTitleScreenSupplier = masterTitleScreenSupplier;
    }

    void setCharacterAvailabilitySupplier(Supplier<CharacterAvailability> supplier) {
        gameplayTeamBootstrapContext = new GameplayTeamBootstrapContext(supplier);
    }

    public void setUserRecordingPlaybackStarter(UserRecordingMenu.PlaybackStarter userRecordingPlaybackStarter) {
        this.userRecordingPlaybackStarter =
                levelIterationAdmission.withAppliedPlaybackFrameReset(userRecordingPlaybackStarter);
        installUserRecordingPlaybackStarter(currentMasterTitleScreen());
    }

    public void setTimeAttackLaunchHandler(TimeAttackMenu.LaunchStarter timeAttackLaunchHandler) {
        this.timeAttackLaunchHandler = Objects.requireNonNull(timeAttackLaunchHandler, "timeAttackLaunchHandler");
        installTimeAttackLaunchHandler(currentMasterTitleScreen());
    }

    public void setTimeAttackNetworkHandler(TimeAttackMenu.NetworkStarter handler) {
        this.timeAttackNetworkHandler = Objects.requireNonNull(handler, "handler");
        MasterTitleScreen screen = currentMasterTitleScreen();
        if (screen != null) {
            screen.setTimeAttackNetworkStarter(handler);
        }
    }

    /** Exposed so tests/Engine can arm and end a launched Time Attack session. */
    public TimeAttackRuntime getTimeAttackRuntime() {
        return timeAttackRuntime;
    }

    public void setMultiplayerRaceCoordinator(MultiplayerRaceCoordinator coordinator) {
        this.multiplayerRaceCoordinator = coordinator;
    }

    private UserRecordingMenu.PlaybackStarter withPlaybackAppliedFrameReset(
            UserRecordingMenu.PlaybackStarter starter) {
        Objects.requireNonNull(starter, "starter");
        return (entry, options) -> {
            levelIterationAdmission.resetLastAppliedPlaybackFrame();
            starter.start(entry, options);
        };
    }

    public void setMasterTitleExitHandler(Consumer<String> masterTitleExitHandler) {
        masterTitleExitCoordinator.setStockExitHandler(masterTitleExitHandler);
    }

    public void setStandaloneMasterTitleExitHandler(Consumer<MasterTitleEntry.Launch> standaloneMasterTitleExitHandler) {
        masterTitleExitCoordinator.setStandaloneExitHandler(standaloneMasterTitleExitHandler);
    }

    public void setLegalDisclaimerScreenSupplier(Supplier<LegalDisclaimerScreen> legalDisclaimerSupplier) {
        this.legalDisclaimerSupplier = legalDisclaimerSupplier;
    }

    public void setLegalDisclaimerExitHandler(Runnable legalDisclaimerExitHandler) {
        this.legalDisclaimerExitHandler = legalDisclaimerExitHandler;
    }

    void setNativeModNoticeScreenSupplier(
            Supplier<com.openggf.game.NativeModNoticeScreen> supplier) {
        this.nativeModNoticeSupplier = supplier;
    }

    void setNativeModNoticeExitHandler(Runnable handler) {
        this.nativeModNoticeExitHandler = handler;
    }

    private void updateEditorMode() {
        if (editorInputHandler != null) {
            editorInputHandler.update(inputHandler);
        }
        if (editorStateSyncHandler != null) {
            editorStateSyncHandler.run();
        }
    }

    /**
     * Gets the title card provider, lazily initializing it from the current
     * GameModule.
     * 
     * @return the title card provider
     */
    private TitleCardProvider getTitleCardProviderLazy() {
        if (titleCardProvider == null) {
            titleCardProvider = GameServices.module().getTitleCardProvider();
        }
        return titleCardProvider;
    }

    public InputHandler getInputHandler() {
        return inputHandler;
    }

    public void setGameModeChangeListener(GameModeChangeListener listener) {
        this.gameModeChangeListener = listener;
    }

    public void setTraceCameraFocusController(TraceCameraFocusController controller) {
        this.traceCameraFocusController = controller;
    }

    public GameMode getCurrentGameMode() {
        return currentGameMode;
    }

    /**
     * Sets the game mode directly. Used for master title screen initialization.
     */
    public void setGameMode(GameMode mode) {
        GameMode oldMode = changeGameModeForBoundary(mode);
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, mode);
        }
    }

    void installLiveRewindBoundaryReporter() {
        installLiveRewindBoundaryReporter(liveRewindManager::markBoundary);
    }

    void installLiveRewindBoundaryReporter(Consumer<RewindBoundary> liveBoundaryConsumer) {
        GameplayModeContext context = resolveGameplayModeContext();
        if (context == null) {
            return;
        }
        context.setRewindBoundaryReporter(boundary -> {
            if (TraceSessionLauncher.active() == null) {
                liveBoundaryConsumer.accept(boundary);
            }
        });
        liveRewindBoundaryReporterContext = context;
    }

    GameMode changeGameModeForBoundary(GameMode nextMode) {
        GameMode oldMode = currentGameMode;
        if (oldMode != nextMode) {
            if (oldMode == GameMode.SPECIAL_STAGE) {
                specialStageEntryPresentation.clear();
            }
            currentGameMode = nextMode;
            reportRewindModeBoundary(oldMode, nextMode);
        }
        return oldMode;
    }

    GameMode changeGameModeWithoutRewindBoundary(GameMode nextMode) {
        GameMode oldMode = currentGameMode;
        if (oldMode == GameMode.SPECIAL_STAGE && oldMode != nextMode) {
            specialStageEntryPresentation.clear();
        }
        currentGameMode = nextMode;
        return oldMode;
    }

    private void reportRewindModeBoundary(GameMode oldMode, GameMode newMode) {
        GameplayModeContext context = resolveGameplayModeContext();
        if (context == null) {
            return;
        }
        if (oldMode == GameMode.LEVEL && newMode != GameMode.LEVEL) {
            context.markRewindBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
        }
        if (oldMode == GameMode.SPECIAL_STAGE && isActiveSpecialStageProviderRewindable()) {
            deregisterSpecialStageAdapter();
            if (!specialStageRewindBoundaryThisFrame) {
                context.markRewindBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
            }
        }
        if (newMode == GameMode.SPECIAL_STAGE && isActiveSpecialStageProviderRewindable()) {
            context.markRewindBoundary(RewindBoundary.MODE_ENTER_REWINDABLE);
        }
        if (oldMode != GameMode.LEVEL && newMode == GameMode.LEVEL) {
            context.markRewindBoundary(RewindBoundary.MODE_ENTER_REWINDABLE);
        }
    }

    private void deregisterSpecialStageAdapter() {
        GameplayModeContext context = resolveGameplayModeContext();
        if (context != null) {
            context.deregisterSpecialStageAdapter();
        }
    }

    void markBonusEntryNonRewindableBoundary() {
        GameplayModeContext context = resolveGameplayModeContext();
        if (context != null) {
            context.markRewindBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
        }
    }

    GameMode enterBonusTitleCardAfterLevelLoadBoundary() {
        markBonusEntryNonRewindableBoundary();
        postTitleCardDestination = PostTitleCardDestination.BONUS_STAGE;
        return changeGameModeWithoutRewindBoundary(GameMode.TITLE_CARD);
    }

    /**
     * Pauses the game loop due to window losing focus.
     * Audio is also paused to prevent music continuing while game is frozen.
     */
    public synchronized void pause() {
        if (!paused) {
            paused = true;
            updateAudioPauseState();
        }
    }

    /**
     * Resumes the game loop after window regains focus.
     * Audio playback is restored only if user hasn't also paused via keyboard.
     */
    public synchronized void resume() {
        if (paused) {
            paused = false;
            updateAudioPauseState();
        }
    }

    /**
     * Toggles the user-initiated pause state (via keyboard).
     * This is separate from window focus pause so both can work independently.
     */
    public synchronized void toggleUserPause() {
        userPaused = !userPaused;
        updateAudioPauseState();
    }

    /**
     * @return true if the user has paused the game via keyboard
     */
    public boolean isUserPaused() {
        return userPaused;
    }

    /** @see OuterFramePresentation#modeFor */
    public PresentationMode presentationModeForOuterFrame(boolean modalPicker, boolean frameStepRequested) {
        return outerFramePresentation.modeFor(modalPicker, isPaused(), frameStepRequested);
    }

    /** @see OuterFramePresentation#present */
    public void presentOuterFrame(boolean modalPicker, boolean frameStepRequested) {
        outerFramePresentation.present(modalPicker, isPaused(), frameStepRequested);
    }

    void setAudioPresentationProbe(OuterFramePresentation.Probe probe) { outerFramePresentation.setProbe(probe); }

    /**
     * @return true if the game loop is currently paused (either by window or user)
     */
    public synchronized boolean isPaused() {
        return paused || userPaused;
    }

    public boolean externalFrameOrInputOwnerActive() {
        return ExternalFrameOrInputOwnership.active(engineServices);
    }

    /**
     * Updates audio pause state based on combined pause flags.
     * Audio should be paused if either window or user pause is active.
     */
    private synchronized void updateAudioPauseState() {
        if (paused || userPaused) {
            audioManager.pause();
        } else {
            audioManager.resume();
        }
    }

    /**
     * Advances the game by one frame. This is the main update loop.
     * Call this method at your target FPS (typically 60fps).
     */
    public void step() {
        try {
            TraceSessionLauncher traceSession = TraceSessionLauncher.active();
            int traceFastForwardSteps = traceSession == null
                    ? 0
                    : traceSession.beginFastForwardOuterFrame(inputHandler, isPaused());
            LevelIterationAdmissionController.runTraceObservedStep(
                    this::stepInternal, () -> currentGameMode,
                    playbackDebugManager::getCursorFrame);
            int fastForwardedFrames = 0;
            while (fastForwardedFrames < traceFastForwardSteps
                    && !isPaused()
                    && traceSession.isFastForwardPumpAllowed()) {
                LevelIterationAdmissionController.runTraceObservedStep(
                        this::stepInternal, () -> currentGameMode,
                        playbackDebugManager::getCursorFrame);
                fastForwardedFrames++;
            }
            int pumpedFrames = 0;
            while (!isPaused()
                    && userRecordingControls.shouldPumpFastForward()
                    && pumpedFrames < USER_RECORDING_FAST_FORWARD_EXTRA_STEPS_PER_FRAME) {
                LevelIterationAdmissionController.runTraceObservedStep(
                        this::stepInternal, () -> currentGameMode,
                        playbackDebugManager::getCursorFrame);
                pumpedFrames++;
            }
        } finally {
            runAfterStepMasterTitleLaunchCallbackIfPresent();
            presenceManager.tick();
        }
    }

    public void closePresence() {
        presenceManager.close();
    }

    /**
     * Time-attack retry key, mirroring how
     * {@code LiveUserRecordingRuntime.recordKey()} reads
     * {@link SonicConfiguration#RECORDING_RECORD_KEY}.
     */
    private int timeAttackRetryKey() {
        return configService.getInt(SonicConfiguration.TIME_ATTACK_RETRY_KEY);
    }

    /**
     * True while the level is mid a special-stage/bonus-stage/ending transition
     * or a pending zone/act transition -- the exact same composite condition
     * the gameplay-tick freeze block below computes (special/bonus/ending/
     * zone-act freeze). {@code currentGameMode} stays {@code GameMode.LEVEL}
     * throughout this whole window (the fade only flips the mode once its
     * completion callback runs), so this is exposed for
     * {@link LiveRewindManager}'s mode-based "not applicable" gate to consult
     * -- a sub-state {@code GameMode} alone cannot express. See
     * ssentry-rewind-report.md.
     * <p>
     * <strong>This predicate freezes ordinary gameplay ticks</strong> (via the
     * freeze block below) whenever it is true -- it must stay scoped to
     * exactly the four flags that legitimately warrant that freeze (this was
     * pre-existing behavior). Do NOT fold {@link FadeManager#hasPendingCompletion()}
     * in here: unlike a special/bonus/ending/zone-act transition, an ordinary
     * callback-bearing fade (e.g. death respawn, act-complete) does not freeze
     * ROM gameplay -- objects keep ticking underneath a cosmetic fade overlay.
     * See {@link #isRewindBlocked()} for the rewind-only superset that adds the
     * fade term.
     */
    private boolean isNonRewindableTransitionPending() {
        // Called unconditionally near the top of stepInternal(), before any
        // currentGameMode-specific dispatch -- levelManager can be null in
        // non-gameplay modes (e.g. MASTER_TITLE_SCREEN with no active session).
        return specialStageTransitionPending
                || bonusStageTransitionPending
                || endingTransitionPending
                || (levelManager != null && levelManager.isLevelInactiveForTransition());
    }

    /**
     * Services a queued Special Stage request when the run frame driver owns
     * the physical row and suppresses the native level body. The request is
     * still an engine-owned transition; only the ordinary gameplay work is
     * suppressed for the shared gap.
     */
    boolean consumeSpecialStageRequestDuringSuppressedRunRow() {
        if (currentGameMode != GameMode.LEVEL || levelManager == null
                || !levelManager.consumeSpecialStageRequest()) {
            return false;
        }
        enterSpecialStage();
        return true;
    }

    /**
     * Presents a level restart's mandatory title card on a row whose ordinary
     * level body is suppressed.
     *
     * <p>The card is not gameplay: the ROM's {@code Level_TtlCardLoop} runs
     * {@code ExecuteObjects}/{@code BuildSprites} over freshly cleared object
     * RAM holding only the card's own elements, plus {@code RunPLC}
     * (docs/s1disasm/sonic.asm:2814-2842). A restart therefore reaches the card
     * on the same rows a run's shared transition gap suppresses the level body
     * on, exactly as the special-stage results exit already does from its own
     * mode's update.
     */
    boolean presentPendingTitleCardDuringSuppressedRunRow() {
        if (currentGameMode != GameMode.LEVEL || levelManager == null
                || !levelManager.consumeTitleCardRequest()) {
            return false;
        }
        enterTitleCard(levelManager.getTitleCardZone(), levelManager.getTitleCardAct());
        return true;
    }

    /**
     * Starts a level restart's fade on a row whose ordinary level body is
     * suppressed.
     *
     * <p>{@code Sonic_ResetLevel}'s sixtieth decrement writes {@code f_restart}
     * from inside the object pass of the first row a run's transition gap owns
     * (docs/s1disasm/_incObj/01 Sonic.asm:2062-2073); the level main loop's own
     * test then falls into {@code GM_Level} without iterating again
     * (docs/s1disasm/sonic.asm:3016-3018). The engine's respawn consumer runs a
     * row later than that test, which lands on a gap row whose body is already
     * suppressed, so the restart is started here for the same reason the
     * results exit and the restart's title card are.
     */
    boolean startPendingRespawnDuringSuppressedRunRow() {
        if (currentGameMode != GameMode.LEVEL || levelManager == null
                || fadeManager == null || fadeManager.isActive()
                || !levelManager.consumeRespawnRequest()) {
            return false;
        }
        startRespawnFade();
        return true;
    }

    /**
     * Set when {@link #exitTitleCard()} releases into {@link GameMode#LEVEL}
     * during the current iteration, and cleared at the top of every iteration.
     * Read only by {@link #runGapRowContinuesSourceLevelMainLoop()}.
     */
    private boolean titleCardReleasedIntoLevelThisIteration;

    /**
     * True while a run's shared transition-gap row is still the source level's
     * own main-loop iteration, so the ordinary level body must run on it.
     *
     * <p>See {@link TraceSessionLauncher#runGapRowContinuesSourceLevelMainLoop}
     * for the recorder and ROM basis. The engine's form of the ROM writes that
     * end a level loop is a pending level-exit request
     * ({@link com.openggf.level.LevelTransitionCoordinator#hasPendingLevelExit()})
     * or an in-flight blocking transition ({@link #isRewindBlocked()}, which
     * covers a native blocking fade's pending completion and the
     * bonus/ending/zone-act flags); either means the loop has already ended.
     *
     * <p>A title-card release into LEVEL on THIS iteration is a third such
     * write, and it is the one a level-to-level gap actually lands on. Once the
     * destination level has loaded and its card has been released, the source
     * level's main loop is provably over: the release IS
     * {@code Level_StartGame}, which the ROM reaches only after the title-card
     * leave loop's last iteration (docs/s2disasm/s2.asm:5060-5066, :5081-5082).
     * The destination's first object pass then comes from
     * {@code Level_MainLoop}, which runs {@code PauseGame} and
     * {@code WaitForVint} BEFORE its {@code jsr (RunObjects).l}
     * (docs/s2disasm/s2.asm:5088-5095) -- so the release row itself dispatches
     * no object pass at all, and the first one belongs to the destination's
     * next V-blank, i.e. its recorded row 0. S1 orders the same two routines the
     * same way ({@code Level_StartGame} at docs/s1disasm/sonic.asm:2990-2991,
     * then {@code Level_MainLoop}'s {@code WaitForVBlank} at :2998-3001 ahead of
     * {@code jsr (ExecuteObjects).l} at :3006), and so does S3K
     * ({@code bclr #7,(Game_mode).w} at docs/skdisasm/sonic3k.asm:7882, then
     * {@code Wait_VSync} at :7888 ahead of {@code Process_Sprites} at :7894).
     *
     * <p>Without this term the latch survived every locked title-card row (a
     * title-card iteration never reaches the gap-body test) and was consumed by
     * the release row instead, handing the freshly loaded destination one extra
     * pre-row-0 object pass. That put its CPU sidekick exactly one
     * {@code Tails_acceleration} step ahead for the rest of the segment
     * (docs/s2disasm/s2.asm:38907).
     */
    private boolean runGapRowContinuesSourceLevelMainLoop() {
        boolean levelExitWritten = levelManager == null
                || levelManager.hasPendingLevelExit()
                || titleCardReleasedIntoLevelThisIteration
                || isRewindBlocked();
        return TraceSessionLauncher.runGapRowContinuesSourceLevelMainLoop(
                currentGameMode, levelExitWritten);
    }

    /**
     * True whenever rewind engagement must be rejected: either
     * {@link #isNonRewindableTransitionPending()} (the four transition flags,
     * which ALSO freeze gameplay), or a fade is in flight with a completion
     * callback that has not yet run ({@link FadeManager#hasPendingCompletion()}).
     * <p>
     * The fade term is intentionally NOT folded into
     * {@link #isNonRewindableTransitionPending()} itself, because that method
     * also drives the gameplay-tick freeze block -- ROM gameplay keeps ticking
     * during an ordinary callback-bearing fade (death respawn, act-complete,
     * the S1 giant-ring special-stage entry, etc.), it is only REWIND
     * engagement that must be rejected there: {@link FadeManager#restore()}
     * deliberately does not restore the transient {@code onFadeComplete}
     * callback, so a rewind restore landing inside such a fade's window
     * orphans whatever the callback was going to do (dropping or freezing the
     * transition), independent of whether gameplay itself is frozen. This
     * composite is consumed ONLY by the two rewind-engagement call sites
     * below ({@link LiveRewindManager}/{@link TraceSessionLauncher}), never by
     * the gameplay freeze block. See ssentry-rewind-report.md.
     */
    private boolean isRewindBlocked() {
        // fadeManager can be null in non-gameplay modes (e.g.
        // MASTER_TITLE_SCREEN with no active session).
        return isNonRewindableTransitionPending()
                || (fadeManager != null && fadeManager.hasPendingCompletion());
    }

    /**
     * True while the current bonus stage supports held rewind (Gumball /
     * Pachinko). The Slot Machine's provider reports supportsRewind()==false,
     * so its rewind hooks are never driven and it keeps its no-rewind behavior.
     */
    private boolean isBonusStageRewindable() {
        return currentGameMode == GameMode.BONUS_STAGE
                && activeBonusStageProvider != null
                && activeBonusStageProvider.supportsRewind();
    }

    private boolean isSpecialStageRewindable() {
        return currentGameMode == GameMode.SPECIAL_STAGE
                && !specialStageRewindBoundaryThisFrame
                && isActiveSpecialStageProviderRewindable();
    }

    private boolean isActiveSpecialStageProviderRewindable() {
        return activeSpecialStageProvider != null
                && activeSpecialStageProvider.supportsRewind();
    }

    private void severSpecialStageRewindForLiveOnlyShortcut() {
        if (currentGameMode != GameMode.SPECIAL_STAGE || specialStageRewindBoundaryThisFrame) {
            return;
        }
        specialStageRewindBoundaryThisFrame = true;
        GameplayModeContext context = resolveGameplayModeContext();
        if (context != null) {
            context.markRewindBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
        }
    }

    private void prepareSpecialStageRewindFrame() {
        specialStageRewindBoundaryThisFrame = false;
        if (currentGameMode == GameMode.SPECIAL_STAGE) {
            detectSpecialStageLiveOnlyShortcutBoundary();
        }
    }

    private void finishTimeAttackMasterTitleFrame(MasterTitleScreen masterScreen) {
        if (pendingTimeAttackLaunch != null) {
            TimeAttackLaunchRequest deferredLaunch = pendingTimeAttackLaunch;
            pendingTimeAttackLaunch = null;
            timeAttackLaunchHandler.launch(deferredLaunch);
        }
        if (pendingReopenTimeAttackMenu && masterScreen != null
                && masterScreen.tryOpenTimeAttackMenu()) {
            pendingReopenTimeAttackMenu = false;
        }
        wasMasterTitleScreenActiveLastFrame = true;
    }

    private void finishTimeAttackMasterTitleExit() {
        if (wasMasterTitleScreenActiveLastFrame) {
            pendingReopenTimeAttackMenu = false;
        }
        wasMasterTitleScreenActiveLastFrame = false;
    }

    private void handleTimeAttackRetryInput() {
        if (timeAttackRuntime.isActive() && inputHandler.isKeyPressed(timeAttackRetryKey())) {
            timeAttackRuntime.requestRetry();
        }
    }

    private void requireInputHandler() {
        if (inputHandler == null) {
            throw new IllegalStateException("InputHandler must be set before calling step()");
        }
    }

    private void stepInternal() {
        continueScreen.beginIteration();
        refreshRuntimeBindings();
        GameplayModeContext lifecycleContext = resolveGameplayModeContext();
        if (lifecycleContext == null || !lifecycleContext.isGameplayRuntimeReady()) {
            stepInternalBody();
            return;
        }
        Runnable productionIteration = () ->
            lifecycleContext.plcFrameLifecycle().runLogicalIteration(
                    frame -> {
                        if (frame.isOwnedBy(PlcLifecyclePhase.PALETTE_FADE)) {
                            LevelFrameStep.dispatchGameVBlank(
                                    LevelFrameContext.from(lifecycleContext), frame);
                        }
                    },
                    continueScreen::updateFade, frame -> {
                        activePlcLifecycleFrame = frame;
                        try {
                            stepInternalBody();
                            return null;
                        } finally {
                            activePlcLifecycleFrame = null;
                        }
                    });
        TraceSessionLauncher.runProductionIterationIfActive(
                productionIteration, this::advanceTraceRunPhysicalRow);
    }

    private void advanceTraceRunPhysicalRow() {
        levelIterationAdmission.advanceTraceRunPhysicalRow(
                playbackDebugManager, userRecordingControls,
                TraceSessionLauncher.active());
    }

    private void stepInternalBody() {
        requireInputHandler();
        LevelIterationAdmissionController.refreshTraceInputSnapshot(inputHandler);
        audioUpdatedThisStep = false;
        refreshRuntimeBindings();
        prepareSpecialStageRewindFrame();
        PaletteOwnershipRegistry paletteRegistry = GameServices.paletteOwnershipRegistryOrNull();
        if (paletteRegistry != null) {
            paletteRegistry.beginFrame();
        }
        com.openggf.game.TitleInputOwnership.routePlayback(currentGameMode,
                () -> playbackDebugManager.handleInput(inputHandler));
        playbackDebugManager.setObservedMode(currentGameMode);

        if (RewindReleaseRetryCoordinator.consumePendingFrame(liveRewindManager, inputHandler)) return;
        boolean rewindBlocked = isRewindBlocked();
        if (currentGameMode == GameMode.LEVEL
                && TraceSessionLauncher.active() != null
                && TraceSessionLauncher.active().handleRealtimeRewindInput(
                        rewindBlocked, inputHandler)) {
            inputHandler.update();
            return;
        }
        if ((currentGameMode == GameMode.LEVEL || isBonusStageRewindable() || isSpecialStageRewindable())
                && TraceSessionLauncher.active() == null
                && !timeAttackRuntime.isActive()
                && liveRewindManager.handleRealtimeRewindInput(
                        currentGameMode, rewindBlocked, inputHandler)) {
            inputHandler.update();
            return;
        }

        boolean playbackTakeoverConsumedPausePress =
                handlePlaybackTakeoverBeforePlaybackInputBridge(inputHandler);

        if (currentGameMode == GameMode.LEGAL_DISCLAIMER) {
            bootScreenModeController.updateLegalDisclaimer(
                    legalDisclaimerSupplier != null ? legalDisclaimerSupplier.get() : null,
                    inputHandler,
                    () -> {
                        if (legalDisclaimerExitHandler != null) {
                            legalDisclaimerExitHandler.run();
                            legalDisclaimerExitHandler = null;
                        }
                    });
            return;
        }

        if (currentGameMode == GameMode.NATIVE_MOD_NOTICE) {
            bootScreenModeController.updateNativeModNotice(
                    nativeModNoticeSupplier != null ? nativeModNoticeSupplier.get() : null,
                    inputHandler,
                    () -> {
                        if (nativeModNoticeExitHandler != null) {
                            nativeModNoticeExitHandler.run();
                            nativeModNoticeExitHandler = null;
                        }
                    });
            return;
        }

        if (currentGameMode == GameMode.MASTER_TITLE_SCREEN) {
            escapeToMasterTitleController.update(currentGameMode, inputHandler);
            MasterTitleScreen masterScreen = currentMasterTitleScreen();
            bootScreenModeController.updateMasterTitle(
                    masterScreen,
                    inputHandler,
                    this::exitMasterTitleScreen);
            if (!resolveFadeManager().isActive()) {
                com.openggf.game.TitleInputOwnership.routeQuit(masterScreen, this::startEscapeApplicationExitTransition);
            }
            finishTimeAttackMasterTitleFrame(masterScreen);
            return;
        }
        finishTimeAttackMasterTitleExit();

        if (!isPaused()
                && !timeAttackRuntime.isActive()
                && (currentGameMode == GameMode.EDITOR
                || (currentGameMode == GameMode.LEVEL
                && configService.getBoolean(SonicConfiguration.EDITOR_ENABLED)))
                && inputHandler.isKeyPressed(GLFW_KEY_TAB)
                && (inputHandler.isKeyDown(GLFW_KEY_LEFT_SHIFT)
                || inputHandler.isKeyDown(GLFW_KEY_RIGHT_SHIFT))
                && editorPlaytestToggleHandler != null) {
            editorPlaytestToggleHandler.run();
            inputHandler.update();
            return;
        }

        if (currentGameMode == GameMode.EDITOR) {
            if (inputHandler.isKeyPressed(GLFW_KEY_F5) && editorFreshStartHandler != null) {
                editorFreshStartHandler.run();
                inputHandler.update();
                return;
            }
            updateEditorMode();
            inputHandler.update();
            return;
        }

        TraceSessionLauncher visualTraceSession = TraceSessionLauncher.active();
        if (LevelIterationAdmissionController.shouldVisualTraceOwnEscape(
                currentGameMode, visualTraceSession,
                inputHandler.isKeyPressed(GLFW_KEY_ESCAPE))) {
            visualTraceSession.requestEarlyExit();
            inputHandler.update();
            return;
        }

        escapeToMasterTitleController.update(currentGameMode, inputHandler);
        if (currentGameMode == GameMode.LEVEL) {
            userRecordingControls.updateLevelControlInput(inputHandler);
            handleTimeAttackRetryInput();
        }

        int pauseKey = configService.getInt(SonicConfiguration.PAUSE_KEY);
        if (!userPauseInputAllowedForCurrentMode() && userPaused) {
            userPaused = false;
            updateAudioPauseState();
        }
        // Gamepad Start toggles this pause (not the silent ROM Game_paused pause
        // below) because this is the one with visible feedback: the "PAUSED" HUD
        // overlay and the audio halt both key off userPaused/isUserPaused().
        if (!playbackTakeoverConsumedPausePress
                && userPauseInputAllowedForCurrentMode()
                && (inputHandler.isKeyPressed(pauseKey)
                || (!TraceSessionLauncher.isRunFrameDriverActive()
                && !playbackDebugManager.isDriving(currentGameMode)
                && inputHandler.logical().player1().startPressed()))) {
            if (userPaused && userRecordingControls.handlePlaybackTakeoverRequest()) {
                userPaused = false;
                updateAudioPauseState();
            } else {
                toggleUserPause();
            }
        }

        int frameStepKey = configService.getInt(SonicConfiguration.FRAME_STEP_KEY);
        boolean doFrameStep = isPaused() && inputHandler.isKeyPressed(frameStepKey);

        if (isPaused() && !doFrameStep) {
            inputHandler.update();
            return;
        }

        titleCardReleasedIntoLevelThisIteration = false;
        if (!prepareAdmittedIteration(doFrameStep)) {
            return;
        }

        if ((currentGameMode == GameMode.LEVEL
                || currentGameMode == GameMode.BONUS_STAGE)
                && TraceSessionLauncher.admitsRunLogicalGameplayInput(currentGameMode)) {
            playbackDebugManager.shouldSkipCurrentGameplayTick();
        }

        boolean deferAudioUntilGameplayTick =
                currentGameMode == GameMode.LEVEL
                        || currentGameMode == GameMode.BONUS_STAGE
                        || currentGameMode == GameMode.TITLE_CARD;
        if (!deferAudioUntilGameplayTick) {
            updateNonGameplayAudio(doFrameStep);
        }

        if (!playbackDebugManager.isCurrentGameplayTickSuppressed()) {
            profiler.beginSection("timers");
            timerManager.update();
            profiler.endSection("timers");
        }

        profiler.beginSection("input");
        boolean debugShortcutsEnabled = debugShortcutsEnabled();
        if (timeAttackRuntime.isActive() && debugShortcutsEnabled
                && TimeAttackDebugInput.taintPressed(inputHandler, configService)) {
            timeAttackRuntime.markTainted();
        }
        debugOverlayManager.updateInput(inputHandler, debugShortcutsEnabled, configService);
        if (debugShortcutsEnabled) {
            debugOverlayManager.getObjectArtViewer().updateInput(inputHandler);
        }

        if (isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_KEY))) {
            if (currentGameMode == GameMode.SPECIAL_STAGE) {
                severSpecialStageRewindForLiveOnlyShortcut();
            }
            handleSpecialStageDebugKey();
        }

        BonusStageType debugBonusType = debugShortcutsEnabled
                ? resolveBonusStageDebugShortcut(inputHandler)
                : BonusStageType.NONE;
        if (debugBonusType != BonusStageType.NONE) {
            handleBonusStageDebugKey(debugBonusType);
        }

        if (currentGameMode == GameMode.SPECIAL_STAGE) {
            updateSpecialStageMode();
        } else if (currentGameMode == GameMode.SPECIAL_STAGE_RESULTS) {
            updateSpecialStageResultsMode();
        } else if (currentGameMode == GameMode.TITLE_CARD) {
            throw new IllegalStateException("title-card admission must own the iteration");
        } else if (currentGameMode == GameMode.TITLE_SCREEN) {
            GameLoopPlcLifecycle.runPhase(activePlcLifecycleFrame,
                    PlcLifecyclePhase.TITLE_SCREEN, this::updateTitleScreenMode);
            profiler.endSection("input");
            return;
        } else if (currentGameMode == GameMode.LEVEL_SELECT) {
            GameLoopPlcLifecycle.runPhase(activePlcLifecycleFrame,
                    PlcLifecyclePhase.LEVEL_SELECT, this::updateLevelSelectMode);
            profiler.endSection("input");
            return;
        } else if (currentGameMode == GameMode.CONTINUE_SCREEN) {
            GameLoopPlcLifecycle.runPhase(activePlcLifecycleFrame,
                    PlcLifecyclePhase.CONTINUE_SCREEN, () -> continueScreen.update(activePlcLifecycleFrame));
            profiler.endSection("input");
            return;
        } else if (currentGameMode == GameMode.DATA_SELECT) {
            updateDataSelectMode();
            profiler.endSection("input");
            return;
        } else if (currentGameMode == GameMode.CREDITS_TEXT
                || currentGameMode == GameMode.CREDITS_DEMO
                || currentGameMode == GameMode.TRY_AGAIN_END
                || currentGameMode == GameMode.ENDING_CUTSCENE) {
            GameLoopPlcLifecycle.runPhase(activePlcLifecycleFrame,
                    GameLoopPlcLifecycle.endingPhase(endingProvider), this::updateEnding);
            inputHandler.update();
            profiler.endSection("input");
            return;
        }

        profiler.endSection("input");

        // Evaluated before the suppression test, never inside it: the call
        // consumes the gap's first row whatever mode that row is in, and a gap
        // opened from a Special Stage results screen spends its first rows
        // outside LEVEL.
        boolean gapRowContinuesLevelMainLoop =
                runGapRowContinuesSourceLevelMainLoop();
        if (TraceSessionLauncher.suppressesRunNativeLevelBody(currentGameMode)
                && !gapRowContinuesLevelMainLoop) {
            // A shared transition gap's rows are owned by the game's blocking
            // level-exit/entry routine once the level's own main loop has ended
            // (see runGapRowContinuesSourceLevelMainLoop for the row that still
            // belongs to the loop). That routine is not gameplay, but it does
            // make mode transitions of its own -- the S1 results path raises the
            // Special Stage request from a fade callback, a restart's load
            // raises its locked title card, and a death's own f_restart starts
            // the reload. Service those here or the run driver replays gap input
            // forever against a scene that never moves.
            consumeSpecialStageRequestDuringSuppressedRunRow();
            presentPendingTitleCardDuringSuppressedRunRow();
            startPendingRespawnDuringSuppressedRunRow();
            inputHandler.update();
            return;
        }

        if (currentGameMode == GameMode.LEVEL) {
            if (!updateLevelMode(doFrameStep)) {
                return;
            }
        } else if (currentGameMode == GameMode.BONUS_STAGE) {
            updateBonusStageMode(doFrameStep);
        }

        if (traceCameraFocusController != null) {
            traceCameraFocusController.postUpdate();
        }

        inputHandler.update();
    }

    public boolean ownsGameplayFadeLifecycle() {
        GameplayModeContext context = resolveGameplayModeContext();
        return context != null && context.isGameplayRuntimeReady();
    }

    private boolean prepareAdmittedIteration(boolean doFrameStep) {
        syncPlaybackInputBridge();
        LevelFrameResult admission = levelIterationAdmission.admit(
                currentGameMode, () -> updateTitleCardMode(doFrameStep),
                () -> titleReleaseResult, levelManager, gameplayMode,
                inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.START))
                        || playbackDebugManager.isCurrentForcedStartPress(),
                userRecordingControls,
                request -> routeTimeAttackSeamlessTransitionBeforeApply(
                        request, doFrameStep),
                this::startPendingInLevelTitleCard,
                () -> LevelIterationAdmissionController
                        .prepareTraceRunAdmissionAndHardwareTiming(
                                currentGameMode, this::syncPlaybackInputBridge),
                LevelIterationAdmissionController
                        ::deactivateTraceHardwareTimingForAdmission);
        if (admission == LevelFrameResult.PAUSED) {
            LevelFrameStep.serviceVBlankOnly(LevelFrameContext.from(gameplayMode),
                    activePlcLifecycleFrame, PlcLifecyclePhase.NORMAL_PAUSE);
            inputHandler.update();
            return false;
        }
        if (admission == LevelFrameResult.SETUP_ONLY) {
            return false;
        }
        if (levelIterationAdmission.completePendingBoundary(
                doFrameStep, this::updateNonGameplayAudio,
                () -> levelIterationAdmission.finishPlaybackBoundary(
                        true, playbackDebugManager, userRecordingControls),
                this::resolveGameplayModeContext)) {
            LevelFrameStep.serviceVBlankOnly(LevelFrameContext.from(gameplayMode),
                    activePlcLifecycleFrame, PlcLifecyclePhase.LAG);
            inputHandler.update();
            return false;
        }
        if (traceCameraFocusController != null) {
            traceCameraFocusController.tick(inputHandler);
        }
        if (currentGameMode == GameMode.LEVEL) {
            userRecordingControls.updateLevelControlInput(inputHandler);
        }
        return true;
    }

    /**
     * SPECIAL_STAGE per-frame update: debug shortcuts, sprite-debug navigation,
     * the special-stage input/update tick, and the completion → results-screen
     * handoff. Falls through (no early return) to the shared post-update tail,
     * matching the original dispatcher.
     */
    private void updateSpecialStageMode() {
        SpecialStageProvider ssProvider = getActiveSpecialStageProvider();
        GameLoopSpecialStageLifecycle.update(ssProvider, configService,
                this::isUnmodifiedDebugKeyPressed,
                this::debugCompleteSpecialStageWithEmerald, this::debugFailSpecialStage,
                inputHandler, gameplayMode, activePlcLifecycleFrame,
                specialStageObservationPacing, this::updateSpecialStageInput,
                specialStageEntryPresentation, fadeManager,
                () -> revealSpecialStage(ssProvider), this::isSpecialStageRewindable,
                liveRewindManager, currentGameMode, this::enterResultsScreen);
    }


    /**
     * SPECIAL_STAGE_RESULTS per-frame update: advances the results screen and
     * exits when complete. Falls through to the shared post-update tail.
     */
    public boolean commitDeferredTraceRunModeBoundaryIfReady() {
        return TraceSessionLauncher.commitDeferredRunModeBoundary(
                currentGameMode, getActiveSpecialStageProvider(), this::enterResultsScreen);
    }

    private void updateSpecialStageResultsMode() {
        // ROM SS_NormalExit is a full loop iteration: VintID_TitleCards, a
        // V-int that runs ProcessPLC_9Tiles (docs/s1disasm/sonic.asm:946),
        // ExecuteObjects, BuildSprites, then RunPLC at the tail
        // (docs/s1disasm/sonic.asm:3402-3413). Claiming the lifecycle frame
        // alone never reached the hardware timing boundaries, so the loop-tail
        // arm was never submitted as hardware work.
        LevelFrameStep.executeHardwareTimedObjectScan(
                LevelFrameContext.from(gameplayMode), activePlcLifecycleFrame,
                PlcLifecyclePhase.SPECIAL_STAGE_RESULTS,
                this::runSpecialStageResultsIteration);
    }

    /** One ROM SS_NormalExit iteration (docs/s1disasm/sonic.asm:3402-3413). */
    private void runSpecialStageResultsIteration() {
        if (resultsExitReady) {
            // ROM: the GM loop only falls through to the next mode's dispatch
            // on the frame after the whiteout's final WaitForVBla, so the
            // returning level's load (and its ClearPLC/AddPLC submissions)
            // belongs to the first frame past the results screen's last
            // sampled row, never to that row itself.
            if (resultsExitPreLevelFadeFramesRemaining < 0) {
                // Level begins before its counted fade: Sonic 2's loc_3EC4
                // PlaySound(F9) precedes ClearPLC/Pal_FadeToBlack.
                levelManager.beginLevelEntry();
                resultsExitPreLevelFadeFramesRemaining = GameServices.module()
                        .getLevelInitProfile().preLevelFadeOutFrames();
            }
            if (resultsExitPreLevelFadeFramesRemaining > 0) {
                // The next mode's own entry fades out the previous screen
                // before it queues the returning level's PLCs (S1: GM_Level's
                // ClearPLC + PaletteFadeOut, sonic.asm:2710-2716). Each fade
                // frame is a WaitForVBlank row whose RunPLC only ever sees the
                // cleared queue, so hold the exit body — and with it the
                // reload's PLC submissions — for the fade's duration.
                resultsExitPreLevelFadeFramesRemaining--;
                return;
            }
            resultsExitPreLevelFadeFramesRemaining = -1;
            resultsExitReady = false;
            doExitResultsScreen();
            return;
        }
        if (resultsExitFadeCompleted) {
            // The exit fade's completion fired during this iteration's fade
            // update; hold the (already white) screen for the rest of the
            // frame — the ROM CPU is still inside PaletteWhiteOut's final
            // wait — and run the exit body on the next iteration.
            resultsExitFadeCompleted = false;
            resultsExitReady = true;
        } else {
            // Update results screen
            resultsFrameCounter++;
            if (resultsScreen != null) {
                resultsScreen.update(resultsFrameCounter, null);
                if (resultsScreen.isComplete()) {
                    exitResultsScreen();
                }
            }
        }
    }

    /**
     * TITLE_CARD per-frame update.
     *
     * @return {@code true} if the title card released control this frame,
     *         {@code false} if the card is still in its locked phase
     */
    private boolean updateTitleCardMode(boolean doFrameStep) {
        return GameLoopTitleCardLifecycle.update(
                doFrameStep, getTitleCardProviderLazy(), activePlcLifecycleFrame,
                gameplayMode, levelManager, spriteManager, camera, inputHandler,
                this::runInputNeutralTitleCardPlayerPrelude, postTitleCardDestination,
                this::exitTitleCard, result -> titleReleaseResult = result,
                this::beginGameplayAudioFrameForTick,
                () -> advanceGameplayAudioFrameForTick(doFrameStep),
                phase -> GameLoopPlcLifecycle.prepare(activePlcLifecycleFrame, phase),
                (name, step) -> {
                    profiler.beginSection(name);
                    step.run();
                    profiler.endSection(name);
                });
    }

    /**
     * TITLE_SCREEN per-frame update: delegates provider sequencing to the
     * menu-screen controller and exits once it reports an exit request and no
     * fade is in progress. The controller owns {@code inputHandler.update()}.
     */
    private void updateTitleScreenMode() {
        menuScreenModeController.updateTitleScreen(
                getTitleScreenProviderLazy(),
                inputHandler,
                () -> {
                    if (!fadeManager.isActive()) {
                        exitTitleScreen();
                    }
                });
    }

    /**
     * LEVEL_SELECT per-frame update: delegates provider sequencing to the
     * menu-screen controller and exits once it reports a selection.
     */
    private void updateLevelSelectMode() {
        menuScreenModeController.updateLevelSelect(
                getLevelSelectProviderLazy(),
                inputHandler,
                this::exitLevelSelect);
    }

    /**
     * DATA_SELECT per-frame update: delegates provider sequencing to the
     * menu-screen controller and exits once it reports completion.
     */
    private void updateDataSelectMode() {
        menuScreenModeController.updateDataSelect(
                getDataSelectProviderLazy(),
                inputHandler,
                this::exitDataSelect);
    }

    /**
     * LEVEL per-frame update: overlay tick, seamless/title-card/fade transition
     * routing, the canonical gameplay frame step (or lag-gated skip), special/
     * bonus-stage entry checks, trace-session drive, and level debug keys.
     *
     * @return {@code true} when the frame completed normally and the caller
     *         should run the shared post-update tail; {@code false} when a
     *         transition/fade consumed the frame and the caller must return.
     */
    private boolean updateLevelMode(boolean doFrameStep) {
        // Continue updating title card overlay if still active
        // (TEXT_WAIT and TEXT_EXIT phases where player can move but text is still
        // visible)
        TitleCardProvider tcp = getTitleCardProviderLazy();
        TraceSuppressedRowClosure.executeUnownedTitleCardWork(
                playbackDebugManager.isCurrentGameplayTickSuppressed(),
                tcp, this::startPendingInLevelTitleCard,
                this::applyTitleCardControlLock);

        if (multiplayerRaceCoordinator != null) {
            multiplayerRaceCoordinator.pump();
            multiplayerRaceCoordinator.pollLocalInput(inputHandler);
            if (multiplayerRaceCoordinator.holdGameplay()) {
                updateNonGameplayAudio(doFrameStep);
                return true;
            }
        }

        // Check if a title card was requested (new level loaded)
        if (levelManager.consumeTitleCardRequest()) {
            enterTitleCard(levelManager.getTitleCardZone(), levelManager.getTitleCardAct());
            levelIterationAdmission.finishPlaybackBoundary(
                    false, playbackDebugManager, userRecordingControls);
            updateNonGameplayAudio(doFrameStep);
            return false; // Skip normal level update this frame
        }

        // Check for transition requests that need fade-to-black
        FadeManager fadeManager = this.fadeManager;
        if (!fadeManager.isActive()) {
            if (levelManager.consumeRespawnRequest()) {
                startRespawnFade();
                levelIterationAdmission.finishPlaybackBoundary(
                        false, playbackDebugManager, userRecordingControls);
                updateNonGameplayAudio(doFrameStep);
                return false;
            }
            GameOverExit gameOverExit = levelManager.consumeGameOverExitRequest();
            if (gameOverExit != null) {
                userRecordingControls.stopActiveRecording(UserRecordingStopReason.LEVEL_ENDED);
                GameLoopGameOverExit.startToBlack(gameOverExit, levelManager, audioManager, fadeManager,
                        resolveGameplayModeContext(), () -> continueScreen.enterAfterGameOverFade(gameOverExit));
                levelIterationAdmission.finishPlaybackBoundary(
                        false, playbackDebugManager, userRecordingControls);
                updateNonGameplayAudio(doFrameStep);
                return false;
            }
            // The real act-completion gate: LevelManager.advanceToNextLevel() (called
            // directly by S1/S2 results-screen objects, bypassing the request/consume
            // queue below) and S3kResultsScreenObjectInstance.onExitReady() both queue
            // this instead of advancing when a time attack attempt is active. The four
            // sites below remain for seamless/next-act/next-zone/credits transitions
            // that are NOT time-attack-gated at their source (debug keys, S1/S2 boss
            // defeat -> credits, and the S3K seamless cross-act reload).
            if (levelManager.consumeTimeAttackMenuReturnRequest()) {
                userRecordingControls.stopActiveRecording(UserRecordingStopReason.LEVEL_ENDED);
                timeAttackRuntime.deactivate();
                startTimeAttackReturnToMenuFade();
                levelIterationAdmission.finishPlaybackBoundary(
                        false, playbackDebugManager, userRecordingControls);
                updateNonGameplayAudio(doFrameStep);
                return false;
            }
            if (levelManager.consumeNextActRequest()) {
                userRecordingControls.stopActiveRecording(UserRecordingStopReason.LEVEL_ENDED);
                // A finished/abandoned time attack returns to the time attack menu
                // instead of bleeding into the auto-advanced next act.
                if (TimeAttackLevelEndRouting.returnsToMenuOnLevelEnd(timeAttackRuntime.isActive())) {
                    timeAttackRuntime.deactivate();
                    startTimeAttackReturnToMenuFade();
                } else {
                    startNextActFade();
                }
                levelIterationAdmission.finishPlaybackBoundary(
                        false, playbackDebugManager, userRecordingControls);
                updateNonGameplayAudio(doFrameStep);
                return false;
            }
            if (levelManager.consumeNextZoneRequest()) {
                userRecordingControls.stopActiveRecording(UserRecordingStopReason.LEVEL_ENDED);
                // A finished/abandoned time attack returns to the time attack menu
                // instead of bleeding into the auto-advanced next zone.
                if (TimeAttackLevelEndRouting.returnsToMenuOnLevelEnd(timeAttackRuntime.isActive())) {
                    timeAttackRuntime.deactivate();
                    startTimeAttackReturnToMenuFade();
                } else {
                    startNextZoneFade();
                }
                levelIterationAdmission.finishPlaybackBoundary(
                        false, playbackDebugManager, userRecordingControls);
                updateNonGameplayAudio(doFrameStep);
                return false;
            }
            if (levelManager.consumeZoneActRequest()) {
                userRecordingControls.stopActiveRecording(UserRecordingStopReason.LEVEL_ENDED);
                startZoneActFade(levelManager.getRequestedZone(), levelManager.getRequestedAct(),
                        levelManager.getRequestedMusicId());
                levelIterationAdmission.finishPlaybackBoundary(
                        false, playbackDebugManager, userRecordingControls);
                updateNonGameplayAudio(doFrameStep);
                return false;
            }
            if (levelManager.consumeCreditsRequest()) {
                userRecordingControls.stopActiveRecording(UserRecordingStopReason.LEVEL_ENDED);
                // A finished/abandoned time attack returns to the time attack menu
                // instead of bleeding into the auto-advanced credits sequence.
                if (TimeAttackLevelEndRouting.returnsToMenuOnLevelEnd(timeAttackRuntime.isActive())) {
                    timeAttackRuntime.deactivate();
                    startTimeAttackReturnToMenuFade();
                } else if (GameServices.module().getGameId() == GameId.STANDALONE) {
                    masterTitleExitCoordinator.startStandaloneCompletion();
                } else {
                    startEndingFade();
                }
                levelIterationAdmission.finishPlaybackBoundary(
                        false, playbackDebugManager, userRecordingControls);
                updateNonGameplayAudio(doFrameStep);
                return false;
            }
            if (timeAttackRuntime.isActive() && timeAttackRuntime.consumeRetryRequested()) {
                TimeAttackLaunchRequest retryLaunch = timeAttackRuntime.launch();
                levelManager.getCheckpointState().clear();
                levelManager.getTransitions().requestZoneAndAct(retryLaunch.zone(), retryLaunch.act());
                levelIterationAdmission.finishPlaybackBoundary(
                        false, playbackDebugManager, userRecordingControls);
                updateNonGameplayAudio(doFrameStep);
                return false;
            }
        }

        boolean freezeForArtViewer = debugShortcutsEnabled()
                && debugOverlayManager.isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER);
        // Freeze level updates during special/bonus stage entry transitions, an
        // ending transition, or a pending zone/act fade (ObjB2 transition parity).
        boolean freezeForNonRewindableTransition = isNonRewindableTransitionPending();
        if (!freezeForArtViewer && !freezeForNonRewindableTransition) {
            beginGameplayAudioFrameForTick();
            // LiveTraceComparator (or any PlaybackFrameObserver) may ask
            // us to skip the gameplay tick on ROM lag frames so the
            // engine and trace stay aligned. Cursor advance still runs
            // via onLevelFrameAdvanced below.
            boolean skipGameplay = TraceSessionLauncher.shouldSkipRunGameplayTick(
                    playbackDebugManager);
            boolean holdVblankForPendingLoad =
                    playbackDebugManager.shouldHoldVblankForPendingLevelLoad();
            if (!skipGameplay) {
                // Canonical level tick sequence — see LevelFrameStep for ordering rationale.
                spriteManager.publishHeldInputForLevelEvents(inputHandler);
                // ROM in-game pause (Game_paused / Pause_Loop): the P1 Start leading
                // edge is classified by the outer FrameAdmission before reaching
                // this ordinary step, so a pause freezes the level update while
                // the frame counter still advances. Universal across S1/S2/S3K
                // (the trigger and unpause are a Start-press edge in every game;
                // per-game pause divergences are debug-only cheats inert in normal
                // play). Keyboard-only on purpose: this pause has no visible HUD
                // feedback and never halts audio, so gamepad Start instead drives
                // the PAUSE_KEY-style userPaused toggle below (which does both) --
                // see the pauseKey handling earlier in this method.
                userRecordingControls.beforeLevelFrame(inputHandler);
                if (timeAttackRuntime.isActive()) {
                    timeAttackRuntime.beforeLevelFrame(inputHandler);
                }
                int heldVblank = levelManager.getObjectManager() != null
                        ? levelManager.getObjectManager().getVblaCounter()
                        : 0;
                LevelFrameResult frameResult = LevelFrameStep.execute(
                        LevelFrameContext.from(gameplayMode),
                        activePlcLifecycleFrame, PlcLifecyclePhase.ORDINARY_LEVEL,
                        levelManager, camera, () -> spriteManager.update(inputHandler),
                        (name, step) -> {
                            profiler.beginSection(name);
                            step.run();
                            profiler.endSection(name);
                        });
                if (frameResult == LevelFrameResult.GAMEPLAY_FRAME) {
                    playbackDebugManager.onCurrentGameplayTickExecuted();
                }
                if (frameResult == LevelFrameResult.SETUP_ONLY) {
                    return false;
                }
                if (holdVblankForPendingLoad
                        && playbackDebugManager.shouldHoldVblankForPendingLevelLoad()
                        && levelManager.getObjectManager() != null) {
                    levelManager.getObjectManager().initVblaCounter(heldVblank);
                }
                userRecordingControls.afterLevelFrame();
                if (timeAttackRuntime.isActive()) {
                    timeAttackRuntime.afterLevelFrame();
                }
                if (multiplayerRaceCoordinator != null) {
                    multiplayerRaceCoordinator.afterLevelFrame();
                }
            } else if (levelManager.getObjectManager() != null
                    && !holdVblankForPendingLoad) {
                // ROM v_vbla_byte increments in VBlank even on rows where
                // LevelLoop did not run. Headless trace replay mirrors
                // this in HeadlessTestRunner.skipFrameFromRecording();
                // live visual trace mode must do the same or object timing
                // gates enter gameplay hundreds of VBlanks behind.
                int vblankTicks = playbackDebugManager.currentSkippedTickVblankAdvanceCount();
                for (int tick = 0; tick < vblankTicks; tick++) {
                    LevelFrameStep.serviceVBlankOnly(LevelFrameContext.from(gameplayMode),
                            activePlcLifecycleFrame, PlcLifecyclePhase.LAG);
                    levelManager.getObjectManager().advanceVblaCounter();
                }
            }
            advanceGameplayAudioFrameForTick(doFrameStep);
            // Fire the BK2-advance callback either way — on both real
            // gameplay ticks and lag-gated skips — so the observer's
            // cursor always matches the BK2 cursor. onLevelFrameAdvanced
            // is a no-op when no playback session is active.
            if (!TraceSessionLauncher.isRunFrameDriverActive()) {
                advanceTraceRunPhysicalRow();
            }
            boolean seamlessTransitionCompleted =
                    levelManager.consumeActTransitionRewindBoundaryDuringFrame();
            TraceSessionLauncher traceSession = TraceSessionLauncher.active();
            if ((traceSession != null
                    && !TraceSessionLauncher.isRunFrameDriverActive())
                    || traceSession == null) {
                // Reached only inside the !freezeForNonRewindableTransition branch,
                // so the transition-pending predicate is guaranteed false here.
                LevelRewindFrameRecorder.record(
                        traceSession,
                        liveRewindManager,
                        currentGameMode,
                        freezeForNonRewindableTransition,
                        inputHandler,
                        seamlessTransitionCompleted);
            }

            // Check if a checkpoint star requested a special stage. The request is
            // always consumed here regardless of time-attack state (see
            // enterSpecialStage(), which swallows entry uniformly for every caller).
            SpecialStageEntryRequest specialStageRequest =
                    levelManager.consumeSpecialStageEntryRequest();
            if (specialStageRequest != null) {
                enterSpecialStage(specialStageRequest);
            }

            // Check if a bonus star requested a bonus stage
            BonusStageType bonusRequest = levelManager.consumeBonusStageRequest();
            if (bonusRequest != null) {
                enterBonusStage(bonusRequest);
            }

            // Drive the trace session completion-hold + auto-fade state.
            traceSession = TraceSessionLauncher.active();
            if (traceSession != null) {
                traceSession.tick();
            }
        } else {
            updateNonGameplayAudio(doFrameStep);
            // A level-to-level transition fade freezes gameplay but not V_int:
            // the admission controller consumes the row when the compared span
            // still owns it. See consumeTransitionFreezeRow for the ROM
            // citations and why the ownership question is asked, not assumed.
            if (freezeForNonRewindableTransition
                    && !TraceSessionLauncher.isRunFrameDriverActive()) {
                levelIterationAdmission.consumeTransitionFreezeRow(
                        playbackDebugManager,
                        levelManager != null ? levelManager.getObjectManager() : null,
                        LevelFrameContext.from(gameplayMode), activePlcLifecycleFrame);
            }
        }

        // Debug keys for level transitions (use request system for fade)
        if (isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.NEXT_ACT))) {
            levelManager.requestNextAct();
        }

        if (isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.NEXT_ZONE))) {
            // DEZ: skip to ending cutscene instead of next zone
            if (levelManager.getRomZoneId() == 0x0E) {
                levelManager.requestCreditsTransition();
            } else {
                levelManager.requestNextZone();
            }
        }

        // Debug: Teleport to last checkpoint (END key, only in LEVEL mode)
        if (isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.DEBUG_LAST_CHECKPOINT_KEY))) {
            teleportToLastCheckpoint();
        }

        // Level select key (F9 by default)
        if (isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.LEVEL_SELECT_KEY))) {
            enterLevelSelect();
        }

        if (isUnmodifiedDebugKeyPressed(
                configService.getInt(SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY))) {
            logCurrentPreviewCaptureOverride();
        }
        return true;
    }

    /**
     * Routes a cross-act time-attack request before the admission owner applies
     * its destination. Mid-act seamless requests remain owned by the ordinary
     * transition path.
     */
    private boolean routeTimeAttackSeamlessTransitionBeforeApply(
            SeamlessLevelTransitionRequest request, boolean doFrameStep) {
        if (!TimeAttackLevelEndRouting.shouldSuppressSeamlessTransitionForTimeAttack(
                timeAttackRuntime.isActive(), request.type(), timeAttackRuntime.launch(),
                request.targetZone(), request.targetAct())) {
            return false;
        }
        timeAttackRuntime.deactivate();
        startTimeAttackReturnToMenuFade();
        updateNonGameplayAudio(doFrameStep);
        return true;
    }

    private void runInputNeutralTitleCardPlayerPrelude() {
        java.util.List<AbstractPlayableSprite> playables = spriteManager.getAllSprites().stream()
                .filter(AbstractPlayableSprite.class::isInstance)
                .map(AbstractPlayableSprite.class::cast)
                .toList();
        int[] forcedInputMasks = new int[playables.size()];
        for (int i = 0; i < playables.size(); i++) {
            AbstractPlayableSprite playable = playables.get(i);
            forcedInputMasks[i] = playable.getForcedInputMask();
            playable.clearForcedInputMask();
        }
        try {
            spriteManager.update(inputHandler);
        } finally {
            for (int i = 0; i < playables.size(); i++) {
                playables.get(i).setForcedInputMask(forcedInputMasks[i]);
            }
        }
    }

    /**
     * BONUS_STAGE per-frame update: overlay tick, glowing-sphere bootstrap, the
     * shared level frame step (with the bonus-stage high-priority enforcement
     * and completion check), and the gumball debug shortcuts. Falls through to
     * the shared post-update tail.
     */
    private void updateBonusStageMode(boolean doFrameStep) {
        // Continue updating title card overlay during bonus stage
        // (EXIT phase: elements slide off screen after exitTitleCard transitioned here)
        TitleCardProvider bonusTcp = getTitleCardProviderLazy();
        if (bonusTcp != null && bonusTcp.isOverlayActive()) {
            bonusTcp.update();
        }

        if (levelManager.getRomZoneId() == Sonic3kZoneIds.ZONE_GLOWING_SPHERE) {
            ensureBonusStageBootstrapObjectPresent(BonusStageType.GLOWING_SPHERE);
        }

        // Bonus stage runs the same level frame steps as LEVEL mode
        boolean freezeForBonusExit = bonusStageTransitionPending;
        if (!freezeForBonusExit) {
            beginGameplayAudioFrameForTick();
            spriteManager.publishHeldInputForLevelEvents(inputHandler);
            // LiveTraceComparator (or any PlaybackFrameObserver) may ask us to
            // skip the gameplay tick on ROM lag frames so the engine and trace
            // stay aligned. Cursor advance still runs via onLevelFrameAdvanced
            // below. Mirrors updateLevelMode's skip gate.
            boolean skipGameplay = playbackDebugManager.shouldSkipCurrentGameplayTick();
            if (!skipGameplay) {
                LevelFrameStep.execute(
                        LevelFrameContext.from(gameplayMode),
                        activePlcLifecycleFrame, PlcLifecyclePhase.ORDINARY_LEVEL,
                        levelManager, camera, () -> {
                    spriteManager.update(inputHandler);
                }, (name, step) -> {
                    profiler.beginSection(name);
                    step.run();
                    profiler.endSection(name);
                });
                playbackDebugManager.onCurrentGameplayTickExecuted();

                // ROM lines 127411-127412: player art_tile priority bit stays HIGH throughout
                // the bonus stage. Must be set AFTER the sprite update (which runs inside
                // LevelFrameStep.execute) because setAir(false) on hurt-landing clears it.
                forcePlayerHighPriorityInBonusStage();

                // Notify coordinator of frame tick
                if (activeBonusStageProvider != null && !activeBonusStageProvider.updateDuringLevelFrame()) {
                    activeBonusStageProvider.onFrameUpdate();
                }

                // Record a rewind keyframe/step for rewind-supported bonus stages
                // (Gumball/Pachinko). Placed before the completion check so the
                // exit frame — which sets bonusStageTransitionPending — is not
                // recorded. The LEVEL path is unchanged; the Slot Machine is
                // excluded via supportsRewind().
                if (isBonusStageRewindable() && TraceSessionLauncher.active() == null) {
                    liveRewindManager.recordExternalFrame(
                            currentGameMode, bonusStageTransitionPending, inputHandler);
                }

                // Check bonus stage completion
                if (activeBonusStageProvider != null && activeBonusStageProvider.isStageComplete()) {
                    exitBonusStage();
                }
            } else if (levelManager.getObjectManager() != null) {
                // ROM v_vbla_byte increments in VBlank even on rows where
                // LevelLoop did not run; keep bonus lag rows in VBla-counter
                // parity, mirroring updateLevelMode's skip branch.
                int vblankTicks = playbackDebugManager.currentSkippedTickVblankAdvanceCount();
                for (int tick = 0; tick < vblankTicks; tick++) {
                    LevelFrameStep.serviceHardwareVBlankOnly(
                            LevelFrameContext.from(gameplayMode), activePlcLifecycleFrame);
                    // V-blank-only row: see the exactly-one-tick-per-serviced-V-blank invariant on ObjectManager.vblaCounter.
                    levelManager.getObjectManager().advanceVblaCounter();
                }
            }
            advanceGameplayAudioFrameForTick(doFrameStep);
            // Fire the BK2-advance callback either way — on both real
            // gameplay ticks and lag-gated skips — so the observer's cursor
            // always matches the BK2 cursor. onLevelFrameAdvanced is a no-op
            // when no playback session is active.
            playbackDebugManager.onLevelFrameAdvanced();
        } else {
            updateNonGameplayAudio(doFrameStep);
            // ROM parity during the bonus-exit hold/fade: after the machine catches
            // the player (Check_PlayerInRange -> loc_61076 sets Restart_level_flag),
            // the ROM keeps running BONUS_STAGE frames while the screen fades and the
            // level reload is staged, and V_int (the recorder's frame source) keeps
            // ticking across them -- so the recorded interior segment includes that
            // ~150-frame tail before its mode_change_bk2_frame. The engine's exit
            // fade freezes gameplay here, but must still advance the shared playback
            // cursor (and the VBla counter) once per frozen frame, exactly as the
            // lag-skip branch above does, or the cursor lands well short of the
            // return segment's offset and the BONUS->LEVEL fall-through feeds the
            // return level a stale frame-0 input. onLevelFrameAdvanced is a no-op
            // when no playback session is active.
            if (levelManager.getObjectManager() != null) {
                LevelFrameStep.serviceHardwareVBlankOnly(
                        LevelFrameContext.from(gameplayMode), activePlcLifecycleFrame);
                // V-blank-only row: see the exactly-one-tick-per-serviced-V-blank invariant on ObjectManager.vblaCounter.
                levelManager.getObjectManager().advanceVblaCounter();
            }
            playbackDebugManager.onLevelFrameAdvanced();
        }

        // Debug: F11 cycles through bucket/priority isolation for the gumball machine
        if (isUnmodifiedDebugKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F11)) {
            com.openggf.game.sonic3k.objects.GumballMachineObjectInstance.cycleDebugFilter();
        }
        // Debug: Insert cycles through gumball child-source isolation without colliding with overlay F-keys.
        if (isUnmodifiedDebugKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_INSERT)) {
            com.openggf.game.sonic3k.objects.GumballMachineObjectInstance.cycleDebugSourceFilter();
        }
    }

    private void updateNonGameplayAudio(boolean doFrameStep) {
        if (audioUpdatedThisStep) {
            return;
        }
        if (shouldAdvanceGameplayAudioForCurrentMode()) {
            beginGameplayAudioFrameForTick();
            advanceGameplayAudioFrameForTick(doFrameStep);
            return;
        }
        audioUpdatedThisStep = true;
    }

    private boolean shouldAdvanceGameplayAudioForCurrentMode() {
        return currentGameMode == GameMode.LEVEL
                || currentGameMode == GameMode.BONUS_STAGE
                || currentGameMode == GameMode.TITLE_CARD;
    }

    private void beginGameplayAudioFrameForTick() {
        gameplayAudioFrame++;
        audioManager.beginGameplayAudioFrame(gameplayAudioFrame);
    }

    private void advanceGameplayAudioFrameForTick(boolean doFrameStep) {
        if (audioUpdatedThisStep) {
            return;
        }
        audioUpdatedThisStep = true;
    }

    boolean handlePlaybackTakeoverBeforePlaybackInputBridge(InputHandler input) {
        if (input == null || !userPaused || !userPauseInputAllowedForCurrentMode()) {
            return false;
        }
        int pauseKey = configService.getInt(SonicConfiguration.PAUSE_KEY);
        if (!input.isKeyPressed(pauseKey) || !userRecordingControls.handlePlaybackTakeoverRequest()) {
            return false;
        }
        userPaused = false;
        updateAudioPauseState();
        return true;
    }

    public void applyScheduledPlaybackInputImmediately() {
        playbackInputBridge.publishImmediately(playbackDebugManager, inputHandler,
                spriteManager);
    }

    private void syncPlaybackInputBridge() {
        playbackInputBridge.sync(playbackDebugManager, currentGameMode,
                inputHandler, spriteManager);
    }

    /**
     * Package-private so {@code com.openggf.TraceSessionLauncher.LiveFixture}
     * can resolve the primary playable sprite by calling the same
     * ActiveGameplayTeamResolver path the rest of GameLoop uses.
     */
    AbstractPlayableSprite getMainPlayableSprite() {
        if (spriteManager == null) {
            return null;
        }
        String mainCode = resolveMainCharacterCode();
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            return playable;
        }
        return null;
    }

    String resolveMainCharacterCode() {
        return ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
    }

    /**
     * Handles the special stage debug key (TAB by default).
     * When in level mode, enters the next special stage.
     * When in special stage mode, exits to results screen (as failure).
     * When in results screen mode, skips back to level.
     */
    private void handleSpecialStageDebugKey() {
        if (currentGameMode == GameMode.LEVEL) {
            enterSpecialStage();
        } else if (currentGameMode == GameMode.SPECIAL_STAGE) {
            enterResultsScreen(false);
        } else if (currentGameMode == GameMode.SPECIAL_STAGE_RESULTS) {
            exitResultsScreen();
        }
    }

    private void detectSpecialStageLiveOnlyShortcutBoundary() {
        if (currentGameMode != GameMode.SPECIAL_STAGE || !debugShortcutsEnabled()) {
            return;
        }

        SpecialStageProvider ssProvider = getActiveSpecialStageProvider();
        SpecialStageDebugCapabilities debugCapabilities =
                SpecialStageDebugCapabilities.orNone(ssProvider.debugCapabilities());
        int leftKey = configService.getInt(SonicConfiguration.LEFT);
        int rightKey = configService.getInt(SonicConfiguration.RIGHT);
        int upKey = configService.getInt(SonicConfiguration.UP);
        int downKey = configService.getInt(SonicConfiguration.DOWN);

        if (isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_KEY))
                || (debugCapabilities.stageSelection()
                && isUnmodifiedDebugKeyPressed(GLFW_KEY_X))
                || (debugCapabilities.layoutSelection()
                && isUnmodifiedDebugKeyPressed(GLFW_KEY_Z))
                || isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_COMPLETE_KEY))
                || isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_FAIL_KEY))
                || (debugCapabilities.spriteViewer()
                && isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_SPRITE_DEBUG_KEY)))
                || (debugCapabilities.planeVisibility()
                && isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_PLANE_DEBUG_KEY)))
                || (debugCapabilities.gameplayMovement()
                && isUnmodifiedDebugKeyPressed(configService.getInt(SonicConfiguration.DEBUG_MODE_KEY)))
                || (debugCapabilities.alignment()
                && isUnmodifiedDebugKeyPressed(GLFW_KEY_F4))
                || (debugCapabilities.lagCompensation()
                && isUnmodifiedDebugKeyPressed(GLFW_KEY_F1))) {
            severSpecialStageRewindForLiveOnlyShortcut();
            return;
        }

        if (debugCapabilities.spriteViewer() && ssProvider.isSpriteDebugMode()
                && ssProvider.getDebugProvider() != null
                && (isUnmodifiedDebugKeyPressed(leftKey)
                || isUnmodifiedDebugKeyPressed(rightKey)
                || isUnmodifiedDebugKeyPressed(upKey)
                || isUnmodifiedDebugKeyPressed(downKey))) {
            severSpecialStageRewindForLiveOnlyShortcut();
            return;
        }

        if (debugCapabilities.alignment() && ssProvider.isAlignmentTestMode()
                && (isUnmodifiedDebugKeyPressed(leftKey)
                || isUnmodifiedDebugKeyPressed(rightKey)
                || isUnmodifiedDebugKeyPressed(upKey)
                || isUnmodifiedDebugKeyPressed(downKey)
                || isUnmodifiedDebugKeyPressed(GLFW_KEY_SPACE))) {
            severSpecialStageRewindForLiveOnlyShortcut();
        }
    }

    private boolean isUnmodifiedDebugKeyPressed(int keyCode) {
        return debugShortcutsEnabled() && inputHandler.isKeyPressedWithoutModifiers(keyCode);
    }

    private boolean debugShortcutsEnabled() {
        return configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
    }

    private boolean userPauseInputAllowedForCurrentMode() {
        return switch (currentGameMode) {
            case LEVEL, TITLE_CARD, SPECIAL_STAGE, SPECIAL_STAGE_RESULTS, BONUS_STAGE -> true;
            default -> false;
        };
    }

    static BonusStageType resolveBonusStageDebugShortcut(InputHandler inputHandler) {
        if (inputHandler == null || !inputHandler.isKeyPressed(GLFW_KEY_B)) {
            return BonusStageType.NONE;
        }

        boolean shift = inputHandler.isShiftDown();
        boolean control = inputHandler.isControlDown();
        boolean alt = inputHandler.isAltDown();
        int activeModifierCount = (shift ? 1 : 0) + (control ? 1 : 0) + (alt ? 1 : 0);
        if (activeModifierCount != 1) {
            return BonusStageType.NONE;
        }
        if (shift) {
            return BonusStageType.GUMBALL;
        }
        if (control) {
            return BonusStageType.GLOWING_SPHERE;
        }
        return BonusStageType.SLOT_MACHINE;
    }

    public static ObjectSpawn resolveBonusStageBootstrapSpawn(BonusStageType type) {
        return BonusStageBootstrapInstaller.resolveSpawn(
                GameServices.currentOrBootstrapGameModule().getBonusStageProvider(), type);
    }

    /**
     * Handles the debug bonus stage shortcut.
     * Shift+B enters Gumball, Ctrl+B enters Glowing Spheres, Alt+B enters Slots.
     * When in bonus stage mode, triggers immediate exit back to level.
     */
    private void handleBonusStageDebugKey(BonusStageType debugType) {
        if (currentGameMode == GameMode.LEVEL) {
            enterBonusStage(debugType);
        } else if (currentGameMode == GameMode.BONUS_STAGE) {
            // Force exit
            if (activeBonusStageProvider != null) {
                activeBonusStageProvider.requestExit();
            }
        }
    }

    /**
     * Debug function: Teleports the player to the furthest right checkpoint in the level.
     * Only works in LEVEL mode (END key is used for special stage completion in special stage mode).
     */
    private void teleportToLastCheckpoint() {
        Level level = levelManager.getCurrentLevel();
        if (level == null) {
            return;
        }

        // Find the furthest right checkpoint (game-agnostic)
        int checkpointId = GameServices.module().getCheckpointObjectId();
        if (checkpointId == 0) {
            LOGGER.info("DEBUG: Current game has no checkpoint object ID configured");
            return;
        }
        ObjectSpawn lastCheckpoint = level.getObjects().stream()
            .filter(spawn -> spawn.objectId() == checkpointId)
            .max(Comparator.comparingInt(ObjectSpawn::x))
            .orElse(null);

        if (lastCheckpoint != null) {
            int checkpointX = lastCheckpoint.x();
            int checkpointY = lastCheckpoint.y();

            String mainCode = resolveMainCharacterCode();
            var sprite = spriteManager.getSprite(mainCode);
                if (sprite instanceof AbstractPlayableSprite player) {
                // Teleport player to checkpoint position
                player.setX((short) checkpointX);
                player.setY((short) checkpointY);
                player.setXSpeed((short) 0);
                player.setYSpeed((short) 0);
                player.setGSpeed((short) 0);
                player.setAir(false);
                player.setRolling(false);

                // Move camera to center on player (prevents pit death from camera mismatch)
                int screenWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
                int screenHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
                int cameraX = checkpointX - (screenWidth / 2);
                int cameraY = checkpointY - (screenHeight / 2);

                // Clamp camera to reasonable range (floor at 0)
                cameraX = Math.max(0, cameraX);
                cameraY = Math.max(0, cameraY);

                camera.setX((short) cameraX);
                camera.setY((short) cameraY);

                LOGGER.info("DEBUG: Teleported to checkpoint at (" + checkpointX + ", " + checkpointY +
                    "), camera at (" + cameraX + ", " + cameraY + ")");
            }
        } else {
            LOGGER.info("DEBUG: No checkpoints found in this level");
        }
    }

    private void logCurrentPreviewCaptureOverride() {
        if (camera == null || levelManager == null) {
            return;
        }
        S1DataSelectImageGenerator.PreviewCapturePoint point =
                S1DataSelectImageGenerator.previewCapturePointFromCamera(camera.getX(), camera.getY());
        LOGGER.info("DEBUG: Preview capture override for zone "
                + levelManager.getRomZoneId()
                + " -> new PreviewCapturePoint("
                + point.centreX()
                + ", "
                + point.centreY()
                + ")");
    }

    /**
     * Debug function: Immediately completes the special stage with emerald
     * collected.
     * Simulates successful completion with the ring requirement met.
     * Press END key during special stage to trigger.
     */
    private void debugCompleteSpecialStageWithEmerald() {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        SpecialStageProvider ssProvider = getActiveSpecialStageProvider();

        // Force emerald collection state
        ssProvider.setEmeraldCollected(true);

        // Get the ring count for this stage from the active provider
        int stageIndex = ssProvider.getCurrentStage();
        int ringRequirement = ssProvider.getDebugCompletionRingCount(stageIndex);

        LOGGER.info("DEBUG: Completing Special Stage " + (stageIndex + 1) +
                " with emerald (forcing " + ringRequirement + " rings)");

        // Enter results screen with emerald collected and simulated ring count
        enterResultsScreenWithDebugRings(true, ringRequirement);
    }

    /**
     * Debug method to fail special stage and go directly to results screen.
     * Press DEL key during special stage to trigger.
     */
    private void debugFailSpecialStage() {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        int stageIndex = getActiveSpecialStageProvider().getCurrentStage();
        int smallRingCount = 15; // A small amount of rings to show ring bonus tally

        LOGGER.info("DEBUG: Failing Special Stage " + (stageIndex + 1) +
                " (with " + smallRingCount + " rings)");

        // Enter results screen without emerald and with small ring count
        enterResultsScreenWithDebugRings(false, smallRingCount);
    }

    /**
     * Enters results screen with a specific ring count (for debug).
     * Uses fade-to-white transition like the normal path.
     */
    private void enterResultsScreenWithDebugRings(boolean emeraldCollected, int ringsCollected) {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        // Don't start another fade if one is already in progress
        FadeManager fadeManager = this.fadeManager;
        if (fadeManager.isActive()) {
            return;
        }

        // Store special stage results for the results screen
        ssRingsCollected = ringsCollected;
        ssEmeraldCollected = emeraldCollected;
        ssStageIndex = getActiveSpecialStageProvider().getCurrentStage();

        // Publish only for providers that retain legacy GameLoop ownership.
        if (emeraldCollected) {
            SpecialStageTransitionSupport.publishRewardIfLoopOwned(
                    getActiveSpecialStageProvider(), gameState,
                    ssStageIndex, activeSpecialStageRewardKind);
        }

        // Start fade-to-white, then show results when complete
        GameLoopPlcLifecycle.startToWhite(resolveGameplayModeContext(), fadeManager, () -> {
            doEnterResultsScreenDebug();
        });

        LOGGER.info("DEBUG: Starting fade-to-white to exit Special Stage");
    }

    /**
     * Actually enters the results screen after fade-to-white completes (debug
     * version).
     */
    private void doEnterResultsScreenDebug() {
        doEnterResultsScreen();
    }

    /**
     * Enters the special stage from level mode.
     * Uses GameStateManager to track which stage to enter (cycles 0-6).
     * <p>
     * The mode change is immediate, because that is what every ROM does: the
     * level-side owner writes the game mode inside its own object tick and
     * runs no fade of its own — S1 {@code Got_ChkSS}
     * ("_incObj/3A Got Through Card.asm":198-201), S2 {@code Obj79_Star}
     * (s2.asm:44875-44877), S3K {@code SSEntryFlash_GoSS} (s3.asm:79628). The
     * white-out that precedes the stage belongs to the special-stage entry
     * itself ({@code GM_Special}'s {@code PaletteWhiteOut}, sonic.asm:3227 /
     * {@code SpecialStage}'s {@code Pal_FadeToWhite}, s2.asm:6546), which
     * {@link SpecialStageEntryPresentationController} owns: it fades the
     * level's frozen last frame to white (the Engine keeps drawing the level
     * while {@link SpecialStageProvider#isEntryFadeToWhiteActive()} holds) and
     * parks white until the provider's reveal boundary. Fading here first
     * would delay the mode change by the whole fade and run the white-out
     * twice.
     */
    public void enterSpecialStage() {
        enterSpecialStage(SpecialStageEntryRequest.ordinary());
    }

    private void enterSpecialStage(SpecialStageEntryRequest request) {
        if (currentGameMode != GameMode.LEVEL) {
            return;
        }

        if (TimeAttackLevelEndRouting.suppressesStageEntry(timeAttackRuntime.isActive())) {
            return;
        }

        FadeManager fadeManager = this.fadeManager;
        boolean screenAlreadyFaded = false;
        boolean fadeFromBlack = false;
        boolean transitionSfxAlreadyPlayed = false;

        if (fadeManager.isActive()) {
            FadeManager.FadeState fadeState = fadeManager.getState();
            if (fadeState == FadeManager.FadeState.HOLD_WHITE) {
                // Screen is held white (S1 big ring -> results -> fade to white path).
                // Take over and enter special stage directly with fade-from-white.
                screenAlreadyFaded = true;
                fadeFromBlack = false;
                transitionSfxAlreadyPlayed = true;
            } else if (fadeState == FadeManager.FadeState.HOLD_BLACK) {
                // Screen is held black. Enter special stage with fade-from-black.
                screenAlreadyFaded = true;
                fadeFromBlack = true;
            } else {
                // Different fade in progress, can't start
                return;
            }
        }

        SpecialStageProvider ssProvider = getCurrentModuleSpecialStageProvider();
        if (!ssProvider.hasSpecialStages()) {
            LOGGER.fine("Current game module has no special stages; ignoring entry request");
            return;
        }

        // Clear power-ups before entering special stage
        String mainCode = resolveMainCharacterCode();
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            playable.clearPowerUps();
        }

        if (shouldPlaySpecialStageEntrySfx(transitionSfxAlreadyPlayed)) {
            playSpecialStageTransitionSfx(ssProvider);
        }

        if (ssProvider.fadesMusicOnEntry()) {
            // S2 SpecialStage queues MusID_FadeOut between its entry SFX and
            // Pal_FadeToWhite (s2.asm:6542-6546). The provider owns whether
            // this shared entry path submits that command.
            audioManager.fadeOutMusic();
        }

        // Determine which stage to enter
        final int stageIndex = SpecialStageTransitionSupport.resolveStageIndex(
                request, ssProvider, gameState);
        activeSpecialStageRewardKind = request.rewardKind();

        // The provider owns the entry white-out; preserve the level's last
        // frame until its reveal boundary rather than delaying the mode change.
        if (screenAlreadyFaded) {
            fadeManager.cancel();
        }
        doEnterSpecialStage(ssProvider, stageIndex, fadeFromBlack);
        LOGGER.info("Entered Special Stage " + (stageIndex + 1)
                + " revealing from " + (fadeFromBlack ? "black" : "white"));
    }

    /**
     * Actually enters the special stage after the transition fade completes.
     * The provider owns the entry fade; the mode boundary is committed immediately.
     *
     * @param fadeFromBlack true if the screen is already black and should fade from black;
     *                      false for the normal fade-from-white reveal
     */
    void doEnterSpecialStage(SpecialStageProvider ssProvider, int stageIndex,
                                     boolean fadeFromBlack) {
        SpecialStageStartupPolicy startupPolicy = defaultSpecialStageStartupPolicy();
        doEnterSpecialStage(ssProvider, stageIndex, fadeFromBlack, startupPolicy,
                activeSpecialStageRewardKind);
        if (startupPolicy == SpecialStageStartupPolicy.TRACE_ACCURATE) {
            // Startup policy and external scheduling admission are independent
            // contracts. Retain the legacy provider notification for callers
            // that implement live pacing; S2 disables its interactive
            // approximation because recorded lag rows are admitted at the
            // timing port.
            ssProvider.setLagCompensation(0);
        }
    }

    /**
     * FAST skips the ROM's masked-interrupt entry load, which is right for
     * ordinary interactive play where nothing supplies those frames. When a
     * {@link PlaybackDebugManager} BK2 session is actively driving playback
     * -- the dev movie-playback hotkeys, or a headless multi-stage trace-run
     * chain drive (see {@code AbstractRunChainTest}) -- the recorded lag rows
     * are admitted by the timing port, so the provider must add nothing or
     * recorded per-frame input would skew against the stage's own ticks. {@code TraceSessionLauncher}'s own dedicated special-stage trace
     * session already calls {@code TRACE_ACCURATE} directly for the same
     * reason (it owns its transition trigger); this generalizes the same
     * ROM-state predicate (a BK2 session is playing) to the organic
     * giant-ring/checkpoint-star entry path used by ordinary gameplay AND by
     * any other BK2-driven session that reaches this transition.
     */
    private SpecialStageStartupPolicy defaultSpecialStageStartupPolicy() {
        return LevelIterationAdmissionController.specialStageStartupPolicy(
                playbackDebugManager.isSessionPlaying());
    }

    void doEnterSpecialStage(SpecialStageProvider ssProvider, int stageIndex,
                             boolean fadeFromBlack, SpecialStageStartupPolicy startupPolicy) {
        doEnterSpecialStage(ssProvider, stageIndex, fadeFromBlack, startupPolicy,
                EmeraldRewardKind.CHAOS_EMERALD);
    }

    private void doEnterSpecialStage(SpecialStageProvider ssProvider, int stageIndex,
                                     boolean fadeFromBlack,
                                     SpecialStageStartupPolicy startupPolicy,
                                     EmeraldRewardKind rewardKind) {
        // Clear the transition freeze flag (now we're in special stage mode)
        specialStageTransitionPending = false;
        activeSpecialStageRewardKind = rewardKind;

        try {
            ssProvider.reset();
            if (activeSpecialStageRewardKind == EmeraldRewardKind.CHAOS_EMERALD) {
                // Preserve the established S1/S2 and ordinary S3K startup seam.
                ssProvider.initializeStage(stageIndex, startupPolicy);
            } else {
                ssProvider.initializeStage(stageIndex, startupPolicy, activeSpecialStageRewardKind);
            }
            activeSpecialStageProvider = ssProvider;
            GameplayModeContext context = resolveGameplayModeContext();
            if (context != null) {
                context.registerSpecialStageAdapter(ssProvider);
            }

            // The level camera survives the ROM's entry fade-to-white, which
            // still shows the level's last frame; revealSpecialStage origins it.
            specialStageEntryPresentation.begin(ssProvider, fadeFromBlack, fadeManager,
                    () -> revealSpecialStage(ssProvider),
                    gameplayMode.plcFrameLifecycle());

            GameMode oldMode = changeGameModeForBoundary(GameMode.SPECIAL_STAGE);

            // Notify listener of mode change
            if (gameModeChangeListener != null) {
                gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
            }

            LOGGER.info("Entered Special Stage " + (stageIndex + 1) + " (H32 mode: 256x224)");
        } catch (IOException e) {
            specialStageEntryPresentation.clear();
            deregisterSpecialStageAdapter();
            activeSpecialStageProvider = NoOpSpecialStageProvider.INSTANCE;
            throw new RuntimeException("Failed to initialize Special Stage " + (stageIndex + 1), e);
        } catch (RuntimeException e) {
            specialStageEntryPresentation.clear();
            deregisterSpecialStageAdapter();
            activeSpecialStageProvider = NoOpSpecialStageProvider.INSTANCE;
            throw e;
        }
    }

    // ==================== Bonus Stage Methods ====================

    /**
     * Enters a bonus stage from level mode.
     * Captures current state, fades to black, loads the bonus zone.
     */
    private void enterBonusStage(BonusStageType type) {
        if (currentGameMode != GameMode.LEVEL || timeAttackRuntime.isActive()) {
            return;
        }

        BonusStageProvider provider = GameServices.module().getBonusStageProvider();
        if (!provider.hasBonusStages()) {
            LOGGER.fine("Current game module has no bonus stages; ignoring entry request");
            return;
        }

        if (type == null || type == BonusStageType.NONE) {
            LOGGER.fine("Bonus stage entry ignored: NONE type");
            return;
        }

        if (fadeManager.isActive()) {
            LOGGER.fine("Bonus stage entry ignored: fade already in progress");
            return;
        }

        var sprite = spriteManager.getSprite(resolveMainCharacterCode());
        AbstractPlayableSprite playable = sprite instanceof AbstractPlayableSprite candidate
                ? candidate
                : null;
        var capture = bonusStageTransitionCoordinator.captureEntry(
                levelManager,
                camera,
                waterSystem,
                playable,
                GameServices.module().getLevelEventProvider(),
                encodeSavedShieldStatus(playable));
        BonusStageState savedState = capture.savedState();
        pendingBonusReturnStarPostMark = capture.pendingStarPostActivationMark();

        // Fade out music
        audioManager.fadeOutMusic();

        bonusStageTransitionPending = true;
        fadeManager.startFadeToBlack(() -> {
            doEnterBonusStage(provider, type, savedState);
        });

        LOGGER.info("Starting fade-to-black for Bonus Stage " + type);
    }

    /**
     * Actually enters the bonus stage after the fade-to-black completes.
     * Loads the bonus zone, then shows the "BONUS STAGE" title card.
     * The actual gameplay setup (HUD, rings, priority, music) is deferred
     * to {@link #applyDeferredBonusStageSetup()} when the title card exits.
     */
    private void doEnterBonusStage(BonusStageProvider provider, BonusStageType type,
                                    BonusStageState savedState) {
        bonusStageTransitionPending = false;
        pendingBonusStageShieldRestore = null;

        // Register provider on the active gameplay mode so objects can access it via GameServices.bonusStage().
        activeBonusStageProvider = provider;
        var gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null) {
            gameplayMode.setActiveBonusStageProvider(provider);
        }

        provider.onEnter(type, savedState);

        if (gameplayMode != null) {
            gameplayMode.registerBonusStageAdapter(provider);
        }

        // Load the bonus zone through the normal level loading path
        int zoneId = provider.getZoneId(type);
        int zone = (zoneId >> 8) & 0xFF;
        int act = zoneId & 0xFF;

        try {
            // Suppress auto-music: bonus music plays later in applyDeferredBonusStageSetup
            levelManager.setSuppressNextMusicChange(true);
            levelManager.loadZoneAndAct(zone, act);
            // Consume the default title card request — we'll show the bonus card instead
            levelManager.consumeTitleCardRequest();
        } catch (IOException e) {
            LOGGER.severe("Failed to load bonus stage zone: " + e.getMessage());
            provider.onExit();
            activeBonusStageProvider = null;
            if (gameplayMode != null) {
                gameplayMode.setActiveBonusStageProvider(null);
                gameplayMode.deregisterBonusStageAdapter();
            }
            changeGameModeForBoundary(GameMode.LEVEL);
            return;
        }
        GameMode oldMode = enterBonusTitleCardAfterLevelLoadBoundary();

        prepareBonusStageForTitleCard(type, savedState);

        // Defer bonus-stage-specific setup to exitTitleCard() when the title card completes
        deferredBonusProvider = provider;
        deferredBonusType = type;
        deferredBonusState = savedState;

        // Initialize the bonus title card
        TitleCardProvider tcp = getTitleCardProviderLazy();
        if (tcp != null) {
            tcp.initializeBonus();
        }

        // Play bonus stage music at title card start (ROM: Restore_LevelMusic during title card)
        int musicId = provider.getMusicId(type);
        if (musicId >= 0) {
            audioManager.playMusic(musicId);
        }

        // Enter TITLE_CARD mode — exitTitleCard will transition to BONUS_STAGE
        applyTitleCardControlLock(true);

        // Cancel the FadeManager overlay — the bonus title card manages its own
        // per-channel background fade (matching ROM Pal_FadeFromBlack). The FadeManager
        // was in HOLD_BLACK from the fade-to-black; clearing it lets the title card's
        // own black rect and text render without being covered by the fade overlay.
        fadeManager.cancel();

        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Entered bonus title card for " + type + " (zone 0x"
                + Integer.toHexString(zoneId) + ")");
    }

    /**
     * Forces all player sprites to VDP high priority during bonus stage.
     * ROM lines 127411-127412: bset #7 on BOTH Player_1 AND Player_2 art_tile.
     * This only restores the tile-priority bit; it does not rewrite the player's
     * display bucket, which remains governed by the normal priority model.
     */
    private void forcePlayerHighPriorityInBonusStage() {
        boolean changed = false;
        for (var sprite : spriteManager.getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                // Preserve object-specific render ordering overrides such as the
                // Pachinko magnet orb's "behind the orb" phase.
                if (playable.getPriorityBucket() == com.openggf.graphics.RenderPriority.PLAYER_DEFAULT
                        && !playable.isHighPriority()) {
                    playable.setHighPriority(true);
                    changed = true;
                }
            }
        }
        if (changed) {
            spriteManager.invalidateRenderBuckets();
        }
    }

    private void prepareBonusStageForTitleCard(BonusStageType type, BonusStageState savedState) {
        if (savedState != null && levelManager.getLevelGamestate() != null) {
            levelManager.getLevelGamestate().pauseTimer();
            levelManager.getLevelGamestate().setRings(savedState.savedRingCount());
        }
        levelManager.setBonusStageHudLayout(true);
        if (savedState != null) {
            applyBonusStageEntryShieldRestore(
                    resolveMainPlayableSprite(), savedState.savedStatusSecondary());
        }
        restorePlayableStateForBonusTitleCard();
        forcePlayerHighPriorityInBonusStage();
        refreshPlayableSpriteArtCaches();
        ensureBonusStageBootstrapObjectPresent(type);
    }

    private void ensureBonusStageBootstrapObjectPresent(BonusStageType type) {
        BonusStageBootstrapInstaller.ensurePresent(
                activeBonusStageProvider, type, levelManager.getObjectManager());
    }

    private void restorePlayableStateForBonusTitleCard() {
        for (var sprite : spriteManager.getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                playable.setHidden(false);
                playable.setObjectControlled(false);
            }
        }
    }

    private void refreshPlayableSpriteArtCaches() {
        for (var sprite : spriteManager.getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable
                    && playable.getSpriteRenderer() != null) {
                playable.getSpriteRenderer().invalidateDplcCache();
            }
        }
    }

    /**
     * Exits the current bonus stage. Fades to black, restores previous zone.
     */
    private void exitBonusStage() {
        if (currentGameMode != GameMode.BONUS_STAGE || activeBonusStageProvider == null) {
            return;
        }

        BonusStageProvider provider = activeBonusStageProvider;
        BonusStageState savedState = provider.getSavedState();

        audioManager.fadeOutMusic();

        bonusStageTransitionPending = true;
        if (shouldStartBonusStageExitFade(provider)) {
            fadeManager.startFadeToBlack(() -> {
                doExitBonusStage(provider, savedState);
            });
            LOGGER.info("Starting fade-to-black to exit Bonus Stage");
        } else {
            doExitBonusStage(provider, savedState);
            LOGGER.info("Exiting Bonus Stage using provider-completed fade-to-black");
        }
    }

    static boolean shouldStartBonusStageExitFade(BonusStageProvider provider) {
        return provider == null || !provider.hasCompletedExitFadeToBlack();
    }

    /**
     * Actually exits the bonus stage after the fade-to-black completes.
     */
    private void doExitBonusStage(BonusStageProvider provider, BonusStageState savedState) {
        bonusStageTransitionPending = false;

        // ROM: on bonus-stage exit the live HUD Ring_count is copied straight into
        // Saved_ring_count (loc_61076: move.w (Ring_count).w,(Saved_ring_count).w,
        // sonic3k.asm:127760; the pachinko/slots exits do the same at 96683/99001),
        // and the returning level reload then restores Ring_count from
        // Saved_ring_count. So the count carried back is the interior's LIVE ring
        // total at exit, NOT the entry snapshot plus a bookkeeping reward. This
        // matters where a machine's per-item ring award differs between the HUD
        // Ring_count and the Saved_ring_count it also bumps: the gumball ring ball
        // adds +10 to the HUD but +20 to Saved_ring_count (loc_6114E, :127845), and
        // that transient +20 is discarded here by the Ring_count->Saved_ring_count
        // copy. Capture the live HUD ring total now, before onExit()/loadZoneAndAct
        // reset it, and restore it on return below (replacing the former
        // savedRingCount + rewards.rings() reconstruction, which double-counted the
        // gumball ball as +20 and returned 79 rings where the ROM returns 69).
        int interiorExitRingCount =
                bonusStageTransitionCoordinator.captureInteriorExitRingCount(levelManager, savedState);

        // Capture rewards BEFORE onExit() in case it resets counters.
        BonusStageProvider.BonusStageRewards rewards = provider.getRewards();

        provider.onExit();
        activeBonusStageProvider = null;
        levelManager.setBonusStageHudLayout(false);

        var gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null) {
            gameplayMode.setActiveBonusStageProvider(null);
            gameplayMode.deregisterBonusStageAdapter();
        }

        if (savedState == null) {
            LOGGER.warning("No saved state for bonus stage exit — returning to zone 0,0");
            try {
                levelManager.loadZoneAndAct(0, 0);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load fallback level", e);
            }
            changeGameModeForBoundary(GameMode.LEVEL);
            fadeManager.startFadeFromBlack(null);
            return;
        }

        // Restore previous zone
        int zone = (savedState.savedZoneAndAct() >> 8) & 0xFF;
        int act = savedState.savedZoneAndAct() & 0xFF;

        try {
            // Signal bonus stage return so onInitLevel() can skip intros (ROM: Level_FromSavedGame)
            levelManager.setBonusStageReturnCheckpointIndex(savedState.savedLastStarPostHit());
            // Suppress auto-music: zone music starts below during the title card
            levelManager.setSuppressNextMusicChange(true);
            bonusStageTransitionCoordinator.prepareReturnLoad(levelManager);
            levelManager.loadZoneAndAct(zone, act);
            // Consume the auto-generated title card request — we initialize it ourselves below
            levelManager.consumeTitleCardRequest();
        } catch (IOException e) {
            throw new RuntimeException("Failed to reload level after bonus stage", e);
        } finally {
            levelManager.clearBonusStageReturn();
        }

        var sprite = spriteManager.getSprite(resolveMainCharacterCode());
        AbstractPlayableSprite playable = sprite instanceof AbstractPlayableSprite candidate
                ? candidate
                : null;
        ShieldType shieldToRestore = playable != null
                ? resolveShieldToRestore(rewards, savedState.savedStatusSecondary())
                : null;
        if (playable != null) {
            pendingBonusStageShieldRestore = shieldToRestore;
        }
        bonusStageTransitionCoordinator.restoreReturnState(
                levelManager,
                camera,
                waterSystem,
                playable,
                GameServices.module().getLevelEventProvider(),
                savedState,
                pendingBonusReturnStarPostMark,
                interiorExitRingCount,
                shieldToRestore,
                rewards,
                gameState::addLife);
        pendingBonusReturnStarPostMark = -1;

        // Initialize zone title card (ROM: Level routine always shows title card on reload)
        int apparentZone = (savedState.savedApparentZoneAndAct() >> 8) & 0xFF;
        int apparentAct = savedState.savedApparentZoneAndAct() & 0xFF;
        TitleCardProvider tcp = getTitleCardProviderLazy();
        if (tcp != null) {
            tcp.initialize(apparentZone, apparentAct);
        }

        // Enter TITLE_CARD mode — exitTitleCard will transition to LEVEL
        postTitleCardDestination = PostTitleCardDestination.LEVEL;
        GameMode oldMode = changeGameModeForBoundary(GameMode.TITLE_CARD);
        applyTitleCardControlLock(true);

        // Play zone music (ROM: Restore_LevelMusic during title card wait)
        levelManager.playCurrentLevelMusic();

        // Fade from black — level + zone title card become visible together
        fadeManager.startFadeFromBlack(null);

        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }
        LOGGER.info("Exiting bonus stage, entering zone title card for zone " + zone + " act " + act);
    }

    static int encodeSavedShieldStatus(AbstractPlayableSprite playable) {
        if (playable == null || !playable.hasShield()) {
            return 0;
        }
        ShieldType shieldType = playable.getShieldType();
        if (shieldType == null) {
            return 0;
        }
        return switch (shieldType) {
            case FIRE -> 1 << STATUS_FIRE_SHIELD_BIT;
            case LIGHTNING -> 1 << STATUS_LIGHTNING_SHIELD_BIT;
            case BUBBLE -> 1 << STATUS_BUBBLE_SHIELD_BIT;
            default -> 0;
        };
    }

    static ShieldType resolveShieldToRestore(BonusStageProvider.BonusStageRewards rewards, int savedStatusSecondary) {
        if (rewards.fireShield()) {
            return ShieldType.FIRE;
        }
        if (rewards.bubbleShield()) {
            return ShieldType.BUBBLE;
        }
        if (rewards.lightningShield()) {
            return ShieldType.LIGHTNING;
        }
        if (rewards.shield()) {
            return ShieldType.BASIC;
        }

        return savedShieldType(savedStatusSecondary);
    }

    /**
     * ROM {@code SpawnLevelMainSprites_SpawnPowerup} {@code loc_6A02}
     * (docs/skdisasm/sonic3k.asm:8294-8323) tests the saved elemental bits in
     * fire -> lightning -> bubble order and re-gives that shield.
     */
    static ShieldType savedShieldType(int savedStatusSecondary) {
        int savedShieldBits = savedStatusSecondary & SAVED_SHIELD_MASK;
        if ((savedShieldBits & (1 << STATUS_FIRE_SHIELD_BIT)) != 0) {
            return ShieldType.FIRE;
        }
        if ((savedShieldBits & (1 << STATUS_LIGHTNING_SHIELD_BIT)) != 0) {
            return ShieldType.LIGHTNING;
        }
        if ((savedShieldBits & (1 << STATUS_BUBBLE_SHIELD_BIT)) != 0) {
            return ShieldType.BUBBLE;
        }
        return null;
    }

    /**
     * ROM {@code SpawnLevelMainSprites_SpawnPowerup}
     * (docs/skdisasm/sonic3k.asm:8264-8290) runs on every level spawn, and the
     * bonus zones are explicitly routed into its restore arm --
     * {@code cmpi.b #$13,(Current_zone).w / beq loc_69E0} and the same for
     * {@code #$14} (:8270-8273). It re-gives Player 1 the shield saved in
     * {@code Saved_status_secondary}, so the ROM's player keeps its elemental
     * shield for the DURATION of the bonus stage, not only after returning to
     * the level.
     *
     * <p>The engine captured the value at entry
     * ({@link #encodeSavedShieldStatus}) but consumed it only on the way out
     * ({@link #resolveShieldToRestore}), leaving the bonus-stage player
     * shieldless. In Pachinko that removes {@code Test_Ring_Collisions}'
     * {@code Status_LtngShield} arm (:18450-18453), which allocates
     * {@code Obj_Attracted_Ring} and pulls a nearby ring into the player.
     */
    public static void applyBonusStageEntryShieldRestore(
            AbstractPlayableSprite playable, int savedStatusSecondary) {
        if (playable == null) {
            return;
        }
        ShieldType shieldType = savedShieldType(savedStatusSecondary);
        if (shieldType != null) {
            playable.giveShield(shieldType);
        }
    }

    AbstractPlayableSprite resolveMainPlayableSprite() {
        String mainCode = resolveMainCharacterCode();
        var sprite = spriteManager.getSprite(mainCode);
        return sprite instanceof AbstractPlayableSprite playable ? playable : null;
    }

    void applyPendingBonusStageShieldRestore(AbstractPlayableSprite playable) {
        if (pendingBonusStageShieldRestore == null || playable == null) {
            return;
        }
        ShieldType shieldType = pendingBonusStageShieldRestore;
        pendingBonusStageShieldRestore = null;
        playable.giveShield(shieldType);
    }

    void setPendingBonusStageShieldRestoreForTest(ShieldType shieldType) {
        pendingBonusStageShieldRestore = shieldType;
    }

    boolean hasPendingBonusStageShieldRestoreForTest() {
        return pendingBonusStageShieldRestore != null;
    }

    /**
     * Enters the results screen after special stage completion/failure.
     * Performs fade-to-white transition before showing results.
     *
     * @param emeraldCollected true if an emerald was collected
     */
    private void enterResultsScreen(boolean emeraldCollected) {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        // Check if the SS manager pre-started a fade (S1: concurrent fade during exit spin)
        FadeManager fadeManager = this.fadeManager;
        boolean fadeAlreadyWhite = (fadeManager.getState() == FadeManager.FadeState.HOLD_WHITE);

        // The pre-started S1 fade carries no completion, so the screen is simply held
        // white and this call owns the transition. The fade-to-white started below also
        // parks in HOLD_WHITE for one frame before FadeManager runs its completion, and
        // a finished stage re-raises this transition every frame -- so that window must
        // keep waiting. Taking it over would enter the results screen twice and open a
        // second native blocking fade while the pending completion still owns the first.
        if (fadeManager.isActive() && (!fadeAlreadyWhite || fadeManager.hasPendingCompletion())) {
            return; // Different fade in progress, or our own completion still pending
        }

        SpecialStageProvider ssProvider = getActiveSpecialStageProvider();

        // Store special stage results for the results screen
        ssRingsCollected = ssProvider.getRingsCollected();
        ssEmeraldCollected = emeraldCollected;
        ssStageIndex = ssProvider.getCurrentStage();

        // Mark emerald as collected now (so it shows in results screen)
        if (emeraldCollected) {
            SpecialStageTransitionSupport.publishRewardIfLoopOwned(
                    ssProvider, gameState, ssStageIndex,
                    activeSpecialStageRewardKind);
        }

        if (fadeAlreadyWhite) {
            // Fade pre-started by SS manager (S1) - screen is already white.
            // Go directly to results; doEnterResultsScreen() calls startFadeFromWhite().
            doEnterResultsScreen();
        } else {
            // Normal path (S2): start fade-to-white, then callback to results.
            // This boundary is raised from updateSpecialStageMode's tail, after
            // specialStageEntryPresentation.update has already spent this
            // iteration's FadeManager tick, so the ROM's first
            // Pal_FadeToWhite WaitForVint is already the NEXT iteration's --
            // deferring again would give the routine 23 V-ints instead of the
            // 22 its dbf loop runs (docs/s2disasm/s2.asm:3571-3582) and push
            // the results loop's first VintID_Level (s2.asm:6797-6803, 781,
            // 1770) one row late.
            //
            // Note that V-int does NOT retire the stage's last player DPLC
            // pair, as an earlier version of this comment claimed. The results
            // setup block zeroes the queue head first -- unguarded, so the
            // shipped ROM runs it -- at s2.asm:6759-6760, and ProcessDMAQueue
            // stops on a zero first word (s2.asm:1772-1790). The ROM DISCARDS
            // that pair; it never transfers. Vint_Fade (s2.asm:1068-1070) does
            // not call ProcessDMAQueue either, so nothing drains between the
            // mode change and the clear.
            GameLoopPlcLifecycle.startToWhiteAfterFrameFadeTick(
                    resolveGameplayModeContext(), fadeManager, () -> {
                        doEnterResultsScreen();
                    });
        }

        LOGGER.info("Starting fade-to-white to exit Special Stage");
    }

    /**
     * The special-stage identity a recorded run segment is cut on: the value
     * {@code Current_Special_Stage} held when the ROM entered
     * {@code GameModeID_SpecialStage}, held constant for the whole recorded
     * segment.
     *
     * <p>The ROM's byte is not constant across that segment. A won stage
     * increments it inside the stage itself, in the same block that raises
     * {@code SS_Check_Rings_flag} ({@code addi_.b #1,(Current_Special_Stage).w},
     * docs/s2disasm/s2.asm:72467-72477), and it is zeroed only on a later entry
     * once it reaches 7 (s2.asm:6538-6540). The run recorder samples it once, at
     * the first {@code GameModeID_SpecialStage} frame
     * ({@code S2RunCaptureRunner.StartSsSegment}), so a segment's recorded
     * {@code special_stage_index} is that pre-increment number for the whole
     * segment, results tail included.
     *
     * <p>The engine splits that one ROM mode into {@code SPECIAL_STAGE} plus
     * {@code SPECIAL_STAGE_RESULTS}, and entering results deinitialises the
     * stage manager ({@code SpecialStageProvider#resetForResults}), dropping its
     * scratch stage index back to 0. Reading the live provider during the
     * results tail therefore reports 0 for every stage -- which only happened to
     * match the recorded identity for stage 0. The results phase belongs to the
     * stage captured at the exit boundary, so report that instead.
     */
    public Integer recordedSpecialStageIdentity(GameMode observedMode) {
        if (observedMode == GameMode.SPECIAL_STAGE_RESULTS) {
            return ssStageIndex;
        }
        if (observedMode != GameMode.SPECIAL_STAGE) {
            return null;
        }
        SpecialStageProvider provider = getActiveSpecialStageProvider();
        return provider != null ? provider.getCurrentStage() : null;
    }

    /**
     * Actually enters the results screen after fade-to-white completes.
     */
    void doEnterResultsScreen() {
        // Reset special stage provider
        SpecialStageProvider ssProvider = getActiveSpecialStageProvider();
        ssProvider.resetForResults();

        // Transition to results mode
        GameMode oldMode = changeGameModeForBoundary(GameMode.SPECIAL_STAGE_RESULTS);
        resultsFrameCounter = 0;

        // Create results screen with current emerald count via provider
        int totalEmeralds = gameState.getEmeraldCount();
        resultsScreen = ssProvider.createResultsScreen(
                ssRingsCollected, ssEmeraldCollected, ssStageIndex, totalEmeralds);

        if (resultsScreen == null) {
            resultsScreen = NoOpResultsScreen.INSTANCE;
        }

        playSpecialStageResultsMusic(ssProvider);

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        // Neither game fades back in here. After the stage's whiteout both ROMs
        // rebuild the screen and then write the results palette straight to the
        // active palette in one go -- S2 `moveq #PalID_Result,d0 / bsr.w
        // PalLoad_Now` (docs/s2disasm/s2.asm:6762-6763) and S1
        // `moveq #palid_SSResult,d0 / bsr.w PalLoad` (docs/s1disasm/sonic.asm:3382-3383)
        // -- so the results screen appears at full intensity on the very first
        // V-blank of its tally loop (S2 `VintID_Level` at s2.asm:6797-6800, S1
        // `SS_NormalExit` at sonic.asm:3403-3406). A fade-from-white here would
        // instead hold PALETTE_FADE for its whole duration and push the first
        // results-owned V-blank that many rows late. (That V-blank's
        // ProcessDMAQueue retires nothing: s2.asm:6759-6760 zeroes the queue
        // head first, unguarded, so the ROM discards the stage's last player
        // DPLC pair rather than transferring it.)
        fadeManager.clearOverlayForImmediatePaletteLoad();

        LOGGER.info("Entered Special Stage Results Screen (rings=" + ssRingsCollected +
                ", emerald=" + ssEmeraldCollected + ")");
    }

    /**
     * Exits the results screen and shows the title card before returning to the
     * level.
     * Performs fade-to-black transition before showing title card.
     */
    private void exitResultsScreen() {
        if (currentGameMode != GameMode.SPECIAL_STAGE_RESULTS) {
            return;
        }

        // Don't start another fade if one is already in progress
        FadeManager fadeManager = this.fadeManager;
        if (fadeManager.isActive()) {
            return;
        }

        // Play the special stage exit sound (same as entry sound)
        playSpecialStageTransitionSfx(getActiveSpecialStageProvider());

        // Start fade-to-white, then show title card when complete. The
        // completion only latches the exit: the fade update that completes it
        // runs at the start of the results screen's last whiteout frame, and
        // the exit body (the returning level's load) must not run until the
        // iteration after that frame's V-int sample.
        GameLoopPlcLifecycle.startToWhite(resolveGameplayModeContext(), fadeManager, () -> {
            resultsExitFadeCompleted = true;
        });

        LOGGER.info("Starting fade-to-white to exit Results Screen");
    }

    /**
     * Actually exits the results screen after fade-to-black completes.
     */
    private void doExitResultsScreen() {
        // Clean up results screen
        resultsScreen = null;
        deregisterSpecialStageAdapter();
        activeSpecialStageProvider = NoOpSpecialStageProvider.INSTANCE;

        // S3K's special-stage clear sets bit 7 in Last_star_post_hit before
        // the return reload, enabling Load_Starpost_Settings' Saved2 branch.
        levelManager.setLastStarPostHit();

        if (levelManager.getCurrentLevel() == null) {
            // No level was loaded (special stage launched from level select).
            // Load the starting level; loadCurrentLevel() will request its own title card.
            GameMode oldMode = changeGameModeForBoundary(GameMode.LEVEL);
            try {
                levelManager.loadZoneAndAct(0, 0);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load starting level after special stage", e);
            }

            if (gameModeChangeListener != null) {
                gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
            }

            GameLoopPlcLifecycle.startFromWhite(resolveGameplayModeContext(), fadeManager, null);

            LOGGER.info("Exited Results Screen, loaded starting level (no previous level)");
            return;
        }

        // ROM HPZ results rebuild the sanctuary as the results-screen backdrop only.
        // The pedestal that launched the stage (loc_90926) sets
        // Special_bonus_entry_flag = 1, so Load_Starpost_Settings takes loc_2D2C2 and
        // restores Saved2_* — the player resumes in the zone the Big Ring was collected
        // in, not in the sanctuary. The sanctuary is re-entered only through another
        // Big Ring.
        //
        // Otherwise (and for every other game) the ROM runs the full Level: function,
        // which clears all object RAM (bridges/stateful objects reset to initial state)
        // and reloads level data:
        // - S1: zone/act may have been advanced before SS entry
        // - S2: same zone/act, objects reset, rings cleared (Obj79_LoadData clr.w Ring_count)
        // - S3K: same zone/act, objects reset, rings restored from Saved2_ring_count
        boolean sanctuaryReturn;
        // The star-post activation mark is the engine's model of the ROM's
        // "last checkpoint reached" byte, and that byte is NOT part of what the
        // return's level reload clears.
        //
        // S3K: Load_Starpost_Settings' giant-ring/bonus branch loc_2D2C2
        // (docs/skdisasm/sonic3k.asm:61793-61819) restores the whole Saved2_*
        // block but deliberately writes no Last_star_post_hit, so the value the
        // special-stage exit left there survives -- the exit's
        // "ori.b #$80,(Last_star_post_hit).w" (:12121, :12676) over the subtype
        // sub_2D164 stored when the post was touched (:61704), with LevelSizeLoad's
        // "andi.b #$7F,(Last_star_post_hit).w" (:7881) stripping the marker bit
        // before LevelLoop. sub_2D028 then reads it as already-hit for every post
        // whose subtype is at or below it ("cmp.b d2,d1 / bhs.w loc_2D0EA",
        // :61606-61610), which is what stops the post the player entered from
        // re-arming its 20-ring bonus stars (:61638-61641) on the return.
        //
        // S1 is the same shape: v_lastlamp is cleared only by the end-of-act card
        // (docs/s1disasm/_incObj/3A Got Through Card.asm:198), a death
        // (_incObj/01 Sonic.asm:1116) and a new game (sonic.asm:1968) -- never by a
        // level reload -- and Lamp_Blue gates on the identical "last >= subtype"
        // comparison (_incObj/79 Lamppost.asm:57-62).
        //
        // The bonus-stage return already carries this across its own reload
        // (BonusStageTransitionCoordinator.restoreReturnState); the
        // special-stage return is the second implementation of the same contract
        // and was missing it.
        int returnStarPostActivationMark = -1;
        RespawnState checkpointBeforeReturnReload = levelManager.getCheckpointState();
        if (checkpointBeforeReturnReload != null) {
            returnStarPostActivationMark =
                    checkpointBeforeReturnReload.getStarPostActivationMark();
        }
        // The reload's title card is presented below by this method, so the
        // load must request it (keeping the queued initial PLCs live for the
        // card's locked loop) rather than model an omitted presentation.
        levelManager.setResultsReturnCardOwnedByCaller(true);
        try {
            sanctuaryReturn = SpecialStageTransitionSupport.loadSpecialStageReturnLevel(
                    levelManager, activeSpecialStageRewardKind, ssStageIndex,
                    ssEmeraldCollected);
        } finally {
            levelManager.setResultsReturnCardOwnedByCaller(false);
        }
        if (returnStarPostActivationMark >= 0) {
            RespawnState checkpointAfterReturnReload = levelManager.getCheckpointState();
            if (checkpointAfterReturnReload != null) {
                checkpointAfterReturnReload.restoreStarPostActivationMark(
                        returnStarPostActivationMark);
            }
        }

        // Consume any pending title card request to prevent double title card
        // (we're manually entering the title card below)
        levelManager.consumeTitleCardRequest();

        if (sanctuaryReturn) {
            // ROM GameMode_SpecialStageResults installs the HPZ hub directly;
            // there is no intervening HPZ title card. The controller consumes
            // the typed return context and runs success choreography or exposes
            // the failed pedestal immediately.
            returningFromSpecialStage = false;
            GameMode oldMode = changeGameModeForBoundary(GameMode.LEVEL);
            if (gameModeChangeListener != null) {
                gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
            }
            fadeManager.startFadeFromWhite(null);
            requestSessionSave(SaveReason.SPECIAL_STAGE_SAVE);
            LOGGER.info("Exited Results Screen directly into the HPZ sanctuary hub"
                    + " (stage=" + ssStageIndex + ", success=" + ssEmeraldCollected + ")");
            return;
        }

        // Set flag so exitTitleCard knows to restore checkpoint state
        returningFromSpecialStage = true;

        // Enter title card mode for the APPARENT zone/act. Obj_TitleCardInit
        // never reads Current_zone_and_act: the act-number art is chosen by
        // "tst.b (Apparent_act).w / bne" -- Num2 when non-zero, Num1 when zero
        // (sonic3k.asm:62131-62141) -- and the zone art by
        // "move.b (Apparent_zone_and_act).w,d0" (sonic3k.asm:62155). The two
        // acts diverge across an S3K seamless act transition, because
        // Current_act advances inside the act-1 background-event dispatch while
        // Apparent_act is only raised later, by the end-sign results object's
        // "move.b #1,(Apparent_act).w" at loc_2DD06 (sonic3k.asm:62714). A
        // giant-ring special stage entered in that window returns to a card the
        // ROM still draws with the act-1 digit. The bonus-stage return already
        // reads Saved_apparent_zone_and_act for the same reason.
        int zoneIndex = levelManager.getCurrentZone();
        int actIndex = levelManager.getApparentAct();
        enterTitleCardFromResults(zoneIndex, actIndex);

        // Reveal the title card by fading from white (the screen is currently white
        // from exitResultsScreen()'s fade-to-white). Without this, the white overlay
        // persists indefinitely because completeFade() sees no new fade was started.
        GameLoopPlcLifecycle.startFromWhite(resolveGameplayModeContext(), fadeManager, null);
        requestSessionSave(SaveReason.SPECIAL_STAGE_SAVE);

        LOGGER.info("Exited Results Screen, entering Title Card for zone " + zoneIndex + " act " + actIndex);
    }

    /**
     * Enters the title card from the results screen context.
     * Similar to enterTitleCard but allows entry from SPECIAL_STAGE_RESULTS mode.
     * Restores the player to their checkpoint position before showing title card.
     */
    private void enterTitleCardFromResults(int zoneIndex, int actIndex) {
        GameMode oldMode = changeGameModeForBoundary(GameMode.TITLE_CARD);

        // Restore player to checkpoint state BEFORE title card starts
        // This prevents the player from falling/dying during the title card animation
        String mainCode = resolveMainCharacterCode();
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            RespawnState checkpointState = levelManager.getCheckpointState();

            if (levelManager.hasBigRingReturn()
                    && (levelManager.isSanctuaryOriginRestorePending(zoneIndex, actIndex)
                        || levelManager.sanctuaryReentryStage().isEmpty())) {
                // S3K big ring path: restore all Saved2_* state
                restoreBigRingReturn(playable);
            } else if (checkpointState != null && checkpointState.isActive()) {
                // S2 checkpoint star path: restore to checkpoint (ROM: Saved_* variables)
                checkpointState.restoreToPlayer(playable, camera);
            } else {
                // No checkpoint - camera will follow player at level start position
                camera.updatePosition(true);
            }

            // Freeze all movement during title card
            playable.setXSpeed((short) 0);
            playable.setYSpeed((short) 0);
            playable.setGSpeed((short) 0);
            playable.setAir(false);
            // Clear death/hurt state to prevent dying during title card
            playable.setDead(false);
            playable.setHurt(false);
            playable.setDeathCountdown(0);
            playable.setInvulnerableFrames(0);
            playable.setRolling(false);
            // Restore visibility and controls (cleared by S3K big ring entry sequence)
            playable.setHidden(false);
            playable.setObjectControlled(false);
            // Unfreeze camera (frozen by S3K big ring entry sequence)
            camera.setFrozen(false);

            // ROM SpawnLevelMainSprites_SpawnPlayers, both arms
            // (docs/skdisasm/sonic3k.asm:8367 sidekick, :8388 Tails-as-Player_1).
            SpecialStageReturnSpawn.applyMainCharacterSpawnOffset(playable, configService);
            SpecialStageReturnSpawn.respawnSidekicks(
                    playable, spriteManager.getSidekicks(), levelManager);
        }
        InLevelTitleCardCoordinator.prepareResultsTransition(
                sprite, this::applyTitleCardControlLock, GameServices::module, spriteManager, levelManager);

        // Initialize the title card manager
        if (getTitleCardProviderLazy() != null) {
            getTitleCardProviderLazy().initialize(zoneIndex, actIndex);
        }

        // Start zone music immediately when title card begins (not at the end)
        levelManager.playCurrentLevelMusic();

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }
    }

    private void restoreBigRingReturn(AbstractPlayableSprite playable) {
        SpecialStageTransitionSupport.restoreBigRingReturn(levelManager.getBigRingReturn(),
                playable, camera, levelManager, waterSystem);
    }

    /**
     * Enters the title card for the current zone/act.
     * Called when a level first loads or after player respawns.
     *
     * @param zoneIndex Zone index (0-10)
     * @param actIndex  Act index (0-2)
     */
    public void enterTitleCard(int zoneIndex, int actIndex) {
        if (currentGameMode != GameMode.LEVEL) {
            return;
        }

        GameMode oldMode = changeGameModeForBoundary(GameMode.TITLE_CARD);

        // Freeze the player during title card - full state reset
        String mainCode = resolveMainCharacterCode();
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            // Freeze all movement
            playable.setXSpeed((short) 0);
            playable.setYSpeed((short) 0);
            playable.setGSpeed((short) 0);
            playable.setAir(false);
            // Clear death/hurt state to prevent dying during title card
            playable.setDead(false);
            playable.setHurt(false);
            playable.setDeathCountdown(0);
            playable.setInvulnerableFrames(0);
            playable.setRolling(false);
        }
        applyTitleCardControlLock(true);

        // Initialize the title card manager
        if (getTitleCardProviderLazy() != null) {
            getTitleCardProviderLazy().initialize(zoneIndex, actIndex);
        }

        // Snap camera to player position immediately so it's correct from the start
        // Normal updates during the title card will keep it settled
        camera.updatePosition(true);

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Entered Title Card for zone " + zoneIndex + " act " + actIndex);
    }

    void startPendingInLevelTitleCard() {
        InLevelTitleCardCoordinator.startIfRequested(
                levelManager, getTitleCardProviderLazy(),
                GameServices.gameState().isEndOfLevelActive(), this::applyTitleCardControlLock);
    }

    /**
     * Applies (or releases) the control lock on all playable sprites for the
     * duration of the title card.
     *
     * <p>ROM parity:
     * <ul>
     *   <li>S1 sets {@code Control_Locked} during the title-card setup so
     *       Sonic_ControlsLock skips input parsing.</li>
     *   <li>S2 sets {@code Control_Locked}/{@code Control_Locked_P2} at
     *       s2.asm:4950-4951 immediately after the {@code Level_TtlCard}
     *       wait loop, before the player is allowed to move.</li>
     *   <li>S3K sets {@code Ctrl_1_locked}/{@code Ctrl_2_locked} at
     *       sonic3k.asm:7774-7775 immediately after the title-card wait
     *       loop, clearing them when the level proper starts.</li>
     * </ul>
     *
     * <p>Both the main player and any CPU sidekicks are toggled so the
     * canonical {@code LevelFrameStep} sprite update path reads no input
     * while the title card is on screen.
     */
    void applyTitleCardControlLock(boolean locked) {
        if (spriteManager == null) {
            return;
        }
        for (var sprite : spriteManager.getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                playable.setControlLocked(locked);
            }
        }
    }

    /**
     * Exits the title card and returns to level mode.
     * Note: We do NOT reset the title card manager here because the overlay
     * (TEXT_WAIT and TEXT_EXIT phases) still needs to run. The title card
     * will reset itself when it reaches COMPLETE state, or when a new
     * title card is initialized.
     */
    private void exitTitleCard() {
        if (currentGameMode != GameMode.TITLE_CARD) {
            return;
        }

        GameMode oldMode = currentGameMode;
        applyTitleCardControlLock(false);

        if (postTitleCardDestination == PostTitleCardDestination.BONUS_STAGE) {
            // Transitioning to bonus stage after "BONUS STAGE" title card
            postTitleCardDestination = PostTitleCardDestination.LEVEL;
            changeGameModeForBoundary(GameMode.BONUS_STAGE);

            // Apply deferred bonus stage setup
            applyDeferredBonusStageSetup();

            // Re-arm the playback forced-input bridge now that the mode is
            // BONUS_STAGE. This exit runs mid-step and falls through to
            // BONUS_STAGE gameplay processing in the SAME loop.step(): that
            // fall-through frame IS the interior's first gameplay tick. The
            // step-top syncPlaybackInputBridge already ran while the mode was
            // still TITLE_CARD, which PlaybackDebugManager.isDriving() does not
            // drive (only LEVEL/BONUS_STAGE), so the player's first interior tick
            // would otherwise run with a stale/neutral forced mask -- dropping the
            // recorded frame-0 controller input the ROM's first bonus frame reads
            // (e.g. the gumball's frame-0 left press that produces the single
            // grounded ground-move before the player falls into the machine).
            // Re-syncing here, after control is released and the mode is
            // BONUS_STAGE, applies the current forced-input mask so the
            // fall-through tick reads the recorded input. Bonus-entry only; LEVEL
            // title-card exits are unaffected.
            syncPlaybackInputBridge();

            LOGGER.info("Exited bonus title card, entering BONUS_STAGE mode");
        } else if (returningFromSpecialStage) {
            changeGameModeForBoundary(GameMode.LEVEL);
            returningFromSpecialStage = false;
            LOGGER.info("Exited Title Card, returned to level from special stage at checkpoint");
        } else {
            changeGameModeForBoundary(GameMode.LEVEL);
            applyPendingBonusStageShieldRestore(resolveMainPlayableSprite());

            // Re-apply zone-specific player state (airborne intros like HCZ1, MGZ1)
            LevelEventProvider levelEvents = GameServices.module().getLevelEventProvider();
            if (levelEvents instanceof com.openggf.game.sonic3k.Sonic3kLevelEventManager s3kEvents) {
                s3kEvents.applyZonePlayerStateAfterTitleCard();
            }
            LOGGER.info("Exited Title Card, starting level");
        }

        // A title-card release falls through into the destination gameplay in
        // this same loop.step(). The step-top bridge sync ran while the mode was
        // still TITLE_CARD (which is intentionally not driven), so refresh the
        // forced input now for LEVEL as well as for BONUS_STAGE above. Otherwise
        // a death/restart consumes one stale pre-reload input on the first native
        // Level_MainLoop frame before a trace comparator can observe it.
        if (currentGameMode == GameMode.LEVEL) {
            TraceSessionLauncher.admitRunDestinationBeforeProductionIfActive(currentGameMode);
            syncPlaybackInputBridge();
        }

        if (currentGameMode == GameMode.LEVEL) {
            titleCardReleasedIntoLevelThisIteration = true;
        }

        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }
    }

    /**
     * Applies bonus stage setup that was deferred until the title card completed.
     * Mirrors the setup previously done inline in doEnterBonusStage.
     */
    private void applyDeferredBonusStageSetup() {
        BonusStageProvider provider = deferredBonusProvider;

        // Clear deferred state
        deferredBonusProvider = null;
        deferredBonusType = null;
        deferredBonusState = null;

        if (provider == null) {
            LOGGER.warning("No deferred bonus stage provider — skipping setup");
            return;
        }

        provider.onDeferredSetupComplete();

        // Music already started in doEnterBonusStage (at title card init).
        // Background fade is handled by the title card's own per-channel rect fade
        // during DISPLAY (synchronized with the 22-frame hold, matching ROM).
    }

    // ==================== Master Title Screen Methods ====================

    /**
     * Pre-flight check for {@link #launchGameByEntry}. Returns false when
     * a master-title fade is already in flight (launchGameByEntry would
     * throw in that case). Package-private so
     * {@link TraceSessionLauncher} can refuse a launch *before* mutating
     * the configuration service.
     */
    boolean canLaunchGameNow() {
        return !resolveFadeManager().isActive();
    }

    void launchGameByEntry(MasterTitleScreen.GameEntry entry, Runnable afterGameLoaded) {
        MasterTitleScreen masterScreen = currentMasterTitleScreen();
        if (masterScreen == null) {
            throw new IllegalStateException("No master title screen available");
        }
        if (resolveFadeManager().isActive()) {
            throw new IllegalStateException(
                    "Cannot launch game: a master-title fade is already in flight");
        }
        masterTitleLaunchCoordinator.setPendingLaunchCallback(afterGameLoaded);
        try {
            masterScreen.selectEntry(entry);
            exitMasterTitleScreen(masterScreen);
        } catch (RuntimeException e) {
            // The fade callback will never fire, so drop the staged launch callback.
            masterTitleLaunchCoordinator.clearPendingLaunchCallback();
            throw e;
        }
    }

    private void runAfterStepMasterTitleLaunchCallbackIfPresent() {
        masterTitleLaunchCoordinator.runAfterStepLaunchCallbackIfPresent();
    }

    void setReturnToMasterTitleHandler(Runnable returnToMasterTitleHandler) {
        masterTitleLaunchCoordinator.setReturnToMasterTitleHandler(returnToMasterTitleHandler);
    }

    void setMasterTitleLaunchFailureHandler(Runnable masterTitleLaunchFailureHandler) {
        masterTitleLaunchCoordinator.setLaunchFailureHandler(masterTitleLaunchFailureHandler);
    }

    private MasterTitleScreen currentMasterTitleScreen() {
        MasterTitleScreen masterScreen = masterTitleScreenSupplier != null
                ? masterTitleScreenSupplier.get() : null;
        installUserRecordingPlaybackStarter(masterScreen);
        installTimeAttackLaunchHandler(masterScreen);
        installTimeAttackNetworkHandler(masterScreen);
        return masterScreen;
    }

    /**
     * Time-attack launches are deferred out of MasterTitleScreen.update: the
     * launch tears the screen down (Engine.launchTimeAttack -> cleanup()) and
     * must not run re-entrantly while the screen is still updating itself.
     */
    private TimeAttackLaunchRequest pendingTimeAttackLaunch;

    /**
     * A finished/abandoned time attack attempt returns to the master title
     * screen instead of advancing into the next act/zone/credits (see the
     * consume sites in {@link #updateLevelMode}). This flag requests that,
     * once the title screen reaches {@code ACTIVE}, it auto-reopen the time
     * attack menu so the player lands back where they started rather than on
     * the bare title screen. Cleared once {@link MasterTitleScreen#tryOpenTimeAttackMenu()}
     * succeeds, or defensively if the game mode ever leaves
     * {@code MASTER_TITLE_SCREEN} while still pending.
     */
    private boolean pendingReopenTimeAttackMenu;

    /** Tracks the previous frame's mode so the defensive clear above only fires on an
     * actual MASTER_TITLE_SCREEN -&gt; other-mode transition, not merely "not currently
     * on the title screen" (which is also true for every frame of the return fade
     * itself, before the mode has switched). */
    private boolean wasMasterTitleScreenActiveLastFrame;

    private void installTimeAttackLaunchHandler(MasterTitleScreen masterScreen) {
        if (masterScreen != null) {
            masterScreen.setTimeAttackLaunchStarter(request -> pendingTimeAttackLaunch = request);
        }
    }

    private void installTimeAttackNetworkHandler(MasterTitleScreen masterScreen) {
        if (masterScreen != null) {
            masterScreen.setTimeAttackNetworkStarter(timeAttackNetworkHandler);
        }
    }

    private void installUserRecordingPlaybackStarter(MasterTitleScreen masterScreen) {
        if (masterScreen != null) {
            masterScreen.setUserRecordingPlaybackStarter(userRecordingPlaybackStarter);
        }
    }

    /**
     * Tear down the current trace session and hand control back to the
     * Engine so it can recreate the master title screen and reset
     * gameplay state. Called by {@link TraceSessionLauncher#teardown()}.
     */
    void returnToMasterTitle() {
        escapeToMasterTitleController.reset();
        levelIterationAdmission.reset();
        userRecordingSessionLauncher.stopActiveRecording(UserRecordingStopReason.LEVEL_ENDED);
        userRecordingSessionLauncher.endPlaybackSession();
        levelIterationAdmission.resetLastAppliedPlaybackFrame();
        // Every route out of a time-attack session funnels through this method
        // (escape-to-master-title hold and TraceSessionLauncher teardown), so
        // this is the single place isActive() must be cleared -- otherwise it
        // stays sticky and the frame hooks above would keep firing during the
        // next, unrelated gameplay session.
        timeAttackRuntime.deactivate();
        masterTitleLaunchCoordinator.returnToMasterTitle();
    }

    private void startEscapeToMasterTitleTransition() {
        FadeManager manager = resolveFadeManager();
        if (manager.isActive()) {
            return;
        }
        audioManager.fadeOutMusic();
        manager.startFadeToBlack(this::returnToMasterTitle);
    }

    /**
     * A finished/abandoned time attack attempt does not auto-advance into the
     * next act/zone/credits sequence like normal play does (see the consume
     * sites in {@link #updateLevelMode}). Instead it fades to black and hands
     * off to the same {@link #returnToMasterTitle()} choreography as the
     * escape-to-title hold, then arms {@link #pendingReopenTimeAttackMenu} so
     * the title screen reopens the time attack menu once it becomes active.
     */
    private void startTimeAttackReturnToMenuFade() {
        FadeManager manager = resolveFadeManager();
        if (manager.isActive()) {
            return;
        }
        pendingReopenTimeAttackMenu = multiplayerRaceCoordinator == null;
        audioManager.fadeOutMusic();
        manager.startFadeToBlack(this::returnToMasterTitle);
    }

    private void startEscapeApplicationExitTransition() {
        FadeManager manager = resolveFadeManager();
        if (manager.isActive()) {
            return;
        }
        audioManager.fadeOutMusic();
        manager.startFadeToBlack(applicationExitHandler);
    }

    /**
     * Exits the master title screen after the user selects a game.
     * Performs a fade-to-black, then initializes the selected game (Phase 2),
     * and transitions to the game-specific title screen.
     */
    private void exitMasterTitleScreen(MasterTitleScreen masterScreen) {
        FadeManager fadeManager = resolveFadeManager();
        if (fadeManager.isActive()) {
            return;
        }

        MasterTitleEntry.Launch launch = masterScreen.getSelectedLaunch();
        String selectedGameId = masterScreen.getSelectedGameId();
        boolean programmaticSelection = masterScreen.isProgrammaticSelection();

        fadeManager.startFadeToBlack(() -> {
            if (launch != null && launch.entry() instanceof MasterTitleEntry.Standalone)
                masterTitleExitCoordinator.exitStandalone(launch);
            else masterTitleExitCoordinator.exitStock(selectedGameId, programmaticSelection);
        });

        LOGGER.info("Starting fade-to-black for master title screen exit (game: " + selectedGameId + ")");
    }

    /**
     * Actually performs the master title screen exit after fade-to-black completes.
     */
    private void doExitMasterTitleScreen(String selectedGameId, boolean programmaticSelection) { masterTitleExitCoordinator.exitStock(selectedGameId, programmaticSelection); }

    private void doExitStandaloneMasterTitle(MasterTitleEntry.Launch launch) { masterTitleExitCoordinator.exitStandalone(launch); }

    private boolean hasReadyGameplayRuntime() {
        if (gameplayMode != null && gameplayMode.isGameplayRuntimeReady()) {
            return true;
        }
        GameplayModeContext currentGameplayMode = SessionManager.getCurrentGameplayMode();
        return currentGameplayMode != null && currentGameplayMode.isGameplayRuntimeReady();
    }

    public void restartFromRecordingLaunchContext(RecordingLaunchContext context) {
        Objects.requireNonNull(context, "context");

        configService.clearSessionOverrides();
        configService.setSessionOverride(SonicConfiguration.DEFAULT_ROM, context.gameId());
        configService.setSessionOverride(SonicConfiguration.DEBUG_VIEW_ENABLED, context.debugToolsEnabled());
        configService.setSessionOverride(SonicConfiguration.MAIN_CHARACTER_CODE, context.mainCharacter());
        configService.setSessionOverride(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                String.join(",", context.sidekickCharacters()));
        configService.setSessionOverride(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
        configService.resolveDisplayAspect();

        try {
            romManager.close();
            Rom rom = romManager.getRom();
            GameModule rootModule = engineServices.romDetection()
                    .detectAndCreateModule(rom)
                    .orElseThrow(() -> new IOException(
                            "ROM not recognized for recording launch context: " + context.gameId()));

            GameModule module = DeterministicPatchLaunches.forRecording(
                    moduleResolutionService, rootModule, context);
            audioManager.setAudioProfile(module.getAudioProfile());
            audioManager.setRom(rom);
            resetModuleScopedProviders();

            GameplayModeContext freshGameplayMode = gameplayTeamBootstrapContext.openAndLoad(
                    rootModule, module, engineServices, configService, context.zone(), context.act(),
                    this::setGameplayMode);

            GameMode oldMode = changeGameModeForBoundary(GameMode.LEVEL);
            if (gameModeChangeListener != null) {
                gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
            }
            LOGGER.info("Restarted recording launch context: " + context.gameId()
                    + " zone " + context.zone() + " act " + context.act()
                    + " team " + context.mainCharacter());
        } catch (IOException e) {
            throw new RuntimeException("Failed to restart from recording launch context", e);
        }
    }

    FadeManager resolveFadeManager() {
        FadeManager manager = this.fadeManager;
        if (manager != null) {
            return manager;
        }
        return engineServices.graphics().getFadeManager();
    }

    // ==================== Title Screen Methods ====================

    /**
     * Initializes the game loop to start in title screen mode.
     * Called from Engine.init() when TITLE_SCREEN_ON_STARTUP is true.
     */
    public void initializeTitleScreenMode() {
        LOGGER.info("Initializing game in Title Screen mode");

        // Ensure the ROM is loaded and audio is initialized
        try {
            var rom = romManager.getRom();
            var gameModule = GameServices.module();

            audioManager.setAudioProfile(gameModule.getAudioProfile());
            audioManager.setRom(rom);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load ROM for title screen", e);
        }

        GameMode oldMode = changeGameModeForBoundary(GameMode.TITLE_SCREEN);

        camera.setX((short) 0);
        camera.setY((short) 0);

        TitleScreenProvider titleScreen = getTitleScreenProviderLazy();
        if (titleScreen != null) {
            titleScreen.initialize();
        }

        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Game initialized in Title Screen mode");
    }

    /**
     * Exits the title screen and hands off to the resolved destination.
     *
     * <p>The destination flow owns the visual transition: Sonic 1 level select
     * reuses the frozen title backdrop, Sonic 2 level start goes straight into
     * the pre-level/title-card pipeline, and donated data select uses a
     * fade-to-black / fade-from-black handoff.
     */
    private void exitTitleScreen() {
        TitleScreenProvider titleScreen = getTitleScreenProviderLazy();
        if (titleScreen == null) {
            return;
        }

        TitleActionRoute route = resolveTitleActionRoute(titleScreen);

        // Sonic 1 level select: immediate transition, no fade, music continues.
        // The original game loads Pal_LevelSel and clears the BG plane instantly.
        // Title screen art data is kept loaded so it can be rendered behind the
        // level select text with the brown/sepia palette tint.
        if (route == TitleActionRoute.LEVEL_SELECT && titleScreen.supportsLevelSelectOverlay()) {
            doEnterLevelSelectFromTitleScreen();
            return;
        }

        if (shouldFadeTitleScreenExit(route) && fadeManager.isActive()) {
            return;
        }

        // Fade out title music
        audioManager.fadeOutMusic();

        if (shouldFadeTitleScreenExit(route)) {
            GameLoopPlcLifecycle.startToBlack(resolveGameplayModeContext(), fadeManager,
                    () -> doExitTitleScreen(route));
            LOGGER.info("Title screen exit fading to " + route);
            return;
        }

        doExitTitleScreen(route);
        LOGGER.info("Title screen exit routed directly to " + route);
    }

    /**
     * Enters the level select screen directly from the title screen, with no
     * fade transition and no music restart. Used by Sonic 1 where the title
     * screen art remains visible (with level select palette) behind the menu.
     *
     * <p>From the Sonic 1 disassembly (Tit_ChkLevSel): the transition loads
     * Pal_LevelSel, clears the BG VRAM plane, and draws the menu text. Music
     * continues uninterrupted.
     */
    private void doEnterLevelSelectFromTitleScreen() {
        // Do NOT reset the title screen - its art data is still needed
        // for rendering the frozen background behind level select text.
        // Do NOT fade music - title music continues.

        GameMode oldMode = changeGameModeForBoundary(GameMode.LEVEL_SELECT);

        camera.setX((short) 0);
        camera.setY((short) 0);

        LevelSelectProvider levelSelect = getLevelSelectProviderLazy();
        if (levelSelect != null) {
            levelSelect.initializeFromTitleScreen();
        }

        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Title screen -> Level Select (immediate, no fade)");
    }

    /**
     * Actually performs the title screen exit after fade-to-black completes.
     */
    private void doExitTitleScreen() {
        TitleScreenProvider titleScreen = getTitleScreenProviderLazy();
        doExitTitleScreen(resolveTitleActionRoute(titleScreen));
    }

    private void doExitTitleScreen(TitleActionRoute route) {
        TitleScreenProvider titleScreen = getTitleScreenProviderLazy();
        if (titleScreen != null) {
            titleScreen.reset();
        }

        executeTitleActionRoute(route);
    }

    /**
     * Gets the title screen provider from the current game module.
     */
    public TitleScreenProvider getTitleScreenProvider() {
        return getTitleScreenProviderLazy();
    }

    TitleScreenProvider getTitleScreenProviderLazy() {
        var gameModule = GameServices.module();
        if (gameModule != null) {
            TitleScreenProvider titleScreenProvider = gameModule.getTitleScreenProvider();
            if (titleScreenProvider != null) {
                titleScreenProvider.setExitToLevelHandler(this::handleTitleScreenExitFromProvider);
            }
            return titleScreenProvider;
        }
        return null;
    }

    private void startLevelFromTitleScreenImmediate() {
        setGameMode(GameMode.LEVEL);
        GameServices.gameState().startNewGameFromTitle();
        try {
            levelManager.loadZoneAndActForFreshRuntime(0, 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load title screen start level", e);
        }
        GameLoopPlcLifecycle.startFromBlack(resolveGameplayModeContext(), fadeManager, null);
    }

    private void handleTitleScreenExitFromProvider() {
        TitleScreenProvider titleScreen = getTitleScreenProviderLazy();
        if (titleScreen == null) {
            return;
        }

        TitleActionRoute route = resolveTitleActionRoute(titleScreen);
        // Provider callbacks fire after the provider has already finished its
        // own staged exit, so this path must hand off directly.
        if (route == TitleActionRoute.LEVEL_SELECT && titleScreen.supportsLevelSelectOverlay()) {
            doEnterLevelSelectFromTitleScreen();
            return;
        }

        if (shouldFadeTitleScreenExit(route) && fadeManager.isActive()) {
            return;
        }

        if (shouldFadeTitleScreenExit(route)) {
            GameLoopPlcLifecycle.startToBlack(resolveGameplayModeContext(), fadeManager,
                    () -> doExitTitleScreen(route));
            return;
        }

        doExitTitleScreen(route);
    }

    private TitleActionRoute resolveTitleActionRoute(TitleScreenProvider titleScreen) {
        var gameModule = GameServices.module();
        if (gameModule == null || titleScreen == null) {
            return TitleActionRoute.LEVEL;
        }
        TitleScreenProvider.TitleScreenAction exitAction = titleScreen.consumeExitAction();
        if (exitAction == null) {
            exitAction = TitleScreenProvider.TitleScreenAction.OTHER;
        }
        return startupRouteResolver.resolveTitleAction(
                gameModule,
                resolveDataSelectPresentation(),
                true,
                configService.getBoolean(SonicConfiguration.LEVEL_SELECT_ON_STARTUP),
                exitAction);
    }

    private DataSelectPresentationResolution resolveDataSelectPresentation() {
        var gameModule = GameServices.module();
        if (gameModule == null) {
            return new DataSelectPresentationResolution(false, null);
        }

        DataSelectProvider dataSelectProvider = getDataSelectProviderLazy();
        boolean dataSelectEligible = dataSelectProvider != null
                && !(dataSelectProvider instanceof NoOpDataSelectProvider);
        boolean crossGameEnabled = configService.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED);
        boolean s3kConfiguredDonor = "s3k".equalsIgnoreCase(
                configService.getString(SonicConfiguration.CROSS_GAME_SOURCE));
        // All modules that expose a DataSelectPresentationProvider use the
        // S3K presentation manager as their delegate. Resolve the presentation
        // game as S3K so the startup router recognises the donated screen for
        // S1/S2 hosts.
        GameId presentationId = gameModule.getGameId();
        if (dataSelectEligible
                && dataSelectProvider instanceof com.openggf.game.dataselect.DataSelectPresentationProvider) {
            boolean nativeS3kDataSelect = gameModule.getGameId() == GameId.S3K;
            boolean donatedS3kDataSelect = crossGameEnabled && s3kConfiguredDonor;
            if (nativeS3kDataSelect || donatedS3kDataSelect) {
                presentationId = GameId.S3K;
            } else {
                dataSelectEligible = false;
            }
        }
        return new DataSelectPresentationResolution(dataSelectEligible, presentationId);
    }

    private boolean shouldFadeTitleScreenExit(TitleActionRoute route) {
        return route == TitleActionRoute.DATA_SELECT || route == TitleActionRoute.LEVEL;
    }

    private void executeTitleActionRoute(TitleActionRoute route) {
        switch (route) {
            case DATA_SELECT -> initializeDataSelectMode();
            case LEVEL_SELECT -> doEnterLevelSelect();
            case LEVEL, TWO_PLAYER, OPTIONS, OTHER -> startLevelFromTitleScreenImmediate();
        }
    }

    // ==================== Data Select Methods ====================

    /**
     * Initializes the data select screen mode.
     * Called when the S3K title screen exits (instead of going directly to level).
     */
    private void initializeDataSelectMode() {
        GameMode oldMode = changeGameModeForBoundary(GameMode.DATA_SELECT);
        camera.setX((short) 0);
        camera.setY((short) 0);
        var dataSelect = getDataSelectProviderLazy();
        if (dataSelect != null) {
            dataSelect.initialize();
        }
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }
        fadeManager.startFadeFromBlack(null);
    }

    /**
     * Exits the data select screen.
     * For now, transitions to level loading.
     * This will be enhanced in later tasks to handle slot selection.
     */
    private void exitDataSelect() {
        if (resolveFadeManager().isActive()) {
            return;
        }
        var dataSelect = getDataSelectProviderLazy();
        com.openggf.game.dataselect.DataSelectAction action = com.openggf.game.dataselect.DataSelectAction.none();
        if (dataSelect instanceof com.openggf.game.dataselect.AbstractDataSelectProvider provider) {
            action = provider.consumePendingAction();
        }
        if (action.type() == com.openggf.game.dataselect.DataSelectActionType.NONE
                || dataSelectActionHandler == null) {
            if (dataSelect != null) {
                dataSelect.reset();
            }
            return;
        }
        if (isDataSelectGameplayAction(action.type())) {
            com.openggf.game.dataselect.DataSelectAction pendingAction = action;
            com.openggf.game.dataselect.DataSelectExitTransition transition =
                    dataSelect.exitTransition();
            if (transition.confirmationSfxId() >= 0) {
                audioManager.playSfx(transition.confirmationSfxId());
            }
            audioManager.fadeOutMusic(transition.musicFadeSteps(), transition.musicFadeDelay());
            resolveFadeManager().startFadeToBlack(() -> {
                try {
                    dataSelectActionHandler.accept(pendingAction);
                    if (dataSelect != null) {
                        dataSelect.reset();
                    }
                } catch (RuntimeException e) {
                    restoreDataSelectAfterLaunchFailure(dataSelect, pendingAction, e);
                }
                resolveFadeManager().startFadeFromBlack(
                        null, transition.revealTerminalNoOpFrames());
            });
            return;
        }
        if (dataSelect != null) {
            dataSelect.reset();
        }
        dataSelectActionHandler.accept(action);
    }

    private void restoreDataSelectAfterLaunchFailure(
            DataSelectProvider dataSelect,
            com.openggf.game.dataselect.DataSelectAction action,
            RuntimeException failure) {
        LOGGER.warning("Data Select launch failed for action " + action.type()
                + " slot " + action.slot() + ": " + failure.getMessage());
        if (currentGameMode != GameMode.DATA_SELECT) {
            setGameMode(GameMode.DATA_SELECT);
        }
        if (dataSelect != null) {
            dataSelect.showLaunchError("Unable to load selected save.");
        }
    }

    private boolean isDataSelectGameplayAction(com.openggf.game.dataselect.DataSelectActionType type) {
        return switch (type) {
            case NO_SAVE_START, NEW_SLOT_START, LOAD_SLOT, CLEAR_RESTART -> true;
            case NONE, DELETE_SLOT -> false;
        };
    }

    /**
     * Gets the data select provider from the current game module.
     */
    public DataSelectProvider getDataSelectProvider() {
        return getDataSelectProviderLazy();
    }

    /**
     * Lazily retrieves the data select provider from the current game module.
     */
    private DataSelectProvider getDataSelectProviderLazy() {
        var gameModule = GameServices.module();
        return gameModule != null ? gameModule.getDataSelectProvider() : null;
    }

    // ==================== Level Select Methods ====================

    /**
     * Initializes the game loop to start directly in level select mode.
     * Called from Engine.init() when LEVEL_SELECT_ON_STARTUP is true.
     * Unlike enterLevelSelect(), this does not require being in LEVEL mode first
     * and does not perform a fade transition.
     */
    public void initializeLevelSelectMode() {
        LOGGER.info("Initializing game in Level Select mode");

        // Ensure the ROM is loaded and audio is initialized before level select
        try {
            var rom = romManager.getRom();
            var gameModule = GameServices.module();

            // Initialize audio system with game module's audio profile
            audioManager.setAudioProfile(gameModule.getAudioProfile());
            audioManager.setRom(rom);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load ROM for level select", e);
        }

        GameMode oldMode = changeGameModeForBoundary(GameMode.LEVEL_SELECT);

        // Set camera to origin for level select rendering
        camera.setX((short) 0);
        camera.setY((short) 0);

        // Initialize the level select provider (also loads and caches palettes)
        LevelSelectProvider levelSelect = getLevelSelectProviderLazy();
        if (levelSelect != null) {
            levelSelect.initialize();
        }

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Game initialized in Level Select mode");
    }

    /**
     * Enters the level select screen from level mode.
     * Performs fade-to-black transition before showing level select.
     */
    public void enterLevelSelect() {
        if (currentGameMode != GameMode.LEVEL) {
            return;
        }

        // Don't start another fade if one is already in progress
        FadeManager fadeManager = this.fadeManager;
        if (fadeManager.isActive()) {
            return;
        }

        // Fade out current music
        audioManager.fadeOutMusic();

        // Start fade-to-black, then enter level select when complete
        GameLoopPlcLifecycle.startToBlack(resolveGameplayModeContext(), fadeManager, () -> {
            doEnterLevelSelect();
        });

        LOGGER.info("Starting fade-to-black for Level Select");
    }

    /**
     * Actually enters the level select screen after fade-to-black completes.
     */
    private void doEnterLevelSelect() {
        GameMode oldMode = changeGameModeForBoundary(GameMode.LEVEL_SELECT);

        // Set camera to origin for level select rendering
        camera.setX((short) 0);
        camera.setY((short) 0);

        // Initialize the level select provider
        LevelSelectProvider levelSelect = getLevelSelectProviderLazy();
        if (levelSelect != null) {
            levelSelect.initialize();
        }

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        // Start fade-from-black to reveal the level select
        GameLoopPlcLifecycle.startFromBlack(resolveGameplayModeContext(), fadeManager, null);

        LOGGER.info("Entered Level Select screen");
    }

    /**
     * Exits the level select screen and loads the selected zone/act or special stage.
     */
    private void exitLevelSelect() {
        LevelSelectProvider levelSelect = getLevelSelectProviderLazy();
        if (levelSelect == null) {
            return;
        }

        // Don't start another fade if one is already in progress
        FadeManager fadeManager = this.fadeManager;
        if (fadeManager.isActive()) {
            return;
        }

        if (levelSelect.isSpecialStageSelected()) {
            // Enter special stage
            levelSelect.reset();
            GameMode oldMode = changeGameModeForBoundary(GameMode.LEVEL);

            // Notify listener of mode change
            if (gameModeChangeListener != null) {
                gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
            }

            // Now enter special stage via the normal path
            enterSpecialStage();
            LOGGER.info("Level select -> Special Stage");

        } else if (levelSelect.isSoundTestSelected()) {
            // Sound test was selected but not a level, just reset
            levelSelect.reset();
            LOGGER.info("Level select sound test (no level transition)");

        } else {
            // Load selected zone/act
            int zone = levelSelect.getSelectedZone();
            int act = levelSelect.getSelectedAct();

            // Reset level select manager
            levelSelect.reset();

            GameServices.gameState().startNewGameFromTitle(); // S1 LevSel_Level -> PlayLevel (sonic.asm:2270-2283)
            // Fade out level select music
            audioManager.fadeOutMusic();

            // Start fade-to-black, then load level
            GameLoopPlcLifecycle.startToBlack(resolveGameplayModeContext(), fadeManager, () -> {
                doExitLevelSelectToZone(zone, act);
            });

            LOGGER.info("Level select -> Zone " + zone + " Act " + act);
        }
    }

    /**
     * Actually loads the selected zone/act after fade-to-black completes.
     */
    private void doExitLevelSelectToZone(int zone, int act) {
        GameMode oldMode = changeGameModeForBoundary(GameMode.LEVEL);

        // Load the selected zone/act
        try {
            levelManager.loadZoneAndAct(zone, act);
        } catch (IOException e) {
            LOGGER.severe("Failed to load zone " + zone + " act " + act + ": " + e.getMessage());
            throw new RuntimeException("Failed to load zone " + zone + " act " + act, e);
        }

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        // Start fade-from-black to reveal the title card
        GameLoopPlcLifecycle.startFromBlack(resolveGameplayModeContext(), fadeManager, null);

        LOGGER.info("Loaded zone " + zone + " act " + act + " from level select");
    }

    /**
     * Gets the level select provider from the current game module.
     *
     * @return the level select provider, or null if not available
     */
    public LevelSelectProvider getLevelSelectProvider() {
        return getLevelSelectProviderLazy();
    }

    /**
     * Lazily retrieves the level select provider from the current game module.
     */
    private LevelSelectProvider getLevelSelectProviderLazy() {
        var gameModule = GameServices.module();
        if (gameModule != null) {
            return gameModule.getLevelSelectProvider();
        }
        return null;
    }

    // ==================== Level Transition Methods with Fade ====================

    public ContinueScreenProvider getContinueScreenProvider() {
        return continueScreen.provider();
    }

    /**
     * Starts the fade-to-black transition for death respawn.
     */
    private void startRespawnFade() {
        LOGGER.info("Starting fade-to-black for respawn");

        // Fade out current music (ROM: s2.asm:4757 - level entry with title card)
        audioManager.fadeOutMusic();

        // Start fade-to-black, then respawn when complete
        GameLoopPlcLifecycle.startToBlack(resolveGameplayModeContext(), fadeManager, continueScreen::respawn);
    }

    /**
     * Starts the fade-to-black transition for next act.
     */
    private void startNextActFade() {
        LOGGER.info("Starting fade-to-black for next act");

        // Fade out current music (ROM: s2.asm:4757 - level entry with title card)
        audioManager.fadeOutMusic();

        // Start fade-to-black, then load next act when complete
        GameLoopPlcLifecycle.startToBlack(resolveGameplayModeContext(), fadeManager, this::doNextAct);
    }

    /**
     * Actually loads the next act after fade-to-black completes.
     */
    private void doNextAct() {
        try {
            levelManager.nextAct();
            activateScheduledPlaybackForLoadedLevel();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load next act", e);
        }

        // Start fade-from-black to reveal the title card
        GameLoopPlcLifecycle.startFromBlack(resolveGameplayModeContext(), fadeManager, null);

        LOGGER.info("Loaded next act");
    }

    /**
     * Starts the fade-to-black transition for next zone.
     */
    private void startNextZoneFade() {
        LOGGER.info("Starting fade-to-black for next zone");

        // Fade out current music (ROM: s2.asm:4757 - level entry with title card)
        audioManager.fadeOutMusic();

        // Start fade-to-black, then load next zone when complete
        GameLoopPlcLifecycle.startToBlack(resolveGameplayModeContext(), fadeManager, this::doNextZone);
    }

    /**
     * Actually loads the next zone after fade-to-black completes.
     */
    private void doNextZone() {
        try {
            levelManager.nextZone();
            activateScheduledPlaybackForLoadedLevel();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load next zone", e);
        }

        // Start fade-from-black to reveal the title card
        GameLoopPlcLifecycle.startFromBlack(resolveGameplayModeContext(), fadeManager, null);

        LOGGER.info("Loaded next zone");
    }

    /**
     * Starts the fade-to-black transition for a specific zone/act.
     */
    private void startZoneActFade(int zone, int act, int postLoadMusicId) {
        LOGGER.info("Starting fade-to-black for zone " + zone + " act " + act);

        audioManager.fadeOutMusic();

        GameLoopPlcLifecycle.startToBlack(resolveGameplayModeContext(), fadeManager,
                () -> doZoneAct(zone, act, postLoadMusicId));
    }

    /**
     * Actually loads the specified zone/act after fade-to-black completes.
     */
    private void doZoneAct(int zone, int act, int postLoadMusicId) {
        boolean restoreSanctuaryOrigin =
                levelManager.isSanctuaryOriginRestorePending(zone, act);
        try {
            // A cutscene may still have a source-zone music fade in flight.
            // Load the destination normally, then issue its requested track
            // last so the old fade cannot win the handoff.
            if (postLoadMusicId >= 0) {
                levelManager.setSuppressNextMusicChange(true);
            }
            levelManager.loadZoneAndAct(zone, act);
            activateScheduledPlaybackForLoadedLevel();
            if (postLoadMusicId >= 0) {
                audioManager.playMusic(postLoadMusicId);
            }
            if (restoreSanctuaryOrigin) {
                var sprite = spriteManager.getSprite(resolveMainCharacterCode());
                if (sprite instanceof AbstractPlayableSprite playable) {
                    restoreBigRingReturn(playable);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load zone " + zone + " act " + act, e);
        }

        if (timeAttackRuntime.isActive()) {
            timeAttackRuntime.onLevelReady();
            // An overlay already visible before spawn is just as much an advantage as
            // toggling one on mid-run (see the anyDebugOverlayTogglePressed() taint check
            // below), so taint immediately rather than waiting for the next toggle press.
            if (debugOverlayManager.isEnabled(DebugOverlayToggle.OVERLAY)) {
                timeAttackRuntime.markTainted();
            }
        }

        GameLoopPlcLifecycle.startFromBlack(resolveGameplayModeContext(), fadeManager, null);

        LOGGER.info("Loaded zone " + zone + " act " + act);
    }

    void activateScheduledPlaybackForLoadedLevel() {
        if (TraceSessionLauncher.activateScheduledPlaybackForLoadedLevel(
                playbackDebugManager)) {
            syncPlaybackInputBridge();
        }
    }

    /**
     * Requests a save to the active session slot, if any.
     * Safe to call when no session is active or when the session has no save slot.
     *
     * @param reason the reason triggering this save
     */
    private void requestSessionSave(SaveReason reason) {
        SessionSaveRequests.requestCurrentSessionSave(reason);
    }

    public void requestSaveForCurrentSession(SaveReason reason) {
        requestSessionSave(reason);
    }

    /**
     * Gets the title card provider (for rendering).
     * 
     * @return the title card provider
     */
    public TitleCardProvider getTitleCardProvider() {
        return getTitleCardProviderLazy();
    }

    /**
     * Gets the current results screen object (for rendering).
     * 
     * @return the results screen object, or null if not in results mode
     */
    public ResultsScreen getResultsScreen() {
        return resultsScreen;
    }

    public SpecialStageProvider getActiveSpecialStageProvider() {
        if (currentGameMode == GameMode.SPECIAL_STAGE || currentGameMode == GameMode.SPECIAL_STAGE_RESULTS) {
            return activeSpecialStageProvider != null ? activeSpecialStageProvider : NoOpSpecialStageProvider.INSTANCE;
        }
        return getCurrentModuleSpecialStageProvider();
    }

    private SpecialStageProvider getCurrentModuleSpecialStageProvider() {
        var module = GameServices.module();
        if (module == null) {
            return NoOpSpecialStageProvider.INSTANCE;
        }
        return module.getSpecialStageProvider();
    }

    private void playSpecialStageTransitionSfx(SpecialStageProvider ssProvider) {
        int sfxId = ssProvider.getTransitionSfxId();
        if (sfxId >= 0) {
            audioManager.playSfx(sfxId);
        }
    }

    /** Transition SFX is emitted by the owner that starts the fade. */
    static boolean shouldPlaySpecialStageEntrySfx(boolean transitionSfxAlreadyPlayed) {
        return !transitionSfxAlreadyPlayed;
    }

    private void revealSpecialStage(SpecialStageProvider ssProvider) {
        camera.setX((short) 0);
        camera.setY((short) 0);
        // Special-stage entry clears gameplay power-ups. Clear both audio
        // speed mechanisms at the same boundary, immediately before the new
        // song is constructed, so it cannot inherit the outgoing level's
        // speed-shoes tempo. S3K's stage-local acceleration starts from here.
        audioManager.setSpeedShoes(false);
        audioManager.setSpeedMultiplier(1);
        int musicId = ssProvider.getStageMusicId();
        if (!audioManager.playMusic(ssProvider.getStageMusic()) && musicId >= 0) {
            audioManager.playMusic(musicId);
        }
    }

    private void playSpecialStageResultsMusic(SpecialStageProvider ssProvider) {
        int musicId = ssProvider.getResultsMusicId();
        if (!audioManager.playMusic(ssProvider.getResultsMusic()) && musicId >= 0) {
            audioManager.playMusic(musicId);
        }
    }

    private void updateSpecialStageInput() {
        int leftKey = configService.getInt(SonicConfiguration.LEFT);
        int rightKey = configService.getInt(SonicConfiguration.RIGHT);
        int upKey = configService.getInt(SonicConfiguration.UP);
        int downKey = configService.getInt(SonicConfiguration.DOWN);
        int debugModeKey = configService.getInt(SonicConfiguration.DEBUG_MODE_KEY);

        SpecialStageProvider ssProvider = getActiveSpecialStageProvider();
        SpecialStageDebugCapabilities capabilities =
                SpecialStageDebugCapabilities.orNone(ssProvider.debugCapabilities());

        if (capabilities.gameplayMovement() && isUnmodifiedDebugKeyPressed(debugModeKey)) {
            ssProvider.toggleGameplayDebugMode();
        }

        if (capabilities.alignment() && isUnmodifiedDebugKeyPressed(GLFW_KEY_F4)) {
            ssProvider.toggleAlignmentTestMode();
        }

        if (capabilities.lagCompensation() && isUnmodifiedDebugKeyPressed(GLFW_KEY_F1)) {
            ssProvider.toggleLagCompensationDisplay();
        }

        if (capabilities.alignment() && ssProvider.isAlignmentTestMode()) {
            if (isUnmodifiedDebugKeyPressed(leftKey)) {
                ssProvider.adjustAlignmentOffset(-1);
            }
            if (isUnmodifiedDebugKeyPressed(rightKey)) {
                ssProvider.adjustAlignmentOffset(1);
            }
            if (isUnmodifiedDebugKeyPressed(upKey)) {
                ssProvider.adjustAlignmentSpeed(0.1);
            }
            if (isUnmodifiedDebugKeyPressed(downKey)) {
                ssProvider.adjustAlignmentSpeed(-0.1);
            }
            if (isUnmodifiedDebugKeyPressed(GLFW_KEY_SPACE)) {
                ssProvider.toggleAlignmentStepMode();
            }
            return;
        }

        SpecialStageInputMapper.MappedInput mapped =
                SpecialStageInputMapper.map(inputHandler.logical());
        ssProvider.handleInput(mapped.p1Held(), mapped.p1Pressed(),
                inputHandler.isShiftDown(), inputHandler.isControlDown());
        ssProvider.handlePlayer2Input(mapped.p2Held(), mapped.p2Logical());
    }

    // ==================== Ending / Credits Sequence Methods ====================

    /**
     * Starts the fade-to-black transition to enter the ending sequence.
     * Called when the level requests credits (e.g., after final boss defeat).
     */
    private void startEndingFade() {
        LOGGER.info("Starting fade-to-white for ending sequence");
        EndingProvider provider = GameServices.module().getEndingProvider();
        if (provider != null) {
            provider.saveReasonOnEndingStart().ifPresent(this::requestSessionSave);
        }
        endingTransitionPending = true;
        audioManager.fadeOutMusic();
        GameLoopPlcLifecycle.startToWhite(resolveGameplayModeContext(), fadeManager, this::doEnterEnding);
    }

    /**
     * Actually enters the ending sequence after fade-to-black completes.
     * Initializes the EndingProvider from the current GameModule.
     */
    private void doEnterEnding() {
        endingProvider = GameServices.module().getEndingProvider();
        if (endingProvider == null) {
            endingTransitionPending = false;
            // No ending provider for this game — return to title screen
            LOGGER.warning("No EndingProvider available, returning to title screen");
            setGameMode(GameMode.TITLE_SCREEN);
            TitleScreenProvider titleScreen = getTitleScreenProviderLazy();
            if (titleScreen != null) {
                titleScreen.initialize();
            }
            return;
        }

        if (endingProvider instanceof com.openggf.game.resources.NativeFadeLifecycleAware aware) {
            aware.bindNativeFadeLifecycle(gameplayMode.plcFrameLifecycle());
        }
        endingProvider.initialize();
        endingTransitionPending = false;
        setGameMode(gameModeForPhase(endingProvider.getCurrentPhase()));

        // Reveal the ending scene. Only start our own fade if the provider didn't
        // already start one during initialize() (e.g., S1 credits starts its own
        // fade-from-black with a state-advancing callback that must not be overwritten).
        FadeManager fadeManager = this.fadeManager;
        if (!fadeManager.isActive() || fadeManager.getState() == FadeManager.FadeState.HOLD_WHITE) {
            // Screen is currently white from startEndingFade's fade-to-white.
            // ROM: Normal_palette starts all $0EEE (white), display enabled.
            GameLoopPlcLifecycle.startFromWhite(resolveGameplayModeContext(), fadeManager, null);
        }

        LOGGER.info("Entered ending sequence, phase=" + endingProvider.getCurrentPhase());
    }

    /**
     * Unified update method for all ending-related game modes.
     * Dispatches to phase-specific logic based on the current EndingPhase.
     */
    private void updateEnding() {
        if (endingProvider == null) {
            return;
        }

        EndingPhase phase = endingProvider.getCurrentPhase();

        switch (phase) {
            case CREDITS_TEXT -> updateEndingCreditsText();
            case CREDITS_DEMO -> updateEndingCreditsDemo();
            case POST_CREDITS -> updateEndingPostCredits();
            case CUTSCENE -> updateEndingCutscene();
            case FINISHED -> exitEndingToTitleScreen();
        }

        // Sync GameMode to the current phase (in case provider changed phase internally)
        if (endingProvider != null && !endingProvider.isComplete()) {
            EndingPhase newPhase = endingProvider.getCurrentPhase();
            GameMode targetMode = gameModeForPhase(newPhase);
            if (targetMode != currentGameMode) {
                setGameMode(targetMode);
            }
        }
    }

    /**
     * CREDITS_TEXT phase: update provider, check for demo load requests and completion.
     */
    private void updateEndingCreditsText() {
        endingProvider.update();

        // Check if the provider wants to load a demo zone
        if (endingProvider.hasDemoLoadRequest()) {
            endingProvider.consumeDemoLoadRequest();
            loadEndingDemoZone();
        }

        // Check if ending is complete (e.g., after "PRESENTED BY SEGA")
        if (endingProvider.isComplete()) {
            exitEndingToTitleScreen();
        }
    }

    /**
     * CREDITS_DEMO phase: update provider, run level physics with demo input.
     */
    private void updateEndingCreditsDemo() {
        boolean shouldAdvanceFrozenScene = endingProvider.shouldAdvanceFrozenDemoScene();
        endingProvider.update();
        shouldAdvanceFrozenScene = shouldAdvanceFrozenScene || endingProvider.shouldAdvanceFrozenDemoScene();

        // Apply demo input to player
        String mainCode = resolveMainCharacterCode();
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite player) {
            if (!endingProvider.shouldRunDemoGameplay()) {
                player.setForcedInputMask(0);
            } else {
            // ROM does NOT set obj_control during demos — it writes demo input
            // directly to jpadhold1/jpadpress1 (MoveSonicInDemo.asm).
            // Do NOT use controlLocked here: PlayableSpriteMovement re-reads it
            // and would zero out left/right/jump, overriding the forced input.
                player.setForcedInputMask(endingProvider.getDemoInputMask());
            }
        }

        if (!endingProvider.shouldRunDemoGameplay()) {
            if (shouldAdvanceFrozenScene) {
                levelManager.updateObjectPositionsWithoutTouches();
                levelManager.updateEndingDemoScene();
            }
            // Level_Delay / PalFadeIn_Alt do not dispatch Sonic_Animate.

            if (endingProvider.hasTextReturnRequest()) {
                endingProvider.consumeTextReturnRequest();
                returnFromEndingDemo();
            }
            if (endingProvider.isComplete()) {
                exitEndingToTitleScreen();
            }
            return;
        }

        // Run level physics — follows LevelFrameStep canonical order (steps 1-4),
        // but steps 5-6 are conditional on scroll-freeze state during ending fadeout.
        spriteManager.publishHeldInputForLevelEvents(inputHandler);
        levelManager.updateZoneFeaturesPrePhysics();
        // Mirror LevelFrameStep step 1c: S1/S2 move the water level before the
        // player's underwater check; LevelManager.update() skips the move when
        // this flag is set, so run it here too.
        if (levelManager.advanceWaterLevelBeforePlayerPhysics()) {
            levelManager.advanceDynamicWaterLevel();
        }
        if (levelManager.objectsExecuteAfterPlayerPhysics()) {
            spriteManager.update(inputHandler);
            levelManager.updateObjectPositionsPostPhysicsWithoutTouches();
        } else {
            levelManager.updateObjectPositions();
            spriteManager.update(inputHandler);
        }
        // Camera/scroll in ROM order (DeformLayers (REV01).asm:16-18): the camera
        // move/clamp (ScrollVertical) runs BEFORE the zone event handler, and the
        // boundary easing (DynamicLevelEvents tail) runs AFTER it. Mirrors
        // LevelFrameStep steps 4a/4b/4c. Camera/scroll only if not frozen (during
        // fadeout, scroll freezes).
        boolean scrollFrozen = endingProvider.isScrollFrozen();
        if (!scrollFrozen) {
            camera.updatePosition();
            camera.captureRenderCopy();
        }
        LevelEventProvider levelEvents = GameServices.module().getLevelEventProvider();
        if (levelEvents != null) {
            levelEvents.update();
        }
        if (!scrollFrozen) {
            camera.updateBoundaryEasing();
            if (levelEvents != null) {
                levelEvents.updateAfterCameraBoundaryEasing();
            }
            levelManager.postCameraObjectPlacementSync();
            // The ending cutscene drives LevelManager directly rather than
            // through LevelFrameStep, which now owns the loop-top increment.
            levelManager.advanceLevelFrameCounter();
            levelManager.update();
            levelManager.refreshObjectPostCameraRenderState();
        }

        // Check if returning to text phase
        if (endingProvider.hasTextReturnRequest()) {
            endingProvider.consumeTextReturnRequest();
            returnFromEndingDemo();
        }

        // Check if ending is complete
        if (endingProvider.isComplete()) {
            exitEndingToTitleScreen();
        }
    }

    /**
     * POST_CREDITS phase: update the post-credits screen (e.g., TRY AGAIN / END).
     * Delegates to the provider's {@code updatePostCredits} / {@code consumePostCreditsExitRequest}
     * methods so each game handles its own input-driven exit logic.
     */
    private void updateEndingPostCredits() {
        endingProvider.updatePostCredits(inputHandler);
        if (endingProvider.consumePostCreditsExitRequest()) {
            exitEndingToTitleScreen();
            return;
        }
        if (endingProvider.isComplete()) {
            exitEndingToTitleScreen();
        }
    }

    /**
     * CUTSCENE phase: update provider (e.g., S2 Tornado flyby).
     */
    private void updateEndingCutscene() {
        endingProvider.update();

        if (endingProvider.isComplete()) {
            exitEndingToTitleScreen();
        }
    }

    /**
     * Loads a demo zone for ending credits and transitions to CREDITS_DEMO mode.
     * Reads zone/act/position from the EndingProvider.
     */
    private void loadEndingDemoZone() {
        int zone = endingProvider.getDemoZone();
        int act = endingProvider.getDemoAct();
        int startX = endingProvider.getDemoStartX();
        int startY = endingProvider.getDemoStartY();

        try {
            // Suppress zone music — credits music should continue playing
            levelManager.setSuppressNextMusicChange(true);
            levelManager.loadZoneAndAct(zone, act);
            // Consume the title card request since we don't want a title card
            levelManager.consumeTitleCardRequest();
        } catch (IOException e) {
            LOGGER.severe("Failed to load ending demo zone " + zone + " act " + act + ": " + e.getMessage());
            return;
        }

        // Suppress HUD during credits demos (ROM: HUD is never drawn during credits)
        levelManager.setForceHudSuppressed(true);

        // Position the player at the demo start position (ROM uses center coordinates)
        DemoLamppostState lamppost = endingProvider.getDemoLamppostState();
        String mainCode = resolveMainCharacterCode();
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite player) {
            // ROM: EndingDemoLoad clears rings, time, score, lamppost (sonic.asm:4148-4152)
            player.setRingCount(0);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
            // Don't set controlLocked — ROM doesn't use obj_control during demos.
            // PlayableSpriteMovement re-reads obj_control and would block all input.
            player.setControlLocked(false);
            player.setForcedInputMask(0);

            if (lamppost != null) {
                // Demo starts at a lamppost — restore saved position/camera/water
                player.setCentreX((short) lamppost.playerX());
                player.setCentreY((short) lamppost.playerY());
                player.setRingCount(lamppost.rings());
                camera.setX((short) lamppost.cameraX());
                camera.setY((short) lamppost.cameraY());
                camera.setMaxY((short) lamppost.cameraMaxY());

                // Restore lamppost water state
                int featureZone = levelManager.getFeatureZoneId();
                int featureAct = levelManager.getFeatureActId();
                WaterSystem waterSystem = this.waterSystem;
                waterSystem.setWaterLevelDirect(featureZone, featureAct,
                        lamppost.waterHeight());
                waterSystem.setWaterLevelTarget(featureZone, featureAct,
                        lamppost.waterHeight());
                ZoneFeatureProvider zfp = levelManager.getZoneFeatureProvider();
                if (zfp != null) {
                    zfp.setWaterRoutine(lamppost.waterRoutine());
                }
            } else {
                // All other demos: use startpos (center coordinates)
                player.setCentreX((short) startX);
                player.setCentreY((short) startY);
            }
        }

        // Snap camera to player position (unless lamppost demo has explicit camera coords)
        if (lamppost == null) {
            camera.updatePosition(true);
        }

        // Prepare the viewport and native player initialization before the fade.
        levelManager.prepareEndingDemoScene();

        // Suppress player keyboard input — demo input comes from forcedInputMask only
        spriteManager.setInputSuppressed(true);

        // Switch to CREDITS_DEMO mode
        setGameMode(GameMode.CREDITS_DEMO);

        // Notify provider that zone is loaded
        endingProvider.onDemoZoneLoaded();

        LOGGER.info("Loaded ending demo zone " + zone + " act " + act +
                " at (" + startX + ", " + startY + ")");
    }

    /**
     * Returns from CREDITS_DEMO to CREDITS_TEXT for the next credit.
     */
    private void returnFromEndingDemo() {
        // Restore player keyboard input and clear HUD suppression
        spriteManager.setInputSuppressed(false);
        levelManager.setForceHudSuppressed(false);
        String mainCode = resolveMainCharacterCode();
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite player) {
            player.setControlLocked(false);
            player.clearForcedInputMask();
        }

        setGameMode(GameMode.CREDITS_TEXT);
        endingProvider.onReturnToText();

        LOGGER.info("Returned from ending demo to credits text");
    }

    /**
     * Exits the ending sequence and returns to the title screen.
     */
    private void exitEndingToTitleScreen() {
        LOGGER.info("Ending sequence complete, returning to title screen");
        GameLoopGameOverExit.startEndingReturn(spriteManager, levelManager, resolveMainCharacterCode(),
                audioManager, fadeManager, resolveGameplayModeContext(), this::doExitEndingToTitleScreen);
    }

    /**
     * Actually transitions to the title screen after fade completes.
     */
    private void doExitEndingToTitleScreen() {
        endingProvider = null;
        continueScreen.returnToTitleScreen();
        LOGGER.info("Ending -> Title Screen");
    }

    /**
     * Maps an {@link EndingPhase} to the corresponding {@link GameMode}.
     */
    private GameMode gameModeForPhase(EndingPhase phase) {
        return switch (phase) {
            case CUTSCENE -> GameMode.ENDING_CUTSCENE;
            case CREDITS_TEXT -> GameMode.CREDITS_TEXT;
            case CREDITS_DEMO -> GameMode.CREDITS_DEMO;
            case POST_CREDITS -> GameMode.TRY_AGAIN_END;
            case FINISHED -> GameMode.TITLE_SCREEN;
        };
    }

    /**
     * Gets the ending provider (for rendering in Engine.java).
     */
    public EndingProvider getEndingProvider() {
        return endingProvider;
    }
}
