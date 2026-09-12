package com.openggf.game.sonic1;

import com.openggf.audio.GameAudioProfile;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameOverFlowProvider;
import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import com.openggf.game.sonic1.events.Sonic1LevelEventManager;
import com.openggf.game.sonic1.dataselect.S1DataSelectImageCacheManager;
import com.openggf.game.sonic1.objects.Sonic1StomperDoorObjectInstance;
import com.openggf.game.sonic1.scroll.Sonic1ZoneConstants;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageProvider;
import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenManager;
import com.openggf.game.DebugOverlayProvider;
import com.openggf.game.EndingProvider;
import com.openggf.game.GameModule;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.LevelSelectProvider;
import com.openggf.game.LevelState;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.PhysicsProvider;
import com.openggf.game.RespawnState;
import com.openggf.game.ScrollHandlerProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.ZoneRegistry;
import com.openggf.game.dataselect.CrossGameDataSelectPresentations;
import com.openggf.game.dataselect.DataSelectHostProfile;
import com.openggf.game.dataselect.DataSelectPresentationProvider;
import com.openggf.game.startup.DonatedDataSelectWarmupTask;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.game.sonic1.resources.Sonic1RuntimeArtCoordinator;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.credits.Sonic1EndingProvider;
import com.openggf.game.sonic1.dataselect.S1DataSelectProfile;
import com.openggf.game.sonic1.dataselect.S1SaveSnapshotProvider;
import com.openggf.game.sonic1.levelselect.Sonic1LevelSelectManager;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic1.scroll.Sonic1ScrollHandlerProvider;
import com.openggf.game.CheckpointState;
import com.openggf.game.CanonicalAnimation;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.CrossGameDonorProvider;
import com.openggf.game.DonorCapabilities;
import com.openggf.game.GameId;
import com.openggf.game.LevelGamestate;
import com.openggf.game.OscillationManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic1.titlecard.Sonic1TitleCardManager;
import com.openggf.game.profiles.trace.TracePlaybackProfile;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.PlaneSwitcherConfig;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.level.Palette;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SuperStateController;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static java.security.MessageDigest.getInstance;

/**
 * GameModule implementation for Sonic the Hedgehog 1 (Mega Drive/Genesis).
 */
public class Sonic1GameModule implements GameModule {

    private static final Sonic1ObjectPlacementEncoding OBJECT_PLACEMENT_ENCODING =
            new Sonic1ObjectPlacementEncoding();
    private final GameAudioProfile audioProfile = new Sonic1AudioProfile();
    private final SpecialStageProvider specialStageProvider = new Sonic1SpecialStageProvider();
    private final Sonic1LevelEventManager levelEventManager = new Sonic1LevelEventManager();
    private final Sonic1ZoneRegistry zoneRegistry = new Sonic1ZoneRegistry();
    private final Sonic1SwitchManager switchManager = new Sonic1SwitchManager();
    private final Sonic1SwitchStateRewindAdapter switchStateRewindAdapter =
            new Sonic1SwitchStateRewindAdapter();
    private final Sonic1StomperDoorSingletonRewindAdapter stomperDoorSingletonRewindAdapter =
            new Sonic1StomperDoorSingletonRewindAdapter();
    private final Sonic1ConveyorState conveyorState = new Sonic1ConveyorState();
    private final Sonic1FloatingBlockState floatingBlockState = new Sonic1FloatingBlockState();
    private final Sonic1TitleCardManager titleCardProvider = new Sonic1TitleCardManager();
    private final Sonic1TitleScreenManager titleScreenProvider = new Sonic1TitleScreenManager();
    private final Sonic1LevelSelectManager levelSelectProvider = new Sonic1LevelSelectManager();
    private final S1DataSelectProfile dataSelectHostProfile = new S1DataSelectProfile();
    private final CrossGameDonorProvider donorProvider = new Sonic1CrossGameDonorProvider();
    private DataSelectPresentationProvider dataSelectPresentationProvider;
    private S1DataSelectImageCacheManager dataSelectImageCacheManager;
    private final LevelInitProfile levelInitProfile =
            new Sonic1LevelInitProfile(levelEventManager, switchManager, conveyorState);
    private PhysicsProvider physicsProvider;
    private ObjectRegistry objectRegistry;
    private Sonic1PlcService plcService;

