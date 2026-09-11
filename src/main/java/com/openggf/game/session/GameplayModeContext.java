package com.openggf.game.session;

import com.openggf.camera.Camera;
import com.openggf.audio.AudioManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.game.AbstractBonusStageCoordinator;
import com.openggf.game.BonusStageProvider;
import com.openggf.game.GameServices;
import com.openggf.game.GameMode;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.HardwareBoundaryDispatch;
import com.openggf.game.NoOpBonusStageProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.palette.PaletteColorStateAdapter;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.resources.DynamicArtDiagnosticsProvider;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.rewind.EngineStepper;
import com.openggf.game.rewind.InMemoryKeyframeStore;
import com.openggf.game.rewind.InputSource;
import com.openggf.game.rewind.PlaybackController;
import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.rewind.RewindBoundaryReporter;
import com.openggf.game.rewind.RewindController;
import com.openggf.game.rewind.BonusStageCoordinatorRewindAdapter;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.rewind.snapshot.OscillationStaticAdapter;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.game.timing.HardwareTimingBoundaryObserver;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.LoadTimeProfile;
import com.openggf.level.SeamlessTransitionResourceHandoffRegistry;
import com.openggf.game.timing.RecordedCompletionAuthority;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.graphics.FadeManager;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.rings.RingManager;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.GroundSensor;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;
import com.openggf.trace.replay.runs.RunLevelLoadTracker;
import com.openggf.trace.replay.runs.TraceRunFrameDriver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

public final class GameplayModeContext implements ModeContext {
    private static final Logger LOG =
            Logger.getLogger(GameplayModeContext.class.getName());
    private static final String PATTERN_ANIMATOR_REWIND_KEY = "pattern-animator";
    private static final String[] PLC_ART_REWIND_KEYS = {
            "s2-plc-art",
            "s3k-plc-art"
    };

    private final WorldSession worldSession;
    private final int spawnX;
    private final int spawnY;
    private final EditorPlaytestStash resumeStash;
    private final HardwareTimingService hardwareTiming;
    private final RuntimeArtCoordinator runtimeArtCoordinator;
    private final SeamlessTransitionResourceHandoffRegistry
            seamlessTransitionResourceHandoffs;
    private RecordedCompletionAuthority recordedCompletionAuthority;
    private final PlcFrameLifecycleCoordinator plcFrameLifecycle;
    private final DynamicArtLifecycleService dynamicArtLifecycle;
    private final RunLevelLoadTracker runLevelLoads = new RunLevelLoadTracker();
    private TraceRunFrameDriver traceRunFrameDriver;
    /**
     * Latched once the source level's main loop has stopped owning the current
     * run-chain transition gap's rows. Session-scoped so no earlier gap, test
     * class, or replay run can decide it for a later one.
     */
    private boolean runGapSourceLevelMainLoopEnded;

    private Camera camera;
    private TimerManager timerManager;
    private GameStateManager gameStateManager;
    private FadeManager fadeManager;
    private AudioManager audioManager;
    private GameRng rng;
    private SolidExecutionRegistry solidExecutionRegistry;

    private WaterSystem waterSystem;
    private ParallaxManager parallaxManager;
    private TerrainCollisionManager terrainCollisionManager;
    private CollisionSystem collisionSystem;
    private SpriteManager spriteManager;
    private LevelManager levelManager;

    private ZoneRuntimeRegistry zoneRuntimeRegistry;
    private PaletteOwnershipRegistry paletteOwnershipRegistry;
    private AnimatedTileChannelGraph animatedTileChannelGraph;
    private SpecialRenderEffectRegistry specialRenderEffectRegistry;
    private AdvancedRenderModeController advancedRenderModeController;
    private ZoneLayoutMutationPipeline zoneLayoutMutationPipeline;

    private BonusStageProvider activeBonusStageProvider = NoOpBonusStageProvider.INSTANCE;
    private boolean managersTornDown;

    private PerformanceProfiler profiler;
    private RewindRegistry rewindRegistry;
    private RewindController rewindController;
    private PlaybackController playbackController;
    private RewindBoundaryReporter rewindBoundaryReporter = RewindBoundaryReporter.NO_OP;
    private HardwareTimingBoundaryObserver hardwareTimingBoundaryObserver =
            HardwareTimingBoundaryObserver.NO_OP;
    private Runnable hardwareTimingReplayCloseHook;

    public GameplayModeContext(WorldSession worldSession) {
        this(worldSession, 0, 0, null, HardwareReadinessAdmissionPolicy.LIVE);
    }

    public GameplayModeContext(
            WorldSession worldSession,
            HardwareReadinessAdmissionPolicy admissionPolicy) {
        this(worldSession, 0, 0, null, admissionPolicy);
    }

    public GameplayModeContext(WorldSession worldSession, int spawnX, int spawnY) {
        this(worldSession, spawnX, spawnY, null,
                HardwareReadinessAdmissionPolicy.LIVE);
    }

    public GameplayModeContext(WorldSession worldSession,
                               int spawnX,
                               int spawnY,
                               EditorPlaytestStash resumeStash) {
        this(worldSession, spawnX, spawnY, resumeStash,
                HardwareReadinessAdmissionPolicy.LIVE);
    }

    public GameplayModeContext(
            WorldSession worldSession,
            int spawnX,
            int spawnY,
            EditorPlaytestStash resumeStash,
            HardwareReadinessAdmissionPolicy admissionPolicy) {
        this(worldSession, spawnX, spawnY, resumeStash, admissionPolicy, null);
    }

    /** Test/tool seam for an explicit per-kind recorded policy map. */
    public GameplayModeContext(
            WorldSession worldSession,
            HardwareReadinessAdmissionPolicy admissionPolicy,
            Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> recordedPolicies) {
        this(worldSession, 0, 0, null, admissionPolicy, recordedPolicies);
    }

