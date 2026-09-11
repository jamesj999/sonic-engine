package com.openggf.level.objects;

import com.openggf.audio.AudioManager;
import com.openggf.audio.GameSound;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.BonusStageProvider;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.game.BonusStageType;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModule;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.LevelState;
import com.openggf.game.PlayableEntity;
import com.openggf.game.RespawnState;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SessionSaveRequests;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.WorldSession;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.resources.NativeFadeLifecycle;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.BigRingReturnState;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.level.rings.RingManager;
import com.openggf.physics.CollisionSystem;
import com.openggf.sprites.managers.SpriteManager;
import java.io.IOException;
import java.util.Objects;
import java.util.List;

/**
 * Production implementation of {@link ObjectServices} backed by gameplay session managers.
 * A single instance is held by {@link ObjectManager} and shared across all objects.
 */
public class DefaultObjectServices implements ObjectServices {

    private static final java.util.logging.Logger LOG =
        java.util.logging.Logger.getLogger(DefaultObjectServices.class.getName());

    private final LevelManager levelManager;
    private final Camera camera;
    private final GameStateManager gameState;
    private final SpriteManager spriteManager;
    private final FadeManager fadeManager;
    private final WaterSystem waterSystem;
    private final ParallaxManager parallaxManager;
    private final CollisionSystem collisionSystem;
    private final WorldSession worldSession;
    private final GameRng rng;
    private final ZoneRuntimeRegistry zoneRuntimeRegistry;
    private final PaletteOwnershipRegistry paletteOwnershipRegistry;
    private final ZoneLayoutMutationPipeline zoneLayoutMutationPipeline;
    private final SolidExecutionRegistry solidExecutionRegistry;
    private final EngineContext engineServices;
    private final BonusStageProvider bonusStageProvider;
    // Nullable: only set by the primary session-backed constructor. The bonus
    // stage provider is registered on GameplayModeContext (GameLoop
    // .doEnterBonusStage / TraceReplaySessionBootstrap.applyBonusStageEntry)
    // AFTER the level's ObjectManager/DefaultObjectServices already exists in
    // trace-replay bootstraps that reuse a pre-built fixture level (production
    // always creates a fresh ObjectManager via loadZoneAndAct AFTER
    // registering the provider, so this never goes stale there). Without a
    // live re-read, the captured `bonusStageProvider` above stays pinned to
    // the pre-registration NoOpBonusStageProvider default forever, so an
    // object's requestBonusStageExit() (e.g. Obj_PachinkoEnergyTrap's
    // escape-through-top trigger) silently no-ops. Resolve through this
    // context when present so bonus-stage forwarding always targets the
    // CURRENT registered provider.
    private final GameplayModeContext gameplayMode;

    /**
     * Primary constructor backed by the session-owned gameplay context.
     */
    public DefaultObjectServices(GameplayModeContext gameplayMode,
                                 EngineContext engineServices) {
        this(Objects.requireNonNull(gameplayMode, "gameplayMode").getLevelManager(),
                gameplayMode.getCamera(),
                gameplayMode.getGameStateManager(),
                gameplayMode.getSpriteManager(),
                gameplayMode.getFadeManager(),
                gameplayMode.getWaterSystem(),
                gameplayMode.getParallaxManager(),
                gameplayMode.getCollisionSystem(),
                gameplayMode.getWorldSession(),
                gameplayMode.getRng(),
                gameplayMode.getZoneRuntimeRegistry(),
                gameplayMode.getPaletteOwnershipRegistry(),
                gameplayMode.getZoneLayoutMutationPipeline(),
                gameplayMode.getSolidExecutionRegistry(),
                engineServices,
                gameplayMode.getActiveBonusStageProvider(),
                gameplayMode);
    }

    private DefaultObjectServices(LevelManager levelManager,
                                 Camera camera,
                                 GameStateManager gameState,
                                 SpriteManager spriteManager,
                                 FadeManager fadeManager,
                                 WaterSystem waterSystem,
                                 ParallaxManager parallaxManager,
                                 CollisionSystem collisionSystem,
                                 WorldSession worldSession,
                                 GameRng rng,
                                 ZoneRuntimeRegistry zoneRuntimeRegistry,
                                 PaletteOwnershipRegistry paletteOwnershipRegistry,
                                 ZoneLayoutMutationPipeline zoneLayoutMutationPipeline,
                                 SolidExecutionRegistry solidExecutionRegistry,
                                 EngineContext engineServices,
                                 BonusStageProvider bonusStageProvider) {
        this(levelManager, camera, gameState, spriteManager, fadeManager, waterSystem,
                parallaxManager, collisionSystem, worldSession, rng, zoneRuntimeRegistry,
                paletteOwnershipRegistry, zoneLayoutMutationPipeline, solidExecutionRegistry,
                engineServices, bonusStageProvider, null);
    }

