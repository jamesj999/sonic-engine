package com.openggf.game.sonic3k;

import com.openggf.audio.GameAudioProfile;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameOverFlowProvider;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.CanonicalAnimation;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.CrossGameDonorProvider;
import com.openggf.game.DonorCapabilities;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.DebugOverlayProvider;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.PhysicsProvider;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.LevelState;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.RespawnState;
import com.openggf.game.ScrollHandlerProvider;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.ZoneRegistry;
import com.openggf.game.CheckpointState;
import com.openggf.game.LevelGamestate;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.BonusStageProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.sonic3k.events.S3kSeamlessMutationExecutor;
import com.openggf.game.sonic3k.objects.AizIntroArtLoader;
import com.openggf.game.sonic3k.objects.AizIntroTerrainSwap;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.scroll.Sonic3kScrollHandlerProvider;
import com.openggf.game.sonic3k.sidekick.Sonic3kCnzCarryTrigger;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager;
import com.openggf.game.sonic3k.dataselect.S3kDataSelectManager;
import com.openggf.game.sonic3k.dataselect.S3kSaveSnapshotProvider;
import com.openggf.game.sonic3k.titlescreen.Sonic3kTitleScreenManager;
import com.openggf.game.DataSelectProvider;
import com.openggf.game.LevelSelectProvider;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.sonic3k.levelselect.Sonic3kLevelSelectManager;
import com.openggf.game.GameId;
import com.openggf.game.GameRng;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.LoadTimeProfile;
import com.openggf.game.timing.LoadTimeProfileFactory;
import com.openggf.game.timing.LoadTimeSimulationMode;
import com.openggf.game.timing.ProfiledLoadTimeManifest;
import com.openggf.game.OscillationManager;
import com.openggf.game.dataselect.DataSelectHostProfile;
import com.openggf.game.dataselect.DataSelectPresentationProvider;
import com.openggf.game.dataselect.DataSelectSessionController;
import com.openggf.level.LevelManager;
import com.openggf.level.InitialFixedSstDispatcher;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.PlaneSwitcherConfig;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCarryTrigger;
import com.openggf.sprites.playable.SuperStateController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GameModule for Sonic 3 &amp; Knuckles.
 *
 * <p>Provides audio, zone registry, scroll handlers, object/art providers,
 * data select, special/bonus-stage providers, and level initialization hooks.
 */
