package com.openggf.game.sonic3k;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.AnimalType;
import com.openggf.level.objects.HudStaticArt;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.resources.PlcParser;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.level.resources.PlcParser.PlcEntry;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.data.compression.NemesisReader;
import com.openggf.util.PatternDecompressor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Object art provider for Sonic 3 &amp; Knuckles.
 * <p>
 * Many S3K objects use level patterns rather than dedicated compressed art.
 * This provider builds sprite sheets from the loaded level's pattern data
 * after the level has been loaded.
 */
public class Sonic3kObjectArtProvider implements ObjectArtProvider,
        com.openggf.game.rewind.RewindSnapshottable<com.openggf.game.rewind.snapshot.PlcProgressSnapshot> {
    private static final Logger LOG = Logger.getLogger(Sonic3kObjectArtProvider.class.getName());

    private int currentZoneIndex = -2;
    private int currentActIndex = 0;
    private int loadEpoch = 0;
    private RuntimeArtState cnzTeleporterArtState = RuntimeArtState.IDLE;
    private RuntimeArtState cnzEndBossArtState = RuntimeArtState.IDLE;
    private List<EnemyKosEntry> pendingEnemyKosEntries = List.of();
    private final List<HardwareWorkHandle> enemyKosHandles = new ArrayList<>();
    private S3kKosModuleQueue enemyKosQueue;
    private boolean enemyKosSubmissionArmed;
    private long runtimeArtAdmissionGeneration;
    private long runtimeArtAdmissionNextLeaseId;
    private RuntimeArtAdmissionLease runtimeArtAdmissionLease;
    private boolean runtimeArtAdmissionBound;
    private boolean runtimeArtAdmissionConsumed;
    private long titleCardTeardownLeaseId = -1;

    /** One-pass deferral for {@link #onInLevelTitleCardCompleted(RuntimeArtAdmissionLease)}. */
    private boolean enemyKosArmOnNextRuntimePass;

    /**
     * Residual ROM lifetime of the title-card owner when its presentation was
     * skipped. Non-null only while the owner is still running toward
     * {@code loc_2D8CA}'s {@code LoadEnemyArt}.
     */
    private com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardTeardownModel
            titleCardTeardown;

    private record EnemyKosEntry(int source, int destinationTile) {
    }

    private enum RuntimeArtState {
        IDLE,
        PENDING,
        COMPLETE,
        FAILED
    }

    private final Map<String, PatternSpriteRenderer> renderers = new HashMap<>();
    private final Map<String, ObjectSpriteSheet> sheets = new HashMap<>();
    private final Map<String, SpriteAnimationSet> animations = new HashMap<>();
    private final List<String> rendererKeys = new ArrayList<>();
    private final List<ObjectSpriteSheet> sheetOrder = new ArrayList<>();
    private final List<PatternSpriteRenderer> rendererOrder = new ArrayList<>();

    // Tracks which level tile indices each level-art sheet depends on.
    // Used by Sonic3kPlcLoader.refreshAffectedRenderers() to find which
    // renderers need GPU texture re-upload after PLC application.
    private final Map<String, List<Sonic3kPlcLoader.TileRange>> levelArtTileRanges = new HashMap<>();

    // Shield DPLC-driven renderers and art sets
    private final Map<String, PlayerSpriteRenderer> dplcRenderers = new HashMap<>();
    private final Map<String, SpriteArtSet> shieldArtSets = new HashMap<>();

    // HUD pattern caches
    private Pattern[] hudDigitPatterns;
    private Pattern[] hudTextPatterns;
    private Pattern[] hudLivesPatterns;
    private Pattern[] hudLivesNumbers;
    private Pattern[] hudHexDigits;
    private HudStaticArt hudStaticArt;

    // Zone-specific animal types (set per loadArtForZone call)
    private int animalTypeA = AnimalType.FLICKY.ordinal();
    private int animalTypeB = AnimalType.CHICKEN.ordinal();

    /**
     * S3K zone-to-animal mapping from PLCLoad_Animals_Index in sonic3k.asm.
     * Each entry is {AnimalA, AnimalB} for that zone index.
     */
    private static final AnimalType[][] S3K_ZONE_ANIMALS = {
            {AnimalType.FLICKY, AnimalType.CHICKEN},    // 0x00 AIZ
            {AnimalType.RABBIT, AnimalType.SEAL},       // 0x01 HCZ
            {AnimalType.FLICKY, AnimalType.CHICKEN},    // 0x02 MGZ
            {AnimalType.RABBIT, AnimalType.FLICKY},     // 0x03 CNZ
            {AnimalType.SQUIRREL, AnimalType.FLICKY},   // 0x04 FBZ
            {AnimalType.PENGUIN, AnimalType.SEAL},       // 0x05 ICZ
            {AnimalType.FLICKY, AnimalType.CHICKEN},    // 0x06 LBZ
            {AnimalType.SQUIRREL, AnimalType.CHICKEN},  // 0x07 MHZ
            {AnimalType.RABBIT, AnimalType.CHICKEN},    // 0x08 SOZ
            {AnimalType.FLICKY, AnimalType.CHICKEN},    // 0x09 LRZ
            {AnimalType.RABBIT, AnimalType.CHICKEN},    // 0x0A SSZ
            {AnimalType.SQUIRREL, AnimalType.CHICKEN},  // 0x0B DEZ
            {AnimalType.SQUIRREL, AnimalType.CHICKEN},  // 0x0C DDZ
    };
    private static final AnimalType[] DEFAULT_ANIMALS = {AnimalType.FLICKY, AnimalType.CHICKEN};

    @Override
    public void loadArtForZone(int zoneIndex) throws IOException {
        currentZoneIndex = zoneIndex;
        loadEpoch++;
        cnzTeleporterArtState = RuntimeArtState.IDLE;
        cnzEndBossArtState = RuntimeArtState.IDLE;

        // Clear previous registrations
        renderers.clear();
        sheets.clear();
        animations.clear();
        rendererKeys.clear();
        sheetOrder.clear();
        rendererOrder.clear();
        dplcRenderers.clear();
        shieldArtSets.clear();
        levelArtTileRanges.clear();

        // Load HUD art (same for all zones)
        loadHudArt();

        // Load shared object art (Nemesis compressed from ROM)
        loadExplosionArt();
        loadMonitorArt();
        loadStarPostArt();
        loadEggCapsuleArt();
        loadAnimalArt(zoneIndex);
        loadPointsArt();

        // Load shield art (DPLC-driven, same for all zones)
        loadShieldArt();

        // Drowning countdown digits (any zone can carry water)
        loadAirCountdownDigitArt();

        // Load invincibility star art (non-DPLC, same for all zones)
        loadInvincibilityStarArt();

        // Get act index from LevelManager (available during level load)
        currentActIndex = GameServices.level().getCurrentAct();
        scheduleEnemyKosArt(zoneIndex, currentActIndex);
        issueRuntimeArtAdmissionLease(RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
        Sonic3kPlcArtRegistry.ZoneArtPlan plan =
                Sonic3kPlcArtRegistry.getPlan(zoneIndex, currentActIndex);
        loadStandaloneFromRegistry(plan);
        // PLC-based boss art stays separate for now
        if (zoneIndex == 0x00) {
            loadAizMinibossArtFromPlc();
            loadAizEndBossArt();
            loadAiz2BattleshipArt();
        } else if (zoneIndex == 0x01) {
            loadSharedBossExplosionArt();
            loadHczMinibossArtFromPlc();
            loadHczEndBossArt();
            loadHczGeyserCutsceneArt();
        } else if (zoneIndex == 0x03) {
            // The teleporter art is not a level-load PLC. Obj_CNZTeleporter
            // queues ArtKosM_CNZTeleport at runtime when Knuckles reaches it.
            loadSharedBossExplosionArt();
            loadCnzMinibossArtFromPlc();
            // Obj_CNZEndBoss issues Load_PLC($6E) after its camera-range gate.
            // Keep this out of the level-load path so the provider exposes the
            // same request/completion seam as the native decompression queue.
            loadCnzTraversalArt();
        } else if (zoneIndex == 0x05) {
            loadSharedBossExplosionArt();
            loadIczMinibossArtFromPlc();
            loadIczEndBossArtFromPlc();
        } else if (zoneIndex == 0x06) {
            // LBZ1 start: Sonic is launched from the ground and a splash plays as
            // he breaks the surface (Obj_DashDust anim 4 / ArtUnc_SplashDrown).
            loadSurfaceSplashArt();
        }

        // Level-art sheets are registered later via registerLevelArtSheets()
        // since the level must be loaded first
        LOG.info("Sonic3kObjectArtProvider initialized for zone " + zoneIndex);
    }

    private void loadStandaloneFromRegistry(Sonic3kPlcArtRegistry.ZoneArtPlan plan) {
        Rom rom;
        try {
            rom = GameServices.rom().getRom();
        } catch (IOException e) {
            LOG.warning("Failed to get ROM for standalone art: " + e.getMessage());
            return;
        }
        if (rom == null) return;

        RomByteReader reader;
        try {
            reader = RomByteReader.fromRom(rom);
        } catch (IOException e) {
            LOG.warning("Failed to create RomByteReader: " + e.getMessage());
            return;
        }

        Sonic3kObjectArt art = new Sonic3kObjectArt(null, reader);

        for (Sonic3kPlcArtRegistry.StandaloneArtEntry entry : plan.standaloneArt()) {
            try {
                ObjectSpriteSheet sheet = art.loadStandaloneSheet(rom, entry);
                registerSheet(entry.key(), sheet);
                registerStandaloneAnimations(entry.key());
            } catch (IOException e) {
                LOG.warning("Failed to load standalone art '" + entry.key() + "': " + e.getMessage());
            }
        }
    }

    private void loadHudArt() throws IOException {
        Rom rom = GameServices.rom().getRom();
        if (rom == null) {
            LOG.warning("ROM not available for HUD art loading");
            return;
        }

        // HUD digits (0-9, colon, E) - uncompressed
        hudDigitPatterns = loadUncompressedPatterns(rom,
                Sonic3kConstants.ART_UNC_HUD_DIGITS_ADDR,
                Sonic3kConstants.ART_UNC_HUD_DIGITS_SIZE);
        LOG.info("Loaded " + (hudDigitPatterns != null ? hudDigitPatterns.length : 0) + " HUD digit patterns");

        // HUD text labels (SCORE/RINGS/TIME) - Nemesis compressed, tiles 14+ of ring/HUD blob
        hudTextPatterns = loadHudTextFromNemesis(rom);
        LOG.info("Loaded " + (hudTextPatterns != null ? hudTextPatterns.length : 0) + " HUD text patterns");

        // Lives icon - Nemesis compressed, character-specific
        int livesIconAddr = resolveLifeIconAddr();
        hudLivesPatterns = PatternDecompressor.nemesis(rom, livesIconAddr);
        LOG.info("Loaded " + (hudLivesPatterns != null ? hudLivesPatterns.length : 0) + " HUD lives icon patterns");

        // Lives digits (0-9) - uncompressed
        hudLivesNumbers = loadUncompressedPatterns(rom,
                Sonic3kConstants.ART_UNC_LIVES_DIGITS_ADDR,
                Sonic3kConstants.ART_UNC_LIVES_DIGITS_SIZE);
        LOG.info("Loaded " + (hudLivesNumbers != null ? hudLivesNumbers.length : 0) + " HUD lives digit patterns");

        // Debug HUD hex font (ArtUnc_DebugDigits) - ASCII-aligned, 0-9 then A-F at +17.
        hudHexDigits = loadUncompressedPatterns(rom,
                Sonic3kConstants.ART_UNC_DEBUG_DIGITS_ADDR,
                Sonic3kConstants.ART_UNC_DEBUG_DIGITS_SIZE);
        LOG.info("Loaded " + (hudHexDigits != null ? hudHexDigits.length : 0) + " HUD debug-digit patterns");

        hudStaticArt = Sonic3kHudStaticArtFactory.create(hudTextPatterns, hudLivesPatterns);
    }

    /**
     * Returns the ROM address for the character-specific life icon art.
     * ROM: PLC_01 (Sonic), PLC_05 (Knuckles), PLC_07 (Tails).
     */
    private int resolveLifeIconAddr() {
        String mainChar = ActiveGameplayTeamResolver.resolveMainCharacterCode(
                GameServices.configuration());
        if ("knuckles".equalsIgnoreCase(mainChar)) {
            return Sonic3kConstants.ART_NEM_KNUCKLES_LIFE_ICON_ADDR;
        } else if ("tails".equalsIgnoreCase(mainChar)) {
            return Sonic3kConstants.ART_NEM_TAILS_LIFE_ICON_ADDR;
        }
        return Sonic3kConstants.ART_NEM_SONIC_LIFE_ICON_ADDR;
    }

    private Pattern[] loadUncompressedPatterns(Rom rom, int addr, int size) throws IOException {
        byte[] data = rom.readBytes(addr, size);
        int count = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[count];
        for (int i = 0; i < count; i++) {
            patterns[i] = new Pattern();
            byte[] tile = Arrays.copyOfRange(data, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(tile);
        }
        return patterns;
    }


    /**
     * Loads HUD text patterns from the shared ring/HUD Nemesis blob.
     * The first 14 tiles are ring art; tiles 14+ are HUD text (S, C, O, R, R, I, N, G, T, I, M, E).
     */
    private Pattern[] loadHudTextFromNemesis(Rom rom) throws IOException {
        FileChannel channel = rom.getFileChannel();
        // Rom exposes a shared FileChannel; lock around seek+decode so concurrent
        // readers cannot move the channel position mid-stream.
        byte[] data;
        synchronized (rom) {
            channel.position(Sonic3kConstants.ART_NEM_RING_HUD_TEXT_ADDR);
            data = NemesisReader.decompress(channel);
        }

        int totalTiles = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        int ringTiles = 14; // First 14 tiles are ring sprite data
        int textTiles = totalTiles - ringTiles;
        if (textTiles <= 0) {
            LOG.warning("No HUD text tiles found in ring/HUD blob (total=" + totalTiles + ")");
            return null;
        }

        Pattern[] patterns = new Pattern[textTiles];
        for (int i = 0; i < textTiles; i++) {
            int offset = (ringTiles + i) * Pattern.PATTERN_SIZE_IN_ROM;
            patterns[i] = new Pattern();
            byte[] tile = Arrays.copyOfRange(data, offset, offset + Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(tile);
        }
        return patterns;
    }

    /**
     * Loads S3K explosion art from ROM (Nemesis compressed).
     * <p>
     * Art: ArtNem_Explosion at 0x19200A.
     * Mappings: Map - Explosion.asm (5 frames, same layout as S2).
     * <p>
     * The explosion object itself plays sfx_Break (0x3D) — see Obj_Explosion loc_1E61A.
     */
    private void loadExplosionArt() throws IOException {
        Rom rom = GameServices.rom().getRom();
        if (rom == null) {
            return;
        }

        Pattern[] patterns = PatternDecompressor.nemesis(rom, Sonic3kConstants.ART_NEM_EXPLOSION_ADDR);
        RomByteReader reader = RomByteReader.fromRom(rom);
        ObjectSpriteSheet sheet = buildSheetFromPatterns(
                patterns, reader, Sonic3kConstants.MAP_EXPLOSION_ADDR, 0);
        registerSheet(ObjectArtKeys.EXPLOSION, sheet);
        LOG.info("Loaded S3K explosion art: " + patterns.length + " patterns, 5 frames");
    }

    /**
     * Loads S3K monitor art from ROM (Nemesis compressed).
     * Builds mapping frames and animation set from disassembly data.
     * <p>
     * Art: ArtNem_Monitors at 0x190F4A (60 tiles).
     * 1-Up icon uses player life icon patterns at tile offset 0x310.
     * Mappings: Map - Monitor.asm (12 frames, 6-byte piece format).
     * Animations: Anim - Monitor.asm (11 sequences).
     */
    private void loadMonitorArt() throws IOException {
        Rom rom = GameServices.rom().getRom();
        if (rom == null) {
            LOG.warning("ROM not available for monitor art loading");
            return;
        }

        // Load monitor base patterns (Nemesis compressed, ~60 tiles)
        Pattern[] monitorBasePatterns = PatternDecompressor.nemesis(rom, Sonic3kConstants.ART_NEM_MONITORS_ADDR);

        // Extend pattern array to cover life icon at offset 0x310
        // (1-Up monitor icon uses ArtTile_Player_Life_Icon = ArtTile_Monitors + $310)
        int lifeArtOffset = 0x310;
        int requiredSize = lifeArtOffset + (hudLivesPatterns != null ? hudLivesPatterns.length : 0);
        requiredSize = Math.max(requiredSize, monitorBasePatterns.length);

        Pattern[] monitorPatterns = new Pattern[requiredSize];
        System.arraycopy(monitorBasePatterns, 0, monitorPatterns, 0, monitorBasePatterns.length);

        // Copy life icon patterns at offset 0x310
        if (hudLivesPatterns != null && hudLivesPatterns.length > 0) {
            System.arraycopy(hudLivesPatterns, 0, monitorPatterns, lifeArtOffset,
                    Math.min(hudLivesPatterns.length, monitorPatterns.length - lifeArtOffset));
        }

        // Fill gaps with empty patterns to prevent NPEs
        for (int i = 0; i < monitorPatterns.length; i++) {
            if (monitorPatterns[i] == null) {
                monitorPatterns[i] = new Pattern();
            }
        }

        RomByteReader reader = RomByteReader.fromRom(rom);
        List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                reader, Sonic3kConstants.MAP_MONITOR_ADDR, 12);

        // Create and register sprite sheet
        ObjectSpriteSheet monitorSheet = new ObjectSpriteSheet(monitorPatterns, frames, 0, 1);
        registerSheet(Sonic3kObjectArtKeys.MONITOR, monitorSheet);

        // Build and register animation set (Anim - Monitor.asm)
        SpriteAnimationSet monitorAnimations = buildMonitorAnimations();
        animations.put(ObjectArtKeys.ANIM_MONITOR, monitorAnimations);

        LOG.info("Loaded S3K monitor art: " + monitorBasePatterns.length + " base patterns, "
                + frames.size() + " mapping frames, 11 animations");
    }

    /**
     * Builds S3K monitor animation set from disassembly data.
     * <p>
     * From Anim - Monitor.asm: 11 animation sequences.
     * Anims 0-9 are monitor type animations (alternating icon/box).
     * Anim 10 is the break animation (box, eggman, broken shell → hold).
     */
    private static SpriteAnimationSet buildMonitorAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0 (Eggman): delay=1, [0, 1], loop
        set.addScript(0, new SpriteAnimationScript(1, List.of(0, 1),
                SpriteAnimationEndAction.LOOP, 0));

        // Anims 1-9: delay=1, [0, icon, icon, 1, icon, icon], loop
        // icon frame = anim_id + 1 (maps to mapping frames 2-10)
        for (int i = 1; i <= 9; i++) {
            int iconFrame = i + 1;
            set.addScript(i, new SpriteAnimationScript(1,
                    List.of(0, iconFrame, iconFrame, 1, iconFrame, iconFrame),
                    SpriteAnimationEndAction.LOOP, 0));
        }

        // Anim 10 (break): delay=2, [0, 1, 11], loop back 1 (holds on broken)
        set.addScript(10, new SpriteAnimationScript(2, List.of(0, 1, 11),
                SpriteAnimationEndAction.LOOP_BACK, 1));

        return set;
    }

    /**
     * Loads S3K StarPost art from ROM (Nemesis compressed).
     * <p>
     * Art: ArtNem_EnemyPtsStarPost at 0x192D2A (28 tiles: 8 enemy pts + 20 starpost).
     * Mappings: Map_StarPost at 0x2D348 (5 frames).
     * art_tile: make_art_tile(ArtTile_StarPost+8, 0, 0) — mapping tiles offset by 8 from blob start.
     * <p>
     * Also loads StarPost Stars mappings (Map_StarpostStars, 3 frames) for bonus star rendering.
     */
    private void loadStarPostArt() throws IOException {
        Rom rom = GameServices.rom().getRom();
        if (rom == null) return;

        RomByteReader reader;
        try {
            reader = RomByteReader.fromRom(rom);
        } catch (IOException e) {
            LOG.warning("Failed to create RomByteReader for StarPost art: " + e.getMessage());
            return;
        }

        // Load combined enemy pts + starpost Nemesis art (28 tiles)
        Pattern[] allPatterns = PatternDecompressor.nemesis(rom, Sonic3kConstants.ART_NEM_ENEMY_PTS_STARPOST_ADDR);

        // Parse starpost mappings from ROM
        List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(reader,
                Sonic3kConstants.MAP_STARPOST_ADDR);

        // Mapping tile indices are relative to art_tile (ArtTile_StarPost+8),
        // but the Nemesis blob starts at ArtTile_StarPost. Offset by +8.
        List<SpriteMappingFrame> adjusted = new ArrayList<>(frames.size());
        for (SpriteMappingFrame frame : frames) {
            List<SpriteMappingPiece> adjustedPieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                adjustedPieces.add(new SpriteMappingPiece(
                        piece.xOffset(), piece.yOffset(),
                        piece.widthTiles(), piece.heightTiles(),
                        piece.tileIndex() + 8,
                        piece.hFlip(), piece.vFlip(),
                        piece.paletteIndex(), piece.priority()));
            }
            adjusted.add(new SpriteMappingFrame(adjustedPieces));
        }

        ObjectSpriteSheet sheet = new ObjectSpriteSheet(allPatterns, adjusted, 0, 1);
        registerSheet(ObjectArtKeys.CHECKPOINT, sheet);

        // Also load StarPost Stars mappings for bonus star rendering
        List<SpriteMappingFrame> starFrames = S3kSpriteDataLoader.loadMappingFrames(reader,
                Sonic3kConstants.MAP_STARPOST_STARS_ADDR);
        // Star mappings use tiles relative to art_tile (same blob, same +8 offset)
        List<SpriteMappingFrame> adjustedStars = new ArrayList<>(starFrames.size());
        for (SpriteMappingFrame frame : starFrames) {
            List<SpriteMappingPiece> adjustedPieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                adjustedPieces.add(new SpriteMappingPiece(
                        piece.xOffset(), piece.yOffset(),
                        piece.widthTiles(), piece.heightTiles(),
                        piece.tileIndex() + 8,
                        piece.hFlip(), piece.vFlip(),
                        piece.paletteIndex(), piece.priority()));
            }
            adjustedStars.add(new SpriteMappingFrame(adjustedPieces));
        }

        ObjectSpriteSheet starSheet = new ObjectSpriteSheet(allPatterns, adjustedStars, 0, 1);
        registerSheet(ObjectArtKeys.CHECKPOINT_STAR, starSheet);

        // Load 3 KosinskiM star art variants (ROM: Queue_Kos_Module at loc_2D436).
        // Each variant is 3 tiles replacing tiles at ArtTile_StarPost+8 (index 8 in our blob).
        loadStarVariant(rom, allPatterns, adjustedStars,
                Sonic3kConstants.ART_KOSM_STARPOST_STARS3_ADDR, ObjectArtKeys.CHECKPOINT_STAR_RED);
        loadStarVariant(rom, allPatterns, adjustedStars,
                Sonic3kConstants.ART_KOSM_STARPOST_STARS1_ADDR, ObjectArtKeys.CHECKPOINT_STAR_BLUE);
        loadStarVariant(rom, allPatterns, adjustedStars,
                Sonic3kConstants.ART_KOSM_STARPOST_STARS2_ADDR, ObjectArtKeys.CHECKPOINT_STAR_YELLOW);

        LOG.info("Loaded S3K StarPost art: " + allPatterns.length + " patterns, "
                + adjusted.size() + " frames, " + adjustedStars.size() + " star frames"
                + ", 3 bonus star variants");
    }

    /**
     * Loads a single KosinskiM star art variant and creates a sprite sheet.
     * ROM: Stars are 3 tiles decompressed to ArtTile_StarPost+8 (tile index 8 in our blob).
     */
    private void loadStarVariant(Rom rom, Pattern[] basePatterns,
                                  List<SpriteMappingFrame> starFrames,
                                  int kosmAddr, String artKey) {
        try {
            // Read and decompress KosinskiM data
            byte[] header = rom.readBytes(kosmAddr, 2);
            int fullSize = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
            int inputSize = Math.min(Math.max(fullSize + 256, 0x10000), 0x40000);
            byte[] romData = rom.readBytes(kosmAddr, inputSize);
            byte[] decompressed = KosinskiReader.decompressModuled(romData, 0);

            int tileCount = decompressed.length / Pattern.PATTERN_SIZE_IN_ROM;
            if (tileCount < 1) {
                LOG.warning("Star variant at 0x" + Integer.toHexString(kosmAddr)
                        + " decompressed to " + decompressed.length + " bytes (0 tiles)");
                return;
            }

            // Clone base patterns and replace tiles 8..8+tileCount with variant art
            Pattern[] variantPatterns = Arrays.copyOf(basePatterns, basePatterns.length);
            for (int i = 0; i < tileCount && (8 + i) < variantPatterns.length; i++) {
                variantPatterns[8 + i] = new Pattern();
                byte[] tile = Arrays.copyOfRange(decompressed, i * Pattern.PATTERN_SIZE_IN_ROM,
                        (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                variantPatterns[8 + i].fromSegaFormat(tile);
            }

            registerSheet(artKey, new ObjectSpriteSheet(variantPatterns, starFrames, 0, 1));
        } catch (Exception e) {
            LOG.warning("Failed to load star variant at 0x" + Integer.toHexString(kosmAddr)
                    + ": " + e.getMessage());
        }
    }

    /**
     * Loads animal art for the current zone.
     * <p>
     * Each zone has two assigned animal types. Art is Nemesis-compressed per animal type.
     * Mappings are parsed from ROM (Map_Animals1-5, 3 frames each, 6-byte S3K pieces).
     * The combined sheet follows the same layout as S2: 5 mapping sets × 2 variants × 3 frames.
     * <p>
     * The animal tile bank offset in S3K is 18 tiles (ArtTile_Animals2 - ArtTile_Animals1).
     */
    private void loadAnimalArt(int zoneIndex) throws IOException {
        Rom rom = GameServices.rom().getRom();
        if (rom == null) return;

        RomByteReader reader = RomByteReader.fromRom(rom);

        // Resolve zone-specific animal types
        AnimalType[] zoneAnimals = (zoneIndex >= 0 && zoneIndex < S3K_ZONE_ANIMALS.length)
                ? S3K_ZONE_ANIMALS[zoneIndex] : DEFAULT_ANIMALS;
        AnimalType typeA = zoneAnimals[0];
        AnimalType typeB = zoneAnimals[1];
        animalTypeA = typeA.ordinal();
        animalTypeB = typeB.ordinal();

        // Load Nemesis-compressed art for both animal types
        Pattern[] patternsA = PatternDecompressor.nemesis(rom, getS3kAnimalArtAddr(typeA));
        Pattern[] patternsB = PatternDecompressor.nemesis(rom, getS3kAnimalArtAddr(typeB));

        // Combine into a single bank with S3K's 18-tile offset
        int tileOffset = Sonic3kConstants.S3K_ANIMAL_TILE_OFFSET;
        int combinedLength = Math.max(
                Math.max(patternsA.length, tileOffset + patternsB.length),
                tileOffset * 2);
        Pattern[] combined = new Pattern[combinedLength];
        System.arraycopy(patternsA, 0, combined, 0, Math.min(patternsA.length, combined.length));
        if (tileOffset < combined.length) {
            int copyLen = Math.min(patternsB.length, combined.length - tileOffset);
            System.arraycopy(patternsB, 0, combined, tileOffset, copyLen);
        }
        for (int i = 0; i < combined.length; i++) {
            if (combined[i] == null) combined[i] = new Pattern();
        }

        // Parse all 5 mapping tables from ROM and build the combined frame list.
        // Layout: 5 sets × 2 variants (offset 0, offset 18) × 3 frames = 30 frames.
        // This matches the indexing in AnimalObjectInstance.getFrameIndex().
        int[] mapAddrs = {
                Sonic3kConstants.MAP_ANIMALS1_ADDR,
                Sonic3kConstants.MAP_ANIMALS2_ADDR,
                Sonic3kConstants.MAP_ANIMALS3_ADDR,
                Sonic3kConstants.MAP_ANIMALS4_ADDR,
                Sonic3kConstants.MAP_ANIMALS5_ADDR,
        };

        List<SpriteMappingFrame> allFrames = new ArrayList<>(30);
        for (int mapAddr : mapAddrs) {
            List<SpriteMappingFrame> setFrames = S3kSpriteDataLoader.loadMappingFrames(reader, mapAddr, 3);
            // Variant 0: tile indices as-is (animal A at offset 0)
            allFrames.addAll(setFrames);
            // Variant 1: tile indices shifted by tileOffset (animal B at offset 18)
            for (SpriteMappingFrame frame : setFrames) {
                List<SpriteMappingPiece> shifted = new ArrayList<>(frame.pieces().size());
                for (SpriteMappingPiece piece : frame.pieces()) {
                    shifted.add(new SpriteMappingPiece(
                            piece.xOffset(), piece.yOffset(),
                            piece.widthTiles(), piece.heightTiles(),
                            piece.tileIndex() + tileOffset,
                            piece.hFlip(), piece.vFlip(),
                            piece.paletteIndex(), piece.priority()));
                }
                allFrames.add(new SpriteMappingFrame(shifted));
            }
        }

        ObjectSpriteSheet animalSheet = new ObjectSpriteSheet(combined, allFrames, 0, 1);
        registerSheet(ObjectArtKeys.ANIMAL, animalSheet);
        LOG.info("Loaded S3K animal art: " + typeA.displayName() + " + " + typeB.displayName()
                + ", " + combined.length + " patterns, " + allFrames.size() + " frames");
    }

    /**
     * Returns the S3K-specific Nemesis art ROM address for the given animal type.
     * S3K uses BlueFlicky (mapped to FLICKY) instead of S2's Flicky.
     */
    private static int getS3kAnimalArtAddr(AnimalType type) {
        return switch (type) {
            case RABBIT -> Sonic3kConstants.ART_NEM_RABBIT_ADDR;
            case CHICKEN -> Sonic3kConstants.ART_NEM_CHICKEN_ADDR;
            case PENGUIN -> Sonic3kConstants.ART_NEM_PENGUIN_ADDR;
            case SEAL -> Sonic3kConstants.ART_NEM_SEAL_ADDR;
            case PIG -> Sonic3kConstants.ART_NEM_PIG_ADDR;
            case FLICKY -> Sonic3kConstants.ART_NEM_BLUE_FLICKY_ADDR;
            case SQUIRREL -> Sonic3kConstants.ART_NEM_SQUIRREL_ADDR;
            // Animals not present in S3K ROM - fall back to BlueFlicky
            default -> Sonic3kConstants.ART_NEM_BLUE_FLICKY_ADDR;
        };
    }

    /**
     * Loads enemy score/points popup art.
     * <p>
     * The score tiles are the first 8 tiles of ArtNem_EnemyPtsStarPost (already loaded
     * by loadStarPostArt). Mappings are parsed from Map_EnemyScore (7 frames).
     * Unlike the StarPost mappings which need a +8 tile offset, the score mappings
     * reference tiles starting at 0 (the beginning of the combined art blob).
     */
    private void loadPointsArt() throws IOException {
        Rom rom = GameServices.rom().getRom();
        if (rom == null) return;

        RomByteReader reader = RomByteReader.fromRom(rom);

        // Load the same combined art blob used by StarPost
        Pattern[] patterns = PatternDecompressor.nemesis(rom, Sonic3kConstants.ART_NEM_ENEMY_PTS_STARPOST_ADDR);

        // Parse Map_EnemyScore (7 frames, tile indices relative to start of blob - no offset needed)
        List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(reader,
                Sonic3kConstants.MAP_ENEMY_SCORE_ADDR, 7);

        ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, frames, 0, 1);
        registerSheet(ObjectArtKeys.POINTS, sheet);
        LOG.info("Loaded S3K enemy score art: " + patterns.length + " patterns, " + frames.size() + " frames");
    }

    /**
     * Loads the drowning countdown digits used by {@code Obj_AirCountdown}.
     *
     * <p>The object's mappings are {@code Map_Bubbler} with
     * {@code make_art_tile(ArtTile_Bubbles,0,0)}, so its bubble frames come out
     * of {@code ArtNem_Bubbles} (the shared {@code BUBBLER} sheet). Frames
     * {@code $09}-{@code $12} instead point one 2x3 piece at tile
     * {@code $384} past {@code ArtTile_Bubbles}, which lands on
     * {@code ArtTile_DashDust} — a VRAM window {@code AirCountdown_Load_Art}
     * refills by DMA with six tiles from {@code ArtUnc_AirCountdown} per
     * mapping frame (sonic3k.asm:33489-33516).
     *
     * <p>We can't express "same mapping, different source tiles" with a flat
     * tile offset, so this rebuilds those ten frames from the ROM mapping
     * geometry with each frame's tile index moved onto its own six-tile slice
     * of the digit art — the engine-side equivalent of that DMA. P2 differs
     * only in which VRAM window it targets ({@code Map_Bubbler2} /
     * {@code ArtTile_DashDust_P2}), so both players share this sheet.
     */
    private void loadAirCountdownDigitArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) {
                return;
            }
            RomByteReader reader = RomByteReader.fromRom(rom);
            Pattern[] tiles = S3kSpriteDataLoader.loadArtTiles(reader,
                    Sonic3kConstants.ART_UNC_AIR_COUNTDOWN_ADDR,
                    Sonic3kConstants.ART_UNC_AIR_COUNTDOWN_SIZE);

            int firstFrame = Sonic3kConstants.AIR_COUNTDOWN_FIRST_DIGIT_FRAME;
            int frameCount = Sonic3kConstants.AIR_COUNTDOWN_DIGIT_FRAME_COUNT;
            List<SpriteMappingFrame> bubblerFrames = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_BUBBLER_ADDR, firstFrame + frameCount);

            List<SpriteMappingFrame> digitFrames = new ArrayList<>(frameCount);
            for (int i = 0; i < frameCount; i++) {
                int sourceTile = i * Sonic3kConstants.AIR_COUNTDOWN_TILES_PER_DIGIT;
                List<SpriteMappingPiece> pieces = new ArrayList<>();
                for (SpriteMappingPiece piece : bubblerFrames.get(firstFrame + i).pieces()) {
                    pieces.add(new SpriteMappingPiece(
                            piece.xOffset(),
                            piece.yOffset(),
                            piece.widthTiles(),
                            piece.heightTiles(),
                            piece.tileIndex()
                                    - Sonic3kConstants.AIR_COUNTDOWN_DIGIT_TILE_OFFSET
                                    + sourceTile,
                            piece.hFlip(),
                            piece.vFlip(),
                            piece.paletteIndex(),
                            piece.priority()));
                }
                digitFrames.add(new SpriteMappingFrame(pieces));
            }

            registerSheet(Sonic3kObjectArtKeys.AIR_COUNTDOWN_DIGITS,
                    new ObjectSpriteSheet(tiles, digitFrames, 0, 1));
            LOG.info("Loaded S3K air countdown digits: " + tiles.length + " tiles, "
                    + digitFrames.size() + " frames");
        } catch (IOException e) {
            LOG.warning("Failed to load air countdown digit art: " + e.getMessage());
        }
    }

    /**
     * Loads shield art (fire, lightning, bubble) from ROM using DPLC-driven rendering.
     * Each shield has its own uncompressed art, mappings, DPLCs, and animation scripts.
     */
    private void loadShieldArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) {
                return;
            }
            RomByteReader reader = RomByteReader.fromRom(rom);
            loadSingleShieldArt(reader, Sonic3kObjectArtKeys.FIRE_SHIELD,
                    Sonic3kConstants.ART_UNC_FIRE_SHIELD_ADDR, Sonic3kConstants.ART_UNC_FIRE_SHIELD_SIZE,
                    Sonic3kConstants.MAP_FIRE_SHIELD_ADDR, Sonic3kConstants.DPLC_FIRE_SHIELD_ADDR,
                    Sonic3kConstants.ANI_FIRE_SHIELD_ADDR, Sonic3kConstants.ANI_FIRE_SHIELD_COUNT,
                    Sonic3kConstants.ART_TILE_SHIELD, 0);

            loadSingleShieldArt(reader, Sonic3kObjectArtKeys.LIGHTNING_SHIELD,
                    Sonic3kConstants.ART_UNC_LIGHTNING_SHIELD_ADDR, Sonic3kConstants.ART_UNC_LIGHTNING_SHIELD_SIZE,
                    Sonic3kConstants.MAP_LIGHTNING_SHIELD_ADDR, Sonic3kConstants.DPLC_LIGHTNING_SHIELD_ADDR,
                    Sonic3kConstants.ANI_LIGHTNING_SHIELD_ADDR, Sonic3kConstants.ANI_LIGHTNING_SHIELD_COUNT,
                    Sonic3kConstants.ART_TILE_SHIELD, 0);

            // ROM: Obj_LightningShield init DMA-loads spark art to ArtTile_Shield_Sparks
            // (fixed VRAM, not managed by PLCLoad_Shields). Sparks use their own renderer
            // with the 5 spark tiles and rebased mappings (tile indices 0-4 instead of 31-35).
            buildSparkArtSet(reader);

            loadSingleShieldArt(reader, Sonic3kObjectArtKeys.BUBBLE_SHIELD,
                    Sonic3kConstants.ART_UNC_BUBBLE_SHIELD_ADDR, Sonic3kConstants.ART_UNC_BUBBLE_SHIELD_SIZE,
                    Sonic3kConstants.MAP_BUBBLE_SHIELD_ADDR, Sonic3kConstants.DPLC_BUBBLE_SHIELD_ADDR,
                    Sonic3kConstants.ANI_BUBBLE_SHIELD_ADDR, Sonic3kConstants.ANI_BUBBLE_SHIELD_COUNT,
                    Sonic3kConstants.ART_TILE_SHIELD, 0);

            loadSingleShieldArt(reader, Sonic3kObjectArtKeys.INSTA_SHIELD,
                    Sonic3kConstants.ART_UNC_INSTA_SHIELD_ADDR, Sonic3kConstants.ART_UNC_INSTA_SHIELD_SIZE,
                    Sonic3kConstants.MAP_INSTA_SHIELD_ADDR, Sonic3kConstants.DPLC_INSTA_SHIELD_ADDR,
                    Sonic3kConstants.ANI_INSTA_SHIELD_ADDR, Sonic3kConstants.ANI_INSTA_SHIELD_COUNT,
                    Sonic3kConstants.ART_TILE_SHIELD, 0);
        } catch (IOException e) {
            LOG.warning("Failed to load shield art: " + e.getMessage());
        }
    }

    /**
     * Virtual pattern base for the surface-splash DPLC bank. Lives in the
     * transient-effects range so it never collides with the player's dash-dust
     * bank (water-surface range) or shield banks.
     */
    private static final int SURFACE_SPLASH_PATTERN_BASE = PatternAtlasRange.TRANSIENT_EFFECTS.base();

    /**
     * Loads the splash/drown art set used by {@code Obj_DashDust} animation 4
     * ({@code Ani_DashSplashDrown} frames {@code $16-$1D}). This is the splash
     * that appears when the player emerges from the surface at the LBZ1 start
     * (ROM: {@code Obj_LevelIntro_PlayerLaunchFromGround} writes {@code anim=4}
     * into the Dust object at {@code sonic3k.asm} loc_39AD2).
     *
     * <p>It reuses {@code Map_DashDust} + {@code DPLC_DashSplashDrown} (the same
     * tables as the dash dust) but sources tiles from {@code ArtUnc_SplashDrown}
     * instead of {@code ArtUnc_DashDust}. The DPLC frames {@code $16-$1D} index
     * source tiles 0-123, exactly filling the 124-tile splash art.
     */
    private void loadSurfaceSplashArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) {
                return;
            }
            RomByteReader reader = RomByteReader.fromRom(rom);
            Pattern[] tiles = S3kSpriteDataLoader.loadArtTiles(reader,
                    Sonic3kConstants.ART_UNC_SPLASH_DROWN_ADDR,
                    Sonic3kConstants.ART_UNC_SPLASH_DROWN_SIZE);
            List<SpriteMappingFrame> mappings = S3kSpriteDataLoader.loadMappingFrames(
                    reader, Sonic3kConstants.MAP_DASH_DUST_ADDR);
            List<SpriteDplcFrame> dplcs = S3kSpriteDataLoader.loadDplcFrames(
                    reader, Sonic3kConstants.DPLC_DASH_DUST_ADDR);
            int bankSize = S3kSpriteDataLoader.resolveBankSize(dplcs, mappings);

            SpriteArtSet artSet = new SpriteArtSet(tiles, mappings, dplcs,
                    0, SURFACE_SPLASH_PATTERN_BASE, 1, bankSize, null, null);
            PlayerSpriteRenderer renderer = new PlayerSpriteRenderer(artSet);

            shieldArtSets.put(Sonic3kObjectArtKeys.SURFACE_SPLASH, artSet);
            dplcRenderers.put(Sonic3kObjectArtKeys.SURFACE_SPLASH, renderer);
            LOG.info("Loaded S3K surface splash art: " + tiles.length + " tiles, "
                    + mappings.size() + " mapping frames");
        } catch (IOException e) {
            LOG.warning("Failed to load surface splash art: " + e.getMessage());
        }
    }

    /** Returns the DPLC-driven renderer for the surface-splash effect, or null. */
    public PlayerSpriteRenderer getSurfaceSplashRenderer() {
        return dplcRenderers.get(Sonic3kObjectArtKeys.SURFACE_SPLASH);
    }

    /**
     * Loads invincibility star art from ROM (uncompressed art + ROM-parsed mappings).
     * ROM: ArtUnc_Invincibility binclude (32 tiles), Map_Invincibility (9 frames).
     * No DPLCs — static tile assignment like explosion art.
     */
    private void loadInvincibilityStarArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) {
                return;
            }
            Pattern[] patterns = loadUncompressedPatterns(rom,
                    Sonic3kConstants.ART_UNC_INVINCIBILITY_ADDR,
                    Sonic3kConstants.ART_UNC_INVINCIBILITY_SIZE);

            RomByteReader reader = RomByteReader.fromRom(rom);
            List<SpriteMappingFrame> rawMappings =
                    S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kConstants.MAP_INVINCIBILITY_ADDR);

            // Normalize tile indices to 0-based (ROM mappings reference ArtTile_Shield = $079C)
            List<SpriteMappingFrame> mappings = normalizeMappingTileIndices(rawMappings);

            ObjectSpriteSheet sheet = new ObjectSpriteSheet(patterns, mappings, 0, 1);
            registerSheet(ObjectArtKeys.INVINCIBILITY_STARS, sheet);
            LOG.info("Loaded S3K invincibility star art: " + patterns.length
                    + " tiles, " + mappings.size() + " mapping frames");
        } catch (Exception e) {
            LOG.warning("Failed to load invincibility star art: " + e.getMessage());
        }
    }

    /**
     * Normalizes mapping tile indices to be 0-based by subtracting the minimum
     * tile index found across all pieces. Required when ROM mappings reference
     * absolute VRAM tile positions (e.g. ArtTile_Shield = $079C).
     */
    private static List<SpriteMappingFrame> normalizeMappingTileIndices(List<SpriteMappingFrame> frames) {
        int minTile = Integer.MAX_VALUE;
        for (SpriteMappingFrame frame : frames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                if (piece.tileIndex() < minTile) {
                    minTile = piece.tileIndex();
                }
            }
        }
        if (minTile == 0 || minTile == Integer.MAX_VALUE) {
            return frames;
        }
        final int offset = minTile;
        List<SpriteMappingFrame> normalized = new ArrayList<>(frames.size());
        for (SpriteMappingFrame frame : frames) {
            List<SpriteMappingPiece> pieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece p : frame.pieces()) {
                pieces.add(new SpriteMappingPiece(
                        p.xOffset(), p.yOffset(), p.widthTiles(), p.heightTiles(),
                        p.tileIndex() - offset, p.hFlip(), p.vFlip(),
                        p.paletteIndex(), p.priority()));
            }
            normalized.add(new SpriteMappingFrame(pieces));
        }
        return normalized;
    }

    private void loadSingleShieldArt(RomByteReader reader, String key,
            int artAddr, int artSize, int mapAddr, int dplcAddr,
            int animAddr, int animCount, int baseTile, int paletteIndex) throws IOException {
        Pattern[] tiles = S3kSpriteDataLoader.loadArtTiles(reader, artAddr, artSize);
        List<SpriteMappingFrame> mappings = S3kSpriteDataLoader.loadMappingFrames(reader, mapAddr);
        List<SpriteDplcFrame> dplcs = S3kSpriteDataLoader.loadDplcFrames(reader, dplcAddr);

        // Ensure DPLC count doesn't exceed mapping count
        if (dplcs.size() > mappings.size()) {
            dplcs = new ArrayList<>(dplcs.subList(0, mappings.size()));
        }
        // Pad DPLC list if shorter than mappings (empty DPLC = reuse previous tiles)
        while (dplcs.size() < mappings.size()) {
            dplcs.add(new SpriteDplcFrame(List.of()));
        }

        int bankSize = S3kSpriteDataLoader.resolveBankSize(dplcs, mappings);
        SpriteAnimationSet animSet = S3kSpriteDataLoader.loadAnimationSet(reader, animAddr, animCount);

        SpriteArtSet artSet = new SpriteArtSet(tiles, mappings, dplcs,
                paletteIndex, baseTile, 1, bankSize, null, animSet);
        PlayerSpriteRenderer renderer = new PlayerSpriteRenderer(artSet);

        shieldArtSets.put(key, artSet);
        dplcRenderers.put(key, renderer);

        LOG.info("Loaded " + key + " art: " + tiles.length + " tiles, "
                + mappings.size() + " mapping frames, " + animCount + " animations");
    }

    /**
     * Builds the spark art set (ROM: DMA to ArtTile_Shield_Sparks).
     * Only loads the 5 spark tiles and animation script — rendering is handled
     * directly by {@link LightningSparkObjectInstance} via renderPatternWithId,
     * matching the ROM where spark art is DMA-loaded once (not managed by PLCLoad_Shields).
     */
    private void buildSparkArtSet(RomByteReader reader) throws IOException {
        Pattern[] sparkTiles = S3kSpriteDataLoader.loadArtTiles(reader,
                Sonic3kConstants.ART_UNC_LIGHTNING_SHIELD_SPARKS_ADDR,
                Sonic3kConstants.ART_UNC_LIGHTNING_SHIELD_SPARKS_SIZE);

        // Animation script matching ROM Ani_LightningShield script 1:
        // delay=0, pattern [0,1,2] × 6 then [0,1], endAction=LOOP
        List<Integer> sparkFrames = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sparkFrames.add(0);
            sparkFrames.add(1);
            sparkFrames.add(2);
        }
        sparkFrames.add(0);
        sparkFrames.add(1);
        SpriteAnimationScript sparkScript = new SpriteAnimationScript(
                0, sparkFrames, SpriteAnimationEndAction.LOOP, 0);
        SpriteAnimationSet sparkAnimSet = new SpriteAnimationSet();
        sparkAnimSet.addScript(0, sparkScript);

        // Only artTiles and animationSet are used; other fields are placeholders.
        SpriteArtSet sparkArtSet = new SpriteArtSet(sparkTiles, List.of(), List.of(),
                0, 0, 1, 0, null, sparkAnimSet);
        shieldArtSets.put(Sonic3kObjectArtKeys.LIGHTNING_SPARK, sparkArtSet);

        LOG.info("Built lightning spark art: " + sparkTiles.length + " tiles");
    }

    /** Returns the DPLC-driven renderer for a shield type, or null. */
    public PlayerSpriteRenderer getShieldDplcRenderer(String key) {
        return dplcRenderers.get(key);
    }

    /** Returns the art set for a shield type, or null. */
    public SpriteArtSet getShieldArtSet(String key) {
        return shieldArtSets.get(key);
    }

    @Override
    public void registerLevelTileArt(Level level, int zoneIndex) {
        registerLevelArtSheets(level, zoneIndex);
    }

    @Override
    public void reloadStandaloneArtForActTransition(int zoneIndex) {
        reloadStandaloneRegistryForActTransition(zoneIndex);
        scheduleEnemyKosArt(zoneIndex, currentActIndex);
        issueRuntimeArtAdmissionLease(RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
    }

    private void reloadStandaloneRegistryForActTransition(int zoneIndex) {
        // Refresh act index from LevelManager (act has changed since initial load)
        currentActIndex = GameServices.level().getCurrentAct();

        // Get the new act's art plan and reload standalone entries.
        // Shared entries (explosion, monitor, shields, etc.) are already loaded
        // and will simply be re-registered with the same key, which is harmless.
        Sonic3kPlcArtRegistry.ZoneArtPlan plan =
                Sonic3kPlcArtRegistry.getPlan(zoneIndex, currentActIndex);
        loadStandaloneFromRegistry(plan);
        if (zoneIndex == Sonic3kZoneIds.ZONE_CNZ) {
            loadCnzTraversalArt();
        }

        LOG.info("Reloaded standalone art for zone " + zoneIndex
                + " act " + currentActIndex);
    }

    @Override
    public RuntimeArtAdmissionLease prepareRuntimeArtForActTransition(
            int zoneIndex, RuntimeArtAdmissionPolicy policy) {
        if (policy == RuntimeArtAdmissionPolicy.PRESERVE_CURRENT) {
            reloadStandaloneRegistryForActTransition(zoneIndex);
            return null;
        }

        reloadStandaloneRegistryForActTransition(zoneIndex);
        // A resource-owner reload only transfers the work that its handoff
        // prepared.  The native ICZ1BGE transition enters the target through
        // Load_Sprites/Process_Sprites; it does not run LoadEnemyArt.  Any
        // target-owned Queue_Kos_Module request (for example the Starpost
        // bonus-art request made while the initial target objects execute)
        // must therefore enter the FIFO from that object owner, rather than
        // being replaced by a speculative zone enemy batch here.
        if (policy == RuntimeArtAdmissionPolicy.RESOURCE_HANDOFF_OWNER) {
            scheduleEnemyKosArt(zoneIndex, currentActIndex);
            pendingEnemyKosEntries = List.of();
        } else {
            scheduleEnemyKosArt(zoneIndex, currentActIndex);
        }
        RuntimeArtAdmissionOwnerKind ownerKind = switch (policy) {
            case IMMEDIATE -> RuntimeArtAdmissionOwnerKind.IMMEDIATE;
            case TITLE_OWNER -> RuntimeArtAdmissionOwnerKind.TITLE_OWNER;
            case RESOURCE_HANDOFF_OWNER ->
                    RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER;
            case PRESERVE_CURRENT -> throw new IllegalStateException(
                    "preserve-current admission does not issue a lease");
        };
        RuntimeArtAdmissionLease lease = issueRuntimeArtAdmissionLease(ownerKind);
        if (policy == RuntimeArtAdmissionPolicy.IMMEDIATE) {
            consumeRuntimeArtAdmission(lease, ownerKind);
        }
        return lease;
    }

    /**
     * Issues the title owner's next runtime-art lease after a later in-level
     * title. The results object owns this handoff. The title-card manager
     * remains a strict consumer of the exact lease and never selects or
     * fabricates one.
     *
     * <p>If the previous owner still has admitted work, it continues through
     * the provider queue independently. Once that work has retired, the ROM's
     * title-owner {@code LoadEnemyArt} dispatch creates the next zone/act enemy
     * batch here instead of leaving the queue empty.
     */
    @Override
    public void prepareRuntimeArtForInLevelTitleCard() {
        if (runtimeArtAdmissionLease == null) {
            throw new IllegalStateException("runtime-art admission lease is missing");
        }
        if (!runtimeArtAdmissionConsumed) {
            if (runtimeArtAdmissionLease.ownerKind()
                    == RuntimeArtAdmissionOwnerKind.TITLE_OWNER) {
                return;
            }
            throw new IllegalStateException(
                    "runtime-art admission is still owned by "
                            + runtimeArtAdmissionLease.ownerKind());
        }

        boolean previousBatchStillAdmitted = !pendingEnemyKosEntries.isEmpty()
                || !enemyKosHandles.isEmpty();
        if (!previousBatchStillAdmitted) {
            scheduleEnemyKosArt(currentZoneIndex, currentActIndex);
            issueRuntimeArtAdmissionLease(RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
            return;
        }

        // A carried transition can still own a prepared or active batch. Keep
        // that exact work independent from the presentation lease; the title
        // completion callback will arm it at the native LoadEnemyArt boundary.
        issueRuntimeArtAdmissionLease(
                RuntimeArtAdmissionOwnerKind.TITLE_OWNER,
                fingerprintEnemyKosBatch(List.of()));
    }

    /**
     * Registers object sprite sheets that use level patterns.
     * Must be called AFTER the level is loaded.
     *
     * @param level the loaded level
     * @param zoneIndex the zone index
     */
    public void registerLevelArtSheets(Level level, int zoneIndex) {
        if (level == null) return;

        // Refresh act index from LevelManager — required for act transitions
        // (e.g. AIZ1→AIZ2 seamless reload) where the act changed after initial load.
        currentActIndex = GameServices.level().getCurrentAct();

        PreparedLevelArt prepared = level instanceof Sonic3kLevel s3kLevel
                ? s3kLevel.takePreparedLevelArt()
                : null;
        if (prepared == null
                || prepared.zoneIndex() != zoneIndex
                || prepared.actIndex() != currentActIndex) {
            RomByteReader reader = null;
            try {
                Rom rom = GameServices.rom().getRom();
                if (rom != null) reader = RomByteReader.fromRom(rom);
            } catch (IOException e) {
                LOG.warning("Failed to create RomByteReader for level art: " + e.getMessage());
            }
            prepared = buildLevelArtSheets(level, zoneIndex, currentActIndex, reader);
        }
        registerPreparedLevelArt(prepared);

        LOG.info("Sonic3kObjectArtProvider registered " + rendererKeys.size()
                + " level-art sheets for zone " + zoneIndex);
    }

    /** One level-art sheet built ahead of registration, with the level tiles it depends on. */
    public record PreparedLevelArtSheet(String key,
                                        ObjectSpriteSheet sheet,
                                        List<Sonic3kPlcLoader.TileRange> tileRanges) {
    }

    /**
     * The registry-driven level-art sheets for one zone/act, built from a
     * level's pattern data without touching provider state, so a prepared
     * level load can build them off the frame thread.
     */
    public record PreparedLevelArt(int zoneIndex, int actIndex, List<PreparedLevelArtSheet> sheets) {
    }

    /**
     * Builds the level-art sheets {@link Sonic3kPlcArtRegistry} lists for
     * {@code zoneIndex}/{@code actIndex} from {@code level}'s patterns. Pure:
     * reads the level and the ROM only, so it may run off the frame thread.
     */
    public static PreparedLevelArt buildLevelArtSheets(Level level,
                                                       int zoneIndex,
                                                       int actIndex,
                                                       RomByteReader reader) {
        Sonic3kObjectArt art = new Sonic3kObjectArt(level, reader);
        Sonic3kPlcArtRegistry.ZoneArtPlan plan =
                Sonic3kPlcArtRegistry.getPlan(zoneIndex, actIndex);
        List<PreparedLevelArtSheet> sheets = new ArrayList<>();
        for (Sonic3kPlcArtRegistry.LevelArtEntry entry : plan.levelArt()) {
            ObjectSpriteSheet sheet;
            if (entry.builderName() != null) {
                sheet = invokeBuilder(art, entry.builderName(), entry.artTileBase());
            } else if (entry.mappingAddr() > 0 && entry.frameFilter() != null) {
                sheet = art.buildLevelArtSheetFromRomFiltered(
                        entry.mappingAddr(), entry.artTileBase(), entry.palette(),
                        entry.frameFilter(), entry.mappingFormat(), entry.mappingFrameCount());
            } else if (entry.mappingAddr() > 0) {
                sheet = art.buildLevelArtSheetFromRom(
                        entry.mappingAddr(), entry.artTileBase(), entry.palette(), entry.mappingFormat(),
                        entry.mappingFrameCount());
            } else {
                LOG.warning("LevelArtEntry '" + entry.key() + "' has no builder or mapping addr");
                continue;
            }
            List<Sonic3kPlcLoader.TileRange> ranges = List.of();
            if (sheet != null && art.getLastBuildStartTile() >= 0) {
                ranges = art.getLastBuildTileRanges();
                if (ranges.isEmpty()) {
                    ranges = List.of(new Sonic3kPlcLoader.TileRange(
                            art.getLastBuildStartTile(), art.getLastBuildTileCount()));
                }
            }
            art.clearLastBuildTileRanges();
            sheets.add(new PreparedLevelArtSheet(entry.key(), sheet, ranges));
        }
        return new PreparedLevelArt(zoneIndex, actIndex, List.copyOf(sheets));
    }

    private void registerPreparedLevelArt(PreparedLevelArt prepared) {
        for (PreparedLevelArtSheet entry : prepared.sheets()) {
            registerSheet(entry.key(), entry.sheet());
            if (entry.sheet() != null && !entry.tileRanges().isEmpty()) {
                levelArtTileRanges.put(entry.key(), entry.tileRanges());
            }
        }
    }

    private static ObjectSpriteSheet invokeBuilder(Sonic3kObjectArt art, String builderName, int artTileBase) {
        return switch (builderName) {
            case "buildSpikesSheet" -> art.buildSpikesSheet(artTileBase);
            case "buildSpringVerticalSheet" -> art.buildSpringVerticalSheet(artTileBase);
            case "buildSpringVerticalYellowSheet" -> art.buildSpringVerticalYellowSheet(artTileBase);
            case "buildSpringHorizontalSheet" -> art.buildSpringHorizontalSheet(artTileBase);
            case "buildSpringHorizontalYellowSheet" -> art.buildSpringHorizontalYellowSheet(artTileBase);
            case "buildSpringDiagonalSheet" -> art.buildSpringDiagonalSheet(artTileBase);
            case "buildSpringDiagonalYellowSheet" -> art.buildSpringDiagonalYellowSheet(artTileBase);
            case "buildAiz1TreeSheet" -> art.buildAiz1TreeSheet(artTileBase);
            case "buildAiz1ZiplinePegSheet" -> art.buildAiz1ZiplinePegSheet(artTileBase);
            case "buildAizForegroundPlantSheet" -> art.buildAizForegroundPlantSheet(artTileBase);
            case "buildAnimatedStillSpritesSheet" -> art.buildAnimatedStillSpritesSheet(artTileBase);
            case "buildAnimStillLrzD3Sheet" -> art.buildAnimStillLrzD3Sheet(artTileBase);
            case "buildAnimStillLrz2Sheet" -> art.buildAnimStillLrz2Sheet(artTileBase);
            case "buildAnimStillSozSheet" -> art.buildAnimStillSozSheet(artTileBase);
            case "buildFlippingBridgeSheet" -> art.buildFlippingBridgeSheet(artTileBase);
            case "buildDrawBridgeSheet" -> art.buildDrawBridgeSheet(artTileBase);
            case "buildDisappearingFloorSheet" -> art.buildDisappearingFloorSheet(artTileBase);
            case "buildDisappearingFloorBorderSheet" -> art.buildDisappearingFloorBorderSheet(artTileBase);
            case "buildCnzBalloonSheet" -> art.buildCnzBalloonSheet();
            case "buildCnzCannonSheet" -> art.buildCnzCannonSheet();
            case "buildCnzRisingPlatformSheet" -> art.buildCnzRisingPlatformSheet();
            case "buildCnzTrapDoorSheet" -> art.buildCnzTrapDoorSheet();
            case "buildCnzHoverFanSheet" -> art.buildCnzHoverFanSheet();
            case "buildCnzCylinderSheet" -> art.buildCnzCylinderSheet();
            case "buildCnzBumperSheet" -> art.buildCnzBumperSheet();
            case "buildCnzVacuumTubeSheet" -> art.buildCnzVacuumTubeSheet();
            case "buildCnzSpiralTubeSheet" -> art.buildCnzSpiralTubeSheet();
            default -> {
                LOG.warning("Unknown builder: " + builderName);
                yield null;
            }
        };
    }

    /**
     * Registers a level-art sheet and records its level tile range for PLC refresh.
     */
    private void registerLevelArtSheet(String key, ObjectSpriteSheet sheet, Sonic3kObjectArt art) {
        registerSheet(key, sheet);
        if (sheet != null && art.getLastBuildStartTile() >= 0) {
            List<Sonic3kPlcLoader.TileRange> ranges = art.getLastBuildTileRanges();
            if (ranges.isEmpty()) {
                ranges = List.of(new Sonic3kPlcLoader.TileRange(
                        art.getLastBuildStartTile(), art.getLastBuildTileCount()));
            }
            levelArtTileRanges.put(key, ranges);
        }
        art.clearLastBuildTileRanges();
    }

    private void loadEggCapsuleArt() throws IOException {
        Rom rom = GameServices.rom().getRom();
        if (rom == null) {
            return;
        }
        RomByteReader reader = RomByteReader.fromRom(rom);
        Sonic3kObjectArt art = new Sonic3kObjectArt(null, reader);
        registerSheet(Sonic3kObjectArtKeys.EGG_CAPSULE, art.loadEggCapsuleSheet(rom));
    }

    /**
     * Loads AIZ miniboss art via PLC 0x5A, matching the ROM's Load_PLC call.
     * PLC entries provide both the Nemesis art ROM addresses and VRAM tile destinations,
     * eliminating the need for hardcoded art/tile constants.
     *
     * <p>On real hardware, the boss PLC decompresses into shared VRAM, overwriting
     * spike/spring tiles at 0x0494+ (the boss fire art at 0x0482 overlaps). We avoid
     * this by decompressing into standalone Pattern[] arrays instead of the level's
     * pattern buffer. The ROM restores spike/spring art via Load_PLC(PLC_Monitors)
     * after the boss is defeated; with standalone arrays we don't need that.
     *
     * <p>PLC 0x5A contains 4 entries:
     * <ol start="0">
     *   <li>ArtNem_AIZMiniboss → ArtTile_AIZMiniboss (main boss)</li>
     *   <li>ArtNem_AIZMinibossSmall → ArtTile_AIZMinibossSmall (debris)</li>
     *   <li>ArtNem_AIZBossFire → ArtTile_AIZBossFire (flames)</li>
     *   <li>ArtNem_BossExplosion → ArtTile_BossExplosion2 (shared explosion)</li>
     * </ol>
     */
    private void loadAizMinibossArtFromPlc() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);

            // Parse PLC 0x5A to get art ROM addresses (no level application)
            PlcDefinition plc = Sonic3kPlcLoader.parsePlc(rom, Sonic3kConstants.PLC_AIZ_MINIBOSS);
            List<PlcEntry> entries = plc.entries();
            if (entries.size() < 3) {
                LOG.warning("PLC 0x5A has fewer than 3 entries (" + entries.size() + "), skipping miniboss art");
                return;
            }

            // Decompress each entry's Nemesis art into standalone Pattern[] arrays
            // via PlcParser.decompressEntry() — not into the level's pattern buffer,
            // to avoid overwriting spike/spring tiles at 0x0494+
            List<Pattern[]> decompressed = PlcParser.decompressAll(rom, plc);

            // Entry 0: main boss art
            registerSheet(Sonic3kObjectArtKeys.AIZ_MINIBOSS,
                    buildSheetFromPatterns(decompressed.get(0), reader,
                            Sonic3kConstants.MAP_AIZ_MINIBOSS_ADDR, 1));

            // Entry 1: small debris art
            registerSheet(Sonic3kObjectArtKeys.AIZ_MINIBOSS_SMALL,
                    buildSheetFromPatterns(decompressed.get(1), reader,
                            Sonic3kConstants.MAP_AIZ_MINIBOSS_SMALL_ADDR, 1));

            // Entry 2: flame art
            registerSheet(Sonic3kObjectArtKeys.AIZ_MINIBOSS_FLAME,
                    buildSheetFromPatterns(decompressed.get(2), reader,
                            Sonic3kConstants.MAP_AIZ_MINIBOSS_FLAME_ADDR, 0));

            // Entry 3: boss explosion art (ArtNem_BossExplosion → ArtTile_BossExplosion2)
            if (decompressed.size() >= 4) {
                registerSheet(ObjectArtKeys.BOSS_EXPLOSION,
                        buildSheetFromPatterns(decompressed.get(3), reader,
                                Sonic3kConstants.MAP_BOSS_EXPLOSION_ADDR, 0));
            }

            LOG.info(String.format("Loaded AIZ miniboss art via PLC 0x5A (standalone): " +
                            "main=%d tiles, small=%d tiles, flame=%d tiles, explosion=%s",
                    decompressed.get(0).length,
                    decompressed.get(1).length,
                    decompressed.get(2).length,
                    decompressed.size() >= 4 ? decompressed.get(3).length + " tiles" : "n/a"));
        } catch (IOException e) {
            LOG.warning("Failed to load AIZ miniboss art from PLC: " + e.getMessage());
        }
    }

    /**
     * Loads the shared boss explosion sheet directly from ROM.
     * This keeps non-AIZ bosses from depending on AIZ-specific PLC paths.
     */
    private void loadSharedBossExplosionArt() {
        if (sheets.containsKey(ObjectArtKeys.BOSS_EXPLOSION)) {
            return;
        }
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);
            Pattern[] patterns = PatternDecompressor.nemesis(rom, Sonic3kConstants.ART_NEM_BOSS_EXPLOSION_ADDR);
            registerSheet(ObjectArtKeys.BOSS_EXPLOSION,
                    buildSheetFromPatterns(patterns, reader, Sonic3kConstants.MAP_BOSS_EXPLOSION_ADDR, 0));
        } catch (IOException e) {
            LOG.warning("Failed to load shared boss explosion art: " + e.getMessage());
        }
    }

    /**
     * Ensures the shared boss explosion art is registered.
     * Used by bosses in zones that do not preload the explosion sheet during zone art setup.
     *
     * @return true if the shared boss explosion sheet exists after the call
     */
    public boolean ensureBossExplosionArtLoaded() {
        if (sheets.containsKey(ObjectArtKeys.BOSS_EXPLOSION)
                && renderers.containsKey(ObjectArtKeys.BOSS_EXPLOSION)) {
            return true;
        }
        loadSharedBossExplosionArt();
        return sheets.containsKey(ObjectArtKeys.BOSS_EXPLOSION)
                && renderers.containsKey(ObjectArtKeys.BOSS_EXPLOSION);
    }

    /**
     * Loads HCZ miniboss art via PLC 0x5B, matching the ROM's Load_PLC call.
     */
    private void loadHczMinibossArtFromPlc() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);
            PlcDefinition plc = Sonic3kPlcLoader.parsePlc(rom, Sonic3kConstants.PLC_HCZ_MINIBOSS);
            List<Pattern[]> decompressed = PlcParser.decompressAll(rom, plc);
            if (decompressed.isEmpty() || decompressed.get(0).length == 0) {
                LOG.warning("HCZ miniboss PLC produced no art");
                return;
            }
            // ROM-parsed mappings use the offset-table size word to derive the
            // frame count, so duplicate offsets (e.g. frames 25/26 both pointing
            // at the blank Frame_362BB0) yield duplicate frame entries naturally
            // and no count-correction workaround is needed.
            registerSheet(Sonic3kObjectArtKeys.HCZ_MINIBOSS,
                    buildSheetFromPatterns(decompressed.get(0), reader,
                            Sonic3kConstants.MAP_HCZ_MINIBOSS_ADDR, 1));
            LOG.info("Loaded HCZ miniboss art via PLC 0x5B: "
                    + decompressed.get(0).length + " tiles");
        } catch (IOException e) {
            LOG.warning("Failed to load HCZ miniboss art from PLC: " + e.getMessage());
        }
    }

    /**
     * Loads HCZ end boss art via PLC 0x6C, matching the ROM's Load_PLC call.
     * PLC entries: 0=boss body, 1=Robotnik ship, 2=boss explosion, 3=egg capsule.
     * Loads entry 0 (boss body) and, when not yet registered, entry 1 (Robotnik
     * ship); explosion and egg capsule art are loaded separately.
     */
    private void loadHczEndBossArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);
            PlcDefinition plc = Sonic3kPlcLoader.parsePlc(rom, Sonic3kConstants.PLC_HCZ_END_BOSS);
            List<Pattern[]> decompressed = PlcParser.decompressAll(rom, plc);
            if (decompressed.isEmpty() || decompressed.get(0).length == 0) {
                LOG.warning("HCZ end boss PLC produced no art");
                return;
            }

            // Entry 0: Boss body art (Map_HCZEndBoss) — ROM-parsed.
            registerSheet(Sonic3kObjectArtKeys.HCZ_END_BOSS,
                    buildSheetFromPatterns(decompressed.get(0), reader,
                            Sonic3kConstants.MAP_HCZ_END_BOSS_ADDR, 1));

            // Entry 1: Robotnik ship art (ArtNem_RobotnikShip + Map_RobotnikShip)
            if (decompressed.size() >= 2 && decompressed.get(1).length > 0
                    && sheets.get(Sonic3kObjectArtKeys.ROBOTNIK_SHIP) == null) {
                registerSheet(Sonic3kObjectArtKeys.ROBOTNIK_SHIP,
                        buildSheetFromPatterns(decompressed.get(1), reader,
                                Sonic3kConstants.MAP_ROBOTNIK_SHIP_ADDR, 0));
            }
        } catch (IOException e) {
            LOG.warning("Failed to load HCZ end boss art: " + e.getMessage());
        }
    }

    /**
     * Registers the dedicated CNZ teleporter beam art used by
     * {@code Obj_CNZTeleporter} and shared {@code Obj_TeleporterBeam} in CNZ.
     *
     * <p>The ROM queues {@code ArtKosM_CNZTeleport} directly instead of using a
     * PLC entry, so this stays a dedicated load path rather than being folded
     * into the shared standalone-PLC registry.
     */
    private void loadCnzTeleporterArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);
            Sonic3kObjectArt art = new Sonic3kObjectArt(null, reader);
            registerSheet(Sonic3kObjectArtKeys.CNZ_TELEPORTER, art.loadCnzTeleporterSheet(rom));
        } catch (IOException e) {
            LOG.warning("Failed to load CNZ teleporter art: " + e.getMessage());
        }
    }

    /** Mirrors Obj_CNZTeleporter's Queue_Kos_Module request. */
    public void queueCnzTeleporterArt() {
        if (cnzTeleporterArtState == RuntimeArtState.IDLE
                || cnzTeleporterArtState == RuntimeArtState.FAILED) {
            cnzTeleporterArtState = RuntimeArtState.PENDING;
        }
    }

    public boolean isCnzTeleporterArtPending() {
        return cnzTeleporterArtState == RuntimeArtState.PENDING;
    }

    public boolean isCnzTeleporterArtComplete() {
        return cnzTeleporterArtState == RuntimeArtState.COMPLETE;
    }

    /** Mirrors {@code Obj_CNZEndBoss -> Load_PLC($6E)}. */
    public void queueCnzEndBossArt() {
        if (cnzEndBossArtState == RuntimeArtState.IDLE
                || cnzEndBossArtState == RuntimeArtState.FAILED) {
            cnzEndBossArtState = RuntimeArtState.PENDING;
        }
    }

    public boolean isCnzEndBossArtPending() {
        return cnzEndBossArtState == RuntimeArtState.PENDING;
    }

    public boolean isCnzEndBossArtComplete() {
        return cnzEndBossArtState == RuntimeArtState.COMPLETE;
    }

    /**
     * Mirrors {@code sub_2D3C8}'s runtime {@code Queue_Kos_Module} request
     * after a StarPost creates its four bonus stars.
     *
     * <p>The StarPost does not poll the job after submission. The ROM's global
     * module FIFO remains the owner, so this session-owned provider retains
     * and claims the handle while other ROM consumers can observe the shared
     * queue as non-empty.
     */
    public void queueStarPostBonusArt(int sourceAddress) {
        if (sourceAddress != Sonic3kConstants.ART_KOSM_STARPOST_STARS1_ADDR
                && sourceAddress
                != Sonic3kConstants.ART_KOSM_STARPOST_STARS2_ADDR
                && sourceAddress
                != Sonic3kConstants.ART_KOSM_STARPOST_STARS3_ADDR) {
            throw new IllegalArgumentException(
                    "unsupported StarPost bonus-art source: 0x"
                            + Integer.toHexString(sourceAddress));
        }
        try {
            Rom rom = GameServices.rom().getRom();
            if (enemyKosQueue == null) {
                enemyKosQueue =
                        S3kRuntimeArtCoordinator.current().moduleQueue();
            }
            // Preserve native FIFO order if activation occurs while the
            // title-retired enemy group is waiting to submit.
            for (EnemyKosEntry entry : pendingEnemyKosEntries) {
                enemyKosHandles.add(enemyKosQueue.queue(
                        rom, entry.source(), entry.destinationTile()));
            }
            pendingEnemyKosEntries = List.of();
            enemyKosHandles.add(enemyKosQueue.queue(
                    rom,
                    sourceAddress,
                    Sonic3kConstants.ARTTILE_STARPOST + 8));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to queue S3K StarPost bonus art", e);
        }
    }

    /**
     * Mirrors {@code SSEntryRing_Display}'s {@code loc_6196A} tail, which
     * re-queues {@code ArtKosM_BadnikExplosion} to {@code ArtTile_Explosion}
     * when a special-stage entry ring retires (sonic3k.asm:128448-128490).
     *
     * <p>The ring is deleted on the same frame and never polls the job, so —
     * exactly as for the StarPost bonus stars above — the ROM's global module
     * FIFO stays the owner and this session-owned provider retains and claims
     * the handle.
     */
    public void queueBadnikExplosionArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (enemyKosQueue == null) {
                enemyKosQueue =
                        S3kRuntimeArtCoordinator.current().moduleQueue();
            }
            for (EnemyKosEntry entry : pendingEnemyKosEntries) {
                enemyKosHandles.add(enemyKosQueue.queue(
                        rom, entry.source(), entry.destinationTile()));
            }
            pendingEnemyKosEntries = List.of();
            enemyKosHandles.add(enemyKosQueue.queue(
                    rom,
                    Sonic3kConstants.ART_KOSM_BADNIK_EXPLOSION_ADDR,
                    Sonic3kConstants.ARTTILE_EXPLOSION));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to queue S3K badnik explosion art", e);
        }
    }

    @Override
    public void processRuntimeArtQueue() {
        boolean registeredRuntimeSheet = false;
        advanceTitleCardTeardown();
        if (enemyKosArmOnNextRuntimePass) {
            // The in-level card owner's LoadEnemyArt dispatch is the level
            // frame after the manager's top-of-frame COMPLETE transition, so
            // the submission first becomes eligible on this following pass.
            enemyKosArmOnNextRuntimePass = false;
            enemyKosSubmissionArmed = true;
        }
        processEnemyKosArt();
        if (cnzTeleporterArtState == RuntimeArtState.PENDING) {
            loadCnzTeleporterArt();
            PatternSpriteRenderer renderer = renderers.get(Sonic3kObjectArtKeys.CNZ_TELEPORTER);
            cnzTeleporterArtState = renderer == null
                    ? RuntimeArtState.FAILED : RuntimeArtState.COMPLETE;
            registeredRuntimeSheet |= renderer != null;
        }
        if (cnzEndBossArtState == RuntimeArtState.PENDING) {
            loadCnzEndBossArt();
            PatternSpriteRenderer renderer = renderers.get(Sonic3kObjectArtKeys.CNZ_END_BOSS);
            cnzEndBossArtState = renderer == null
                    ? RuntimeArtState.FAILED : RuntimeArtState.COMPLETE;
            registeredRuntimeSheet |= renderer != null;
        }
        if (registeredRuntimeSheet) {
            // Runtime registration happens after the level-load cache pass.
            ensurePatternsCached(GameServices.graphics(), PatternAtlasRange.OBJECTS.base());
            if (cnzTeleporterArtState == RuntimeArtState.COMPLETE
                    && !renderers.get(Sonic3kObjectArtKeys.CNZ_TELEPORTER).isReady()) {
                cnzTeleporterArtState = RuntimeArtState.FAILED;
            }
            if (cnzEndBossArtState == RuntimeArtState.COMPLETE
                    && !renderers.get(Sonic3kObjectArtKeys.CNZ_END_BOSS).isReady()) {
                cnzEndBossArtState = RuntimeArtState.FAILED;
            }
        }
    }

    private void scheduleEnemyKosArt(int zoneIndex, int actIndex) {
        enemyKosHandles.clear();
        enemyKosQueue = null;
        enemyKosSubmissionArmed = false;
        enemyKosArmOnNextRuntimePass = false;
        titleCardTeardown = null;
        pendingEnemyKosEntries = switch (zoneIndex) {
            case Sonic3kZoneIds.ZONE_AIZ -> List.of(
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_AIZ_MONKEY_DUDE_ADDR,
                            Sonic3kConstants.ARTTILE_AIZ_MONKEY_DUDE),
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_AIZ_BLOOMINATOR_ADDR,
                            Sonic3kConstants.ARTTILE_AIZ_BLOOMINATOR),
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_AIZ_CATERKILLER_JR_ADDR,
                            Sonic3kConstants.ARTTILE_AIZ_CATERKILLER_JR));
            case Sonic3kZoneIds.ZONE_HCZ -> actIndex == 0
                    ? List.of(
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_HCZ_BLASTOID_ADDR,
                                    Sonic3kConstants.ARTTILE_HCZ_BLASTOID_JAWZ),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_HCZ_TURBO_SPIKER_ADDR,
                                    Sonic3kConstants.ARTTILE_HCZ_TURBO_SPIKER),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_HCZ_MEGA_CHOPPER_ADDR,
                                    Sonic3kConstants.ARTTILE_HCZ_MEGA_CHOPPER),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_HCZ_POINTDEXTER_ADDR,
                                    Sonic3kConstants.ARTTILE_HCZ_POINTDEXTER))
                    : List.of(
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_HCZ_JAWZ_ADDR,
                                    Sonic3kConstants.ARTTILE_HCZ_BLASTOID_JAWZ),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_HCZ_TURBO_SPIKER_ADDR,
                                    Sonic3kConstants.ARTTILE_HCZ_TURBO_SPIKER),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_HCZ_MEGA_CHOPPER_ADDR,
                                    Sonic3kConstants.ARTTILE_HCZ_MEGA_CHOPPER),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_HCZ_POINTDEXTER_ADDR,
                                    Sonic3kConstants.ARTTILE_HCZ_POINTDEXTER));
            case Sonic3kZoneIds.ZONE_MGZ -> actIndex == 0
                    ? List.of(
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_MGZ_SPIKER_ADDR,
                                    Sonic3kConstants.ARTTILE_MGZ_SPIKER),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_MGZ_MINIBOSS_ADDR,
                                    Sonic3kConstants.ARTTILE_MGZ_MINIBOSS),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_MGZ_ENDBOSS_DEBRIS_ADDR,
                                    Sonic3kConstants.ARTTILE_MGZ_ENDBOSS_DEBRIS))
                    : List.of(
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_MGZ_SPIKER_ADDR,
                                    Sonic3kConstants.ARTTILE_MGZ_SPIKER),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_MGZ_MANTIS_ADDR,
                                    Sonic3kConstants.ARTTILE_MGZ_MANTIS));
            case Sonic3kZoneIds.ZONE_CNZ -> List.of(
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_CNZ_SPARKLE_ADDR,
                            Sonic3kConstants.ARTTILE_CNZ_SPARKLE),
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_CNZ_BATBOT_ADDR,
                            Sonic3kConstants.ARTTILE_CNZ_BATBOT),
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_CLAMER_SHOT_ADDR,
                            Sonic3kConstants.ARTTILE_CNZ_CLAMER_SHOT),
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_CNZ_BALLOON_ADDR,
                            Sonic3kConstants.ARTTILE_CNZ_BALLOON_PLC));
            // ROM PLCKosM_ICZ queues these entries in this order from
            // LoadEnemyArt after the title-card owner retires.
            // docs/skdisasm/sonic3k.asm:62287-62300, 64392-64395
            case Sonic3kZoneIds.ZONE_ICZ -> List.of(
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_ICZ_SNOWDUST_ADDR,
                            Sonic3kConstants.ARTTILE_ICZ_SNOWDUST),
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_ICZ_STAR_POINTER_ADDR,
                            Sonic3kConstants.ARTTILE_ICZ_STAR_POINTER));
            // ROM PLCKosM_LBZ queues these entries in this order from
            // LoadEnemyArt after the title-card owner retires.
            // docs/skdisasm/sonic3k.asm:62287-62300, 64397-64402
            case Sonic3kZoneIds.ZONE_LBZ -> List.of(
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_SNALE_BLASTER_ADDR,
                            Sonic3kConstants.ARTTILE_SNALE_BLASTER),
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_ORBINAUT_ADDR,
                            Sonic3kConstants.ARTTILE_ORBINAUT),
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_RIBOT_ADDR,
                            Sonic3kConstants.ARTTILE_RIBOT),
                    new EnemyKosEntry(
                            Sonic3kConstants.ART_KOSM_CORKEY_ADDR,
                            Sonic3kConstants.ARTTILE_CORKEY));
            // ROM PLCKosM_MHZ1 / PLCKosM_MHZ2 queue these entries in this
            // order from LoadEnemyArt; act 2 leads with the Cluckoid arrow.
            // docs/skdisasm/sonic3k.asm:64331-64332, 64404-64415
            case Sonic3kZoneIds.ZONE_MHZ -> actIndex == 0
                    ? List.of(
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_MADMOLE_ADDR,
                                    Sonic3kConstants.ARTTILE_MADMOLE),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_MUSHMEANIE_ADDR,
                                    Sonic3kConstants.ARTTILE_MUSHMEANIE),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_DRAGONFLY_ADDR,
                                    Sonic3kConstants.ARTTILE_DRAGONFLY))
                    : List.of(
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_CLUCKOID_ARROW_ADDR,
                                    Sonic3kConstants.ARTTILE_CLUCKOID_ARROW),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_MADMOLE_ADDR,
                                    Sonic3kConstants.ARTTILE_MADMOLE),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_MUSHMEANIE_ADDR,
                                    Sonic3kConstants.ARTTILE_MUSHMEANIE),
                            new EnemyKosEntry(
                                    Sonic3kConstants.ART_KOSM_DRAGONFLY_ADDR,
                                    Sonic3kConstants.ARTTILE_DRAGONFLY));
            default -> List.of();
        };
    }

    RuntimeArtAdmissionLease issueRuntimeArtAdmissionLease(
            RuntimeArtAdmissionOwnerKind ownerKind) {
        return issueRuntimeArtAdmissionLease(
                ownerKind, fingerprintEnemyKosBatch(pendingEnemyKosEntries));
    }

    private RuntimeArtAdmissionLease issueRuntimeArtAdmissionLease(
            RuntimeArtAdmissionOwnerKind ownerKind, long batchFingerprint) {
        runtimeArtAdmissionGeneration++;
        RuntimeArtAdmissionLease lease = new RuntimeArtAdmissionLease(
                runtimeArtAdmissionNextLeaseId++,
                runtimeArtAdmissionGeneration,
                batchFingerprint,
                ownerKind);
        runtimeArtAdmissionLease = lease;
        runtimeArtAdmissionBound = ownerKind != RuntimeArtAdmissionOwnerKind.TITLE_OWNER;
        runtimeArtAdmissionConsumed = false;
        titleCardTeardownLeaseId = -1;
        return lease;
    }

    private static long fingerprintEnemyKosBatch(List<EnemyKosEntry> entries) {
        long hash = 0xcbf29ce484222325L;
        hash ^= entries.size();
        hash *= 0x100000001b3L;
        for (EnemyKosEntry entry : entries) {
            hash ^= Integer.toUnsignedLong(entry.source());
            hash *= 0x100000001b3L;
            hash ^= Integer.toUnsignedLong(entry.destinationTile());
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    @Override
    public RuntimeArtAdmissionLease bindPendingRuntimeArtAdmission(
            RuntimeArtAdmissionOwnerKind ownerKind) {
        if (runtimeArtAdmissionLease == null) {
            throw new IllegalStateException("runtime-art admission lease is missing");
        }
        return bindRuntimeArtAdmission(runtimeArtAdmissionLease.id(), ownerKind);
    }

    @Override
    public RuntimeArtAdmissionLease bindRuntimeArtAdmission(
            long leaseId, RuntimeArtAdmissionOwnerKind ownerKind) {
        RuntimeArtAdmissionLease lease = requireRuntimeArtAdmissionLease(
                leaseId, ownerKind);
        if (runtimeArtAdmissionConsumed) {
            throw new IllegalStateException("runtime-art admission lease is already consumed");
        }
        if (runtimeArtAdmissionBound) {
            throw new IllegalStateException("runtime-art admission lease is already bound");
        }
        runtimeArtAdmissionBound = true;
        return lease;
    }

    @Override
    public RuntimeArtAdmissionLease rebindRuntimeArtAdmission(
            long leaseId, RuntimeArtAdmissionOwnerKind ownerKind) {
        RuntimeArtAdmissionLease lease = requireRuntimeArtAdmissionLease(
                leaseId, ownerKind);
        if (!runtimeArtAdmissionBound) {
            throw new IllegalStateException("runtime-art admission lease is not bound");
        }
        return lease;
    }

    @Override
    public void consumeRuntimeArtAdmission(
            RuntimeArtAdmissionLease lease,
            RuntimeArtAdmissionOwnerKind ownerKind) {
        if (lease == null) {
            throw new IllegalStateException("runtime-art admission lease is missing");
        }
        RuntimeArtAdmissionLease current = requireRuntimeArtAdmissionLease(
                lease.id(), ownerKind);
        if (!current.equals(lease)) {
            throw new IllegalStateException(
                    "runtime-art admission lease generation or batch does not match");
        }
        if (!runtimeArtAdmissionBound) {
            throw new IllegalStateException("runtime-art admission lease is not bound");
        }
        if (runtimeArtAdmissionConsumed) {
            throw new IllegalStateException("runtime-art admission lease is already consumed");
        }
        runtimeArtAdmissionConsumed = true;
        enemyKosSubmissionArmed = true;
    }

    private RuntimeArtAdmissionLease requireRuntimeArtAdmissionLease(
            long leaseId, RuntimeArtAdmissionOwnerKind ownerKind) {
        if (runtimeArtAdmissionLease == null) {
            throw new IllegalStateException("runtime-art admission lease is missing");
        }
        if (runtimeArtAdmissionLease.id() != leaseId) {
            throw new IllegalStateException("runtime-art admission lease is stale");
        }
        if (runtimeArtAdmissionLease.ownerKind() != ownerKind) {
            throw new IllegalStateException("runtime-art admission owner does not match");
        }
        return runtimeArtAdmissionLease;
    }

    private void processEnemyKosArt() {
        if (pendingEnemyKosEntries.isEmpty() && enemyKosHandles.isEmpty()) {
            return;
        }
        if (enemyKosHandles.isEmpty()) {
            if (!enemyKosSubmissionArmed) {
                return;
            }
            var timing = GameServices.hardwareTiming();
            try {
                Rom rom = GameServices.rom().getRom();
                enemyKosQueue = S3kRuntimeArtCoordinator.current().moduleQueue();
                if (!enemyKosQueue.hasCapacityFor(pendingEnemyKosEntries.size())) {
                    return;
                }
                for (EnemyKosEntry entry : pendingEnemyKosEntries) {
                    enemyKosHandles.add(enemyKosQueue.queue(
                            rom, entry.source(), entry.destinationTile()));
                }
                pendingEnemyKosEntries = List.of();
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Unable to queue S3K enemy KosM art", e);
            }
            return;
        }
        if (enemyKosHandles.stream().allMatch(enemyKosQueue::isReady)) {
            S3kKosModuleQueue completedQueue = enemyKosQueue;
            for (HardwareWorkHandle handle : enemyKosHandles) {
                enemyKosQueue.claim(handle);
            }
            enemyKosHandles.clear();
            enemyKosQueue = null;
            completedQueue.allowChildSubmissionAfterHeldAdmission();
        }
    }

    /**
     * Native level setup begins enemy {@code LoadEnemyArt} only after the
     * title-card KosM entries have retired. A ready-but-unclaimed title job is
     * not retirement and must not advance the structural submission ordinal.
     */
    @Override
    public void onTitleCardArtRetired() {
        throw new IllegalStateException(
                "S3K title retirement requires an exact admission lease");
    }

    /**
     * An in-level card presents over live gameplay, and the manager's COMPLETE
     * transition runs at the top of the frame — one dispatch ahead of the
     * native {@code Obj_TitleCardWait2} dispatch that reaches
     * {@code LoadEnemyArt} (docs/skdisasm/sonic3k.asm:62302-62312). Defer the
     * enemy KosM submission to the following runtime-art pass so it lands on
     * the native level frame.
     */
    @Override
    public void onInLevelTitleCardCompleted(RuntimeArtAdmissionLease lease) {
        consumeRuntimeArtAdmission(
                lease, RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
        titleCardTeardown = null;
        enemyKosSubmissionArmed = false;
        enemyKosArmOnNextRuntimePass = true;
    }

    /**
     * Begins modelling the title-card owner's remaining ROM lifetime instead of
     * retiring its art immediately.
     *
     * <p>A skipped presentation removes only the locked display loop. The owner
     * object still runs {@code Obj_TitleCardWait2}'s {@code objoff_2E} countdown
     * and then drains its card elements before {@code loc_2D8CA} reaches
     * {@code LoadEnemyArt} ({@code docs/skdisasm/sonic3k.asm:62249-62261},
     * {@code 62295-62301}).
     */
    @Override
    public void onTitleCardPresentationSkipped() {
        RuntimeArtAdmissionLease lease = bindPendingRuntimeArtAdmission(
                RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
        enemyKosSubmissionArmed = false;
        titleCardTeardownLeaseId = lease.id();
        titleCardTeardown =
                new com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardTeardownModel();
    }

    /**
     * Re-queues the current zone/act's enemy KosM archives, matching a
     * mid-level ROM {@code jsr (LoadEnemyArt).l}
     * ({@code docs/skdisasm/sonic3k.asm:64281-64313}): the caller's object runs
     * {@code Queue_Kos_Module} for every {@code PLCKosM_*} entry during its own
     * execution frame, so the submissions happen immediately rather than
     * waiting for the next {@link #processRuntimeArtQueue()} pump.
     *
     * <p>Used by {@code HCZGeyser_ReloadEnemyArtAndDelete}
     * ({@code docs/skdisasm/sonic3k.asm:65002-65004}), which restores the
     * badnik art the horizontal geyser sheet overwrote before deleting itself.
     */
    public void reloadEnemyKosArt() {
        scheduleEnemyKosArt(currentZoneIndex, currentActIndex);
        enemyKosSubmissionArmed = true;
        processEnemyKosArt();
    }

    /**
     * Runs one level frame of the modelled title-card owner and its children.
     *
     * <p>{@code Obj_TitleCard} creates its card elements through
     * {@code CreateNewSprite4}, which scans forward from the creator's own slot
     * ({@code docs/skdisasm/sonic3k.asm:37894-37919}), so every element lives in
     * a higher {@code Dynamic_object_RAM} slot and {@code ExecuteObjects} runs
     * the owner before its children. After {@code Draw_Sprite} records an
     * off-screen result, the child's following dispatch sees the clear render
     * flag and decrements {@code objoff_30}
     * ({@code docs/skdisasm/sonic3k.asm:62358-62361}). The owner has already
     * tested {@code objoff_30} in that retirement dispatch and returned through the
     * {@code addq.w #1,objoff_32} branch ({@code 62256-62261}). It first
     * observes the drained counter — and so first reaches {@code loc_2D8CA}'s
     * {@code LoadEnemyArt} ({@code 62295-62301}) — on its following dispatch.
     */
    private void advanceTitleCardTeardown() {
        if (titleCardTeardown == null) {
            return;
        }
        if (titleCardTeardown.isComplete()) {
            consumeTitleCardTeardownLease();
            titleCardTeardown = null;
            return;
        }
        if (titleCardTeardown.tick()) {
            consumeTitleCardTeardownLease();
            titleCardTeardown = null;
        }
    }

    private void consumeTitleCardTeardownLease() {
        RuntimeArtAdmissionLease lease = rebindRuntimeArtAdmission(
                titleCardTeardownLeaseId,
                RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
        consumeRuntimeArtAdmission(lease, RuntimeArtAdmissionOwnerKind.TITLE_OWNER);
        var titleCardProvider = GameServices.module().getTitleCardProvider();
        if (titleCardProvider != null) {
            titleCardProvider
                    .completeOmittedPresentationFreshLevelRuntimeArtHandoff();
        }
        titleCardTeardownLeaseId = -1;
    }

    /**
     * Loads CNZ miniboss art via PLC 0x5D (corrected from prior 0x5C in
     * workstream D), matching the
     * {@code PLC_5C_5D -> ArtTile_CNZMiniboss / ArtNem_CNZMiniboss} path.
     *
     * <p>Entry 0 is the dedicated miniboss body art and entry 1 is the shared
     * boss explosion art used by the concrete CNZ miniboss wrapper.
     */
    private void loadCnzMinibossArtFromPlc() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);
            PlcDefinition plc = Sonic3kPlcLoader.parsePlc(rom, Sonic3kConstants.PLC_CNZ_MINIBOSS);
            List<Pattern[]> decompressed = PlcParser.decompressAll(rom, plc);
            if (decompressed.isEmpty() || decompressed.get(0).length == 0) {
                LOG.warning("CNZ miniboss PLC produced no art");
                return;
            }

            registerSheet(Sonic3kObjectArtKeys.CNZ_MINIBOSS,
                    buildSheetFromPatterns(decompressed.get(0), reader,
                            Sonic3kConstants.MAP_CNZ_MINIBOSS_ADDR, 1));

            if (decompressed.size() >= 2 && decompressed.get(1).length > 0
                    && sheets.get(ObjectArtKeys.BOSS_EXPLOSION) == null) {
                registerSheet(ObjectArtKeys.BOSS_EXPLOSION,
                        buildSheetFromPatterns(decompressed.get(1), reader,
                                Sonic3kConstants.MAP_BOSS_EXPLOSION_ADDR, 0));
            }
        } catch (IOException e) {
            LOG.warning("Failed to load CNZ miniboss art from PLC: " + e.getMessage());
        }
    }

    /**
     * Loads ICZ miniboss art via PLC 0x5F, matching {@code Obj_ICZMiniboss}.
     */
    private void loadIczMinibossArtFromPlc() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);
            PlcDefinition plc = Sonic3kPlcLoader.parsePlc(rom, Sonic3kConstants.PLC_ICZ_MINIBOSS);
            List<Pattern[]> decompressed = PlcParser.decompressAll(rom, plc);
            if (decompressed.isEmpty() || decompressed.get(0).length == 0) {
                LOG.warning("ICZ miniboss PLC produced no art");
                return;
            }

            registerSheet(Sonic3kObjectArtKeys.ICZ_MINIBOSS,
                    buildSheetFromPatterns(decompressed.get(0), reader,
                            Sonic3kConstants.MAP_ICZ_MINIBOSS_ADDR, 1));
            LOG.info("Loaded ICZ miniboss art via PLC 0x5F: "
                    + decompressed.get(0).length + " tiles");
        } catch (IOException e) {
            LOG.warning("Failed to load ICZ miniboss art from PLC: " + e.getMessage());
        }
    }

    /**
     * Loads ICZ end-boss art via PLC 0x70, matching {@code Obj_ICZEndBoss}.
     */
    private void loadIczEndBossArtFromPlc() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);
            PlcDefinition plc = Sonic3kPlcLoader.parsePlc(rom, Sonic3kConstants.PLC_ICZ_END_BOSS);
            List<Pattern[]> decompressed = PlcParser.decompressAll(rom, plc);
            if (decompressed.isEmpty() || decompressed.get(0).length == 0) {
                LOG.warning("ICZ end boss PLC produced no art");
                return;
            }

            registerSheet(Sonic3kObjectArtKeys.ICZ_END_BOSS,
                    buildSheetFromPatterns(decompressed.get(0), reader,
                            Sonic3kConstants.MAP_ICZ_END_BOSS_ADDR, 1));

            if (decompressed.size() >= 2 && decompressed.get(1).length > 0
                    && sheets.get(Sonic3kObjectArtKeys.ROBOTNIK_SHIP) == null) {
                registerSheet(Sonic3kObjectArtKeys.ROBOTNIK_SHIP,
                        buildSheetFromPatterns(decompressed.get(1), reader,
                                Sonic3kConstants.MAP_ROBOTNIK_SHIP_ADDR, 0));
            }

            if (decompressed.size() >= 3 && decompressed.get(2).length > 0
                    && sheets.get(ObjectArtKeys.BOSS_EXPLOSION) == null) {
                registerSheet(ObjectArtKeys.BOSS_EXPLOSION,
                        buildSheetFromPatterns(decompressed.get(2), reader,
                                Sonic3kConstants.MAP_BOSS_EXPLOSION_ADDR, 0));
            }
            LOG.info("Loaded ICZ end boss art via PLC 0x70: "
                    + decompressed.get(0).length + " tiles");
        } catch (IOException e) {
            LOG.warning("Failed to load ICZ end boss art from PLC: " + e.getMessage());
        }
    }

    /**
     * Loads CNZ end-boss art via PLC 0x6E, matching the ROM's setup path.
     *
     * <p>PLC_6E loads the CNZ end-boss body, shared Robotnik ship art, shared
     * boss explosion art, and the shared egg capsule art used by the native
     * CNZ end-boss and post-defeat handoff.
     */
    private void loadCnzEndBossArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);
            PlcDefinition plc = Sonic3kPlcLoader.parsePlc(rom, Sonic3kConstants.PLC_CNZ_END_BOSS);
            List<Pattern[]> decompressed = PlcParser.decompressAll(rom, plc);
            if (decompressed.isEmpty() || decompressed.get(0).length == 0) {
                LOG.warning("CNZ end boss PLC produced no art");
                return;
            }

            registerSheet(Sonic3kObjectArtKeys.CNZ_END_BOSS,
                    buildSheetFromPatterns(decompressed.get(0), reader,
                            Sonic3kConstants.MAP_CNZ_END_BOSS_ADDR, 1));

            if (decompressed.size() >= 2 && decompressed.get(1).length > 0
                    && sheets.get(Sonic3kObjectArtKeys.ROBOTNIK_SHIP) == null) {
                registerSheet(Sonic3kObjectArtKeys.ROBOTNIK_SHIP,
                        buildSheetFromPatterns(decompressed.get(1), reader,
                                Sonic3kConstants.MAP_ROBOTNIK_SHIP_ADDR, 0));
            }

            if (decompressed.size() >= 3 && decompressed.get(2).length > 0
                    && sheets.get(ObjectArtKeys.BOSS_EXPLOSION) == null) {
                registerSheet(ObjectArtKeys.BOSS_EXPLOSION,
                        buildSheetFromPatterns(decompressed.get(2), reader,
                                Sonic3kConstants.MAP_BOSS_EXPLOSION_ADDR, 0));
            }
        } catch (IOException e) {
            LOG.warning("Failed to load CNZ end boss art: " + e.getMessage());
        }
    }

    /**
     * Loads the CNZ traversal object sheets directly from ROM.
     *
     * <p>The visible traversal objects in this slice are ROM-backed through the
     * lock-on offsets published in {@link Sonic3kConstants}. Vacuum Tube and
     * Spiral Tube remain controller-only stubs in this slice and intentionally
     * have no dedicated sheet yet.
     */
    private void loadCnzTraversalArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);
            Level level = GameServices.level().getCurrentLevel();
            Sonic3kObjectArt art = new Sonic3kObjectArt(level, reader);

            registerLevelArtSheet(Sonic3kObjectArtKeys.CNZ_BALLOON, art.buildCnzBalloonSheet(), art);
            registerLevelArtSheet(Sonic3kObjectArtKeys.CNZ_CANNON, art.loadCnzCannonSheet(rom), art);
            registerLevelArtSheet(Sonic3kObjectArtKeys.CNZ_RISING_PLATFORM, art.buildCnzRisingPlatformSheet(), art);
            registerLevelArtSheet(Sonic3kObjectArtKeys.CNZ_TRAP_DOOR, art.buildCnzTrapDoorSheet(), art);
            registerLevelArtSheet(Sonic3kObjectArtKeys.CNZ_LIGHT_BULB, art.buildCnzLightBulbSheet(), art);
            registerLevelArtSheet(Sonic3kObjectArtKeys.CNZ_HOVER_FAN, art.buildCnzHoverFanSheet(), art);
            // Cylinder is the last visible traversal object in this slice and
            // keeps the ROM-parsed Map_CNZCylinder sheet rather than a fallback.
            registerLevelArtSheet(Sonic3kObjectArtKeys.CNZ_CYLINDER, art.buildCnzCylinderSheet(), art);
        } catch (IOException e) {
            LOG.warning("Failed to load CNZ traversal art: " + e.getMessage());
        }
    }

    /**
     * Loads HCZ geyser cutscene art (ArtKosM_HCZGeyserVert + Map_HCZWaterWall).
     * ROM: The post-defeat geyser cutscene uses dedicated geyser art at
     * ArtTile_HCZCutsceneGeyser (0x036B), not the boss body art.
     * Frame 1 of Map_HCZWaterWall is the tall vertical water column (12 pieces).
     * Frames 3-5 are splash sprites.
     */
    private void loadHczGeyserCutsceneArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);
            Pattern[] patterns = decompressKosinskiModuled(rom,
                    Sonic3kConstants.ART_KOSM_HCZ_GEYSER_VERT_ADDR);
            if (patterns == null || patterns.length == 0) {
                LOG.warning("HCZ geyser cutscene art decompression produced no tiles");
                return;
            }
            registerSheet(Sonic3kObjectArtKeys.HCZ_GEYSER_CUTSCENE,
                    buildSheetFromPatterns(patterns, reader,
                            Sonic3kConstants.MAP_HCZ_WATERWALL_ADDR, 2));
            LOG.info("Loaded HCZ geyser cutscene art: " + patterns.length + " tiles");
        } catch (IOException e) {
            LOG.warning("Failed to load HCZ geyser cutscene art: " + e.getMessage());
        }
    }

    /**
     * Loads AIZ end boss art: KosinskiModuled main art + PLC 0x6B (Robotnik ship + explosions).
     * The main boss art at ArtKosM_AIZEndBoss is separate from the PLC-loaded shared assets.
     * Matching ROM: Queue_Kos_Module + Load_PLC(#$6B) in Obj_AIZEndBossWait.
     */
    private void loadAizEndBossArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);

            // Main boss art (KosinskiModuled)
            Pattern[] bossPatterns = decompressKosinskiModuled(rom,
                    Sonic3kConstants.ART_KOSM_AIZ_END_BOSS_ADDR);
            if (bossPatterns.length > 0) {
                registerSheet(Sonic3kObjectArtKeys.AIZ_END_BOSS,
                        buildSheetFromPatterns(bossPatterns, reader,
                                Sonic3kConstants.MAP_AIZ_END_BOSS_ADDR, 1));
            }

            // PLC 0x6B: ArtNem_RobotnikShip + ArtNem_BossExplosion (shared assets)
            PlcDefinition plc = Sonic3kPlcLoader.parsePlc(rom, Sonic3kConstants.PLC_AIZ_END_BOSS);
            List<Pattern[]> decompressed = PlcParser.decompressAll(rom, plc);

            // Entry 0: Robotnik ship art
            if (!decompressed.isEmpty() && decompressed.get(0).length > 0) {
                registerSheet(Sonic3kObjectArtKeys.ROBOTNIK_SHIP,
                        buildSheetFromPatterns(decompressed.get(0), reader,
                                Sonic3kConstants.MAP_ROBOTNIK_SHIP_ADDR, 0));
            }

            // Entry 1: Boss explosion art (may already be registered by miniboss PLC)
            if (decompressed.size() >= 2 && decompressed.get(1).length > 0
                    && sheets.get(ObjectArtKeys.BOSS_EXPLOSION) == null) {
                registerSheet(ObjectArtKeys.BOSS_EXPLOSION,
                        buildSheetFromPatterns(decompressed.get(1), reader,
                                Sonic3kConstants.MAP_BOSS_EXPLOSION_ADDR, 0));
            }

            LOG.info(String.format("Loaded AIZ end boss art: main=%d tiles, ship=%s, explosion=%s",
                    bossPatterns.length,
                    !decompressed.isEmpty() ? decompressed.get(0).length + " tiles" : "n/a",
                    decompressed.size() >= 2 ? decompressed.get(1).length + " tiles" : "n/a"));
        } catch (IOException e) {
            LOG.warning("Failed to load AIZ end boss art: " + e.getMessage());
        }
    }

    /**
     * Loads AIZ2 battleship sequence art via standalone KosinskiModuled decompression.
     *
     * <p>The bombership sprites share a single KosinskiModuled art source
     * (ArtKosM_AIZ2Bombership2 at 0x399CC4, 176 tiles). Each object type uses
     * different mapping frames referencing tile indices within this art set.
     * The battleship palette is loaded into palette line 2.
     */
    private void loadAiz2BattleshipArt() {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) return;
            RomByteReader reader = RomByteReader.fromRom(rom);

            // Decompress the shared bombership art (KosinskiModuled, 176 tiles)
            Pattern[] bomberPatterns = decompressKosinskiModuled(rom,
                    Sonic3kConstants.ART_KOSM_AIZ2_BOMBERSHIP_ADDR);
            if (bomberPatterns.length == 0) {
                LOG.warning("Failed to decompress bombership art");
                return;
            }

            // Bomb explosions: 12 frames (tile indices ~$08-$80).
            // Mapping pieces carry palette 1 ($20 byte). Sheet palette 0 means
            // pieces render at their native palette: (1+0)&3 = palette 1.
            // Palette 1 is patched with Pal_AIZBossSmall/Pal_AIZBattleship
            // during the bombing sequence.
            registerSheet(Sonic3kObjectArtKeys.AIZ2_BOMB_EXPLODE,
                    buildSheetFromPatterns(bomberPatterns, reader,
                            Sonic3kConstants.MAP_AIZ2_BOMB_EXPLODE_ADDR, 0));

            // Ship propeller: 4 frames (tile indices ~$00-$07).
            // Mapping pieces carry palette 2 ($A0 byte includes priority).
            registerSheet(Sonic3kObjectArtKeys.AIZ2_SHIP_PROPELLER,
                    buildSheetFromPatterns(bomberPatterns, reader,
                            Sonic3kConstants.MAP_AIZ_SHIP_PROPELLER_ADDR, 0));

            // Small boss craft: 1 frame (tile indices ~$86-$AC).
            // Mapping pieces carry palette 1 ($20 byte). Sheet palette 0 means
            // pieces render at their native palette: (1+0)&3 = palette 1.
            // The small boss patches palette 1 with Pal_AIZBossSmall at spawn time.
            registerSheet(Sonic3kObjectArtKeys.AIZ2_BOSS_SMALL,
                    buildSheetFromPatterns(bomberPatterns, reader,
                            Sonic3kConstants.MAP_AIZ2_BOSS_SMALL_ADDR, 0));

            // Background parallax trees: Nemesis-compressed, 1 frame, 4 stacked pieces
            // Mapping pieces carry palette 2 ($40 byte = priority + pal 2).
            // Sheet palette 0: rendered palette = (2+0)&3 = 2.
            Pattern[] treePatterns = PatternDecompressor.nemesis(rom,
                    Sonic3kConstants.ART_NEM_AIZ_BG_TREE_ADDR);
            if (treePatterns.length > 0) {
                registerSheet(Sonic3kObjectArtKeys.AIZ2_BG_TREE,
                        buildSheetFromPatterns(treePatterns, reader,
                                Sonic3kConstants.MAP_AIZ2_BG_TREE_ADDR, 0));
            }

            LOG.info(String.format("Loaded AIZ2 bombership art: %d tiles, " +
                            "bomb=%s, propeller=%s, small=%s, tree=%s",
                    bomberPatterns.length,
                    sheets.containsKey(Sonic3kObjectArtKeys.AIZ2_BOMB_EXPLODE) ? "ok" : "fail",
                    sheets.containsKey(Sonic3kObjectArtKeys.AIZ2_SHIP_PROPELLER) ? "ok" : "fail",
                    sheets.containsKey(Sonic3kObjectArtKeys.AIZ2_BOSS_SMALL) ? "ok" : "fail",
                    sheets.containsKey(Sonic3kObjectArtKeys.AIZ2_BG_TREE) ? "ok" : "fail"));
        } catch (IOException e) {
            LOG.warning("Failed to load AIZ2 bombership art: " + e.getMessage());
        }
    }

    /**
     * Decompresses KosinskiModuled art from the ROM into Pattern arrays.
     */
    private static Pattern[] decompressKosinskiModuled(Rom rom, int romAddr) throws IOException {
        byte[] header = rom.readBytes(romAddr, 2);
        if (header.length < 2) return new Pattern[0];
        int fullSize = ((header[0] & 0xFF) << 8) | (header[1] & 0xFF);
        int inputSize = Math.min(Math.max(fullSize + 256, 0x10000), 0x40000);
        long romSize = rom.getSize();
        if (romAddr + inputSize > romSize) {
            inputSize = (int) (romSize - romAddr);
        }
        byte[] compressed = rom.readBytes(romAddr, inputSize);
        byte[] data = KosinskiReader.decompressModuled(compressed, 0);
        return PatternDecompressor.fromBytes(data);
    }

    /**
     * Builds a sprite sheet from standalone Pattern[] and ROM mappings.
     * Use with {@link PlcParser#decompressEntry} or {@link PlcParser#decompressAll}
     * for PLC-based art loading without level buffer involvement.
     */
    private static ObjectSpriteSheet buildSheetFromPatterns(
            Pattern[] patterns, RomByteReader reader, int mappingAddr, int paletteIndex) {
        if (patterns == null || patterns.length == 0) return null;
        List<SpriteMappingFrame> mappings =
                S3kSpriteDataLoader.loadMappingFrames(reader, mappingAddr);
        return buildSheetFromPatterns(patterns, mappings, paletteIndex);
    }

    private static ObjectSpriteSheet buildSheetFromPatterns(
            Pattern[] patterns, List<SpriteMappingFrame> mappings, int paletteIndex) {
        if (patterns == null || patterns.length == 0) return null;
        if (mappings.isEmpty()) {
            return new ObjectSpriteSheet(patterns, mappings, paletteIndex, 1);
        }

        int minTile = Integer.MAX_VALUE;
        int maxTileExclusive = Integer.MIN_VALUE;
        for (SpriteMappingFrame frame : mappings) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                minTile = Math.min(minTile, piece.tileIndex());
                int pieceTiles = piece.widthTiles() * piece.heightTiles();
                maxTileExclusive = Math.max(maxTileExclusive, piece.tileIndex() + pieceTiles);
            }
        }

        if (minTile == Integer.MAX_VALUE) {
            return new ObjectSpriteSheet(patterns, mappings, paletteIndex, 1);
        }

        int tileRange = maxTileExclusive - minTile;
        Pattern[] sheetPatterns = patterns;
        int tileAdjustment = 0;
        if (maxTileExclusive <= patterns.length) {
            // Mapping tile words are relative to the decompressed source art.
            // Keep source tile N aligned with mapping tile N by slicing before
            // zero-basing the mappings. AIZ2 bombership sprites rely on this:
            // bomb/explosion frames start at tile $08 and the small boss at $86.
            if (minTile > 0) {
                sheetPatterns = Arrays.copyOfRange(patterns, minTile, maxTileExclusive);
                tileAdjustment = -minTile;
            }
        } else {
            // Some standalone sheets carry absolute VRAM tile numbers in their
            // mappings. In that case the decompressed art starts at mapping min.
            tileAdjustment = -minTile;
        }

        List<SpriteMappingFrame> adjustedMappings = Sonic3kObjectArt.adjustTileIndices(mappings, tileAdjustment);
        if (tileRange > sheetPatterns.length) {
            LOG.warning("Standalone S3K sheet mapping range exceeds pattern count"
                    + ": tileRange=" + tileRange
                    + " patterns=" + sheetPatterns.length);
        }
        return new ObjectSpriteSheet(sheetPatterns, adjustedMappings, paletteIndex, 1);
    }

    /**
     * Returns renderer keys whose level tile ranges overlap any of the given modified ranges.
     * Used by {@link Sonic3kPlcLoader#refreshAffectedRenderers} to find which
     * renderers need GPU texture re-upload after PLC application.
     */
    public List<String> getAffectedRendererKeys(List<Sonic3kPlcLoader.TileRange> modifiedRanges) {
        List<String> affected = new ArrayList<>();
        for (var entry : levelArtTileRanges.entrySet()) {
            for (Sonic3kPlcLoader.TileRange sheetRange : entry.getValue()) {
                int sheetStart = sheetRange.startTileIndex();
                int sheetEnd = sheetStart + sheetRange.tileCount();

                for (Sonic3kPlcLoader.TileRange modified : modifiedRanges) {
                    int modStart = modified.startTileIndex();
                    int modEnd = modStart + modified.tileCount();
                    if (modStart < sheetEnd && modEnd > sheetStart) {
                        affected.add(entry.getKey());
                        break;
                    }
                }
                if (affected.contains(entry.getKey())) {
                    break;
                }
            }
        }
        return affected;
    }

    private void registerSheet(String key, ObjectSpriteSheet sheet) {
        if (sheet == null) {
            return;
        }
        int existingIndex = rendererKeys.indexOf(key);
        if (existingIndex >= 0) {
            PatternSpriteRenderer existing = renderers.get(key);
            ObjectSpriteSheet existingSheet = sheets.get(key);
            if (existing != null && existing.isReady() && sameSheetContent(existingSheet, sheet)) {
                // Act transitions re-register every sheet of the new act's plan,
                // most of them pixel-identical to the ones already on the GPU.
                // Keep the uploaded renderer and only swap the sheet object so
                // later in-place pattern refreshes see the new level's patterns.
                existing.rebindEquivalentSheet(sheet);
                sheets.put(key, sheet);
                sheetOrder.set(existingIndex, sheet);
                return;
            }
            PatternSpriteRenderer renderer = new PatternSpriteRenderer(sheet);
            sheets.put(key, sheet);
            renderers.put(key, renderer);
            sheetOrder.set(existingIndex, sheet);
            rendererOrder.set(existingIndex, renderer);
            return;
        }
        PatternSpriteRenderer renderer = new PatternSpriteRenderer(sheet);
        sheets.put(key, sheet);
        renderers.put(key, renderer);
        rendererKeys.add(key);
        sheetOrder.add(sheet);
        rendererOrder.add(renderer);
    }

    /** True when both sheets would upload identical GPU patterns and draw identical frames. */
    static boolean sameSheetContent(ObjectSpriteSheet a, ObjectSpriteSheet b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.getPaletteIndex() != b.getPaletteIndex()
                || a.getFrameDelay() != b.getFrameDelay()
                || a.getFrameCount() != b.getFrameCount()) {
            return false;
        }
        for (int i = 0; i < a.getFrameCount(); i++) {
            if (!java.util.Objects.equals(a.getFrame(i), b.getFrame(i))) {
                return false;
            }
        }
        Pattern[] left = a.getPatterns();
        Pattern[] right = b.getPatterns();
        if (left.length != right.length) {
            return false;
        }
        byte[] leftPixels = new byte[Pattern.PATTERN_SIZE_IN_MEM];
        byte[] rightPixels = new byte[Pattern.PATTERN_SIZE_IN_MEM];
        for (int i = 0; i < left.length; i++) {
            if (left[i] == right[i]) {
                continue;
            }
            if (left[i] == null || right[i] == null) {
                return false;
            }
            left[i].copyInto(leftPixels, 0);
            right[i].copyInto(rightPixels, 0);
            if (!Arrays.equals(leftPixels, rightPixels)) {
                return false;
            }
        }
        return true;
    }

    private void registerStandaloneAnimations(String key) {
        if (Sonic3kObjectArtKeys.MHZ_SHIP_PROPELLER.equals(key)) {
            SpriteAnimationSet set = new SpriteAnimationSet();
            set.addScript(0, new SpriteAnimationScript(2,
                    List.of(5, 6, 7),
                    SpriteAnimationEndAction.LOOP, 0));
            animations.put(key, set);
        }
    }

    /**
     * Ensures a standalone registry-backed sheet is registered for the current zone/act.
     * Used by objects whose ROM behavior loads auxiliary PLC art on demand.
     *
     * @param key standalone art key
     * @return true if the sheet is registered after the call
     */
    public boolean ensureStandaloneArtLoaded(String key) {
        if (renderers.containsKey(key) && sheets.containsKey(key)) {
            return true;
        }

        Sonic3kPlcArtRegistry.ZoneArtPlan plan =
                Sonic3kPlcArtRegistry.getPlan(currentZoneIndex, currentActIndex);
        Sonic3kPlcArtRegistry.StandaloneArtEntry entry = null;
        for (Sonic3kPlcArtRegistry.StandaloneArtEntry candidate : plan.standaloneArt()) {
            if (candidate.key().equals(key)) {
                entry = candidate;
                break;
            }
        }
        if (entry == null) {
            return false;
        }

        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) {
                return false;
            }
            RomByteReader reader = RomByteReader.fromRom(rom);
            Sonic3kObjectArt art = new Sonic3kObjectArt(null, reader);
            registerSheet(key, art.loadStandaloneSheet(rom, entry));
            registerStandaloneAnimations(key);
            return renderers.containsKey(key) && sheets.containsKey(key);
        } catch (IOException e) {
            LOG.warning("Failed to ensure standalone art '" + key + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Refreshes an existing sheet's pattern data and re-uploads GPU textures.
     * Used after PLC reloads or boss defeat to ensure renderers show updated art.
     *
     * <p>Unlike creating a whole new renderer (which would lose the cached
     * {@code patternBase} and cause invisible sprites), this copies patterns
     * from the new sheet into the existing one and asks the renderer to
     * re-upload the affected GPU textures.
     *
     * @param key the art key to refresh
     * @param freshSheet a newly built sheet with up-to-date patterns
     */
    public void refreshSheetPatterns(String key, ObjectSpriteSheet freshSheet) {
        if (freshSheet == null) return;
        ObjectSpriteSheet existing = sheets.get(key);
        PatternSpriteRenderer renderer = renderers.get(key);
        if (existing == null || renderer == null) {
            // Not registered yet — register fresh so first draw works normally
            registerSheet(key, freshSheet);
            return;
        }

        // Copy fresh patterns into the existing sheet's array so the renderer
        // (which holds a reference to the same array) sees updated data.
        Pattern[] dst = existing.getPatterns();
        Pattern[] src = freshSheet.getPatterns();
        int count = Math.min(dst.length, src.length);
        System.arraycopy(src, 0, dst, 0, count);

        // Ask the renderer to re-upload the affected GPU textures.
        GraphicsManager gfx = GameServices.graphics();
        if (gfx != null && gfx.isGlInitialized() && renderer.isReady()) {
            renderer.updatePatternRange(gfx, 0, count);
        }
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
        return switch (key) {
            case ObjectArtKeys.ANIMAL_TYPE_A -> animalTypeA;
            case ObjectArtKeys.ANIMAL_TYPE_B -> animalTypeB;
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
            renderer.ensurePatternsCached(graphicsManager, next);
            next += sheet.getPatterns().length;
        }
        // Also cache DPLC-driven shield renderers
        for (PlayerSpriteRenderer dplcRenderer : dplcRenderers.values()) {
            dplcRenderer.ensureCached(graphicsManager);
        }
        return next;
    }

    @Override
    public boolean isReady() {
        // Ready if at least one renderer has been cached
        for (PatternSpriteRenderer renderer : renderers.values()) {
            if (renderer.isReady()) {
                return true;
            }
        }
        return false;
    }

    // --- RewindSnapshottable<PlcProgressSnapshot> ---

    @Override
    public String key() {
        return "s3k-plc-art";
    }

    @Override
    public com.openggf.game.rewind.snapshot.PlcProgressSnapshot capture() {
        List<com.openggf.game.rewind.snapshot.PlcProgressSnapshot.PendingKosModule>
                pendingModules = pendingEnemyKosEntries.stream()
                .map(entry ->
                        new com.openggf.game.rewind.snapshot.PlcProgressSnapshot.PendingKosModule(
                                entry.source(), entry.destinationTile()))
                .toList();
        return new com.openggf.game.rewind.snapshot.PlcProgressSnapshot(
                loadEpoch, cnzTeleporterArtState.ordinal()
                        | (cnzEndBossArtState.ordinal() << 2)
                        | (enemyKosArmOnNextRuntimePass ? 1 << 4 : 0),
                pendingModules,
                enemyKosHandles.stream().map(HardwareWorkHandle::ordinal).toList(),
                enemyKosSubmissionArmed,
                titleCardTeardown == null ? -1 : titleCardTeardown.ticksElapsed(),
                runtimeArtAdmissionGeneration,
                runtimeArtAdmissionNextLeaseId,
                runtimeArtAdmissionLease == null ? -1 : runtimeArtAdmissionLease.id(),
                runtimeArtAdmissionLease == null
                        ? 0 : runtimeArtAdmissionLease.batchFingerprint(),
                runtimeArtAdmissionLease == null
                        ? null : runtimeArtAdmissionLease.ownerKind(),
                runtimeArtAdmissionBound,
                runtimeArtAdmissionConsumed,
                titleCardTeardownLeaseId);
    }

    /**
     * Restores provider-owned runtime queue state. Loaded pattern data remains
     * cached, but the state gate determines whether gameplay may observe it.
     */
    @Override
    public void restore(com.openggf.game.rewind.snapshot.PlcProgressSnapshot snap) {
        loadEpoch = snap.loadEpoch();
        int packedState = snap.runtimeState();
        cnzTeleporterArtState = decodeRuntimeArtState(packedState & 3);
        cnzEndBossArtState = decodeRuntimeArtState((packedState >>> 2) & 3);
        enemyKosArmOnNextRuntimePass = (packedState & (1 << 4)) != 0;
        pendingEnemyKosEntries = snap.pendingKosModules().stream()
                .map(entry -> new EnemyKosEntry(
                        entry.sourceAddress(), entry.destinationTile()))
                .toList();
        enemyKosSubmissionArmed = snap.kosSubmissionArmed();
        runtimeArtAdmissionGeneration = snap.runtimeArtAdmissionGeneration();
        runtimeArtAdmissionNextLeaseId = snap.runtimeArtAdmissionNextLeaseId();
        runtimeArtAdmissionLease = snap.runtimeArtAdmissionLeaseId() < 0
                ? null
                : new RuntimeArtAdmissionLease(
                        snap.runtimeArtAdmissionLeaseId(),
                        snap.runtimeArtAdmissionGeneration(),
                        snap.runtimeArtAdmissionBatchFingerprint(),
                        snap.runtimeArtAdmissionOwnerKind());
        runtimeArtAdmissionBound = snap.runtimeArtAdmissionBound();
        runtimeArtAdmissionConsumed = snap.runtimeArtAdmissionConsumed();
        titleCardTeardownLeaseId = snap.titleCardTeardownLeaseId();
        if (snap.titleCardTeardownTicks() < 0) {
            titleCardTeardown = null;
        } else {
            titleCardTeardown =
                    new com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardTeardownModel();
            titleCardTeardown.restoreTicks(snap.titleCardTeardownTicks());
        }
        enemyKosHandles.clear();
        enemyKosQueue = null;
        if (!snap.pendingKosOrdinals().isEmpty()) {
            var timing = GameServices.hardwareTiming();
            enemyKosQueue = S3kRuntimeArtCoordinator.current().moduleQueue();
            for (long ordinal : snap.pendingKosOrdinals()) {
                enemyKosHandles.add(timing.pendingHandle(
                                HardwareWorkKind.KOS_MODULE_QUEUE, ordinal)
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing restored S3K enemy KosM job "
                                        + ordinal)));
            }
        }
    }

    private static RuntimeArtState decodeRuntimeArtState(int state) {
        return state >= 0 && state < RuntimeArtState.values().length
                ? RuntimeArtState.values()[state] : RuntimeArtState.IDLE;
    }
}
