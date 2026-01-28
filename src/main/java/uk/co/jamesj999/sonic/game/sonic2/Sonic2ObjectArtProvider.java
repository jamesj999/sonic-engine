package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.game.GameServices;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.game.ObjectArtProvider;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.objects.ObjectArtData;
import uk.co.jamesj999.sonic.level.objects.ObjectArtKeys;
import uk.co.jamesj999.sonic.level.objects.ObjectSpriteSheet;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Sonic 2-specific implementation of ObjectArtProvider.
 * Wraps Sonic2ObjectArt and provides key-based lookups for renderers, sheets, and animations.
 * <p>
 * This provider lazily initializes the art loader when first needed, obtaining the ROM
 * from RomManager.
 */
public class Sonic2ObjectArtProvider implements ObjectArtProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic2ObjectArtProvider.class.getName());

    private Sonic2ObjectArt artLoader;
    private ObjectArtData artData;
    private int currentZoneIndex = -2; // Use -2 to distinguish from explicit -1

    private final Map<String, PatternSpriteRenderer> renderers = new HashMap<>();
    private final Map<String, ObjectSpriteSheet> sheets = new HashMap<>();
    private final Map<String, SpriteAnimationSet> animations = new HashMap<>();
    private final List<String> rendererKeys = new ArrayList<>();

    // For pattern caching in order
    private final List<ObjectSpriteSheet> sheetOrder = new ArrayList<>();
    private final List<PatternSpriteRenderer> rendererOrder = new ArrayList<>();

    // Results screen uses separate namespace
    private PatternSpriteRenderer resultsRenderer;
    private ObjectSpriteSheet resultsSheet;
    private Pattern[] hudDigitPatterns;
    private Pattern[] hudTextPatterns;
    private Pattern[] hudLivesPatterns;
    private Pattern[] hudLivesNumbers;

    /**
     * Creates a new Sonic2ObjectArtProvider.
     * The art loader is lazily initialized when loadArtForZone is first called.
     */
    public Sonic2ObjectArtProvider() {
        // Lazy initialization
    }

    /**
     * Creates a new Sonic2ObjectArtProvider with explicit ROM access.
     * Use this constructor when you have direct access to the ROM.
     */
    public Sonic2ObjectArtProvider(Rom rom, RomByteReader reader) {
        this.artLoader = new Sonic2ObjectArt(rom, reader);
    }

    private void ensureArtLoader() throws IOException {
        if (artLoader == null) {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) {
                throw new IllegalStateException("ROM not loaded");
            }
            artLoader = new Sonic2ObjectArt(rom, RomByteReader.fromRom(rom));
        }
    }

    @Override
    public void loadArtForZone(int zoneIndex) throws IOException {
        // Check if already loaded for this zone
        if (artData != null && zoneIndex == currentZoneIndex) {
            return;
        }

        ensureArtLoader();
        artData = artLoader.loadForZone(zoneIndex);
        currentZoneIndex = zoneIndex;

        // Clear previous registrations
        renderers.clear();
        sheets.clear();
        animations.clear();
        rendererKeys.clear();
        sheetOrder.clear();
        rendererOrder.clear();

        // Register all sheets and renderers
        registerSheet(ObjectArtKeys.MONITOR, artData.monitorSheet());
        registerSheet(ObjectArtKeys.SPIKE, artData.spikeSheet());
        registerSheet(ObjectArtKeys.SPIKE_SIDE, artData.spikeSideSheet());
        registerSheet(ObjectArtKeys.SPRING_VERTICAL, artData.springVerticalSheet());
        registerSheet(ObjectArtKeys.SPRING_HORIZONTAL, artData.springHorizontalSheet());
        registerSheet(ObjectArtKeys.SPRING_DIAGONAL, artData.springDiagonalSheet());
        registerSheet(ObjectArtKeys.SPRING_VERTICAL_RED, artData.springVerticalRedSheet());
        registerSheet(ObjectArtKeys.SPRING_HORIZONTAL_RED, artData.springHorizontalRedSheet());
        registerSheet(ObjectArtKeys.SPRING_DIAGONAL_RED, artData.springDiagonalRedSheet());
        registerSheet(ObjectArtKeys.EXPLOSION, artData.explosionSheet());
        registerSheet(ObjectArtKeys.SHIELD, artData.shieldSheet());
        registerSheet(ObjectArtKeys.INVINCIBILITY_STARS, artData.invincibilityStarsSheet());
        registerSheet(ObjectArtKeys.BRIDGE, artData.bridgeSheet());
        registerSheet(Sonic2ObjectArtKeys.WATERFALL, artData.waterfallSheet());
        registerSheet(ObjectArtKeys.CHECKPOINT, artData.checkpointSheet());
        registerSheet(ObjectArtKeys.CHECKPOINT_STAR, artData.checkpointStarSheet());

        // Badnik sheets (Sonic 2-specific) - loaded directly via artLoader
        registerSheet(Sonic2ObjectArtKeys.MASHER, artLoader.loadMasherSheet());
        registerSheet(Sonic2ObjectArtKeys.BUZZER, artLoader.loadBuzzerSheet());
        registerSheet(Sonic2ObjectArtKeys.COCONUTS, artLoader.loadCoconutsSheet());
        registerSheet(Sonic2ObjectArtKeys.SPINY, artLoader.loadSpinySheet());
        registerSheet(Sonic2ObjectArtKeys.GRABBER, artLoader.loadGrabberSheet());
        registerSheet(Sonic2ObjectArtKeys.GRABBER_STRING, artLoader.loadGrabberStringSheet());
        registerSheet(Sonic2ObjectArtKeys.CHOP_CHOP, artLoader.loadChopChopSheet());
        registerSheet(Sonic2ObjectArtKeys.WHISP, artLoader.loadWhispSheet());
        registerSheet(Sonic2ObjectArtKeys.GROUNDER, artLoader.loadGrounderSheet());
        registerSheet(Sonic2ObjectArtKeys.GROUNDER_ROCK, artLoader.loadGrounderRockSheet());
        registerSheet(Sonic2ObjectArtKeys.CRAWL, artLoader.loadCrawlSheet());
        registerSheet(Sonic2ObjectArtKeys.ARROW_SHOOTER, artLoader.loadArrowShooterSheet());
        registerSheet(ObjectArtKeys.ANIMAL, artData.animalSheet());
        registerSheet(ObjectArtKeys.POINTS, artData.pointsSheet());

        // Signpost
        registerSheet(ObjectArtKeys.SIGNPOST, artData.signpostSheet());

        // Egg Prison / Capsule (Object 0x3E)
        registerSheet(ObjectArtKeys.EGG_PRISON, artLoader.loadEggPrisonSheet());

        // CNZ objects (Sonic 2-specific)
        registerSheet(Sonic2ObjectArtKeys.BUMPER, artData.bumperSheet());
        registerSheet(Sonic2ObjectArtKeys.HEX_BUMPER, artData.hexBumperSheet());
        registerSheet(Sonic2ObjectArtKeys.BONUS_BLOCK, artData.bonusBlockSheet());
        registerSheet(Sonic2ObjectArtKeys.FLIPPER, artData.flipperSheet());
        registerSheet(Sonic2ObjectArtKeys.LAUNCHER_SPRING_VERT, artLoader.loadLauncherSpringVertSheet());
        registerSheet(Sonic2ObjectArtKeys.LAUNCHER_SPRING_DIAG, artLoader.loadLauncherSpringDiagSheet());
        registerSheet(Sonic2ObjectArtKeys.CNZ_RECT_BLOCKS, artLoader.loadCNZRectBlocksSheet());
        registerSheet(Sonic2ObjectArtKeys.CNZ_BIG_BLOCK, artLoader.loadCNZBigBlockSheet());
        registerSheet(Sonic2ObjectArtKeys.CNZ_ELEVATOR, artLoader.loadCNZElevatorSheet());
        registerSheet(Sonic2ObjectArtKeys.CNZ_CAGE, artLoader.loadCNZCageSheet());
        registerSheet(Sonic2ObjectArtKeys.CNZ_BONUS_SPIKE, artLoader.loadCNZBonusSpikeSheet());

        // CPZ objects (Sonic 2-specific)
        registerSheet(Sonic2ObjectArtKeys.SPEED_BOOSTER, artData.speedBoosterSheet());
        registerSheet(Sonic2ObjectArtKeys.BLUE_BALLS, artData.blueBallsSheet());
        registerSheet(Sonic2ObjectArtKeys.BREAKABLE_BLOCK, artData.breakableBlockSheet());
        registerSheet(Sonic2ObjectArtKeys.CPZ_PLATFORM, artData.cpzPlatformSheet());
        registerSheet(Sonic2ObjectArtKeys.CPZ_STAIR_BLOCK, artData.cpzStairBlockSheet());
        registerSheet(Sonic2ObjectArtKeys.SIDEWAYS_PFORM, artData.sidewaysPformSheet());
        registerSheet(Sonic2ObjectArtKeys.CPZ_PYLON, artData.cpzPylonSheet());
        registerSheet(Sonic2ObjectArtKeys.PIPE_EXIT_SPRING, artData.pipeExitSpringSheet());
        registerSheet(Sonic2ObjectArtKeys.TIPPING_FLOOR, artData.tippingFloorSheet());
        registerSheet(Sonic2ObjectArtKeys.BARRIER, artData.barrierSheet());
        registerSheet(Sonic2ObjectArtKeys.SPRINGBOARD, artData.springboardSheet());

        // Underwater bubbles
        registerSheet(Sonic2ObjectArtKeys.BUBBLES, artData.bubblesSheet());

        // ARZ leaves
        registerSheet(Sonic2ObjectArtKeys.LEAVES, artData.leavesSheet());

        // Collapsing Platform art (Object 0x1F) - zone-specific
        ObjectSpriteSheet oozCollapsingPlatformSheet = artLoader.loadOOZCollapsingPlatformSheet();
        if (oozCollapsingPlatformSheet != null) {
            registerSheet(Sonic2ObjectArtKeys.OOZ_COLLAPSING_PLATFORM, oozCollapsingPlatformSheet);
        }
        ObjectSpriteSheet mczCollapsingPlatformSheet = artLoader.loadMCZCollapsingPlatformSheet();
        if (mczCollapsingPlatformSheet != null) {
            registerSheet(Sonic2ObjectArtKeys.MCZ_COLLAPSING_PLATFORM, mczCollapsingPlatformSheet);
        }

        // Results screen - stored separately, not in sheetOrder
        resultsSheet = artData.resultsSheet();
        resultsRenderer = new PatternSpriteRenderer(resultsSheet);
        sheets.put(ObjectArtKeys.RESULTS, resultsSheet);
        renderers.put(ObjectArtKeys.RESULTS, resultsRenderer);
        rendererKeys.add(ObjectArtKeys.RESULTS);

        // Register animations (common)
        animations.put(ObjectArtKeys.ANIM_MONITOR, artData.monitorAnimations());
        animations.put(ObjectArtKeys.ANIM_SPRING, artData.springAnimations());
        animations.put(ObjectArtKeys.ANIM_CHECKPOINT, artData.checkpointAnimations());
        animations.put(ObjectArtKeys.ANIM_SIGNPOST, artData.signpostAnimations());
        // Register animations (Sonic 2-specific)
        animations.put(Sonic2ObjectArtKeys.ANIM_FLIPPER, artData.flipperAnimations());
        animations.put(Sonic2ObjectArtKeys.ANIM_PIPE_EXIT_SPRING, artData.pipeExitSpringAnimations());
        animations.put(Sonic2ObjectArtKeys.ANIM_TIPPING_FLOOR, artData.tippingFloorAnimations());
        animations.put(Sonic2ObjectArtKeys.ANIM_SPRINGBOARD, artData.springboardAnimations());

        // Store HUD patterns
        hudDigitPatterns = artData.getHudDigitPatterns();
        hudTextPatterns = artData.getHudTextPatterns();
        hudLivesPatterns = artData.getHudLivesPatterns();
        hudLivesNumbers = artData.getHudLivesNumbers();

        LOGGER.info("Sonic2ObjectArtProvider loaded for zone " + zoneIndex +
                " with " + rendererKeys.size() + " renderers");
    }

    private void registerSheet(String key, ObjectSpriteSheet sheet) {
        if (sheet == null) {
            return;
        }
        sheets.put(key, sheet);
        PatternSpriteRenderer renderer = new PatternSpriteRenderer(sheet);
        renderers.put(key, renderer);
        rendererKeys.add(key);
        sheetOrder.add(sheet);
        rendererOrder.add(renderer);
    }

    @Override
    public PatternSpriteRenderer getRenderer(String key) {
        return renderers.get(key);
    }

    @Override
    public ObjectSpriteSheet getSheet(String key) {
        return sheets.get(key);
    }

    @Override
    public SpriteAnimationSet getAnimations(String key) {
        return animations.get(key);
    }

    @Override
    public int getZoneData(String key, int zoneIndex) {
        if (artData == null) {
            return -1;
        }
        return switch (key) {
            case ObjectArtKeys.ANIMAL_TYPE_A -> artData.getAnimalTypeA();
            case ObjectArtKeys.ANIMAL_TYPE_B -> artData.getAnimalTypeB();
            default -> -1;
        };
    }

    @Override
    public Pattern[] getHudDigitPatterns() {
        return hudDigitPatterns;
    }

    @Override
    public Pattern[] getHudTextPatterns() {
        return hudTextPatterns;
    }

    @Override
    public Pattern[] getHudLivesPatterns() {
        return hudLivesPatterns;
    }

    @Override
    public Pattern[] getHudLivesNumbers() {
        return hudLivesNumbers;
    }

    @Override
    public List<String> getRendererKeys() {
        return new ArrayList<>(rendererKeys);
    }

    @Override
    public int ensurePatternsCached(GraphicsManager graphicsManager, int basePatternIndex) {
        int next = basePatternIndex;
        for (int i = 0; i < rendererOrder.size(); i++) {
            ObjectSpriteSheet sheet = sheetOrder.get(i);
            PatternSpriteRenderer renderer = rendererOrder.get(i);
            int count = sheet.getPatterns().length;
            renderer.ensurePatternsCached(graphicsManager, next);
            next += count;
        }

        // Results screen uses a dedicated pattern namespace starting at 0.
        // This ensures its tile indices (0-465) map directly to texture IDs,
        // avoiding issues with high basePatternIndex values.
        // We use a high offset (0x10000) to avoid collision with level/object patterns.
        if (resultsRenderer != null) {
            resultsRenderer.ensurePatternsCached(graphicsManager, 0x10000);
        }

        return next;
    }

    @Override
    public boolean isReady() {
        PatternSpriteRenderer monitorRenderer = renderers.get(ObjectArtKeys.MONITOR);
        PatternSpriteRenderer spikeRenderer = renderers.get(ObjectArtKeys.SPIKE);
        PatternSpriteRenderer springRenderer = renderers.get(ObjectArtKeys.SPRING_VERTICAL);
        return (monitorRenderer != null && monitorRenderer.isReady())
                || (spikeRenderer != null && spikeRenderer.isReady())
                || (springRenderer != null && springRenderer.isReady());
    }

    /**
     * Gets the underlying art data for direct access when needed.
     * Prefer using key-based lookups when possible.
     */
    public ObjectArtData getArtData() {
        return artData;
    }
}