    private DefaultObjectServices(LevelManager levelManager,
                                 Camera camera,
                                 GameStateManager gameState,
                                 SpriteManager spriteManager,
                                 FadeManager fadeManager,
                                 WaterSystem waterSystem,
                                 ParallaxManager parallaxManager,
                                 CollisionSystem collisionSystem,
                                 WorldSession worldSession,
                                 GameRng rng,
                                 ZoneRuntimeRegistry zoneRuntimeRegistry,
                                 PaletteOwnershipRegistry paletteOwnershipRegistry,
                                 ZoneLayoutMutationPipeline zoneLayoutMutationPipeline,
                                 SolidExecutionRegistry solidExecutionRegistry,
                                 EngineContext engineServices,
                                 BonusStageProvider bonusStageProvider,
                                 GameplayModeContext gameplayMode) {
        this.levelManager = Objects.requireNonNull(levelManager, "levelManager");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.gameState = Objects.requireNonNull(gameState, "gameState");
        this.spriteManager = Objects.requireNonNull(spriteManager, "spriteManager");
        this.fadeManager = Objects.requireNonNull(fadeManager, "fadeManager");
        this.waterSystem = Objects.requireNonNull(waterSystem, "waterSystem");
        this.parallaxManager = Objects.requireNonNull(parallaxManager, "parallaxManager");
        this.collisionSystem = Objects.requireNonNull(collisionSystem, "collisionSystem");
        this.worldSession = worldSession;
        this.rng = Objects.requireNonNull(rng, "rng");
        this.zoneRuntimeRegistry = Objects.requireNonNull(zoneRuntimeRegistry, "zoneRuntimeRegistry");
        this.paletteOwnershipRegistry = paletteOwnershipRegistry;
        this.zoneLayoutMutationPipeline = Objects.requireNonNull(zoneLayoutMutationPipeline, "zoneLayoutMutationPipeline");
        this.solidExecutionRegistry = Objects.requireNonNull(solidExecutionRegistry, "solidExecutionRegistry");
        this.engineServices = Objects.requireNonNull(engineServices, "engineServices");
        this.bonusStageProvider = Objects.requireNonNull(bonusStageProvider, "bonusStageProvider");
        this.gameplayMode = gameplayMode;
    }

    private LevelManager lm() {
        return levelManager;
    }

    /**
     * Resolves the CURRENT bonus stage provider registered on the owning
     * GameplayModeContext when one is available, else falls back to the
     * provider captured at construction time (legacy manual-wiring
     * constructor callers with no session context).
     */
    private BonusStageProvider currentBonusStageProvider() {
        return gameplayMode != null ? gameplayMode.getActiveBonusStageProvider() : bonusStageProvider;
    }

    // ── Level state ─────────────────────────────────────────────────────

    @Override
    public ObjectManager objectManager() {
        return lm().getObjectManager();
    }

    @Override
    public ObjectRenderManager renderManager() {
        return lm().getObjectRenderManager();
    }

    @Override
    public LevelState levelGamestate() {
        return lm().getLevelGamestate();
    }

    @Override
    public RespawnState checkpointState() {
        return lm().getCheckpointState();
    }

    @Override
    public LevelManager levelManager() {
        return lm();
    }

    @Override
    public Level currentLevel() {
        return lm().getCurrentLevel();
    }

    @Override
    public int romZoneId() {
        return lm().getRomZoneId();
    }

    @Override
    public int currentAct() {
        return lm().getCurrentAct();
    }

    @Override
    public int featureZoneId() {
        return lm().getFeatureZoneId();
    }

    @Override
    public int featureActId() {
        return lm().getFeatureActId();
    }

    @Override
    public ZoneFeatureProvider zoneFeatureProvider() {
        return lm().getZoneFeatureProvider();
    }

    @Override
    public RingManager ringManager() {
        return lm().getRingManager();
    }

    @Override
    public boolean areAllRingsCollected() {
        return lm().areAllRingsCollected();
    }

    // ── Direct from runtime ─────────────────────────────────────────────

    @Override
    public Camera camera() {
        return camera;
    }

