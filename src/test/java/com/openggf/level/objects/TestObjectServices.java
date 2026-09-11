package com.openggf.level.objects;

import com.openggf.audio.AudioManager;
import com.openggf.audio.GameSound;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.GameModule;
import com.openggf.game.LevelState;
import com.openggf.game.PlayableEntity;
import com.openggf.game.RespawnState;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.save.SaveReason;
import com.openggf.game.session.WorldSession;
import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.level.rings.RingManager;
import com.openggf.physics.CollisionSystem;
import com.openggf.sprites.managers.SpriteManager;

import java.util.List;

/**
 * Lightweight test double for {@link ObjectServices}.
 * Defaults to inert/null behavior and can be selectively wired to real or mocked managers.
 */
public class TestObjectServices implements ObjectServices {

    private LevelManager levelManager;
    private Camera camera;
    private GameStateManager gameState;
    private SpriteManager spriteManager;
    private FadeManager fadeManager;
    private WaterSystem waterSystem;
    private ParallaxManager parallaxManager;
    private CollisionSystem collisionSystem;
    private GraphicsManager graphicsManager;
    private AudioManager audioManager;
    private GameRng rng = new GameRng(GameRng.Flavour.S3K);
    private ZoneRuntimeRegistry zoneRuntimeRegistry = new ZoneRuntimeRegistry();
    private PaletteOwnershipRegistry paletteOwnershipRegistry;
    private ZoneLayoutMutationPipeline zoneLayoutMutationPipeline = new ZoneLayoutMutationPipeline();
    private SolidExecutionRegistry solidExecutionRegistry = SolidExecutionRegistry.inert();
    private Rom rom;
    private RomByteReader romReader;
    private List<PlayableEntity> sidekicks = List.of();
    private WorldSession worldSession;
    private GameModule gameModule;
    private EngineContext engineServices;
    private SonicConfigurationService configuration;
    private DebugOverlayManager debugOverlay;
    private RomManager romManager;
    private CrossGameFeatureProvider crossGameFeatures;
    private ObjectManager isolatedObjectManager;

    /**
     * Installs a real empty object manager for direct object tests that exercise
     * solid-contact ownership without loading a level. The default remains null
     * so tests that intentionally model an absent level manager stay explicit.
     */
    public TestObjectServices withIsolatedObjectManager() {
        if (isolatedObjectManager == null) {
            isolatedObjectManager = new ObjectManager(
                    List.of(), null, 0, null, null,
                    graphicsManager, camera, this);
        }
        return this;
    }

    public TestObjectServices withLevelManager(LevelManager levelManager) {
        this.levelManager = levelManager;
        return this;
    }

    public TestObjectServices withCamera(Camera camera) {
        this.camera = camera;
        return this;
    }

    public TestObjectServices withGameState(GameStateManager gameState) {
        this.gameState = gameState;
        return this;
    }

    public TestObjectServices withSpriteManager(SpriteManager spriteManager) {
        this.spriteManager = spriteManager;
        return this;
    }

    public TestObjectServices withFadeManager(FadeManager fadeManager) {
        this.fadeManager = fadeManager;
        return this;
    }

    public TestObjectServices withWaterSystem(WaterSystem waterSystem) {
        this.waterSystem = waterSystem;
        return this;
    }

    public TestObjectServices withParallaxManager(ParallaxManager parallaxManager) {
        this.parallaxManager = parallaxManager;
        return this;
    }

    public TestObjectServices withCollisionSystem(CollisionSystem collisionSystem) {
        this.collisionSystem = collisionSystem;
        return this;
    }

    public TestObjectServices withGraphicsManager(GraphicsManager graphicsManager) {
        this.graphicsManager = graphicsManager;
        return this;
    }

    public TestObjectServices withAudioManager(AudioManager audioManager) {
        this.audioManager = audioManager;
        return this;
    }

    public TestObjectServices withRng(GameRng rng) {
        this.rng = rng;
        return this;
    }

    public TestObjectServices withZoneRuntimeRegistry(ZoneRuntimeRegistry zoneRuntimeRegistry) {
        this.zoneRuntimeRegistry = zoneRuntimeRegistry;
        return this;
    }

    public TestObjectServices withPaletteOwnershipRegistry(PaletteOwnershipRegistry paletteOwnershipRegistry) {
        this.paletteOwnershipRegistry = paletteOwnershipRegistry;
        return this;
    }

    public TestObjectServices withZoneLayoutMutationPipeline(ZoneLayoutMutationPipeline zoneLayoutMutationPipeline) {
        this.zoneLayoutMutationPipeline = zoneLayoutMutationPipeline;
        return this;
    }

    public TestObjectServices withSolidExecutionRegistry(SolidExecutionRegistry solidExecutionRegistry) {
        this.solidExecutionRegistry = solidExecutionRegistry;
        return this;
    }

    public TestObjectServices withRom(Rom rom) {
        this.rom = rom;
        return this;
    }

    public TestObjectServices withRomReader(RomByteReader romReader) {
        this.romReader = romReader;
        return this;
    }

    public TestObjectServices withSidekicks(List<? extends PlayableEntity> sidekicks) {
        this.sidekicks = List.copyOf(sidekicks);
        return this;
    }

    public TestObjectServices withWorldSession(WorldSession worldSession) {
        this.worldSession = worldSession;
        return this;
    }

    public TestObjectServices withGameModule(GameModule gameModule) {
        this.gameModule = gameModule;
        if (gameModule != null && worldSession == null) {
            this.worldSession = new WorldSession(gameModule);
        }
        return this;
    }

    public TestObjectServices withEngineServices(EngineContext engineServices) {
        this.engineServices = engineServices;
        return this;
    }

