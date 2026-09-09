package com.openggf.level;

import com.openggf.game.GameOverExit;

import com.openggf.game.GameOverExit;

import com.openggf.game.session.EngineContext;
import com.openggf.game.*;
import com.openggf.Engine;
import com.openggf.GameLoop;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.editor.persistence.EditorSaveManager;
import com.openggf.data.Game;
import com.openggf.data.AnimatedPaletteProvider;
import com.openggf.data.AnimatedPatternProvider;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.DynamicStartPositionProvider;
import com.openggf.game.GameStateManager;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.debug.DebugObjectArtViewer;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.mutation.LayoutMutationContext;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.resources.DynamicArtDecisionOwner;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.rewind.snapshot.LevelSnapshot;
import com.openggf.game.rewind.snapshot.LevelTilemapSnapshot;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.rules.CameraRules;
import com.openggf.game.rules.CollisionRules;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.ObjectInteractionRules;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.WorldSession;
import com.openggf.level.rewind.LevelRewindSnapshotAdapter;
import com.openggf.level.rewind.LevelTilemapRewindAdapter;
import com.openggf.level.objects.HudRenderManager;
import com.openggf.level.objects.HudStaticArt;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.PatternAtlas;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.audio.AudioManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PaletteFadePresentation;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.render.BackgroundRenderer;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PersistentRespawnState;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.rings.RingManager;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.level.resources.DeferredLevelResourceTracker;
import com.openggf.level.resources.DeferredLevelResourceLoader;
import com.openggf.level.resources.PreparableLevelLoader;
import com.openggf.level.resources.PreparedLevelBuild;
import com.openggf.level.scroll.BgTilemapUpdateMode;
import com.openggf.level.animation.AnimatedPaletteManager;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.Direction;
import com.openggf.sprites.Sprite;
import com.openggf.game.PowerUpObject;
import com.openggf.level.objects.DefaultPowerUpSpawner;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;
import static org.lwjgl.opengl.GL11.glClearColor;

/**
 * Manages the loading and rendering of game levels.
 */
public class LevelManager extends InitialProcessSpritesLevelManagerBase {
    static final Logger LOGGER = Logger.getLogger(LevelManager.class.getName());
    static final int OBJECT_PATTERN_BASE = PatternAtlasRange.OBJECTS.base();
    private static final int HUD_PATTERN_BASE = PatternAtlasRange.HUD.base();
    /** Base for extra sidekick-style DPLC banks — above water (0x30000) and below title cards (0x40000). */
    public static final int SIDEKICK_PATTERN_BASE = PatternAtlasRange.SIDEKICK_BANKS.base();
    private static final Palette.Color BLACK_BACKDROP = new Palette.Color((byte) 0, (byte) 0, (byte) 0);
    /** Scratch for {@link #resolveLevelBackdropColor()} while a palette fade covers the backdrop line. */
    private final Palette.Color fadedBackdrop = new Palette.Color();
    // Local mirror of the loaded Level owned by WorldSession. Reads use this
    // field directly for speed; writes go through writeCurrentLevel() to keep
    // the world session in sync.
    Level level;
    int blockPixelSize = 128;  // cached from level
    // Block-grid index math (pow2 fast path); recomputed in cacheLevelDimensions().
    BlockGridIndexer blockGrid = new BlockGridIndexer(128);
    /** Owns block/chunk/pattern resolution from world coordinates. */
    private final LevelLayoutLookup layoutLookup = new LevelLayoutLookup(this);
    private int chunksPerBlockSide = 8;
    // Cached level pixel dimensions (immutable once level loads).
    // Avoids repeated getLayerWidthBlocks()*blockPixelSize in hot-path collision lookups.
    int cachedFgWidthPx;
    int cachedFgHeightPx;
    int cachedBgWidthPx;           // Full map width for BG layer (used for block lookups)
    int cachedBgContiguousWidthPx; // Contiguous BG data width from column 0 (for bgTilemapBaseX wrapping)
    int cachedBgHeightPx;
    Game game;
    GameModule gameModule;
    private boolean levelEntryBegun;

    public Game getGame() {
        return game;
    }

    public GameModule getGameModule() {
        return gameModule;
    }

    GameModule activeGameModule() {
        if (gameModule != null) {
            return gameModule;
        }
        WorldSession world = SessionManager.getCurrentWorldSession();
        if (world != null && world.getGameModule() != null) {
            return world.getGameModule();
        }
        return GameServices.currentOrBootstrapGameModule();
    }

    /** Collision model metadata only; frame scheduling may still use inline checkpoints. */
    private boolean isUnifiedCollisionModel() {
        GameRules rules = activeGameModule().getRules();
        return rules != null
                && rules.collision() != null
                && rules.collision().collisionModel() == com.openggf.game.CollisionModel.UNIFIED;
    }

    /** Returns the tilemap lifecycle delegate. */
    public LevelTilemapManager getTilemapManager() {
        return tilemapManager;
    }

    GraphicsManager graphicsManager;
    AudioManager audioManager;
    SpriteManager spriteManager;
    private CollisionSystem collisionSystem;
    WaterSystem waterSystem;
    private GameStateManager gameState;
    SonicConfigurationService configService;
    DebugOverlayManager overlayManager;
    LevelDebugRenderer debugRenderer;
    PerformanceProfiler profiler;
    private CrossGameFeatureProvider crossGameFeatures;
    final List<List<LevelData>> levels = new ArrayList<>();
    private final WorldSession worldSession;
    // Local mirror of zone/act state owned by WorldSession. Reads use these
    // fields directly for speed; writes go through writeCurrentZone /
    // writeCurrentAct / writeApparentAct so both copies stay in sync.
    int currentAct = 0;
    private int apparentAct = 0;
    int currentZone = 0;
    private boolean sidekickRomVisibleReloadFrameCounterBridgeActive;
    private boolean sidekickRomVisibleReloadFrameCounterBridgePrimed;
    private boolean resetCounterPlacementAfterCameraSnap;
    private long completedProductionLoadGeneration;

    /**
     * The native Level routine clears the playable object slots before its
     * blocking title-card loop starts. The ordinary Java load path assembles
     * the destination roster synchronously, so the recording driver holds this
     * boundary until the title-card owner has consumed that loop. It is a
     * transition-only handoff, not gameplay state, and is deliberately not
     * part of rewind snapshots.
     */
    @RewindTransient(reason = "transient fresh-level title-card boundary; rebuilt by the transition owner")
    private FreshLevelTransitionBoundary pendingFreshLevelTransitionBoundary;

    private record FreshLevelTransitionBoundary(
            short previousCameraX,
            short previousCameraY,
            int previousRings,
            short destinationCameraX,
            short destinationCameraY,
            List<TransitionPlayableState> playableStates) {
        private FreshLevelTransitionBoundary {
            playableStates = List.copyOf(playableStates);
        }
    }

    private record TransitionPlayableState(
            String code,
            PerObjectRewindSnapshot state) {
    }

    void writeCurrentZone(int zone) {
        this.currentZone = zone;
        worldSession.setCurrentZone(zone);
    }

    void writeCurrentAct(int act) {
        this.currentAct = act;
        worldSession.setCurrentAct(act);
    }

    private void writeApparentAct(int act) {
        this.apparentAct = act;
        worldSession.setApparentAct(act);
    }

    private void writeCurrentLevel(Level level) {
        this.level = level;
        worldSession.setCurrentLevel(level);
    }
    int frameCounter = 0;
    // When set, the NEXT advanceGlobalOscillation() call skips the global
    // oscillator advance. GameLoop sets it for a title-card wait-loop object
    // pass: the ROM advances the global oscillator (OscillateNumDo) only inside
    // Level_MainLoop (docs/s2disasm/s2.asm:5108), never in the title-card wait
    // loops (s2.asm:4914-4924, 5060-5066) which run RunObjects only. S1 mirrors
    // this (docs/s1disasm/sonic.asm: OscillateNumInit 2913 and OscillateNumDo
    // 3030 are both outside Level_TtlCardLoop 2811-2839). Consumed on the next
    // object pass so it never leaks into the first Level_MainLoop gameplay frame.
    private boolean suppressGlobalOscillationForTitleCardPass = false;
    ObjectManager objectManager;
    private PersistentRespawnState persistentRespawnStateForNextObjectReset;
    /** Keeps a return respawn snapshot alive through the post-load camera snap. */
    private PersistentRespawnState persistentRespawnStateForCameraSnap;
    private int ringFloorCheckCounterPhase;
    RingManager ringManager;
    ZoneFeatureProvider zoneFeatureProvider;
    private TouchResponseTable touchResponseTable;
    ObjectRenderManager objectRenderManager;
    HudRenderManager hudRenderManager;
    AnimatedPatternManager animatedPatternManager;
    AnimatedPaletteManager animatedPaletteManager;
    LevelState levelGamestate;

    // GPU tilemap lifecycle delegate (build/cache/upload/invalidate)
    LevelTilemapManager tilemapManager;

    // All transition request/consume state lives in the coordinator
    private final LevelTransitionCoordinator transitions = new LevelTransitionCoordinator();
    private boolean initialPresentationPlcsCompleted;
    /**
     * Whether the current load's initial title-card presentation is omitted
     * rather than presented, so its player object passes must be replayed.
     */
    private boolean initialPresentationOmitted;

    // ROM: LZ3/SBZ2 vertical wrapping — FG layer wraps Y instead of clamping
    boolean verticalWrapEnabled = false;

    // ROM collision-layout row-index mask (see layoutLookupY). 0 = not modelled.
    int collisionLayoutYMask;

    // Background rendering support
    ParallaxManager parallaxManager;
    boolean useShaderBackground = true; // Feature flag for shader background


    // Cached screen dimensions (avoids repeated config service lookups)
    int cachedScreenWidth;
    int cachedScreenHeight;

    // Camera reference for frustum culling
    Camera camera;

    // Rendering pipeline (extracted from LevelManager — see LevelRenderer).
    private final LevelRenderer levelRenderer = new LevelRenderer(this);
    final LevelFrameRuntimeUpdater frameRuntimeUpdater = new LevelFrameRuntimeUpdater(this);
    private final LevelPlayableArtInitializer playableArtInitializer;
    /** Character identity -> that art bank's single authoritative DPLC owner. */
    private final java.util.Map<String, DynamicArtDecisionOwner> playerArtDplcOwners =
            new java.util.HashMap<>();
    private final LevelDirtyRegionDispatcher dirtyRegionDispatcher;
    private final LevelLostRingSpawnCoordinator lostRingSpawns = new LevelLostRingSpawnCoordinator(this);
    final LevelWaterCoordinator waterCoordinator;
    final LevelCheckpointCoordinator checkpointCoordinator;
    private final LevelActTransitionExecutor actTransitionExecutor;
    private final LevelSeamlessTransitionExecutor seamlessTransitionExecutor;
    private EngineContext engineServices; private EditorSaveManager editorSaveManager;

    /**
     * Constructs a LevelManager with explicit manager dependencies.
     * Used by session-owned gameplay-mode construction to inject peers instead of
     * reading from singletons.
     */
    public LevelManager(Camera camera, SpriteManager spriteManager,
                        ParallaxManager parallaxManager, CollisionSystem collisionSystem,
                        WaterSystem waterSystem, GameStateManager gameState,
                        EngineContext engineServices, WorldSession worldSession) {
        this.camera = camera;
        this.spriteManager = spriteManager;
        this.parallaxManager = parallaxManager;
        this.collisionSystem = collisionSystem;
        this.waterSystem = waterSystem;
        this.gameState = gameState;
        this.worldSession = worldSession;
        this.graphicsManager = engineServices.graphics();
        this.audioManager = engineServices.audio();
        this.configService = engineServices.configuration();
        this.overlayManager = engineServices.debugOverlay();
        this.profiler = engineServices.profiler();
        this.crossGameFeatures = engineServices.crossGameFeatures();
        this.engineServices = engineServices;
        this.playableArtInitializer = new LevelPlayableArtInitializer(
                this, spriteManager, graphicsManager, configService, crossGameFeatures);
        this.dirtyRegionDispatcher = new LevelDirtyRegionDispatcher(this);
        this.waterCoordinator = new LevelWaterCoordinator(this);
        this.checkpointCoordinator = new LevelCheckpointCoordinator(this);
        this.actTransitionExecutor = new LevelActTransitionExecutor(this);
        this.seamlessTransitionExecutor = new LevelSeamlessTransitionExecutor(
                this, transitions);
        this.cachedScreenWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
        this.cachedScreenHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
        // Inherit any zone/act metadata and loaded Level already on the
        // world session (e.g. after editor exit when WorldSession survives
        // but LevelManager is freshly constructed).
        this.currentZone = worldSession.getCurrentZone();
        this.currentAct = worldSession.getCurrentAct();
        this.apparentAct = worldSession.getApparentAct();
        this.level = worldSession.getCurrentLevel();
    }

    public void setEditorSaveManager(EditorSaveManager editorSaveManager) { this.editorSaveManager = editorSaveManager; }

    /**
     * Refreshes the zone list from the current GameModule's ZoneRegistry.
     * Called during level loading to ensure zones match the current game.
     */
    void refreshZoneList() {
        levels.clear();
        levels.addAll(gameModule.getZoneRegistry().getAllZones());
    }

    /**
     * Loads the specified level into memory.
     *
     * @param levelIndex the index of the level to load
     * @throws IOException if an I/O error occurs while loading the level
     */
    public void loadLevel(int levelIndex) throws IOException {
        loadLevel(levelIndex, LevelLoadMode.FULL);
    }

    /**
     * Loads the specified level into memory with explicit load mode.
     *
     * @param levelIndex the index of the level to load
     * @param loadMode   profile execution mode
     * @throws IOException if an I/O error occurs while loading the level
     */
    public void loadLevel(int levelIndex, LevelLoadMode loadMode) throws IOException {
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setLevelIndex(levelIndex);
        ctx.setLoadMode(loadMode);
        loadLevel(levelIndex, loadMode, ctx);
    }

