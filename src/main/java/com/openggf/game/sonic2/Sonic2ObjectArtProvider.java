package com.openggf.game.sonic2;

import com.openggf.game.GameServices;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Level;
import com.openggf.level.Pattern;
import com.openggf.level.objects.HudStaticArt;
import com.openggf.level.objects.ObjectArtData;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.art.ObjectArtBundle;
import com.openggf.level.objects.art.ObjectArtRegistration;
import com.openggf.level.render.PatternSpriteRenderer;

import com.openggf.sprites.animation.SpriteAnimationSet;

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
public class Sonic2ObjectArtProvider implements ObjectArtProvider,
        com.openggf.game.rewind.RewindSnapshottable<com.openggf.game.rewind.snapshot.PlcProgressSnapshot> {
    private static final Logger LOGGER = Logger.getLogger(Sonic2ObjectArtProvider.class.getName());

    @FunctionalInterface
    private interface ZoneSheetBuilder {
        ObjectSpriteSheet build(Sonic2ObjectArt artLoader, int zoneIndex);
    }

    private record SheetRegistration(ObjectArtRegistration metadata, ZoneSheetBuilder builder) {}

    private static final List<SheetRegistration> EAGER_SHEET_REGISTRATIONS = List.of(
            new SheetRegistration(
                    ObjectArtRegistration.sheet(Sonic2ObjectArtKeys.BREAKABLE_BLOCK),
                    Sonic2ObjectArt::loadBreakableBlockSheet),
            new SheetRegistration(
                    ObjectArtRegistration.sheet(Sonic2ObjectArtKeys.CPZ_PLATFORM),
                    Sonic2ObjectArt::loadGenericPlatformBSheet),
            new SheetRegistration(
                    ObjectArtRegistration.sheet(Sonic2ObjectArtKeys.CPZ_STAIR_BLOCK),
                    (artLoader, zoneIndex) -> artLoader.loadCpzStairBlockSheet()),
            new SheetRegistration(
                    ObjectArtRegistration.sheet(Sonic2ObjectArtKeys.SIDEWAYS_PFORM),
                    (artLoader, zoneIndex) -> artLoader.loadSidewaysPformSheet()));

    private Sonic2ObjectArt artLoader;
    private ObjectArtData artData;
    private int currentZoneIndex = -2; // Use -2 to distinguish from explicit -1
    private int loadEpoch = 0;

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
    private Pattern[] hudHexDigits;
    private HudStaticArt hudStaticArt;
    private boolean livesNameUsesIconPalette;

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
        if (artData != null && zoneIndex == currentZoneIndex) {
            return;
        }

        ensureArtLoader();
        artData = artLoader.loadForZone(zoneIndex);
        currentZoneIndex = zoneIndex;
        loadEpoch++;

        // Clear previous registrations
        renderers.clear();
        sheets.clear();
        animations.clear();
        rendererKeys.clear();
        sheetOrder.clear();
        rendererOrder.clear();

        // === Manual art (not in PLCs or needs complex construction) ===
        registerSheet(ObjectArtKeys.MONITOR, artData.monitorSheet());
        registerSheet(Sonic2ObjectArtKeys.WATERFALL, artData.waterfallSheet());
        registerSheet(ObjectArtKeys.ANIMAL, artData.animalSheet());
        registerEagerSheets(zoneIndex);
        // Red spring variants (share base spring art, different mappings — not PLC entries)
        registerSheet(ObjectArtKeys.SPRING_VERTICAL_RED, artData.springVerticalRedSheet());
        registerSheet(ObjectArtKeys.SPRING_HORIZONTAL_RED, artData.springHorizontalRedSheet());
        registerSheet(ObjectArtKeys.SPRING_DIAGONAL_RED, artData.springDiagonalRedSheet());
        // Checkpoint star (same art as checkpoint, different mappings)
        registerSheet(ObjectArtKeys.CHECKPOINT_STAR, artData.checkpointStarSheet());
        // Explosion, boss explosion, and Super Sonic stars were historically loaded
        // manually. The title-card teardown also requests PLCStdWtr, which carries
        // the small breathing-bubble art used by Obj0A.
        registerSheet(ObjectArtKeys.EXPLOSION, artLoader.loadExplosionSheet());
        registerSheet(Sonic2ObjectArtKeys.BOSS_EXPLOSION, artLoader.loadBossExplosionSheet());
        registerSheet(Sonic2ObjectArtKeys.SUPER_SONIC_STARS, artLoader.loadSuperSonicStarsSheet());
        // Signpost (PLC 39) and EggPrison (PLC 64) are loaded on-demand in the ROM
        // (at end-of-level and after boss defeat), but the engine loads them at zone init.
        registerSheet(ObjectArtKeys.SIGNPOST, artLoader.loadSignpostSheet());
        registerSheet(ObjectArtKeys.EGG_PRISON, artLoader.loadEggPrisonSheet());

        // === PLC-driven art loading ===
        Rom rom = GameServices.rom().getRom();
        int[] plcIds = Sonic2PlcLoader.getZonePlcIds(rom, zoneIndex);
        loadPlcEntries(rom, Sonic2Constants.PLC_STD1);
        loadPlcEntries(rom, Sonic2Constants.PLC_STD2);
        loadPlcEntries(rom, Sonic2Constants.PLC_STD_WATER);
        loadPlcEntries(rom, plcIds[0]);  // Zone primary PLC
        loadPlcEntries(rom, plcIds[1]);  // Zone secondary PLC

        // === Art derivatives (same ROM art as PLC parent, different mappings) ===
        // Only load when the parent art was loaded by PLCs for this zone.
        if (sheets.containsKey(Sonic2ObjectArtKeys.GRABBER)) {
            registerSheet(Sonic2ObjectArtKeys.GRABBER_STRING, artLoader.loadGrabberStringSheet());
        }
        if (sheets.containsKey(Sonic2ObjectArtKeys.GROUNDER)) {
            registerSheet(Sonic2ObjectArtKeys.GROUNDER_ROCK, artLoader.loadGrounderRockSheet());
        }
        if (sheets.containsKey(Sonic2ObjectArtKeys.OOZ_FAN_HORIZ)) {
            registerSheet(Sonic2ObjectArtKeys.OOZ_FAN_VERT, artLoader.loadOOZFanVertSheet());
        }
        if (sheets.containsKey(Sonic2ObjectArtKeys.SEESAW)) {
            registerSheet(Sonic2ObjectArtKeys.SEESAW_BALL, artLoader.loadSeesawBallSheet());
        }
        if (sheets.containsKey(Sonic2ObjectArtKeys.MCZ_DRAWBRIDGE)) {
            registerSheet(Sonic2ObjectArtKeys.MCZ_BRIDGE, artLoader.loadMCZBridgeSheet());
        }
        if (sheets.containsKey(Sonic2ObjectArtKeys.MTZ_FLOOR_SPIKE)) {
            registerSheet(Sonic2ObjectArtKeys.MTZ_SPIKE, artLoader.loadMTZSpikeSheet());
        }
        if (sheets.containsKey(Sonic2ObjectArtKeys.WFZ_HOOK)) {
            registerSheet(Sonic2ObjectArtKeys.WFZ_UNKNOWN, artLoader.loadWfzUnknownSheet());
        }

        // === Zone-specific overrides ===
        // HTZ barrier uses zone-specific art instead of CPZ ConstructionStripes
        if (zoneIndex == Sonic2ZoneConstants.ROM_ZONE_HTZ) {
            ObjectSpriteSheet htzBarrierSheet = artLoader.loadHTZBarrierSheet();
            if (htzBarrierSheet != null) {
                registerSheet(Sonic2ObjectArtKeys.BARRIER, htzBarrierSheet);
            }
            // Ground fire (Obj20 routine $A) uses different art/mappings than fire source
            registerSheet(Sonic2ObjectArtKeys.GROUND_FIRE, artLoader.loadGroundFireSheet());
            // Lava bubble / fire shooter (Obj20) - ensure explicitly loaded for HTZ
            // even if PLC timing hasn't triggered yet
            registerIfAbsent(Sonic2ObjectArtKeys.LAVA_BUBBLE, artLoader::loadLavaBubbleSheet);
        }

        // === Boss art (zone-conditional, Phase 3 would use boss PLCs) ===
        loadBossArt(zoneIndex);

        // === GAME OVER / TIME OVER card (Obj39) ===
        registerSheet(ObjectArtKeys.GAME_OVER, artLoader.loadGameOverSheet());

        // === Results screen (separate namespace) ===
        resultsSheet = artData.resultsSheet();
        resultsRenderer = new PatternSpriteRenderer(resultsSheet);
        sheets.put(ObjectArtKeys.RESULTS, resultsSheet);
        renderers.put(ObjectArtKeys.RESULTS, resultsRenderer);
        rendererKeys.add(ObjectArtKeys.RESULTS);

        // === Animations ===
        animations.put(ObjectArtKeys.ANIM_MONITOR, artData.monitorAnimations());
        animations.put(ObjectArtKeys.ANIM_SPRING, artData.springAnimations());
        animations.put(ObjectArtKeys.ANIM_CHECKPOINT, artData.checkpointAnimations());
        animations.put(ObjectArtKeys.ANIM_SIGNPOST, artData.signpostAnimations());
        animations.put(Sonic2ObjectArtKeys.ANIM_FLIPPER, artData.flipperAnimations());
        animations.put(Sonic2ObjectArtKeys.ANIM_PIPE_EXIT_SPRING, artData.pipeExitSpringAnimations());
        animations.put(Sonic2ObjectArtKeys.ANIM_TIPPING_FLOOR, artData.tippingFloorAnimations());
        animations.put(Sonic2ObjectArtKeys.ANIM_SPRINGBOARD, artData.springboardAnimations());

        // === HUD patterns ===
        hudDigitPatterns = artData.getHudDigitPatterns();
        hudTextPatterns = artData.getHudTextPatterns();
        hudLivesPatterns = artData.getHudLivesPatterns();
        hudLivesNumbers = artData.getHudLivesNumbers();
        hudHexDigits = artData.getDebugFontPatterns();
        livesNameUsesIconPalette = false;

        // Cross-game: override lives icon with donor character art (e.g., Knuckles from S3K)
        overrideLivesArtFromDonor();
        rebuildHudStaticArt();

        // The 1-up monitor icon shares VRAM with the life counter, so it must show
        // the same main-character face the HUD does (Tails-alone / Knuckles lock-on).
        overrideMonitorIconArtForMainCharacter();

        LOGGER.info("Sonic2ObjectArtProvider loaded for zone " + zoneIndex +
                " with " + rendererKeys.size() + " renderers (PLC-driven)");
    }

    /**
     * Loads art entries from a PLC definition, dispatching through the art registry.
     * Skips entries whose art key is already registered (prevents double-loading).
     */
    private void loadPlcEntries(Rom rom, int plcId) throws IOException {
        var plc = Sonic2PlcLoader.parsePlc(rom, plcId);
        for (var entry : plc.entries()) {
            var registration = Sonic2PlcArtRegistry.lookup(entry.romAddr());
            if (registration != null && !sheets.containsKey(registration.key())) {
                ObjectSpriteSheet sheet = registration.builder().build(artLoader);
                registerSheet(registration.key(), sheet);
            }
        }
    }

    private void registerEagerSheets(int zoneIndex) {
        for (SheetRegistration registration : EAGER_SHEET_REGISTRATIONS) {
            ObjectSpriteSheet sheet = registration.builder().build(artLoader, zoneIndex);
            registerSheet(registration.metadata().key(), sheet);
        }
    }

    /**
     * Loads a Sonic 2 PLC on demand, matching runtime event-triggered PLC requests.
     * Re-requesting an already loaded PLC is harmless because individual art keys
     * are skipped when present.
     *
     * @return {@code true} when the request registered at least one new sprite sheet
     */
    public boolean requestPlc(int plcId) throws IOException {
        return publishPreparedPlc(preparePlc(plcId));
    }

    /** Builds a runtime PLC's absent sheets without changing renderer registration. */
    public PreparedPlc preparePlc(int plcId) throws IOException {
        return preparePlcs(plcId);
    }

    /** Builds every absent sheet for an ordered PLC batch without registration. */
    public PreparedPlc preparePlcs(int... plcIds) throws IOException {
        ensureArtLoader();
        Rom rom = GameServices.rom().getRom();
        List<PreparedSheet> preparedSheets = new ArrayList<>();
        java.util.Set<String> preparedKeys = new java.util.HashSet<>();
        for (int plcId : plcIds) {
            var plc = Sonic2PlcLoader.parsePlc(rom, plcId);
            for (var entry : plc.entries()) {
                var registration = Sonic2PlcArtRegistry.lookup(entry.romAddr());
                if (registration != null && !sheets.containsKey(registration.key())
                        && preparedKeys.add(registration.key())) {
                    ObjectSpriteSheet sheet = registration.builder().build(artLoader);
                    if (sheet != null) {
                        preparedSheets.add(new PreparedSheet(registration.key(), sheet));
                    }
                }
            }
        }
        return new PreparedPlc(List.copyOf(preparedSheets));
    }

    /** Validates that committing a prepared PLC remains inside the object atlas range. */
    public void preflightPreparedPlc(PreparedPlc prepared) {
        long prospectiveCount = getRegularPatternCount();
        for (PreparedSheet sheet : prepared.sheets()) {
            prospectiveCount += sheet.sheet().getPatterns().length;
        }
        if (prospectiveCount > PatternAtlasRange.OBJECTS.size()) {
            throw new IllegalStateException("Object patterns exceed reserved atlas range: " + prospectiveCount);
        }
    }

    /** Publishes already-built sheets. Registration does not perform ROM or GPU I/O. */
    public boolean publishPreparedPlc(PreparedPlc prepared) {
        int sheetCountBefore = sheetOrder.size();
        for (PreparedSheet preparedSheet : prepared.sheets()) {
            if (!sheets.containsKey(preparedSheet.key())) {
                registerSheet(preparedSheet.key(), preparedSheet.sheet());
            }
        }
        loadEpoch++;
        return sheetOrder.size() > sheetCountBefore;
    }

    public record PreparedPlc(List<PreparedSheet> sheets) {
    }

    public record PreparedSheet(String key, ObjectSpriteSheet sheet) {
    }

    /**
     * Registers a sheet if the key is not already present.
     * Used for boss art that may already have been loaded by PLCs.
     */
    private void registerIfAbsent(String key, java.util.function.Supplier<ObjectSpriteSheet> supplier) {
        if (!sheets.containsKey(key)) {
            ObjectSpriteSheet sheet = supplier.get();
            if (sheet != null) {
                registerSheet(key, sheet);
            }
        }
    }

    /**
     * Loads boss art for the given zone. Boss art is currently zone-conditional;
     * a future Phase 3 could load this via boss PLCs instead.
     */
    private void loadBossArt(int zoneIndex) {
        switch (zoneIndex) {
            case 0x00: // ROM_ZONE_EHZ
                registerIfAbsent(Sonic2ObjectArtKeys.EHZ_BOSS, artLoader::loadEHZBossSheet);
                break;
            case 0x0D: // ROM_ZONE_CPZ
                registerIfAbsent(Sonic2ObjectArtKeys.CPZ_BOSS_EGGPOD, artLoader::loadCPZBossEggpodSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.CPZ_BOSS_PARTS, artLoader::loadCPZBossPartsSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.CPZ_BOSS_JETS, artLoader::loadCPZBossJetsSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.CPZ_BOSS_SMOKE, artLoader::loadCPZBossSmokeSheet);
                break;
            case 0x0F: // ROM_ZONE_ARZ
                registerIfAbsent(Sonic2ObjectArtKeys.ARZ_BOSS_MAIN, artLoader::loadARZBossMainSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.ARZ_BOSS_PARTS, artLoader::loadARZBossPartsSheet);
                break;
            case 0x0C: // ROM_ZONE_CNZ
                registerIfAbsent(Sonic2ObjectArtKeys.CNZ_BOSS, artLoader::loadCNZBossSheet);
                break;
            case 0x07: // ROM_ZONE_HTZ
                registerIfAbsent(Sonic2ObjectArtKeys.HTZ_BOSS, artLoader::loadHTZBossSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.HTZ_BOSS_SMOKE, artLoader::loadHTZBossSmokeSheet);
                break;
            case 0x0B: // ROM_ZONE_MCZ
                registerIfAbsent(Sonic2ObjectArtKeys.MCZ_BOSS, artLoader::loadMCZBossSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.MCZ_FALLING_ROCKS, artLoader::loadMCZFallingRocksSheet);
                break;
            case 0x0A: // ROM_ZONE_OOZ
                registerIfAbsent(Sonic2ObjectArtKeys.OOZ_BOSS, artLoader::loadOOZBossSheet);
                break;
            case 0x04: // ROM_ZONE_MTZ
                registerIfAbsent(Sonic2ObjectArtKeys.MTZ_BOSS, artLoader::loadMTZBossSheet);
                break;
            case 0x0E: // ROM_ZONE_DEZ
                registerIfAbsent(Sonic2ObjectArtKeys.DEZ_SILVER_SONIC, artLoader::loadSilverSonicSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.DEZ_WINDOW, artLoader::loadDEZWindowSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.DEZ_BOSS, artLoader::loadDEZBossSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.DEZ_EGGMAN, artLoader::loadDEZEggmanSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.DEZ_WALL, artLoader::loadDEZWallSheet);
                break;
            case 0x06: // ROM_ZONE_WFZ
                registerIfAbsent(Sonic2ObjectArtKeys.WFZ_BOSS, artLoader::loadWFZBossSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.WFZ_ROBOTNIK, artLoader::loadWFZRobotnikSheet);
                registerIfAbsent(Sonic2ObjectArtKeys.WFZ_ROBOTNIK_PLATFORM, artLoader::loadWFZRobotnikPlatformSheet);
                break;
        }
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
    public ObjectArtBundle getArtBundle() {
        ObjectArtBundle.Builder builder = ObjectArtBundle.builder()
                .sheets(sheets)
                .animations(animations)
                .hudDigitPatterns(hudDigitPatterns)
                .hudTextPatterns(hudTextPatterns)
                .hudLivesPatterns(hudLivesPatterns)
                .hudLivesNumbers(hudLivesNumbers)
                .hudHexDigitPatterns(hudHexDigits);
        if (artData != null) {
            builder.zoneData(ObjectArtKeys.ANIMAL_TYPE_A, artData.getAnimalTypeA())
                    .zoneData(ObjectArtKeys.ANIMAL_TYPE_B, artData.getAnimalTypeB());
        }
        return builder.build();
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
    public Pattern[] getHudHexDigitPatterns() {
        return hudHexDigits;
    }

    @Override
    public HudStaticArt getHudStaticArt() {
        return hudStaticArt;
    }

    /**
     * When cross-game features are active and the character is Knuckles,
     * loads the Knuckles life icon from the S3K donor ROM to replace the
     * S2 Sonic life icon.
     */
    private void overrideLivesArtFromDonor() {
        if (!com.openggf.game.CrossGameFeatureProvider.isS3kDonorActive()) {
            return;
        }
        String mainChar = ActiveGameplayTeamResolver.resolveMainCharacterCode(GameServices.configuration());
        if (!"knuckles".equalsIgnoreCase(mainChar)) {
            return;
        }
        Pattern[] knuxLife = loadS3kKnucklesLivesPatterns();
        if (knuxLife != null && knuxLife.length > 0) {
            hudLivesPatterns = knuxLife;
            livesNameUsesIconPalette = true;
            rebuildHudStaticArt();
            LOGGER.info("Overrode lives icon with Knuckles art from S3K donor (" + knuxLife.length + " tiles)");
        }
    }

    private void rebuildHudStaticArt() {
        hudStaticArt = Sonic2HudStaticArtFactory.create(
                hudTextPatterns,
                hudLivesPatterns,
                livesNameUsesIconPalette);
    }

    /**
     * Overrides the 1-up monitor's life-counter icon tile with the main character's
     * face when it is not Sonic.
     *
     * <p>The Sonic 1-up monitor icon piece maps to tile {@code $154}, which in the ROM
     * is {@code ArtTile_ArtNem_life_counter} — VRAM shared with the HUD life counter.
     * {@code PlrList_Std1} loads Sonic's life art there by default (handled in
     * {@link Sonic2ObjectArt}); {@code PlrList_TailsLife} and the Knuckles lock-on patch
     * replace it for Tails-alone and Knuckles games. We mirror that here so the standard
     * monitor shows the lead character's face. (s2.asm:89193, 89271)
     */
    private void overrideMonitorIconArtForMainCharacter() {
        if (artData == null || artData.monitorSheet() == null) {
            return;
        }
        String mainChar = ActiveGameplayTeamResolver.resolveMainCharacterCode(GameServices.configuration());
        Pattern[] lifeArt = resolveMonitorIconLifeArt(mainChar);
        if (lifeArt == null || lifeArt.length == 0) {
            return; // Sonic (the default already loaded), or Knuckles without an active donor
        }
        Pattern[] monitorPatterns = artData.monitorSheet().getPatterns();
        int offset = Sonic2ObjectArt.MONITOR_LIFE_ICON_TILE;
        int copied = 0;
        for (int i = 0; i < lifeArt.length && offset + i < monitorPatterns.length; i++) {
            monitorPatterns[offset + i] = lifeArt[i];
            copied++;
        }
        LOGGER.info("Overrode 1-up monitor icon with " + mainChar + " life art (" + copied + " tiles)");
    }

    /**
     * Returns the life-counter art to draw on the 1-up monitor for {@code mainChar},
     * or {@code null} to keep the Sonic default. Tails uses native S2 art; Knuckles
     * uses the palette-remapped S3K donor art (only when the donor is active).
     */
    private Pattern[] resolveMonitorIconLifeArt(String mainChar) {
        if ("tails".equalsIgnoreCase(mainChar)) {
            try {
                return com.openggf.util.PatternDecompressor.nemesis(
                        GameServices.rom().getRom(), Sonic2Constants.ART_NEM_TAILS_LIFE_ADDR);
            } catch (Exception e) {
                LOGGER.warning("Failed to load Tails life icon for monitor: " + e.getMessage());
                return null;
            }
        }
        if ("knuckles".equalsIgnoreCase(mainChar)
                && com.openggf.game.CrossGameFeatureProvider.isS3kDonorActive()) {
            return loadS3kKnucklesLivesPatterns();
        }
        return null;
    }

    Pattern[] loadS3kKnucklesLivesPatterns() {
        try {
            com.openggf.data.Rom donorRom = GameServices.rom().getSecondaryRom("s3k");
            Pattern[] knuxLife = com.openggf.util.PatternDecompressor.nemesis(donorRom,
                    com.openggf.game.sonic3k.constants.Sonic3kConstants.ART_NEM_KNUCKLES_LIFE_ICON_ADDR);
            if (knuxLife != null && knuxLife.length > 0) {
                // Remap pixel indices from S3K palette layout to S2-compatible layout.
                // Both palettes have the same colors but at different indices.
                remapPaletteIndices(knuxLife);
                return knuxLife;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to load Knuckles life icon from donor: " + e.getMessage());
        }
        return null;
    }

    /**
     * Remaps pixel palette indices in tile art from S3K's palette layout to the
     * S2-compatible Knuckles palette layout. The same colors exist in both
     * palettes but at different positions.
     *
     * <p>S3K Pal_Knuckles → S2-compatible (0x060BEA) index mapping, derived by
     * matching color values between the two palettes:
     * <pre>
     *  S3K  Color   S2-compat
     *  [0]  0x0000  [0]   transparent
     *  [1]  0x0EEE  [6]   white
     *  [2]  0x064E  [5]   bright red
     *  [3]  0x020C  [3]   medium red
     *  [4]  0x0206  [2]   dark red
     *  [5]  0x0080  [4]   green
     *  [6]  0x000E  [12]  red (shoes)
     *  [7]  0x0008  [13]  dark red (shoes)
     *  [8]  0x00AE  [14]  orange
     *  [9]  0x008E  [15]  dark orange
     *  [10] 0x08AE  [10]  skin
     *  [11] 0x046A  [11]  dark skin
     *  [12] 0x0ECC  [7]   (approx gray)
     *  [13] 0x0CAA  [8]   (approx gray)
     *  [14] 0x0866  [9]   (approx dark gray)
     *  [15] 0x0222  [1]   (approx black)
     * </pre>
     */
    private static final int[] S3K_TO_S2_PALETTE_REMAP = {
        0, 6, 5, 3, 2, 4, 12, 13, 14, 15, 10, 11, 7, 8, 9, 1
    };

    private void remapPaletteIndices(Pattern[] tiles) {
        for (Pattern tile : tiles) {
            for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
                for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                    int oldIdx = tile.getPixel(x, y) & 0x0F;
                    tile.setPixel(x, y, (byte) S3K_TO_S2_PALETTE_REMAP[oldIdx]);
                }
            }
        }
    }

    @Override
    public List<String> getRendererKeys() {
        return new ArrayList<>(rendererKeys);
    }

    @Override
    public int getRegularPatternCount() {
        return sheetOrder.stream().mapToInt(sheet -> sheet.getPatterns().length).sum();
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

    @Override
    public void registerLevelTileArt(Level level, int zoneIndex) {
        registerSmashableGroundSheet(level);
        registerSteamSpringPistonSheet(level);
        if (level != null && artLoader != null
                && (zoneIndex == Sonic2ZoneConstants.ROM_ZONE_MTZ
                || zoneIndex == Sonic2ZoneConstants.ROM_ZONE_MTZ_3)) {
            // Obj2D has no dedicated PLC in MTZ: its frame 1 uses level tiles.
            registerSheet(Sonic2ObjectArtKeys.BARRIER, artLoader.loadMtzBarrierSheet(level));
        }
    }

    /**
     * Registers the SmashableGround sprite sheet using level patterns.
     * This must be called AFTER the level is loaded since SmashableGround uses
     * level art patterns (ArtTile_ArtKos_LevelArt) rather than dedicated object art.
     * <p>
     * Only registers if we're in HTZ (zone 0x07) and the level is available.
     *
     * @param level The loaded level to extract patterns from
     */
    public void registerSmashableGroundSheet(Level level) {
        if (level == null || artLoader == null) {
            return;
        }

        // Only register for HTZ
        int zoneIndex = level.getZoneIndex();
        if (zoneIndex != Sonic2ZoneConstants.ROM_ZONE_HTZ) {
            return;
        }

        // Load and register the sheet
        ObjectSpriteSheet sheet = artLoader.loadSmashableGroundSheet(level);
        if (sheet != null) {
            registerSheet(Sonic2ObjectArtKeys.SMASHABLE_GROUND, sheet);
        }
    }

    /**
     * Register the SteamSpring piston body sheet for MTZ.
     * The piston body uses level art patterns (ArtTile_ArtKos_LevelArt) with palette line 3.
     * Only registers if we're in MTZ (zone 0x04 or 0x05).
     *
     * @param level The loaded level to extract patterns from
     */
    public void registerSteamSpringPistonSheet(Level level) {
        if (level == null || artLoader == null) {
            return;
        }

        int zoneIndex = level.getZoneIndex();
        if (zoneIndex != Sonic2ZoneConstants.ROM_ZONE_MTZ
                && zoneIndex != 0x05) {
            return;
        }

        ObjectSpriteSheet sheet = artLoader.loadSteamSpringPistonSheet(level);
        if (sheet != null) {
            registerSheet(Sonic2ObjectArtKeys.MTZ_STEAM_PISTON, sheet);
        }
    }

    // --- RewindSnapshottable<PlcProgressSnapshot> ---

    @Override
    public String key() {
        return "s2-plc-art";
    }

    @Override
    public com.openggf.game.rewind.snapshot.PlcProgressSnapshot capture() {
        return new com.openggf.game.rewind.snapshot.PlcProgressSnapshot(loadEpoch);
    }

    @Override
    public void restore(com.openggf.game.rewind.snapshot.PlcProgressSnapshot snap) {
        loadEpoch = snap.loadEpoch();
    }
}
