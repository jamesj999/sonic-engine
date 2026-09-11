package com.openggf.game;

import com.openggf.architecture.CompositionRoot;
import com.openggf.audio.AudioManager;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.GameMusic;
import com.openggf.audio.GameSound;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.data.RomManager;
import com.openggf.data.SpindashDustArtProvider;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.rules.CrossGameRuleComposer;
import com.openggf.game.rules.GameRules;
import com.openggf.graphics.RenderContext;
import com.openggf.level.Palette;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.AnimationTranslator;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SuperStateController;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Provides cross-game feature donation: loads player sprites, spindash dust,
 * and physics from a donor game (S2 or S3K) while the base game (e.g., S1)
 * handles levels, collision, objects, and audio.
 *
 * <p>Singleton. Activated via {@code CROSS_GAME_FEATURES_ENABLED} config key.
 * The donor ROM is opened as a secondary ROM (no module detection side-effect).
 */
@CompositionRoot
public class CrossGameFeatureProvider implements PlayerSpriteArtProvider, SpindashDustArtProvider {
    private static final Logger LOGGER = Logger.getLogger(CrossGameFeatureProvider.class.getName());
    private static final CanonicalAnimation[] REQUIRED_TAILS_FLIGHT_ANIMATIONS = {
            CanonicalAnimation.TAILS_FLY,
            CanonicalAnimation.TAILS_FLY_ASCEND,
            CanonicalAnimation.TAILS_FLY_CARRY,
            CanonicalAnimation.TAILS_FLY_CARRY_ASCEND,
            CanonicalAnimation.TAILS_FLY_TIRED,
            CanonicalAnimation.TAILS_SWIM,
            CanonicalAnimation.TAILS_SWIM_ASCEND,
            CanonicalAnimation.TAILS_SWIM_CARRY,
            CanonicalAnimation.TAILS_SWIM_TIRED
    };

    private static CrossGameFeatureProvider instance;

    private GameId donorGameId;
    private RomByteReader donorReader;
    private CrossGameDonorProvider donorProvider;
    private PlayerSpriteArtProvider donorPlayerArtProvider;
    private SpindashDustArtProvider donorDustArtProvider;
    private SmpsLoader donorSmpsLoader;
    private DacData donorDacData;
    private GameRules hybridRules;
    private RenderContext donorRenderContext;
    private PlayerSpriteRenderer instaShieldRenderer;
    private SpriteArtSet instaShieldArtSet;
    private DonorCapabilities donorCapabilities;
    private boolean active;
    private final RomManager romManager;
    private final SonicConfigurationService configService;

    private CrossGameFeatureProvider() {
        this(null, null);
    }

    CrossGameFeatureProvider(RomManager romManager, SonicConfigurationService configService) {
        this.romManager = romManager;
        this.configService = configService;
    }

    private RomManager romManager() {
        return romManager != null ? romManager : GameServices.rom();
    }

    private SonicConfigurationService configService() {
        return configService != null ? configService : GameServices.configuration();
    }

    public static synchronized CrossGameFeatureProvider getInstance() {
        if (instance == null) {
            instance = new CrossGameFeatureProvider();
        }
        return instance;
    }

    /**
     * Initializes the provider by opening the donor ROM and creating art loaders.
     *
     * @param donorGameId "s2" or "s3k"
     * @throws IOException if the donor ROM cannot be opened
     */
    public void initialize(String donorGameCode) throws IOException {
        this.donorGameId = GameId.fromCode(donorGameCode);

        // Same-game guard: disable donation when donor == host
        GameId hostId = resolveHostGameId();
        if (donorGameId == hostId) {
            LOGGER.info("Donor same as host (" + donorGameId.code() + "), donation disabled");
            active = false;
            return;
        }

        Rom donorRom = romManager().getSecondaryRom(donorGameId.code());
        this.donorReader = RomByteReader.fromRom(donorRom);
        GameModule donorModule = resolveDonorModule(donorRom, donorGameId);
        if (donorModule == null) {
            LOGGER.warning("Unable to resolve donor module for: " + donorGameId.code());
            active = false;
            return;
        }
        this.donorProvider = donorModule.getCrossGameDonorProvider();
        if (donorProvider == null) {
            LOGGER.warning("No donor provider for: " + donorGameId.code());
            active = false;
            return;
        }
        this.donorCapabilities = donorProvider.getDonorCapabilities();
        if (donorCapabilities == null) {
            LOGGER.warning("No donor capabilities for: " + donorGameId.code());
            active = false;
            return;
        }
        this.donorPlayerArtProvider = donorProvider.createPlayerArtProvider(donorReader);
        this.donorDustArtProvider = donorProvider.createSpindashDustArtProvider(donorReader);

        hybridRules = buildHybridRules(donorModule.getRules());

        // Create donor render context for palette isolation
        donorRenderContext = RenderContext.getOrCreateDonor(donorGameId);
        syncDonorRenderPalette(ActiveGameplayTeamResolver.resolveMainCharacterCode(configService()));

        initializeDonorAudio();
        loadInstaShieldArt();

        active = true;
        LOGGER.info("Cross-game feature provider initialized with donor: " + donorGameId.code());
    }