    @Override
    public String getIdentifier() {
        return "Sonic1";
    }

    @Override
    public GameId getGameId() {
        return GameId.S1;
    }

    @Override
    public TracePlaybackProfile getTracePlaybackProfile() {
        return TracePlaybackProfile.SONIC_1;
    }

    /**
     * S1 {@code ExItem_Main} loads {@code move.b #7,obTimeFrame(a0)} for the
     * badnik-death explosion (docs/s1disasm/_incObj/24, 27 &amp; 3F
     * Explosions.asm), so the explosion's frame 0 is held 8 game frames — unlike
     * S2/S3K which load {@code 3}. See {@link GameModule#explosionInitialAnimDuration()}.
     */
    @Override
    public int explosionInitialAnimDuration() {
        return 7;
    }

    @Override
    public Game createGame(Rom rom) {
        plcService = new Sonic1PlcService(rom);
        return new Sonic1(rom);
    }

    @Override
    public List<com.openggf.game.rewind.RewindSnapshottable<?>> rewindAdapters() {
        if (plcService == null) {
            return List.of(switchStateRewindAdapter, stomperDoorSingletonRewindAdapter);
        }
        return List.of(plcService, switchStateRewindAdapter, stomperDoorSingletonRewindAdapter);
    }

    @Override
    public ObjectRegistry createObjectRegistry() {
        if (objectRegistry == null) {
            objectRegistry = new Sonic1ObjectRegistry();
        }
        return objectRegistry;
    }

    @Override
    public com.openggf.level.objects.ObjectPlacementEncoding getObjectPlacementEncoding() {
        return OBJECT_PLACEMENT_ENCODING;
    }

    @Override
    public GameAudioProfile getAudioProfile() {
        return audioProfile;
    }

    @Override
    public TouchResponseTable createTouchResponseTable(RomByteReader romReader) {
        // S1 ReactToItem .sizes table: 36 entries at 0x1B5E4.
        // S1 uses `lea .sizes-2(pc,d0.w)` so effective base is .sizes-2 = 0x1B5E2.
        return new TouchResponseTable(romReader,
                Sonic1Constants.TOUCH_SIZES_ADDR, Sonic1Constants.TOUCH_ENTRY_COUNT);
    }

    @Override
    public int getPlaneSwitcherObjectId() {
        // Sonic 1 does not have a dedicated plane switcher object
        return 0;
    }

    @Override
    public int getCheckpointObjectId() {
        return Sonic1ObjectIds.LAMPPOST;
    }