    @Override
    public GameStateManager gameState() {
        return gameState;
    }

    @Override
    public WorldSession worldSession() {
        return worldSession;
    }

    @Override
    public GameModule gameModule() {
        if (worldSession != null) {
            return worldSession.getGameModule();
        }
        return levelManager.getGameModule();
    }

    @Override
    public HardwareTimingService hardwareTiming() {
        if (gameplayMode == null) {
            throw new IllegalStateException(
                    "hardware timing requires session-backed object services");
        }
        return gameplayMode.hardwareTiming();
    }

    @Override
    public RuntimeArtCoordinator runtimeArtCoordinator() {
        if (gameplayMode == null) {
            throw new IllegalStateException(
                    "runtime-art coordination requires session-backed object services");
        }
        return gameplayMode.runtimeArtCoordinator();
    }

    @Override
    public NativeFadeLifecycle nativeFadeLifecycle() {
        if (gameplayMode == null) {
            throw new IllegalStateException(
                    "native fade lifecycle requires session-backed object services");
        }
        return gameplayMode.plcFrameLifecycle();
    }

    @Override
    public SpriteManager spriteManager() {
        return spriteManager;
    }

    @Override
    public CollisionSystem collisionSystem() {
        return collisionSystem;
    }

    @Override
    public FadeManager fadeManager() {
        return fadeManager;
    }

    @Override
    public WaterSystem waterSystem() {
        return waterSystem;
    }

    @Override
    public ParallaxManager parallaxManager() {
        return parallaxManager;
    }

    @Override
    public GameRng rng() {
        return rng;
    }

    @Override
    public ZoneRuntimeRegistry zoneRuntimeRegistry() {
        return zoneRuntimeRegistry;
    }

    @Override
    public ZoneRuntimeState zoneRuntimeState() {
        return zoneRuntimeRegistry.current();
    }

