package com.openggf.game.sonic2;

import com.openggf.game.sonic2.slotmachine.CNZPrizeSoundState;
import com.openggf.audio.GameAudioProfile;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameOverFlowProvider;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectConstants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.sonic2.credits.Sonic2EndingProvider;
import com.openggf.game.sonic2.dataselect.S2SaveSnapshotProvider;
import com.openggf.game.sonic2.dataselect.S2DataSelectImageCacheManager;
import com.openggf.game.sonic2.debug.Sonic2DebugModeProvider;
import com.openggf.game.sonic2.levelselect.LevelSelectManager;
import com.openggf.game.sonic2.objects.BlueBallsObjectInstance;
import com.openggf.game.sonic2.objects.BonusBlockObjectInstance;
import com.openggf.game.sonic2.objects.LauncherBallObjectInstance;
import com.openggf.game.sonic2.objects.MTZLongPlatformObjectInstance;
import com.openggf.game.sonic2.objects.SmashableGroundObjectInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.scroll.Sonic2ScrollHandlerProvider;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.game.sonic2.titlecard.TitleCardManager;
import com.openggf.game.sonic2.titlescreen.TitleScreenManager;
import com.openggf.game.CanonicalAnimation;
import com.openggf.game.CheckpointState;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.CrossGameDonorProvider;
import com.openggf.game.DonorCapabilities;
import com.openggf.game.EndingProvider;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.LevelGamestate;
import com.openggf.game.OscillationManager;
import com.openggf.game.PhysicsProvider;
import com.openggf.game.LevelSelectProvider;
import com.openggf.game.LevelState;
import com.openggf.game.RespawnState;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.ZoneRegistry;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.ScrollHandlerProvider;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.RomOffsetProvider;
import com.openggf.game.DebugModeProvider;
import com.openggf.game.DebugOverlayProvider;
import com.openggf.game.GameId;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.ZoneArtProvider;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.dataselect.CrossGameDataSelectPresentations;
import com.openggf.game.dataselect.DataSelectHostProfile;
import com.openggf.game.dataselect.DataSelectPresentationProvider;
import com.openggf.game.startup.DonatedDataSelectWarmupTask;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.timing.Sonic2LevelMusicScheduler;
import com.openggf.game.sonic2.dataselect.S2DataSelectProfile;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.PlaneSwitcherConfig;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.level.Palette;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.SuperStateController;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static java.security.MessageDigest.getInstance;

public class Sonic2GameModule implements GameModule {
    private final CNZPrizeSoundState cnzPrizeSoundState =
            new CNZPrizeSoundState();
    private final GameAudioProfile audioProfile = new Sonic2AudioProfile();
    private final Sonic2LevelEventManager levelEventManager = new Sonic2LevelEventManager();
    private final Sonic2PlayerArtModeAuthority playerArtModeAuthority = () ->
            Sonic2PlayerArtModeAuthority.onePlayer(levelEventManager.getPlayerCharacter()).initialLifePlc();
    private final Sonic2ZoneRegistry zoneRegistry = new Sonic2ZoneRegistry();
    private final com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug specialStageSpriteDebug =
            new com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug();
    private final com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManager specialStageManager =
            new com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManager(specialStageSpriteDebug);
    private final SpecialStageProvider specialStageProvider = new Sonic2SpecialStageProvider(specialStageManager);
    private final DebugModeProvider debugModeProvider =
            new Sonic2DebugModeProvider(specialStageManager, specialStageSpriteDebug);
    private final Sonic2LevelMusicScheduler levelMusicScheduler =
            new Sonic2LevelMusicScheduler();
    private final LevelInitProfile levelInitProfile = new Sonic2LevelInitProfile(
            levelEventManager, playerArtModeAuthority, levelMusicScheduler);
    private final TitleCardManager titleCardProvider = new TitleCardManager();
    private final TitleScreenManager titleScreenProvider = new TitleScreenManager();
    private final LevelSelectManager levelSelectProvider = new LevelSelectManager();
    private final S2DataSelectProfile dataSelectHostProfile = new S2DataSelectProfile();
    private final CrossGameDonorProvider donorProvider = new Sonic2CrossGameDonorProvider();
    private DataSelectPresentationProvider dataSelectPresentationProvider;
    private S2DataSelectImageCacheManager dataSelectImageCacheManager;
    private Sonic2ObjectArtProvider objectArtProvider;
    private Sonic2ZoneFeatureProvider zoneFeatureProvider;
    private PhysicsProvider physicsProvider;
    private ObjectRegistry objectRegistry;
    private Sonic2PlcService plcService;