    @Override
    public PlaneSwitcherConfig getPlaneSwitcherConfig() {
        return new PlaneSwitcherConfig((byte) 0, (byte) 0, (byte) 0, (byte) 0);
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

    private final GameOverFlowProvider gameOverFlowProvider = new Sonic1GameOverFlowProvider();

    @Override
    public GameOverFlowProvider getGameOverFlowProvider() {
        return gameOverFlowProvider;
    }

    @Override
    public TitleCardProvider getTitleCardProvider() {
        return titleCardProvider;
    }

    @Override
    public com.openggf.game.ContinueScreenProvider createContinueScreenProvider() {
        return new com.openggf.game.sonic1.continuescreen.Sonic1ContinueScreenProvider();
    }

    @Override
    public TitleScreenProvider getTitleScreenProvider() {
        return titleScreenProvider;
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
        return 6;
    }

    @Override
    public int getChaosEmeraldCount() {
        return 6;
    }

    @Override
    public ScrollHandlerProvider getScrollHandlerProvider() {
        return new Sonic1ScrollHandlerProvider();
    }

    @Override
    public ZoneFeatureProvider getZoneFeatureProvider() {
        return new Sonic1ZoneFeatureProvider();
    }

    @Override
    public WaterDataProvider getWaterDataProvider() {
        return new Sonic1WaterDataProvider();
    }

    @Override
    public DebugOverlayProvider getDebugOverlayProvider() {
        return null;
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

    @Override
    public ObjectArtProvider getObjectArtProvider() {
        // Keep provider per-load to avoid stale same-zone state carrying across restarts.
        return new Sonic1ObjectArtProvider();
    }

    @Override
    public void applyPlaneSwitching(AbstractPlayableSprite player) {
        levelEventManager.getLoopManager().update(player);
    }

    @Override
    public RuntimeArtCoordinator createRuntimeArtCoordinator(
            HardwareTimingService timing) {
        return new Sonic1RuntimeArtCoordinator(timing, () -> plcService);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getGameService(Class<T> type) {
        if (type == S1DataSelectImageCacheManager.class) return (T) getDataSelectImageCacheManager();
        if (type == Sonic1LevelEventManager.class) return (T) levelEventManager;
        if (type == Sonic1ZoneRegistry.class) return (T) zoneRegistry;
        if (type == Sonic1SwitchManager.class) return (T) switchManager;
        if (type == Sonic1ConveyorState.class) return (T) conveyorState;
        if (type == Sonic1FloatingBlockState.class) return (T) floatingBlockState;
        if (type == Sonic1PlcService.class) return (T) plcService;
        if (type == PlcLifecycleService.class) return (T) plcService;
        return null;
    }

    @Override
    public Optional<DonatedDataSelectWarmupTask> getDonatedDataSelectWarmupTask() {
        S1DataSelectImageCacheManager manager = getDataSelectImageCacheManager();
        if (manager instanceof DonatedDataSelectWarmupTask warmup) {
            return Optional.of(warmup);
        }
        return Optional.empty();
    }

    @Override
    public void onLevelLoad() {
        // Reset oscillation values to Sonic 1 settings.
        // S1 differs from S2 in oscillators 8+ (amplitude, initial values).
        // Reference: docs/s1disasm/_inc/Oscillatory Routines.asm
        OscillationManager.resetForSonic1();
        // Reset switch state for new level (Sonic 1 f_switch array)
        switchManager.reset();
        // Reset v_obj6B singleton flag for SBZ3 StomperDoor
        Sonic1StomperDoorObjectInstance.resetSbz3Flag();
        // Reset conveyor belt state for new level (Sonic 1 f_conveyrev + v_obj63)
        conveyorState.reset();
        // Reset REV01 f_obj56 for every fresh level/restart.
        floatingBlockState.reset();
    }

    @Override
    public void resetModuleScopedState() {
        plcService = null;
    }

    @Override
    public java.util.function.BiFunction<Integer, Integer,
            com.openggf.level.objects.AbstractObjectInstance> getWaterSplashFactory() {
        // S1 LZ splash art from ObjectRenderManager (Object 0x08).
        return com.openggf.game.sonic1.objects.Sonic1SplashObjectInstance::new;
    }

    @Override
    public PhysicsProvider getPhysicsProvider() {
        if (physicsProvider == null) {
            physicsProvider = new Sonic1PhysicsProvider();
        }
        return physicsProvider;
    }

    @Override
    public int getRemappedFeatureZone(int logicalZone, int act, int levelZoneIndex) {
        // S1 SBZ act 3 is loaded from LZ zone data; remap to SBZ for feature lookups
        if (logicalZone == Sonic1ZoneConstants.ZONE_SBZ
                && act == 2
                && levelZoneIndex == Sonic1Constants.ZONE_LZ) {
            return Sonic1Constants.ZONE_SBZ;
        }
        return -1;
    }

    @Override
    public int getRemappedFeatureAct(int logicalZone, int act, int levelZoneIndex) {
        if (logicalZone == Sonic1ZoneConstants.ZONE_SBZ
                && act == 2
                && levelZoneIndex == Sonic1Constants.ZONE_LZ) {
            return 2;
        }
        return -1;
    }

    @Override
    public int getRomAct(int logicalZone, int act, int levelZoneIndex) {
        // Scrap Brain act 3 is the LZ slot's act 4: v_zone=id_LZ, v_act=act4.
        // sonic.asm:2755-2768 enables water for the whole id_LZ slot, and
        // sonic.asm:2779 then picks the SBZ3 underwater palette off act4.
        if (logicalZone == Sonic1ZoneConstants.ZONE_SBZ
                && act == 2
                && levelZoneIndex == Sonic1Constants.ZONE_LZ) {
            return 3;
        }
        // The ROM has no separate Final Zone level id: FZ is SBZ act 3, so
        // v_zone=id_SBZ and v_act reads act3 there. The engine models FZ as its
        // own logical zone whose act index restarts at 0.
        if (logicalZone == Sonic1ZoneConstants.ZONE_FZ) {
            return 2;
        }
        return -1;
    }

    @Override
    public EndingProvider getEndingProvider() {
        return new Sonic1EndingProvider();
    }

    @Override
    public com.openggf.game.save.SaveSnapshotProvider getSaveSnapshotProvider() {
        return new S1SaveSnapshotProvider();
    }

    @Override
    public LevelInitProfile getLevelInitProfile() {
        return levelInitProfile;
    }

    @Override
    public boolean hasTrailInvincibilityStars() {
        return true;
    }

    @Override
    public SuperStateController createSuperStateController(AbstractPlayableSprite player) {
        if (CrossGameFeatureProvider.isActive()) {
            return GameServices.crossGameFeatures().createSuperStateController(player);
        }
        return null; // Vanilla S1 has no Super Sonic
    }

    @Override
    public DonorCapabilities getDonorCapabilities() {
        return Sonic1DonorCapabilities.INSTANCE;
    }

    @Override
    public CrossGameDonorProvider getCrossGameDonorProvider() {
        return donorProvider;
    }

    private S1DataSelectImageCacheManager getDataSelectImageCacheManager() {
        if (dataSelectImageCacheManager == null) {
            dataSelectImageCacheManager = new WarmupAwareS1DataSelectImageCacheManager(
                    Path.of("saves", "image-cache", "s1"),
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
            throw new IllegalStateException("Unable to hash Sonic 1 ROM for data select image generation", e);
        }
    }

    public interface S1DataSelectImageWarmup {
        void ensureGenerationStarted();
    }

    private static final class WarmupAwareS1DataSelectImageCacheManager
            extends S1DataSelectImageCacheManager implements S1DataSelectImageWarmup, DonatedDataSelectWarmupTask {

        private WarmupAwareS1DataSelectImageCacheManager(Path cacheRoot,
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

    /** Lazily-constructed singleton holding S1 donation metadata. */
    private static final class Sonic1DonorCapabilities implements DonorCapabilities {

        static final Sonic1DonorCapabilities INSTANCE = new Sonic1DonorCapabilities();

        private static final java.util.Set<PlayerCharacter> CHARACTERS =
                java.util.Set.of(PlayerCharacter.SONIC_ALONE);

        private static final java.util.Map<CanonicalAnimation, CanonicalAnimation> FALLBACKS =
                buildFallbacks();

        private static java.util.Map<CanonicalAnimation, CanonicalAnimation> buildFallbacks() {
            return DonorCapabilities.buildFallbackMap(
                    Sonic1AnimationIds.values(), Sonic1AnimationIds::toCanonical,
                    java.util.Map.ofEntries(
                            // Non-native animations -> nearest native fallback
                            java.util.Map.entry(CanonicalAnimation.SPINDASH,        CanonicalAnimation.DUCK),
                            java.util.Map.entry(CanonicalAnimation.SKID,            CanonicalAnimation.STOP),
                            java.util.Map.entry(CanonicalAnimation.SLIDE,           CanonicalAnimation.ROLL),
                            java.util.Map.entry(CanonicalAnimation.BLINK,           CanonicalAnimation.WAIT),
                            java.util.Map.entry(CanonicalAnimation.GET_UP,          CanonicalAnimation.WAIT),
                            java.util.Map.entry(CanonicalAnimation.VICTORY,         CanonicalAnimation.WAIT),
                            java.util.Map.entry(CanonicalAnimation.BLANK,           CanonicalAnimation.WAIT),
                            java.util.Map.entry(CanonicalAnimation.GLIDE_DROP,      CanonicalAnimation.SPRING),
                            java.util.Map.entry(CanonicalAnimation.GLIDE_LAND,      CanonicalAnimation.WAIT),
                            java.util.Map.entry(CanonicalAnimation.GLIDE_SLIDE,     CanonicalAnimation.PUSH),
                            java.util.Map.entry(CanonicalAnimation.HANG2,           CanonicalAnimation.HANG),
                            java.util.Map.entry(CanonicalAnimation.BALANCE2,        CanonicalAnimation.BALANCE),
                            java.util.Map.entry(CanonicalAnimation.BALANCE3,        CanonicalAnimation.BALANCE),
                            java.util.Map.entry(CanonicalAnimation.BALANCE4,        CanonicalAnimation.BALANCE),
                            java.util.Map.entry(CanonicalAnimation.FLY,             CanonicalAnimation.SPRING),
                            java.util.Map.entry(CanonicalAnimation.SUPER_TRANSFORM, CanonicalAnimation.WAIT),
                            java.util.Map.entry(CanonicalAnimation.BUBBLE,          CanonicalAnimation.GET_AIR),
                            java.util.Map.entry(CanonicalAnimation.HURT2,           CanonicalAnimation.HURT),
                            java.util.Map.entry(CanonicalAnimation.HURT_FALL,       CanonicalAnimation.HURT)
                    ));
        }

        @Override
        public java.util.Set<PlayerCharacter> getPlayableCharacters() { return CHARACTERS; }

        @Override
        public boolean hasSpindash() { return false; }

        @Override
        public boolean hasSuperTransform() { return false; }

        @Override
        public boolean hasHyperTransform() { return false; }

        @Override
        public boolean hasInstaShield() { return false; }

        @Override
        public boolean hasTailsFlight() { return false; }

        @Override
        public boolean hasElementalShields() { return false; }

        @Override
        public boolean hasSidekick() { return false; }

        @Override
        public java.util.Map<CanonicalAnimation, CanonicalAnimation> getAnimationFallbacks() {
            return FALLBACKS;
        }

        @Override
        public int resolveNativeId(CanonicalAnimation canonical) {
            return Sonic1AnimationIds.fromCanonical(canonical);
        }

        @Override
        public com.openggf.data.PlayerSpriteArtProvider getPlayerArtProvider(
                com.openggf.data.RomByteReader reader) {
            var art = new Sonic1PlayerArt(reader);
            return art::loadForCharacter;
        }
    }

    private static final class Sonic1CrossGameDonorProvider implements CrossGameDonorProvider {
        @Override
        public DonorCapabilities getDonorCapabilities() {
            return Sonic1DonorCapabilities.INSTANCE;
        }

        @Override
        public com.openggf.data.PlayerSpriteArtProvider createPlayerArtProvider(RomByteReader reader) {
            return Sonic1DonorCapabilities.INSTANCE.getPlayerArtProvider(reader);
        }

        @Override
        public GameAudioProfile getAudioProfile() {
            return new Sonic1AudioProfile();
        }

        @Override
        public Palette loadCharacterPalette(RomByteReader reader, String characterCode) {
            int paletteAddr = resolveSonicPaletteAddress(reader);
            byte[] data = reader.slice(paletteAddr, Palette.PALETTE_SIZE_IN_ROM);
            Palette palette = new Palette();
            palette.fromSegaFormat(data);
            return palette;
        }

        @Override
        public Palette loadUnderwaterCharacterPalette(RomByteReader reader, String characterCode) {
            byte[] data = reader.slice(Sonic1Constants.PAL_LZ_SONIC_UNDERWATER_ADDR,
                    Palette.PALETTE_SIZE_IN_ROM);
            Palette palette = new Palette();
            palette.fromSegaFormat(data);
            return palette;
        }

        private int resolveSonicPaletteAddress(RomByteReader reader) {
            // PalPointers differs between REV00 and REV01. Identify Pal_Sonic by
            // the same destination/size contract used by the native level loader
            // instead of treating the REV00 data address as revision-independent.
            for (int paletteId = 2; paletteId < 10; paletteId++) {
                int entryAddr = Sonic1Constants.PALETTE_TABLE_ADDR + paletteId * 8;
                int destination = reader.readU16BE(entryAddr + 4);
                int byteCount = (reader.readU16BE(entryAddr + 6) + 1) * 4;
                if (destination == 0xFB00 && byteCount == Palette.PALETTE_SIZE_IN_ROM) {
                    return reader.readU32BE(entryAddr);
                }
            }
            return Sonic1Constants.SONIC_PALETTE_ADDR;
        }
    }
}