    @Override
    public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
        return paletteOwnershipRegistry;
    }

    @Override
    public ZoneLayoutMutationPipeline zoneLayoutMutationPipeline() {
        return zoneLayoutMutationPipeline;
    }

    @Override
    public SolidExecutionRegistry solidExecutionRegistry() {
        return solidExecutionRegistry;
    }

    // ── Engine globals (not runtime-owned) ──────────────────────────────

    @Override
    public GraphicsManager graphicsManager() {
        return engineServices.graphics();
    }

    @Override
    public AudioManager audioManager() {
        return engineServices.audio();
    }

    @Override
    public EngineContext engineServices() {
        return engineServices;
    }

    @Override
    public SonicConfigurationService configuration() {
        return engineServices.configuration();
    }

    @Override
    public DebugOverlayManager debugOverlay() {
        return engineServices.debugOverlay();
    }

    @Override
    public RomManager romManager() {
        return engineServices.roms();
    }

    @Override
    public CrossGameFeatureProvider crossGameFeatures() {
        return engineServices.crossGameFeatures();
    }

    // ── Audio convenience ───────────────────────────────────────────────

    @Override
    public void playSfx(int soundId) {
        audioManager().playSfx(soundId);
    }

    @Override
    public void playSfx(GameSound sound) {
        audioManager().playSfx(sound);
    }

    @Override
    public void playMusic(int musicId) {
        audioManager().playMusic(musicId);
    }

    @Override
    public void fadeOutMusic() {
        audioManager().fadeOutMusic();
    }

    // ── ROM (engine global) ─────────────────────────────────────────────

    @Override
    public Rom rom() throws IOException {
        return romManager().getRom();
    }

    @Override
    public RomByteReader romReader() throws IOException {
        return RomByteReader.fromRom(rom());
    }

    // ── Sidekicks ───────────────────────────────────────────────────────

    @Override
    public List<PlayableEntity> sidekicks() {
        return List.copyOf(spriteManager.getSidekicks());
    }

    // ── Lost rings ──────────────────────────────────────────────────────

    @Override
    public void spawnLostRings(PlayableEntity player, int frameCounter) {
        if (player instanceof com.openggf.sprites.playable.AbstractPlayableSprite aps) {
            lm().spawnLostRings(aps, frameCounter);
        } else {
            LOG.warning("spawnLostRings: player is not AbstractPlayableSprite, rings not spawned");
        }
    }

    @Override
    public void spawnLostRingsAfterCurrentFrame(PlayableEntity player, int frameCounter) {
        if (player instanceof com.openggf.sprites.playable.AbstractPlayableSprite aps) {
            lm().spawnLostRingsAfterCurrentFrame(aps, frameCounter);
        } else {
            LOG.warning("spawnLostRingsAfterCurrentFrame: player is not AbstractPlayableSprite, rings not spawned");
        }
    }

    @Override
    public void spawnLostRingsWithDeferredOwner(PlayableEntity player, int frameCounter) {
        if (player instanceof com.openggf.sprites.playable.AbstractPlayableSprite aps) {
            lm().spawnLostRingsWithDeferredOwner(aps, frameCounter);
        } else {
            LOG.warning("spawnLostRingsWithDeferredOwner: player is not AbstractPlayableSprite, rings not spawned");
        }
    }

    // ── Level actions ───────────────────────────────────────────────────

    @Override
    public void advanceToNextLevel() {
        try {
            lm().advanceToNextLevel();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to advance to next level", e);
        }
    }

    @Override
    public void requestCreditsTransition() {
        lm().requestCreditsTransition();
    }

    @Override
    public void requestSpecialStageEntry() {
        lm().requestSpecialStageEntry();
    }

    @Override
    public void invalidateForegroundTilemap() {
        lm().invalidateForegroundTilemap();
    }

    @Override
    public void updatePalette(int paletteIndex, byte[] paletteData) {
        lm().updatePalette(paletteIndex, paletteData);
    }

    // ── Level transition actions ───────────────────────────────────────

    @Override
    public void advanceZoneActOnly() {
        lm().advanceZoneActOnly();
    }

    @Override
    public void setApparentAct(int act) {
        lm().setApparentAct(act);
    }

    @Override
    public void advanceToSpecialStageEntryRoutine() {
        lm().advanceToSpecialStageEntryRoutine();
    }

    @Override
    public void requestBonusStageEntry(BonusStageType type) {
        lm().requestBonusStageEntry(type);
    }

    @Override
    public void requestBonusStageExit() {
        try {
            currentBonusStageProvider().requestExit();
        } catch (Exception e) {
            LOG.warning("requestBonusStageExit failed: " + e.getMessage());
        }
    }

    @Override
    public BonusStageProvider bonusStageProviderOrNull() {
        return currentBonusStageProvider();
    }

    @Override
    public void addBonusStageRings(int count) {
        try {
            currentBonusStageProvider().addRings(count);
        } catch (Exception e) {
            LOG.warning("addBonusStageRings failed: " + e.getMessage());
        }
    }

    @Override
    public void setBonusStageShield(com.openggf.game.ShieldType type) {
        try {
            currentBonusStageProvider().setAwardedShield(type);
        } catch (Exception e) {
            LOG.warning("setBonusStageShield failed: " + e.getMessage());
        }
    }

    @Override
    public void requestZoneAndAct(int zone, int act) {
        lm().requestZoneAndAct(zone, act);
    }

    @Override
    public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow) {
        lm().requestZoneAndAct(zone, act, deactivateLevelNow);
    }

    @Override
    public void requestSeamlessTransition(com.openggf.level.SeamlessLevelTransitionRequest request) {
        lm().requestSeamlessTransition(request);
    }

    // ── Level queries ──────────────────────────────────────────────────

    @Override
    public int getCurrentLevelMusicId() {
        return lm().getCurrentLevelMusicId();
    }

    @Override
    public int[] findPatternOffset(int refX, int refY, int minTileIdx, int maxTileIdx, int searchRadius) {
        return lm().findPatternOffset(refX, refY, minTileIdx, maxTileIdx, searchRadius);
    }

    @Override
    public void saveBigRingReturn(BigRingReturnState state) {
        lm().saveBigRingReturn(state);
    }

    @Override
    public void clearLastStarPostHit() {
        lm().clearLastStarPostHit();
    }

    @Override
    public void requestSessionSave(SaveReason reason) {
        SessionSaveRequests.requestCurrentSessionSave(reason);
    }

    // ── Game-specific providers ─────────────────────────────────────────

    @Override
    public LevelEventProvider levelEventProvider() {
        GameModule gm = gameModule();
        return gm != null ? gm.getLevelEventProvider() : null;
    }

    @Override
    public TitleCardProvider titleCardProvider() {
        GameModule gm = gameModule();
        return gm != null ? gm.getTitleCardProvider() : null;
    }

    @Override
    public <T> T gameService(Class<T> type) {
        GameModule gm = gameModule();
        return gm != null ? gm.getGameService(type) : null;
    }
}