    /**
     * Returns true if the cross-game feature provider is initialized and active.
     */
    public static boolean isActive() {
        return instance != null && instance.active;
    }

    /**
     * Returns true if the cross-game feature provider is active and the donor
     * game is Sonic 3&amp;K. Used to gate features that require S3K donation
     * specifically (e.g., donated data select presentation).
     */
    public static boolean isS3kDonorActive() {
        return isActive() && instance.donorGameId == GameId.S3K;
    }

    @Override
    public SpriteArtSet loadPlayerSpriteArt(String characterCode) throws IOException {
        syncDonorRenderPalette(characterCode);
        if (donorCapabilities == null) {
            return null;
        }
        if (donorPlayerArtProvider == null) {
            return null;
        }
        SpriteArtSet donorArt = donorPlayerArtProvider.loadPlayerSpriteArt(characterCode);
        if (donorArt == null) {
            return donorArt;
        }
        if (characterCode != null && "tails".equalsIgnoreCase(characterCode.trim())
                && donorCapabilities.hasTailsFlight()) {
            validateTailsFlightArtContract(characterCode.trim(), donorArt);
        }
        if (donorArt.animationProfile() == null) {
            return donorArt;
        }
        // Translate the animation profile for host compatibility
        if (donorArt.animationProfile() instanceof ScriptedVelocityAnimationProfile donorProfile) {
            ScriptedVelocityAnimationProfile translated = AnimationTranslator.translate(
                    donorCapabilities, donorProfile, donorArt.animationSet());
            return new SpriteArtSet(donorArt.artTiles(), donorArt.mappingFrames(),
                    donorArt.dplcFrames(), donorArt.paletteIndex(), donorArt.basePatternIndex(),
                    donorArt.frameDelay(), donorArt.bankSize(), translated, donorArt.animationSet());
        }
        return donorArt;
    }

    private void validateTailsFlightArtContract(String characterCode, SpriteArtSet donorArt) {
        for (CanonicalAnimation required : REQUIRED_TAILS_FLIGHT_ANIMATIONS) {
            int nativeId = donorCapabilities.resolveNativeId(required);
            if (nativeId < 0 || donorArt.animationSet() == null
                    || donorArt.animationSet().getScript(nativeId) == null) {
                String source = donorGameId != null ? donorGameId.code() : "unknown";
                String nativeIdText = nativeId >= 0
                        ? "0x" + Integer.toHexString(nativeId)
                        : Integer.toString(nativeId);
                throw new IllegalStateException("Donor " + source
                        + " character " + characterCode
                        + " is missing required animation " + required
                        + " at native ID " + nativeIdText);
            }
        }
    }

    @Override
    public SpriteArtSet loadSpindashDustArt(String characterCode) throws IOException {
        return donorDustArtProvider == null ? null : donorDustArtProvider.loadSpindashDustArt(characterCode);
    }

    /**
     * Returns typed hybrid game rules: host runtime behavior with explicitly
     * donated player capabilities.
     */
    public GameRules getHybridRules() {
        return hybridRules;
    }

    /**
     * Returns true if the donor game natively includes a sidekick character (e.g., Tails).
     */
    public boolean supportsSidekick() {
        return donorCapabilities != null && donorCapabilities.hasSidekick();
    }

    /**
     * Loads the character palette (palette line 0) from the donor ROM.
     * This provides the correct Sonic/Tails colors for donor sprites
     * without interfering with the base game's level palettes (lines 1-3).
     *
     * @return the donor's character palette, or null if unavailable
     */
    public Palette loadCharacterPalette() {
        return loadCharacterPalette(null);
    }