    private GameplayModeContext(
            WorldSession worldSession,
            int spawnX,
            int spawnY,
            EditorPlaytestStash resumeStash,
            HardwareReadinessAdmissionPolicy admissionPolicy,
            Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> recordedPolicies) {
        this.worldSession = Objects.requireNonNull(worldSession, "worldSession");
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.resumeStash = resumeStash;
        HardwareReadinessAdmissionPolicy checkedPolicy =
                Objects.requireNonNull(admissionPolicy, "admissionPolicy");
        LoadTimeProfile profile = checkedPolicy
                == HardwareReadinessAdmissionPolicy.RECORDED
                ? LoadTimeProfile.IMMEDIATE
                : Objects.requireNonNullElse(
                        worldSession.getGameModule().createLoadTimeProfile(
                                worldSession.loadTimeSimulationMode(),
                                LOG::warning),
                        LoadTimeProfile.IMMEDIATE);
        this.hardwareTiming = new HardwareTimingService(
                com.openggf.game.timing.RomWorkBudgetScheduler.oneWorkUnitAt(
                        HardwareServiceBoundary.POST_OBJECTS),
                profile);
        this.runtimeArtCoordinator = Objects.requireNonNull(
                worldSession.getGameModule()
                        .createRuntimeArtCoordinator(hardwareTiming),
                "runtimeArtCoordinator");
        this.seamlessTransitionResourceHandoffs =
                new SeamlessTransitionResourceHandoffRegistry();
        this.dynamicArtLifecycle = new DynamicArtLifecycleService();
        this.plcFrameLifecycle =
                new PlcFrameLifecycleCoordinator(
                        worldSession.getGameModule(), dynamicArtLifecycle);
        this.recordedCompletionAuthority =
                checkedPolicy == HardwareReadinessAdmissionPolicy.RECORDED
                        ? (recordedPolicies == null
                                ? hardwareTiming.beginRecordedAdmission()
                                : hardwareTiming.beginRecordedAdmission(recordedPolicies))
                        : null;
    }

    public boolean isGameplayRuntimeReady() {
        return !managersTornDown
                && camera != null
                && timerManager != null
                && gameStateManager != null
                && fadeManager != null
                && rng != null
                && solidExecutionRegistry != null
                && waterSystem != null
                && parallaxManager != null
                && terrainCollisionManager != null
                && collisionSystem != null
                && spriteManager != null
                && levelManager != null
                && zoneRuntimeRegistry != null
                && paletteOwnershipRegistry != null
                && animatedTileChannelGraph != null
                && specialRenderEffectRegistry != null
                && advancedRenderModeController != null
                && zoneLayoutMutationPipeline != null;
    }

    public WorldSession getWorldSession() {
        return worldSession;
    }

    public int getSpawnX() {
        return spawnX;
    }

    public int getSpawnY() {
        return spawnY;
    }

    public boolean hasResumeStash() {
        return resumeStash != null;
    }

    public Optional<EditorPlaytestStash> getResumeStash() {
        return Optional.ofNullable(resumeStash);
    }

    /**
     * Attaches the core disposable gameplay-scoped managers — those without
     * inter-manager construction-order dependencies. Called by
     * the session gameplay factory or test paths that recycle a mode context
     * after destroying its managers. Re-attachment replaces existing
     * references.
     */
    public void attachGameplayManagers(Camera camera,
                                       TimerManager timerManager,
                                       GameStateManager gameStateManager,
                                       FadeManager fadeManager,
                                       GameRng rng,
                                       SolidExecutionRegistry solidExecutionRegistry) {
        attachGameplayManagers(camera, timerManager, gameStateManager, fadeManager,
                rng, solidExecutionRegistry, null);
    }

    public void attachGameplayManagers(Camera camera,
                                       TimerManager timerManager,
                                       GameStateManager gameStateManager,
                                       FadeManager fadeManager,
                                       GameRng rng,
                                       SolidExecutionRegistry solidExecutionRegistry,
                                       PerformanceProfiler profiler) {
        attachGameplayManagers(camera, timerManager, gameStateManager, fadeManager,
                rng, solidExecutionRegistry, profiler, null);
    }

    public void attachGameplayManagers(Camera camera,
                                       TimerManager timerManager,
                                       GameStateManager gameStateManager,
                                       FadeManager fadeManager,
                                       GameRng rng,
                                       SolidExecutionRegistry solidExecutionRegistry,
                                       PerformanceProfiler profiler,
                                       AudioManager audioManager) {
        this.camera = Objects.requireNonNull(camera, "camera");
        this.timerManager = Objects.requireNonNull(timerManager, "timerManager");
        this.gameStateManager = Objects.requireNonNull(gameStateManager, "gameStateManager");
        this.fadeManager = Objects.requireNonNull(fadeManager, "fadeManager");
        this.audioManager = audioManager;
        this.rng = Objects.requireNonNull(rng, "rng");
        this.solidExecutionRegistry = Objects.requireNonNull(solidExecutionRegistry, "solidExecutionRegistry");
        this.profiler = profiler;
        this.managersTornDown = false;
        if (!dynamicArtLifecycle.isRunActive()) {
            dynamicArtLifecycle.beginRun();
        }

        this.rewindRegistry = new RewindRegistry(profiler);
        this.rewindRegistry.register(hardwareTiming);
        this.rewindRegistry.register(dynamicArtLifecycle);
        runtimeArtCoordinator.registerRewindAdapters(this.rewindRegistry);
        this.rewindRegistry.register(seamlessTransitionResourceHandoffs);
        this.rewindRegistry.register(camera);
        this.rewindRegistry.register(gameStateManager);
        this.rewindRegistry.register(rng);
        this.rewindRegistry.register(timerManager);
        this.rewindRegistry.register(fadeManager);
        this.rewindRegistry.register(new OscillationStaticAdapter());
        registerGameModuleRewindAdapters();
        // Register solid-execution adapter (no-op if not DefaultSolidExecutionRegistry)
        if (solidExecutionRegistry instanceof DefaultSolidExecutionRegistry dser) {
            this.rewindRegistry.register(dser);
        }
    }