    public TestObjectServices withConfiguration(SonicConfigurationService configuration) {
        this.configuration = configuration;
        return this;
    }

    public TestObjectServices withDebugOverlay(DebugOverlayManager debugOverlay) {
        this.debugOverlay = debugOverlay;
        return this;
    }

    public TestObjectServices withRomManager(RomManager romManager) {
        this.romManager = romManager;
        return this;
    }

    public TestObjectServices withCrossGameFeatures(CrossGameFeatureProvider crossGameFeatures) {
        this.crossGameFeatures = crossGameFeatures;
        return this;
    }

    @Override
    public ObjectManager objectManager() {
        return levelManager != null ? levelManager.getObjectManager() : isolatedObjectManager;
    }

    @Override
    public ObjectRenderManager renderManager() {
        return levelManager != null ? levelManager.getObjectRenderManager() : null;
    }

    @Override
    public LevelState levelGamestate() {
        return levelManager != null ? levelManager.getLevelGamestate() : null;
    }

    @Override
    public RespawnState checkpointState() {
        return levelManager != null ? levelManager.getCheckpointState() : null;
    }

    @Override
    public LevelManager levelManager() {
        return levelManager;
    }

    @Override
    public Level currentLevel() {
        return levelManager != null ? levelManager.getCurrentLevel() : null;
    }

    @Override
    public int romZoneId() {
        return levelManager != null ? levelManager.getRomZoneId() : 0;
    }

    @Override
    public int currentAct() {
        return levelManager != null ? levelManager.getCurrentAct() : 0;
    }

    @Override
    public int featureZoneId() {
        return levelManager != null ? levelManager.getFeatureZoneId() : 0;
    }

    @Override
    public int featureActId() {
        return levelManager != null ? levelManager.getFeatureActId() : 0;
    }

    @Override
    public ZoneFeatureProvider zoneFeatureProvider() {
        return levelManager != null ? levelManager.getZoneFeatureProvider() : null;
    }

    @Override
    public RingManager ringManager() {
        return levelManager != null ? levelManager.getRingManager() : null;
    }

    @Override
    public boolean areAllRingsCollected() {
        return levelManager != null && levelManager.areAllRingsCollected();
    }

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
        if (worldSession != null) {
            return worldSession;
        }
        return gameModule != null ? new WorldSession(gameModule) : null;
    }

    @Override
    public GameModule gameModule() {
        if (gameModule != null) {
            return gameModule;
        }
        return worldSession != null ? worldSession.getGameModule() : null;
    }

    @Override
    public SpriteManager spriteManager() {
        return spriteManager;
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
    public CollisionSystem collisionSystem() {
        return collisionSystem;
    }

    @Override
    public GraphicsManager graphicsManager() {
        return graphicsManager;
    }

    @Override
    public AudioManager audioManager() {
        return audioManager;
    }

    @Override
    public EngineContext engineServices() {
        return engineServices;
    }

    @Override
    public SonicConfigurationService configuration() {
        return configuration;
    }

    @Override
    public DebugOverlayManager debugOverlay() {
        return debugOverlay;
    }

    @Override
    public RomManager romManager() {
        return romManager;
    }

    @Override
    public CrossGameFeatureProvider crossGameFeatures() {
        return crossGameFeatures;
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

    @Override
    public void playSfx(int soundId) {
    }

    @Override
    public void playSfx(GameSound sound) {
    }

    @Override
    public void playMusic(int musicId) {
    }

    @Override
    public void fadeOutMusic() {
    }

    @Override
    public Rom rom() {
        return rom;
    }

    @Override
    public RomByteReader romReader() {
        return romReader;
    }

    @Override
    public List<PlayableEntity> sidekicks() {
        if (sidekicks.isEmpty() && spriteManager != null) {
            return List.copyOf(spriteManager.getSidekicks());
        }
        return sidekicks;
    }

    @Override
    public void spawnLostRings(PlayableEntity player, int frameCounter) {
    }

    @Override
    public void advanceToNextLevel() {
    }

    @Override
    public void requestCreditsTransition() {
    }

    @Override
    public void requestSpecialStageEntry() {
    }

    @Override
    public void invalidateForegroundTilemap() {
    }

    @Override
    public void updatePalette(int paletteIndex, byte[] paletteData) {
    }

    @Override
    public void advanceZoneActOnly() {
    }

    @Override
    public void setApparentAct(int act) {
    }

    @Override
    public void advanceToSpecialStageEntryRoutine() {
    }

    @Override
    public void requestBonusStageEntry(com.openggf.game.BonusStageType type) {
        if (levelManager != null) {
            levelManager.requestBonusStageEntry(type);
        }
    }

    @Override
    public void requestBonusStageExit() {
    }

    @Override
    public void addBonusStageRings(int count) {
    }

    @Override
    public void setBonusStageShield(com.openggf.game.ShieldType type) {
    }

    @Override
    public void requestZoneAndAct(int zone, int act) {
    }

    @Override
    public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow) {
    }

    @Override
    public int getCurrentLevelMusicId() {
        return levelManager != null ? levelManager.getCurrentLevelMusicId() : -1;
    }

    @Override
    public int[] findPatternOffset(int refX, int refY, int minTileIdx, int maxTileIdx, int searchRadius) {
        return levelManager != null
                ? levelManager.findPatternOffset(refX, refY, minTileIdx, maxTileIdx, searchRadius)
                : null;
    }

    @Override
    public void saveBigRingReturn(com.openggf.level.BigRingReturnState state) {
        if (levelManager != null) {
            levelManager.saveBigRingReturn(state);
        }
    }

    @Override
    public void clearLastStarPostHit() {
        if (levelManager != null) {
            levelManager.clearLastStarPostHit();
        }
    }

    @Override
    public void requestSessionSave(SaveReason reason) {
    }
}