    /**
     * Loads the character palette from the donor ROM.
     * Knuckles uses a separate palette (Pal_Knuckles); Sonic/Tails share one.
     *
     * @param characterCode the character code ("sonic", "tails", "knuckles"), or null for default
     * @return the donor's character palette, or null if unavailable
     */
    @Override
    public Palette loadCharacterPalette(String characterCode) {
        if (donorProvider == null || donorReader == null) {
            return null;
        }
        return donorProvider.loadCharacterPalette(donorReader, characterCode);
    }

    /**
     * Loads the donor's native underwater character palette when available.
     */
    public Palette loadUnderwaterCharacterPalette(String characterCode) {
        if (donorProvider == null || donorReader == null) {
            return null;
        }
        return donorProvider.loadUnderwaterCharacterPalette(donorReader, characterCode);
    }

    /**
     * Returns a palette compatible with the HOST game's palette line 0 layout,
     * but with the donor character's colors. For Knuckles donated from S3K into S2,
     * this returns the S2-compatible Knuckles palette (0x060BEA) which has Knuckles'
     * reds at indices 2-5 but keeps S2's universal colors at indices 6-15.
     *
     * @param characterCode character code
     * @return host-compatible palette, or null if not applicable
     */
    public Palette loadHostCompatiblePalette(String characterCode) {
        if (donorProvider == null || donorReader == null) {
            return null;
        }
        return donorProvider.loadHostCompatiblePalette(donorReader, characterCode);
    }

    public RenderContext getDonorRenderContext() {
        return donorRenderContext;
    }

    private void syncDonorRenderPalette(String characterCode) {
        if (donorRenderContext == null) {
            return;
        }
        Palette charPalette = loadCharacterPalette(characterCode);
        if (charPalette != null) {
            donorRenderContext.setPalette(0, charPalette);
        }
        donorRenderContext.setUnderwaterPalette(0, loadUnderwaterCharacterPalette(characterCode));
    }

    /**
     * Initializes donor audio: creates a donor SmpsLoader and DacData from
     * the donor ROM, then registers all donor sounds with AudioManager.
     * Base game's sound map always takes priority at playback time, so
     * shared sounds (JUMP, RING) still use the base game's versions.
     */
    private void initializeDonorAudio() {
        GameAudioProfile donorProfile;
        donorProfile = donorProvider.getAudioProfile();
        if (donorProfile == null) {
            LOGGER.warning("No donor audio profile for: " + donorGameId.code());
            return;
        }

        try {
            Rom donorRom = romManager().getSecondaryRom(donorGameId.code());
            donorSmpsLoader = donorProfile.createSmpsLoader(donorRom);
            donorDacData = donorSmpsLoader.loadDacData();

            AudioManager am = GameServices.audio();
            am.registerDonorLoader(donorGameId.code(), donorSmpsLoader, donorDacData,
                    donorProfile.getSequencerConfig(), donorProfile);
            Map<GameMusic, Integer> donorMusic = donorProfile.getMusicMap();
            am.registerDonorMusicMap(donorGameId.code(), donorMusic);

            Map<GameSound, Integer> donorSounds = donorProfile.getSoundMap();
            for (Map.Entry<GameSound, Integer> entry : donorSounds.entrySet()) {
                am.registerDonorSound(entry.getKey(), donorGameId.code(), entry.getValue());
            }

            LOGGER.info("Donor audio initialized from " + donorGameId.code()
                    + " (" + donorSounds.size() + " sounds, " + donorMusic.size() + " music cues registered)");
        } catch (IOException e) {
            LOGGER.warning("Failed to initialize donor audio from " + donorGameId.code()
                    + ": " + e.getMessage());
        }
    }

    public String getDonorGameId() {
        return donorGameId == null ? null : donorGameId.code();
    }

    /**
     * Returns true if the donor game uses separate art for Tails' tail appendage (Obj05).
     * S3K has separate Map_Tails_Tail / DPLC_Tails_Tail tables; S2 reuses the main body art.
     */
    public boolean hasSeparateTailsTailArt() {
        return donorProvider != null && donorProvider.hasSeparateTailsTailArt();
    }

    /**
     * Loads the separate tail appendage art set from the donor game.
     * Only valid when {@link #hasSeparateTailsTailArt()} returns true.
     */
    public SpriteArtSet loadTailsTailArt() throws IOException {
        if (donorProvider == null || donorReader == null) {
            return SpriteArtSet.EMPTY;
        }
        return donorProvider.loadTailsTailArt(donorReader);
    }