    /**
     * Attaches the level-coupled disposable managers — water, parallax, the
     * terrain/collision pair, sprite manager, and the LevelManager itself.
     * These have construction-order dependencies on each other and on the core
     * managers, so the caller is responsible for constructing them in the
     * correct order before this attach call.
     */
    public void attachLevelManagers(WaterSystem waterSystem,
                                    ParallaxManager parallaxManager,
                                    TerrainCollisionManager terrainCollisionManager,
                                    CollisionSystem collisionSystem,
                                    SpriteManager spriteManager,
                                    LevelManager levelManager) {
        this.waterSystem = Objects.requireNonNull(waterSystem, "waterSystem");
        this.parallaxManager = Objects.requireNonNull(parallaxManager, "parallaxManager");
        this.terrainCollisionManager = Objects.requireNonNull(terrainCollisionManager, "terrainCollisionManager");
        this.collisionSystem = Objects.requireNonNull(collisionSystem, "collisionSystem");
        this.spriteManager = Objects.requireNonNull(spriteManager, "spriteManager");
        this.levelManager = Objects.requireNonNull(levelManager, "levelManager");

        if (rewindRegistry != null) {
            rewindRegistry.deregister("parallax");
            rewindRegistry.deregister("water");
            rewindRegistry.deregister("collision");
            rewindRegistry.deregister("sprites");
            rewindRegistry.deregister("palette-colors");
            rewindRegistry.deregisterPostRestoreCallback("parallax-derived-state");
            rewindRegistry.deregisterPostRestoreCallback("sprite-powerup-derived-state");
            rewindRegistry.deregisterPostRestoreCallback("sprite-latched-solid-derived-state");
            rewindRegistry.deregisterPostRestoreCallback("sprite-carry-solid-derived-state");
            rewindRegistry.register(parallaxManager);
            rewindRegistry.register(waterSystem);
            rewindRegistry.register(collisionSystem);
            rewindRegistry.register(spriteManager.rewindSnapshottable());
            rewindRegistry.register(new PaletteColorStateAdapter(
                    () -> levelPalettesOrNull(levelManager),
                    () -> underwaterPalettesOrNull(waterSystem, levelManager),
                    GameServices::graphics));
            rewindRegistry.registerPostRestoreCallback(
                    "parallax-derived-state",
                    levelManager::recomputeParallaxAfterRewindRestore);
            rewindRegistry.registerPostRestoreCallback(
                    "sprite-powerup-derived-state",
                    spriteManager::refreshPowerUpObjectsAfterRewindRestore);
            rewindRegistry.registerPostRestoreCallback(
                    "sprite-latched-solid-derived-state",
                    () -> spriteManager.refreshLatchedSolidObjectsAfterRewindRestore(
                            levelManager.getObjectManager()));
            rewindRegistry.registerPostRestoreCallback(
                    "sprite-carry-solid-derived-state",
                    () -> spriteManager.refreshCarrySolidContactOwnersAfterRewindRestore(
                            levelManager.getObjectManager()));
        }
    }