    /**
     * Loads the specified level into memory with explicit load mode and context.
     * <p>
     * When the context has {@code includePostLoadAssembly} set, the profile will
     * include post-load steps (checkpoint restore, player spawn, camera, etc.).
     *
     * @param levelIndex the index of the level to load
     * @param loadMode   profile execution mode
     * @param ctx        pre-built context with checkpoint snapshot and spawn data
     * @throws IOException if an I/O error occurs while loading the level
     */
    public void loadLevel(int levelIndex, LevelLoadMode loadMode, LevelLoadContext ctx) throws IOException {
        discardInitialProcessSpritesLifecycle();
        try {
            ctx.resetInitialProcessSpritesRequestForLoadAttempt();
            GameModule module = activeGameModule();
            LevelInitProfile profile = module.getLevelInitProfile();
            ctx.setLevelIndex(levelIndex);
            ctx.setLoadMode(loadMode);

            List<InitStep> steps = profile.levelLoadSteps(ctx);
            if (steps.isEmpty()) {
                throw new IllegalStateException(
                    "No level load steps defined for " +
                    module.getClass().getSimpleName() +
                    ". All game modules must implement levelLoadSteps().");
            }
            for (InitStep step : steps) {
                long start = System.nanoTime();
                step.execute();
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                LOGGER.fine(() -> String.format("  [%s] %dms — %s", step.name(), elapsed, step.romRoutine()));
            }
            // The LoadLevelData step stores the result in ctx
            if (ctx.getLevel() != null) {
                writeCurrentLevel(ctx.getLevel());
            }
            markRewindLevelLoadBoundary();
            InitialProcessSpritesLifecycle requestedSetup =
                    ctx.requestedInitialProcessSpritesLifecycle();
            if (requestedSetup != InitialProcessSpritesLifecycle.NONE) {
                spriteManager.setFrameCounter(0);
            }
            publishInitialProcessSpritesLifecycle(requestedSetup);
            if (loadMode == LevelLoadMode.FULL) {
                completedProductionLoadGeneration = Math.incrementExact(
                        completedProductionLoadGeneration);
            }
        } catch (Exception e) {
            discardInitialProcessSpritesLifecycle();
            activeGameModule().getLevelInitProfile().cancelPendingLevelLoadWork();
            // Profile steps wrap checked exceptions in RuntimeException; unwrap if cause is IOException
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                LOGGER.log(SEVERE, "Failed to load level " + levelIndex, ioe);
                throw ioe;
            }
            LOGGER.log(SEVERE, "Unexpected error while loading level " + levelIndex, e);
            throw new IOException("Failed to load level due to unexpected error.", e);
        } finally {
            // The suppress flag belongs to this load only. A load that fails before
            // ScheduleLevelMusic — or a preview capture, which has no music step — must not
            // leave it latched for the next level, which would start that level silent.
            transitions.setSuppressNextMusicChange(false);
            levelEntryBegun = false;
        }
    }

    @Override
    protected void executeInitialProcessSprites() {
        new InitialProcessSpritesExecutor().execute(
                gameModule, spriteManager, objectManager, camera, zoneFeatureProvider, frameCounter);
    }

    static void markRewindLevelLoadBoundary() {
        GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null) {
            gameplayMode.markRewindBoundary(RewindBoundary.LEVEL_LOAD);
        }
    }

    /**
     * Phase A: Initialize ROM access, parallax, game module, and zone registry.
     */
    public void initGameModule(int levelIndex) throws IOException {
        Rom rom = GameServices.rom().getRom();
        parallaxManager.load(rom);
        gameModule = GameServices.module();
        collisionLayoutYMask = resolveCollisionLayoutYMask();
        refreshZoneList();
        game = gameModule.createGame(rom);
    }

    /**
     * Phase C/F: Configure audio manager and play level music.
     */
    public void initAudio(int levelIndex) throws IOException {
        // S1 EndingDemoLoad enters Level: with the credits track already playing;
        // that path contains no PlaySound command (sonic.asm:3823-3935). Rebuilding
        // the ROM/profile source would invalidate the active track even though the
        // following playlist request is suppressed.
        if (transitions.isSuppressNextMusicChange()) {
            return;
        }
        configureAudio();
        playLevelMusic(levelIndex);
    }

    /** Establishes the current game's production audio/request service. */
    public void configureAudio() throws IOException {
        // Profile installation builds the SMPS loader immediately. Install the
        // current ROM first so a reboot or ROM swap cannot use the closed one.
        audioManager.setRom(GameServices.rom().getRom());
        audioManager.setAudioProfile(gameModule.getAudioProfile());
        audioManager.setSoundMap(game.getSoundMap());
        audioManager.resetRingSound();
    }

    /**
     * Starts one ROM level-entry boundary. Results returns reach it before
     * their counted fade; the ordered load profile reaches it as fallback.
     */
    public void beginLevelEntry() {
        levelEntryBegun = LevelMusicCoordinator.beginEntry(levelEntryBegun,
                activeGameModule().getLevelInitProfile());
    }

    /** Submits the level's playlist request after game-owned entry commands. */
    public void playLevelMusic(int levelIndex) throws IOException {
        LevelMusicCoordinator.prepare(game, transitions, levelIndex)
                .ifPresent(this::publishPreparedLevelMusic);
    }

    /** Resolves the selected zone/act's playlist entry without publishing it. */
    public java.util.OptionalInt prepareCurrentLevelMusic() throws IOException {
        return LevelMusicCoordinator.prepareCurrent(game, transitions, resolveLevelData());
    }

    /** Re-publishes the current level request through the canonical owner. */
    public void playCurrentLevelMusic() {
        publishPreparedLevelMusic(getCurrentLevelMusicId());
    }

    /** Publishes a request the active game profile has released. */
    public void publishPreparedLevelMusic(int musicId) {
        LevelMusicCoordinator.publish(audioManager, musicId,
                activeGameModule().getLevelInitProfile());
    }

    /**
     * Phase A-C: Initialize game module, configure audio manager, and play level music.
     */
    public void initGameModuleAndAudio(int levelIndex) throws IOException {
        initGameModule(levelIndex);
        initAudio(levelIndex);
    }

    /**
     * Phase E-F: Delegate to Game.loadLevel(), cache level dimensions, and reset dirty flags.
     *
     * @return the loaded Level instance (also assigned to {@code this.level})
     */
    public Level loadLevelData(int levelIndex) throws IOException {
        discardPreparedLevelLoad();
        Level loaded = game.loadLevel(levelIndex);
        writeCurrentLevel(loaded);
        rebuildLevelDerivedState();
        return loaded;
    }

    private final LevelLoadPreparer loadPreparer = new LevelLoadPreparer();

    /**
     * Starts building the level for {@code zone}/{@code act} on the shared
     * preparer thread so a later seamless transition into it installs the
     * result instead of constructing it on the transition frame.
     *
     * <p>Call this from the transition owner at the point where the ROM itself
     * starts its own target-level resource work (for example when it queues the
     * target's Kos jobs), so the host-side build shares that existing wait. The
     * later install always joins the build; it never moves the install frame.
     *
     * @param mutationKey the seamless mutation the transition will apply after
     *                    the install, so the build can pre-apply its layout part
     *                    and the prebuilt tilemaps already match it
     * @return true when a build was started
     */
    public boolean prepareActTransitionLevelLoad(int zone, int act, String mutationKey) {
        if (!(game instanceof PreparableLevelLoader loader)) {
            return false;
        }
        if (zone < 0 || zone >= levels.size() || act < 0 || act >= levels.get(zone).size()) {
            return false;
        }
        int levelIndex = levels.get(zone).get(act).getLevelIndex();
        // Resolve gameplay configuration on the frame thread. The submitted task
        // owns its ROM and immutable load inputs, never a live feature provider.
        var task = loader.prepareLevelBuildTask(levelIndex, mutationKey);
        loadPreparer.prepare(loader, levelIndex, mutationKey, task);
        return true;
    }

    /** Drops any level build prepared for a transition that will no longer happen. */
    public void discardPreparedLevelLoad() {
        loadPreparer.discard();
    }

    /** Number of level installs served from a prepared build; test observability. */
    public int preparedLevelInstallCount() {
        return loadPreparer.installedFromPreparedCount();
    }

    private PreparedLevelBuild takePreparedLevelLoad(int levelIndex, String mutationKey) {
        return loadPreparer.take(game, levelIndex, mutationKey);
    }

    private void adoptPreparedTilemaps(PreparedLevelBuild prepared) {
        if (prepared == null || tilemapManager == null) {
            return;
        }
        // Wrapping policy belongs to the installed target and its live zone state.
        // Build on the frame thread, after installation; ROM decoding remains async.
        PrebuiltTilemaps tilemaps = LevelTilemapPrebuilder.build(
                level, graphicsManager, gameState, zoneFeatureProvider, currentZone,
                parallaxManager, verticalWrapEnabled);
        if (tilemaps != null) {
            tilemapManager.adoptPrebuiltTilemaps(tilemaps);
        }
    }

    public Level loadLevelData(
            int levelIndex,
            DeferredLevelResourceTracker deferredResources)
            throws IOException {
        discardPreparedLevelLoad();
        return loadActTransitionLevelData(levelIndex, deferredResources, null);
    }

    Level loadActTransitionLevelData(
            int levelIndex,
            DeferredLevelResourceTracker deferredResources,
            String mutationKey) throws IOException {
        DeferredLevelResourceTracker activeDeferredResources =
                deferredResources != null
                        ? deferredResources
                        : DeferredLevelResourceTracker.none();
        Level loaded;
        PreparedLevelBuild prepared = null;
        if (activeDeferredResources.hasExplicitPolicy()
                && game instanceof DeferredLevelResourceLoader loader) {
            discardPreparedLevelLoad();
            loaded = loader.loadLevelWithDeferredResources(
                    levelIndex, activeDeferredResources);
        } else {
            if (!activeDeferredResources.isEmpty()) {
                throw new IllegalStateException(
                        game.getIdentifier()
                                + " does not support deferred level resources");
            }
            prepared = takePreparedLevelLoad(levelIndex, mutationKey);
            loaded = prepared != null
                    ? ((PreparableLevelLoader) game).installPreparedLevel(prepared)
                    : game.loadLevel(levelIndex);
        }
        writeCurrentLevel(loaded);
        rebuildLevelDerivedState();
        adoptPreparedTilemaps(prepared);
        return loaded;
    }

    /**
     * Re-runs the post-load setup steps over the currently-loaded {@link Level}
     * (block dimensions, debug renderer, dimension cache, tilemap manager).
     * Used both by {@link #loadLevelData(int)} after a fresh ROM read and by
     * the editor mode exit path when LevelManager is freshly constructed but
     * inherits its Level from {@code WorldSession}. Safe to call multiple
     * times; each call rebuilds dependent state from {@code level}.
     */
    public void rebuildLevelDerivedState() {
        if (level == null) {
            return;
        }
        blockPixelSize = level.getBlockPixelSize();
        chunksPerBlockSide = level.getChunksPerBlockSide();
        debugRenderer = new LevelDebugRenderer(new LevelDebugContext(
                level, blockPixelSize, overlayManager, graphicsManager,
                cachedScreenWidth, cachedScreenHeight));
        cacheLevelDimensions();
        tilemapManager = new LevelTilemapManager(buildGeometry(), graphicsManager, gameState);
    }

    /**
     * Restores the read/render level view needed while editor mode is active
     * after gameplay mode teardown has reset gameplay-owned managers. This
     * intentionally rebuilds only level-derived rendering state; gameplay
     * object, ring, collision, and event systems are recreated on playtest
     * resume.
     */
    public void restoreEditorLevelView(Level editorLevel) {
        Level restoredLevel = editorLevel != null ? editorLevel : worldSession.getCurrentLevel();
        if (restoredLevel == null) {
            return;
        }
        writeCurrentLevel(restoredLevel);
        currentZone = worldSession.getCurrentZone();
        currentAct = worldSession.getCurrentAct();
        apparentAct = worldSession.getApparentAct();
        gameModule = worldSession.getGameModule();
        collisionLayoutYMask = resolveCollisionLayoutYMask();
        rebuildLevelDerivedState();
    }

    /**
     * Restores a loaded level inherited from {@code WorldSession} into a
     * freshly-constructed LevelManager — the path used after editor mode exit
     * when the gameplay mode is rebuilt around the surviving world
     * data. Re-runs the standard {@link #loadZoneAndAct(int, int)} flow to
     * orchestrate every gameplay subsystem (game module, audio, animated
     * content, objects, rings, zone features, art, water, etc.), then if the
     * inherited Level was a {@link MutableLevel}, swaps it back in via
     * {@link #setLevel(Level)} so any mutations made before editor entry
     * survive the round trip.
     * <p>
     * Used by the editor exit flow after the old gameplay mode has been
     * torn down and a fresh gameplay mode has been built over the surviving
     * {@code WorldSession}.
     */
    public void restoreInheritedLevel() throws IOException {
        Level inherited = level;
        if (inherited == null) {
            return;
        }
        int zone = currentZone;
        int act = currentAct;
        loadZoneAndAct(zone, act);
        if (inherited instanceof MutableLevel) {
            setLevel(inherited);
        }
    }

    /**
     * Phase E: Initialize animated pattern and palette managers for the loaded level.
     */
    public void initAnimatedContent() {
        initAnimatedPatterns();
        initAnimatedPalettes();
    }

    /**
     * Swaps the current level for a new one (e.g. a MutableLevel snapshot).
     * Re-initialises animated content managers so they read from the new
     * level's Pattern/Palette arrays, and invalidates the foreground tilemap
     * to trigger a full rebuild.
     *
     * @param newLevel the level to swap in
     */
    public void setLevel(Level newLevel) {
        writeCurrentLevel(newLevel);
        blockPixelSize = newLevel.getBlockPixelSize();
        chunksPerBlockSide = newLevel.getChunksPerBlockSide();
        cacheLevelDimensions();
        initAnimatedContent();
        if (tilemapManager != null) {
            tilemapManager.updateGeometry(buildGeometry());
            tilemapManager.invalidateAllTilemaps();
        } else {
            invalidateForegroundTilemap();
        }
    }

    /**
     * Processes dirty regions from a MutableLevel, dispatching incremental
     * updates to the relevant subsystems. This is a no-op when the current
     * level is not a MutableLevel, so there is zero performance impact on
     * normal gameplay.
     * <p>
     * Called from {@code LevelFrameStep} at the start of each frame.
     */
    public void processDirtyRegions() {
        dirtyRegionDispatcher.processDirtyRegions();
    }

    /**
     * Phase G: Create ObjectManager, TouchResponseTable, and wire CollisionSystem.
     */
    public void initObjectManager() throws IOException {
        Rom rom = GameServices.rom().getRom();
        RomByteReader romReader = RomByteReader.fromRom(rom);
        touchResponseTable = gameModule.createTouchResponseTable(romReader);
        objectManager = new ObjectManager(level.getObjects(),
                gameModule.createObjectRegistry(),
                gameModule.getPlaneSwitcherObjectId(),
                gameModule.getPlaneSwitcherConfig(),
                touchResponseTable,
                graphicsManager,
                camera,
                buildObjectServices());
        objectManager.initRingFloorCheckCounterPhase(ringFloorCheckCounterPhase);

        // S1 parity: counter-based respawn tracking DISABLED pending fix for
        // load/unload/reload incompatibility. The counter system prevents respawns
        // because forward and backward counters assign different respawn indices
        // to the same object. The ROM doesn't have this issue because ObjPosLoad
        // never unloads objects — they persist until their own code deletes them.
        // S1 counter-based respawn tracking.
        GameRules gameRules = gameModule.getRules();
        if (gameRules != null
                && gameRules.collision() != null
                && gameRules.collision().collisionModel() == com.openggf.game.CollisionModel.UNIFIED) {
            objectManager.enableCounterBasedRespawn();
        } else {
            objectManager.enableExecThenLoadPlacement();
            objectManager.enforceSlotLimit();
        }

        // S3K parity: ROM's Object_respawn_table bit 7 stays set permanently
        // after a player kill (sonic3k.asm loc_1BA40 / Touch_EnemyNormal). Match
        // by latching destroyedInWindow for the rest of the level.
        if (gameRules != null
                && gameRules.objectInteraction() != null
                && gameRules.objectInteraction().permanentRespawnTableLatch()) {
            objectManager.enablePermanentDestroyLatch();
        }

        // Wire up CollisionSystem with ObjectManager for unified collision pipeline
        collisionSystem.setObjectManager(objectManager);

        // Inject PowerUpSpawner into all playable sprites
        refreshPlayablePowerUpSpawners();
    }

    /**
     * Injects a {@link DefaultPowerUpSpawner} backed by the current
     * {@link ObjectManager} into the main player and all sidekicks.
     */
    String resolveMainCharacterCode() {
        return ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
    }

    /**
     * Rebinds the active playable roster to the current object manager's
     * power-up spawner.
     *
     * <p>The normal level-load path calls this when it creates the object
     * manager. Headless shared-level fixtures replace the sprite roster while
     * retaining that manager, so they call it again after registering the new
     * team. This keeps fixed shield objects and their ROM-allocated children on
     * the same lifecycle path as production gameplay.</p>
     */
    public void refreshPlayablePowerUpSpawners() {
        LevelManagerInitializationSupport.rebindPowerUpSpawners(
                objectManager, spriteManager, resolveMainCharacterCode());
    }

    /**
     * Phase G: Reset camera bounds and initialize object placement window.
     */
    public void initCameraBounds() {
        PersistentRespawnState persistentRespawnState = persistentRespawnStateForNextObjectReset;
        persistentRespawnStateForNextObjectReset = null;
        persistentRespawnStateForCameraSnap = persistentRespawnState;
        LevelManagerInitializationSupport.resetCameraBounds(
                camera, level, objectManager, persistentRespawnState);
    }

    /**
     * Arms a one-shot respawn-table restore for the next full object-system
     * initialization. The state is consumed inside {@link #initCameraBounds()},
     * after placement bookkeeping is reset and before any initial-window object
     * can be materialized. The post-load camera snap receives the same state
     * once more when the level's placement window is rebuilt there.
     */
    public void restorePersistentRespawnOnNextObjectReset(PersistentRespawnState state) {
        persistentRespawnStateForNextObjectReset = state;
    }

    /**
     * Phase G: Create ObjectManager, wire CollisionSystem, and reset camera bounds.
     * Also registers level and object-manager rewind adapters with the active
     * {@link com.openggf.game.session.GameplayModeContext} (if one exists).
     */
    public void initObjectSystem() throws IOException {
        initObjectManager();
        initCameraBounds();
        com.openggf.game.session.GameplayModeContext gameplayMode =
                com.openggf.game.session.SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null) {
            gameplayMode.registerLevelAdapters(this);
        }
    }

    /**
     * Phase H: Reset game-specific object state for the new level.
     */
    public void initGameplayState() {
        // Clear end-of-level flags left over from the previous act's results screen.
        // Without this, stale endOfLevelFlag=true persists across full zone transitions
        // (e.g. AIZ2 results → HCZ1 load), causing the next zone's act transition to
        // fire immediately when the BG event handler first checks the flag.
        GameServices.gameState().resetForLevel();
        gameModule.onLevelLoad();
    }

    /**
     * Phase H: Create RingManager and cache ring patterns.
     */
    public void initRings() {
        ringManager = LevelManagerInitializationSupport.initializeRings(this, touchResponseTable,
                audioManager, camera, graphicsManager, transitions.bigRingReturnRingStatusTable());
    }

    /**
     * Phase H: Initialize zone-specific features (CNZ bumpers, CPZ pylon, water surface, etc.).
     */
    public void initZoneFeatures() throws IOException {
        zoneFeatureProvider = gameModule.getZoneFeatureProvider();
        resetZoneScopedRegistriesForLevelLoad();
        applyLevelLoadPaletteOverrides();
        initializeZoneFeatureProvider(zoneFeatureProvider);
    }

    void reinitializeZoneFeaturesForActTransition() throws IOException {
        if (zoneFeatureProvider == null) {
            zoneFeatureProvider = gameModule.getZoneFeatureProvider();
        }
        resetZoneScopedRegistriesForLevelLoad();
        applyLevelLoadPaletteOverrides();
        initializeZoneFeatureProvider(zoneFeatureProvider);
    }

    void resetZoneScopedRegistriesForLevelLoad() {
        LevelZoneScopedRegistryResetter.reset();
    }

    private void applyLevelLoadPaletteOverrides() {
        if (game instanceof LevelLoadPaletteOverrideProvider provider && level != null) {
            provider.applyLevelLoadPaletteOverrides(level, currentZone, currentAct);
        }
    }

    private void initializeZoneFeatureProvider(ZoneFeatureProvider zoneFeatureProvider) throws IOException {
        Rom rom = GameServices.rom().getRom();
        SpecialRenderEffectRegistry specialRenderEffectRegistry = GameServices.specialRenderEffectRegistryOrNull();
        AdvancedRenderModeController advancedRenderModeController = GameServices.advancedRenderModeControllerOrNull();
        if (zoneFeatureProvider != null) {
            zoneFeatureProvider.reset();
            zoneFeatureProvider.initZoneFeatures(rom, getFeatureZoneId(), getFeatureActId(), camera.getX());
            // Cache zone feature patterns (water surface, etc.)
            int waterPatternBase = 0x30000; // High offset to avoid collision
            zoneFeatureProvider.ensurePatternsCached(graphicsManager, waterPatternBase);
            if (specialRenderEffectRegistry != null) {
                zoneFeatureProvider.registerSpecialRenderEffects(
                        specialRenderEffectRegistry, getFeatureZoneId(), getFeatureActId());
            }
            if (advancedRenderModeController != null) {
                zoneFeatureProvider.registerAdvancedRenderModes(
                        advancedRenderModeController, getFeatureZoneId(), getFeatureActId());
            }
        }
    }

    /**
     * Phase H: Reset game-specific state, create RingManager, and initialize zone features.
     */
    public void initGameState() throws IOException {
        initGameplayState();
        initRings();
        initZoneFeatures();
    }

    /**
     * Phase C: Load object art and player sprite art into the pattern atlas.
     */
    public void initArt() {
        initObjectArt();
        // Level_ClrRam zeroes the RAM block holding the player
        // last-loaded-DPLC registers on every level load, before the level's
        // players are created and their art primed (S2:
        // clearRAM Misc_Variables,Misc_Variables_End, with
        // Sonic_LastLoadedDPLC / Tails_LastLoadedDPLC /
        // TailsTails_LastLoadedDPLC inside it at
        // docs/s2disasm/s2.constants.asm:1484, 1556, 1625-1626, 1629; S1:
        // clearRAM v_levelvariables, docs/s1disasm/sonic.asm:2742, with
        // v_sonframenum inside it at docs/s1disasm/_Variables.asm:179, 230,
        // 301). A level entered after a special stage must therefore not dedup
        // its first player transfer against the mapping frame that stage left
        // in the shared register. Only the level-load phase does this;
        // refreshPlayableSpriteArt rebuilds renderers mid-gameplay, where no
        // clearRAM runs.
        var levelLoadLifecycle = GameServices.dynamicArtLifecycleOrNull();
        if (levelLoadLifecycle != null) {
            levelLoadLifecycle.clearPlayerDplcDedupRegistersForLevelLoad();
        }
        playableArtInitializer.initialize();
    }

    /**
     * Phase C: Reset player state, initialize checkpoint, and create level gamestate.
     */
    public void initPlayerAndCheckpoint() {
        resetPlayerState();
        checkpointCoordinator.prepareForLevelStart();
        levelGamestate = gameModule.createLevelState();
    }

    /**
     * Phase C: Load object art, player sprite art, reset player state,
     * and initialize checkpoint and level gamestate.
     */
    public void initArtAndPlayer() {
        initArt();
        initPlayerAndCheckpoint();
    }

    /**
     * Phase B: Initialize the water system for the current level.
     */
    public void initWater() throws IOException {
        waterCoordinator.initialize();
    }

    /**
     * Initialize the water system, with optional seamless-transition awareness.
     * ROM: CheckLevelForWater (sonic3k.asm:9754-9759) compares Apparent_zone_and_act
     * to Current_zone_and_act. During seamless transitions Apparent != Current,
     * which enables water in cases that a direct load would disable (AIZ2 Knuckles).
     *
     * @param seamlessTransition true when called during a seamless act transition
     */
    void initWater(boolean seamlessTransition) throws IOException {
        waterCoordinator.initialize(seamlessTransition);
    }

    /**
     * Engine-specific: Pre-allocate BG FBO at the maximum required size.
     */
    public void initBackgroundRenderer() {
        // Pre-allocate the background FBO at maximum required size to avoid
        // mid-frame GPU reallocation hitches (e.g., AIZ intro ocean->beach transition)
        BackgroundRenderer bgRenderer = graphicsManager.getBackgroundRenderer();
        if (bgRenderer != null && bgRenderer.isInitialized()) {
            int maxBgWidth;
            if (zoneFeatureProvider != null && !zoneFeatureProvider.bgWrapsHorizontally()) {
                // S3K uses full-width BG data (e.g., AIZ intro ocean-to-beach transition)
                maxBgWidth = Math.max(cachedScreenWidth, getLayerLevelWidthPx((byte) 1));
            } else {
                // S1/S2 use VDP-width (512px) background periods.
                // Pre-allocating to full level width can exceed GPU max texture size
                // (S2: 128 blocks * 128px = 16384, right at GPU limit).
                maxBgWidth = Math.max(cachedScreenWidth, LevelTilemapManager.VDP_BG_PLANE_WIDTH_PX);
            }
            int fboHeight = 256 + LevelConstants.CHUNK_HEIGHT;
            graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM,
                    (cx, cy, cw, ch) -> bgRenderer.ensureCapacity(maxBgWidth, fboHeight)));
        }
    }

    /**
     * Updates object positions before player physics.
     * This must be called BEFORE spriteManager.update() so that SolidContacts
     * sees the current frame's platform positions, fixing 1-frame lag on
     * fast-moving platforms (SwingingPlatform, CNZ Elevators).
     *
     * <p>Update order is critical:
     * <ol>
     *   <li>OscillationManager - oscillation values first</li>
     *   <li>objectManager - platforms read oscillation, move to new positions</li>
     *   <li>spriteManager - SolidContacts now sees updated positions</li>
     * </ol>
     */
    public void updateObjectPositions() {
        if (objectManager != null) {
            Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
            AbstractPlayableSprite playable = player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;
            List<AbstractPlayableSprite> sidekicks = spriteManager.getSidekicks();
            objectManager.update(camera.getX(), playable, sidekicks, frameCounter);
        }

        // ROM parity: OscillateNumDo runs AFTER ExecuteObjects in both S1
        // (sonic.asm:3205→3223) and S2 (s2.asm:5091→5104). Objects must read
        // the previous frame's oscillation values, then OscillateNumDo advances
        // them for the next frame.
        advanceGlobalOscillation();
    }

    /**
     * Returns true when the active module executes objects after player physics and
     * solid checkpoints are resolved during object execution. Driven by the
     * {@link ObjectInteractionRules#objectsExecuteAfterPlayerPhysics()} flag — independent
     * of collision model so S1 (UNIFIED) and S2/S3K (DUAL_PATH) can share the
     * post-physics ordering per the 2026-04-18-solid-ordering-rom-accuracy plan.
     */
    public boolean objectsExecuteAfterPlayerPhysics() {
        GameModule activeModule = activeGameModule();
        if (activeModule == null
                || activeModule.getRules() == null
                || activeModule.getRules().objectInteraction() == null) {
            return false;
        }
        return activeModule.getRules().objectInteraction().objectsExecuteAfterPlayerPhysics();
    }

    /**
     * Returns true when the active module advances the dynamic water level (move
     * toward target) BEFORE the player's underwater check, matching S1/S2 ROM
     * order ({@code LZWaterFeatures}/{@code WaterEffects} run before
     * {@code ExecuteObjects}/{@code RunObjects}). S3K returns false because
     * {@code Process_Sprites} runs before {@code Handle_Onscreen_Water_Height},
     * so the player reads the previous frame's water level there. Driven by the
     * {@link CollisionRules#advanceWaterLevelBeforePlayerPhysics()} flag.
     */
    public boolean advanceWaterLevelBeforePlayerPhysics() {
        GameModule activeModule = activeGameModule();
        if (activeModule == null
                || activeModule.getRules() == null
                || activeModule.getRules().collision() == null) {
            return false;
        }
        return activeModule.getRules().collision().advanceWaterLevelBeforePlayerPhysics();
    }

    /**
     * Advances the dynamic water level (move toward target) for the current
     * level. Extracted from {@link #update()} so the inline-order path can run it
     * BEFORE the player physics step when
     * {@link #advanceWaterLevelBeforePlayerPhysics()} is set, matching ROM order
     * where {@code v_waterpos2}/{@code Water_Level} is moved before the player's
     * {@code Sonic_Water} underwater check. This relocates only the level MOVE;
     * the per-act target ({@code DynWaterHeight}) is still set by the zone
     * feature provider in {@link #update()}.
     */
    public void advanceDynamicWaterLevel() {
        waterCoordinator.advanceDynamicWaterLevel();
    }

    /**
     * Run touch responses for a single player. Called from tickPlayablePhysics
     * after handleMovement but before post-movement solid contacts, matching
     * the ROM's ReactToItem timing within ExecuteObjects.
     */
    public void applyTouchResponses(PlayableEntity player) {
        if (objectManager != null) {
            objectManager.runTouchResponsesForPlayer(
                    player,
                    frameCounter,
                    objectsExecuteAfterPlayerPhysics());
        }
        if (ringManager != null && player instanceof AbstractPlayableSprite playable && !playable.getDead()) {
            ringManager.collectAttractedRing(playable, frameCounter);
            ringManager.attractStageRings(playable);
            if (!ringManager.usesObjectTouchCollection()) {
                ringManager.collectStageRings(playable, frameCounter);
            }
            // Lost (spilled) ring collection now runs through the unified slot-ordered touch
            // loop in ObjectManager.runTouchResponsesForPlayer (above) via the type-keyed
            // LostRingObjectInstance branch — see ObjectManager Touch_ChkValue lost-ring gate.
            // The legacy RingManager.checkLostRingCollection scan has been removed.
        }
    }

    /**
     * Refreshes object touch snapshots before inline-order player physics runs.
     * This keeps ReactToItem aligned to the current frame's pre-object-update state.
     */
    public void prepareTouchResponseSnapshots() {
        if (objectManager != null) objectManager.snapshotTouchResponseState(touchResponseUsesPreviousCollisionResponseList());
        if (ringManager != null) ringManager.prepareAttractedRingTouchSnapshot();
    }

    private boolean touchResponseUsesPreviousCollisionResponseList() {
        GameModule activeModule = activeGameModule();
        return activeModule != null
                && activeModule.getRules() != null
                && activeModule.getRules().objectInteraction() != null
                && activeModule.getRules().objectInteraction().touchResponseUsesPreviousCollisionResponseList();
    }

    /**
     * Advances object streaming/execution without any touch responses.
     * Used by non-interactive ending demo preroll phases so objects can become
     * visible and animate without hurting/collecting from the frozen player.
     */
    public void updateObjectPositionsWithoutTouches() {
        updateObjectPositionsWithoutTouches(true);
    }

    /**
     * Advances object execution, optionally leaving the global oscillator for
     * the canonical loop tail. The latter is needed when ScreenEvents can
     * request a restart after Process_Sprites: the ROM skips OscillateNumDo on
     * that row along with the rest of the loop tail.
     */
    public void updateObjectPositionsWithoutTouches(boolean advanceOscillation) {
        if (objectManager != null) {
            Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
            AbstractPlayableSprite playable = player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;

            List<AbstractPlayableSprite> sidekicks = spriteManager.getSidekicks();
            objectManager.update(camera.getX(), playable, sidekicks, frameCounter, false);
        }

        // ROM parity: OscillateNumDo runs AFTER ExecuteObjects in both S1
        // (sonic.asm:3205→3223) and S2 (s2.asm:5091→5104). Objects must read
        // the previous frame's oscillation values, then OscillateNumDo advances
        // them for the next frame. Placing this call before objectManager.update()
        // caused a 1-frame phase shift in oscillating platform positions.
        if (advanceOscillation) {
            advanceGlobalOscillation();
        }
    }

    /**
     * Runs legacy post-player object hooks after the playable step has completed.
     * This is for ROM behaviors where later SST slots read Sonic's current
     * post-movement state and write globals for the following frame.
     */
    public void updateObjectPostPlayerHooks() {
        if (objectManager == null) {
            return;
        }
        Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
        AbstractPlayableSprite playable =
                player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;
        objectManager.runPostPlayerHooks(playable, frameCounter);
    }

    /**
     * Runs object execution after player physics with inline solid resolution.
     * Used by inline-order modules, where the ROM executes the player slot first,
     * then processes solid objects in slot order against the player's already-moved state.
     */
    public void updateObjectPositionsPostPhysicsWithoutTouches() {
        updateObjectPositionsPostPhysicsWithoutTouches(null);
    }

    public void updateObjectPositionsPostPhysicsWithoutTouches(Runnable afterExecBeforePlacement) {
        updateObjectPositionsPostPhysicsWithoutTouches(afterExecBeforePlacement, true);
    }

    /**
     * Executes the post-physics object pass, optionally deferring the global
     * oscillator until the canonical level-loop tail.
     */
    public void updateObjectPositionsPostPhysicsWithoutTouches(
            Runnable afterExecBeforePlacement, boolean advanceOscillation) {
        if (objectManager != null) {
            Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
            AbstractPlayableSprite playable = player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;
            List<AbstractPlayableSprite> sidekicks = spriteManager.getSidekicks();
            objectManager.update(camera.getX(), playable, sidekicks, frameCounter,
                    false, true, true, afterExecBeforePlacement);
        }

        // ROM parity: objects read the previous frame's oscillation values, then
        // OscillateNumDo advances them for the next frame after ExecuteObjects.
        if (advanceOscillation) {
            advanceGlobalOscillation();
        }
    }

    /**
     * Requests that the next {@link #advanceGlobalOscillation()} call skip the
     * global-oscillator advance, matching the ROM title-card wait loops which
     * run {@code RunObjects} but not {@code OscillateNumDo} (the oscillator only
     * advances inside {@code Level_MainLoop}; docs/s2disasm/s2.asm:5108 vs the
     * wait loops at s2.asm:4914-4924 / 5060-5066, and docs/s1disasm/sonic.asm
     * where {@code OscillateNumDo} at 3030 is outside {@code Level_TtlCardLoop}
     * 2811-2839). Called by {@code GameLoop.updateTitleCardMode} for each locked
     * title-card object pass so the oscillator holds at its {@code OscillateNumInit}
     * baseline until gameplay unlocks. Without it, every locked title-card frame
     * over-advances the global oscillator, phase-offsetting oscillation-driven
     * moving platforms (e.g. Obj18) when control returns.
     */
    public void suppressGlobalOscillationForTitleCardPass() {
        this.suppressGlobalOscillationForTitleCardPass = true;
    }

    /** Advances the native VBlank clock while S3K dispatches title-card SSTs. */
    public void advanceTitleCardVblankOnly() {
        if (objectManager != null) {
            // V-blank-only row: see the exactly-one-tick-per-serviced-V-blank invariant on ObjectManager.vblaCounter.
            objectManager.advanceVblaCounter();
        }
    }

    /** Advances the global oscillator at the canonical level-loop tail. */
    public void advanceGlobalOscillationAtLevelLoopTail() {
        // LevelFrameStep calls this before update() increments frameCounter.
        // OscillateNumDo runs after the current Level_frame_counter tick, so
        // publish the phase for the frame that the following object pass will
        // consume rather than reusing the completed frame's counter. This is
        // the ROM order in LevelLoop (sonic3k.asm:7889, 7928-7931): the next
        // Process_Sprites pass reads the table after this tail update.
        //
        // OscillateNumDo is reached ONLY from the main level loop in all three
        // games -- S1 Level_MainLoop (sonic.asm:3033), S2 Level_MainLoop
        // (s2.asm:5108) and S3K Level_MainLoop (sonic3k.asm:7909) -- while
        // OscillateNumInit runs once during level init (S1 sonic.asm:2916,
        // S2 s2.asm:4999). The title-card / level-load sequence never reaches
        // that loop tail, so its passes must not tick the oscillators. The
        // title-card lifecycle already raises the one-shot suppression flag for
        // each of those passes; this tail is the second implementation of the
        // same OscillateNumDo contract and has to honour it exactly as
        // advanceGlobalOscillation() does. Without it every title-card frame
        // advanced the global table, so the level began that many frames out of
        // phase (S2 EHZ1 star-post re-entry: 128 title-card passes, leaving the
        // Obj18 subtype-2 vertical platform at x=$07C0 at the opposite end of
        // its travel and catching a player the ROM lets fall past).
        if (suppressGlobalOscillationForTitleCardPass) {
            suppressGlobalOscillationForTitleCardPass = false;
            return;
        }
        OscillationManager.update(frameCounter);
    }

    void advanceGlobalOscillation() {
        if (suppressGlobalOscillationForTitleCardPass) {
            suppressGlobalOscillationForTitleCardPass = false;
            return;
        }
        int featureZone = getFeatureZoneId();
        int featureAct = getFeatureActId();
        if (zoneFeatureProvider != null
                && !zoneFeatureProvider.shouldAdvanceGlobalOscillation(featureZone, featureAct)) {
            return;
        }
        OscillationManager.update(frameCounter);
    }

    /**
     * Post-camera object placement catch-up: runs the placement window update
     * using the current (post-camera-update) camera position.
     * <p>
     * ROM parity: {@code ObjPosLoad} runs <b>after</b> {@code DeformLayers}
     * (camera update), using the post-camera position. The main placement pass
     * inside {@code ObjectManager.update()} uses the pre-camera position;
     * this call closes the gap when the camera advance crosses a chunk boundary.
     * <p>
     * The Placement class short-circuits when the camera chunk hasn't changed,
     * so this is a no-op on most frames. When the camera has crossed a chunk
     * boundary, the placement's active set is updated to include newly-windowed
     * spawns. On the next frame, {@code syncActiveSpawns()} creates instances.
     */
    public void postCameraObjectPlacementSync() {
        if (objectManager != null) {
            objectManager.postCameraPlacementUpdate(camera.getX());
        }
    }

    public void refreshObjectPostCameraRenderState() {
        if (objectManager != null) {
            objectManager.refreshPostCameraRenderState();
        }
    }

    /**
     * Advances zone scroll handlers that own foreground camera movement.
     *
     * @return true when the normal player-follow camera step should be skipped
     */
    public boolean advanceCameraDrivenScrollForFrame() {
        return parallaxManager != null
                && parallaxManager.advanceCameraDrivenScroll(currentZone, currentAct, camera, frameCounter);
    }

    /**
     * Runs pre-physics zone feature updates (e.g., LZ water slides and wind tunnels).
     *
     * <p>ROM order: {@code LZWaterFeatures} runs before {@code ExecuteObjects},
     * so water slides set {@code f_slidemode} and {@code obInertia} before
     * {@code Sonic_Move} executes. This method must be called before
     * {@code spriteManager.update()} to match that ordering.
     */
    public void updateZoneFeaturesPrePhysics() {
        if (zoneFeatureProvider != null && level != null) {
            Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
            AbstractPlayableSprite playable = player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;
            zoneFeatureProvider.updatePrePhysics(playable, camera.getX(), getFeatureZoneId());
        }
    }

    public void updateZoneFeaturesAfterPlayablePhysics(AbstractPlayableSprite playable) {
        if (zoneFeatureProvider != null && level != null && playable != null) {
            playable.capturePreZoneFeatureSnapshot();
            zoneFeatureProvider.updateAfterPlayablePhysics(playable, camera.getX(), getFeatureZoneId());
        }
    }

    /**
     * Runs zone-event routines whose ROM owner is after the complete object pass.
     * The native player slots are visited before the sidekick slot, matching the
     * S3K {@code sub_714E} / {@code sub_71E4} dispatch order.
     */
    public void updateZoneFeaturesAfterObjectExecution() {
        if (zoneFeatureProvider == null || level == null || spriteManager == null) {
            return;
        }
        int cameraX = camera.getX();
        int zoneIndex = getFeatureZoneId();
        AbstractPlayableSprite main = spriteManager.getMainPlayable();
        if (main != null) {
            zoneFeatureProvider.updateAfterObjectExecution(main, cameraX, zoneIndex);
        }
        for (AbstractPlayableSprite sidekick : spriteManager.getRegisteredSidekicks()) {
            if (sidekick != null && sidekick != main) {
                zoneFeatureProvider.updateAfterObjectExecution(sidekick, cameraX, zoneIndex);
            }
        }
    }

    /**
     * Advances {@code Level_frame_counter} at the ROM's own point in the loop:
     * immediately after the V-blank wait and before the object pass, so every
     * routine that runs this frame reads the already-incremented value.
     *
     * <p>All three games place the increment there --
     * {@code addq.w #1,(v_framecount).w} after {@code WaitForVBlank}
     * ({@code docs/s1disasm/sonic.asm:3001-3006}),
     * {@code addq.w #1,(Level_frame_counter).w} after {@code WaitForVint}
     * ({@code docs/s2disasm/s2.asm:5090-5094}) and after {@code Wait_VSync}
     * ({@code docs/skdisasm/sonic3k.asm:7919-7925}) -- in each case before
     * {@code ExecuteObjects} / {@code RunObjects} / {@code Process_Sprites}.
     */
    public void advanceLevelFrameCounter() {
        frameCounter++;
    }

    public void update() {
        // NOTE: OscillationManager and objectManager are now updated via updateObjectPositions()
        // which is called earlier in GameLoop to fix platform riding sync (1-frame lag fix).

        lostRingSpawns.processPending();

        Sprite player = null;
        AbstractPlayableSprite playable = null;
        boolean needsPlayer = ringManager != null || zoneFeatureProvider != null || levelGamestate != null;
        if (needsPlayer) {
            player = spriteManager.getSprite(resolveMainCharacterCode());
            playable = player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;
        }
        if (ringManager != null) {
            ringManager.update(camera.getX(), playable, frameCounter + 1, false);
            // Per-ring spilled-ring physics now runs in the object exec loop
            // (LostRingObjectInstance); this only advances the shared decelerating
            // spin once per frame (ROM ChangeRingFrame / Ring_spill_anim_*).
            ringManager.updateLostRingPhysics(frameCounter + 1);
        }
        // Water movement — ROM order: MoveWater (move toward target) runs BEFORE
        // DynWaterHeight (zone features set new target for next frame).
        // Use effective feature zone/act so S1 SBZ3 (loaded from LZ act 4 slot)
        // resolves to SBZ3 water behavior while retaining LZ tile/object resources.
        waterCoordinator.advanceDynamicWaterLevelAfterPlayerPhysicsIfNeeded();

        // Update zone-specific features (CNZ bumpers, S1 DynWaterHeight, etc.)
        if (zoneFeatureProvider != null && level != null) {
            zoneFeatureProvider.update(playable, camera.getX(), getFeatureZoneId());
        }
        if (levelGamestate != null) {
            if (!isHudSuppressed()) {
                levelGamestate.update();
            }
            if (levelGamestate.isTimeOver() && playable != null && !playable.getDead()) {
                playable.applyHurtOrDeath(0, DamageCause.TIME_OVER, false);
            }
        }

        frameRuntimeUpdater.updateParallaxAndAnimatedContent();

        // Legacy object-before-physics modules update playable water state here.
        // Inline-order modules run this immediately after the player slot in
        // LevelFrameStep, before ExecuteObjects, matching S3K's Sonic_Water /
        // Tails_Water ordering.
        if (!objectsExecuteAfterPlayerPhysics()) {
            updatePlayableWaterStatesForCurrentLevel();
        }
    }

    /**
     * Advances non-player scene systems for ending-demo preroll phases.
     * Keeps water and zone features in sync while player physics/input are frozen.
     */
    public void updateEndingDemoScene() {
        Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
        AbstractPlayableSprite playable = player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;

        if (ringManager != null) {
            ringManager.update(camera.getX(), null, frameCounter + 1);
        }

        // Water movement before zone features (ROM order: MoveWater before DynWaterHeight)
        waterCoordinator.advanceDynamicWaterLevel();

        if (zoneFeatureProvider != null && level != null) {
            zoneFeatureProvider.update(playable, camera.getX(), getFeatureZoneId());
        }

        updatePlayableWaterStatesForCurrentLevel();
    }

    public void updatePlayableWaterStatesForCurrentLevel() {
        waterCoordinator.updatePlayableWaterStatesForCurrentLevel();
    }

    public void updatePlayableWaterStateForCurrentLevel(AbstractPlayableSprite playable) {
        waterCoordinator.updatePlayableWaterStateForCurrentLevel(playable);
    }

    boolean shouldSuppressUnderwaterPalette(int zoneId, int actId) {
        return waterCoordinator.shouldSuppressUnderwaterPalette(zoneId, actId);
    }

    public void applyPlaneSwitchers(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        // Object-backed S2/S3K switches execute from their own SST slots.
        // This player-slot hook remains for module-owned switching such as S1 loops.
        GameModule module = activeGameModule();
        if (module != null) {
            module.applyPlaneSwitching(player);
        }
    }

    public LevelState getLevelGamestate() {
        return levelGamestate;
    }

    /**
     * Replaces the current level gamestate with a fresh instance.
     * Used by non-seamless S3K act transitions where acts share level data
     * and no level reload occurs. The results screen calls this to reset
     * timer and rings for the new act.
     */
    public void resetLevelGamestate(LevelState newState) {
        this.levelGamestate = newState;
    }

    /**
     * Rebuilds playable sprite renderers after scripts add a sidekick during
     * gameplay, such as MGZ2's boss-transition Tails rescue object.
     */
    public void refreshPlayableSpriteArt() {
        playableArtInitializer.initialize();
    }

    /**
     * ROM player-art DPLC banks are keyed by the character that owns the bank,
     * not by who submits into it. {@code LoadSonicDynPLC_Part2} /
     * {@code LoadTailsDynPLC_Part2} are shared entry points that any code may
     * jump into with a DPLC frame index in d0
     * (docs/s2disasm/s2.asm:38829-38862, 41659-41697), and they dedupe against
     * the single {@code Sonic_LastLoadedDPLC} / {@code Tails_LastLoadedDPLC}
     * word (docs/s2disasm/s2.asm:26039-26041). Object code that submits into a
     * character bank — the Tornado pilot, {@code ObjB2_Animate_Pilot}
     * (docs/s2disasm/s2.asm:79538-79565) — must therefore share the same owner
     * as the playable, so this registry hands out the bank's one authoritative
     * owner rather than minting a second dedupe state.
     */
    public DynamicArtDecisionOwner playerArtDplcOwner(String character) {
        if (character == null) {
            return null;
        }
        String key = character.toLowerCase(java.util.Locale.ROOT);
        DynamicArtDecisionOwner owner = playerArtDplcOwners.get(key);
        if (owner == null && !playerArtDplcOwners.containsKey(key)) {
            owner = playableArtInitializer.createAbsentCharacterBankOwner(key);
            playerArtDplcOwners.put(key, owner);
        }
        return owner;
    }

    void registerPlayerArtDplcOwner(String character, DynamicArtDecisionOwner owner) {
        if (character == null) {
            return;
        }
        String key = character.toLowerCase(java.util.Locale.ROOT);
        if (owner == null) {
            playerArtDplcOwners.remove(key);
        } else {
            playerArtDplcOwners.put(key, owner);
        }
    }

    void clearPlayerArtDplcOwners() {
        playerArtDplcOwners.clear();
    }

    /**
     * Computes the running VRAM bank offset for each sidekick within SIDEKICK_PATTERN_BASE.
     * Every sidekick unconditionally gets its own isolated bank — no name-based slot
     * optimization (which missed ART_TILE collisions like Knuckles/Sonic sharing 0x0680).
     *
     * @param bankSizes the bank size of each sidekick's art set, in order
     * @return list of offsets (one per sidekick) within SIDEKICK_PATTERN_BASE
     */
    public static List<Integer> computeSidekickBankOffsets(List<Integer> bankSizes) {
        return LevelPlayableArtInitializer.computeSidekickBankOffsets(bankSizes);
    }

    /**
     * Reserves an isolated virtual pattern bank from the sidekick DPLC range
     * without registering a gameplay sidekick. Render-only systems such as
     * trace ghosts use this to avoid corrupting real player/sidekick DPLC state.
     */
    public int reserveSidekickPatternBank(int bankSize) {
        return playableArtInitializer.reserveSidekickPatternBank(bankSize);
    }

    private void resetPlayerState() {
        Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
        if (player instanceof AbstractPlayableSprite playable) {
            playable.resetState();
        }
        for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
            sidekick.resetState();
            if (sidekick.getCpuController() != null) {
                sidekick.getCpuController().reset();
            }
        }
    }

    private void initObjectArt() {
        PatternAtlas patternAtlas = graphicsManager.getPatternAtlas();
        if (patternAtlas != null) {
            patternAtlas.clearRanges();
        }
        ObjectArtProvider provider = gameModule != null ? gameModule.getObjectArtProvider() : null;
        if (provider == null) {
            objectRenderManager = null;
            return;
        }

        try {
            int zoneIndex = level != null ? level.getZoneIndex() : -1;
            provider.loadArtForZone(zoneIndex);

            objectRenderManager = new ObjectRenderManager(provider);
            // Register level-tile-based object art (must be after level load)
            provider.registerLevelTileArt(level, zoneIndex);
            if (patternAtlas != null) {
                patternAtlas.registerRange(PatternAtlasRange.OBJECTS);
            }
            LOGGER.info("Initializing Object Art. Base Index: " + OBJECT_PATTERN_BASE);
            refreshObjectArtPatterns();

            hudRenderManager = new HudRenderManager(graphicsManager, camera, gameState);
            hudRenderManager.setHudPalettes(provider.getHudTextPaletteLine(), provider.getHudFlashPaletteLine());
            // Wire up HUD to unified UI render pipeline
            if (graphicsManager.getUiRenderPipeline() != null) {
                graphicsManager.getUiRenderPipeline().setHudRenderManager(hudRenderManager);
            }

            // HUD uses a fixed pattern base to avoid collisions with dynamically registered object sheets
            int hudBaseIndex = HUD_PATTERN_BASE;
            Pattern[] hudDigits = provider.getHudDigitPatterns();
            if (hudDigits != null) {
                LOGGER.info("Cached " + hudDigits.length + " HUD Digit patterns at index " + hudBaseIndex);
                for (int i = 0; i < hudDigits.length; i++) {
                    graphicsManager.cachePatternTexture(hudDigits[i], hudBaseIndex + i);
                }
                hudRenderManager.setDigitPatternIndex(hudBaseIndex);

                int nextHudIndex = hudBaseIndex + hudDigits.length;
                HudStaticArt staticHudArt = provider.getHudStaticArt();
                if (staticHudArt != null && staticHudArt.patterns() != null) {
                    LOGGER.info("Cached " + staticHudArt.patterns().length
                            + " HUD Static patterns at index " + nextHudIndex);
                    for (int i = 0; i < staticHudArt.patterns().length; i++) {
                        graphicsManager.cachePatternTexture(staticHudArt.patterns()[i], nextHudIndex + i);
                    }
                    hudRenderManager.setStaticHudArt(nextHudIndex, staticHudArt);
                    hudRenderManager.setLivesPaletteOverrideSupplier(provider::getHudLivesPaletteOverride);
                    nextHudIndex += staticHudArt.patterns().length;
                }

                Pattern[] hudLivesNumbers = provider.getHudLivesNumbers();
                if (hudLivesNumbers != null) {
                    LOGGER.info("Cached " + hudLivesNumbers.length + " HUD Lives Numbers patterns at index "
                            + nextHudIndex);
                    for (int i = 0; i < hudLivesNumbers.length; i++) {
                        graphicsManager.cachePatternTexture(hudLivesNumbers[i], nextHudIndex + i);
                    }
                    hudRenderManager.setLivesNumbersPatternIndex(nextHudIndex);
                    nextHudIndex += hudLivesNumbers.length;
                }

                Pattern[] hudHexDigits = provider.getHudHexDigitPatterns();
                if (hudHexDigits != null) {
                    LOGGER.info("Cached " + hudHexDigits.length + " HUD Hex Digit patterns at index "
                            + nextHudIndex);
                    for (int i = 0; i < hudHexDigits.length; i++) {
                        graphicsManager.cachePatternTexture(hudHexDigits[i], nextHudIndex + i);
                    }
                    hudRenderManager.setHexDigitsPatternIndex(nextHudIndex);
                }
            }

        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load object art.", e);
            objectRenderManager = null;
        }
        com.openggf.game.session.GameplayModeContext gameplayMode =
                com.openggf.game.session.SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null && provider != null) {
            gameplayMode.registerPlcArtAdapter(provider);
        }
    }

    /**
     * Validates and caches the regular object-art allocation within its fixed
     * virtual-pattern governance range.
     *
     * @return the first virtual pattern index after the cached regular object art
     */
    public int refreshObjectArtPatterns() {
        if (objectRenderManager == null) {
            throw new IllegalStateException("Object render manager is not initialized");
        }
        int count = objectRenderManager.getRegularPatternCount();
        if (count < 0) {
            throw new IllegalStateException("Invalid regular object pattern count: " + count);
        }
        if (count > PatternAtlasRange.OBJECTS.size()) {
            throw new IllegalStateException("Object patterns exceed reserved atlas range: " + count);
        }
        int prospectiveEnd = Math.addExact(OBJECT_PATTERN_BASE, count);
        int actualEnd = objectRenderManager.ensurePatternsCached(graphicsManager, OBJECT_PATTERN_BASE);
        if (actualEnd != prospectiveEnd) {
            throw new IllegalStateException("Object pattern preflight/cache mismatch: "
                    + prospectiveEnd + " != " + actualEnd);
        }
        return actualEnd;
    }

    boolean isHudSuppressed() {
        return transitions.isForceHudSuppressed()
                || (zoneFeatureProvider != null
                    && zoneFeatureProvider.shouldSuppressHud(currentZone, currentAct));
    }

    private void initAnimatedPatterns() {
        animatedPatternManager = null;
        if (!(game instanceof AnimatedPatternProvider provider)) {
            return;
        }
        try {
            animatedPatternManager = provider.loadAnimatedPatternManager(level, level.getZoneIndex());
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load animated patterns.", e);
            animatedPatternManager = null;
        }
        com.openggf.game.session.GameplayModeContext gameplayMode =
                com.openggf.game.session.SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null) {
            gameplayMode.registerPatternAnimatorAdapter(animatedPatternManager);
        }
    }

    private void initAnimatedPalettes() {
        animatedPaletteManager = null;
        if (!(game instanceof AnimatedPaletteProvider provider)) {
            return;
        }
        try {
            animatedPaletteManager = provider.loadAnimatedPaletteManager(level, level.getZoneIndex());
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load animated palettes.", e);
            animatedPaletteManager = null;
        }
    }

    /**
     * Debug Functionality to print each pattern to the screen.
     */
    public void drawAllPatterns() {
        if (debugRenderer != null) {
            debugRenderer.drawAllPatterns();
        }
    }

    /**
     * Renders the current level by processing and displaying collision data.
     * This is currently for debugging purposes to visualize collision areas.
     */
    public void draw() {
        drawWithSpritePriority(null, true);
    }

    public record LevelRenderOptions(boolean includePlayerSprites,
                                     boolean includeObjectSprites,
                                     boolean includeRings,
                                     boolean includeHud,
                                     boolean includeDebugOverlays,
                                     boolean includeObjectArtViewer,
                                     boolean includeWaterSurface) {
        public static LevelRenderOptions gameplay() {
            return new LevelRenderOptions(true, true, true, true, true, true, true);
        }

        public static LevelRenderOptions tilesOnly() {
            return new LevelRenderOptions(false, false, false, false, false, false, false);
        }

        public static LevelRenderOptions previewCapture() {
            return new LevelRenderOptions(false, true, false, false, false, false, false);
        }

        public boolean hasGameplayPass() {
            return includePlayerSprites || includeObjectSprites || includeRings;
        }
    }

    public void drawWithSpritePriority(SpriteManager spriteManager) {
        drawWithSpritePriority(spriteManager, true);
    }

    public void drawWithSpritePriority(SpriteManager spriteManager, boolean includeSpritePass) {
        drawWithRenderOptions(spriteManager,
                includeSpritePass ? LevelRenderOptions.gameplay() : LevelRenderOptions.tilesOnly());
    }

    public void drawWithRenderOptions(SpriteManager spriteManager, LevelRenderOptions renderOptions) {
        levelRenderer.drawWithRenderOptions(spriteManager, renderOptions);
    }

    /**
     * Renders the shared sprite/object gameplay pass used after tile rendering.
     * Delegates to {@link LevelRenderer}.
     */
    public void renderSpriteObjectPass(SpriteManager spriteManager, boolean includeWaterSurface) {
        levelRenderer.renderSpriteObjectPass(spriteManager, includeWaterSurface);
    }

    /**
     * Renders the DEZ background during the ending cutscene.
     * Delegates to {@link LevelRenderer}.
     */
    public void renderEndingBackground(int bgVscroll) {
        levelRenderer.renderEndingBackground(bgVscroll);
    }

    /**
     * Renders the DEZ star field background for the ending cutscene, with an
     * optional backdrop color override. Delegates to {@link LevelRenderer}.
     */
    public void renderEndingBackground(int bgVscroll, float[] backdropOverride) {
        levelRenderer.renderEndingBackground(bgVscroll, backdropOverride);
    }

    public void recomputeParallaxAfterRewindRestore() {
        frameRuntimeUpdater.refreshParallaxState();
    }

    /** Prepares the demo scene before Level_Delay / PalFadeIn_Alt. */
    public void prepareEndingDemoScene() {
        // S1 Level_SkipTtlCard calls DeformLayers before LoadTilesFromStart.
        // DeformLayers writes v_scrposy_vdp as well as the background scroll
        // table. The foreground renderer reads that separate VSRAM value,
        // so snapping Camera alone leaves the fade showing the old terrain.
        frameRuntimeUpdater.refreshParallaxState();
        camera.captureRenderCopy();
        // The demo position overrides the ordinary act start after load, so
        // reseed placement from that same final viewport before the hidden delay.
        if (objectManager != null) {
            objectManager.reset(camera.getX());
        }
        if (ringManager != null) {
            ringManager.reset(camera.getX());
        }
        // Level_LoadObj executes the fresh Sonic slot once before BuildSprites.
        // Sonic_Move selects Wait and refreshes the movement-animation latches;
        // merely clearing obAnim leaves the preceding demo's speed latch alive.
        spriteManager.warmUpFreshMainPlayableOnly(
                activeGameModule().getLevelInitProfile().freshMainPlayablePreludeFrames(),
                this, mainPlayableSprite());
    }

    /**
     * Test-only entry point that delegates to {@link LevelRenderer}'s special
     * render effect dispatch. Retained on {@code LevelManager} because existing
     * reflection-based unit tests expect the method to live here.
     */
    @SuppressWarnings("unused")
    private void dispatchSpecialRenderEffects(SpecialRenderEffectStage stage, int frameCounter) {
        levelRenderer.dispatchSpecialRenderEffects(stage, frameCounter);
    }


    void ensureBackgroundTilemapData() {
        if (tilemapManager != null) {
            int bgCameraX = parallaxManager != null ? parallaxManager.getBgCameraX() : Integer.MIN_VALUE;
            int bgCameraY = parallaxManager != null ? parallaxManager.getVscrollFactorBG() : 0;
            BgTilemapUpdateMode updateMode = parallaxManager != null
                    ? parallaxManager.getBgTilemapUpdateMode()
                    : BgTilemapUpdateMode.STATIC_WINDOW;
            applyBackgroundTilemapWindowSelection(bgCameraX);
            if (updateMode == BgTilemapUpdateMode.PERSISTENT_NAMETABLE_64X32) {
                tilemapManager.ensureBackgroundTilemapData(this::getBlockAtPosition,
                        zoneFeatureProvider, currentZone, parallaxManager,
                        updateMode, bgCameraX, bgCameraY, verticalWrapEnabled);
            } else {
                // Preserve the existing stateless virtual-dispatch seam used by
                // ad-hoc tilemap writers and focused LevelManager tests.
                tilemapManager.ensureBackgroundTilemapData(this::getBlockAtPosition,
                        zoneFeatureProvider, currentZone, parallaxManager, verticalWrapEnabled);
            }
        }
    }

    /**
     * Selects the BG tilemap cache window used by wrapped-background zones.
     * This must run before both render-driven and ad-hoc tilemap builds so they
     * see the same MGZ state-8 cache configuration.
     *
     * @return true when MGZ state 8 should use the full-width per-line BG tilemap path
     */
    boolean applyBackgroundTilemapWindowSelection(int bgCameraX) {
        if (tilemapManager == null) {
            return false;
        }
        BgTilemapUpdateMode updateMode = parallaxManager != null
                ? parallaxManager.getBgTilemapUpdateMode()
                : BgTilemapUpdateMode.STATIC_WINDOW;
        boolean fullWidthPerLineTilemap = zoneFeatureProvider != null
                && zoneFeatureProvider.useFullWidthBackgroundTilemapWindow(
                currentZone, currentAct, bgCameraX, cachedBgContiguousWidthPx);
        int newBgPeriodWidth = parallaxManager != null
                ? parallaxManager.getBgPeriodWidth()
                : LevelTilemapManager.VDP_BG_PLANE_WIDTH_PX;
        if (fullWidthPerLineTilemap) {
            if (tilemapManager.getBgTilemapBaseX() != 0) {
                tilemapManager.setBgTilemapBaseX(0);
                tilemapManager.setBackgroundTilemapDirty(true);
            }
            newBgPeriodWidth = cachedBgContiguousWidthPx;
        } else if (updateMode == BgTilemapUpdateMode.STATIC_WINDOW
                && bgCameraX != Integer.MIN_VALUE
                && zoneFeatureProvider != null && zoneFeatureProvider.bgWrapsHorizontally()) {
            int newBase = Math.floorDiv(bgCameraX, 16) * 16;
            if (newBase != tilemapManager.getBgTilemapBaseX()) {
                // Window-only change: eligible for the incremental one-column shift.
                tilemapManager.requestBgWindowBaseX(newBase);
            }
        } else if (updateMode == BgTilemapUpdateMode.STATIC_WINDOW
                && tilemapManager.getBgTilemapBaseX() != 0) {
            tilemapManager.setBgTilemapBaseX(0);
            tilemapManager.setBackgroundTilemapDirty(true);
        }

        if (newBgPeriodWidth != tilemapManager.getCurrentBgPeriodWidth()) {
            tilemapManager.setCurrentBgPeriodWidth(newBgPeriodWidth);
            tilemapManager.setBackgroundTilemapDirty(true);
        }

        // S3K CNZ miniboss loops a fixed BG band (CNZ1BGE_Boss); anchor/clamp the BG
        // tilemap to that band so the looping scroll excludes the room floor below it.
        int loopBandBaseY = zoneFeatureProvider != null
                ? zoneFeatureProvider.backgroundLoopBandBaseY(currentZone, currentAct)
                : -1;
        if (loopBandBaseY != tilemapManager.getBgLoopBandBaseY()) {
            tilemapManager.setBgLoopBandBaseY(loopBandBaseY);
            tilemapManager.setBackgroundTilemapDirty(true);
        }
        return fullWidthPerLineTilemap;
    }

    void ensureForegroundTilemapData() {
        if (tilemapManager != null) {
            tilemapManager.ensureForegroundTilemapData(this::getBlockAtPosition,
                    zoneFeatureProvider, currentZone, parallaxManager, verticalWrapEnabled);
        }
    }

    /**
     * Ensures the live foreground tilemap cache represents the current layout
     * before a ROM-style script mutates layout RAM without requesting a redraw.
     */
    public void snapshotForegroundTilemapBeforeRuntimeLayoutMutation() {
        ensureForegroundTilemapData();
    }

    /**
     * Retrieves the block at a given position.
     *
     * @param layer the layer to retrieve the block from
     * @return the Block at the specified position, or null if not found
     */
    int getLayerLevelWidthPx(byte layer) {
        if (level == null) {
            return blockPixelSize;
        }
        int widthBlocks = Math.max(1, level.getLayerWidthBlocks(layer));
        return widthBlocks * blockPixelSize;
    }

    int getLayerLevelHeightPx(byte layer) {
        if (level == null) {
            return blockPixelSize;
        }
        int heightBlocks = Math.max(1, level.getLayerHeightBlocks(layer));
        return heightBlocks * blockPixelSize;
    }

    /**
     * Populates cached FG/BG pixel dimensions from the current level.
     * Must be called after a level is loaded (dimensions are immutable during gameplay).
     */
    private void cacheLevelDimensions() {
        if (level != null) {
            cachedFgWidthPx = getLayerLevelWidthPx((byte) 0);
            cachedFgHeightPx = getLayerLevelHeightPx((byte) 0);
            cachedBgWidthPx = getLayerLevelWidthPx((byte) 1);  // Full map width (matches reference)
            cachedBgContiguousWidthPx = computeActualBgDataWidthPx();  // For bgTilemapBaseX wrapping
            cachedBgHeightPx = getLayerLevelHeightPx((byte) 1);
        } else {
            cachedFgWidthPx = blockPixelSize;
            cachedFgHeightPx = blockPixelSize;
            cachedBgWidthPx = blockPixelSize;
            cachedBgContiguousWidthPx = blockPixelSize;
            cachedBgHeightPx = blockPixelSize;
        }
        blockGrid = new BlockGridIndexer(blockPixelSize);
        collisionLayoutYMask = resolveCollisionLayoutYMask();
    }

    private int resolveCollisionLayoutYMask() {
        GameRules rules = gameRulesFor(gameModule);
        if (rules == null || rules.collision() == null) {
            return 0;
        }
        if (!rules.collision().layoutYMaskAppliesToAllLookups()) {
            // The mask is real but is not a constant for this game (S3K writes
            // Layout_row_index_mask per level: $7C normally, $3C for looping levels,
            // sonic3k.asm:102207/110071/110322/114224/114253), and it masks an
            // already-shifted row index rather than a Y position. Treating it as a
            // constant made ICZ1's snowboard intro end its slope ride 193px short, so
            // this game keeps its previous lookup behaviour until the runtime variable
            // is modelled.
            return 0;
        }
        return rules.collision().defaultCollisionLayoutYMask();
    }

    /**
     * Maps a world Y onto a collision-layout row the way the ROM's tile lookup does.
     *
     * <p>Every game's block lookup <em>masks</em> the layout row index rather than
     * bounds-checking it, because the layout is a fixed-size RAM buffer:
     * <ul>
     *   <li>S1 {@code FindNearestTile}: {@code lsr.w #1,d0 / andi.w #$380,d0} over the
     *       8-row, {@code layout_row}=$80 buffer — an 0x800px window
     *       (docs/s1disasm/_incObj/"sub FindNearestTile &amp; FindFloor &amp; FindWall.asm":15-17,
     *       docs/s1disasm/_Variables.asm:21).</li>
     *   <li>S2 {@code Find_Tile}: {@code add.w d0,d0 / andi.w #$F00,d0} over 16 rows of
     *       128px — also an 0x800px window (docs/s2disasm/s2.asm:43366-43368).</li>
     *   <li>S3K {@code Find_Tile_FG}: {@code lsr.w #5,d0 / and.w (Layout_row_index_mask).w,d0}
     *       (docs/skdisasm/sonic3k.asm:19143-19145).</li>
     * </ul>
     * The per-game window is already carried by
     * {@code CollisionRules.defaultCollisionLayoutYMask()}, which
     * {@code GroundSensor.verticalTileLookupY} applies to the negative-Y ceiling probe.
     * Applying it here makes it what it actually is in the ROM: a property of how the
     * layout array is indexed, on every lookup, for every object — not a player-only or
     * upward-only special case.
     *
     * <p>Consequence: an object that leaves the level vertically keeps sensing terrain
     * from a wrapped row instead of falling forever. Rows past the end of the loaded
     * layout are zero-filled RAM, i.e. the blank-chunk path (with {@code FixBugs = 0} that
     * is {@code .blanktile -> movea.l d1,a1} pointing at {@code v_chunk0collision}, which
     * is permanently 0; the {@code FixBugs} branch would instead point at a ROM zero word,
     * same observable result) — modelled here by returning -1 so callers see no terrain.
     *
     * @return the wrapped Y, or -1 when the wrapped row is outside the loaded layout
     */


    /**
     * Builds a LevelGeometry snapshot from the current cached dimensions.
     */
    private LevelGeometry buildGeometry() {
        return new LevelGeometry(level, cachedFgWidthPx, cachedFgHeightPx,
                cachedBgWidthPx, cachedBgContiguousWidthPx, cachedBgHeightPx,
                blockPixelSize, chunksPerBlockSide);
    }

    /**
     * Scan the BG layer (layer 1) to find the contiguous data width.
     * On the Mega Drive, the BG nametable is a 512px-wide ring buffer.
     * The scroll handler fills it from the BG map, wrapping at the map's
     * data width.  The Map stores both FG and BG with the same total width
     * (e.g., 128 blocks = 16384px), but BG data typically only spans a
     * small contiguous region from column 0 (e.g., 8 blocks for HTZ).
     * <p>
     * Using the contiguous BG data width for X wrapping ensures that queries
     * at large camera X positions wrap back to valid BG data rather than
     * reading empty columns in the unused portion of the map.
     * <p>
     * Example: HTZ BG data spans 8 contiguous columns (1024px) within a
     * 128-column map.  Without this fix, bgTilemapBaseX=6144 queries
     * column 48 (empty).  With contiguous width = 1024px wrapping,
     * 6144 mod 1024 = 0 → column 0 (valid).
     */
    private int computeActualBgDataWidthPx() {
        int dataWidthPx = LevelGeometry.contiguousBgDataWidthPx(level, blockPixelSize);
        if (level != null && level.getMap() != null
                && dataWidthPx < level.getMap().getWidth() * blockPixelSize) {
            LOGGER.fine("BG contiguous data width: " + dataWidthPx / blockPixelSize + " blocks ("
                    + dataWidthPx + "px) out of " + level.getMap().getWidth() + " map columns");
        }
        return dataWidthPx;
    }

    /** Fast cached getter for layer pixel width (avoids per-call getLayerWidthBlocks). */
    int getCachedLayerWidthPx(byte layer) {
        int cached = layer == 0 ? cachedFgWidthPx : cachedBgWidthPx;
        return cached > 0 ? cached : getLayerLevelWidthPx(layer);
    }

    /** Fast cached getter for layer pixel height (avoids per-call getLayerHeightBlocks). */
    int getCachedLayerHeightPx(byte layer) {
        int cached = layer == 0 ? cachedFgHeightPx : cachedBgHeightPx;
        return cached > 0 ? cached : getLayerLevelHeightPx(layer);
    }

    /** Delegates to {@link LevelLayoutLookup#getBlockAtPosition}. */
    Block getBlockAtPosition(byte layer, int x, int y) {
        return layoutLookup.getBlockAtPosition(layer, x, y);
    }

    /**
     * Returns the raw block index (0-255) at the given pixel position in the foreground layer.
     * Equivalent to the ROM's Level_Layout lookup used by OilSlides.
     *
     * @param x pixel X coordinate
     * @param y pixel Y coordinate
     * @return block index (0-255), or -1 if out of bounds
     */
    /**
     * Returns the raw block index (0-255) at the given pixel position in the
     * foreground layer, or -1 when out of bounds.
     */
    public int getBlockIdAt(int x, int y) {
        return layoutLookup.getBlockIdAt(x, y);
    }

    /** Returns the ChunkDesc at the given pixel position, or null. */
    public ChunkDesc getChunkDescAt(byte layer, int x, int y) {
        return layoutLookup.getChunkDescAt(layer, x, y);
    }

    /**
     * Returns the ChunkDesc at the given pixel position, optionally resolving
     * Sonic 1 loop collision (low plane uses alternate block index).
     *
     * @param layer        0 = foreground, 1 = background
     * @param x            pixel X
     * @param y            pixel Y
     * @param loopLowPlane if true and layer == 0, resolve collision block index via Level
     * @return the ChunkDesc, or null if out of bounds
     */
    /**
     * Returns the ChunkDesc at the given pixel position, optionally resolving
     * Sonic 1 loop collision (low plane uses an alternate block index).
     */
    public ChunkDesc getChunkDescAt(
            byte layer, int x, int y, boolean loopLowPlane) {
        return layoutLookup.getChunkDescAt(layer, x, y, loopLowPlane);
    }

    public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc, int solidityBitIndex) {
        return getSolidTileForChunkDesc(chunkDesc, solidityBitIndex, solidityBitIndex >= 0x0E);
    }

    public SolidTile getSolidTileForChunkDesc(
            ChunkDesc chunkDesc, int solidityBitIndex, boolean useSecondaryCollisionPath) {
        try {
            if (chunkDesc == null) {
                return null;
            }
            if (!chunkDesc.isSolidityBitSet(solidityBitIndex)) {
                return null;
            }

            Chunk chunk = level.getChunk(chunkDesc.getChunkIndex());
            if (chunk == null) {
                return null;
            }
            // Get collision index - ROM treats index 0 as "no collision"
            // (s2.asm FindFloor line 42963: beq.s loc_1E7E2)
            int collisionIndex = useSecondaryCollisionPath
                    ? chunk.getSolidTileAltIndex()
                    : chunk.getSolidTileIndex();
            if (collisionIndex == 0) {
                return null; // No collision shape assigned
            }
            return level.getSolidTile(collisionIndex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Deprecated or convenience method for backward compatibility if needed,
    // but better to remove or update callers.
    // For now, let's overload it to default to Layer 0 (Primary) if not specified,
    // or we can force update. GroundSensor is the main one.
    // I'll leave a deprecated one just in case, or remove it.
    // GroundSensor calls it. I should update GroundSensor.
    // But I can't leave this here without updating GroundSensor first or it won't
    // compile?
    // Wait, I can overload.
    public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc, byte layer) {
        int solidityBitIndex = (layer == 0) ? 0x0C : 0x0E;
        return getSolidTileForChunkDesc(chunkDesc, solidityBitIndex);
    }

    public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc) {
        return getSolidTileForChunkDesc(chunkDesc, (byte) 0);
    }

    /**
     * Returns the current level.
     *
     * @return the current Level object
     */
    public Level getCurrentLevel() {
        return level;
    }

    /** Monotonic token for successful full loads, including same-level reloads. */
    public long getCompletedProductionLoadGeneration() {
        return completedProductionLoadGeneration;
    }

    public int getCurrentZone() {
        return currentZone;
    }

    /**
     * Returns the ROM zone ID for the currently loaded level.
     * Unlike {@link #getCurrentZone()} which returns the zone registry progression
     * index, this returns the game-specific zone identifier from the ROM data
     * (e.g. Sonic1Constants.ZONE_MZ = 2 for Marble Zone regardless of gameplay order).
     * Use this when comparing against game-specific zone constants.
     */
    public int getRomZoneId() {
        return level != null ? level.getZoneIndex() : -1;
    }

    /**
     * Returns the ROM's {@code v_act} for the current level.
     *
     * <p>Pairs with {@link #getRomZoneId()}: together they are the
     * ({@code v_zone}, {@code v_act}) word ROM routines compare against. Sonic 1
     * needs both remapped — SBZ act 3 is the LZ slot's act 4, and Final Zone is
     * SBZ act 3 — so gates ported from the disassembly read this rather than the
     * logical act. This is distinct from {@link #getFeatureActId()}, which
     * answers which zone/act a feature system (water, palettes) is keyed by.
     */
    public int getRomActId() {
        if (level == null || gameModule == null) {
            return currentAct;
        }
        int romAct = gameModule.getRomAct(currentZone, currentAct, level.getZoneIndex());
        return romAct >= 0 ? romAct : currentAct;
    }

    /**
     * Returns the effective zone ID for zone features/water logic.
     *
     * <p>Sonic 1 SBZ3 uses the LZ zone slot ({@code id_LZ act 3}) for map/art data,
     * but gameplay systems treat it as SBZ act 3. For feature systems that are keyed
     * by zone/act (water palettes/heights), map that specific case back to SBZ.
     */
    public int getFeatureZoneId() {
        if (level == null || gameModule == null) {
            return level != null ? level.getZoneIndex() : -1;
        }
        int remapped = gameModule.getRemappedFeatureZone(currentZone, currentAct, level.getZoneIndex());
        return remapped >= 0 ? remapped : level.getZoneIndex();
    }

    /**
     * Returns the effective act index for zone features/water logic.
     */
    public int getFeatureActId() {
        if (level == null || gameModule == null) {
            return currentAct;
        }
        int remapped = gameModule.getRemappedFeatureAct(currentZone, currentAct, level.getZoneIndex());
        return remapped >= 0 ? remapped : currentAct;
    }

    public int getCurrentAct() {
        return currentAct;
    }

    /**
     * Returns the apparent act for title card display.
     * ROM: {@code Apparent_act} — stays at 0 during AIZ's seamless fire
     * transition even though {@code Current_act} changes to 1.
     */
    public int getApparentAct() {
        return apparentAct;
    }

    /**
     * Sets the apparent act for title card display.
     * ROM: {@code move.b #1,(Apparent_act).w} — called by the results
     * screen when act 1 ends, so subsequent death/restart title cards
     * show the correct act number.
     */
    public void setApparentAct(int act) {
        this.apparentAct = act;
        worldSession.setApparentAct(act);
    }

    /**
     * Updates a specific palette line with new color data.
     * This is used to load boss palettes during boss fights.
     *
     * @param paletteIndex The palette line to update (0-3)
     * @param paletteData  The raw Sega-format palette data (32 bytes for 16 colors)
     */
    public void updatePalette(int paletteIndex, byte[] paletteData) {
        if (level == null || paletteIndex < 0 || paletteIndex >= 4) {
            return;
        }

        // Create a new palette from the data
        Palette newPalette = new Palette();
        newPalette.fromSegaFormat(paletteData);

        // Update the level's palette object so palette cycling uses the new palette
        // This is critical - without this, palette cycling would re-cache the original
        // level palette, overwriting the boss palette we just loaded
        level.setPalette(paletteIndex, newPalette);

        // Update the graphics manager's cached palette texture
        GraphicsManager graphicsMan = graphicsManager;
        if (graphicsMan.isGlInitialized()) {
            graphicsMan.cachePaletteTexture(newPalette, paletteIndex);
        }

        LOGGER.fine("Updated palette line " + paletteIndex + " with " + paletteData.length + " bytes");
    }

    /**
     * Marks the foreground tilemap as dirty, forcing a rebuild on next render.
     * Call this after modifying the level layout (e.g., placing boss arena walls).
     * This is equivalent to setting Screen_redraw_flag in the original ROM.
     */
    public void invalidateForegroundTilemap() {
        if (tilemapManager != null) {
            tilemapManager.invalidateForegroundTilemap();
        }
    }

    /**
     * Marks only the pattern atlas lookup as dirty. Use this after runtime
     * writes that replace 8x8 pattern data in place (art overlays, PLC uploads)
     * without changing the pattern indices the tilemap cells reference.
     */
    public void invalidatePatternLookup() {
        if (tilemapManager != null) {
            tilemapManager.invalidatePatternLookup();
        }
    }

    public void reuploadDirtyPatterns(java.util.BitSet dirtyPatterns) {
        dirtyRegionDispatcher.reuploadDirtyPatterns(dirtyPatterns);
    }

    public void resyncObjectSpawnListFromLevel() {
        if (objectManager == null || level == null) {
            return;
        }
        objectManager.resyncSpawnList(level.getObjects());
    }

    public void resyncRingSpawnListFromLevel() {
        if (ringManager == null || level == null) {
            return;
        }
        ringManager.resyncSpawnList(level.getRings());
    }

    public void flushQueuedLayoutMutations() {
        Level currentLevel = getCurrentLevel();
        if (currentLevel == null || !GameServices.hasRuntime()) {
            return;
        }

        LevelMutationSurface surface = LevelMutationSurface.forLevel(currentLevel);
        LayoutMutationContext context = new LayoutMutationContext(surface, this::applyMutationEffects);
        GameServices.zoneLayoutMutationPipeline().flush(context);
    }

    public void applyMutationEffects(MutationEffects effects) {
        dirtyRegionDispatcher.applyMutationEffects(effects);
    }

    /**
     * Reads the foreground tile descriptor currently represented by level data at world coordinates.
     * This resolves block/chunk indirection plus chunk descriptor flips, matching tilemap build logic.
     */
    public int getForegroundTileDescriptorAtWorld(int worldX, int worldY) {
        return getTileDescriptorAtWorld((byte) 0, worldX, worldY);
    }

    /**
     * Reads the background tile descriptor currently represented by level data at world coordinates.
     * This resolves block/chunk indirection plus chunk descriptor flips, matching tilemap build logic.
     */
    public int getBackgroundTileDescriptorAtWorld(int worldX, int worldY) {
        return getTileDescriptorAtWorld((byte) 1, worldX, worldY);
    }

    /**
     * Copies one 16x16 BG source row into the live Plane B tilemap buffer.
     *
     * <p>S3K's Slot Machine bonus stage does this from {@code sub_4ECAA}: {@code d0/d1}
     * select the source BG map row, {@code d5} is the destination VDP plane address,
     * and {@code d6} is the number of longwords to draw. Each longword represents
     * two horizontal 8x8 cells, and the routine writes both 8x8 rows of the
     * 16x16 source block row.
     */
    public boolean copyBackgroundTileRowFromWorldToVdpPlane(int sourceWorldX, int sourceWorldY,
                                                            int destVramAddress, int longWordCount) {
        if (tilemapManager == null || longWordCount <= 0) {
            return false;
        }
        ensureBackgroundTilemapData();
        int bgWidthTiles = tilemapManager.getBackgroundTilemapWidthTiles();
        int bgHeightTiles = tilemapManager.getBackgroundTilemapHeightTiles();
        if (bgWidthTiles <= 0 || bgHeightTiles <= 0) {
            return false;
        }

        int destPlaneOffsetBytes = Math.floorMod(destVramAddress - 0xE000, 0x1000);
        int destCell = destPlaneOffsetBytes / 2;
        int cellCount = longWordCount * 2;
        int sourceStartX = (sourceWorldX >> 4) << 4;
        boolean changed = false;
        for (int row = 0; row < 2; row++) {
            int sourceRowY = sourceWorldY + row * Pattern.PATTERN_HEIGHT;
            int destRowCell = destCell + row * 64; // Plane B is 64 cells wide, so +0x80 bytes per 8x8 row.
            for (int i = 0; i < cellCount; i++) {
                int planeCell = (destRowCell + i) & 0x7FF; // Plane B is 64x32 cells.
                int destTileX = planeCell & 0x3F;
                int destTileY = (planeCell >>> 6) & 0x1F;
                if (destTileX >= bgWidthTiles || destTileY >= bgHeightTiles) {
                    continue;
                }
                int descriptor = getBackgroundTileDescriptorAtWorld(sourceStartX + i * Pattern.PATTERN_WIDTH,
                        sourceRowY);
                changed |= tilemapManager.setBackgroundTileDescriptorAtTilemapCell(destTileX, destTileY, descriptor);
            }
        }
        return changed;
    }

    private int getTileDescriptorAtWorld(byte layer, int worldX, int worldY) {
        return layoutLookup.getTileDescriptorAtWorld(layer, worldX, worldY);
    }

    /**
     * Overwrites one foreground tile descriptor at world coordinates in the live FG tilemap buffer.
     * Call {@link #uploadForegroundTilemap()} once after batching writes.
     *
     * @return true if tilemap bytes changed
     */
    public boolean setForegroundTileDescriptorAtWorld(int worldX, int worldY, int descriptor) {
        if (tilemapManager == null) {
            return false;
        }
        return tilemapManager.setForegroundTileDescriptorAtWorld(worldX, worldY, descriptor,
                this::getBlockAtPosition, zoneFeatureProvider, currentZone,
                parallaxManager, verticalWrapEnabled);
    }

    /**
     * Reads a foreground tile descriptor from the live foreground tilemap buffer at world coordinates.
     * Unlike {@link #getForegroundTileDescriptorAtWorld(int, int)}, this returns the currently visible
     * descriptor after runtime tilemap writes.
     */
    public int getForegroundTileDescriptorFromTilemapAtWorld(int worldX, int worldY) {
        if (tilemapManager == null) {
            return 0;
        }
        return tilemapManager.getForegroundTileDescriptorFromTilemapAtWorld(worldX, worldY,
                this::getBlockAtPosition, zoneFeatureProvider, currentZone,
                parallaxManager, verticalWrapEnabled);
    }

    /**
     * Uploads the current foreground tilemap bytes to the GPU renderer (if active).
     * No-op in headless mode.
     */
    public void uploadForegroundTilemap() {
        if (tilemapManager != null) {
            tilemapManager.uploadForegroundTilemap();
        }
    }

    /**
     * Uploads the current background tilemap bytes to the GPU renderer (if active).
     * No-op in headless mode.
     */
    public void uploadBackgroundTilemap() {
        if (tilemapManager != null) {
            tilemapManager.uploadBackgroundTilemap();
        }
    }

    /**
     * Marks background/foreground tilemaps and pattern lookup as dirty.
     * Use this after runtime terrain art/chunk overlays so the GPU tilemap
     * data is rebuilt on the next render.
     */
    public void invalidateAllTilemaps() {
        if (tilemapManager != null) {
            tilemapManager.invalidateAllTilemaps();
        }
    }

    /**
     * Pre-builds FG and BG tilemap data from the current level state.
     * The pre-built data can later be swapped in via {@link #swapToPrebuiltTilemaps()}
     * to avoid the expensive full-level tilemap rebuild on the transition frame.
     */
    public void prebuildTransitionTilemaps() {
        if (tilemapManager != null) {
            tilemapManager.prebuildTransitionTilemaps(this::getBlockAtPosition,
                    zoneFeatureProvider, currentZone, parallaxManager, verticalWrapEnabled);
        }
    }

    /**
     * Swaps pre-built tilemap data into the live arrays, uploads to GPU,
     * and clears FG/BG dirty flags. Still marks pattern lookup dirty
     * (cheap rebuild, needed if pattern count changed from the overlay).
     *
     * @return true if pre-built data was available and swapped in
     */
    public boolean swapToPrebuiltTilemaps() {
        if (tilemapManager == null) {
            return false;
        }
        return tilemapManager.swapToPrebuiltTilemaps();
    }

    /**
     * Returns whether pre-built transition tilemap data is available.
     */
    public boolean hasPrebuiltTilemaps() {
        return tilemapManager != null && tilemapManager.hasPrebuiltTilemaps();
    }

    /**
     * Gets the music ID for the current level.
     * Returns -1 if no level is loaded or music ID cannot be determined.
     */
    public int getCurrentLevelMusicId() {
        return LevelMusicCoordinator.currentMusicId(
                game, levels, currentZone, currentAct, LOGGER);
    }

    /**
     * Gets the music selected by the ROM's {@code Apparent_zone_and_act}.
     * This intentionally differs from {@link #getCurrentLevelMusicId()} during
     * seamless transitions such as AIZ's fire curtain, where AIZ2 resources
     * are loaded while the apparent act remains AIZ1.
     */
    public int getApparentLevelMusicId() {
        if (gameModule == null) {
            return -1;
        }
        return gameModule.getZoneRegistry().getMusicId(currentZone, apparentAct);
    }

    public Collection<ObjectSpawn> getActiveObjectSpawns() {
        if (objectManager == null) {
            return List.of();
        }
        return objectManager.getActiveSpawns();
    }

    public ObjectRenderManager getObjectRenderManager() {
        return objectRenderManager;
    }

    public RingManager getRingManager() {
        return ringManager;
    }

    public int getFrameCounter() {
        return frameCounter;
    }

    /**
     * Aligns this manager's level frame counter during one-time replay/bootstrap
     * setup. Seed it with the PREVIOUS completed level frame:
     * {@link #advanceLevelFrameCounter()} runs at the top of the next
     * {@code LevelFrameStep} frame, where the ROM's own loop increments
     * {@code Level_frame_counter}, so that frame's body reads the ROM's value.
     */
    public void setFrameCounter(int frameCounter) {
        this.frameCounter = frameCounter;
    }

    public ZoneFeatureProvider getZoneFeatureProvider() {
        return zoneFeatureProvider;
    }

    public AnimatedPatternManager getAnimatedPatternManager() {
        return animatedPatternManager;
    }

    public AnimatedPaletteManager getAnimatedPaletteManager() {
        return animatedPaletteManager;
    }

    public boolean areAllRingsCollected() {
        return ringManager != null && ringManager.areAllCollected();
    }

    public ObjectManager getObjectManager() {
        return objectManager;
    }

    /** Preserves the recorded Obj37 V-int low-bit phase across seamless act rebuilds. */
    public void initRingFloorCheckCounterPhase(int phase) {
        ringFloorCheckCounterPhase = phase;
        if (objectManager != null) {
            objectManager.initRingFloorCheckCounterPhase(phase);
        }
    }

    public void spawnLostRings(AbstractPlayableSprite player, int frameCounter) {
        lostRingSpawns.spawnImmediately(player, frameCounter);
    }

    public void spawnLostRingsAfterCurrentFrame(AbstractPlayableSprite player, int frameCounter) {
        lostRingSpawns.queue(player, frameCounter, false);
    }

    public void spawnLostRingsWithDeferredOwner(AbstractPlayableSprite player, int frameCounter) {
        lostRingSpawns.queue(player, frameCounter, true);
    }

    // ── Post-load assembly methods ──────────────────────────────────────
    // Extracted from loadCurrentLevel() so profile steps can delegate to them.
    // Each method corresponds to one post-load InitStep (steps 14-20).

    /**
     * Step 14: Restore checkpoint state after loadLevel() clears it.
     * ROM: S1 Lamp_LoadInfo, S2 Obj79_LoadData, S3K Saved_zone_and_act restore.
     */
    public void restoreCheckpointState(LevelLoadContext ctx) {
        checkpointCoordinator.restoreCheckpointState(ctx);
    }

    private void restoreCheckpointRuntimeState(LevelLoadContext ctx) {
        checkpointCoordinator.restoreRuntimeState(ctx);
    }

    /**
     * Step 15: Set player position from checkpoint or level start.
     * ROM: S1/S2 StartLocations / Obj79_LoadData, S3K Get_PlayerStart.
     */
    public void spawnPlayerAtStartPosition(LevelLoadContext ctx) {
        String mainCode = resolveMainCharacterCode();
        Sprite player = spriteManager.getSprite(mainCode);
        if (player == null) {
            LOGGER.warning("SpawnPlayer: no sprite registered for code '" + mainCode
                    + "' — skipping. Register the player sprite before loadZoneAndAct().");
            return;
        }
        LevelData levelData = ctx.getLevelData();
        if (levelData == null) {
            levelData = resolveLevelData();
            if (levelData == null) {
                throw new IllegalStateException(
                    "LevelLoadContext.levelData is null and could not be auto-resolved " +
                    "from the levels map (zone=" + currentZone + ", act=" + currentAct + "). " +
                    "Ensure InitGameModule has run before SpawnPlayer.");
            }
            ctx.setLevelData(levelData);
            LOGGER.info("Auto-resolved levelData from levels map: " + levelData.name());
        }

        int spawnY = -1;
        // ROM: Level_FromSavedGame sets Saved2_* position before level init.
        // Gated by Last_star_post_hit exactly as loc_1BE46 gates the restore
        // (skdisasm/sonic3k.asm:38148-38151): the Super Emerald arena restart
        // clears that flag (loc_618AC, sonic3k.asm:128414), so the arena load
        // must place the player from Sonic_Start_Locations even though the
        // Saved2_ block written by Save_Level_Data2 is still live for the
        // return leg.
        if (transitions.hasBigRingReturn() && transitions.isLastStarPostHitSet()) {
            BigRingReturnState br = transitions.getBigRingReturn();
            player.setCentreX((short) br.playerX());
            player.setCentreY((short) br.playerY());
            spawnY = br.playerY();
            LOGGER.info("Set player position from big ring return: X=" + br.playerX() +
                    ", Y=" + br.playerY() + " (center coordinates)");
        } else if (ctx.hasCheckpoint()) {
            player.setCentreX((short) ctx.getCheckpointX());
            player.setCentreY((short) ctx.getCheckpointY());
            spawnY = ctx.getCheckpointY();
            LOGGER.info("Set player position from checkpoint: X=" + ctx.getCheckpointX() +
                    ", Y=" + ctx.getCheckpointY() + " (center coordinates)");
        } else {
            int spawnX = levelData.getStartXPos();
            spawnY = levelData.getStartYPos();

            if (game instanceof DynamicStartPositionProvider dynamicStartProvider) {
                try {
                    int[] dynamicStart = dynamicStartProvider.getStartPosition(currentZone, currentAct);
                    if (dynamicStart != null && dynamicStart.length >= 2) {
                        spawnX = dynamicStart[0];
                        spawnY = dynamicStart[1];
                        LOGGER.info("Set player position from dynamic start provider: X=" + spawnX +
                                ", Y=" + spawnY + " (zone=" + currentZone + ", act=" + currentAct + ")");
                    } else {
                        LOGGER.info("Dynamic start provider unavailable, using levelData fallback for " +
                                levelData.name());
                    }
                } catch (IOException e) {
                    LOGGER.warning("DynamicStartPositionProvider failed, using levelData fallback: " + e.getMessage());
                }
            }

            player.setCentreX((short) spawnX);
            player.setCentreY((short) spawnY);
            LOGGER.info("Set player position from level start: X=" + spawnX +
                    ", Y=" + spawnY + " (center coordinates)" +
                    ", level: " + levelData.name());
        }
        ctx.setSpawnY(spawnY);
    }

    /**
     * Step 16: Reset player state for level start.
     * ROM: S2 InitPlayers state clear, S3K object constructor defaults.
     */
    public void resetPlayerForLevelStart(LevelLoadContext ctx) {
        Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
        if (!(player instanceof AbstractPlayableSprite playable)) {
            return;
        }
        playable.resetState();
        playable.setXSpeed((short) 0);
        playable.setYSpeed((short) 0);
        playable.setGSpeed((short) 0);
        // ROM: SBZ3 (spawnY=0) spawns airborne — set air=true so gravity applies.
        playable.setAir(ctx.getSpawnY() == 0);
        LOGGER.info("Player state after loadCurrentLevel: air=" + playable.getAir() +
                ", ySpeed=" + playable.getYSpeed() + ", layer=" + player.getLayer());
        playable.setRolling(false);
        playable.setDead(false);
        playable.setHurt(false);
        playable.setDeathCountdown(0);
        playable.setInvulnerableFrames(0);
        playable.setInvincibleFrames(0);
        playable.setDirection(Direction.RIGHT);
        playable.setAngle((byte) 0);
        player.setLayer((byte) 0);
        playable.setHighPriority(false);
        playable.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);
        playable.setRingCount(0);
        if (ctx.hasCheckpoint() && ctx.hasCheckpointSolidBits()) {
            playable.setTopSolidBit(ctx.getCheckpointTopSolidBit());
            playable.setLrbSolidBit(ctx.getCheckpointLrbSolidBit());
        }
        audioManager.setSpeedShoes(false);
    }

    /**
     * Step 17: Initialize camera for level start.
     * ROM: S1/S2 SetScreen/InitCameraValues, S3K Get_LevelSizeStart.
     */
    public void initCameraForLevel() {
        Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
        if (!(player instanceof AbstractPlayableSprite playable)) {
            persistentRespawnStateForCameraSnap = null;
            return;
        }
        BigRingReturnState bigRingReturn = transitions.hasBigRingReturn()
                && transitions.isLastStarPostHitSet()
                ? transitions.getBigRingReturn()
                : null;
        // ROM: Load_Starpost_Settings restores Saved2_camera_max_Y_pos before
        // Get_LevelSizeStart computes the first return camera position
        // (skdisasm/sonic3k.asm:61834-61837, 38172-38178). Publish that bound
        // before either camera update below; applying it only in the later
        // title-card handoff leaves the return one camera step behind.
        if (bigRingReturn != null) {
            camera.setMaxY((short) bigRingReturn.cameraMaxY());
        }
        PersistentRespawnState persistentRespawnState = persistentRespawnStateForCameraSnap;
        int preSnapCameraX = camera.getX();
        camera.setFrozen(false);
        camera.setFocusedSprite(playable);
        camera.updatePosition(true);

        Level currentLevel = getCurrentLevel();
        if (currentLevel != null) {
            camera.setMinX((short) currentLevel.getMinX());
            camera.setMaxX((short) currentLevel.getMaxX());
            camera.setMinY((short) currentLevel.getMinY());
            camera.setMaxY((short) (bigRingReturn != null
                    ? bigRingReturn.cameraMaxY()
                    : currentLevel.getMaxY()));
            // Vertical wrapping: enabled when minY < 0. The wrap range differs per game:
            // S1 (UNIFIED): 0x800 (DeformLayers.asm LZ3/SBZ2 loop sections)
            // S3K (DUAL_PATH): level height in pixels (e.g. 0x1000 for MGZ1's 32-row map).
            //   The S3K block lookup masks the row index (Layout_row_index_mask=$7C),
            //   so Y coordinates wrap at the map height — rows above the level (negative Y)
            //   map to the bottom rows of the layout.
            if (currentLevel.getMinY() < 0) {
                int wrapRange = isUnifiedCollisionModel()
                        ? Camera.VERTICAL_WRAP_RANGE  // S1: 0x800
                        : cachedFgHeightPx;            // S3K: level height
                camera.setVerticalWrapEnabled(true, wrapRange);
            } else {
                camera.setVerticalWrapEnabled(false);
            }
            verticalWrapEnabled = camera.isVerticalWrapEnabled();
            camera.updatePosition(true);
            if (objectManager != null
                    && (objectManager.usesTwoAxisCursorPlacement()
                            || (camera.getX() != preSnapCameraX
                                    && (!objectManager.usesCounterBasedRespawn()
                                            || resetCounterPlacementAfterCameraSnap)))) {
                // The object manager is constructed before the level-start
                // camera snap. Rebuild its initial window once Camera_X_pos
                // matches the new start, otherwise full reloads can seed
                // objects from the previous level's camera band (e.g. SCZ ->
                // WFZ missing ObjB2 at x=$0060). This also matters for S1 death
                // reloads: seeding from the death camera temporarily occupies
                // low SST slots, then frees them before start-area ring groups
                // execute, reversing parent/child FindFreeObj allocation.
                // S3K also needs this for its separate Y-camera placement pass.
                objectManager.reset(camera.getX(), persistentRespawnState);
            }
            // The first reset in initCameraBounds has already consumed the
            // one-shot load state. Clear the handoff after the final camera
            // snap so a later gameplay reset cannot inherit a return table.
            persistentRespawnStateForCameraSnap = null;
            // ROM parity: only when Get_LevelSizeStart had to clamp the camera
            // Y down to Camera_max_Y_pos does the immediately-following
            // DeformBgLayer call advance Camera_Y_pos past maxY. Levels whose
            // player spawn sits within maxY have no maxY clamp on the snap, so
            // the engine's normal first scroll converges without the ROM quirk.
            // (For S3K AIZ1: player spawn is below maxY, snap clamps to $0390,
            // setup-DeformBgLayer scrolls to $0396; for S1 GHZ1 player spawn is
            // within maxY, no clamp/scroll quirk -- the engine matches ROM
            // exactly without arming the flag.)
        }

        // Apply per-game fast vertical scroll cap from typed camera rules.
        // S1/S2: 16px/frame (s2.asm:18190), S3K: 24px/frame (sonic3k.asm:loc_1C1B0).
        CameraRules cameraRules = cameraRulesFor(activeGameModule());
        if (cameraRules != null) {
            camera.setFastScrollCap(cameraRules.fastScrollCap());
            // ROM S1 leaves the leftward horizontal camera move uncapped (FixBugs=0);
            // S2/S3K cap both directions.
            camera.setUncappedLeftwardScroll(cameraRules.uncappedLeftwardHorizontalScroll());
        }
        persistentRespawnStateForCameraSnap = null;
    }

    private CameraRules cameraRulesFor(GameModule module) {
        GameRules rules = gameRulesFor(module);
        return rules != null ? rules.camera() : null;
    }

    private GameRules gameRulesFor(GameModule module) {
        if (module == null) {
            return null;
        }
        try {
            GameRules rules = module.getRules();
            if (rules != null) {
                return rules;
            }
        } catch (IllegalArgumentException | IllegalStateException ignored) {
        }
        return null;
    }

    /**
     * Step 18: Initialize level events for dynamic boundary updates.
     * All games: LevelEventProvider.initLevel(zone, act).
     */
    public void initLevelEventsForLevel() {
        LevelEventProvider levelEvents = activeGameModule().getLevelEventProvider();
        if (levelEvents != null) {
            levelEvents.initLevel(currentZone, currentAct);
        }
    }

    /**
     * Step 19: Spawn sidekicks (Tails etc.) near the main player.
     * S2: InitPlayers multi-char. S3K: SpawnLevelMainSprites_SpawnPlayers (-$20 X, +4 Y).
     *
     * @param xOffset sidekick X offset from player (negative = behind). S2 uses -40, S3K uses -32.
     * @param yOffset sidekick Y offset from player. S2 uses 0, S3K uses +4.
     */
    public void spawnSidekicks(int xOffset, int yOffset) {
        spriteManager.removeTemporarySidekicks();
        Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
        if (player == null) {
            return;
        }
        if (player instanceof AbstractPlayableSprite leaderInit) {
            // ROM Obj01_Init_Continued (s2.asm:36201-36217) / Sonic_Init_Continued
            // -> Reset_Player_Position_Array (sonic3k.asm:21931-21941, 22166-22178):
            // the leader's own init offsets its position by (-$20, +4), zeroes
            // Sonic_Pos_Record_Index, then runs Sonic_RecordPos 64 times while
            // re-zeroing each Stat_table entry it writes. Neither buffer sits in a
            // GM_Level clearRAM range, so without this the previous level's recorded
            // leader positions/inputs survive a star-post restart or a special-stage
            // return and drive the delayed sidekick follow from stale data.
            leaderInit.resetPositionAndStatTableHistoryAtCentre(
                    (short) (leaderInit.getCentreX() - 0x20),
                    (short) (leaderInit.getCentreY() + 4));
        }
        for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
            // ROM writes only the x_pos/y_pos words here (S2 InitPlayers
            // s2.asm:5191-5195; S3K SpawnLevelMainSprites_SpawnPlayers
            // sonic3k.asm:8364-8367), which on a 68000 leaves the adjacent
            // sub-pixel words untouched -- but the level routine zeroed the
            // whole object RAM block before reaching this point
            // (clearRAM Object_RAM,LevelOnly_Object_RAM_End, s2.asm:4808;
            // clearRAM Object_RAM,(Kos_decomp_buffer-Object_RAM),
            // sonic3k.asm:7619, ahead of the SpawnLevelMainSprites call at
            // :7849), so the sidekick's sub-pixel is ZERO here on every level
            // entry -- including a re-entry such as the special-stage return,
            // which runs the whole Level: routine again.
            sidekick.setCentreX((short) (player.getCentreX() + xOffset));
            sidekick.setCentreY((short) (player.getCentreY() + yOffset));
            sidekick.setXSpeed((short) 0);
            sidekick.setYSpeed((short) 0);
            sidekick.setGSpeed((short) 0);
            sidekick.setAir(false);
            sidekick.setDead(false);
            sidekick.setDeathCountdown(0);
            sidekick.setHighPriority(false);
            sidekick.setDirection(Direction.RIGHT);
            // The sidekick inherits the leader's collision-path pair, and the
            // ROM does it unconditionally in both games that have one. S2
            // Obj02_Init branches on `cmpi.w #2,(Player_mode).w` -- mode 2 is
            // TAILS ALONE, so the `Obj02_Init_2Pmode` label is a misnomer: it
            // is the normal Sonic-and-Tails path, and it runs
            // `move.w (MainCharacter+top_solid_bit).w,top_solid_bit(a0)`
            // (docs/s2disasm/s2.asm:38907-38928). The `$C`/`$D` write below it
            // is the Tails-alone branch. S3K Tails_Init is the same shape:
            // `cmpi.w #2,(Player_mode).w / bne.s loc_1375E`, and loc_1375E runs
            // `move.w (Player_1+top_solid_bit).w,top_solid_bit(a0)`
            // (docs/skdisasm/sonic3k.asm:26105-26133). Sonic 1 has no sidekick.
            //
            // It is a WORD move in both, so it carries lrb_solid_bit with it.
            // The leader's own init runs first -- Obj01/Sonic occupies the slot
            // before Obj02/Tails in the same object pass -- so by here the
            // leader already holds whatever the star post or special-stage
            // return restored (S2 Obj79_LoadData, s2.asm:44787). Without this
            // the sidekick keeps the engine's `$C`/`$D` default and probes the
            // primary collision array while the leader is on the secondary one,
            // which reads a different floor angle and a different surface Y on
            // the same slope.
            if (player instanceof AbstractPlayableSprite leaderPaths) {
                sidekick.setTopSolidBit(leaderPaths.getTopSolidBit());
                sidekick.setLrbSolidBit(leaderPaths.getLrbSolidBit());
            }
            if (sidekick.getCpuController() != null) {
                applySidekickLevelBounds(sidekick);
                // Capture the spawn centre for the deferred CPU placement. ROM
                // SpawnLevelMainSprites_SpawnPlayers places the sidekick and fills
                // Sonic_Pos_Record_Buf before the first LevelLoop physics tick
                // (sonic3k.asm:8359-8369), while the leader is still at this centre.
                if (player instanceof AbstractPlayableSprite leaderSprite) {
                    SidekickCpuController controller = sidekick.getCpuController();
                    controller.captureLevelStartLeaderAnchor(
                            leaderSprite.getCentreX(),
                            leaderSprite.getCentreY());
                    if (controller.getLeader() == leaderSprite) {
                        controller.adoptLevelStartLeaderHistoryPrefill();
                    }
                }
            }
        }
    }

    /**
     * Re-establishes the CPU sidekick's own level boundary words from the
     * level's boundary values.
     *
     * <p>ROM {@code LevelSizeLoad} writes {@code Tails_Min_X_pos} /
     * {@code Tails_Max_X_pos} and {@code Tails_Min_Y_pos} /
     * {@code Tails_Max_Y_pos} from the same {@code LevelSize} table longs that
     * seed {@code Camera_Min_X_pos} / {@code Camera_Max_Y_pos}
     * (docs/s2disasm/s2.asm:14695-14706), on <em>every</em> entry to the
     * {@code Level:} routine — including the special-stage return, which
     * re-runs the whole level routine. Those words are what
     * {@code Obj02_CheckGameOver} reads before branching to
     * {@code TailsCPU_Despawn} (s2.asm:41146-41155) and what
     * {@code Tails_LevelBound} clamps against, so leaving them unset disables
     * the sidekick kill plane entirely.
     *
     * <p>Shared by the level-load path ({@link #spawnSidekicks}) and the
     * special-stage-return re-init replica in {@code GameLoop}, which resets
     * the CPU controller and must restore the same boundary words rather than
     * leaving them cleared.
     */
    public void applySidekickLevelBounds(AbstractPlayableSprite sidekick) {
        if (sidekick == null || sidekick.getCpuController() == null) {
            return;
        }
        sidekick.getCpuController().setLevelBounds(
                (int) camera.getMinX(),
                (int) camera.getMaxX(),
                (int) Math.max(camera.getMaxY(), camera.getMaxYTarget()));
    }

    /**
     * Step 20: Request title card display.
     * Skipped in headless mode and when zone feature provider suppresses it.
     */
    public void requestTitleCardIfNeeded(LevelLoadContext ctx) {
        initialPresentationPlcsCompleted = false;
        initialPresentationOmitted = false;
        boolean headlessWholeRunHandoff = graphicsManager.isHeadlessMode()
                && GameServices.playbackDebug().hasScheduledLevelLoadSession();
        if (!ctx.isShowTitleCard()) {
            completeSkippedInitialTitleCardPresentation(ctx.isQueueFreshLevelRuntimeArt());
            return;
        }
        // GameLoop presents the bonus- and special-stage-results return cards
        // itself after the reload; both loads must request rather than model an
        // omitted presentation, keeping the queued initial PLCs live for the
        // presented card's locked loop.
        boolean callerOwnedReturnCard = transitions.isBonusStageReturn()
                || transitions.isResultsReturnCardOwnedByCaller();
        boolean presentationSuppressed = !callerOwnedReturnCard
                && zoneFeatureProvider != null
                && zoneFeatureProvider.shouldSuppressInitialTitleCard(
                        currentZone, currentAct);
        if (presentationSuppressed) {
            // A suppressed presentation models a load where ROM never creates
            // the title-card owner at all (S3K Level init skips the
            // Obj_TitleCard install for a fresh AIZ1 Sonic/Tails game:
            // docs/skdisasm/sonic3k.asm:7702-7709 branch past 7728-7735).
            // With no owner object there is no Obj_TitleCardWait2 teardown and
            // no LoadEnemyArt submission — the intro installs the title card
            // itself much later (sonic3k.asm:114393-114396). Reach the PLC
            // boundary without starting the skipped-presentation teardown
            // model.
            completeInitialTitleCardPresentation();
            return;
        }
        // Runtime-art ownership is a property of the fresh load, not of
        // whether this process renders the title card. Headless gameplay still
        // retains the native title owner through its omitted-presentation exit
        // tail, so arm that owner's handoff before choosing the render path.
        if (ctx.isQueueFreshLevelRuntimeArt()) {
            var titleCardProvider = activeGameModule().getTitleCardProvider();
            if (titleCardProvider != null) {
                titleCardProvider.requestFreshLevelRuntimeArtHandoff(
                        ctx.getLevelIndex());
            }
        }
        // GameLoop owns the mandatory bonus/results-return card after the
        // reload. Keep its lease unbound until that explicit initialization,
        // even headless.
        // A re-entry into the game's own Level: routine from a running level
        // reaches the locked title-card loop by construction — S1's end-of-act
        // card writes only f_restart and the main loop falls straight back into
        // GM_Level (docs/s1disasm/_incObj/3A Got Through Card.asm:200-211,
        // sonic.asm:3041-3055, 2814-2842). Its card is part of the restart, not
        // of a host entry a headless boundary may omit.
        if (!graphicsManager.isHeadlessMode()
                || headlessWholeRunHandoff
                || ctx.isTitleCardRequiredInHeadlessMode()
                || callerOwnedReturnCard
                || transitions.isLevelRoutineReentry()) {
            // ROM: title card reads Apparent_act, not Current_act.
            // After AIZ's seamless fire transition, Current_act is 1 but
            // Apparent_act stays 0 until the results screen exits.
            requestTitleCard(currentZone, apparentAct);
            return;
        }

        completeSkippedInitialTitleCardPresentation(ctx.isQueueFreshLevelRuntimeArt());
    }

    /**
     * Consumes one pending initial title-card request and reaches the same
     * production PLC boundary as an intentionally omitted presentation.
     *
     * <p>This is the live-tool/headless transition boundary. Callers cannot
     * select game-specific phase counts or mutate a PLC service directly.
     */
    public boolean skipPendingInitialTitleCardPresentation() {
        if (!transitions.consumeTitleCardRequest()) {
            return false;
        }
        // A consumed request already ran the owner's init through the
        // presentation path, so this boundary queues no entry art.
        completeSkippedInitialTitleCardPresentation(false);
        return true;
    }

    private void completeSkippedInitialTitleCardPresentation(boolean ownsFreshArt) {
        initialPresentationOmitted = true;
        completeInitialTitleCardPresentation();
        // A headless fresh load omits presentation, but the title-card owner
        // object is not deleted with it: it keeps running through
        // Obj_TitleCardWait2 and only opens runtime-art admission via
        // LoadEnemyArt once its own counter and element drain finish. Tell the
        // provider the presentation was skipped and let it model that lifetime
        // rather than retiring the art on the first level frame.
        // docs/skdisasm/sonic3k.asm:62249-62261, 62295-62301, 64302-64309
        var objectArtProvider = activeGameModule().getObjectArtProvider();
        if (objectArtProvider != null) {
            objectArtProvider.onTitleCardPresentationSkipped();
        }
        // Omitting the presentation does not end the title card's object
        // lifetime. Sonic 2's pieces survive the locked loop and run
        // Obj34_WaitAndGoAway on ordinary gameplay frames, loading the
        // standard-water and per-zone animal art on the frame the zone-name
        // piece leaves the screen.
        // docs/s2disasm/s2.asm:4914-4925, 5066-5080, 27605-27637
        // Obj_TitleCardInit's four Queue_Kos_Module calls happen on the owner's
        // first dispatch, before anything is drawn, so they belong to a load
        // that reached the game's own Level: routine rather than to the
        // presentation a headless boundary omits.
        // docs/skdisasm/sonic3k.asm:62108-62164
        var titleCardProvider = activeGameModule().getTitleCardProvider();
        if (titleCardProvider != null) {
            titleCardProvider.beginOmittedPresentation(currentZone, apparentAct, ownsFreshArt);
        }
    }

    /**
     * Reaches the ROM's game-owned PLC boundary between the locked initial
     * title-card loop and the first ordinary level iteration.
     */
    public void completeInitialTitleCardPresentation() {
        var profile = activeGameModule().getLevelInitProfile();
        // The prelude that stages the player's tiles runs before this boundary,
        // and its transfer belongs to the first V-int of the Level: routine's
        // counted pre-main-loop tail — the Level_Delay / PalFadeIn_Alt rows
        // ending on the frame before Level_MainLoop — not to this frame and
        // not to the first gameplay claim after the transition gap. A load
        // whose PLC boundary was already reached still stages here, so hold
        // the transfer before the completed-boundary check.
        var dynamicArt = GameServices.dynamicArtLifecycleOrNull();
        if (dynamicArt != null && dynamicArt.isRunActive()) {
            dynamicArt.holdPendingPlayerPreparationForPreMainLoopTail(
                    profile.preLevelMainLoopDelayFrames());
        }
        if (initialPresentationPlcsCompleted) {
            return;
        }
        profile.completeInitialPresentationPlcs();
        // Only an OMITTED presentation needs its player object passes replayed.
        // A presented card really dispatches them: the provider runs the ROM's
        // own pre-Level_MainLoop passes (S2: the s2.asm:5006 RunObjects plus
        // the 25 iterations of the s2.asm:5060-5066 leave loop) with player
        // physics live, so replaying them here would advance the animation and
        // the persistent last-loaded-DPLC byte a second time.
        if (initialPresentationOmitted) {
            replaySkippedPresentationPlayerAnimation(
                    profile.skippedPresentationPlayableFrames(),
                    profile.skippedPresentationPlayableFramesBeforeFirstVBlank());
        }
        initialPresentationPlcsCompleted = true;
    }

    /**
     * Establishes the ROM's persistent last-loaded-DPLC residency before the
     * first ordinary level iteration.
     *
     * <p>{@code InitPlayers} creates the player objects at
     * docs/s2disasm/s2.asm:4945, i.e. before the title-card leave loop at
     * docs/s2disasm/s2.asm:5060-5066 (WaitForVint / RunObjects / BuildSprites /
     * RunPLC_RAM). That loop therefore runs {@code Sonic_Animate} and
     * {@code LoadSonicDynPLC} with the players loaded, so
     * {@code Sonic_LastLoadedDPLC} (docs/s2disasm/s2.asm:38829-38840) and
     * {@code Tails_LastLoadedDPLC} (docs/s2disasm/s2.asm:41659-41690) already
     * hold the displayed mapping frame when {@code Level_MainLoop} starts; they
     * are cleared to -1 only on a character swap (docs/s2disasm/s2.asm:26039-26041).
     * A headless load omits that presentation, so the first gameplay animation
     * tick saw "no previous frame" and submitted a DMA transfer the ROM had
     * already retired. Replaying the loop's own animation ticks, at the ROM's
     * VBlank-then-RunObjects order (queue at s2.asm:1713, drain at s2.asm:1769),
     * makes gameplay frame 0 submit exactly when the mapping frame really
     * changed across the loop. S1 (docs/s1disasm/_incObj/01 Sonic.asm:2391-2398)
     * and S3K (docs/skdisasm/sonic3k.asm:25216-25218) use the same predicate;
     * they contribute no iterations unless their own init profile declares one.
     */
    private void replaySkippedPresentationPlayerAnimation(
            int iterations, int passesBeforeFirstVBlank) {
        if (iterations <= 0 || spriteManager == null) {
            return;
        }
        List<AbstractPlayableSprite> playables = new ArrayList<>();
        AbstractPlayableSprite main = spriteManager.getMainPlayable();
        if (main != null) {
            playables.add(main);
        }
        playables.addAll(spriteManager.getSidekicks());
        if (playables.isEmpty()) {
            return;
        }
        var dynamicArt = GameServices.dynamicArtLifecycleOrNull();
        for (int frame = 0; frame < iterations; frame++) {
            // The leading object passes the profile declares run outside the
            // omitted presentation's wait loop, so no V-blank precedes them;
            // the loop's first WaitForVint drains the queue they built.
            if (frame >= passesBeforeFirstVBlank
                    && dynamicArt != null && dynamicArt.isRunActive()) {
                dynamicArt.serviceProductionVBlank();
            }
            for (AbstractPlayableSprite playable : playables) {
                playable.getAnimationManager().update(frame);
            }
        }
    }

    /**
     * Resolves the {@link LevelData} for the current zone and act from the
     * {@code levels} map.
     * <p>
     * Used as a fallback when {@code LevelLoadContext.levelData} has not been
     * pre-seeded by the caller. Returns {@code null} if the levels map is
     * empty or the current zone/act is out of bounds.
     */
    private LevelData resolveLevelData() {
        if (levels.isEmpty() || currentZone < 0 || currentZone >= levels.size()) {
            return null;
        }
        List<LevelData> acts = levels.get(currentZone);
        if (acts == null || currentAct < 0 || currentAct >= acts.size()) {
            return null;
        }
        return acts.get(currentAct);
    }

    /**
     * Loads the current level with title card.
     * Use this for fresh level starts (zone/act changes).
     */
    public void loadCurrentLevel() {
        loadCurrentLevel(true);
    }

    /**
     * Reloads the current level the way a death restart reaches it.
     *
     * <p>ROM: {@code Sonic_ResetLevel} writes nothing but {@code f_restart}
     * (docs/s1disasm/_incObj/01 Sonic.asm:2062-2073) and the level main loop's
     * own {@code tst.w (f_restart).w} falls straight back into {@code GM_Level}
     * (sonic.asm:3016-3018), which runs the whole routine including the locked
     * {@code Level_TtlCardLoop} (sonic.asm:2814-2842) exactly as a first entry
     * does. That makes the card part of the restart rather than of a host
     * entry a headless boundary may omit — the same reason
     * {@link #advanceToNextLevel()} marks its load.
     */
    public void restartCurrentLevelAfterDeath() {
        transitions.setLevelRoutineReentry(true);
        try {
            loadCurrentLevel(true);
        } finally {
            transitions.setLevelRoutineReentry(false);
        }
    }

    /**
     * Loads the current level for death respawn (no title card).
     */
    public void respawnPlayer() {
        loadCurrentLevel(false);
    }

    public void loadCurrentLevel(LevelLoadMode loadMode, boolean showTitleCard) {
        loadCurrentLevel(showTitleCard, loadMode, true);
    }

    /**
     * Loads the current level with optional title card.
     *
     * @param showTitleCard true to show title card on fresh starts, false for death
     *                      respawns
     */
    private void loadCurrentLevel(boolean showTitleCard) {
        loadCurrentLevel(showTitleCard, LevelLoadMode.FULL, true);
    }

    private void loadCurrentLevel(
            boolean showTitleCard, LevelLoadMode loadMode, boolean runtimeReload) {
        loadCurrentLevel(showTitleCard, loadMode, runtimeReload, false, false);
    }

    private void loadCurrentLevel(
            boolean showTitleCard, LevelLoadMode loadMode, boolean runtimeReload,
            boolean titleCardRequiredInHeadlessMode) {
        loadCurrentLevel(showTitleCard, loadMode, runtimeReload,
                titleCardRequiredInHeadlessMode, false);
    }

    private void loadCurrentLevel(
            boolean showTitleCard, LevelLoadMode loadMode, boolean runtimeReload,
            boolean titleCardRequiredInHeadlessMode,
            boolean queueFreshLevelRuntimeArt) {
        discardPreparedLevelLoad();
        try {
            // V_int_run_count is global work RAM, outside Dynamic_object_RAM.
            // A full death/results reload rebuilds ObjectManager just like the
            // seamless act-transition path, but must carry this clock across
            // the rebuild so slot-phased object gates (e.g. Batbrain) retain
            // their native timing.
            int inheritedVblaCounter = objectManager != null ? objectManager.getVblaCounter() : 0;
            int inheritedVIntRunCounterPhaseOffset = objectManager != null
                    ? objectManager.getVIntRunCounterPhaseOffset()
                    : 0;
            transitions.setSpecialStageReturnLevelReloadRequested(false);
            transitions.setLevelInactiveForTransition(false);

            // ROM: SSEntryFlash_GoSS sets Respawn_table_keep before entering
            // the special stage, and the return path skips clearing
            // Object_respawn_table (skdisasm/sonic3k.asm:128446-128451,
            // 37457-37465). Reapply the captured table before InitObjectSystem
            // materializes the new level window. Last_star_post_hit is the
            // same gate used by Get_LevelSizeStart for Saved2_* restoration.
            if (transitions.hasBigRingReturn()
                    && transitions.isLastStarPostHitSet()
                    && transitions.getBigRingReturnRespawnState() != null) {
                persistentRespawnStateForNextObjectReset =
                        transitions.getBigRingReturnRespawnState();
            }

            if (levels.isEmpty()) {
                // ROM is already loaded by Engine.initializeGame(), so
                // GameModuleRegistry has the correct module. Just bootstrap
                // the zone list for level data lookup.
                gameModule = GameServices.module();
                collisionLayoutYMask = resolveCollisionLayoutYMask();
                refreshZoneList();
            }
            LevelData levelData = levels.get(currentZone).get(currentAct);

            LevelLoadContext ctx = new LevelLoadContext();
            ctx.setShowTitleCard(showTitleCard);
            ctx.setTitleCardRequiredInHeadlessMode(titleCardRequiredInHeadlessMode);
            ctx.setQueueFreshLevelRuntimeArt(queueFreshLevelRuntimeArt);
            ctx.setLevelData(levelData);
            ctx.setIncludePostLoadAssembly(true);
            ctx.setAssemblyKind(LevelAssemblyKind.FRESH_LEVEL_ASSEMBLY);
            ctx.snapshotCheckpoint(checkpointCoordinator.state());

            resetCounterPlacementAfterCameraSnap = runtimeReload;
            loadLevel(levelData.getLevelIndex(), loadMode, ctx);
            if (loadMode != LevelLoadMode.PREVIEW_CAPTURE) {
                applyPersistedEditorEdits();
            }
            restoreCheckpointRuntimeState(ctx);

            if (objectManager != null) {
                objectManager.initVblaCounter(inheritedVblaCounter);
                objectManager.initVIntRunCounterPhaseOffset(inheritedVIntRunCounterPhaseOffset);
            }

            frameCounter = 0;
            sidekickRomVisibleReloadFrameCounterBridgeActive = false;
            sidekickRomVisibleReloadFrameCounterBridgePrimed = false;
            activateScheduledPlaybackForLoadedLevel();

        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            resetCounterPlacementAfterCameraSnap = false;
        }
    }

    /**
     * Completes a whole-run playback rebind at the common level-load seam.
     * Results objects can load the next level synchronously from inside their
     * own object tick, so this cannot live only in {@code GameLoop}'s fade
     * callbacks. Apply frame-0 input to the rebuilt player immediately: the
     * caller may continue into that player's first tick before the loop-top
     * playback bridge runs again.
     */
    private void activateScheduledPlaybackForLoadedLevel() {
        PlaybackDebugManager playback = GameServices.playbackDebug();
        if (!com.openggf.TraceSessionLauncher.activateScheduledPlaybackForLoadedLevel(playback)) {
            return;
        }
        GameLoop loop = Engine.currentGameLoop();
        if (loop != null) {
            loop.applyScheduledPlaybackInputImmediately();
        }
    }

    private void applyPersistedEditorEdits() {
        if (level == null || gameModule == null) {
            return;
        }
        if (editorSaveManager == null || !editorSaveManager.supportsRuntimeEditApply(gameModule.getGameId())) {
            return;
        }
        MutableLevel mutableLevel = level instanceof MutableLevel existing
                ? existing
                : MutableLevel.snapshot(level);
        EditorSaveManager.ApplyResult result = editorSaveManager.tryApplyEdits(gameModule.getGameId(), currentZone, currentAct, mutableLevel);
        if (result == EditorSaveManager.ApplyResult.APPLIED && mutableLevel != level) {
            setLevel(mutableLevel);
        }
    }

    public void nextAct() throws IOException {
        writeCurrentAct(currentAct + 1);
        if (currentAct >= levels.get(currentZone).size()) {
            writeCurrentAct(0);
        }
        writeApparentAct(currentAct);
        // Clear checkpoint when manually changing level
        checkpointCoordinator.clear();
        com.openggf.TraceSessionLauncher.runLevelAdvanceLoad(this::loadCurrentLevel);
    }

    /**
     * Advance to the next level in progression order.
     * Unlike nextAct() which wraps, this advances to next zone when acts are
     * exhausted.
     * Called by results screen after tally completes.
     */
    public void advanceToNextLevel() throws IOException {
        writeCurrentAct(currentAct + 1);
        if (currentAct >= levels.get(currentZone).size()) {
            // Move to next zone
            writeCurrentZone(currentZone + 1);
            writeCurrentAct(0);
            if (currentZone >= levels.size()) {
                requestCreditsTransition();
                return;
            }
        }
        writeApparentAct(currentAct);
        // Clear checkpoint when advancing
        checkpointCoordinator.clear();
        // ROM: the end-of-act card only sets f_restart, so this load is a
        // Level: re-entry rather than a fresh host entry — it runs the whole
        // routine including Level_TtlCardLoop.
        // docs/s1disasm/_incObj/3A Got Through Card.asm:200-211, sonic.asm:3041-3055
        transitions.setLevelRoutineReentry(true);
        try {
            com.openggf.TraceSessionLauncher.runLevelAdvanceLoad(this::loadCurrentLevel);
        } finally {
            transitions.setLevelRoutineReentry(false);
        }
    }

    /**
     * Advances zone/act counters without loading the level.
     * Used when entering special stage from big ring - the ROM advances
     * the level counters before entering the special stage (Got_NextLevel).
     */
    public void advanceZoneActOnly() {
        writeCurrentAct(currentAct + 1);
        if (currentAct >= levels.get(currentZone).size()) {
            writeCurrentZone(currentZone + 1);
            writeCurrentAct(0);
            if (currentZone >= levels.size()) {
                writeCurrentZone(0);
            }
        }
        writeApparentAct(currentAct);
        checkpointCoordinator.clear();
        transitions.setSpecialStageReturnLevelReloadRequested(true);
    }

    public void loadZoneAndAct(int zone, int act) throws IOException {
        loadZoneAndAct(zone, act, LevelLoadMode.FULL);
    }

    public void loadZoneAndAct(int zone, int act, LevelLoadMode loadMode) throws IOException {
        loadZoneAndAct(zone, act, loadMode, false, false);
    }

    /** Loads a production fresh level while leaving presentation ownership to GameLoop. */
    public void loadZoneAndActForFreshRuntime(int zone, int act) throws IOException {
        loadZoneAndAct(zone, act, LevelLoadMode.FULL, false, true);
    }

    /**
     * Loads a destination reached through a normal zone/act transition while
     * retaining the production title-card owner in headless mode.
     *
     * <p>Ordinary standalone headless level loads intentionally omit the
     * initial presentation. A whole-run handoff is different: the ROM has
     * loaded the destination and entered its blocking title-card loop, whose
     * hardware-timed art work is part of the destination lifecycle.
     */
    public void loadZoneAndActWithTitleCard(int zone, int act) throws IOException {
        loadZoneAndAct(zone, act, LevelLoadMode.FULL, true, true);
    }

    /**
     * Loads a fresh destination while exposing the native pre-title-card
     * boundary to a deterministic frame driver.
     *
     * <p>The S3K {@code Level:} routine clears the old playable slots before
     * the title-card loop, then assembles the destination players after that
     * loop. The normal production load API keeps the complete assembly
     * synchronous for callers that need an immediately playable level. This
     * companion retains the assembled destination state for release, but
     * presents the cleared transition boundary until
     * {@link #completeFreshLevelTransitionBoundary()} is called.</p>
     */
    public void loadZoneAndActAtFreshTitleCardBoundary(int zone, int act)
            throws IOException {
        if (pendingFreshLevelTransitionBoundary != null) {
            throw new IllegalStateException(
                    "a fresh title-card transition boundary is already pending");
        }
        short previousCameraX = camera.getX();
        short previousCameraY = camera.getY();
        int previousRings = levelGamestate != null ? levelGamestate.getRings() : 0;

        loadZoneAndActWithTitleCard(zone, act);

        List<TransitionPlayableState> playableStates = new ArrayList<>();
        for (Sprite sprite : spriteManager.getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                playableStates.add(new TransitionPlayableState(
                        playable.getCode(), playable.captureRewindState(false)));
            }
        }
        // Level's Get_LevelSizeStart snaps the new camera from the loaded
        // player's ROM centre before the title-card loop owns the transition.
        // Reapply that force formula here rather than retaining a camera value
        // left by the synchronous Java assembly tail.
        AbstractPlayableSprite destinationPlayer = mainPlayableSprite();
        if (destinationPlayer != null) {
            camera.setFocusedSprite(destinationPlayer);
            camera.updatePosition(true);
        }
        short destinationCameraX = camera.getX();
        short destinationCameraY = camera.getY();

        // Level: has cleared Dynamic_object_RAM and both player slots at this
        // point, but retains the prior camera/ring globals until the title
        // loop's post-load tail reinitializes them.
        camera.setX(previousCameraX);
        camera.setY(previousCameraY);
        if (levelGamestate != null) {
            levelGamestate.setRings(previousRings);
        }
        AbstractPlayableSprite player = mainPlayableSprite();
        if (player != null) {
            player.setRolling(false);
            player.setCentreX((short) 0);
            player.setCentreY((short) 0);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
            player.setAir(false);
            player.setJumping(false);
            player.setAnimationId(0);
            player.setMappingFrame(0);
            player.setObjectRoutineOverride(0);
            player.setNativeSlotPresent(true);
        }
        for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
            sidekick.setCentreX((short) 0);
            sidekick.setCentreY((short) 0);
            sidekick.setXSpeed((short) 0);
            sidekick.setYSpeed((short) 0);
            sidekick.setGSpeed((short) 0);
            sidekick.setAir(false);
            sidekick.setNativeSlotPresent(false);
        }
        discardPendingInitialProcessSpritesForStateRestoration();
        pendingFreshLevelTransitionBoundary = new FreshLevelTransitionBoundary(
                previousCameraX,
                previousCameraY,
                previousRings,
                destinationCameraX,
                destinationCameraY,
                playableStates);
    }

    /** Completes the destination-player assembly after its title-card loop. */
    public void completeFreshLevelTransitionBoundary() {
        FreshLevelTransitionBoundary boundary = pendingFreshLevelTransitionBoundary;
        if (boundary == null) {
            return;
        }
        for (TransitionPlayableState playableState : boundary.playableStates()) {
            Sprite sprite = spriteManager.getSprite(playableState.code());
            if (sprite instanceof AbstractPlayableSprite playable) {
                playable.restoreRewindState(playableState.state());
                playable.setObjectRoutineOverride(null);
                playable.setNativeSlotPresent(true);
            }
        }
        applyFreshLevelTransitionDestinationCamera(boundary);
        pendingFreshLevelTransitionBoundary = null;
    }

    /**
     * Publishes the destination player for the first post-title load row while
     * retaining the transition boundary for the following ordinary loop. The
     * ROM's level setup exposes the spawn state with the main object routine
     * still at zero and without the sidekick slot; the next full loop releases
     * those setup holds through {@link #completeFreshLevelTransitionBoundary()}.
     */
    public void publishFreshLevelTransitionInitialBoundary() {
        FreshLevelTransitionBoundary boundary = pendingFreshLevelTransitionBoundary;
        if (boundary == null) {
            return;
        }
        for (TransitionPlayableState playableState : boundary.playableStates()) {
            Sprite sprite = spriteManager.getSprite(playableState.code());
            if (sprite instanceof AbstractPlayableSprite playable) {
                playable.restoreRewindState(playableState.state());
                playable.setObjectRoutineOverride(null);
                playable.setNativeSlotPresent(true);
            }
        }
        AbstractPlayableSprite player = mainPlayableSprite();
        if (player != null) {
            // The first destination row exposes SpawnLevelMainSprites' newly
            // allocated player slot before the initial Process_Sprites pass
            // initializes its status and animation fields. Keep the fully
            // assembled state above for the following release, but publish
            // the native pre-dispatch boundary here.
            player.clearAirForNativeControlRestore();
            short centreY = player.getCentreY();
            player.setRolling(false);
            player.setCentreYPreserveSubpixel(centreY);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
            player.setJumping(false);
            player.setAnimationId(0);
            player.setMappingFrame(0);
            player.setAnimationFrameIndex(0);
            player.setAnimationTick(0);
            player.setForcedAnimationId(-1);
            player.setObjectMappingFrameControl(false);
            player.setTopSolidBit((byte) 0);
            player.setLrbSolidBit((byte) 0);
            player.setObjectRoutineOverride(0);
        }
        for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
            sidekick.setNativeSlotPresent(false);
        }
        applyFreshLevelTransitionDestinationCamera(boundary);
    }

    /** Publishes the native camera handoff while playable slots remain held. */
    public void completeFreshLevelTransitionCameraBoundary() {
        FreshLevelTransitionBoundary boundary = pendingFreshLevelTransitionBoundary;
        if (boundary == null) {
            return;
        }
        applyFreshLevelTransitionDestinationCamera(boundary);
    }

    private void applyFreshLevelTransitionDestinationCamera(
            FreshLevelTransitionBoundary boundary) {
        AbstractPlayableSprite player = mainPlayableSprite();
        if (player != null) {
            camera.setFocusedSprite(player);
        }
        // setFocusedSprite() also seeds the camera from the sprite's render
        // bounds. Publish the ROM Get_LevelSizeStart result after focusing so
        // that the transition boundary keeps the native centre-based camera
        // position rather than the sprite's top-left render coordinate.
        camera.setX(boundary.destinationCameraX());
        camera.setY(boundary.destinationCameraY());
    }

    /**
     * Returns whether the loaded destination is still at the native
     * pre-title-card player boundary owned by the recording/transition driver.
     */
    public boolean hasPendingFreshLevelTransitionBoundary() {
        return pendingFreshLevelTransitionBoundary != null;
    }

    private AbstractPlayableSprite mainPlayableSprite() {
        Sprite player = spriteManager.getSprite(resolveMainCharacterCode());
        return player instanceof AbstractPlayableSprite playable ? playable : null;
    }

    private void loadZoneAndAct(
            int zone, int act, LevelLoadMode loadMode,
            boolean titleCardRequiredInHeadlessMode,
            boolean queueFreshLevelRuntimeArt) throws IOException {
        try {
            writeCurrentAct(act);
            writeApparentAct(act);
            writeCurrentZone(zone);
            // Clear checkpoint when manually changing level
            checkpointCoordinator.clear();
            loadCurrentLevel(
                    loadMode != LevelLoadMode.PREVIEW_CAPTURE,
                    loadMode,
                    false,
                    titleCardRequiredInHeadlessMode,
                    queueFreshLevelRuntimeArt);
        } finally {
            // A load that fails before initCameraBounds must not leak a stage-return
            // respawn table into a later, potentially different, level.
            persistentRespawnStateForNextObjectReset = null;
            persistentRespawnStateForCameraSnap = null;
        }
    }

    /**
     * Performs a ROM-aligned act transition: reloads layout + collision,
     * resets managers, applies offsets, and restores camera bounds.
     * <p>
     * This bypasses the profile system entirely because act transitions
     * are NOT level loads in the ROM — they are in-place data swaps
     * performed by level event background routines.
     * <p>
     * ROM reference: S3K zone BG event handlers (e.g. AIZ Act 2 transition
     * at sonic3k.asm). Pattern: set zone/act → Load_Level + LoadSolids →
     * Offset_ObjectsDuringTransition → clear managers → restore camera bounds.
     *
     * @param request the transition request with target zone/act, offsets, etc.
     * @throws IOException if level data loading fails
     */
    public void executeActTransition(SeamlessLevelTransitionRequest request) throws IOException {
        actTransitionExecutor.execute(request);
    }

    void markActTransitionOscillationAdvancedDuringFrame() {
        actTransitionExecutor.markOscillationAdvancedDuringFrame();
    }

    /**
     * Consumes the marker set when an in-place act transition ran inside the
     * current level loop. The transition-owned oscillator dispatch replaces
     * this frame's ordinary loop-tail advance.
     */
    public boolean consumeActTransitionExecutedDuringFrame() {
        return actTransitionExecutor.consumeExecutedDuringFrame();
    }

    /**
     * Consumes the one-shot rewind boundary published by a completed in-place
     * act reload. Frame-top seamless transitions consume this signal through
     * {@link LevelSeamlessTransitionExecutor}; reloads executed inside the
     * level-event pass leave it for {@code GameLoop} to apply after recording
     * the rest of the logical frame.
     */
    public boolean consumeActTransitionRewindBoundaryDuringFrame() {
        return actTransitionExecutor.consumeRewindBoundaryDuringFrame();
    }

    public boolean consumeActTransitionOscillationAdvancedDuringFrame() {
        return actTransitionExecutor.consumeOscillationAdvancedDuringFrame();
    }

    void restoreCameraBoundsForCurrentLevel(Camera cam) {
        Level currentLevel = getCurrentLevel();
        if (currentLevel == null) {
            return;
        }
        cam.setMinX((short) currentLevel.getMinX());
        cam.setMaxX((short) currentLevel.getMaxX());
        cam.setMinY((short) currentLevel.getMinY());
        cam.setMaxY((short) currentLevel.getMaxY());
        if (currentLevel.getMinY() < 0) {
            int wrapRange = isUnifiedCollisionModel()
                    ? Camera.VERTICAL_WRAP_RANGE
                    : cachedFgHeightPx;
            cam.setVerticalWrapEnabled(true, wrapRange);
        } else {
            cam.setVerticalWrapEnabled(false);
        }
        verticalWrapEnabled = cam.isVerticalWrapEnabled();
    }

    void applyPostTransitionCameraOverrides(SeamlessLevelTransitionRequest request, Camera cam) {
        if (request == null) {
            return;
        }
        Integer minX = request.postTransitionMinX();
        if (minX != null) {
            cam.setMinX((short) (int) minX);
        }
        Integer maxX = request.postTransitionMaxX();
        if (maxX != null) {
            cam.setMaxX((short) (int) maxX);
        }
        Integer minY = request.postTransitionMinY();
        if (minY != null) {
            cam.setMinY((short) (int) minY);
        }
        Integer maxY = request.postTransitionMaxY();
        if (maxY != null) {
            cam.setMaxY((short) (int) maxY);
        }
        Integer maxYTarget = request.postTransitionMaxYTarget();
        if (maxYTarget != null) {
            cam.setMaxYTarget((short) (int) maxYTarget);
        }
    }

    /**
     * Shifts persistent dynamic objects carried across a seamless reload by the
     * transition world delta, mirroring ROM {@code Offset_ObjectsDuringTransition}.
     * The delta matches the player offset (player/camera/object offsets are the
     * same world shift for every S3K seamless act transition).
     */
    void offsetCarriedObjectsForTransition(List<ObjectInstance> carried,
                                                   SeamlessLevelTransitionRequest request) {
        if (request == null || carried == null || carried.isEmpty()) {
            return;
        }
        int offsetX = request.playerOffsetX();
        int offsetY = request.playerOffsetY();
        CarriedTitlePublicationTiming titleTiming =
                CarriedTitlePublicationTiming.from(request);
        for (ObjectInstance instance : carried) {
            if (instance != null && !instance.isDestroyed()) {
                instance.onCarriedAcrossSeamlessTransition(offsetX, offsetY, titleTiming);
            }
        }
    }

    void applySeamlessOffsets(SeamlessLevelTransitionRequest request, Camera cam) {
        if (request == null) {
            return;
        }
        if (cam.getFocusedSprite() instanceof AbstractPlayableSprite playable) {
            int newX = playable.getCentreX() + request.playerOffsetX();
            int newY = playable.getCentreY() + request.playerOffsetY();
            // ROM transition offset code adjusts the position words only
            // (for AIZ1->AIZ2: sub.w d0/d1 from x_pos/y_pos). The subpixel
            // words must survive the reload or fixed-point motion resumes from
            // the wrong fraction.
            playable.setCentreXPreserveSubpixel((short) newX);
            playable.setCentreYPreserveSubpixel((short) newY);
            // The level reload replaced the pattern buffer; force DPLC re-upload
            // so the player sprite is visible on the next draw.
            if (playable.getSpriteRenderer() != null) {
                playable.getSpriteRenderer().invalidateDplcCache();
            }
            // Persistent insta-shield survives transitions but the ObjectManager was rebuilt
            // (rebuildManagersForActTransition creates a new one). Re-register + invalidate DPLC.
            if (playable.getInstaShieldObject() != null) {
                playable.markInstaShieldForReregistration();
                playable.getInstaShieldObject().invalidateDplcCache();
            }
            // ROM Load_Level clears Dynamic_object_RAM. If the player was riding
            // an act-1 transition helper, the next ExecuteObjects pass clears the
            // stale on-object bit and produces the one-frame airborne handoff.
            if (request.forceAirOnStaleObjectSupportLoss() && objectManager != null) {
                objectManager.forceAirOnStaleObjectSupportLoss(playable);
            }
        }
        for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
            int newX = sidekick.getCentreX() + request.playerOffsetX();
            int newY = sidekick.getCentreY() + request.playerOffsetY();
            sidekick.setCentreXPreserveSubpixel((short) newX);
            sidekick.setCentreYPreserveSubpixel((short) newY);
            if (sidekick.getSpriteRenderer() != null) {
                sidekick.getSpriteRenderer().invalidateDplcCache();
            }
            if (request.forceAirOnStaleObjectSupportLoss() && objectManager != null) {
                objectManager.forceAirOnStaleObjectSupportLoss(sidekick);
            }
        }
        cam.setX((short) (cam.getX() + request.cameraOffsetX()));
        cam.setY((short) (cam.getY() + request.cameraOffsetY()));
    }

    /**
     * Rebuilds object and ring managers with the new act's spawn data.
     * <p>
     * ROM behavior: {@code Load_Level} swaps the object/ring position index
     * pointers, then clears {@code Dynamic_object_RAM} and
     * {@code Ring_status_table}. Because our managers hold immutable spawn
     * lists from construction, a simple {@code reset()} only clears runtime
     * state without swapping in the new act's spawn sources. We must
     * reconstruct both managers so they reference {@code level.getObjects()}
     * and {@code level.getRings()} from the newly loaded act.
     */
    void rebuildManagersForActTransition(
            Camera cam,
            List<ObjectInstance> persistentDynamicObjects,
            boolean retainedEndLevelOwner) {
        int cameraX = cam.getX();
        // V_int_run_count is global work RAM, outside Dynamic_object_RAM, and
        // Load_Level does not clear it. The ObjectManager owns our live copy of
        // that clock, so carry it across the manager rebuild even though the
        // per-act object execution counter intentionally restarts.
        int inheritedVblaCounter = objectManager != null ? objectManager.getVblaCounter() : 0;
        int inheritedVIntRunCounterPhaseOffset = objectManager != null
                ? objectManager.getVIntRunCounterPhaseOffset()
                : 0;

        // Rebuild ObjectManager with the new act's object spawns
        objectManager = new ObjectManager(level.getObjects(),
                gameModule.createObjectRegistry(),
                gameModule.getPlaneSwitcherObjectId(),
                gameModule.getPlaneSwitcherConfig(),
                touchResponseTable,
                graphicsManager,
                camera,
                buildObjectServices());
        // V_int_run_count is global work RAM and Load_Level does not consume a
        // Process_Sprites dispatch. Preserve Obj37's floor-check phase unchanged.
        objectManager.inheritRingFloorCheckCounterPhase(ringFloorCheckCounterPhase);
        GameRules gameRules = gameModule.getRules();
        if (gameRules != null
                && gameRules.collision() != null
                && gameRules.collision().collisionModel() == com.openggf.game.CollisionModel.UNIFIED) {
            objectManager.enableCounterBasedRespawn();
        } else {
            objectManager.enableExecThenLoadPlacement();
            objectManager.enforceSlotLimit();
        }
        if (gameRules != null
                && gameRules.objectInteraction() != null
                && gameRules.objectInteraction().permanentRespawnTableLatch()) {
            objectManager.enablePermanentDestroyLatch();
        }
        collisionSystem.setObjectManager(objectManager);
        objectManager.reset(cameraX);
        objectManager.initVblaCounter(inheritedVblaCounter);
        objectManager.initVIntRunCounterPhaseOffset(inheritedVIntRunCounterPhaseOffset);

        // Rebuild RingManager with the new act's ring spawns
        RingSpriteSheet ringSpriteSheet = level.getRingSpriteSheet();
        ringManager = new RingManager(level.getRings(), ringSpriteSheet, this, touchResponseTable, audioManager);
        ringManager.reset(cameraX);
        ringManager.ensurePatternsCached(graphicsManager, level.getPatternCount());

        // Re-register player dynamic objects (shield, invincibility) that were
        // orphaned when the old ObjectManager was replaced.
        // ROM: these live in Dynamic_object_RAM which persists across act transitions.
        reregisterPlayerDynamicObjects(cam.getFocusedSprite());
        for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
            reregisterPlayerDynamicObjects(sidekick);
        }
        if (persistentDynamicObjects != null) {
            for (ObjectInstance object : persistentDynamicObjects) {
                if (object != null && !object.isDestroyed()) {
                    objectManager.addDynamicObject(object);
                }
            }
        }
    }

    private ObjectServices buildObjectServices() {
        var gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null && gameplayMode.getLevelManager() == this && engineServices != null) {
            return new DefaultObjectServices(gameplayMode, engineServices);
        }
        throw new IllegalStateException("LevelManager.buildObjectServices() requires the active GameplayModeContext");
    }

    private void reregisterPlayerDynamicObjects(Sprite sprite) {
        if (!(sprite instanceof AbstractPlayableSprite playable)) {
            return;
        }
        // Re-inject spawner since ObjectManager was rebuilt
        playable.setPowerUpSpawner(new DefaultPowerUpSpawner(objectManager));
        PowerUpObject shield = playable.getShieldObject();
        if (shield != null && !shield.isDestroyed()) {
            playable.getPowerUpSpawner().registerObject(shield);
        }
        PowerUpObject invincibility = playable.getInvincibilityObject();
        if (invincibility != null && !invincibility.isDestroyed()) {
            playable.getPowerUpSpawner().registerObject(invincibility);
        }
    }

    void initLevelEventsForCurrentZoneAct() {
        LevelEventProvider levelEvents = activeGameModule().getLevelEventProvider();
        if (levelEvents != null) {
            levelEvents.initLevel(currentZone, currentAct);
        }
    }

    public void nextZone() throws IOException {
        writeCurrentZone(currentZone + 1);
        if (currentZone >= levels.size()) {
            writeCurrentZone(0);
        }
        writeCurrentAct(0);
        writeApparentAct(0);
        // Clear checkpoint when manually changing level
        checkpointCoordinator.clear();
        com.openggf.TraceSessionLauncher.runLevelAdvanceLoad(this::loadCurrentLevel);
    }

    public void loadZone(int zone) throws IOException {
        writeCurrentZone(zone);
        writeCurrentAct(0);
        writeApparentAct(0);
        // Clear checkpoint when manually changing level
        checkpointCoordinator.clear();
        loadCurrentLevel();
    }

    public RespawnState getCheckpointState() {
        return checkpointCoordinator.state();
    }

    public CheckpointState.RewindState captureCheckpointStateForRewind() {
        return checkpointCoordinator.captureRewindState();
    }

    public void restoreCheckpointStateForRewind(CheckpointState.RewindState checkpointRewindState) {
        if (checkpointRewindState == null) {
            return;
        }
        checkpointCoordinator.restoreRewindState(checkpointRewindState);
    }

    // ==================== Transition Coordinator Delegation ====================
    // Thin wrappers that delegate to LevelTransitionCoordinator.
    /** Returns the transition coordinator. */
    public LevelTransitionCoordinator getTransitions() { return transitions; }

    /** @see LevelTransitionCoordinator#advanceToSpecialStageEntryRoutine() */
    public void advanceToSpecialStageEntryRoutine() {
        transitions.advanceToSpecialStageEntryRoutine();
        GameServices.playbackDebug().onSpecialStageRequestRaised();
    }

    /** @see LevelTransitionCoordinator#requestSpecialStageEntry() */
    public void requestSpecialStageEntry() {
        transitions.requestSpecialStageEntry();
        GameServices.playbackDebug().onSpecialStageRequestRaised();
        endLevelDynamicArtComparisonSegmentAtRomModeChange();
    }

    /**
     * Ends the level's dynamic-art comparison window on the ROM iteration that
     * writes the special-stage game mode. {@code Obj79_Star} performs
     * {@code move.b #GameModeID_SpecialStage,(Game_Mode).w} from inside
     * {@code RunObjects} (docs/s2disasm/s2.asm:44877), and S3K's
     * {@code SSEntryFlash_GoSS} does the same from its object tick, so the rest
     * of that iteration -- {@code BuildSprites} and its DPLC queueing at
     * docs/s2disasm/s2.asm:5108-5110 -- already runs with the level's game mode
     * gone. The iteration itself still completes (the mode test that leaves
     * {@code Level_MainLoop} is at :5122-5125), which is why the engine keeps
     * running it; it simply is not a row of the level segment any more, exactly
     * as the run recorder finalizes the level segment on the first frame that
     * reads {@code $10} and writes no row for it. Production only: no expected
     * trace value crosses this seam.
     */
    private void endLevelDynamicArtComparisonSegmentAtRomModeChange() {
        GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null) {
            gameplayMode.endDynamicArtComparisonSegmentAtRomModeChange();
        }
    }

    /**
     * @see LevelTransitionCoordinator#consumeSpecialStageRequest()
     * <p>
     * A results card's armed {@code Got_NextLevel} performs its
     * {@code v_zone_act} write here, on the same frame as the mode change it
     * returns — not on the earlier frame that armed the routine.
     */
    public boolean consumeSpecialStageRequest() {
        boolean entering = transitions.consumeSpecialStageRequest();
        if (entering && transitions.consumeSpecialStageEntryLevelAdvance()) {
            advanceZoneActOnly();
        }
        return entering;
    }

    /** @see LevelTransitionCoordinator#consumeSpecialStageReturnLevelReloadRequest() */
    public boolean consumeSpecialStageReturnLevelReloadRequest() { return transitions.consumeSpecialStageReturnLevelReloadRequest(); }

    /** @see LevelTransitionCoordinator#requestBonusStageEntry(BonusStageType) */
    public void requestBonusStageEntry(BonusStageType type) { transitions.requestBonusStageEntry(type); }

    /** @see LevelTransitionCoordinator#consumeBonusStageRequest() */
    public BonusStageType consumeBonusStageRequest() { return transitions.consumeBonusStageRequest(); }

    /** @see LevelTransitionCoordinator#saveBigRingReturn(BigRingReturnState) */
    public void saveBigRingReturn(BigRingReturnState state) {
        if (state == null) {
            transitions.saveBigRingReturn(null, null);
            return;
        }

        AbstractPlayableSprite playable = mainPlayableSprite();
        ShieldType savedShield = captureSpecialStageReturnShield(playable);
        if (state.savedShieldType() == null) {
            state = state.withSavedShieldType(savedShield);
        }
        PersistentRespawnState respawnState = objectManager != null
                ? objectManager.capturePersistentRespawn()
                : null;
        transitions.saveBigRingReturn(state, respawnState, ringManager);
    }

    /**
     * S3K's SpawnLevelMainSprites_SpawnPowerup consumes only the elemental
     * shield bits from Saved2_status_secondary. The ordinary BASIC shield bit
     * is intentionally excluded by the ROM mask.
     */
    private static ShieldType captureSpecialStageReturnShield(AbstractPlayableSprite playable) {
        if (playable == null || !playable.hasShield() || playable.getShieldType() == null) {
            return null;
        }
        ShieldType shieldType = playable.getShieldType();
        return switch (shieldType) {
            case FIRE, LIGHTNING, BUBBLE -> shieldType;
            case BASIC -> null;
        };
    }

    /** @see LevelTransitionCoordinator#clearLastStarPostHit() */
    public void clearLastStarPostHit() { transitions.clearLastStarPostHit(); }

    /** @see LevelTransitionCoordinator#hasBigRingReturn() */
    public boolean hasBigRingReturn() { return transitions.hasBigRingReturn(); }

    /** @see LevelTransitionCoordinator#getBigRingReturn() */
    public BigRingReturnState getBigRingReturn() { return transitions.getBigRingReturn(); }

    /** @see LevelTransitionCoordinator#clearBigRingReturn() */
    public void clearBigRingReturn() { transitions.clearBigRingReturn(); }

    /** @see LevelTransitionCoordinator#setBonusStageReturnCheckpointIndex(int) */
    public void setBonusStageReturnCheckpointIndex(int idx) { transitions.setBonusStageReturnCheckpointIndex(idx); }

    /** @see LevelTransitionCoordinator#isBonusStageReturn() */
    public boolean isBonusStageReturn() { return transitions.isBonusStageReturn(); }

    /** @see LevelTransitionCoordinator#getBonusStageReturnCheckpointIndex() */
    public int getBonusStageReturnCheckpointIndex() { return transitions.getBonusStageReturnCheckpointIndex(); }

    /** @see LevelTransitionCoordinator#clearBonusStageReturn() */
    public void clearBonusStageReturn() { transitions.clearBonusStageReturn(); }

    /** @see LevelTransitionCoordinator#setResultsReturnCardOwnedByCaller(boolean) */
    public void setResultsReturnCardOwnedByCaller(boolean owned) { transitions.setResultsReturnCardOwnedByCaller(owned); }

    /** @see LevelTransitionCoordinator#requestTitleCard(int, int) */
    public void requestTitleCard(int zone, int act) { transitions.requestTitleCard(zone, act); }

    /** @see LevelTransitionCoordinator#requestInLevelTitleCard(int, int) */
    public void requestInLevelTitleCard(int zone, int act) { transitions.requestInLevelTitleCard(zone, act); }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay) {
        transitions.requestInLevelTitleCard(zone, act, resetLevelGamestateAtDisplay);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay,
                                        int resetAdditionalDispatches) {
        transitions.requestInLevelTitleCard(
                zone, act, resetLevelGamestateAtDisplay, resetAdditionalDispatches);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay,
                                        int resetAdditionalDispatches, boolean lockPlayerControl) {
        transitions.requestInLevelTitleCard(zone, act, resetLevelGamestateAtDisplay,
                resetAdditionalDispatches, lockPlayerControl);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay,
                                        int resetAdditionalDispatches, boolean lockPlayerControl,
                                        int exitAdditionalDispatches) {
        transitions.requestInLevelTitleCard(zone, act, resetLevelGamestateAtDisplay,
                resetAdditionalDispatches, lockPlayerControl, exitAdditionalDispatches);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay,
                                        int resetAdditionalDispatches,
                                        int resetPhaseOneDispatchOverlap,
                                        boolean lockPlayerControl,
                                        int exitAdditionalDispatches) {
        transitions.requestInLevelTitleCard(zone, act, resetLevelGamestateAtDisplay,
                resetAdditionalDispatches, resetPhaseOneDispatchOverlap,
                lockPlayerControl, exitAdditionalDispatches);
    }

    public void requestInLevelTitleCard(int zone, int act, boolean resetLevelGamestateAtDisplay,
                                        int resetAdditionalDispatches,
                                        int resetPhaseOneDispatchOverlap,
                                        boolean lockPlayerControl,
                                        int exitAdditionalDispatches,
                                        int exitPhaseOneDispatchOverlap) {
        transitions.requestInLevelTitleCard(zone, act, resetLevelGamestateAtDisplay,
                resetAdditionalDispatches, resetPhaseOneDispatchOverlap,
                lockPlayerControl, exitAdditionalDispatches,
                exitPhaseOneDispatchOverlap);
    }

    /** @see LevelTransitionCoordinator#isTitleCardRequested() */
    public boolean isTitleCardRequested() { return transitions.isTitleCardRequested(); }

    /**
     * @return true if vertical wrapping is active (ROM: LZ3/SBZ2 loop sections)
     */
    public boolean isVerticalWrapEnabled() {
        return verticalWrapEnabled;
    }

    /** @see LevelTransitionCoordinator#consumeTitleCardRequest() */
    public boolean consumeTitleCardRequest() { return transitions.consumeTitleCardRequest(); }

    /** @see LevelTransitionCoordinator#consumeInLevelTitleCardRequest() */
    public boolean consumeInLevelTitleCardRequest() { return transitions.consumeInLevelTitleCardRequest(); }

    public boolean consumeInLevelTitleCardLevelGamestateResetRequest() {
        return transitions.consumeInLevelTitleCardLevelGamestateResetRequest();
    }

    public boolean hasPendingInLevelTitleCardHeldCounterDispatch() {
        return transitions.hasPendingInLevelTitleCardHeldCounterDispatch();
    }

    public int consumeInLevelTitleCardResetAdditionalDispatches() {
        return transitions.consumeInLevelTitleCardResetAdditionalDispatches();
    }

    public int consumeInLevelTitleCardResetPhaseOneDispatchOverlap() {
        return transitions.consumeInLevelTitleCardResetPhaseOneDispatchOverlap();
    }

    public boolean consumeInLevelTitleCardPlayerControlLockRequest() {
        return transitions.consumeInLevelTitleCardPlayerControlLockRequest();
    }

    public int consumeInLevelTitleCardExitAdditionalDispatches() {
        return transitions.consumeInLevelTitleCardExitAdditionalDispatches();
    }

    public int consumeInLevelTitleCardExitPhaseOneDispatchOverlap() {
        return transitions.consumeInLevelTitleCardExitPhaseOneDispatchOverlap();
    }

    /** @see LevelTransitionCoordinator#getTitleCardZone() */
    public int getTitleCardZone() { return transitions.getTitleCardZone(); }

    /** @see LevelTransitionCoordinator#getTitleCardAct() */
    public int getTitleCardAct() { return transitions.getTitleCardAct(); }

    /** @see LevelTransitionCoordinator#getInLevelTitleCardZone() */
    public int getInLevelTitleCardZone() { return transitions.getInLevelTitleCardZone(); }

    /** @see LevelTransitionCoordinator#getInLevelTitleCardAct() */
    public int getInLevelTitleCardAct() { return transitions.getInLevelTitleCardAct(); }

    /**
     * Resets gameplay-owned mutable state without clearing the durable
     * {@link com.openggf.game.session.WorldSession} level and zone metadata.
     */
    public void resetGameplayState() {
        discardPreparedLevelLoad();
        discardInitialProcessSpritesLifecycle();
        com.openggf.game.session.GameplayModeContext gameplayMode =
                com.openggf.game.session.SessionManager.getCurrentGameplayMode();
        if (gameplayMode != null && gameplayMode.getRewindRegistry() != null) {
            gameplayMode.getRewindRegistry().deregister("level");
            gameplayMode.getRewindRegistry().deregister("object-manager");
            gameplayMode.getRewindRegistry().deregister("level-event");
        }
        level = null;
        game = null;
        gameModule = null;
        collisionLayoutYMask = 0;
        objectManager = null;
        persistentRespawnStateForNextObjectReset = null;
        persistentRespawnStateForCameraSnap = null;
        ringManager = null;
        zoneFeatureProvider = null;
        levelRenderer.resetState();
        objectRenderManager = null;
        hudRenderManager = null;
        animatedPatternManager = null;
        animatedPaletteManager = null;
        checkpointCoordinator.resetState();
        levelGamestate = null;
        if (tilemapManager != null) {
            tilemapManager.resetState();
        }
        tilemapManager = null;
        currentZone = 0;
        currentAct = 0;
        apparentAct = 0;
        frameCounter = 0;
        sidekickRomVisibleReloadFrameCounterBridgeActive = false;
        sidekickRomVisibleReloadFrameCounterBridgePrimed = false;
        actTransitionExecutor.resetFrameMarkers();
        transitions.resetState();
        verticalWrapEnabled = false;
        touchResponseTable = null;
        useShaderBackground = true;
        cacheLevelDimensions();
        levels.clear();
    }

    /**
     * Resets mutable state without destroying the singleton instance.
     * Replaces the reflection-based tearDown hacks in test classes.
     */
    public void resetState() {
        resetGameplayState();
        writeCurrentLevel(null);
        writeCurrentZone(0);
        writeCurrentAct(0);
        writeApparentAct(0);
    }

    /**
     * Reset the frame counter to 0.
     * Used for deterministic visual regression testing to ensure animations
     * are in a consistent state between reference generation and test runs.
     */
    public void resetFrameCounter() {
        this.frameCounter = 0;
        sidekickRomVisibleReloadFrameCounterBridgeActive = false;
        sidekickRomVisibleReloadFrameCounterBridgePrimed = false;
        actTransitionExecutor.resetFrameMarkers();
    }

    public void setClearColor() {
        if (level == null) {
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            return;
        }
        Palette.Color backdrop = resolveLevelBackdropColor();
        glClearColor(backdrop.rFloat(), backdrop.gFloat(), backdrop.bFloat(), 1.0f);
    }

    /**
     * The backdrop colour as CRAM presents it: line 2 colour 0 ({@code $8720}),
     * which a palette fade covering that line (S1 PalFadeIn_Alt on lines 1-3)
     * fades with the planes. Both the clear colour and the parallax shader's
     * transparent-pixel fill read this, so the fade is applied here once.
     */
    Palette.Color resolveLevelBackdropColor() {
        if (level == null) {
            return BLACK_BACKDROP;
        }
        if (isForceBlackBackdrop()) {
            return BLACK_BACKDROP;
        }
        Palette.Color backdrop = level.getBackdropColor();
        if (graphicsManager == null) {
            return backdrop;
        }
        PaletteFadePresentation fade = graphicsManager.getPaletteFadePresentation();
        if (!fade.affects(level.getBackdropPaletteLine())) {
            return backdrop;
        }
        int rgb = fade.fadeRgb(
                Byte.toUnsignedInt(backdrop.r),
                Byte.toUnsignedInt(backdrop.g),
                Byte.toUnsignedInt(backdrop.b));
        fadedBackdrop.r = (byte) (rgb >>> 16);
        fadedBackdrop.g = (byte) (rgb >>> 8);
        fadedBackdrop.b = (byte) rgb;
        return fadedBackdrop;
    }

    private boolean isForceBlackBackdrop() {
        ZoneFeatureProvider zfp = zoneFeatureProvider;
        return zfp != null && zfp.isForceBlackBackdrop();
    }

    /**
     * Reloads the current level's palettes into the graphics manager.
     * Call this after returning from special stage to restore level colors.
     */
    public void reloadLevelPalettes() {
        if (level == null) {
            LOGGER.warning("Cannot reload palettes: no level loaded");
            return;
        }

        int paletteCount = level.getPaletteCount();
        for (int i = 0; i < paletteCount; i++) {
            Palette palette = level.getPalette(i);
            if (palette != null) {
                graphicsManager.cachePaletteTexture(palette, i);
            }
        }
        LOGGER.fine("Reloaded " + paletteCount + " level palettes");
    }

    // ==================== Transition Request Delegation ====================
    // These delegate to LevelTransitionCoordinator so external callers keep working.

    /** @see LevelTransitionCoordinator#hasPendingLevelExit() */
    public boolean hasPendingLevelExit() { return transitions.hasPendingLevelExit(); }

    /** @see LevelTransitionCoordinator#requestRespawn() */
    public void requestRespawn() { transitions.requestRespawn(); }

    /** @see LevelTransitionCoordinator#consumeRespawnRequest() */
    public boolean consumeRespawnRequest() { return transitions.consumeRespawnRequest(); }

    public boolean isRespawnRequestedForRewind() { return transitions.isRespawnRequested(); }

    public void restoreRespawnRequestedForRewind(boolean respawnRequested) {
        transitions.restoreRespawnRequested(respawnRequested);
    }

    /** @see LevelTransitionCoordinator#requestNextAct() */
    public void requestNextAct() { transitions.requestNextAct(); }

    /** @see LevelTransitionCoordinator#consumeNextActRequest() */
    public boolean consumeNextActRequest() { return transitions.consumeNextActRequest(); }

    /** @see LevelTransitionCoordinator#requestNextZone() */
    public void requestNextZone() { transitions.requestNextZone(); }

    /** @see LevelTransitionCoordinator#consumeNextZoneRequest() */
    public boolean consumeNextZoneRequest() { return transitions.consumeNextZoneRequest(); }

    /** @see LevelTransitionCoordinator#requestZoneAndAct(int, int) */
    public void requestZoneAndAct(int zone, int act) { transitions.requestZoneAndAct(zone, act); }

    /** @see LevelTransitionCoordinator#requestZoneAndAct(int, int, boolean) */
    public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow) { transitions.requestZoneAndAct(zone, act, deactivateLevelNow); }

    /** @see LevelTransitionCoordinator#requestZoneAndAct(int, int, boolean, int) */
    public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow, int musicId) {
        transitions.requestZoneAndAct(zone, act, deactivateLevelNow, musicId);
    }

    /** @see LevelTransitionCoordinator#getRequestedMusicId() */
    public int getRequestedMusicId() { return transitions.getRequestedMusicId(); }

    /** @see LevelTransitionCoordinator#requestSeamlessTransition(SeamlessLevelTransitionRequest) */
    public void requestSeamlessTransition(SeamlessLevelTransitionRequest request) { transitions.requestSeamlessTransition(request); }

    /** @see LevelTransitionCoordinator#consumeSeamlessTransitionRequest() */
    public SeamlessLevelTransitionRequest consumeSeamlessTransitionRequest() { return transitions.consumeSeamlessTransitionRequest(); }

    /**
     * Applies a seamless transition immediately.
     * <p>
     * Routes through {@link #executeActTransition} for RELOAD types,
     * which bypasses the profile system and matches ROM behavior.
     */
    public void applySeamlessTransition(SeamlessLevelTransitionRequest request) {
        seamlessTransitionExecutor.execute(request);
    }

    void markSidekickRomVisibleReloadFrameCounterBridge() {
        sidekickRomVisibleReloadFrameCounterBridgeActive = true;
        sidekickRomVisibleReloadFrameCounterBridgePrimed = true;
    }

    public boolean isSidekickRomVisibleReloadFrameCounterBridgeActive() {
        return sidekickRomVisibleReloadFrameCounterBridgeActive;
    }

    public boolean isSidekickRomVisibleReloadResumeFrameCounterBridgeActive() {
        return currentAct != apparentAct || isPostReloadFrameCounterBridgeStillVisible();
    }

    public void clearSidekickRomVisibleReloadFrameCounterBridge() {
        sidekickRomVisibleReloadFrameCounterBridgeActive = false;
    }

    private boolean isPostReloadFrameCounterBridgeStillVisible() {
        if (!sidekickRomVisibleReloadFrameCounterBridgePrimed) {
            return false;
        }
        SpriteManager spriteManager = GameServices.spritesOrNull();
        return spriteManager != null && spriteManager.getFrameCounter() == frameCounter + 1;
    }

    void applySeamlessMutation(String mutationKey) {
        gameModule.applySeamlessMutation(this, mutationKey);
    }

    /** @see LevelTransitionCoordinator#consumeZoneActRequest() */
    public boolean consumeZoneActRequest() { return transitions.consumeZoneActRequest(); }

    /** @see LevelTransitionCoordinator#getRequestedZone() */
    public int getRequestedZone() { return transitions.getRequestedZone(); }

    /** @see LevelTransitionCoordinator#getRequestedAct() */
    public int getRequestedAct() { return transitions.getRequestedAct(); }

    /** @see LevelTransitionCoordinator#isLevelInactiveForTransition() */
    public boolean isLevelInactiveForTransition() { return transitions.isLevelInactiveForTransition(); }

    /** @see LevelTransitionCoordinator#setLevelInactiveForTransition(boolean) */
    public void setLevelInactiveForTransition(boolean inactive) {
        transitions.setLevelInactiveForTransition(inactive);
    }

    /** @see LevelTransitionCoordinator#requestCreditsTransition() */
    public void requestCreditsTransition() { transitions.requestCreditsTransition(); }

    /** @see LevelTransitionCoordinator#consumeCreditsRequest() */
    public boolean consumeCreditsRequest() { return transitions.consumeCreditsRequest(); }

    public void requestGameOverExit(GameOverExit exit) { transitions.requestGameOverExit(exit); }

    public GameOverExit consumeGameOverExitRequest() { return transitions.consumeGameOverExitRequest(); }

    public GameOverExit getGameOverExitRequested() { return transitions.getGameOverExitRequested(); }

    /** @see LevelTransitionCoordinator#setForceHudSuppressed(boolean) */
    public void setForceHudSuppressed(boolean suppressed) { transitions.setForceHudSuppressed(suppressed); }

    public void setBonusStageHudLayout(boolean enabled) {
        if (hudRenderManager != null) {
            hudRenderManager.setBonusStageHudLayout(enabled);
        }
    }

    /** @see LevelTransitionCoordinator#setSuppressNextMusicChange(boolean) */
    public void setSuppressNextMusicChange(boolean suppress) { transitions.setSuppressNextMusicChange(suppress); }

    /** @see LevelTransitionCoordinator#isSuppressNextMusicChange() */
    public boolean isSuppressNextMusicChange() { return transitions.isSuppressNextMusicChange(); }

    /**
     * Finds the offset from a reference position to the first pattern within a tile index range.
     * Scans the level chunks around the reference position looking for patterns that use
     * VRAM tile indices within the specified range.
     * <p>
     * This is used by CNZ slot machines to find where the slot display tiles are positioned
     * relative to the cage object, as this varies between CNZ1 (below) and CNZ2 (above).
     *
     * @param refX       Reference X position (world coordinates, typically cage center)
     * @param refY       Reference Y position (world coordinates, typically cage center)
     * @param minTileIdx Minimum VRAM tile index to search for (inclusive)
     * @param maxTileIdx Maximum VRAM tile index to search for (inclusive)
     * @param searchRadius Radius in pixels to search around the reference position
     * @return int[2] with {offsetX, offsetY} from ref to first matching pattern center,
     *         or null if no matching pattern found
     */
    public int[] findPatternOffset(int refX, int refY, int minTileIdx, int maxTileIdx, int searchRadius) {
        return LevelPatternLocator.findPatternOffset(
                level, blockPixelSize, refX, refY, minTileIdx, maxTileIdx, searchRadius);
    }

    /**
     * Returns a {@link com.openggf.game.rewind.RewindSnapshottable} adapter for level state.
     * Captures block/chunk array references and map data; restores via copy-on-write.
     */
    public RewindSnapshottable<LevelSnapshot> levelRewindSnapshottable() {
        return LevelRewindSnapshotAdapter.create(this);
    }

    /** Returns the rewind adapter for the history-dependent persistent Plane B nametable. */
    public RewindSnapshottable<LevelTilemapSnapshot> levelTilemapRewindSnapshottable() {
        return new LevelTilemapRewindAdapter(tilemapManager);
    }
}