    /**
     * Creates a Super Sonic state controller using the donor game's implementation
     * and pre-loads ROM data from the donor ROM.
     *
     * @param player the player sprite to attach the controller to
     * @return a donor-game SuperStateController with ROM data pre-loaded, or null
     */
    /**
     * Returns the donor game's shield object factory, or {@code null} when no
     * donor is active or the donor contributes no shield variants. Callers fall
     * back to the host {@link GameModule#getShieldFactory()}.
     */
    public java.util.function.BiFunction<AbstractPlayableSprite, ShieldType,
            com.openggf.level.objects.ShieldObjectInstance> getDonorShieldFactory() {
        if (!active || donorProvider == null) {
            return null;
        }
        return donorProvider.getShieldFactory();
    }

    /**
     * Returns the donor game's insta-shield object factory, or {@code null}
     * when no donor is active or the donor has no insta-shield object.
     */
    public java.util.function.Function<AbstractPlayableSprite,
            com.openggf.level.objects.AbstractObjectInstance> getDonorInstaShieldFactory() {
        if (!active || donorProvider == null) {
            return null;
        }
        return donorProvider.getInstaShieldFactory();
    }

    public SuperStateController createSuperStateController(AbstractPlayableSprite player) {
        if (!active || donorReader == null || donorCapabilities == null || donorProvider == null) {
            return null;
        }
        if (!donorCapabilities.hasSuperTransform()) {
            return null;  // S1 donor: no super transformation
        }
        SuperStateController ctrl = donorProvider.createSuperStateController(player);
        if (ctrl == null) {
            return null;
        }
        try {
            ctrl.loadRomData(donorReader);
            ctrl.setRomDataPreLoaded(true);
            LOGGER.fine("Created cross-game Super Sonic controller from donor: " + donorGameId.code());
        } catch (Exception e) {
            LOGGER.warning("Failed to load donor Super ROM data: " + e.getMessage());
            return null;
        }
        return ctrl;
    }

    public void resetState() {
        close();
    }

    public void close() {
        donorGameId = null;
        donorReader = null;
        donorProvider = null;
        donorPlayerArtProvider = null;
        donorDustArtProvider = null;
        donorSmpsLoader = null;
        donorDacData = null;
        hybridRules = null;
        donorRenderContext = null;
        instaShieldRenderer = null;
        instaShieldArtSet = null;
        donorCapabilities = null;
        active = false;
    }

    private GameId resolveHostGameId() {
        if (GameServices.hasRuntime()) {
            return GameServices.module().getGameId();
        }
        return GameServices.currentOrBootstrapGameModule().getGameId();
    }

    /**
     * Loads insta-shield art tiles, mappings, DPLCs, and animations from the S3K donor ROM.
     * Only runs when the donor is S3K; silently skips for S2 donors.
     */
    private void loadInstaShieldArt() {
        if (donorProvider == null || donorReader == null) {
            return;
        }
        try {
            instaShieldArtSet = donorProvider.loadInstaShieldArt(donorReader);
            if (instaShieldArtSet == null || instaShieldArtSet.isEmpty()) {
                return;
            }
            instaShieldRenderer = new PlayerSpriteRenderer(instaShieldArtSet);
            instaShieldRenderer.setRenderContext(donorRenderContext);

            LOGGER.info("Loaded donor insta-shield art: " + instaShieldArtSet.artTiles().length + " tiles, "
                    + instaShieldArtSet.mappingFrames().size() + " mapping frames");
        } catch (IOException e) {
            LOGGER.warning("Failed to load donor insta-shield art: " + e.getMessage());
        }
    }

    public PlayerSpriteRenderer getInstaShieldRenderer() {
        return instaShieldRenderer;
    }

    public SpriteArtSet getInstaShieldArtSet() {
        return instaShieldArtSet;
    }

    private GameRules buildHybridRules(GameRules donorRules) {
        GameRules baseRules = GameServices.module().getRules();

        return CrossGameRuleComposer.compose(baseRules, donorRules, donorCapabilities);
    }

    private GameModule resolveDonorModule(Rom donorRom, GameId expectedGameId) {
        return GameServices.romDetection()
                .detectAndCreateModule(donorRom)
                .filter(module -> module.getGameId() == expectedGameId)
                .orElse(null);
    }
}