    @Override
    public String getIdentifier() {
        return "Sonic2";
    }

    @Override
    public int getTailsTailVramBase() {
        return Sonic2Constants.ART_TILE_TAILS_TAILS;
    }

    @Override
    public GameId getGameId() {
        return GameId.S2;
    }

    @Override
    public com.openggf.game.profiles.trace.TracePlaybackProfile getTracePlaybackProfile() {
        return com.openggf.game.profiles.trace.TracePlaybackProfile.SONIC_2;
    }

    @Override
    public Game createGame(Rom rom) {
        plcService = new Sonic2PlcService(rom);
        return new Sonic2(rom);
    }

    @Override
    public List<com.openggf.game.rewind.RewindSnapshottable<?>> rewindAdapters() {
        return plcService == null
                ? List.of(levelMusicScheduler, cnzPrizeSoundState)
                : List.of(plcService, levelMusicScheduler, cnzPrizeSoundState);
    }

    @Override
    public ObjectRegistry createObjectRegistry() {
        if (objectRegistry == null) {
            objectRegistry = new Sonic2ObjectRegistry();
        }
        return objectRegistry;
    }

    @Override
    public GameAudioProfile getAudioProfile() {
        return audioProfile;
    }

    @Override
    public TouchResponseTable createTouchResponseTable(RomByteReader romReader) {
        return new TouchResponseTable(romReader,
                Sonic2ObjectConstants.TOUCH_SIZES_ADDR,
                Sonic2ObjectConstants.TOUCH_ENTRY_COUNT);
    }

    @Override
    public int getPlaneSwitcherObjectId() {
        return Sonic2ObjectIds.LAYER_SWITCHER;
    }

    @Override
    public int getCheckpointObjectId() {
        return Sonic2ObjectIds.CHECKPOINT;
    }

    @Override
    public PlaneSwitcherConfig getPlaneSwitcherConfig() {
        return new PlaneSwitcherConfig(
                Sonic2ObjectConstants.PATH0_TOP_SOLID_BIT,
                Sonic2ObjectConstants.PATH0_LRB_SOLID_BIT,
                Sonic2ObjectConstants.PATH1_TOP_SOLID_BIT,
                Sonic2ObjectConstants.PATH1_LRB_SOLID_BIT);
    }

    @Override
    public LevelEventProvider getLevelEventProvider() {
        return levelEventManager;
    }

    @Override
    public RespawnState createRespawnState() {
        return new CheckpointState();
    }

    @Override
    public LevelState createLevelState() {
        return new LevelGamestate();
    }

    @Override
    public TitleCardProvider getTitleCardProvider() {
        return titleCardProvider;
    }

    private final GameOverFlowProvider gameOverFlowProvider = new Sonic2GameOverFlowProvider();

    @Override
    public GameOverFlowProvider getGameOverFlowProvider() {
        return gameOverFlowProvider;
    }

    @Override
    public ZoneRegistry getZoneRegistry() {
        return zoneRegistry;
    }

    @Override
    public SpecialStageProvider getSpecialStageProvider() {
        return specialStageProvider;
    }

    @Override
    public int getSpecialStageCycleCount() {
        return 7;
    }

    @Override
    public int getChaosEmeraldCount() {
        return 7;
    }

    // Fresh instance per call. Callers store for the level's lifetime. Stateless.
    @Override
    public ScrollHandlerProvider getScrollHandlerProvider() {
        return new Sonic2ScrollHandlerProvider();
    }

    // Lazily cached — same instance across level loads. Reset via module replacement.
    @Override
    public ZoneFeatureProvider getZoneFeatureProvider() {
        if (zoneFeatureProvider == null) {
            zoneFeatureProvider = new Sonic2ZoneFeatureProvider();
        }
        return zoneFeatureProvider;
    }

    // Fresh instance per call. Callers store for the level's lifetime. Stateless.
    @Override
    public WaterDataProvider getWaterDataProvider() {
        return new Sonic2WaterDataProvider();
    }

    // Fresh instance per call. Callers store for the level's lifetime. Stateless.
    @Override
    public RomOffsetProvider getRomOffsetProvider() {
        return new Sonic2RomOffsetProvider();
    }

    // Module-owned instance so special-stage debug/controller state stays coherent.
    @Override
    public DebugModeProvider getDebugModeProvider() {
        return debugModeProvider;
    }