    private static Palette[] levelPalettesOrNull(LevelManager levelManager) {
        Level level = levelManager.getCurrentLevel();
        if (level == null) {
            return null;
        }
        Palette[] palettes = new Palette[level.getPaletteCount()];
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = level.getPalette(i);
        }
        return palettes;
    }

    private static Palette[] underwaterPalettesOrNull(WaterSystem waterSystem, LevelManager levelManager) {
        Level level = levelManager.getCurrentLevel();
        if (level == null) {
            return null;
        }
        return waterSystem.getUnderwaterPalette(level.getZoneIndex(), levelManager.getCurrentAct());
    }

    /**
     * Attaches the runtime-shared registries used by zone-specific behavior:
     * zone-typed runtime state, palette ownership arbitration, animated tile
     * channels, special render effects, advanced render mode overrides, and
     * the zone layout mutation pipeline. Each currently mixes durable world
     * data with per-frame mutation state; the world/gameplay split inside
     * these registries is deferred to a later migration phase.
     */
    public void attachSharedRegistries(ZoneRuntimeRegistry zoneRuntimeRegistry,
                                       PaletteOwnershipRegistry paletteOwnershipRegistry,
                                       AnimatedTileChannelGraph animatedTileChannelGraph,
                                       SpecialRenderEffectRegistry specialRenderEffectRegistry,
                                       AdvancedRenderModeController advancedRenderModeController,
                                       ZoneLayoutMutationPipeline zoneLayoutMutationPipeline) {
        this.zoneRuntimeRegistry = Objects.requireNonNull(zoneRuntimeRegistry, "zoneRuntimeRegistry");
        this.paletteOwnershipRegistry = Objects.requireNonNull(paletteOwnershipRegistry, "paletteOwnershipRegistry");
        this.animatedTileChannelGraph = Objects.requireNonNull(animatedTileChannelGraph, "animatedTileChannelGraph");
        this.specialRenderEffectRegistry = Objects.requireNonNull(specialRenderEffectRegistry, "specialRenderEffectRegistry");
        this.advancedRenderModeController = Objects.requireNonNull(advancedRenderModeController, "advancedRenderModeController");
        this.zoneLayoutMutationPipeline = Objects.requireNonNull(zoneLayoutMutationPipeline, "zoneLayoutMutationPipeline");

        if (rewindRegistry != null) {
            rewindRegistry.deregister("zone-runtime");
            rewindRegistry.deregister("palette-ownership");
            rewindRegistry.deregister("animated-tile-channels");
            rewindRegistry.deregister("special-render");
            rewindRegistry.deregister("advanced-render-mode");
            rewindRegistry.deregister("mutation-pipeline");
            rewindRegistry.register(zoneRuntimeRegistry);
            rewindRegistry.register(paletteOwnershipRegistry);
            rewindRegistry.register(animatedTileChannelGraph);
            rewindRegistry.register(specialRenderEffectRegistry);
            rewindRegistry.register(advancedRenderModeController);
            rewindRegistry.register(zoneLayoutMutationPipeline);
        }
    }

    public Camera getCamera() {
        return camera;
    }

    public TimerManager getTimerManager() {
        return timerManager;
    }

    public GameStateManager getGameStateManager() {
        return gameStateManager;
    }

    public FadeManager getFadeManager() {
        return fadeManager;
    }

    /**
     * Advances the session-owned fade one step. Callers outside the session that
     * only need the per-frame fade tick use this rather than holding the
     * FadeManager itself, so their package does not gain a graphics edge
     * (TestArchUnitRules#core_runtime_cycle_cluster_does_not_gain_top_level_edges).
     */
    public void updateFade() {
        if (fadeManager != null) {
            fadeManager.update();
        }
    }

    public PlcFrameLifecycleCoordinator plcFrameLifecycle() {
        return plcFrameLifecycle;
    }

    public GameRng getRng() {
        return rng;
    }

    public SolidExecutionRegistry getSolidExecutionRegistry() {
        return solidExecutionRegistry;
    }

    public WaterSystem getWaterSystem() {
        return waterSystem;
    }

    public ParallaxManager getParallaxManager() {
        return parallaxManager;
    }

    public TerrainCollisionManager getTerrainCollisionManager() {
        return terrainCollisionManager;
    }

    public CollisionSystem getCollisionSystem() {
        return collisionSystem;
    }

    public SpriteManager getSpriteManager() {
        return spriteManager;
    }

    public LevelManager getLevelManager() {
        return levelManager;
    }

    public ObjectManager getObjectManager() {
        return levelManager != null ? levelManager.getObjectManager() : null;
    }

    public ZoneRuntimeRegistry getZoneRuntimeRegistry() {
        return zoneRuntimeRegistry;
    }

    public PaletteOwnershipRegistry getPaletteOwnershipRegistry() {
        return paletteOwnershipRegistry;
    }

    public AnimatedTileChannelGraph getAnimatedTileChannelGraph() {
        return animatedTileChannelGraph;
    }

    public SpecialRenderEffectRegistry getSpecialRenderEffectRegistry() {
        return specialRenderEffectRegistry;
    }

    public AdvancedRenderModeController getAdvancedRenderModeController() {
        return advancedRenderModeController;
    }

    public ZoneLayoutMutationPipeline getZoneLayoutMutationPipeline() {
        return zoneLayoutMutationPipeline;
    }

    public HardwareTimingService hardwareTiming() {
        return hardwareTiming;
    }

    /** Production-owned player DPLC lifecycle for this gameplay session. */
    public DynamicArtLifecycleService dynamicArtLifecycle() {
        return dynamicArtLifecycle;
    }

    /** Read-only player-DPLC diagnostics for observers outside the owner. */
    public DynamicArtDiagnosticsProvider dynamicArtDiagnostics() {
        return dynamicArtLifecycle;
    }

    /** Production-issued structural receipts consumed by complete-run adapters. */
    public RunLevelLoadTracker runLevelLoads() {
        return runLevelLoads;
    }

    public Optional<TraceRunFrameDriver> traceRunFrameDriver() {
        return Optional.ofNullable(traceRunFrameDriver);
    }

    /**
     * Restarts the dynamic-art run so its ledger holds only the transfers
     * this session's own level load produced.
     *
     * <p>A session that boots on top of an already-loaded host (the master
     * title screen live, a throwaway engine-init level load headless)
     * inherits that load's player-DPLC priming, and every transfer id it
     * mints afterwards is displaced by the inherited count. The session owns
     * the dynamic-art lifecycle, so this reset is published from here rather
     * than by handing the mutation-capable
     * {@link DynamicArtLifecycleService} to the caller that needs it: callers
     * barred from dynamic-art mutation authority (trace and ghost code, see
     * {@code TestS1S2PlcComparisonOnlyGuard}) can ask for a fresh ledger
     * without being able to write one. No-op when no run is active.
     */
    public void restartDynamicArtRunForFreshSession() {
        if (dynamicArtLifecycle.isRunActive()) {
            dynamicArtLifecycle.restartRunForFreshSession();
        }
    }

    /**
     * Re-arms the one-row latch that decides which rows of a run-chain
     * transition gap are still owned by the source level's own main loop (see
     * {@code TraceSessionLauncher.runGapRowContinuesSourceLevelMainLoop}).
     */
    public void beginRunTransitionGap() {
        runGapSourceLevelMainLoopEnded = false;
    }

    /**
     * Consumes that latch: true exactly once per armed gap, on the first row
     * that asks.
     */
    public boolean consumeRunGapFirstRow() {
        boolean first = !runGapSourceLevelMainLoopEnded;
        runGapSourceLevelMainLoopEnded = true;
        return first;
    }

    public void installTraceRunFrameDriver(TraceRunFrameDriver driver) {
        traceRunFrameDriver = Objects.requireNonNull(driver, "driver");
    }

    public void clearTraceRunFrameDriver(TraceRunFrameDriver driver) {
        if (traceRunFrameDriver == driver) {
            traceRunFrameDriver = null;
        }
    }

    /**
     * Closes an open dynamic-art comparison window at a structural replay
     * boundary. Expected trace values never cross this production-owned seam.
     */
    /**
     * Ends the level's comparison window on the ROM iteration whose object pass
     * wrote the next game mode.
     *
     * @see com.openggf.game.resources.DynamicArtLifecycleService#endComparisonSegmentAtRomModeChange()
     */
    public void endDynamicArtComparisonSegmentAtRomModeChange() {
        dynamicArtLifecycle.endComparisonSegmentAtRomModeChange();
    }

    public void endDynamicArtComparisonSegment() {
        if (dynamicArtLifecycle.isComparisonSegmentOpen()) {
            dynamicArtLifecycle.closeComparisonSegment();
        }
    }

    /**
     * Runs the production V-blank art boundary once more at the end of a
     * replay, without publishing a comparison row. The ROM's main loop
     * services its V-int before running objects within one iteration
     * (docs/s2disasm/s2.asm:5088 Level_MainLoop, :5091 WaitForVint with
     * VintID_Level, which reaches ProcessDMAQueue at
     * docs/s2disasm/s2.asm:1769), so the iteration that follows the last
     * sampled frame still retires the transfers submitted on it. Carries no
     * expected trace value.
     */
    public void serviceTerminalDynamicArtVBlank() {
        if (dynamicArtLifecycle.isRunActive()
                && dynamicArtLifecycle.isComparisonSegmentOpen()) {
            dynamicArtLifecycle.serviceTerminalProductionVBlank();
        }
    }

    /** Game-owned runtime-art coordinator for this gameplay session. */
    public RuntimeArtCoordinator runtimeArtCoordinator() {
        return runtimeArtCoordinator;
    }

    /** Captures comparison-only physical queue state at the logical frame boundary. */
    public List<QueueDiagnosticSnapshot> captureQueueDiagnostics() {
        List<QueueDiagnosticSnapshot> snapshots = new ArrayList<>();
        PlcLifecycleService plc = worldSession.getGameModule()
                .getGameService(PlcLifecycleService.class);
        if (plc != null) {
            snapshots.addAll(plc.captureQueueDiagnostics());
        }
        snapshots.addAll(runtimeArtCoordinator.captureQueueDiagnostics());
        snapshots.sort(Comparator.comparing(QueueDiagnosticSnapshot::kind));
        HashSet<QueueDiagnosticSnapshot.Kind> kinds = new HashSet<>();
        for (QueueDiagnosticSnapshot snapshot : snapshots) {
            if (!kinds.add(snapshot.kind())) {
                throw new IllegalStateException(
                        "duplicate queue diagnostic kind: " + snapshot.kind().wireName());
            }
        }
        return List.copyOf(snapshots);
    }

    public SeamlessTransitionResourceHandoffRegistry
            seamlessTransitionResourceHandoffs() {
        return seamlessTransitionResourceHandoffs;
    }

    /**
     * Runs the frame's module state step ahead of timing admission at the boundary,
     * modelling LevelLoop's tail call to Process_Kos_Module_Queue (sonic3k.asm:7908)
     * reaching the next iteration's Process_Kos_Queue (7887) across Wait_VSync (7888).
     * Pairs with {@link #afterHardwareTimingService}; a caller that services a boundary
     * must invoke both, in the order LevelFrameStep.serviceBoundary uses.
     */
    public void beforeHardwareTimingService(HardwareServiceBoundary boundary) {
        runtimeArtCoordinator.beforeTimingService(boundary);
    }

    /** Completes direct physical retirement after timing admission at the boundary. */
    public void afterHardwareTimingService(HardwareServiceBoundary boundary) {
        runtimeArtCoordinator.afterTimingService(boundary);
    }

    /**
     * Traverses a hardware service boundary exactly as the production frame does.
     *
     * <p>Callers outside {@code LevelFrameStep} that need to advance hardware timing
     * — alternate loops and tests modelling a frame — use this rather than composing
     * {@link #beforeHardwareTimingService}, {@code hardwareTiming().service} and
     * {@link #afterHardwareTimingService} by hand, so the sequence has one definition.
     */
    public void serviceHardwareTimingBoundary(HardwareServiceBoundary boundary) {
        HardwareBoundaryDispatch.serviceBoundary(
                boundary, runtimeArtCoordinator, hardwareTiming,
                hardwareTimingBoundaryObserver);
    }

    public RecordedCompletionAuthority recordedCompletionAuthority() {
        if (recordedCompletionAuthority == null) {
            throw new IllegalStateException(
                    "gameplay context was not constructed for recorded hardware admission");
        }
        return recordedCompletionAuthority;
    }

    /**
     * Converts a production-live title-card prelude into recorded readiness
     * without replacing any gameplay or runtime-art owner.
     */
    public RecordedCompletionAuthority activateRecordedHardwareAdmission() {
        if (recordedCompletionAuthority != null) {
            throw new IllegalStateException(
                    "gameplay context already owns recorded hardware admission");
        }
        requireQueuesIdleForRecordedAdmission(captureQueueDiagnostics());
        RecordedCompletionAuthority activated =
                hardwareTiming.beginRecordedAdmissionAfterLiveEpoch();
        recordedCompletionAuthority = activated;
        return activated;
    }

    static void requireQueuesIdleForRecordedAdmission(
            List<QueueDiagnosticSnapshot> snapshots) {
        Objects.requireNonNull(snapshots, "queue diagnostics");
        for (QueueDiagnosticSnapshot snapshot : snapshots) {
            Objects.requireNonNull(snapshot, "queue diagnostic");
            if (snapshot.busy()) {
                throw new IllegalStateException(
                        "runtime-art queue is not idle: "
                                + snapshot.kind().wireName());
            }
        }
    }

    public HardwareTimingBoundaryObserver hardwareTimingBoundaryObserver() {
        return hardwareTimingBoundaryObserver;
    }

    public void setHardwareTimingBoundaryObserver(
            HardwareTimingBoundaryObserver observer) {
        hardwareTimingBoundaryObserver = observer != null
                ? observer
                : HardwareTimingBoundaryObserver.NO_OP;
    }

    public void setHardwareTimingReplayCloseHook(Runnable closeHook) {
        if (hardwareTimingReplayCloseHook != null) {
            throw new IllegalStateException(
                    "hardware timing replay close hook is already installed");
        }
        hardwareTimingReplayCloseHook =
                Objects.requireNonNull(closeHook, "closeHook");
    }

    public void clearHardwareTimingReplayCloseHook() {
        hardwareTimingReplayCloseHook = null;
    }

    // ── Rewind framework ─────────────────────────────────────────────────

    /**
     * Returns the {@link RewindRegistry} for this gameplay session. The six
     * always-available atomic adapters (camera, game-state, rng, timers,
     * fade, oscillation) are registered automatically by
     * {@link #attachGameplayManagers}. Level and object-manager adapters are
     * added post-load via {@link #registerLevelAdapters}.
     */
    public RewindRegistry getRewindRegistry() {
        return rewindRegistry;
    }

    /**
     * Registers (or re-registers) the level and object-manager adapters with
     * the rewind registry. Safe to call multiple times — existing entries are
     * deregistered first to avoid duplicate-key errors.
     * <p>
     * Must be called by {@link LevelManager} after both the level data and
     * the {@link com.openggf.level.objects.ObjectManager} are ready (i.e.
     * after {@code initObjectSystem()} completes). If
     * {@code levelManager.getObjectManager()} is null the object-manager
     * adapter is skipped.
     */
    public void registerLevelAdapters(LevelManager levelManager) {
        if (rewindRegistry == null) {
            return;
        }
        // Game modules create ROM-bound services during LevelManager.initGameModule().
        // Register again at the post-createGame level boundary so the façade
        // instance captured by rewind is the live service, not the empty
        // pre-ROM module graph seen when the gameplay session was attached.
        registerGameModuleRewindAdapters();
        // A production level load calls this after its tilemap owner exists.
        // Retaining the game-service registration here also makes the lifecycle
        // safe for an early caller: it must not install a level-tilemap adapter
        // around a not-yet-created manager.
        if (levelManager.getTilemapManager() == null) {
            return;
        }
        rewindRegistry.deregister("level");
        rewindRegistry.deregister("level-tilemap");
        rewindRegistry.deregister("object-manager");
        rewindRegistry.deregister("level-event");
        rewindRegistry.deregister("solid-execution");
        rewindRegistry.deregisterPostRestoreCallback("level-tilemap-event-reconcile");
        rewindRegistry.register(levelManager.levelRewindSnapshottable());
        rewindRegistry.register(levelManager.levelTilemapRewindSnapshottable());
        if (levelManager.getObjectManager() != null) {
            rewindRegistry.register(levelManager.getObjectManager().rewindSnapshottable());
        }
        if (solidExecutionRegistry instanceof DefaultSolidExecutionRegistry dser) {
            rewindRegistry.register(dser);
        }
        // Register level-event manager adapter (available after gameModule is set).
        AbstractLevelEventManager levelEventManager = null;
        if (levelManager.getGameModule() != null) {
            LevelEventProvider lep = levelManager.getGameModule().getLevelEventProvider();
            if (lep instanceof AbstractLevelEventManager alem) {
                levelEventManager = alem;
                rewindRegistry.register(alem);
            }
            rewindRegistry.deregister(
                    com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager.REWIND_KEY);
            rewindRegistry.deregister(
                    com.openggf.game.sonic2.titlecard.TitleCardManager.REWIND_KEY);
            if (levelManager.getGameModule().getTitleCardProvider()
                    instanceof com.openggf.game.rewind.RewindSnapshottable<?> titleCard) {
                rewindRegistry.deregister(titleCard.key());
                rewindRegistry.register(titleCard);
            }
        }
        // Register game-specific extra adapters contributed by the level-event manager
        // (e.g. S3K AIZ2 boss-endgame static latches). Deregister first for idempotency.
        if (levelEventManager != null) {
            for (com.openggf.game.rewind.RewindSnapshottable<?> extra : levelEventManager.extraRewindAdapters()) {
                rewindRegistry.deregister(extra.key());
                rewindRegistry.register(extra);
            }
        }
        // Post-restore reconciliation (runs after all entry restores, i.e. after
        // object-manager recreate): let level-event handlers reconcile one-shot
        // sequence state against the restored object set (e.g. S3K AIZ2
        // ship-loop/boss softlock guards), and invalidate the BG incremental-shift
        // window so it re-derives from the restored camera position. The FG
        // tilemap needs no invalidation here (the AIZ2 FG ring self-heals via the
        // bidirectional window reconcile; flat FG tilemaps are layout-pure).
        // Held rewind fires this on every backward step, so it must stay cheap.
        // No-ops outside the zones that need them.
        final AbstractLevelEventManager reconcileTarget = levelEventManager;
        rewindRegistry.registerPostRestoreCallback("level-tilemap-event-reconcile", () -> {
            if (reconcileTarget != null) {
                reconcileTarget.reconcileAfterRewindRestore();
            }
            var tilemapManager = levelManager.getTilemapManager();
            if (tilemapManager != null) {
                tilemapManager.resetTilemapsForRewindRestore();
            }
        });
    }

    /**
     * Registers the current session module's rewindable services idempotently.
     * This is deliberately separate from the core-manager attachment because
     * ROM-bound game services are created later by the level lifecycle.
     */
    public void registerGameModuleRewindAdapters() {
        if (rewindRegistry == null) {
            return;
        }
        for (com.openggf.game.rewind.RewindSnapshottable<?> adapter
                : worldSession.getGameModule().rewindAdapters()) {
            rewindRegistry.deregister(adapter.key());
            rewindRegistry.register(adapter);
        }
    }

    /**
     * Registers the {@link RingManager} rewind adapter after ring data is
     * available (Phase H of level load, after {@link #registerLevelAdapters}).
     * Safe to call with a null argument -- it is silently ignored.
     */
    public void registerRingAdapter(RingManager ringManager) {
        if (rewindRegistry == null || ringManager == null) {
            return;
        }
        rewindRegistry.deregister("rings");
        rewindRegistry.register(ringManager);
    }

    /**
     * Rebinds only the concrete managers replaced by an in-place act reload.
     * Other adapter owners retain their registration identity and restore order.
     */
    public void rebindActTransitionManagerAdapters(
            ObjectManager objectManager, RingManager ringManager) {
        if (rewindRegistry == null) {
            return;
        }
        rewindRegistry.deregister("object-manager");
        rewindRegistry.deregister("rings");
        if (objectManager != null) {
            rewindRegistry.register(objectManager.rewindSnapshottable());
        }
        if (ringManager != null) {
            rewindRegistry.register(ringManager);
        }
    }

    /**
     * Registers an {@link com.openggf.game.ObjectArtProvider} that also implements
     * {@link com.openggf.game.rewind.RewindSnapshottable} with the rewind registry.
     * Called from {@link com.openggf.level.LevelManager} after object art is loaded.
     * Safe to call with a null argument; stale optional PLC-art adapters are
     * removed for zones that do not expose a snapshottable provider.
     */
    public void registerPlcArtAdapter(com.openggf.game.ObjectArtProvider provider) {
        if (rewindRegistry == null) {
            return;
        }
        deregisterPlcArtAdapters();
        if (provider == null) {
            return;
        }
        if (provider instanceof com.openggf.game.rewind.RewindSnapshottable<?> snap) {
            rewindRegistry.deregister(snap.key());
            rewindRegistry.register(snap);
        }
    }

    /**
     * Registers a {@link com.openggf.level.animation.AnimatedPatternManager} that also
     * implements {@link com.openggf.game.rewind.RewindSnapshottable} with the rewind
     * registry. Called from {@link com.openggf.level.LevelManager#initAnimatedContent()}.
     * Safe to call with a null argument; stale optional pattern animator
     * adapters are removed for zones without an animated pattern manager.
     */
    public void registerPatternAnimatorAdapter(
            com.openggf.level.animation.AnimatedPatternManager mgr) {
        if (rewindRegistry == null) {
            return;
        }
        rewindRegistry.deregister(PATTERN_ANIMATOR_REWIND_KEY);
        if (mgr == null) {
            return;
        }
        if (mgr instanceof com.openggf.game.rewind.RewindSnapshottable<?> snap) {
            rewindRegistry.deregister(snap.key());
            rewindRegistry.register(snap);
        }
    }

    /**
     * Registers a rewind adapter capturing the active bonus-stage coordinator's
     * reward accumulators, but only for a rewind-supported stage
     * (Gumball/Pachinko). No-op for the Slot Machine or when rewind is
     * unavailable. Idempotent -- deregisters any prior adapter first.
     */
    public void registerBonusStageAdapter(BonusStageProvider provider) {
        if (rewindRegistry == null) {
            return;
        }
        rewindRegistry.deregister(BonusStageCoordinatorRewindAdapter.KEY);
        if (provider instanceof AbstractBonusStageCoordinator coordinator
                && provider.supportsRewind()) {
            rewindRegistry.register(new BonusStageCoordinatorRewindAdapter(coordinator));
        }
    }

    /** Removes the bonus-stage coordinator rewind adapter on stage exit. */
    public void deregisterBonusStageAdapter() {
        if (rewindRegistry != null) {
            rewindRegistry.deregister(BonusStageCoordinatorRewindAdapter.KEY);
        }
    }

    public void registerSpecialStageAdapter(SpecialStageProvider provider) {
        if (rewindRegistry == null) {
            return;
        }
        rewindRegistry.deregister(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY);
        if (provider != null && provider.supportsRewind()) {
            provider.rewindAdapter().ifPresent(rewindRegistry::register);
        }
    }

    public void deregisterSpecialStageAdapter() {
        if (rewindRegistry != null) {
            rewindRegistry.deregister(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY);
        }
    }

    private void deregisterPlcArtAdapters() {
        for (String key : PLC_ART_REWIND_KEYS) {
            rewindRegistry.deregister(key);
        }
    }

    /**
     * Constructs and installs a {@link RewindController} and
     * {@link PlaybackController} backed by this context's registry. Replaces
     * any previously installed controllers.
     *
     * @throws IllegalStateException if {@link #attachGameplayManagers} has
     *         not been called yet (registry is null)
     */
    public PlaybackController installPlaybackController(
            InputSource inputs,
            EngineStepper stepper,
            int keyframeInterval) {
        if (rewindRegistry == null) {
            throw new IllegalStateException(
                    "rewindRegistry not initialised — call attachGameplayManagers first");
        }
        this.rewindController = new RewindController(
                rewindRegistry,
                new InMemoryKeyframeStore(),
                inputs,
                stepper,
                keyframeInterval,
                audioManager,
                profiler);
        this.playbackController = new PlaybackController(rewindController);
        return playbackController;
    }

    /** Returns the installed {@link RewindController}, or {@code null} if not yet installed. */
    public RewindController getRewindController() {
        return rewindController;
    }

    /** Returns the installed {@link PlaybackController}, or {@code null} if not yet installed. */
    public PlaybackController getPlaybackController() {
        return playbackController;
    }

    public void setRewindBoundaryReporter(RewindBoundaryReporter reporter) {
        this.rewindBoundaryReporter = reporter != null ? reporter : RewindBoundaryReporter.NO_OP;
    }

    public void markRewindBoundary(RewindBoundary boundary) {
        if (boundary != null) {
            rewindBoundaryReporter.markBoundary(boundary);
        }
    }

    // ── Bonus stage provider ─────────────────────────────────────────────

    /**
     * Returns the active bonus stage provider, or
     * {@link NoOpBonusStageProvider#INSTANCE} when no bonus stage is active.
     * Owned here (gameplay-scoped) so callers can resolve it via
     * {@link com.openggf.game.session.SessionManager#getCurrentGameplayMode()}
     * without consulting a process-wide gameplay locator.
     */
    public BonusStageProvider getActiveBonusStageProvider() {
        return activeBonusStageProvider;
    }

    public void setActiveBonusStageProvider(BonusStageProvider provider) {
        this.activeBonusStageProvider = provider != null ? provider : NoOpBonusStageProvider.INSTANCE;
    }

    @Override
    public GameMode getGameMode() {
        return GameMode.LEVEL;
    }

    @Override
    public void destroy() {
        tearDownManagers();
    }

    /**
     * Tears down all attached managers in reverse construction order.
     * Idempotent: each manager's reset is a no-op when its field is null
     * (e.g., when destroy is invoked during a partial setup).
     */
    public void tearDownManagers() {
        if (managersTornDown) {
            return;
        }
        managersTornDown = true;
        RuntimeException replayCloseFailure = null;
        Runnable replayClose = hardwareTimingReplayCloseHook;
        hardwareTimingReplayCloseHook = null;
        if (replayClose != null) {
            try {
                replayClose.run();
            } catch (RuntimeException failure) {
                replayCloseFailure = failure;
            }
        }
        if (rewindRegistry != null) {
            rewindRegistry.deregister(HardwareTimingService.REWIND_KEY);
            rewindRegistry.deregister(DynamicArtLifecycleService.REWIND_KEY);
            runtimeArtCoordinator.deregisterRewindAdapters(rewindRegistry);
            rewindRegistry.deregister(
                    seamlessTransitionResourceHandoffs.key());
        }
        hardwareTiming.resetForMissingSnapshot();
        if (dynamicArtLifecycle.isRunActive()) {
            dynamicArtLifecycle.finishRun();
        }
        dynamicArtLifecycle.resetForMissingSnapshot();
        runtimeArtCoordinator.resetForMissingSnapshot();
        plcFrameLifecycle.reset();
        seamlessTransitionResourceHandoffs.resetForMissingSnapshot();
        hardwareTimingBoundaryObserver = HardwareTimingBoundaryObserver.NO_OP;
        if (zoneLayoutMutationPipeline != null) {
            zoneLayoutMutationPipeline.clear();
        }
        if (solidExecutionRegistry != null) {
            solidExecutionRegistry.clearTransientState();
        }
        if (animatedTileChannelGraph != null) {
            animatedTileChannelGraph.clear();
        }
        if (specialRenderEffectRegistry != null) {
            specialRenderEffectRegistry.clear();
        }
        if (advancedRenderModeController != null) {
            advancedRenderModeController.clear();
        }
        if (paletteOwnershipRegistry != null) {
            paletteOwnershipRegistry.clear();
        }
        if (zoneRuntimeRegistry != null) {
            zoneRuntimeRegistry.clear();
        }
        if (levelManager != null) {
            levelManager.resetGameplayState();
        }
        if (spriteManager != null) {
            spriteManager.resetState();
        }
        if (collisionSystem != null) {
            collisionSystem.resetState();
        }
        if (terrainCollisionManager != null) {
            terrainCollisionManager.resetState();
        }
        if (parallaxManager != null) {
            parallaxManager.resetState();
        }
        if (waterSystem != null) {
            waterSystem.reset();
        }
        if (fadeManager != null) {
            fadeManager.cancel();
        }
        if (gameStateManager != null) {
            gameStateManager.resetState();
        }
        if (timerManager != null) {
            timerManager.resetState();
        }
        if (camera != null) {
            camera.resetState();
        }
        GroundSensor.setLevelManager(null);
        zoneLayoutMutationPipeline = null;
        advancedRenderModeController = null;
        specialRenderEffectRegistry = null;
        animatedTileChannelGraph = null;
        paletteOwnershipRegistry = null;
        zoneRuntimeRegistry = null;
        levelManager = null;
        spriteManager = null;
        collisionSystem = null;
        terrainCollisionManager = null;
        parallaxManager = null;
        waterSystem = null;
        solidExecutionRegistry = null;
        rng = null;
        audioManager = null;
        fadeManager = null;
        gameStateManager = null;
        timerManager = null;
        camera = null;
        activeBonusStageProvider = NoOpBonusStageProvider.INSTANCE;
        rewindController = null;
        playbackController = null;
        rewindBoundaryReporter = RewindBoundaryReporter.NO_OP;
        rewindRegistry = null;
        if (replayCloseFailure != null) {
            throw replayCloseFailure;
        }
    }

    /**
     * Resets session-progress counters to "fresh gameplay" defaults — score,
     * rings, lives, emeralds, timer, and (via LevelManager) checkpoint state.
     * Per the session ownership migration design
     * (docs/architecture/designs/2026-04-07-runtime-ownership-migration-design.md),
     * editor exit must reinitialize gameplay session state as fresh gameplay,
     * not resumed state. Call this from the exit-editor flow after a new
     * gameplay mode context is wired up.
     */
    public void initializeFreshGameplayState() {
        if (gameStateManager != null) {
            gameStateManager.resetState();
        }
        if (timerManager != null) {
            timerManager.resetState();
        }
        if (levelManager != null) {
            com.openggf.game.RespawnState checkpoint = levelManager.getCheckpointState();
            if (checkpoint != null) {
                checkpoint.clear();
            }
        }
    }
}