public class Sonic3kGameModule implements GameModule {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kGameModule.class.getName());

    static {
        // Register the S3K data-select renderer as a cross-game donor so S1/S2 modules
        // can request a donated presentation without naming the S3K-specific delegate.
        com.openggf.game.dataselect.CrossGameDataSelectPresentations.registerDonor(
                com.openggf.game.dataselect.CrossGameDataSelectPresentations.DONOR_S3K,
                S3kDataSelectManager::new);
    }

    private final GameAudioProfile audioProfile = new Sonic3kAudioProfile();
    /**
     * ROM {@code AIZ_vine_angle}, advanced by {@code ChangeRingFrame}
     * (sonic3k.asm:9693) and never cleared by a level or special-stage init --
     * both clears stop one word short of it (sonic3k.asm:7622, 10606). The
     * module is the session-lived owner; the {@code Sonic3k} Game it is handed
     * to is rebuilt on every level load (LevelManager.java:459), so owning it
     * there reset the swing phase of every AIZ giant ride vine on re-entry.
     */
    private final Sonic3kGlobalAnimationState globalAnimationState =
            new Sonic3kGlobalAnimationState();
    private final Sonic3kLevelEventManager levelEventManager = new Sonic3kLevelEventManager();
    private final Sonic3kTitleCardManager titleCardManager = new Sonic3kTitleCardManager();
    private final Sonic3kZoneRegistry zoneRegistry = new Sonic3kZoneRegistry();
    private final Sonic3kTitleScreenManager titleScreenProvider = new Sonic3kTitleScreenManager();
    private final Sonic3kLevelSelectManager levelSelectProvider = new Sonic3kLevelSelectManager();
    private final com.openggf.game.sonic3k.dataselect.S3kDataSelectProfile dataSelectHostProfile =
            new com.openggf.game.sonic3k.dataselect.S3kDataSelectProfile();
    private DataSelectPresentationProvider dataSelectPresentationProvider;
    private final com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageManager specialStageManager =
            new com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageManager();
    private final Sonic3kSpecialStageProvider specialStageProvider =
            new Sonic3kSpecialStageProvider(specialStageManager);
    private final LevelInitProfile levelInitProfile = new Sonic3kLevelInitProfile(levelEventManager);
    private final SidekickCarryTrigger sidekickCarryTrigger = new Sonic3kCnzCarryTrigger();
    private final CrossGameDonorProvider donorProvider = new Sonic3kCrossGameDonorProvider();
    private Sonic3kScrollHandlerProvider scrollHandlerProvider;
    private PhysicsProvider physicsProvider;
    private Sonic3kObjectArtProvider objectArtProvider;
    private ObjectRegistry objectRegistry;
    private final Sonic3kBonusStageCoordinator bonusStageCoordinator = new Sonic3kBonusStageCoordinator();

    @Override
    public String getIdentifier() {
        return "Sonic3k";
    }

    @Override
    public GameId getGameId() {
        return GameId.S3K;
    }

    @Override
    public Game createGame(Rom rom) {
        try {
            return new Sonic3k(rom, globalAnimationState);
        } catch (java.io.IOException e) {
            LOGGER.severe("Failed to create S3K game: " + e.getMessage());
            return null;
        }
    }

    @Override
    public RuntimeArtCoordinator createRuntimeArtCoordinator(
            HardwareTimingService timing) {
        return new S3kRuntimeArtCoordinator(timing);
    }

    /** Generator-owned measured manifest; never hand-edited. */
    static final String PROFILED_LOAD_TIME_MANIFEST = "/load-time-profiles/s3k-v1.json";
    /**
     * Hand-tuned normal-play manifest, seeded as a copy of
     * {@link #PROFILED_LOAD_TIME_MANIFEST} and extended with entries the
     * measurement stream does not cover (the title-screen Sonic frames).
     */
    static final String FAST_LOAD_TIME_MANIFEST = "/load-time-profiles/s3k-fast-v1.json";

    @Override
    public LoadTimeProfile createLoadTimeProfile(
            LoadTimeSimulationMode mode,
            Consumer<String> warningSink) {
        return switch (mode) {
            case NONE -> LoadTimeProfile.IMMEDIATE;
            case FAST -> LoadTimeProfileFactory.resolve(
                    mode, LoadTimeProfile.IMMEDIATE,
                    loadS3kProfile(FAST_LOAD_TIME_MANIFEST, warningSink), warningSink);
            case PROFILED, REALISTIC -> LoadTimeProfileFactory.resolve(
                    mode, loadS3kProfile(PROFILED_LOAD_TIME_MANIFEST, warningSink),
                    null, warningSink);
        };
    }

    private static LoadTimeProfile loadS3kProfile(
            String resourceName, Consumer<String> warningSink) {
        LoadTimeProfile manifest;
        try {
            var resource = Sonic3kGameModule.class.getResourceAsStream(resourceName);
            if (resource == null) {
                throw new IllegalStateException("missing S3K load-time manifest " + resourceName);
            }
            try (resource) {
                manifest = ProfiledLoadTimeManifest.load(resource, warningSink);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "failed to load S3K load-time manifest " + resourceName, exception);
        }
        return (submission, handle) -> {
            if (submission.kind()
                    == com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE
                    && "kosinski_moduled".equals(submission.compressionVariant())) {
                return new com.openggf.game.timing.LoadTimeDecision(
                        0,
                        Set.of(),
                        com.openggf.game.timing.LoadTimeDecisionSource.IMMEDIATE,
                        "s3k-kos-v1-composite-parent");
            }
            return manifest.assign(submission, handle);
        };
    }

    @Override
    public ObjectRegistry createObjectRegistry() {
        if (objectRegistry == null) {
            objectRegistry = new Sonic3kObjectRegistry();
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
                Sonic3kConstants.TOUCH_SIZES_ADDR,
                Sonic3kConstants.TOUCH_SIZES_COUNT);
    }

    @Override
    public int getPlaneSwitcherObjectId() {
        return Sonic3kObjectIds.PATH_SWAP;
    }

    @Override
    public PlaneSwitcherConfig getPlaneSwitcherConfig() {
        return new PlaneSwitcherConfig((byte) 0x0C, (byte) 0x0D, (byte) 0x0E, (byte) 0x0F);
    }

    @Override
    public int getCheckpointObjectId() {
        return Sonic3kObjectIds.STAR_POST;
    }

    @Override
    public LevelEventProvider getLevelEventProvider() {
        return levelEventManager;
    }

    @Override
    public InitialFixedSstDispatcher createInitialFixedSstDispatcher(
            SpriteManager sprites,
            ObjectManager objects,
            ZoneFeatureProvider zoneFeatures) {
        InitialWaveSplashSstOwner waveOwner =
                zoneFeatures instanceof Sonic3kZoneFeatureProvider provider
                        ? provider.initialWaveSplashSstOwner()
                        : new InitialWaveSplashSstOwner() {
                            @Override public boolean isRegistered() { return false; }
                            @Override public void processInitialWaveSplash(
                                    com.openggf.sprites.managers.ProcessSpritesEpoch epoch) { }
                        };
        return new S3kInitialFixedSstDispatcher(
                levelEventManager, sprites, objects, waveOwner);
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
    public ZoneRegistry getZoneRegistry() {
        return zoneRegistry;
    }

    @Override
    public GameRng.Flavour rngFlavour() {
        return GameRng.Flavour.S3K;
    }

    @Override
    public ScrollHandlerProvider getScrollHandlerProvider() {
        if (scrollHandlerProvider == null) {
            scrollHandlerProvider = new Sonic3kScrollHandlerProvider();
        }
        return scrollHandlerProvider;
    }

    private final GameOverFlowProvider gameOverFlowProvider = new Sonic3kGameOverFlowProvider();

    @Override
    public GameOverFlowProvider getGameOverFlowProvider() {
        return gameOverFlowProvider;
    }

    @Override
    public TitleCardProvider getTitleCardProvider() {
        return titleCardManager;
    }

    @Override
    public com.openggf.game.ContinueScreenProvider createContinueScreenProvider() {
        return new com.openggf.game.sonic3k.continuescreen.Sonic3kContinueScreenProvider(
                com.openggf.game.sonic3k.runtime.S3kRuntimeStates.resolvePlayerCharacter(
                        GameServices.zoneRuntimeRegistry(), GameServices.configuration()).ordinal(), false);
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
    public DataSelectProvider getDataSelectProvider() {
        return getDataSelectPresentationProvider();
    }

    @Override
    public DataSelectPresentationProvider getDataSelectPresentationProvider() {
        if (dataSelectPresentationProvider == null) {
            dataSelectPresentationProvider = new DataSelectPresentationProvider(S3kDataSelectManager::new,
                    new DataSelectSessionController(dataSelectHostProfile));
        }
        return dataSelectPresentationProvider;
    }

    @Override
    public DataSelectHostProfile getDataSelectHostProfile() {
        return dataSelectHostProfile;
    }

    @Override
    public com.openggf.game.save.SaveSnapshotProvider getSaveSnapshotProvider() {
        return new S3kSaveSnapshotProvider();
    }

    @Override
    public ZoneFeatureProvider getZoneFeatureProvider() {
        return new Sonic3kZoneFeatureProvider();
    }

    @Override
    public DebugOverlayProvider getDebugOverlayProvider() {
        return null;
    }

    @Override
    public ObjectArtProvider getObjectArtProvider() {
        if (objectArtProvider == null) {
            objectArtProvider = new Sonic3kObjectArtProvider();
        }
        return objectArtProvider;
    }

    @Override
    public PhysicsProvider getPhysicsProvider() {
        if (physicsProvider == null) {
            physicsProvider = new Sonic3kPhysicsProvider();
        }
        return physicsProvider;
    }

    @Override
    public WaterDataProvider getWaterDataProvider() {
        return new Sonic3kWaterDataProvider();
    }

    @Override
    public LevelInitProfile getLevelInitProfile() {
        return levelInitProfile;
    }

    @Override
    public boolean hasSeparateTailsTailArt() {
        return true;
    }

    @Override
    public SpriteArtSet loadTailsTailArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            Sonic3kPlayerArt s3kArt = new Sonic3kPlayerArt(RomByteReader.fromRom(rom));
            return s3kArt.loadTailsTail();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load S3K tails tail art", e);
            return SpriteArtSet.EMPTY;
        }
    }

    @Override
    public SuperStateController createSuperStateController(
            AbstractPlayableSprite player) {
        if (CrossGameFeatureProvider.isActive()) {
            return GameServices.crossGameFeatures().createSuperStateController(player);
        }
        return new Sonic3kSuperStateController(player);
    }

    @Override
    public SidekickCarryTrigger getSidekickCarryTrigger() {
        return sidekickCarryTrigger;
    }

    @Override
    public void applySeamlessMutation(LevelManager levelManager, String mutationKey) {
        S3kSeamlessMutationExecutor.apply(levelManager, mutationKey);
    }

    @Override
    public void resetModuleScopedState() {
        AizIntroArtLoader.reset();
        AizIntroTerrainSwap.reset();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getGameService(Class<T> type) {
        if (type == Sonic3kLevelEventManager.class) return (T) levelEventManager;
        if (type == Sonic3kTitleCardManager.class) return (T) titleCardManager;
        if (type == Sonic3kZoneRegistry.class) return (T) zoneRegistry;
        if (type == com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageManager.class)
            return (T) specialStageManager;
        return null;
    }

    @Override
    public void onLevelLoad() {
        // Reset oscillation values used by moving platforms, etc.
        OscillationManager.reset();
        // Reset button/trigger state for the new level
        Sonic3kLevelTriggerManager.reset();
    }

    @Override
    public SpecialStageProvider getSpecialStageProvider() {
        return specialStageProvider;
    }

    @Override
    public BonusStageProvider getBonusStageProvider() {
        return bonusStageCoordinator;
    }

    @Override
    public boolean supportsSidekick() {
        return true;
    }

    @Override
    public java.util.function.Function<com.openggf.game.PlayableEntity,
            com.openggf.level.objects.AbstractObjectInstance> getInvincibilityStarsFactory() {
        return com.openggf.game.sonic3k.objects.Sonic3kInvincibilityStarsObjectInstance::new;
    }

    @Override
    public java.util.function.BiFunction<AbstractPlayableSprite, com.openggf.game.ShieldType,
            com.openggf.level.objects.ShieldObjectInstance> getShieldFactory() {
        // Same objects whether S3K is the host or the cross-game donor.
        return donorProvider.getShieldFactory();
    }

    @Override
    public java.util.function.Function<AbstractPlayableSprite,
            com.openggf.level.objects.AbstractObjectInstance> getInstaShieldFactory() {
        return donorProvider.getInstaShieldFactory();
    }

    @Override
    public DonorCapabilities getDonorCapabilities() {
        return Sonic3kDonorCapabilities.INSTANCE;
    }

    @Override
    public CrossGameDonorProvider getCrossGameDonorProvider() {
        return donorProvider;
    }

    /** Lazily-constructed singleton holding S3K donation metadata. */
    private static final class Sonic3kDonorCapabilities implements DonorCapabilities {

        static final Sonic3kDonorCapabilities INSTANCE = new Sonic3kDonorCapabilities();

        private static final java.util.Set<PlayerCharacter> CHARACTERS =
                java.util.Set.of(
                        PlayerCharacter.SONIC_ALONE,
                        PlayerCharacter.SONIC_AND_TAILS,
                        PlayerCharacter.TAILS_ALONE,
                        PlayerCharacter.KNUCKLES);

        private static final java.util.Map<CanonicalAnimation, CanonicalAnimation> FALLBACKS =
                buildFallbacks();

        private static java.util.Map<CanonicalAnimation, CanonicalAnimation> buildFallbacks() {
            return DonorCapabilities.buildFallbackMap(
                    Sonic3kAnimationIds.values(), Sonic3kAnimationIds::toCanonical,
                    java.util.Map.ofEntries(
                            // S1-specific animations -> nearest S3K native fallback
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
                            java.util.Map.entry(CanonicalAnimation.GET_AIR,     CanonicalAnimation.BLANK),
                            java.util.Map.entry(CanonicalAnimation.BURNT,       CanonicalAnimation.HURT),
                            java.util.Map.entry(CanonicalAnimation.SHRINK,      CanonicalAnimation.DEATH),
                            java.util.Map.entry(CanonicalAnimation.WATER_SLIDE, CanonicalAnimation.HURT_FALL),
                            java.util.Map.entry(CanonicalAnimation.NULL_ANIM,   CanonicalAnimation.BLANK),
                            // S2-specific animations not in S3K -> nearest S3K native fallback
                            java.util.Map.entry(CanonicalAnimation.SLIDE,       CanonicalAnimation.HURT_FALL),
                            java.util.Map.entry(CanonicalAnimation.HURT2,       CanonicalAnimation.HURT)
                    ));
        }

        @Override
        public java.util.Set<PlayerCharacter> getPlayableCharacters() { return CHARACTERS; }

        @Override
        public boolean hasSpindash() { return true; }

        @Override
        public boolean hasSuperTransform() { return true; }

        @Override
        public boolean hasHyperTransform() { return true; }

        @Override
        public boolean hasInstaShield() { return true; }

        @Override
        public boolean hasTailsFlight() { return true; }

        @Override
        public boolean hasElementalShields() { return true; }

        @Override
        public boolean hasSidekick() { return true; }

        @Override
        public java.util.Map<CanonicalAnimation, CanonicalAnimation> getAnimationFallbacks() {
            return FALLBACKS;
        }

        @Override
        public int resolveNativeId(CanonicalAnimation canonical) {
            return Sonic3kAnimationIds.fromCanonical(canonical);
        }

        @Override
        public com.openggf.data.PlayerSpriteArtProvider getPlayerArtProvider(
                com.openggf.data.RomByteReader reader) {
            var art = new Sonic3kPlayerArt(reader);
            return art::loadForCharacter;
        }
    }

    private static final class Sonic3kCrossGameDonorProvider implements CrossGameDonorProvider {
        @Override
        public java.util.function.BiFunction<AbstractPlayableSprite, com.openggf.game.ShieldType,
                com.openggf.level.objects.ShieldObjectInstance> getShieldFactory() {
            return (player, type) -> switch (type) {
                case FIRE -> new com.openggf.game.sonic3k.objects.FireShieldObjectInstance(player);
                case LIGHTNING -> new com.openggf.game.sonic3k.objects.LightningShieldObjectInstance(player);
                case BUBBLE -> new com.openggf.game.sonic3k.objects.BubbleShieldObjectInstance(player);
                default -> new com.openggf.level.objects.ShieldObjectInstance(player);
            };
        }

        @Override
        public java.util.function.Function<AbstractPlayableSprite,
                com.openggf.level.objects.AbstractObjectInstance> getInstaShieldFactory() {
            return com.openggf.game.sonic3k.objects.InstaShieldObjectInstance::new;
        }

        @Override
        public DonorCapabilities getDonorCapabilities() {
            return Sonic3kDonorCapabilities.INSTANCE;
        }

        @Override
        public com.openggf.data.PlayerSpriteArtProvider createPlayerArtProvider(RomByteReader reader) {
            return Sonic3kDonorCapabilities.INSTANCE.getPlayerArtProvider(reader);
        }

        @Override
        public com.openggf.data.SpindashDustArtProvider createSpindashDustArtProvider(RomByteReader reader) {
            Sonic3kDustArt dustArt = new Sonic3kDustArt(reader);
            return dustArt::loadForCharacter;
        }

        @Override
        public GameAudioProfile getAudioProfile() {
            return new Sonic3kAudioProfile();
        }

        @Override
        public Palette loadCharacterPalette(RomByteReader reader, String characterCode) {
            int paletteAddr;
            int paletteSize = Palette.PALETTE_SIZE_IN_ROM;
            if ("knuckles".equalsIgnoreCase(characterCode)) {
                paletteAddr = Sonic3kConstants.KNUCKLES_PALETTE_ADDR;
                paletteSize = 32;
            } else {
                paletteAddr = Sonic3kConstants.SONIC_PALETTE_ADDR;
            }
            byte[] data = reader.slice(paletteAddr, paletteSize);
            Palette palette = new Palette();
            palette.fromSegaFormat(data);
            return palette;
        }

        @Override
        public Palette loadHostCompatiblePalette(RomByteReader reader, String characterCode) {
            if (!"knuckles".equalsIgnoreCase(characterCode)) {
                return null;
            }
            byte[] data = reader.slice(Sonic3kConstants.KNUCKLES_S2_PALETTE_ADDR,
                    Palette.PALETTE_SIZE_IN_ROM);
            Palette palette = new Palette();
            palette.fromSegaFormat(data);
            Palette.Color gold = palette.getColor(14);
            Palette.Color idx4 = palette.getColor(4);
            idx4.r = gold.r;
            idx4.g = gold.g;
            idx4.b = gold.b;
            return palette;
        }

        @Override
        public SuperStateController createSuperStateController(AbstractPlayableSprite player) {
            return new Sonic3kSuperStateController(player);
        }

        @Override
        public boolean hasSeparateTailsTailArt() {
            return true;
        }

        @Override
        public SpriteArtSet loadTailsTailArt(RomByteReader reader) throws IOException {
            return new Sonic3kPlayerArt(reader).loadTailsTail();
        }

        @Override
        public SpriteArtSet loadInstaShieldArt(RomByteReader reader) throws IOException {
            Pattern[] tiles = S3kSpriteDataLoader.loadArtTiles(reader,
                    Sonic3kConstants.ART_UNC_INSTA_SHIELD_ADDR,
                    Sonic3kConstants.ART_UNC_INSTA_SHIELD_SIZE);
            List<SpriteMappingFrame> mappings = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_INSTA_SHIELD_ADDR);
            List<SpriteDplcFrame> dplcs = S3kSpriteDataLoader.loadDplcFrames(
                    reader, Sonic3kConstants.DPLC_INSTA_SHIELD_ADDR);

            if (dplcs.size() > mappings.size()) {
                dplcs = new ArrayList<>(dplcs.subList(0, mappings.size()));
            }
            while (dplcs.size() < mappings.size()) {
                dplcs.add(new SpriteDplcFrame(List.of()));
            }

            int bankSize = S3kSpriteDataLoader.resolveBankSize(dplcs, mappings);
            SpriteAnimationSet animSet = S3kSpriteDataLoader.loadAnimationSet(reader,
                    Sonic3kConstants.ANI_INSTA_SHIELD_ADDR,
                    Sonic3kConstants.ANI_INSTA_SHIELD_COUNT);

            return new SpriteArtSet(tiles, mappings, dplcs,
                    0, Sonic3kConstants.ART_TILE_SHIELD, 1, bankSize, null, animSet);
        }
    }
}