    @Override
    public DebugOverlayProvider getDebugOverlayProvider() {
        // Debug overlay content is currently handled by the generic DebugRenderer
        // Future: Create Sonic2DebugOverlayProvider for game-specific overlay content
        return null;
    }

    // Fresh instance per call. Callers store for the level's lifetime. Stateless.
    @Override
    public ZoneArtProvider getZoneArtProvider() {
        return new Sonic2ZoneArtProvider();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getGameService(Class<T> type) {
        if (type == CNZPrizeSoundState.class) return (T) cnzPrizeSoundState;
        if (type == S2DataSelectImageCacheManager.class) return (T) getDataSelectImageCacheManager();
        if (type == Sonic2LevelEventManager.class) return (T) levelEventManager;
        if (type == Sonic2ZoneRegistry.class) return (T) zoneRegistry;
        if (type == com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug.class)
            return (T) specialStageSpriteDebug;
        if (type == com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManager.class)
            return (T) specialStageManager;
        if (type == Sonic2PlcService.class) return (T) plcService;
        if (type == PlcLifecycleService.class) return (T) plcService;
        return null;
    }

    @Override
    public Optional<DonatedDataSelectWarmupTask> getDonatedDataSelectWarmupTask() {
        S2DataSelectImageCacheManager manager = getDataSelectImageCacheManager();
        if (manager instanceof DonatedDataSelectWarmupTask warmup) {
            return Optional.of(warmup);
        }
        return Optional.empty();
    }

    @Override
    public void onLevelLoad() {
        // Reset oscillation values used by moving platforms, etc.
        OscillationManager.reset();
        // Reset object-specific static state that persists across load/unload cycles
        BlueBallsObjectInstance.resetGlobalState();
        BonusBlockObjectInstance.resetGroupCounters();
        LauncherBallObjectInstance.clearActiveCaptures();
        ButtonVineTriggerManager.reset();
        SmashableGroundObjectInstance.resetGlobalState();
        MTZLongPlatformObjectInstance.resetGlobalState();
        cnzPrizeSoundState.resetForMissingSnapshot();
    }

    @Override
    public void resetModuleScopedState() {
        plcService = null;
    }

    @Override
    public com.openggf.game.ContinueScreenProvider createContinueScreenProvider() {
        return new com.openggf.game.sonic2.continuescreen.Sonic2ContinueScreenProvider();
    }

    @Override
    public TitleScreenProvider getTitleScreenProvider() {
        return titleScreenProvider;
    }

    @Override
    public LevelSelectProvider getLevelSelectProvider() {
        return levelSelectProvider;
    }

    @Override
    public com.openggf.game.DataSelectProvider getDataSelectProvider() {
        return getDataSelectPresentationProvider();
    }

    @Override
    public DataSelectPresentationProvider getDataSelectPresentationProvider() {
        if (dataSelectPresentationProvider == null) {
            dataSelectPresentationProvider = CrossGameDataSelectPresentations.donated(
                    CrossGameDataSelectPresentations.DONOR_S3K, dataSelectHostProfile);
        }
        return dataSelectPresentationProvider;
    }

    @Override
    public DataSelectHostProfile getDataSelectHostProfile() {
        return dataSelectHostProfile;
    }

    // Lazily cached — same instance across level loads. Reset via module replacement.
    @Override
    public ObjectArtProvider getObjectArtProvider() {
        if (objectArtProvider == null) {
            objectArtProvider = new Sonic2ObjectArtProvider();
        }
        return objectArtProvider;
    }

    // Lazily cached — same instance across level loads. Reset via module replacement.
    @Override
    public PhysicsProvider getPhysicsProvider() {
        if (physicsProvider == null) {
            physicsProvider = new Sonic2PhysicsProvider();
        }
        return physicsProvider;
    }

    @Override
    public LevelInitProfile getLevelInitProfile() {
        return levelInitProfile;
    }

    @Override
    public SuperStateController createSuperStateController(
            AbstractPlayableSprite player) {
        // S2 grants a Super form to Sonic and to nobody else. The whole
        // transformation path lives in Sonic's own object: Sonic_CheckGoSuper
        // is reached from Sonic_JumpHeight inside Obj01_MdJump
        // (docs/s2disasm/s2.asm:37432, :37455), and Sonic_Super is called from
        // Obj01_Control (:36249). Obj02 (Tails) has no counterpart of either --
        // Super Tails is an S3K feature and needs the super emeralds, which S2
        // does not have. Level setup asks for a controller once per playable,
        // sidekick included, so the decline belongs here: the module is the
        // owner of "which characters this game lets transform".
        if (!(player instanceof Sonic)) {
            return null;
        }
        if (CrossGameFeatureProvider.isActive()) {
            return GameServices.crossGameFeatures().createSuperStateController(player);
        }
        return new Sonic2SuperStateController(player);
    }

    @Override
    public boolean supportsSidekick() {
        return true;
    }

    @Override
    public boolean isSidekickSuppressedForZone(int zoneId) {
        return zoneId == Sonic2ZoneConstants.ZONE_SCZ
            || zoneId == Sonic2ZoneConstants.ZONE_WFZ
            || zoneId == Sonic2ZoneConstants.ZONE_DEZ;
    }

    // Fresh instance per call. Callers store for the level's lifetime. Stateless.
    @Override
    public EndingProvider getEndingProvider() {
        return new Sonic2EndingProvider();
    }

    @Override
    public com.openggf.game.save.SaveSnapshotProvider getSaveSnapshotProvider() {
        return new S2SaveSnapshotProvider();
    }

    private S2DataSelectImageCacheManager getDataSelectImageCacheManager() {
        if (dataSelectImageCacheManager == null) {
            dataSelectImageCacheManager = new WarmupAwareS2DataSelectImageCacheManager(
                    Path.of("saves", "image-cache", "s2"),
                    GameServices.configuration(),
                    this::romSha256,
                    new ObjectMapper());
        }
        return dataSelectImageCacheManager;
    }

    private String romSha256() {
        try {
            Rom rom = GameServices.rom().getRom();
            MessageDigest digest = getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rom.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash Sonic 2 ROM for data select image generation", e);
        }
    }

    public interface S2DataSelectImageWarmup {
        void ensureGenerationStarted();
    }

    private static final class WarmupAwareS2DataSelectImageCacheManager
            extends S2DataSelectImageCacheManager implements S2DataSelectImageWarmup, DonatedDataSelectWarmupTask {

        private WarmupAwareS2DataSelectImageCacheManager(Path cacheRoot,
                                                         com.openggf.configuration.SonicConfigurationService config,
                                                         java.util.function.Supplier<String> romSha256Supplier,
                                                         ObjectMapper mapper) {
            super(cacheRoot, config, romSha256Supplier, mapper);
        }

        @Override
        public void ensureGenerationStarted() {
            super.ensureGenerationStarted();
        }

        @Override
        public void start() {
            ensureGenerationStarted();
        }

        @Override
        public boolean isRunning() {
            return isGenerationRunning();
        }
    }

    @Override
    public DonorCapabilities getDonorCapabilities() {
        return Sonic2DonorCapabilities.INSTANCE;
    }

    @Override
    public CrossGameDonorProvider getCrossGameDonorProvider() {
        return donorProvider;
    }

    /** Lazily-constructed singleton holding S2 donation metadata. */
    private static final class Sonic2DonorCapabilities implements DonorCapabilities {

        static final Sonic2DonorCapabilities INSTANCE = new Sonic2DonorCapabilities();

        private static final java.util.Set<PlayerCharacter> CHARACTERS =
                java.util.Set.of(
                        PlayerCharacter.SONIC_ALONE,
                        PlayerCharacter.SONIC_AND_TAILS,
                        PlayerCharacter.TAILS_ALONE);

        private static final java.util.Map<CanonicalAnimation, CanonicalAnimation> FALLBACKS =
                buildFallbacks();

        private static java.util.Map<CanonicalAnimation, CanonicalAnimation> buildFallbacks() {
            return DonorCapabilities.buildFallbackMap(
                    Sonic2AnimationIds.values(), Sonic2AnimationIds::toCanonical,
                    java.util.Map.ofEntries(
                            // S1-specific animations -> nearest S2 native fallback
                            java.util.Map.entry(CanonicalAnimation.STOP,        CanonicalAnimation.SKID),
                            java.util.Map.entry(CanonicalAnimation.WARP1,       CanonicalAnimation.ROLL),
                            java.util.Map.entry(CanonicalAnimation.WARP2,       CanonicalAnimation.ROLL),
                            java.util.Map.entry(CanonicalAnimation.WARP3,       CanonicalAnimation.ROLL),
                            java.util.Map.entry(CanonicalAnimation.WARP4,       CanonicalAnimation.ROLL),
                            java.util.Map.entry(CanonicalAnimation.FLOAT3,      CanonicalAnimation.SPRING),
                            java.util.Map.entry(CanonicalAnimation.FLOAT4,      CanonicalAnimation.SPRING),
                            java.util.Map.entry(CanonicalAnimation.LEAP1,       CanonicalAnimation.SPRING),
                            java.util.Map.entry(CanonicalAnimation.LEAP2,       CanonicalAnimation.SPRING),
                            java.util.Map.entry(CanonicalAnimation.SURF,        CanonicalAnimation.WAIT),
                            java.util.Map.entry(CanonicalAnimation.GET_AIR,     CanonicalAnimation.BUBBLE),
                            java.util.Map.entry(CanonicalAnimation.BURNT,       CanonicalAnimation.HURT),
                            java.util.Map.entry(CanonicalAnimation.SHRINK,      CanonicalAnimation.DEATH),
                            java.util.Map.entry(CanonicalAnimation.WATER_SLIDE, CanonicalAnimation.SLIDE),
                            java.util.Map.entry(CanonicalAnimation.NULL_ANIM,   CanonicalAnimation.WAIT),
                            // S3K-specific animations -> nearest S2 native fallback
                            java.util.Map.entry(CanonicalAnimation.VICTORY,     CanonicalAnimation.WAIT),
                            java.util.Map.entry(CanonicalAnimation.GLIDE_DROP,  CanonicalAnimation.SPRING),
                            java.util.Map.entry(CanonicalAnimation.GLIDE_LAND,  CanonicalAnimation.WAIT),
                            java.util.Map.entry(CanonicalAnimation.GLIDE_SLIDE, CanonicalAnimation.SLIDE),
                            java.util.Map.entry(CanonicalAnimation.BLANK,       CanonicalAnimation.WAIT),
                            java.util.Map.entry(CanonicalAnimation.HURT_FALL,   CanonicalAnimation.HURT)
                    ));
        }

        @Override
        public java.util.Set<PlayerCharacter> getPlayableCharacters() { return CHARACTERS; }

        @Override
        public boolean hasSpindash() { return true; }

        @Override
        public boolean hasSuperTransform() { return true; }

        @Override
        public boolean hasHyperTransform() { return false; }

        @Override
        public boolean hasInstaShield() { return false; }

        @Override
        public boolean hasTailsFlight() { return false; }

        @Override
        public boolean hasElementalShields() { return false; }

        @Override
        public boolean hasSidekick() { return true; }

        @Override
        public java.util.Map<CanonicalAnimation, CanonicalAnimation> getAnimationFallbacks() {
            return FALLBACKS;
        }

        @Override
        public int resolveNativeId(CanonicalAnimation canonical) {
            return Sonic2AnimationIds.fromCanonical(canonical);
        }

        @Override
        public com.openggf.data.PlayerSpriteArtProvider getPlayerArtProvider(
                com.openggf.data.RomByteReader reader) {
            var art = new Sonic2PlayerArt(reader);
            return art::loadForCharacter;
        }
    }

    private static final class Sonic2CrossGameDonorProvider implements CrossGameDonorProvider {
        @Override
        public DonorCapabilities getDonorCapabilities() {
            return Sonic2DonorCapabilities.INSTANCE;
        }

        @Override
        public com.openggf.data.PlayerSpriteArtProvider createPlayerArtProvider(RomByteReader reader) {
            return Sonic2DonorCapabilities.INSTANCE.getPlayerArtProvider(reader);
        }

        @Override
        public com.openggf.data.SpindashDustArtProvider createSpindashDustArtProvider(RomByteReader reader) {
            Sonic2DustArt dustArt = new Sonic2DustArt(reader);
            return dustArt::loadForCharacter;
        }

        @Override
        public GameAudioProfile getAudioProfile() {
            return new Sonic2AudioProfile();
        }

        @Override
        public Palette loadCharacterPalette(RomByteReader reader, String characterCode) {
            byte[] data = reader.slice(Sonic2Constants.SONIC_TAILS_PALETTE_ADDR, Palette.PALETTE_SIZE_IN_ROM);
            Palette palette = new Palette();
            palette.fromSegaFormat(data);
            return palette;
        }

        @Override
        public SuperStateController createSuperStateController(AbstractPlayableSprite player) {
            return new Sonic2SuperStateController(player);
        }
    }
}
