package com.openggf.game.sonic2;

import com.openggf.game.GameServices;
import com.openggf.game.ZoneArtProvider;
import com.openggf.game.common.CommonSpriteDataLoader;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.AnimalType;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectArtData;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.util.PatternDecompressor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads common object art (monitors, spikes, springs) for Sonic 2 (REV01).
 */
public class Sonic2ObjectArt {
    private static final Logger LOGGER = Logger.getLogger(Sonic2ObjectArt.class.getName());
    private static final int ANIMAL_TILE_OFFSET = 0x14;
    private static final int TORNADO_ART_TILE = Sonic2Constants.ART_TILE_ENDING_TORNADO;
    private static final int TORNADO_SONIC_RIDER_OFFSET = Sonic2Constants.ART_TILE_SONIC - TORNADO_ART_TILE;
    private static final int TORNADO_TAILS_RIDER_OFFSET = Sonic2Constants.ART_TILE_TAILS - TORNADO_ART_TILE;
    private static final int TORNADO_RIDER_TILE_COUNT = 6;

    /**
     * VRAM tile index of the 1-up monitor's life-counter icon piece (obj26 frame 2,
     * {@code Map_obj26_0034}, tile {@code $154}). In the ROM this is
     * {@code ArtTile_ArtNem_life_counter} ({@code = ArtTile_ArtNem_Powerups + $154}),
     * shared with the HUD life counter, so the icon shows the main character's face.
     */
    public static final int MONITOR_LIFE_ICON_TILE = 340;
    private static final AnimalType[] DEFAULT_ANIMALS = { AnimalType.RABBIT, AnimalType.RABBIT };
    private static final AnimalType[][] ZONE_ANIMALS = {
            { AnimalType.SQUIRREL, AnimalType.FLICKY }, // 0 EHZ
            { AnimalType.SQUIRREL, AnimalType.FLICKY }, // 1 Zone 1
            { AnimalType.SQUIRREL, AnimalType.FLICKY }, // 2 WZ
            { AnimalType.SQUIRREL, AnimalType.FLICKY }, // 3 Zone 3
            { AnimalType.MONKEY, AnimalType.EAGLE }, // 4 MTZ1/2
            { AnimalType.MONKEY, AnimalType.EAGLE }, // 5 MTZ3
            { AnimalType.MONKEY, AnimalType.EAGLE }, // 6 WFZ
            { AnimalType.MONKEY, AnimalType.EAGLE }, // 7 HTZ
            { AnimalType.MOUSE, AnimalType.SEAL }, // 8 HPZ
            { AnimalType.MOUSE, AnimalType.SEAL }, // 9 Zone 9
            { AnimalType.PENGUIN, AnimalType.SEAL }, // 10 OOZ
            { AnimalType.MOUSE, AnimalType.CHICKEN }, // 11 MCZ
            { AnimalType.BEAR, AnimalType.FLICKY }, // 12 CNZ
            { AnimalType.RABBIT, AnimalType.EAGLE }, // 13 CPZ
            { AnimalType.PIG, AnimalType.CHICKEN }, // 14 DEZ
            { AnimalType.PENGUIN, AnimalType.FLICKY }, // 15 ARZ
            { AnimalType.TURTLE, AnimalType.CHICKEN } // 16 SCZ
    };

    private final Rom rom;
    private final RomByteReader reader;
    private final Map<Integer, ObjectArtData> cachedByZone = new HashMap<>();

    public Sonic2ObjectArt(Rom rom, RomByteReader reader) {
        this.rom = rom;
        this.reader = reader;
    }

    public ObjectArtData load() throws IOException {
        return loadForZone(-1);
    }

    public ObjectArtData loadForZone(int zoneIndex) throws IOException {
        Integer cacheKey = zoneIndex;
        ObjectArtData cached = cachedByZone.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        AnimalType[] zoneAnimals = resolveZoneAnimals(zoneIndex);
        AnimalType animalTypeA = zoneAnimals[0];
        AnimalType animalTypeB = zoneAnimals[1];

        // Load Monitor Art (base art)
        Pattern[] monitorBasePatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_MONITOR_ADDR, "Monitor");
        // Load the life-counter art for the 1-up monitor icon (obj26 frame 2's icon
        // piece maps to tile $154 = MONITOR_LIFE_ICON_TILE). In the ROM that tile is
        // ArtTile_ArtNem_life_counter, the same VRAM the HUD life counter uses, so the
        // standard 1-up monitor displays the main character's face. PlrList_Std1 loads
        // Sonic's life art there for every level; Tails-alone and Knuckles (lock-on)
        // override this tile in Sonic2ObjectArtProvider, mirroring PlrList_TailsLife /
        // the Knuckles HUD patch. (s2.asm:89193; mappings/sprite/obj26.asm)
        Pattern[] lifeIconPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SONIC_LIFE_ADDR, "SonicLife");

        List<SpriteMappingFrame> monitorMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MONITOR_ADDR);

        // Calculate max requested tile index
        int maxTileIndex = 0;
        for (SpriteMappingFrame frame : monitorMappings) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                maxTileIndex = Math.max(maxTileIndex, piece.tileIndex());
            }
        }

        // Extend monitor patterns to cover the max requested index
        int requiredSize = maxTileIndex + 1;
        // Ensure we have enough space for Tails Life Art starting at 340 (0x154 * 32
        // bytes = 10880 offset)
        int lifeArtOffset = MONITOR_LIFE_ICON_TILE;
        requiredSize = Math.max(requiredSize, lifeArtOffset + lifeIconPatterns.length);

        Pattern[] monitorPatterns = new Pattern[requiredSize];
        // Copy base patterns
        if (monitorBasePatterns.length > 0) {
            System.arraycopy(monitorBasePatterns, 0, monitorPatterns, 0, monitorBasePatterns.length);
        }
        // Copy the main character's life-counter art at the icon tile offset
        if (lifeIconPatterns.length > 0 && lifeArtOffset < monitorPatterns.length) {
            System.arraycopy(lifeIconPatterns, 0, monitorPatterns, lifeArtOffset,
                    Math.min(lifeIconPatterns.length, monitorPatterns.length - lifeArtOffset));
        }

        // Fill gaps with empty patterns to prevent NPEs
        for (int i = 0; i < monitorPatterns.length; i++) {
            if (monitorPatterns[i] == null) {
                monitorPatterns[i] = new Pattern();
            }
        }

        ObjectSpriteSheet monitorSheet = new ObjectSpriteSheet(monitorPatterns, monitorMappings, 0, 1);

        ObjectSpriteSheet spikeSheet = loadSpikeSheet();
        ObjectSpriteSheet spikeSideSheet = loadSpikeSideSheet();

        ObjectSpriteSheet springVerticalSheet = loadSpringVerticalSheet();
        ObjectSpriteSheet springHorizontalSheet = loadSpringHorizontalSheet();
        ObjectSpriteSheet springDiagonalSheet = loadSpringDiagonalSheet();
        ObjectSpriteSheet springVerticalRedSheet = loadSpringVerticalRedSheet();
        ObjectSpriteSheet springHorizontalRedSheet = loadSpringHorizontalRedSheet();
        ObjectSpriteSheet springDiagonalRedSheet = loadSpringDiagonalRedSheet();

        ObjectSpriteSheet explosionSheet = loadExplosionSheet();
        ObjectSpriteSheet shieldSheet = loadShieldSheet();
        ObjectSpriteSheet bridgeSheet = loadBridgeSheet();

        Pattern[] waterfallPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EHZ_WATERFALL_ADDR,
                "EHZWaterfall");
        List<SpriteMappingFrame> waterfallMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_WATERFALL_ADDR);
        int waterfallMaxTile = computeMaxTileIndex(waterfallMappings);
        if (waterfallMaxTile >= waterfallPatterns.length) {
            Pattern[] extended = new Pattern[waterfallMaxTile + 1];
            if (waterfallPatterns.length > 0) {
                System.arraycopy(waterfallPatterns, 0, extended, 0, waterfallPatterns.length);
            }
            for (int i = 0; i < extended.length; i++) {
                if (extended[i] == null) {
                    extended[i] = new Pattern();
                }
            }
            waterfallPatterns = extended;
        }
        ObjectSpriteSheet waterfallSheet = new ObjectSpriteSheet(waterfallPatterns, waterfallMappings, 1, 1);

        ObjectSpriteSheet invincibilityStarsSheet = loadInvincibilityStarsSheet();

        SpriteAnimationSet monitorAnimations = loadAnimationSet(
                Sonic2Constants.ANI_OBJ26_ADDR,
                Sonic2Constants.ANI_OBJ26_SCRIPT_COUNT);
        SpriteAnimationSet springAnimations = loadAnimationSet(
                Sonic2Constants.ANI_OBJ41_ADDR,
                Sonic2Constants.ANI_OBJ41_SCRIPT_COUNT);
        List<SpriteMappingFrame> springMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_SPRING_ADDR);

        // Checkpoint/Starpost art
        ObjectSpriteSheet checkpointSheet = loadCheckpointSheet();
        ObjectSpriteSheet checkpointStarSheet = loadCheckpointStarSheet();
        List<SpriteMappingFrame> checkpointMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_CHECKPOINT_ADDR);
        SpriteAnimationSet checkpointAnimations = loadAnimationSet(
                Sonic2Constants.ANI_OBJ79_ADDR,
                Sonic2Constants.ANI_OBJ79_SCRIPT_COUNT);

        // Badnik sheets are loaded separately via Sonic2ObjectArtProvider
        // using the load*Sheet() methods on this class

        Pattern[] animalPatterns = loadAnimalPatterns(animalTypeA, animalTypeB);
        List<SpriteMappingFrame> animalMappings = createAnimalMappings(); // Complex composite layout - kept hardcoded
        ObjectSpriteSheet animalSheet = new ObjectSpriteSheet(animalPatterns, animalMappings, 0, 1);

        ObjectSpriteSheet pointsSheet = loadPointsSheet();
        ObjectSpriteSheet signpostSheet = loadSignpostSheet();
        SpriteAnimationSet signpostAnimations = createSignpostAnimations();

        ObjectSpriteSheet bumperSheet = loadBumperSheet();
        ObjectSpriteSheet hexBumperSheet = loadHexBumperSheet();
        ObjectSpriteSheet bonusBlockSheet = loadBonusBlockSheet();
        ObjectSpriteSheet flipperSheet = loadFlipperSheet();
        SpriteAnimationSet flipperAnimations = createFlipperAnimations();

        ObjectSpriteSheet speedBoosterSheet = loadSpeedBoosterSheet();
        ObjectSpriteSheet blueBallsSheet = loadBlueBallsSheet();

        SpriteAnimationSet pipeExitSpringAnimations = createPipeExitSpringAnimations();
        SpriteAnimationSet tippingFloorAnimations = createTippingFloorAnimations();
        SpriteAnimationSet springboardAnimations = createSpringboardAnimations();

        ObjectSpriteSheet bubblesSheet = loadBubblesSheet();
        ObjectSpriteSheet leavesSheet = loadLeavesSheet();

        // Results screen art (Obj3A)
        // ROM mappings expect fixed VRAM tile bases for each chunk:
        // Numbers (0x520), Perfect (0x540), TitleCard (0x580),
        // ResultsText (0x5B0), MiniCharacter (0x5F4).
        // We build a pattern array aligned to VRAM_BASE_NUMBERS so that
        // mapping tile indices can be offset by -VRAM_BASE_NUMBERS.
        Pattern[] hudDigitPatterns = safeLoadUncompressedPatterns(
                Sonic2Constants.ART_UNC_HUD_NUMBERS_ADDR,
                Sonic2Constants.ART_UNC_HUD_NUMBERS_SIZE,
                "HUDNumbers");
        Pattern[] hudTextPatterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_HUD_ADDR,
                "HUDText");
        Pattern[] perfectPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_PERFECT_ADDR, "PerfectText");
        Pattern[] titleCardPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_TITLE_CARD_ADDR, "TitleCard");
        Pattern[] resultsTextPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_RESULTS_TEXT_ADDR,
                "ResultsText");
        Pattern[] miniSonicPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_MINI_SONIC_ADDR, "MiniSonic");

        Pattern[] bonusDisplayPatterns = createBlankPatterns(Sonic2Constants.RESULTS_BONUS_DIGIT_TILES);

        Pattern[] resultsPatterns = createResultsVramPatterns(
                bonusDisplayPatterns,
                perfectPatterns,
                titleCardPatterns,
                resultsTextPatterns,
                miniSonicPatterns,
                hudTextPatterns);

        // Load mappings with offset relative to Numbers VRAM base (0x520)
        List<SpriteMappingFrame> resultsMappings = loadMappingFramesWithTileOffset(
                Sonic2Constants.MAPPINGS_EOL_TITLE_CARDS_ADDR, -Sonic2Constants.VRAM_BASE_NUMBERS);
        resultsPatterns = ensureResultsPatternCapacity(resultsPatterns, resultsMappings);
        ObjectSpriteSheet resultsSheet = new ObjectSpriteSheet(resultsPatterns, resultsMappings, 0, 1);

        Pattern[] hudLivesPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SONIC_LIFE_ADDR, "SonicLife");
        Pattern[] hudLivesNumbers = safeLoadUncompressedPatterns(Sonic2Constants.ART_UNC_LIVES_NUMBERS_ADDR,
                Sonic2Constants.ART_UNC_LIVES_NUMBERS_SIZE, "LivesNumbers");
        Pattern[] debugFontPatterns = safeLoadUncompressedPatterns(Sonic2Constants.ART_UNC_DEBUG_FONT_ADDR,
                Sonic2Constants.ART_UNC_DEBUG_FONT_SIZE, "DebugFont");
        ObjectArtData artData = new ObjectArtData(
                monitorSheet,
                spikeSheet,
                spikeSideSheet,
                springVerticalSheet,
                springHorizontalSheet,
                springDiagonalSheet,
                springVerticalRedSheet,
                springHorizontalRedSheet,
                springDiagonalRedSheet,
                explosionSheet,
                shieldSheet,
                invincibilityStarsSheet,
                bridgeSheet,
                waterfallSheet,
                checkpointSheet,
                checkpointStarSheet,
                animalSheet,
                animalTypeA.ordinal(),
                animalTypeB.ordinal(),
                pointsSheet,
                signpostSheet,
                bumperSheet,
                hexBumperSheet,
                bonusBlockSheet,
                flipperSheet,
                speedBoosterSheet,
                blueBallsSheet,
                resultsSheet,
                bubblesSheet,
                leavesSheet,
                hudDigitPatterns,
                hudTextPatterns,
                hudLivesPatterns,
                hudLivesNumbers,
                debugFontPatterns,
                monitorAnimations,
                springAnimations,
                checkpointAnimations,
                signpostAnimations,
                flipperAnimations,
                pipeExitSpringAnimations,
                tippingFloorAnimations,
                springboardAnimations);

        cachedByZone.put(cacheKey, artData);
        return artData;
    }

    private Pattern[] loadUncompressedPatterns(int artAddr, int length) throws IOException {
        if (length <= 0) {
            return new Pattern[0];
        }
        byte[] result = new byte[length];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(result);
        synchronized (rom) {
            FileChannel channel = rom.getFileChannel();
            channel.position(artAddr);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
            }
        }
        if (buffer.hasRemaining()) {
            throw new IOException("Unexpected EOF reading uncompressed art");
        }
        if (result.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent uncompressed art tile data");
        }
        int patternCount = result.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(result, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);
        }
        return patterns;
    }

    /**
     * Safely loads Nemesis patterns, returning an empty array on failure.
     * Logs full stack trace for diagnosis without blocking other art.
     * 
     * @param artAddr   ROM address of the Nemesis-compressed art
     * @param assetName Human-readable name for error reporting
     * @return Decompressed patterns, or empty array on failure
     */
    private Pattern[] safeLoadNemesisPatterns(int artAddr, String assetName) {
        try {
            synchronized (rom) {
                return PatternDecompressor.nemesis(rom, artAddr);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE,
                    String.format("Failed to load art '%s' at 0x%06X", assetName, artAddr), e);
            return new Pattern[0];
        }
    }

    /**
     * Safely loads uncompressed patterns, returning an empty array on failure.
     */
    private Pattern[] safeLoadUncompressedPatterns(int artAddr, int length, String assetName) {
        try {
            return loadUncompressedPatterns(artAddr, length);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE,
                    String.format("Failed to load uncompressed art '%s' at 0x%06X", assetName, artAddr), e);
            return new Pattern[0];
        }
    }

    // ---- Extracted sheet builders (used by loadForZone and PLC registry) ----

    /**
     * Obj39 GAME OVER / TIME OVER sheet: ArtNem_Game_Over with Obj39_MapUnc_14C6C.
     * The ROM decompresses it through PLCID_GameOver when CheckGameOver asks for
     * it; the engine keeps the sheet resident and uses the PLC queue for timing.
     */
    public ObjectSpriteSheet loadGameOverSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_GAME_OVER_ADDR,
                Sonic2Constants.MAPPINGS_GAME_OVER_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadSpikeSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPIKES_ADDR,
                Sonic2Constants.MAP_UNC_SPIKES_ADDR, 1, 1);
    }

    public ObjectSpriteSheet loadSpikeSideSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPIKES_SIDE_ADDR,
                Sonic2Constants.MAP_UNC_SPIKES_ADDR, 1, 1);
    }

    public ObjectSpriteSheet loadSpringVerticalSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPRING_VERTICAL_ADDR,
                Sonic2Constants.MAP_UNC_SPRING_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadSpringHorizontalSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPRING_HORIZONTAL_ADDR,
                Sonic2Constants.MAP_UNC_SPRING_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadSpringDiagonalSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPRING_DIAGONAL_ADDR,
                Sonic2Constants.MAP_UNC_SPRING_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadSpringVerticalRedSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPRING_VERTICAL_ADDR,
                Sonic2Constants.MAP_UNC_SPRING_RED_ADDR, 1, 1);
    }

    public ObjectSpriteSheet loadSpringHorizontalRedSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPRING_HORIZONTAL_ADDR,
                Sonic2Constants.MAP_UNC_SPRING_RED_ADDR, 1, 1);
    }

    public ObjectSpriteSheet loadSpringDiagonalRedSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPRING_DIAGONAL_ADDR,
                Sonic2Constants.MAP_UNC_SPRING_RED_ADDR, 1, 1);
    }

    public ObjectSpriteSheet loadExplosionSheet() {
        // ROM: make_art_tile(ArtTile_ArtNem_Explosion,0,0) — palette 0.
        // Mapping pieces encode palette 1 for frames 1-4, so sheet palette must be 0.
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_EXPLOSION_ADDR,
                Sonic2Constants.MAP_UNC_EXPLOSION_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadShieldSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SHIELD_ADDR,
                Sonic2Constants.MAP_UNC_SHIELD_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadInvincibilityStarsSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_INVINCIBILITY_STARS_ADDR,
                "InvincibilityStars");
        if (patterns.length == 0) return null;
        List<SpriteMappingFrame> rawMappings = loadMappingFrames(
                Sonic2Constants.MAP_UNC_INVINCIBILITY_STARS_ADDR);
        List<SpriteMappingFrame> mappings = normalizeMappings(rawMappings);
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    public ObjectSpriteSheet loadBridgeSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_BRIDGE_ADDR,
                Sonic2Constants.MAP_UNC_BRIDGE_ADDR, 2, 1);
    }

    public ObjectSpriteSheet loadCheckpointSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CHECKPOINT_ADDR,
                Sonic2Constants.MAP_UNC_CHECKPOINT_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadCheckpointStarSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CHECKPOINT_ADDR,
                Sonic2Constants.MAP_UNC_CHECKPOINT_STAR_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadPointsSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_NUMBERS_ADDR,
                Sonic2Constants.MAP_UNC_POINTS_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadSignpostSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SIGNPOST_ADDR,
                Sonic2Constants.MAP_UNC_SIGNPOST_A_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadBumperSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_BUMPER_ADDR,
                Sonic2Constants.MAP_UNC_BUMPER_ADDR, 2, 1);
    }

    public ObjectSpriteSheet loadHexBumperSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_HEX_BUMPER_ADDR,
                Sonic2Constants.MAP_UNC_HEX_BUMPER_ADDR, 2, 1);
    }

    public ObjectSpriteSheet loadBonusBlockSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_BONUS_BLOCK_ADDR,
                Sonic2Constants.MAP_UNC_BONUS_BLOCK_ADDR, 2, 1);
    }

    public ObjectSpriteSheet loadFlipperSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_FLIPPER_ADDR,
                Sonic2Constants.MAP_UNC_FLIPPER_ADDR, 2, 1);
    }

    public ObjectSpriteSheet loadSpeedBoosterSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPEED_BOOSTER_ADDR,
                Sonic2Constants.MAP_UNC_SPEED_BOOSTER_ADDR, 3, 1);
    }

    public ObjectSpriteSheet loadBlueBallsSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CPZ_DROPLET_ADDR,
                Sonic2Constants.MAP_UNC_BLUE_BALLS_ADDR, 3, 0);
    }

    public ObjectSpriteSheet loadBreakableBlockSheet(int zoneIndex) {
        ZoneArtProvider.ObjectArtConfig artConfig =
                getObjectArtConfig(Sonic2ObjectIds.BREAKABLE_BLOCK, zoneIndex);
        int artAddr = artConfig != null
                ? artConfig.artAddress()
                : Sonic2Constants.ART_NEM_CPZ_METAL_BLOCK_ADDR;
        int palette = artConfig != null ? artConfig.palette() : 3;
        String assetName = (zoneIndex == Sonic2Constants.ZONE_HTZ) ? "HTZRock" : "CPZMetalBlock";
        Pattern[] patterns = safeLoadNemesisPatterns(artAddr, assetName);
        int mappingAddr = (zoneIndex == Sonic2Constants.ZONE_HTZ)
                ? Sonic2Constants.MAP_UNC_OBJ32_HTZ_ADDR
                : Sonic2Constants.MAP_UNC_OBJ32_CPZ_ADDR;
        List<SpriteMappingFrame> mappings = loadMappingFrames(mappingAddr);
        return new ObjectSpriteSheet(patterns, mappings, palette, 1);
    }

    public ObjectSpriteSheet loadGenericPlatformBSheet(int zoneIndex) {
        ZoneArtProvider.ObjectArtConfig artConfig =
                getObjectArtConfig(Sonic2ObjectIds.GENERIC_PLATFORM_B, zoneIndex);
        int artAddr = artConfig != null
                ? artConfig.artAddress()
                : Sonic2Constants.ART_NEM_CPZ_ELEVATOR_ADDR;
        int palette = artConfig != null ? artConfig.palette() : 3;
        Pattern[] patterns = safeLoadNemesisPatterns(artAddr, "GenericPlatformB");
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ19_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, palette, 0);
    }

    public ObjectSpriteSheet loadCpzStairBlockSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_CPZ_STAIRBLOCK_ADDR, "CPZStairBlock");
        List<SpriteMappingFrame> mappings = createCPZStairBlockMappings();
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    public ObjectSpriteSheet loadSidewaysPformSheet() {
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_CPZ_STAIR_BLOCK_ADDR);
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_CPZ_STAIRBLOCK_ADDR, "CPZStairBlock");
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Hand-crafted mapping frames for the CPZ stair block / MTZ platform art sheet.
     * <p>
     * The ROM mapping files for these objects use ArtTile_ArtKos_LevelArt (level art tiles)
     * which cannot be used directly as a sprite sheet. These frames use ArtNem_CPZStairBlock
     * patterns as a stand-in, arranged to match the platform shapes:
     * <ul>
     *   <li>Frame 0: 4-block wide platform (MTZLongPlatform, 4 pieces)</li>
     *   <li>Frame 1: 2-block platform (MTZLongPlatform narrow, 2 pieces)</li>
     *   <li>Frame 2: Single 32x32 block (MTZPlatform / CPZ square platform)</li>
     * </ul>
     */
    private List<SpriteMappingFrame> createCPZStairBlockMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: 4 blocks (MTZLongPlatform wide variant)
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-0x40, -0x10, 4, 4, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(-0x20, -0x10, 4, 4, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(0,     -0x10, 4, 4, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(0x20,  -0x10, 4, 4, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1: 2 blocks (MTZLongPlatform narrow variant, second piece h-flipped)
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-0x20, -0x10, 4, 4, 0, false, false, 0));
        frame1.add(new SpriteMappingPiece(0,     -0x10, 4, 4, 0, true,  false, 0));
        frames.add(new SpriteMappingFrame(frame1));

        // Frame 2: Single 32x32 block (MTZPlatform / CPZ square platform)
        // Matches obj6B.asm: spritePiece -$10, -$10, 4, 4, 0, 0, 0, 0, 0
        List<SpriteMappingPiece> frame2 = new ArrayList<>();
        frame2.add(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(frame2));

        return frames;
    }

    public ObjectSpriteSheet loadCpzPylonSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CPZ_METAL_THINGS_ADDR,
                Sonic2Constants.MAP_UNC_CPZ_PYLON_ADDR, 2, 1);
    }

    public ObjectSpriteSheet loadPipeExitSpringSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_PIPE_EXIT_SPRING_ADDR,
                Sonic2Constants.MAP_UNC_PIPE_EXIT_SPRING_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadTippingFloorSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CPZ_ANIMATED_BITS_ADDR,
                Sonic2Constants.MAP_UNC_TIPPING_FLOOR_ADDR, 3, 1);
    }

    public ObjectSpriteSheet loadBarrierSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CONSTRUCTION_STRIPES_ADDR,
                Sonic2Constants.MAP_UNC_BARRIER_ADDR, 1, 1);
    }

    public ObjectSpriteSheet loadSpringboardSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_LEVER_SPRING_ADDR,
                Sonic2Constants.MAP_UNC_SPRINGBOARD_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadBubblesSheet() {
        Pattern[] bigBubblePatterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_BUBBLE_GENERATOR_ADDR, "BigBubbles");
        Pattern[] standardBubblePatterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_BUBBLES_ADDR, "Bubbles");
        Pattern[] countdownPatterns = safeLoadUncompressedPatterns(
                Sonic2Constants.ART_UNC_COUNTDOWN_ADDR,
                6 * 6 * Pattern.PATTERN_SIZE_IN_ROM, "DrowningCountdown");
        if (bigBubblePatterns.length == 0 && standardBubblePatterns.length == 0) {
            return null;
        }

        int standardBubbleOffset = Sonic2Constants.ART_TILE_STANDARD_WATER_BUBBLES
                - Sonic2Constants.ART_TILE_BUBBLES;
        int requiredSize = Math.max(bigBubblePatterns.length,
                standardBubbleOffset + standardBubblePatterns.length);
        int countdownOffset = requiredSize;
        Pattern[] patterns = createBlankPatterns(requiredSize + countdownPatterns.length);
        System.arraycopy(bigBubblePatterns, 0, patterns, 0,
                Math.min(bigBubblePatterns.length, patterns.length));
        if (standardBubbleOffset >= 0 && standardBubbleOffset < patterns.length) {
            System.arraycopy(standardBubblePatterns, 0, patterns, standardBubbleOffset,
                    Math.min(standardBubblePatterns.length, patterns.length - standardBubbleOffset));
        }
        System.arraycopy(countdownPatterns, 0, patterns, countdownOffset, countdownPatterns.length);

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_SMALL_BUBBLES_ADDR);
        // Obj0A frames 8-$D all map the same six-tile VRAM window. The ROM's
        // Obj0A_LoadCountdownArt replaces that window with one of six blocks
        // from ArtUnc_Countdown. Keep the blocks side-by-side in this immutable
        // sheet and redirect each mapping frame to its matching block. Adding
        // art_tile $855B to mapping word $1F41 wraps the tile index to $49C,
        // clears both flip bits through carry, and selects palette line 1.
        for (int frame = 8; frame <= 13 && frame < mappings.size(); frame++) {
            SpriteMappingPiece romPiece = mappings.get(frame).pieces().getFirst();
            SpriteMappingPiece atlasPiece = new SpriteMappingPiece(
                    romPiece.xOffset(), romPiece.yOffset(),
                    romPiece.widthTiles(), romPiece.heightTiles(),
                    countdownOffset + (frame - 8) * 6,
                    false, false,
                    1, romPiece.priority());
            mappings.set(frame, new SpriteMappingFrame(List.of(atlasPiece)));
        }
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    public ObjectSpriteSheet loadLeavesSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_LEAVES_ADDR,
                Sonic2Constants.MAP_UNC_LEAVES_ADDR, 3, 1);
    }

    /**
     * Load water surface patterns for Chemical Plant Zone (and Hidden Palace Zone).
     * <p>
     * CPZ uses pink/purple chemical water surface art.
     * ROM address: 0x82364 (Nemesis compressed, 24 blocks)
     *
     * @return CPZ water surface patterns, or empty array on failure
     */
    public Pattern[] loadWaterSurfaceCPZPatterns() {
        return safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_WATER_SURFACE_CPZ_ADDR, "WaterSurfaceCPZ");
    }

    /**
     * Load water surface patterns for Aquatic Ruin Zone.
     * <p>
     * ARZ uses natural blue water surface art.
     * ROM address: 0x82E02 (Nemesis compressed, 16 blocks)
     *
     * @return ARZ water surface patterns, or empty array on failure
     */
    public Pattern[] loadWaterSurfaceARZPatterns() {
        return safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_WATER_SURFACE_ARZ_ADDR, "WaterSurfaceARZ");
    }

    /**
     * Load OOZ Swinging Platform sprite sheet (Object 0x15 in Oil Ocean Zone).
     * <p>
     * ROM: ArtNem_OOZSwingPlat at 0x80E26, palette line 2
     * This is the dedicated art for OOZ; MCZ and ARZ use level art instead.
     *
     * @return sprite sheet for OOZ swinging platform, or null on failure
     */
    public ObjectSpriteSheet loadOOZSwingPlatformSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_OOZ_SWING_PLAT_ADDR,
                Sonic2Constants.MAP_UNC_OBJ15_A_ADDR, 2, 1);
    }

    /**
     * Load OOZ Burner Lid sprite sheet (Object 0x33 platform).
     * Green platform from OOZ that pops up from burner pipe.
     * <p>
     * ROM: ArtNem_BurnerLid at 0x80274, palette line 3, art tile 0x032C
     * Mappings: obj33_a.asm - 1 frame, 2 pieces (left + h-flipped right, 3x2 tiles each)
     *
     * @return sprite sheet for OOZ burner lid, or null on failure
     */
    public ObjectSpriteSheet loadOOZBurnerLidSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_BURNER_LID_ADDR,
                Sonic2Constants.MAP_UNC_OBJ33_A_ADDR, 3, 1);
    }

    /**
     * Load OOZ Burn Flame sprite sheet (Object 0x33 flame child).
     * Green flame from OOZ burners that appears beneath the popping platform.
     * <p>
     * ROM: ArtNem_OOZBurn at 0x81514, palette line 3, art tile 0x02E2
     * Mappings: obj33_b.asm - 3 frames of flame animation
     *
     * @return sprite sheet for OOZ burner flame, or null on failure
     */
    public ObjectSpriteSheet loadOOZBurnFlameSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_OOZ_BURN_ADDR,
                Sonic2Constants.MAP_UNC_OBJ33_B_ADDR, 3, 1);
    }

    /**
     * Load OOZ SlidingSpike sprite sheet (Object 0x43).
     * ROM: ArtNem_SpikyThing at 0x8007C, mapping Obj43_MapUnc_23FE0.
     */
    public ObjectSpriteSheet loadOOZSlidingSpikeSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPIKY_THING_ADDR,
                Sonic2Constants.MAP_UNC_OBJ43_ADDR, 2, 1);
    }

    /**
     * Load OOZ pressure spring sprite sheet (Object 0x45).
     * ROM: ArtNem_PushSpring at 0x80C64, mapping Obj45_MapUnc_2451A.
     */
    public ObjectSpriteSheet loadOOZPressureSpringSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_PUSH_SPRING_ADDR,
                Sonic2Constants.MAP_UNC_OBJ45_ADDR, 2, 1);
    }

    /**
     * Load OOZ Fan horizontal sprite sheet (Object 0x3F).
     * Side-blowing fan from Oil Ocean Zone.
     * <p>
     * ROM: ArtNem_OOZFanHoriz at 0x81254, palette line 3
     * Mappings: obj3F.asm - 11 frames (ping-pong cycle through 6 unique blade positions)
     * Each frame has 3 pieces: blade + body top + body bottom.
     *
     * @return sprite sheet for horizontal fan, or null on failure
     */
    public ObjectSpriteSheet loadOOZFanHorizSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_OOZ_FAN_ADDR, "OOZFan");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ3F_HORIZ_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load OOZ Fan vertical sprite sheet (Object 0x3F, subtype bit 7 set).
     * Upward-blowing fan from Oil Ocean Zone.
     * <p>
     * ROM: Same art source as horizontal (ArtNem_OOZFanHoriz at 0x81254)
     * Mappings: obj3F.asm - 11 frames (vertical variant), each with 3 pieces:
     * blade + body left + body right.
     *
     * @return sprite sheet for vertical fan, or null on failure
     */
    public ObjectSpriteSheet loadOOZFanVertSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_OOZ_FAN_ADDR, "OOZFan");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ3F_VERT_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load OOZ LauncherBall sprite sheet (Object 0x48).
     * Transporter ball from Oil Ocean Zone that fires the player in a cardinal direction.
     * <p>
     * ROM: ArtNem_LaunchBall at 0x806E0, palette line 3, art tile 0x0368
     * Mappings: obj48.asm - 8 frames showing ball rotation/opening sequence
     *
     * @return sprite sheet for OOZ launcher ball, or null on failure
     */
    public ObjectSpriteSheet loadLaunchBallSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_LAUNCH_BALL_ADDR, "LaunchBall");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_LAUNCH_BALL_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load OOZ Launcher vertical art (Object 0x3D, subtype 0).
     * ROM: ArtNem_StripedBlocksVert at 0x8030A, palette line 3, art tile 0x0332
     * Mappings: obj3D.asm - frames 0 (intact 4x1 columns) and 1 (broken 4x4 grid)
     *
     * @return sprite sheet for vertical launcher, or null on failure
     */
    public ObjectSpriteSheet loadOOZLauncherVertSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_STRIPED_BLOCKS_VERT_ADDR, "StripedBlocksVert");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ3D_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load OOZ Launcher horizontal art (Object 0x3D, subtype != 0).
     * ROM: ArtNem_StripedBlocksHoriz at 0x81048, palette line 3, art tile 0x03FF
     * Mappings: obj3D.asm - frames 2 (intact 4x1 rows) and 3 (broken 4x4 grid)
     *
     * @return sprite sheet for horizontal launcher, or null on failure
     */
    public ObjectSpriteSheet loadOOZLauncherHorizSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_STRIPED_BLOCKS_HORIZ_ADDR, "StripedBlocksHoriz");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ3D_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load OOZ Collapsing Platform sprite sheet (Object 0x1F in Oil Ocean Zone).
     * <p>
     * ROM: ArtNem_OOZPlatform at 0x809D0, palette line 3
     *
     * @return sprite sheet for OOZ collapsing platform, or null on failure
     */
    public ObjectSpriteSheet loadOOZCollapsingPlatformSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_OOZ_COLLAPSING_PLATFORM_ADDR, "OOZCollapsingPlatform");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ1F_B_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load MCZ Collapsing Platform sprite sheet (Object 0x1F in Mystic Cave Zone).
     * <p>
     * ROM: ArtNem_MCZCollapsePlat at 0xF1ABA, palette line 3
     *
     * @return sprite sheet for MCZ collapsing platform, or null on failure
     */
    public ObjectSpriteSheet loadMCZCollapsingPlatformSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_MCZ_COLLAPSING_PLATFORM_ADDR, "MCZCollapsingPlatform");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ1F_C_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load MCZ Vine Pulley sprite sheet (Object 0x80 - MCZ variant).
     * <p>
     * ROM: ArtNem_VinePulley at 0xF1D5C, palette line 3
     * Mappings: Obj80_MapUnc_29C64 (7 frames, extension-based)
     * <p>
     * Disassembly Reference: s2.asm Obj80_MCZ_Init (loc_29A1C)
     *
     * @return sprite sheet for MCZ vine pulley, or null on failure
     */
    public ObjectSpriteSheet loadVinePulleySheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_VINE_PULLEY_ADDR, "VinePulley");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ80_MCZ_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load MCZ Crate sprite sheet (Object 0x6A - MCZ variant).
     * <p>
     * ROM: ArtNem_Crate at 0xF187C, palette line 3
     * A large 64x64 wooden crate that moves when the player walks off of it.
     * <p>
     * Disassembly Reference: s2.asm Obj6A_Init (lines 53661-53683)
     * Mappings: Obj6A_MapUnc_27D30 at s2.asm line 53850
     * <pre>
     * Map_obj6A_0002: 4 pieces
     *   spritePiece -$20, -$20, 4, 4, 0, 0, 0, 0, 0      ; top-left
     *   spritePiece    0, -$20, 4, 4, $10, 0, 0, 0, 0    ; top-right
     *   spritePiece -$20,    0, 4, 4, $10, 1, 1, 0, 0    ; bottom-left (H-flip, V-flip)
     *   spritePiece    0,    0, 4, 4, 0, 1, 1, 0, 0      ; bottom-right (H-flip, V-flip)
     * </pre>
     *
     * @return sprite sheet for MCZ wooden crate, or null on failure
     */
    public ObjectSpriteSheet loadMCZCrateSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_CRATE_ADDR, "MCZCrate");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_CRATE_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load MCZ Drawbridge sprite sheet (Object 0x81).
     * <p>
     * ROM: ArtNem_MCZGateLog at 0xF1E06, palette line 3
     * Mappings: Obj81_MapUnc_2A24E (2 frames - empty frame 0, log piece frame 1)
     * <p>
     * The drawbridge uses 8 child sprites stacked vertically, each rendering frame 1.
     * Frame 0 is an empty/null frame used when the drawbridge is invisible.
     * <p>
     * Disassembly Reference: s2.asm lines 56420-56617 (Obj81 code)
     *
     * @return sprite sheet for MCZ drawbridge log segments, or null on failure
     */
    public ObjectSpriteSheet loadMCZDrawbridgeSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_MCZ_GATE_LOG_ADDR, "MCZDrawbridge");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ81_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load MCZ Bridge sprite sheet (Object 0x77 - horizontal gate).
     * <p>
     * Reuses the same Nemesis art as the drawbridge (ArtNem_MCZGateLog at 0xF1E06),
     * but with different mappings: 5 frames of 8 pieces each representing the gate
     * in various states from closed (flat horizontal) to open (split into two columns).
     * <p>
     * Disassembly Reference: mappings/sprite/obj77.asm (Obj77_MapUnc_29064)
     *
     * @return sprite sheet for MCZ bridge gate, or null on failure
     */
    public ObjectSpriteSheet loadMCZBridgeSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_MCZ_GATE_LOG_ADDR, "MCZBridge");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ77_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load VineSwitch sprite sheet (Object 0x7F - MCZ pull switch).
     * <p>
     * ROM: ArtNem_VineSwitch at 0xF1C64, palette line 3
     * Mappings: Obj7F_MapUnc_29938 (2 frames - normal and grabbed)
     * <p>
     * Disassembly Reference: mappings/sprite/obj7F.asm (Obj7F_MapUnc_29938)
     *
     * @return sprite sheet for MCZ vine switch, or null on failure
     */
    public ObjectSpriteSheet loadVineSwitchSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_VINE_SWITCH_ADDR, "VineSwitch");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ7F_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load WFZ Hook sprite sheet (Object 0x80 - WFZ variant).
     * <p>
     * ROM: ArtNem_WfzHook at 0x8D388, palette line 1
     * Mappings: Obj80_MapUnc_29DD0 (13 frames, extension-based)
     * <p>
     * Disassembly Reference: s2.asm Obj80_WFZ_Init at loc_29C06
     *
     * @return sprite sheet for WFZ hook on chain, or null on failure
     */
    public ObjectSpriteSheet loadWFZHookSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_HOOK_ADDR, "WFZHook");
        if (patterns.length == 0) {
            return null;
        }
        // ROM Obj80_WFZ_Init draws with make_art_tile(ArtTile_ArtNem_WfzHook_Fudge,1,0):
        // the mappings are authored against base $03FE, but the art is loaded to
        // $03FA (plreq ArtTile_ArtNem_WfzHook). Apply the +4 "fudge" tile offset so
        // the chain (piece tile 0) and hook (piece tile 4) resolve to the ROM tiles;
        // without it the chain shows the 4 leading art tiles and the hook renders the
        // chain tiles (garbled). See s2.asm:56667-56668, s2.constants.asm:2399-2400.
        int hookFudgeOffset = Sonic2Constants.ART_TILE_WFZ_HOOK_FUDGE - Sonic2Constants.ART_TILE_WFZ_HOOK;
        List<SpriteMappingFrame> mappings = loadMappingFramesWithTileOffset(
                Sonic2Constants.MAP_UNC_OBJ80_WFZ_ADDR, hookFudgeOffset);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    /**
     * Load ObjBB removed WFZ unknown object sheet.
     * ROM: ObjBB_SubObjData uses ObjBB_MapUnc_3BBA0 with
     * make_art_tile(ArtTile_ArtNem_Unknown,1,0). ArtTile_ArtNem_Unknown is
     * $03FA, the same tile base as ArtTile_ArtNem_WfzHook.
     */
    public ObjectSpriteSheet loadWfzUnknownSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_HOOK_ADDR, "WFZUnknown");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJBB_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    /**
     * Load WFZ floating platform sheet (Obj19 / WFZPlatform).
     * ROM: ArtNem_WfzFloatingPlatform + Obj19_MapUnc_2222A,
     * make_art_tile(ArtTile_ArtNem_WfzFloatingPlatform,1,1).
     */
    public ObjectSpriteSheet loadWfzFloatingPlatformSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_PLATFORM_ADDR, "WFZFloatingPlatform");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJ19_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    /**
     * Load Tornado main sheet (ObjB2 subtype $50/$52/$54).
     * ROM: ArtNem_Tornado + ObjB2_MapUnc_3AFF2, palette line 0.
     */
    public ObjectSpriteSheet loadTornadoSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_TORNADO_ADDR, "Tornado");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJB2_A_ADDR);
        Pattern[] combined = createTornadoCombinedPatterns(patterns, mappings);
        return new ObjectSpriteSheet(combined, mappings, 0, 1);
    }

    /**
     * Load Tornado thruster sheet (ObjB2 subtype $5C).
     * ROM: ArtNem_TornadoThruster + ObjB2_MapUnc_3B292, palette line 0.
     */
    public ObjectSpriteSheet loadTornadoThrusterSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_TORNADO_THRUSTER_ADDR, "TornadoThruster");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJB2_B_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    /**
     * Load WFZ thrust sheet used by ObjB2 subtypes $56/$58.
     * ROM: ArtNem_WfzThrust + ObjBC_MapUnc_3BC08, palette line 2.
     */
    public ObjectSpriteSheet loadWfzThrustSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_THRUST_ADDR, "WFZThrust");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJBC_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 2, 1);
    }

    // ========== WFZ Tilting Platform (Object 0xB6) ==========

    /**
     * Load WFZ Tilting Platform (ObjB6) sprite sheet.
     * ROM: ArtNem_WfzTiltPlatforms at 0x8E010, palette line 1, priority bit set.
     * Art tile: make_art_tile(ArtTile_ArtNem_WfzTiltPlatforms,1,1) = palette 1, priority.
     * 4 mapping frames: horizontal flat, tilted right, vertical, tilted left.
     * <p>
     * Disassembly Reference: s2.asm line 79605 (ObjB6_SubObjData), mappings/sprite/objB6.asm
     *
     * @return sprite sheet for WFZ tilting platform, or null on failure
     */
    public ObjectSpriteSheet loadWFZTiltPlatformSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_TILT_PLATFORMS_ADDR, "WFZTiltPlatforms");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJB6_ADDR);
        // Palette line 1, priority bit set (from ObjB6_SubObjData)
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    // ========== WFZ Belt Platform (Object 0xBD) ==========

    /**
     * Load WFZ Belt Platform (ObjBD) sprite sheet - ascending/descending metal platform from WFZ.
     * ROM: ArtNem_WfzBeltPlatform at 0x8DD0C, palette line 3, priority bit set.
     * Art tile: make_art_tile(ArtTile_ArtNem_WfzBeltPlatform,3,1) = $040E | palette 3, priority.
     * 3 mapping frames: folded, mid, unfolded. Each frame has 2 pieces of 3x1 tiles (48x8 px).
     * <p>
     * Disassembly Reference: s2.asm line 80062 (ObjBD_SubObjData), mappings/sprite/objBD.asm
     *
     * @return sprite sheet for WFZ belt platform, or null on failure
     */
    public ObjectSpriteSheet loadWFZBeltPlatformSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_BELT_PLATFORM_ADDR, "WFZBeltPlatform");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJBD_ADDR);
        // ROM make_art_tile(ArtTile_ArtNem_WfzBeltPlatform,3,1): palette line 3 (passed directly).
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    // ========== WFZ Stick / unused badnik (Object 0xBF) ==========

    /**
     * Load ObjBF WFZStick sprite sheet.
     * ROM: ArtNem_WfzUnusedBadnik at 0x8DDF6, mappings ObjBF_MapUnc_3BEE0,
     * make_art_tile(ArtTile_ArtNem_WfzUnusedBadnik,3,1).
     */
    public ObjectSpriteSheet loadWfzStickSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_UNUSED_BADNIK_ADDR, "WfzUnusedBadnik");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJBF_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    // ========== WFZ Laser (Object 0xB9) ==========

    /**
     * Load WFZ Laser (ObjB9) sprite sheet - horizontal laser beam from Wing Fortress Zone.
     * ROM: ArtNem_WfzHrzntlLazer at 0x8DC42, palette line 2, priority bit set.
     * Art tile: make_art_tile(ArtTile_ArtNem_WfzHrzntlLazer,2,1) = $03C3 | palette 2, priority.
     * Single mapping frame with 6 pieces forming a wide horizontal laser beam.
     *
     * @return sprite sheet for WFZ laser, or null on failure
     */
    public ObjectSpriteSheet loadWFZLaserSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_HRZNTL_LAZER_ADDR, "WFZHrzntlLazer");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJB9_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 2, 1);
    }

    // ========== WFZ VerticalLaser (Object 0xB7) ==========

    /**
     * Load WFZ Vertical Laser (ObjB7) sprite sheet - huge unused vertical laser from WFZ.
     * Spawned as a child by ObjB6 (TiltingPlatform) during the fire countdown behavior.
     * ROM: ArtNem_WfzVrtclLazer at 0x8DA6E, palette line 2, priority bit set.
     * Art tile: make_art_tile(ArtTile_ArtNem_WfzVrtclLazer,2,1) = palette 2, priority.
     * Single mapping frame: 16 pieces (8 pairs of 3x4 tiles) forming a tall vertical beam.
     * width_pixels = $18.
     * <p>
     * Disassembly Reference: s2.asm line 79662 (ObjB7_SubObjData), mappings/sprite/objB7.asm
     *
     * @return sprite sheet for WFZ vertical laser, or null on failure
     */
    public ObjectSpriteSheet loadWFZVerticalLaserSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_VRTCL_LAZER_ADDR, "WFZVrtclLazer");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJB7_ADDR);
        // Palette line 2, priority bit set (from ObjB7_SubObjData)
        return new ObjectSpriteSheet(patterns, mappings, 2, 1);
    }

    // ========== WFZ LateralCannon / Retracting Platform (Object 0xBE) ==========

    /**
     * Load WFZ LateralCannon (ObjBE) sprite sheet - retracting platform from Wing Fortress Zone.
     * ROM: ArtNem_WfzGunPlatform at 0x8D540, palette line 3, priority bit set.
     * Mappings at ObjBE_MapUnc_3BE46 (5 frames: retraction/extension animation).
     * Art tile: make_art_tile(ArtTile_ArtNem_WfzGunPlatform,3,1) = palette 3, priority.
     */
    public ObjectSpriteSheet loadWfzGunPlatformSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_GUN_PLATFORM_ADDR, "WfzGunPlatform");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJBE_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    // ========== WFZ SpeedLauncher (Object 0xC0) ==========

    /**
     * Load WFZ SpeedLauncher (ObjC0) sprite sheet - catapult platform from Wing Fortress Zone.
     * ROM: ArtNem_WfzLaunchCatapult at 0x8DCA2, palette line 1, no priority.
     * Mappings at ObjC0_MapUnc_3C098 (1 frame: 2 pieces forming the catapult).
     * Art tile: make_art_tile(ArtTile_ArtNem_WfzLaunchCatapult,1,0) = palette 1, no priority.
     */
    public ObjectSpriteSheet loadWfzLaunchCatapultSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_LAUNCH_CATAPULT_ADDR, "WfzLaunchCatapult");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJC0_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    // ========== WFZ BreakablePlating (Object 0xC1) ==========

    /**
     * Load WFZ BreakablePlating (ObjC1) sprite sheet - breakable plating from Wing Fortress Zone.
     * ROM: ArtNem_BreakPanels at 0x7FF98, palette line 3, priority bit set.
     * Art tile: make_art_tile(ArtTile_ArtNem_BreakPanels,3,1) = palette 3, priority.
     * <p>
     * Mappings at ObjC1_MapUnc_3C280 (6 frames).
     * <p>
     * Disassembly Reference: s2.asm line 80560 (ObjC1_SubObjData), mappings/sprite/objC1.asm
     */
    public ObjectSpriteSheet loadWfzBreakPanelsSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_BREAK_PANELS_ADDR, "BreakPanels");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJC1_ADDR);
        // Palette line 3, priority bit set (from ObjC1_SubObjData: make_art_tile(ArtTile_ArtNem_BreakPanels,3,1))
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    // ========== WFZ Rivet (Object 0xC2) ==========

    /**
     * Load WFZ Rivet (ObjC2) sprite sheet - rivet at end of WFZ that opens the ship when busted.
     * ROM: ArtNem_WfzSwitch at 0x7FF2A, palette line 1, priority bit set.
     * Art tile: make_art_tile(ArtTile_ArtNem_WfzSwitch,1,1) = palette 1, priority.
     * <p>
     * Mappings: ObjC2_MapUnc_3C3C2 (1 frame, 2 pieces)
     * <pre>
     * Frame 0: spritePiece -$10, -8, 2, 2, 0, 0, 0, 0, 0  (left half)
     *          spritePiece    0, -8, 2, 2, 0, 1, 0, 0, 0  (right half, x-flipped)
     * </pre>
     * <p>
     * Disassembly Reference: s2.asm line 80620 (ObjC2_SubObjData), mappings/sprite/objC2.asm
     */
    public ObjectSpriteSheet loadWfzRivetSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_SWITCH_ADDR, "WfzSwitch");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJC2_ADDR);
        // Palette line 1, priority bit set (from ObjC2_SubObjData: make_art_tile(ArtTile_ArtNem_WfzSwitch,1,1))
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    // ========== WFZ Conveyor Belt Wheel (Object 0xBA) ==========

    /**
     * Load WFZ Conveyor Belt Wheel (ObjBA) sprite sheet - static wheel decoration from Wing Fortress Zone.
     * ROM: ArtNem_WfzConveyorBeltWheel at 0x8D7D8, palette line 2, priority bit set.
     * Art tile: make_art_tile(ArtTile_ArtNem_WfzConveyorBeltWheel,2,1) = $03EA | palette 2, priority.
     * <p>
     * Single mapping frame: one 4x4 piece (32x32 pixels) at offset (-16, -16) from center.
     * <p>
     * Disassembly Reference: s2.asm lines 79855-79860 (ObjBA_SubObjData, ObjBA_MapUnc_3BB70)
     *
     * @return sprite sheet for WFZ conveyor belt wheel, or null on failure
     */
    public ObjectSpriteSheet loadWFZConveyorBeltWheelSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_CONVEYOR_BELT_WHEEL_ADDR, "WFZConveyorBeltWheel");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OBJBA_ADDR);
        // Palette line 2 = paletteIndex 2 (engine maps directly to VDP palette lines)
        return new ObjectSpriteSheet(patterns, mappings, 2, 1);
    }

    // ========== WFZ WallTurret (Object 0xB8) ==========

    /**
     * Load WFZ WallTurret (ObjB8) sprite sheet - wall-mounted turret from Wing Fortress Zone.
     * ROM: ArtNem_WfzWallTurret at 0x8D1A0, palette line 0.
     * Mappings at ObjB8_Obj98_MapUnc_3BA46 (5 frames: 3 turret orientations + 2 projectile frames).
     * Art tile: make_art_tile(ArtTile_ArtNem_WfzWallTurret,0,0) = $03AB | palette 0, no priority.
     */
    public ObjectSpriteSheet loadWallTurretSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_WALL_TURRET_ADDR, "WFZWallTurret");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_WFZ_WALL_TURRET_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    // ========== SCZ Cloud (Object 0xB3) ==========

    /**
     * Load SCZ Cloud sprite sheet.
     * ROM: ArtNem_Clouds at 0x8DAFC, palette line 2.
     * Art tile: make_art_tile(ArtTile_ArtNem_Clouds,2,0) = $054F | palette 2, no priority.
     */
    public ObjectSpriteSheet loadCloudSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_CLOUDS_ADDR, "Clouds");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_CLOUD_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 2, 1);
    }

    // ========== WFZ Vertical Propeller (Object 0xB4) ==========

    /**
     * Load WFZ Vertical Propeller sprite sheet (Object 0xB4).
     * <p>
     * ROM: ArtNem_WfzVrtclPrpllr at 0x8DEB8, palette line 1, priority bit set.
     * Art tile: make_art_tile(ArtTile_ArtNem_WfzVrtclPrpllr,1,1) = $0561 | palette 1, priority.
     * <p>
     * Disassembly Reference: s2.asm ObjB4_SubObjData (lines 79195-79196)
     *
     * @return sprite sheet for vertical propeller, or null on failure
     */
    public ObjectSpriteSheet loadVPropellerSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_VRTCL_PRPLLR_ADDR, "WfzVrtclPrpllr");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_VPROPELLER_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    // ========== WFZ/SCZ Horizontal Propeller (Object 0xB5) ==========

    /**
     * Load HPropeller sprite sheet (Object 0xB5).
     * Horizontal spinning propeller blades from WFZ/SCZ.
     * <p>
     * ROM: ArtNem_WfzHrzntlPrpllr at 0x8DEE8, palette line 1, priority bit set.
     * SubObjData: make_art_tile(ArtTile_ArtNem_WfzHrzntlPrpllr,1,1)
     * <p>
     * Disassembly Reference: s2.asm ObjB5_SubObjData (line 79299)
     *
     * @return sprite sheet for HPropeller, or null on failure
     */
    public ObjectSpriteSheet loadHPropellerSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_WFZ_HRZNTL_PRPLLR_ADDR, "WfzHrzntlPrpllr");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_HPROPELLER_ADDR);
        // Palette line 1, priority bit set (make_art_tile with pal=1, pri=1)
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    /**
     * Load CNZ LauncherSpring vertical sprite sheet (Object 0x85 subtype 0x00).
     * <p>
     * ROM: ArtNem_CNZVertPlunger at 0x81C96, palette line 0
     *
     * @return sprite sheet for vertical launcher spring, or null on failure
     */
    public ObjectSpriteSheet loadLauncherSpringVertSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CNZ_VERT_PLUNGER_ADDR,
                Sonic2Constants.MAP_UNC_LAUNCHER_SPRING_VERT_ADDR, 0, 1);
    }

    /**
     * Load CNZ LauncherSpring diagonal sprite sheet (Object 0x85 subtype 0x81).
     * <p>
     * ROM: ArtNem_CNZDiagPlunger at 0x81AB0, palette line 0
     *
     * @return sprite sheet for diagonal launcher spring, or null on failure
     */
    public ObjectSpriteSheet loadLauncherSpringDiagSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CNZ_DIAG_PLUNGER_ADDR,
                Sonic2Constants.MAP_UNC_LAUNCHER_SPRING_DIAG_ADDR, 0, 1);
    }

    /**
     * Load CNZ Rect Blocks sprite sheet (Object 0xD2) - "caterpillar" flashing blocks.
     * <p>
     * ROM: ArtNem_CNZSnake at 0x81600, palette line 2
     * 16 animation frames showing blocks moving around a rectangular path.
     *
     * @return sprite sheet for CNZ rect blocks, or null on failure
     */
    public ObjectSpriteSheet loadCNZRectBlocksSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CNZ_SNAKE_ADDR,
                Sonic2Constants.MAP_UNC_CNZ_RECT_BLOCKS_ADDR, 2, 1);
    }

    /**
     * Load CNZ Big Block sprite sheet (Object 0xD4) - large 64x64 oscillating platform.
     * <p>
     * ROM: ArtNem_BigMovingBlock at 0x816C8, palette line 2
     * Single frame with 4 pieces in 2x2 grid with flip symmetry.
     *
     * @return sprite sheet for CNZ big block, or null on failure
     */
    public ObjectSpriteSheet loadCNZBigBlockSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CNZ_BIG_BLOCK_ADDR,
                Sonic2Constants.MAP_UNC_CNZ_BIG_BLOCK_ADDR, 2, 1);
    }

    /**
     * Load CNZ Elevator sprite sheet (Object 0xD5) - vertical platform that moves when stood on.
     * <p>
     * ROM: ArtNem_CNZElevator at 0x817B4, palette line 2
     * Visual: 32x16 pixel platform made of two 2x2 (16x16) pieces
     *
     * @return sprite sheet for CNZ elevator, or null on failure
     */
    public ObjectSpriteSheet loadCNZElevatorSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CNZ_ELEVATOR_ADDR,
                Sonic2Constants.MAP_UNC_CNZ_ELEVATOR_ADDR, 2, 1);
    }

    /**
     * Load CNZ Cage sprite sheet (Object 0xD6 "PointPokey") - casino cage that captures player.
     * <p>
     * ROM: ArtNem_CNZCage at 0x81826, palette line 0 (frame 0) and 1 (frame 1)
     * Visual: 48x48 pixel cage made of 6 pieces in a 2x3 grid (3 rows, 2 columns)
     * Frame 0: Idle cage, Frame 1: Active/lit cage with priority
     *
     * @return sprite sheet for CNZ cage, or null on failure
     */
    public ObjectSpriteSheet loadCNZCageSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CNZ_CAGE_ADDR,
                Sonic2Constants.MAP_UNC_CNZ_CAGE_ADDR, 0, 1);
    }

    /**
     * Load CNZ Bonus Spike (ObjD3) sprite sheet - spiked ball from slot machine.
     * ROM: ArtNem_CNZBonusSpike at 0x81668, palette line 0
     * <p>
     * This is a simple 2x2 tile (16x16 pixel) spiked ball sprite used as a
     * "bad" prize when the slot machine shows Eggman faces.
     */
    public ObjectSpriteSheet loadCNZBonusSpikeSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CNZ_BONUS_SPIKE_ADDR,
                Sonic2Constants.MAP_UNC_CNZ_BONUS_SPIKE_ADDR, 0, 1);
    }

    // ========== Badnik Sheet Loaders ==========
    // These are Sonic 2-specific and loaded directly by Sonic2ObjectArtProvider

    /**
     * Load Masher (Obj5C) sprite sheet - leaping piranha from EHZ.
     * ROM: ArtNem_Masher at 0x839EA, palette line 0
     */
    public ObjectSpriteSheet loadMasherSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_MASHER_ADDR,
                Sonic2Constants.MAP_UNC_MASHER_ADDR, 0, 1);
    }

    /**
     * Load Buzzer (Obj4B) sprite sheet - flying bee from EHZ.
     * ROM: ArtNem_Buzzer at 0x8316A, palette line 0
     */
    public ObjectSpriteSheet loadBuzzerSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_BUZZER_ADDR,
                Sonic2Constants.MAP_UNC_BUZZER_ADDR, 0, 1);
    }

    /**
     * Load Coconuts (Obj9D) sprite sheet - monkey badnik from EHZ.
     * ROM: ArtNem_Coconuts at 0x8A87A, palette line 0
     */
    public ObjectSpriteSheet loadCoconutsSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_COCONUTS_ADDR,
                Sonic2Constants.MAP_UNC_COCONUTS_ADDR, 0, 1);
    }

    /**
     * Load Crawlton (Obj9E) sprite sheet - snake badnik from MCZ.
     * ROM: ArtNem_Crawlton at 0x8AB36, palette line 1.
     * 3 frames: frame 0/1 = head (3x2 tiles), frame 2 = body segment (2x2 tiles).
     */
    public ObjectSpriteSheet loadCrawltonSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CRAWLTON_ADDR,
                Sonic2Constants.MAP_UNC_CRAWLTON_ADDR, 1, 1);
    }

    /**
     * Load Flasher (ObjA3) sprite sheet - firefly/glowbug badnik from MCZ.
     * ROM: ArtNem_Flasher at 0x8AC5E, palette line 0.
     */
    public ObjectSpriteSheet loadFlasherSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_FLASHER_ADDR,
                Sonic2Constants.MAP_UNC_FLASHER_ADDR, 0, 1);
    }

    public ObjectSpriteSheet loadAsteronSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_MTZ_SUPERNOVA_ADDR,
                Sonic2Constants.MAP_UNC_ASTERON_ADDR, 0, 1);
    }

    /**
     * Load Shellcracker (Obj9F/ObjA0) sprite sheet - crab badnik from MTZ.
     * ROM: ArtNem_Shellcracker at 0x8B058, palette line 0.
     * make_art_tile(ArtTile_ArtNem_Shellcracker,0,0) - palette 0, priority 0.
     * 6 frames from Obj9F_MapUnc_38314 (shared by Obj9F body and ObjA0 claw):
     *   Frame 0: Walking 1 (body with claws + legs down)
     *   Frame 1: Walking 2 (body with legs mid)
     *   Frame 2: Walking 3 (body with legs up/reversed)
     *   Frame 3: Attack pose (body with no top claws, legs down)
     *   Frame 4: Claw joint piece (1x1 tile)
     *   Frame 5: Claw segment (3x3 tiles)
     */
    public ObjectSpriteSheet loadShellcrackerSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SHELLCRACKER_ADDR,
                Sonic2Constants.MAP_UNC_SHELLCRACKER_ADDR, 0, 0);
    }

    /**
     * Load Slicer (ObjA1) sprite sheet - praying mantis badnik from MTZ.
     * ROM: ArtNem_MtzMantis at 0x8AD80, palette line 1.
     * make_art_tile(ArtTile_ArtNem_MtzMantis,1,0) - palette 1, priority 0.
     * 9 frames from ObjA1_MapUnc_385E2 (shared by ObjA1 and ObjA2):
     *   Frame 0: Walking 1 (body + claws down)
     *   Frame 1: Walking 2 (body + claws mid)
     *   Frame 2: Walking 3 (body + claws up)
     *   Frame 3: Arms raised (preparing to throw)
     *   Frame 4: Body only (after throw)
     *   Frame 5: Pincer projectile (claw down-right)
     *   Frame 6: Pincer projectile (claw down-left)
     *   Frame 7: Pincer projectile (claw rotated 1)
     *   Frame 8: Pincer projectile (claw rotated 2)
     */
    public ObjectSpriteSheet loadSlicerSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_MTZ_MANTIS_ADDR,
                Sonic2Constants.MAP_UNC_SLICER_ADDR, 1, 0);
    }

    /**
     * Load Spiny (ObjA5) sprite sheet - crawling badnik from CPZ.
     * ROM: ArtNem_Spiny at 0x8B430, palette line 1 (make_art_tile(ArtTile_ArtNem_Spiny,1,0))
     */
    public ObjectSpriteSheet loadSpinySheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SPINY_ADDR,
                Sonic2Constants.MAP_UNC_SPINY_ADDR, 1, 1);
    }

    /**
     * Load Grabber (ObjA7) sprite sheet - spider badnik from CPZ.
     * ROM: ArtNem_Grabber at 0x8B6B4, palette line 1
     */
    public ObjectSpriteSheet loadGrabberSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_GRABBER_ADDR,
                Sonic2Constants.MAP_UNC_GRABBER_ADDR, 1, 1);
    }

    /**
     * Load Grabber String (ObjAA) sprite sheet - thread connecting Grabber to anchor.
     * Uses same patterns as Grabber but different mappings.
     * ROM: ArtNem_Grabber at 0x8B6B4, palette line 1
     */
    public ObjectSpriteSheet loadGrabberStringSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_GRABBER_ADDR,
                Sonic2Constants.MAP_UNC_GRABBER_STRING_ADDR, 1, 1);
    }

    /**
     * Load ChopChop (Obj91) sprite sheet - piranha badnik from ARZ.
     * ROM: ArtNem_ChopChop at 0x89B9A, palette line 1
     */
    public ObjectSpriteSheet loadChopChopSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CHOPCHOP_ADDR,
                Sonic2Constants.MAP_UNC_CHOPCHOP_ADDR, 1, 1);
    }

    /**
     * Load Whisp (Obj8C) sprite sheet - blowfly badnik from ARZ.
     * ROM: ArtNem_Whisp at 0x895E4
     * Uses palette line 1.
     * 2 animation frames, each with 2 sprite pieces (3x1 tiles stacked = 24x16 pixels).
     */
    public ObjectSpriteSheet loadWhispSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_WHISP_ADDR,
                Sonic2Constants.MAP_UNC_WHISP_ADDR, 1, 1);
    }

    /**
     * Load ArrowShooter (Obj22) sprite sheet - arrow shooter from ARZ.
     * ROM: ArtNem_ArrowAndShooter at 0x90020, Obj22_MapUnc_25804
     * Uses palette line 0 for arrow (frame 0) and palette line 1 for shooter.
     */
    public ObjectSpriteSheet loadArrowShooterSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_ARROW_SHOOTER_ADDR,
                Sonic2Constants.MAP_UNC_ARROW_SHOOTER_ADDR, 0, 1);
    }

    /**
     * Load Grounder (Obj8D/8E) sprite sheet - drill badnik from ARZ.
     * ROM: ArtNem_Grounder at 0x8970E, palette line 1
     * 5 frames: 2 idle (symmetric with flipped parts) + 3 walking
     */
    public ObjectSpriteSheet loadGrounderSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_GROUNDER_ADDR,
                Sonic2Constants.MAP_UNC_GROUNDER_ADDR, 1, 1);
    }

    /**
     * Load Grounder Rock (Obj90) sprite sheet - rock projectiles from ARZ.
     * Uses same Grounder art but different mappings and palette line 2.
     * 3 frames: 2x2 large rock, 1x1 small rocks (2 variants)
     */
    public ObjectSpriteSheet loadGrounderRockSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_GROUNDER_ADDR,
                Sonic2Constants.MAP_UNC_GROUNDER_ROCK_ADDR, 2, 0);
    }

    /**
     * Load Spiker (Obj92/93) sprite sheet - drill badnik from HTZ.
     * <p>
     * Spiker uses two separate art sources in VRAM:
     * - Sol badnik art at tile $3DE (ArtNem_Sol)
     * - Spiker art at tile $520 (ArtNem_Spiker)
     * <p>
     * The mappings reference absolute tile indices, so we build a combined
     * sheet using a base tile of $3DE and offset Spiker tiles accordingly.
     */
    public ObjectSpriteSheet loadSpikerSheet() {
        Pattern[] solPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SOL_ADDR, "Sol");
        Pattern[] spikerPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SPIKER_ADDR, "Spiker");
        if (solPatterns.length == 0 && spikerPatterns.length == 0) {
            return null;
        }

        final int baseTile = 0x3DE;    // ArtTile_ArtNem_Sol
        final int spikerTile = 0x520;  // ArtTile_ArtNem_Spiker
        final int spikerOffset = spikerTile - baseTile;
        final int maxTileUsed = 0x533; // Highest tile referenced by Obj92/93 mappings

        int requiredSize = Math.max(spikerOffset + spikerPatterns.length, solPatterns.length);
        requiredSize = Math.max(requiredSize, (maxTileUsed - baseTile) + 1);

        Pattern[] combined = new Pattern[requiredSize];
        if (solPatterns.length > 0) {
            System.arraycopy(solPatterns, 0, combined, 0,
                    Math.min(solPatterns.length, combined.length));
        }
        if (spikerPatterns.length > 0 && spikerOffset < combined.length) {
            int copyLen = Math.min(spikerPatterns.length, combined.length - spikerOffset);
            System.arraycopy(spikerPatterns, 0, combined, spikerOffset, copyLen);
        }

        for (int i = 0; i < combined.length; i++) {
            if (combined[i] == null) {
                combined[i] = new Pattern();
            }
        }

        // ROM: art_tile = make_art_tile(ArtTile_ArtKos_LevelArt, 0, 0) — tile indices
        // are absolute VRAM addresses. Offset by -baseTile to convert to array indices.
        List<SpriteMappingFrame> mappings = loadMappingFramesWithTileOffset(
                Sonic2Constants.MAP_UNC_SPIKER_ADDR, -baseTile);
        return new ObjectSpriteSheet(combined, mappings, 0, 1);
    }

    /**
     * Load Sol (Obj95) sprite sheet - HTZ fireball badnik.
     * ROM:
     * - Fireball 1 art at tile $39E (ArtNem_HtzFireball1) - 20 tiles
     * - Sol badnik art at tile $3DE (ArtNem_Sol) - 4 tiles
     * - Fireball mappings use tile $3AE (offset 0x10 within fireball art)
     *
     * Palette usage (matching original ROM obj95.asm):
     * - Sprite sheet base palette: 0 (from art_tile = make_art_tile(ArtTile_ArtKos_LevelArt, 0, 0))
     * - Body frames (0-2): piece palette 0 → final palette 0 (character palette)
     * - Fireball frames (3-4): piece palette 1 → final palette 1 (zone palette line 1 = HTZ)
     *
     * The fireball art contains graphics designed for palette 1, which includes
     * orange/red fire colors in the HTZ zone palette.
     */
    public ObjectSpriteSheet loadSolSheet() {
        Pattern[] fireballPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_HTZ_FIREBALL1_ADDR,
                "HtzFireball1");
        Pattern[] solPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SOL_ADDR, "Sol");
        if (fireballPatterns.length == 0 && solPatterns.length == 0) {
            return null;
        }

        final int baseTile = 0x39E;      // ArtTile_ArtNem_HtzFireball1
        final int solTile = 0x3DE;       // ArtTile_ArtNem_Sol
        final int fireballOffset = 0;    // Fireball art starts at base tile
        final int solOffset = solTile - baseTile;
        final int maxTileUsed = 0x3E1;   // Highest tile referenced by Obj95 mappings

        int requiredSize = Math.max(fireballOffset + fireballPatterns.length, solOffset + solPatterns.length);
        requiredSize = Math.max(requiredSize, (maxTileUsed - baseTile) + 1);
        Pattern[] combined = new Pattern[requiredSize];
        if (fireballPatterns.length > 0 && fireballOffset < combined.length) {
            int copyLen = Math.min(fireballPatterns.length, combined.length - fireballOffset);
            System.arraycopy(fireballPatterns, 0, combined, fireballOffset, copyLen);
        }
        if (solPatterns.length > 0 && solOffset < combined.length) {
            int copyLen = Math.min(solPatterns.length, combined.length - solOffset);
            System.arraycopy(solPatterns, 0, combined, solOffset, copyLen);
        }

        for (int i = 0; i < combined.length; i++) {
            if (combined[i] == null) {
                combined[i] = new Pattern();
            }
        }

        // ROM: art_tile = make_art_tile(ArtTile_ArtKos_LevelArt, 0, 0) — mapping pieces
        // use absolute VRAM tile indices ($3DE for body, $3AE for fireball). Offset by
        // -baseTile to convert to array indices within the combined pattern array.
        List<SpriteMappingFrame> mappings = loadMappingFramesWithTileOffset(
                Sonic2Constants.MAP_UNC_SOL_ADDR, -baseTile);
        return new ObjectSpriteSheet(combined, mappings, 0, 1);
    }

    /**
     * Load Rexon (Obj94/96/97) sprite sheet - lava snake from HTZ.
     * ROM: ArtNem_Rexon at 0x89DEC, palette line 3.
     * 4 frames:
     * - Frame 0: Body (3x2 = 6 tiles, 27x16 pixels)
     * - Frame 1: Neck segment (2x2 = 4 tiles, 16x16 pixels)
     * - Frame 2: Full body view (4x2 = 8 tiles, 32x16 pixels)
     * - Frame 3: Projectile (1x1 = 1 tile, 8x8 pixels)
     */
    public ObjectSpriteSheet loadRexonSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_REXON_ADDR,
                Sonic2Constants.MAP_UNC_REXON_ADDR, 3, 1);
    }

    /**
     * Load Crawl (ObjC8) sprite sheet - bouncer badnik from CNZ.
     * ROM: ArtNem_Crawl at 0x901A4, palette line 0
     * 4 frames: 2 walking + 2 impact (ground/air)
     */
    public ObjectSpriteSheet loadCrawlSheet() {
        // ROM: make_art_tile(ArtTile_ArtNem_Crawl, 0, 1) — palette 0
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CRAWL_ADDR,
                Sonic2Constants.MAP_UNC_CRAWL_ADDR, 0, 1);
    }

    /**
     * Load Nebula (Obj99) sprite sheet - bomber badnik from SCZ.
     * ROM: ArtNem_Nebula at 0x8A142, palette line 1, priority 1.
     * 5 frames: 0-3 = body with propeller animation, 4 = bomb.
     * Shared mappings (Obj99_Obj98_MapUnc_3789A) for Nebula and its bomb projectile.
     */
    public ObjectSpriteSheet loadNebulaSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_NEBULA_ADDR,
                Sonic2Constants.MAP_UNC_NEBULA_ADDR, 1, 1);
    }

    /**
     * Load Turtloid (Obj9A) sprite sheet - turtle badnik from SCZ.
     * ROM: ArtNem_Turtloid at 0x8A362, palette line 0.
     * Shared mappings (Obj9A_Obj98_MapUnc_37B62 / Map_obj9C) for Turtloid body,
     * rider, projectile, and jet exhaust (10 frames total).
     */
    public ObjectSpriteSheet loadTurtloidSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_TURTLOID_ADDR,
                Sonic2Constants.MAP_UNC_TURTLOID_ADDR, 0, 1);
    }

    /**
     * Load Balkiry (ObjAC) sprite sheet - jet badnik from SCZ.
     * ROM: ArtNem_Balkrie at 0x8BC16, palette line 0, priority 1.
     * Uses its own mappings (ObjAC_MapUnc_393CC / Map_objAC) with 2 frames:
     *   Frame 0: Body without exhaust
     *   Frame 1: Body with exhaust (Balkiry init sets mapping_frame=1)
     */
    public ObjectSpriteSheet loadBalkirySheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_BALKIRY_ADDR,
                Sonic2Constants.MAP_UNC_BALKIRY_ADDR, 0, 1);
    }

    /**
     * Load Clucker (ObjAD/ObjAE) sprite sheet - chicken turret badnik from WFZ.
     * ROM: ArtNem_WfzScratch at 0x8B9DC, palette line 0.
     * Shared by CluckerBase (frame 12), Clucker (frames 0-11, 21), and projectile (frames 13-20).
     * 22 frames from Map_objAE (ObjAD_Obj98_MapUnc_395B4).
     */
    public ObjectSpriteSheet loadCluckerSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_WFZ_SCRATCH_ADDR,
                Sonic2Constants.MAP_UNC_CLUCKER_ADDR, 0, 1);
    }

    /**
     * Load Octus (Obj4A) sprite sheet - octopus badnik from OOZ.
     * ROM: ArtNem_Octus at 0x8336A, palette line 1.
     * 7 frames: body poses (0-4), bullet frames (5-6).
     */
    public ObjectSpriteSheet loadOctusSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_OCTUS_ADDR,
                Sonic2Constants.MAP_UNC_OCTUS_ADDR, 1, 1);
    }

    /**
     * Load Aquis (Obj50) sprite sheet - seahorse badnik from OOZ.
     * ROM: ArtNem_Aquis at 0x8368A, palette line 1.
     * 9 frames from Map_obj50.
     */
    public ObjectSpriteSheet loadAquisSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_AQUIS_ADDR,
                Sonic2Constants.MAP_UNC_AQUIS_ADDR, 1, 1);
    }

    /**
     * Load Seesaw (Obj14) sprite sheet - tilting platform from HTZ.
     * ROM: ArtNem_HtzSeeSaw at 0xF096E, palette line 2
     * 2 frames: tilted (0) and flat (1). Frame 2 uses frame 0 with x-flip.
     */
    public ObjectSpriteSheet loadSeesawSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_HTZ_SEESAW_ADDR,
                Sonic2Constants.MAP_UNC_SEESAW_ADDR, 0, 0);
    }

    /**
     * Load Seesaw Ball sprite sheet - ball child of Seesaw from HTZ.
     * ROM: ArtNem_Sol at 0xF0D4A, palette line 0 (alternates with line 1 for animation)
     * 2 frames: same sprite with different palette lines for blinking effect.
     */
    public ObjectSpriteSheet loadSeesawBallSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_SOL_ADDR,
                Sonic2Constants.MAP_UNC_SEESAW_BALL_ADDR, 0, 1);
    }

    /**
     * Load HTZ Zipline Lift sprite sheet - diagonal moving platform from HTZ.
     * ROM: ArtNem_HtzZipline at 0xF0602, palette line 2.
     * 5 frames:
     * - Frame 0: Main lift (10 pieces) - diagonal platform with cables
     * - Frame 1: Variant lift (8 pieces)
     * - Frame 2: Rope only (2 pieces) - shown after motion stops
     * - Frame 3: Left stake (3 pieces) - for scenery
     * - Frame 4: Right stake (3 pieces) - for scenery
     */
    public ObjectSpriteSheet loadHTZLiftSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_HTZ_ZIPLINE_ADDR,
                Sonic2Constants.MAP_UNC_HTZ_LIFT_ADDR, 2, 1);
    }

    /**
     * Load HTZ Barrier sprite sheet (Object 0x2D subtype 0 in Hill Top Zone).
     * Uses ArtNem_HtzValveBarrier at 0xF08F6 instead of the CPZ ConstructionStripes art.
     * Frame 0 mapping: 4 x 2x2 tile pieces stacked vertically (16x64 total), palette 1.
     *
     * @return sprite sheet for HTZ valve barrier, or null on failure
     */
    public ObjectSpriteSheet loadHTZBarrierSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_HTZ_VALVE_BARRIER_ADDR,
                Sonic2Constants.MAP_UNC_BARRIER_ADDR, 1, 1);
    }

    /**
     * Load MTZ Cog sprite sheet (Object 0x65 child / standalone cog).
     * ROM: ArtNem_MtzCog at 0xF178E, palette line 3.
     * Mappings: Obj65_MapUnc_26F04 (obj65_b.asm) - 3 frames.
     * <pre>
     * Frame 0: 2 pieces, tiles 0-5, symmetric (normal + hFlip)
     * Frame 1: 2 pieces, tiles 6-11, (normal + hFlip+vFlip)
     * Frame 2: 2 pieces, tiles 6-11, (vFlip + hFlip)
     * </pre>
     */
    public ObjectSpriteSheet loadMTZCogSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_MTZ_COG_ADDR, "MtzCog");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MTZ_COG_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load Button (Obj47) sprite sheet.
     * ROM: ArtNem_Button at 0x78DAC, palette line 0.
     * From obj47.asm Map_obj47:
     *   Frame 0 (unpressed): spritePiece -$10, -$C, 4, 2, 0, 0, 0, 0, 0
     *   Frame 1 (pressed):   spritePiece -$10, -$C, 4, 2, 8, 0, 0, 0, 0
     *   Frame 2 (unused):    spritePiece -$10, -8,  4, 2, 0, 0, 0, 0, 0
     */
    public ObjectSpriteSheet loadButtonSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_BUTTON_ADDR, "Button");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_BUTTON_ADDR);
        // Palette line 0: make_art_tile(ArtTile_ArtNem_Button,0,0)
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    /**
     * Load MTZ Floor Spike (Obj6D) sprite sheet.
     * ROM: ArtNem_MtzSpike at 0xF148E, palette line 1.
     * Shares mappings with Obj68 (SpikyBlock) - uses frame 0 only (vertical spike).
     * From obj68.asm Map_obj68_000A: spritePiece -4, -$10, 1, 4, 0, 0, 1, 0, 0
     */
    public ObjectSpriteSheet loadMTZFloorSpikeSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_MTZ_SPIKE_ADDR, "MtzSpike");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MTZ_FLOOR_SPIKE_ADDR);
        // Palette line 1: make_art_tile(ArtTile_ArtNem_MtzSpike,1,0)
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    /**
     * Load MTZ SpikyBlock (Obj68) block sprite sheet.
     * ROM: ArtNem_MtzSpikeBlock at 0xF12B6, palette line 3.
     * Frame 4 from Map_obj68: 32x32 block (two 16x32 halves, right half hFlipped).
     */
    public ObjectSpriteSheet loadMTZSpikeBlockSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_MTZ_SPIKE_BLOCK_ADDR, "MtzSpikeBlock");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MTZ_FLOOR_SPIKE_ADDR);
        // Palette line 3: make_art_tile(ArtTile_ArtNem_MtzSpikeBlock,3,0)
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load MTZ Spike child (Obj68 spike part) sprite sheet.
     * ROM: ArtNem_MtzSpike at 0xF148E, palette line 1.
     * Frames 0-3 from Map_obj68: directional spikes (Up, Right, Down, Left).
     */
    public ObjectSpriteSheet loadMTZSpikeSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_MTZ_SPIKE_ADDR, "MtzSpike");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MTZ_SPIKE_ADDR);
        // Palette line 1: make_art_tile(ArtTile_ArtNem_MtzSpike,1,0)
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    /**
     * Load MTZ Steam (Obj42 child) sprite sheet.
     * ROM: ArtNem_MtzSteam at 0xF1384, palette line 1.
     * Steam puffs use frames 0-6; the piston body (frame 7) uses level art.
     * From mappings/sprite/obj42.asm (Map_obj42).
     */
    public ObjectSpriteSheet loadMTZSteamSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_MTZ_STEAM_ADDR,
                Sonic2Constants.MAP_UNC_MTZ_STEAM_ADDR, 1, 1);
    }

    /**
     * Load MTZ Spin Tube Flash (Obj67) sprite sheet.
     * ROM: ArtNem_MtzSpinTubeFlash at 0xF1870, palette line 3.
     * make_art_tile(ArtTile_ArtNem_MtzSpinTubeFlash,3,0) = $056B
     * From mappings/sprite/obj67.asm (Map_obj67).
     */
    public ObjectSpriteSheet loadMTZSpinTubeFlashSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_MTZ_SPIN_TUBE_FLASH_ADDR,
                Sonic2Constants.MAP_UNC_MTZ_SPIN_TUBE_FLASH_ADDR, 3, 1);
    }

    /**
     * Load MTZ Wheel Indent (Obj6E subtype 3) sprite sheet.
     * ROM: ArtNem_MtzWheelIndent at 0xF120E, palette line 3.
     * From obj6E.asm Map_obj6E_007E: spritePiece -$C, -$C, 3, 3, 0, 0, 0, 0, 0
     */
    public ObjectSpriteSheet loadMTZWheelIndentSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_MTZ_WHEEL_INDENT_ADDR,
                Sonic2Constants.MAP_UNC_OBJ6E_ADDR, 3, 1);
    }

    // ========== MTZ Wheel / Cog (Object 0x70) ==========

    /**
     * Load MTZ Wheel sprite sheet (Object 0x70 - Giant rotating cog).
     * ROM: ArtNem_MtzWheel at 0xF0DB6, palette line 3.
     * Art tile: make_art_tile(ArtTile_ArtNem_MtzWheel,3,0) = $0378
     * Mappings: Obj70_MapUnc_28786 (obj70.asm) - 32 frames.
     * <p>
     * 32 frames representing a cog tooth at different rotation angles.
     * Mapped from disassembly mappings/sprite/obj70.asm.
     */
    public ObjectSpriteSheet loadMTZWheelSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_MTZ_WHEEL_ADDR,
                Sonic2Constants.MAP_UNC_OBJ70_ADDR, 3, 1);
    }

    // ========== MTZ Conveyor / Lava Cup (Object 0x6C) ==========

    /**
     * Load MTZ Lava Cup sprite sheet (Object 0x6C) - small platform on pulleys.
     * ROM: ArtNem_LavaCup at 0xF167C, palette line 3.
     * Art tile: make_art_tile(ArtTile_ArtNem_LavaCup,3,0) = 0x03F9
     * Mappings: Obj6C_MapUnc_28372 (obj6C.asm) - 1 frame, 2 pieces.
     * <pre>
     * Frame 0: 2 pieces (2x2 tiles each), left half at (-$10,-8), right half at (0,-8)
     * Total: 32x16 pixels
     * </pre>
     */
    public ObjectSpriteSheet loadMTZLavaCupSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_MTZ_LAVA_CUP_ADDR,
                Sonic2Constants.MAP_UNC_MTZ_LAVA_CUP_ADDR, 3, 1);
    }

    private AnimalType[] resolveZoneAnimals(int zoneIndex) {
        if (zoneIndex < 0 || zoneIndex >= ZONE_ANIMALS.length) {
            return DEFAULT_ANIMALS;
        }
        return ZONE_ANIMALS[zoneIndex];
    }

    /**
     * Returns the S2-specific Nemesis art ROM address for the given animal type.
     */
    public static int getAnimalArtAddr(AnimalType type) {
        return switch (type) {
            case RABBIT -> Sonic2Constants.ART_NEM_RABBIT_ADDR;
            case CHICKEN -> Sonic2Constants.ART_NEM_CHICKEN_ADDR;
            case PENGUIN -> Sonic2Constants.ART_NEM_PENGUIN_ADDR;
            case SEAL -> Sonic2Constants.ART_NEM_SEAL_ADDR;
            case PIG -> Sonic2Constants.ART_NEM_PIG_ADDR;
            case FLICKY -> Sonic2Constants.ART_NEM_FLICKY_ADDR;
            case SQUIRREL -> Sonic2Constants.ART_NEM_SQUIRREL_ADDR;
            case EAGLE -> Sonic2Constants.ART_NEM_EAGLE_ADDR;
            case MOUSE -> Sonic2Constants.ART_NEM_MOUSE_ADDR;
            case MONKEY -> Sonic2Constants.ART_NEM_MONKEY_ADDR;
            case TURTLE -> Sonic2Constants.ART_NEM_TURTLE_ADDR;
            case BEAR -> Sonic2Constants.ART_NEM_BEAR_ADDR;
        };
    }

    private Pattern[] loadAnimalPatterns(AnimalType animalTypeA, AnimalType animalTypeB) {
        Pattern[] animalPatternsA = safeLoadNemesisPatterns(getAnimalArtAddr(animalTypeA),
                "Animal-" + animalTypeA.displayName());
        Pattern[] animalPatternsB = safeLoadNemesisPatterns(getAnimalArtAddr(animalTypeB),
                "Animal-" + animalTypeB.displayName());
        int minLength = ANIMAL_TILE_OFFSET * 2;
        int combinedLength = Math.max(Math.max(animalPatternsA.length, ANIMAL_TILE_OFFSET + animalPatternsB.length),
                minLength);
        Pattern[] combined = new Pattern[combinedLength];
        if (animalPatternsA.length > 0) {
            System.arraycopy(animalPatternsA, 0, combined, 0, Math.min(animalPatternsA.length, combined.length));
        }
        if (animalPatternsB.length > 0 && ANIMAL_TILE_OFFSET < combined.length) {
            int copyLength = Math.min(animalPatternsB.length, combined.length - ANIMAL_TILE_OFFSET);
            System.arraycopy(animalPatternsB, 0, combined, ANIMAL_TILE_OFFSET, copyLength);
        }
        for (int i = 0; i < combined.length; i++) {
            if (combined[i] == null) {
                combined[i] = new Pattern();
            }
        }
        return combined;
    }

    private List<SpriteMappingFrame> loadMappingFrames(int mappingAddr) {
        return S2SpriteDataLoader.loadMappingFrames(reader, mappingAddr);
    }

    private List<SpriteMappingFrame> loadMappingFramesWithTileOffset(int mappingAddr, int tileOffset) {
        return S2SpriteDataLoader.loadMappingFramesWithTileOffset(reader, mappingAddr, tileOffset);
    }

    private Pattern[] createTornadoCombinedPatterns(Pattern[] tornadoPatterns, List<SpriteMappingFrame> mappings) {
        int requiredSize = Math.max(tornadoPatterns.length, computeMaxTileIndex(mappings) + 1);
        Pattern[] combined = createBlankPatterns(requiredSize);

        System.arraycopy(tornadoPatterns, 0, combined, 0, Math.min(tornadoPatterns.length, combined.length));
        copyTornadoRiderPatterns(combined, Sonic2Constants.ART_UNC_SONIC_ADDR,
                TORNADO_SONIC_RIDER_OFFSET, "TornadoSonicRider");
        copyTornadoRiderPatterns(combined, Sonic2Constants.ART_UNC_TAILS_ADDR,
                TORNADO_TAILS_RIDER_OFFSET, "TornadoTailsRider");

        return combined;
    }

    private void copyTornadoRiderPatterns(Pattern[] combined, int artAddr, int targetOffset, String assetName) {
        if (targetOffset < 0 || targetOffset >= combined.length) {
            return;
        }
        Pattern[] riderPatterns = safeLoadUncompressedPatterns(
                artAddr, TORNADO_RIDER_TILE_COUNT * Pattern.PATTERN_SIZE_IN_ROM, assetName);
        int copyLength = Math.min(riderPatterns.length, combined.length - targetOffset);
        if (copyLength > 0) {
            System.arraycopy(riderPatterns, 0, combined, targetOffset, copyLength);
        }
    }

    /**
     * Build an ObjectSpriteSheet from ROM art and mapping addresses.
     * Loads Nemesis-compressed patterns and parses S2 mapping frames from ROM.
     *
     * @param artAddr      ROM address of Nemesis-compressed art
     * @param mappingAddr  ROM address of mapping table
     * @param paletteIndex palette line index (0-3)
     * @param bankSize     pattern bank size (typically 1)
     * @return sprite sheet, or null if art loading fails
     */
    private ObjectSpriteSheet buildArtSheetFromRom(int artAddr, int mappingAddr, int paletteIndex, int bankSize) {
        Pattern[] patterns = safeLoadNemesisPatterns(artAddr, String.format("art@0x%X", artAddr));
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(mappingAddr);
        return new ObjectSpriteSheet(patterns, mappings, paletteIndex, bankSize);
    }

    private SpriteAnimationSet loadAnimationSet(int animAddr, int scriptCount) {
        return CommonSpriteDataLoader.loadAnimationSet(reader, animAddr, scriptCount);
    }

    private SpriteMappingFrame createSimpleFrame(int x, int y, int wTiles, int hTiles, int tileIndex) {
        SpriteMappingPiece piece = new SpriteMappingPiece(x, y, wTiles, hTiles, tileIndex, false, false, 0);
        return new SpriteMappingFrame(List.of(piece));
    }

    private List<SpriteMappingFrame> normalizeMappings(List<SpriteMappingFrame> originalFrames) {
        int minTileIndex = Integer.MAX_VALUE;

        // Pass 1: Find minimum tile index
        for (SpriteMappingFrame frame : originalFrames) {
            for (SpriteMappingPiece piece : frame.pieces()) {
                minTileIndex = Math.min(minTileIndex, piece.tileIndex());
            }
        }

        // Pass 2: Create new frames with shifted indices
        List<SpriteMappingFrame> newFrames = new ArrayList<>(originalFrames.size());
        for (SpriteMappingFrame frame : originalFrames) {
            List<SpriteMappingPiece> newPieces = new ArrayList<>(frame.pieces().size());
            for (SpriteMappingPiece piece : frame.pieces()) {
                newPieces.add(new SpriteMappingPiece(
                        piece.xOffset(),
                        piece.yOffset(),
                        piece.widthTiles(),
                        piece.heightTiles(),
                        piece.tileIndex() - minTileIndex,
                        piece.hFlip(),
                        piece.vFlip(),
                        piece.paletteIndex()));
            }
            newFrames.add(new SpriteMappingFrame(newPieces));
        }

        return newFrames;
    }

    private int computeMaxTileIndex(List<SpriteMappingFrame> frames) {
        int max = -1;
        if (frames == null) {
            return max;
        }
        for (SpriteMappingFrame frame : frames) {
            if (frame == null || frame.pieces() == null) {
                continue;
            }
            for (SpriteMappingPiece piece : frame.pieces()) {
                int tiles = piece.widthTiles() * piece.heightTiles();
                int end = piece.tileIndex() + Math.max(tiles, 1) - 1;
                max = Math.max(max, end);
            }
        }
        return max;
    }

    /**
     * Creates mappings for Animal (Obj28) - all animal variants.
     * Based on obj28_a-e.asm (S2 disassembly).
     */
    private List<SpriteMappingFrame> createAnimalMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        final int tileOffset = ANIMAL_TILE_OFFSET; // ArtTile_ArtNem_Animal_2 - ArtTile_ArtNem_Animal_1

        addAnimalMappingSet(frames, 0); // Map_obj28_a (Animal_1)
        addAnimalMappingSet(frames, tileOffset); // Map_obj28_a (Animal_2)
        addAnimalMappingSetB(frames, 0);
        addAnimalMappingSetB(frames, tileOffset);
        addAnimalMappingSetC(frames, 0);
        addAnimalMappingSetC(frames, tileOffset);
        addAnimalMappingSetD(frames, 0);
        addAnimalMappingSetD(frames, tileOffset);
        addAnimalMappingSetE(frames, 0);
        addAnimalMappingSetE(frames, tileOffset);
        return frames;
    }

    private void addAnimalMappingSet(List<SpriteMappingFrame> frames, int tileOffset) {
        // Map_obj28_a: 0010, 001A, 0006
        frames.add(createSimpleFrame(-8, -8, 2, 2, 8 + tileOffset));
        frames.add(createSimpleFrame(-8, -8, 2, 2, 0x0C + tileOffset));
        frames.add(createSimpleFrame(-8, -0x14, 2, 4, 0 + tileOffset));
    }

    private void addAnimalMappingSetB(List<SpriteMappingFrame> frames, int tileOffset) {
        // Map_obj28_b: 0010, 001A, 0006
        frames.add(createSimpleFrame(-0x0C, -8, 3, 2, 8 + tileOffset));
        frames.add(createSimpleFrame(-0x0C, -8, 3, 2, 0x0E + tileOffset));
        frames.add(createSimpleFrame(-8, -0x14, 2, 4, 0 + tileOffset));
    }

    private void addAnimalMappingSetC(List<SpriteMappingFrame> frames, int tileOffset) {
        // Map_obj28_c: 0010, 001A, 0006
        frames.add(createSimpleFrame(-0x0C, -8, 3, 2, 6 + tileOffset));
        frames.add(createSimpleFrame(-0x0C, -8, 3, 2, 0x0C + tileOffset));
        frames.add(createSimpleFrame(-8, -0x0C, 2, 3, 0 + tileOffset));
    }

    private void addAnimalMappingSetD(List<SpriteMappingFrame> frames, int tileOffset) {
        // Map_obj28_d: 0010, 001A, 0006
        frames.add(createSimpleFrame(-8, -8, 2, 2, 6 + tileOffset));
        frames.add(createSimpleFrame(-8, -8, 2, 2, 0x0A + tileOffset));
        frames.add(createSimpleFrame(-8, -0x0C, 2, 3, 0 + tileOffset));
    }

    private void addAnimalMappingSetE(List<SpriteMappingFrame> frames, int tileOffset) {
        // Map_obj28_e: 0010, 001A, 0006
        frames.add(createSimpleFrame(-8, -0x0C, 2, 3, 6 + tileOffset));
        frames.add(createSimpleFrame(-8, -0x0C, 2, 3, 0x0C + tileOffset));
        frames.add(createSimpleFrame(-8, -0x0C, 2, 3, 0 + tileOffset));
    }

    /**
     * Creates animations for Signpost (Obj0D).
     * Based on Ani_obj0D in s2.asm.
     *
     * Frame indices match obj0D_a.asm ROM order (as parsed by loadMappingFrames):
     *   0 = Sonic final face (tiles $22+$2E, wide)
     *   1 = Tails face
     *   2 = Eggman front face (tiles 0, h-flip) — initial state
     *   3 = Spin transition A (tile $0C, 4x4)
     *   4 = Edge/thin view (tile $1C)
     *   5 = Spin transition B (tile $0C, h-flipped)
     *
     * ROM animation scripts (Ani_obj0D):
     *   0: $0F, $02, $FF       — hold frame 2 (Eggman, initial state)
     *   1: $01, 2,3,4,5, 1,3,4,5, 0,3,4,5, $FF  — spin
     *   2: same as 1
     *   3: $0F, $00, $FF       — hold frame 0 (Sonic face, final state)
     *   4: $0F, $01, $FF       — hold frame 1 (Tails face)
     */
    private SpriteAnimationSet createSignpostAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: Hold Eggman face (ROM: $0F, $02, $FF)
        set.addScript(0, new SpriteAnimationScript(0x0F, List.of(2), SpriteAnimationEndAction.LOOP, 0));

        // Anim 1: Spin — Eggman → transitions → Tails → transitions → Sonic
        // (ROM: $01, 2,3,4,5, 1,3,4,5, 0,3,4,5, $FF)
        set.addScript(1, new SpriteAnimationScript(0x01,
                List.of(2, 3, 4, 5, 1, 3, 4, 5, 0, 3, 4, 5),
                SpriteAnimationEndAction.LOOP, 0));

        // Anim 2: Same as 1 (2P mode)
        set.addScript(2, new SpriteAnimationScript(0x01,
                List.of(2, 3, 4, 5, 1, 3, 4, 5, 0, 3, 4, 5),
                SpriteAnimationEndAction.LOOP, 0));

        // Anim 3: Hold Sonic face (ROM: $0F, $00, $FF)
        set.addScript(3, new SpriteAnimationScript(0x0F, List.of(0), SpriteAnimationEndAction.LOOP, 0));

        // Anim 4: Hold Tails face (ROM: $0F, $01, $FF)
        set.addScript(4, new SpriteAnimationScript(0x0F, List.of(1), SpriteAnimationEndAction.LOOP, 0));

        return set;
    }

    /**
     * Creates animations for CNZ Flipper (Obj86).
     * Based on Ani_obj86 in s2.asm.
     */
    private SpriteAnimationSet createFlipperAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: Vertical idle - hold frame 0 ($0F, 0, $FF)
        set.addScript(0, new SpriteAnimationScript(0x0F, List.of(0),
                SpriteAnimationEndAction.LOOP, 0));

        // Anim 1: Vertical trigger ($03, 1, 2, 1, $FD, 0)
        set.addScript(1, new SpriteAnimationScript(0x03, List.of(1, 2, 1),
                SpriteAnimationEndAction.SWITCH, 0));

        // Anim 2: Horizontal idle - hold frame 4 ($0F, 4, $FF)
        set.addScript(2, new SpriteAnimationScript(0x0F, List.of(4),
                SpriteAnimationEndAction.LOOP, 0));

        // Anim 3: Horizontal trigger left ($00, 5, 4, 3, 3, 3, 3, $FD, 2)
        set.addScript(3, new SpriteAnimationScript(0x00, List.of(5, 4, 3, 3, 3, 3),
                SpriteAnimationEndAction.SWITCH, 2));

        // Anim 4: Horizontal trigger right ($00, 3, 4, 5, 5, 5, 5, $FD, 2)
        set.addScript(4, new SpriteAnimationScript(0x00, List.of(3, 4, 5, 5, 5, 5),
                SpriteAnimationEndAction.SWITCH, 2));

        return set;
    }

    /**
     * Creates animations for CPZ Pipe Exit Spring (Obj7B).
     * From Ani_obj7B in the disassembly:
     * Anim 0: Idle - hold frame 0 ($0F, 0, $FF)
     * Anim 1: Triggered - show frame 3, then switch to anim 0 ($00, 3, $FD, 0)
     * Anim 2: Raised - spring moves up when player is below in tube ($05, 1, 2, 2, 2, 4, $FD, 0)
     */
    private SpriteAnimationSet createPipeExitSpringAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: Idle - hold frame 0 indefinitely
        set.addScript(0, new SpriteAnimationScript(0x0F, List.of(0),
                SpriteAnimationEndAction.LOOP, 0));

        // Anim 1: Triggered - show frame 3 briefly, then switch to anim 0
        set.addScript(1, new SpriteAnimationScript(0x00, List.of(3),
                SpriteAnimationEndAction.SWITCH, 0));

        // Anim 2: Raised - spring visually moves up when player passes below in tube
        // ROM: byte_29777: dc.b $5, 1, 2, 2, 2, 4, $FD, 0
        // Frames 1 and 2 show the spring at Y offset -32 (16 pixels higher than normal)
        set.addScript(2, new SpriteAnimationScript(0x05, List.of(1, 2, 2, 2, 4),
                SpriteAnimationEndAction.SWITCH, 0));

        return set;
    }

    /**
     * Creates animations for CPZ Tipping Floor (Obj0B).
     * Two animations for forward (0->4) and reverse (4->0) tipping motion.
     * Delay of 7 frames between each frame change.
     */
    private SpriteAnimationSet createTippingFloorAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: Forward - frames 0,1,2,3,4, loop back to frame 1
        // Delay 7 ($07) between frames
        set.addScript(0, new SpriteAnimationScript(0x07, List.of(0, 1, 2, 3, 4),
                SpriteAnimationEndAction.LOOP_BACK, 1));

        // Anim 1: Reverse - frames 4,3,2,1,0, loop back to frame 1
        set.addScript(1, new SpriteAnimationScript(0x07, List.of(4, 3, 2, 1, 0),
                SpriteAnimationEndAction.LOOP_BACK, 1));

        return set;
    }

    /**
     * Creates animations for Springboard / Lever Spring (Obj40).
     * <p>
     * Anim 0: Idle - holds frame 0 indefinitely
     * Anim 1: Triggered - shows compressed frame 1, then switches back to idle (anim 0)
     */
    private SpriteAnimationSet createSpringboardAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: Idle - hold frame 0 indefinitely
        set.addScript(0, new SpriteAnimationScript(0x0F, List.of(0),
                SpriteAnimationEndAction.LOOP, 0));

        // Anim 1: Triggered - show frame 1 briefly, then switch to anim 0
        // Short delay (3 frames) before switching back
        set.addScript(1, new SpriteAnimationScript(0x03, List.of(1, 0),
                SpriteAnimationEndAction.SWITCH, 0));

        return set;
    }

    /**
     * Creates a pattern array matching the fixed VRAM layout for the results
     * screen.
     * Each art chunk is placed at its exact VRAM tile base (relative to
     * VRAM_BASE_NUMBERS).
     */
    private Pattern[] createResultsVramPatterns(
            Pattern[] bonusDigits,
            Pattern[] perfect,
            Pattern[] titleCard,
            Pattern[] resultsText,
            Pattern[] miniSonic,
            Pattern[] hudText) {
        int base = Sonic2Constants.VRAM_BASE_NUMBERS;
        int maxEnd = base;
        maxEnd = Math.max(maxEnd, base + bonusDigits.length);
        maxEnd = Math.max(maxEnd, Sonic2Constants.VRAM_BASE_PERFECT + perfect.length);
        maxEnd = Math.max(maxEnd, Sonic2Constants.VRAM_BASE_TITLE_CARD + titleCard.length);
        maxEnd = Math.max(maxEnd, Sonic2Constants.VRAM_BASE_RESULTS_TEXT + resultsText.length);
        maxEnd = Math.max(maxEnd, Sonic2Constants.VRAM_BASE_MINI_CHARACTER + miniSonic.length);
        maxEnd = Math.max(maxEnd, Sonic2Constants.VRAM_BASE_HUD_TEXT + hudText.length);
        // Include space for trailing blank at $6F0-$6F1 (used in results mappings)
        maxEnd = Math.max(maxEnd, 0x6F2);

        int totalSize = Math.max(0, maxEnd - base);
        Pattern[] result = new Pattern[totalSize];

        // Fill gaps with empty tiles so unmapped references stay blank.
        Pattern emptyPattern = new Pattern();
        Arrays.fill(result, emptyPattern);

        copyPatterns(result, bonusDigits, Sonic2Constants.VRAM_BASE_NUMBERS - base);
        copyPatterns(result, perfect, Sonic2Constants.VRAM_BASE_PERFECT - base);
        copyPatterns(result, titleCard, Sonic2Constants.VRAM_BASE_TITLE_CARD - base);
        copyPatterns(result, resultsText, Sonic2Constants.VRAM_BASE_RESULTS_TEXT - base);
        copyPatterns(result, miniSonic, Sonic2Constants.VRAM_BASE_MINI_CHARACTER - base);
        copyPatterns(result, hudText, Sonic2Constants.VRAM_BASE_HUD_TEXT - base);

        // Tile $6F0 is used as a trailing blank in the results mappings.
        int trailingBlank = 0x6F0 - base;
        if (trailingBlank >= 0 && trailingBlank < result.length) {
            result[trailingBlank] = new Pattern();
            if (trailingBlank + 1 < result.length) {
                result[trailingBlank + 1] = new Pattern();
            }
        }

        return result;
    }

    private Pattern[] createBlankPatterns(int count) {
        if (count <= 0) {
            return new Pattern[0];
        }
        Pattern[] patterns = new Pattern[count];
        for (int i = 0; i < count; i++) {
            patterns[i] = new Pattern();
        }
        return patterns;
    }

    private Pattern[] ensureResultsPatternCapacity(Pattern[] patterns, List<SpriteMappingFrame> mappings) {
        int maxTileIndex = computeMaxTileIndex(mappings);
        if (maxTileIndex < 0 || maxTileIndex < patterns.length) {
            return patterns;
        }
        Pattern[] expanded = new Pattern[maxTileIndex + 1];
        Pattern emptyPattern = new Pattern();
        Arrays.fill(expanded, emptyPattern);
        System.arraycopy(patterns, 0, expanded, 0, Math.min(patterns.length, expanded.length));
        return expanded;
    }

    private void copyPatterns(Pattern[] dest, Pattern[] src, int destPos) {
        if (src == null || src.length == 0 || destPos >= dest.length) {
            return;
        }
        if (destPos < 0) {
            int skip = -destPos;
            if (skip >= src.length) {
                return;
            }
            src = Arrays.copyOfRange(src, skip, src.length);
            destPos = 0;
        }
        int copyLen = Math.min(src.length, dest.length - destPos);
        System.arraycopy(src, 0, dest, destPos, copyLen);
    }

    /**
     * Gets the art configuration for an object from the ZoneArtProvider.
     *
     * @param objectId the object type ID
     * @param zoneIndex the current zone index
     * @return the art configuration, or null if not available
     */
    private ZoneArtProvider.ObjectArtConfig getObjectArtConfig(int objectId, int zoneIndex) {
        return GameServices.module().getZoneArtProvider().getObjectArt(objectId, zoneIndex);
    }

    /**
     * Loads Egg Prison / Capsule sprite sheet (Object 0x3E).
     *
     * @return ObjectSpriteSheet for the Egg Prison, or null on failure
     */
    public ObjectSpriteSheet loadEggPrisonSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_EGG_PRISON_ADDR,
                Sonic2Constants.MAP_UNC_EGG_PRISON_ADDR, 1, 1);
    }

    /**
     * Load CPZ Boss Eggpod sprite sheet (Obj5D main + Robotnik).
     * Uses ArtNem_Eggpod with mappings from Obj5D_MapUnc_2ED8C.
     * Palette line 1 by default (main body). Robotnik uses palette override 0.
     */
    public ObjectSpriteSheet loadCPZBossEggpodSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EGGPOD_ADDR, "Eggpod");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_CPZ_BOSS_EGGPOD_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    /**
     * Load CPZ Boss parts sprite sheet (pipe, pump, container, gunk, etc.).
     * Uses ArtNem_CPZBoss with mappings from Obj5D_MapUnc_2EADC.
     * Palette line 1 by default; dripper/gunk use palette override 3.
     */
    public ObjectSpriteSheet loadCPZBossPartsSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_CPZ_BOSS_ADDR, "CPZBoss");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_CPZ_BOSS_PARTS_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    /**
     * Load CPZ Boss eggpod jets sprite sheet (exhaust flame).
     * Uses ArtNem_EggpodJets with mappings from Obj5D_MapUnc_2EE88.
     * Palette line 0 (as per art tile).
     */
    public ObjectSpriteSheet loadCPZBossJetsSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EGGPOD_JETS_ADDR, "EggpodJets");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_CPZ_BOSS_JETS_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    /**
     * Load CPZ Boss smoke puff sprite sheet (used during retreat).
     * Uses ArtNem_BossSmoke with mappings from Obj5D_MapUnc_2EEA0.
     */
    public ObjectSpriteSheet loadCPZBossSmokeSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_BOSS_SMOKE_ADDR, "BossSmoke");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_CPZ_BOSS_SMOKE_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    /**
     * Load ARZ Boss main sprite sheet (Obj89 main vehicle).
     * Uses ArtNem_ARZBoss with mappings from Obj89_MapUnc_30E04.
     * Palette line 0 (ArtTile_ArtNem_ARZBoss uses palette 0).
     *
     * The ARZ boss main frames use tiles from BOTH ARZ boss art AND Eggpod art.
     * In the original game:
     * - ARZ boss art is at VRAM tile $3E0 (ArtTile_ArtNem_ARZBoss)
     * - Eggpod art is at VRAM tile $500 (ArtTile_ArtNem_Eggpod_4)
     * - Gap = $500 - $3E0 = $120 = 288 tiles
     *
     * The mappings use tile indices relative to art_tile ($3E0), so:
     * - Tile $6F → ARZ boss tile (low index, within ARZ art)
     * - Tile $150 → Eggpod tile ($3E0 + $150 = $530, which is $30 into Eggpod at $500)
     *
     * We create a combined pattern array with Eggpod patterns at offset $120.
     */
    public ObjectSpriteSheet loadARZBossMainSheet() {
        Pattern[] arzPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_ARZ_BOSS_ADDR, "ARZBoss");
        if (arzPatterns.length == 0) {
            return null;
        }

        // Load Eggpod patterns (used for Robotnik's face/body in various frames)
        Pattern[] eggpodPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EGGPOD_ADDR, "Eggpod");

        // Calculate offset: $500 - $3E0 = $120 = 288 tiles
        int eggpodOffset = Sonic2Constants.ART_TILE_EGGPOD_4 - Sonic2Constants.ART_TILE_ARZ_BOSS;

        // Create combined array large enough to hold both art sets at correct offsets
        int combinedSize = Math.max(arzPatterns.length, eggpodOffset + eggpodPatterns.length);
        Pattern[] combinedPatterns = new Pattern[combinedSize];

        // Copy ARZ boss patterns starting at index 0
        System.arraycopy(arzPatterns, 0, combinedPatterns, 0, arzPatterns.length);

        // Copy Eggpod patterns at offset $120 (288)
        for (int i = 0; i < eggpodPatterns.length && (eggpodOffset + i) < combinedSize; i++) {
            combinedPatterns[eggpodOffset + i] = eggpodPatterns[i];
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_ARZ_BOSS_MAIN_ADDR);
        return new ObjectSpriteSheet(combinedPatterns, mappings, 0, 1);
    }

    /**
     * Load ARZ Boss parts sprite sheet (pillars, arrows, bulging eyes).
     * Uses ArtNem_ARZBoss with mappings from Obj89_MapUnc_30D68.
     * Palette line 0 (ArtTile_ArtNem_ARZBoss uses palette 0).
     */
    public ObjectSpriteSheet loadARZBossPartsSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_ARZ_BOSS_ADDR, "ARZBoss");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_ARZ_BOSS_PARTS_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    /**
     * Load Boss Explosion sprite sheet (Obj58).
     * Uses ArtNem_FieryExplosion with mappings from Obj58_MapUnc_2D50A.
     * Palette line 0 (as per art tile).
     */
    public ObjectSpriteSheet loadBossExplosionSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_FIERY_EXPLOSION_ADDR, "BossExplosion");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_BOSS_EXPLOSION_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    /**
     * Load Super Sonic Stars sprite sheet (Obj7E).
     * ROM: ArtNem_SuperSonic_stars at 0x7393C, mappings at Obj7E_MapUnc_1E1BE.
     * 6 mapping frames: 0=small(8x8), 1=medium(16x16), 2=large(24x24), 3=medium, 4=small, 5=empty.
     */
    public ObjectSpriteSheet loadSuperSonicStarsSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SUPER_SONIC_STARS_ADDR, "SuperSonicStars");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> rawMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_SUPER_SONIC_STARS_ADDR);
        List<SpriteMappingFrame> mappings = normalizeMappings(rawMappings);
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    /**
     * Load EHZ Boss (Obj56) sprite sheet - multi-component boss with 3 art sources.
     * ROM: ArtNem_Eggpod at 0x83BF6 (flying vehicle),
     *      ArtNem_EHZBoss at 0x8507C (ground vehicle/wheels/spike),
     *      ArtNem_EggChoppers at 0x85868 (propeller)
     * Palette line 1
     *
     * Note: This is a composite sheet combining all three art sources.
     * Full sprite mappings from obj56_a.asm, obj56_b.asm, obj56_c.asm need to be implemented.
     */
    public ObjectSpriteSheet loadEHZBossSheet() {
        // Load all three art sources
        Pattern[] eggpodPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EGGPOD_ADDR, "Eggpod");
        Pattern[] ehzBossPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EHZ_BOSS_ADDR, "EHZBoss");
        Pattern[] choppersPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EGG_CHOPPERS_ADDR, "EggChoppers");

        if (eggpodPatterns.length == 0 && ehzBossPatterns.length == 0 && choppersPatterns.length == 0) {
            return null;
        }

        // Combine all patterns into a single array
        Pattern[] allPatterns = new Pattern[eggpodPatterns.length + ehzBossPatterns.length + choppersPatterns.length];
        System.arraycopy(eggpodPatterns, 0, allPatterns, 0, eggpodPatterns.length);
        System.arraycopy(ehzBossPatterns, 0, allPatterns, eggpodPatterns.length, ehzBossPatterns.length);
        System.arraycopy(choppersPatterns, 0, allPatterns, eggpodPatterns.length + ehzBossPatterns.length, choppersPatterns.length);

        // Load all three mapping tables from ROM with tile offsets into combined array.
        // Each sub-object's ROM tiles start from 0 (relative to its own art_tile).
        int eggpodOffset = 0;
        int vehicleOffset = eggpodPatterns.length;
        int choppersOffset = eggpodPatterns.length + ehzBossPatterns.length;

        List<SpriteMappingFrame> mappings = new ArrayList<>();
        mappings.addAll(loadMappingFramesWithTileOffset(Sonic2Constants.MAP_UNC_EHZ_BOSS_A_ADDR, choppersOffset));
        mappings.addAll(loadMappingFramesWithTileOffset(Sonic2Constants.MAP_UNC_EHZ_BOSS_B_ADDR, vehicleOffset));
        mappings.addAll(loadMappingFramesWithTileOffset(Sonic2Constants.MAP_UNC_EHZ_BOSS_C_ADDR, eggpodOffset));

        return new ObjectSpriteSheet(allPatterns, mappings, 1, 1);
    }

    /**
     * Load CNZ Boss sprite sheet (Object 0x51) - Eggman's electricity generator boss.
     * <p>
     * ROM Reference: s2.asm:90063 (ArtNem_CNZBoss) + Obj51_MapUnc_320EA
     * Art addresses:
     * - CNZBoss: 0x87AAC (Nemesis compressed) at VRAM $0407
     * - Eggpod: 0x83BF6 (Nemesis compressed) at VRAM $0500 (for Robotnik graphics)
     * <p>
     * The mappings use a "fudge" base of $03A7 (= $0407 - $60), so:
     * - Tiles $60-$E4 reference CNZBoss art (subtract $60 for array index)
     * - Tiles $17D-$1B1 reference Eggpod art at VRAM $0500 (Robotnik body/face)
     * <p>
     * We create a combined pattern array where:
     * - CNZBoss patterns at indices 0+ (tiles $60+ minus $60)
     * - Eggpod patterns at indices $11D+ (tiles $17D+ minus $60)
     *
     * @return sprite sheet for CNZ boss, or null on failure
     */
    public ObjectSpriteSheet loadCNZBossSheet() {
        // Load CNZBoss art (generators, electrodes, electricity effects)
        Pattern[] cnzBossPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_CNZ_BOSS_ADDR, "CNZBoss");
        if (cnzBossPatterns.length == 0) {
            return null;
        }

        // Load Eggpod art (Robotnik body, propellers, face expressions)
        Pattern[] eggpodPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EGGPOD_ADDR, "Eggpod");

        // Calculate offsets based on VRAM tile positions:
        // - Fudge base = $03A7
        // - CNZBoss at VRAM $0407: tile $60 → index 0 (subtract $60)
        // - Eggpod at VRAM $0500: tile $17D → index $11D (since $17D - $60 = $11D)
        //   Eggpod VRAM offset from fudge: $0500 - $03A7 = $159
        //   But mappings reference $17D which is $17D - $60 = $11D from CNZBoss start
        final int EGGPOD_OFFSET_IN_COMBINED = 0x11D; // Where Eggpod tiles start in combined array

        // Create combined array large enough for both art sources
        // Highest Eggpod tile used: $1B1 → index $151 (needs array size of at least $152)
        int combinedSize = Math.max(cnzBossPatterns.length, EGGPOD_OFFSET_IN_COMBINED + eggpodPatterns.length);
        Pattern[] combinedPatterns = new Pattern[combinedSize];

        // Initialize with empty patterns
        for (int i = 0; i < combinedSize; i++) {
            combinedPatterns[i] = new Pattern();
        }

        // Copy CNZBoss patterns at index 0 (tiles $60+ in mappings)
        System.arraycopy(cnzBossPatterns, 0, combinedPatterns, 0, cnzBossPatterns.length);

        // Copy Eggpod patterns at index $11D (tiles $17D+ in mappings)
        // The Eggpod tiles used are $17D, $181, $185, $189, $191, $199, $1A1, $1A9, $1B1
        // These correspond to Eggpod array indices: $24, $28, $2C, $30, $38, $40, $48, $50, $58
        // (calculated as: mapping_tile - $60 - $11D + $24 for first one, but actually
        //  VRAM $0500 + offset = tile value, so Eggpod[offset] where offset = VRAM_tile - $0500)
        // Tile $17D in mapping → VRAM $03A7 + $17D = $0524 → Eggpod[$0524 - $0500] = Eggpod[$24]
        if (eggpodPatterns.length > 0) {
            // Map Eggpod tiles to correct positions in combined array
            // Eggpod VRAM starts at $0500, fudge base is $03A7
            // Mapping tile $17D → VRAM $0524 → Eggpod index $24
            // Combined array index = mapping_tile - $60 = $17D - $60 = $11D
            // So Eggpod[$24] goes to combined[$11D]
            int eggpodVramBase = 0x0500;
            int fudgeBase = 0x03A7;

            // Copy the relevant Eggpod tiles to their correct positions
            int[] eggpodMappingTiles = {0x17D, 0x181, 0x185, 0x189, 0x191, 0x199, 0x1A1, 0x1A9, 0x1B1};
            for (int mappingTile : eggpodMappingTiles) {
                int vramTile = fudgeBase + mappingTile;
                int eggpodIndex = vramTile - eggpodVramBase;
                int combinedIndex = mappingTile - 0x60;

                if (eggpodIndex >= 0 && eggpodIndex < eggpodPatterns.length &&
                        combinedIndex >= 0 && combinedIndex < combinedSize) {
                    combinedPatterns[combinedIndex] = eggpodPatterns[eggpodIndex];
                }
            }

            // Also copy a range of Eggpod patterns for any we might have missed
            // The propeller and face tiles use consecutive patterns
            // Start copying from Eggpod[$24] (where the referenced tiles begin)
            int eggpodCopyStart = 0x24;
            int maxCopyCount = Math.min(0x60, eggpodPatterns.length - eggpodCopyStart);
            for (int i = 0; i < maxCopyCount; i++) {
                int combinedIndex = EGGPOD_OFFSET_IN_COMBINED + i;
                if (combinedIndex < combinedSize && (eggpodCopyStart + i) < eggpodPatterns.length) {
                    combinedPatterns[combinedIndex] = eggpodPatterns[eggpodCopyStart + i];
                }
            }
        }

        // ROM mapping tiles use fudge base $03A7 = CNZBoss VRAM $0407 - $60.
        // Tile $60 in ROM = CNZBoss[0] in our array → tileOffset = -0x60.
        List<SpriteMappingFrame> mappings = loadMappingFramesWithTileOffset(Sonic2Constants.MAP_UNC_CNZ_BOSS_ADDR, -0x60);
        return new ObjectSpriteSheet(combinedPatterns, mappings, 0, 1);
    }

    /**
     * Load Smashable Ground sprite sheet (Object 0x2F) - breakable rock platform in HTZ.
     * <p>
     * This object uses LEVEL patterns (ArtTile_ArtKos_LevelArt), not dedicated object art.
     * The patterns must be extracted from the level at runtime.
     * <p>
     * From s2.asm obj2F.asm mappings, the tile indices used are:
     * - 0x12, 0x16: Top rock patterns (4x2 = 8 tiles or 2x2 = 4 tiles each)
     * - 0x4A: Upper-middle patterns (2x2 = 4 tiles)
     * - 0x4E: Lower-middle patterns (2x2 = 4 tiles)
     * - 0x52: Base/bottom patterns (2x2 = 4 tiles)
     * <p>
     * Palette: Line 2 (standard level palette)
     *
     * @param level The level to extract patterns from
     * @return sprite sheet for smashable ground, or null if level is null
     */
    public ObjectSpriteSheet loadSmashableGroundSheet(com.openggf.level.Level level) {
        if (level == null) {
            return null;
        }

        // Extract the needed patterns from level data
        // The highest tile index used is 0x52 + (2*2) = 0x56
        // We need tiles from 0x12 to around 0x56
        int maxTileNeeded = 0x56;
        int patternCount = level.getPatternCount();
        if (patternCount < maxTileNeeded) {
            LOGGER.warning("Level has fewer patterns than SmashableGround needs: "
                    + patternCount + " < " + maxTileNeeded);
            // Still try to load what we can
        }

        // Extract patterns for the object
        // We'll create a local pattern array with remapped indices
        // Pattern indices used: 0x12, 0x16, 0x4A, 0x4E, 0x52
        // We'll create a compact array where:
        // - Index 0 = level pattern 0x12
        // - Index 8 = level pattern 0x16 (for the 4x2 top section)
        // - Index 12 = level pattern 0x4A
        // - Index 16 = level pattern 0x4E
        // - Index 20 = level pattern 0x52
        // This allows us to use the mapping offsets directly

        // Actually, let's just copy enough patterns from the level to cover
        // the range 0x00 to 0x56, and use the original tile indices
        int copyCount = Math.min(patternCount, maxTileNeeded + 4);
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_SMASHABLE_GROUND_A_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 2, 1);
    }

    /**
     * Load SteamSpring piston body (Obj42) sprite sheet from level art.
     * ROM: ArtKos_LevelArt, palette line 3.
     * Frame 7 from Map_obj42 uses level art tiles 0x15 and 0x1D.
     */
    public ObjectSpriteSheet loadSteamSpringPistonSheet(com.openggf.level.Level level) {
        if (level == null) {
            return null;
        }

        // The highest tile index used is 0x1D + (2*4) = 0x25
        int maxTileNeeded = 0x25;
        int patternCount = level.getPatternCount();
        if (patternCount < maxTileNeeded) {
            LOGGER.warning("Level has fewer patterns than SteamSpring piston needs: "
                    + patternCount + " < " + maxTileNeeded);
        }

        int copyCount = Math.min(patternCount, maxTileNeeded + 1);
        Pattern[] patterns = new Pattern[copyCount];
        for (int i = 0; i < copyCount; i++) {
            patterns[i] = level.getPattern(i);
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MTZ_STEAM_ADDR);
        // Palette line 3: make_art_tile(ArtTile_ArtKos_LevelArt,3,0)
        return new ObjectSpriteSheet(patterns, mappings, 3, 1);
    }

    /**
     * Load HTZ Boss sprite sheet (Object 0x52) - Lava flamethrower boss.
     * <p>
     * ROM Reference: s2.asm:63619-64207 (Obj52)
     * Art addresses:
     * - HTZBoss: 0x8595C (Nemesis compressed) at VRAM $0421 - flamethrower, lava ball components
     * - Eggpod: 0x83BF6 (Nemesis compressed) at VRAM $03C1 (Eggpod_2) - Robotnik/vehicle body
     * <p>
     * The mappings use these tile bases:
     * - Tiles 0-$5F: Eggpod art (palette 1), used for main vehicle body
     * - Tiles $60+: HTZ boss art (palette 0), used for flamethrower and lava ball
     * <p>
     * We create a combined pattern array where:
     * - Eggpod patterns at indices 0+
     * - HTZ boss patterns at indices 0x60+ (tiles $60+ in mappings)
     *
     * @return sprite sheet for HTZ boss, or null on failure
     */
    public ObjectSpriteSheet loadHTZBossSheet() {
        // Load Eggpod art (vehicle body with Robotnik)
        Pattern[] eggpodPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EGGPOD_ADDR, "Eggpod");
        if (eggpodPatterns.length == 0) {
            return null;
        }

        // Load HTZ boss art (flamethrower, lava ball components)
        Pattern[] htzBossPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_HTZ_BOSS_ADDR, "HTZBoss");

        // HTZ boss tiles start at offset 0x60 in the combined array
        // Eggpod tiles: 0x00-0x5F
        // HTZ boss tiles: 0x60+
        final int HTZ_BOSS_OFFSET = 0x60;

        // Create combined array large enough for both art sources
        int combinedSize = Math.max(eggpodPatterns.length, HTZ_BOSS_OFFSET + htzBossPatterns.length);
        Pattern[] combinedPatterns = new Pattern[combinedSize];

        // Initialize with empty patterns
        for (int i = 0; i < combinedSize; i++) {
            combinedPatterns[i] = new Pattern();
        }

        // Copy Eggpod patterns at index 0
        System.arraycopy(eggpodPatterns, 0, combinedPatterns, 0, eggpodPatterns.length);

        // Copy HTZ boss patterns at index 0x60
        if (htzBossPatterns.length > 0) {
            for (int i = 0; i < htzBossPatterns.length && (HTZ_BOSS_OFFSET + i) < combinedSize; i++) {
                combinedPatterns[HTZ_BOSS_OFFSET + i] = htzBossPatterns[i];
            }
        }

        // ROM tiles: Eggpod at 0-$5F, HTZ boss at $60+. Matches our combined array layout.
        // art_tile = make_art_tile(Eggpod_2, 0, 0) → palette 0, so ROM piece palettes are final.
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_HTZ_BOSS_MAIN_ADDR);
        return new ObjectSpriteSheet(combinedPatterns, mappings, 0, 1);
    }

    /**
     * Load HTZ Boss smoke sprite sheet.
     * Uses same smoke art as CPZ boss (ArtNem_BossSmoke) with HTZ-specific mappings.
     * ROM Reference: Obj52_MapUnc_30258 (4 frames of smoke animation)
     *
     * @return sprite sheet for HTZ boss smoke, or null on failure
     */
    public ObjectSpriteSheet loadHTZBossSmokeSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_BOSS_SMOKE_ADDR,
                Sonic2Constants.MAP_UNC_HTZ_BOSS_SMOKE_ADDR, 0, 1);
    }

    /**
     * Load HTZ Lava Bubble (Obj20) sprite sheet.
     * ROM: art_tile = make_art_tile(ArtTile_ArtNem_HtzFireball2, 0, 1) — palette 0, priority 1.
     * Mappings: Obj20_MapUnc_23254 (6 frames of 2x2 tile sprites).
     */
    public ObjectSpriteSheet loadLavaBubbleSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_HTZ_FIREBALL2_ADDR,
                Sonic2Constants.MAP_UNC_LAVA_BUBBLE_ADDR, 0, 1);
    }

    /**
     * Load HTZ Ground Fire (Obj20 routine $A) sprite sheet.
     * ROM: art_tile = make_art_tile(ArtTile_ArtNem_HtzFireball1, 0, 1) — palette 0, priority 1.
     * Mappings: Obj20_MapUnc_23294 (6 frames of 2x4 tile sprites = 16x32px).
     */
    public ObjectSpriteSheet loadGroundFireSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_HTZ_FIREBALL1_ADDR,
                Sonic2Constants.MAP_UNC_GROUND_FIRE_ADDR, 0, 1);
    }

    /**
     * Load MCZ Boss sprite sheet.
     * Uses ArtNem_MCZBoss with mappings from Obj57_MapUnc_316EC.
     * ROM: art_tile = make_art_tile(ArtTile_ArtNem_MCZBoss,0,0) = $03C0
     *
     * @return sprite sheet for MCZ boss, or null on failure
     */
    public ObjectSpriteSheet loadMCZBossSheet() {
        Pattern[] bossPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_MCZ_BOSS_ADDR, "MCZBoss");
        if (bossPatterns.length == 0) {
            return null;
        }

        // Load Eggpod patterns (used for Robotnik's face in frames 14-19)
        // ROM: PlrList_MczBoss loads ArtNem_Eggpod at VRAM tile $0500
        Pattern[] eggpodPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EGGPOD_ADDR, "Eggpod");

        // Calculate offset: $500 - $3C0 = $140 = 320 tiles
        int eggpodOffset = Sonic2Constants.ART_TILE_EGGPOD_4 - Sonic2Constants.ART_TILE_MCZ_BOSS;

        // Create combined array large enough to hold both art sets at correct offsets
        int combinedSize = Math.max(bossPatterns.length, eggpodOffset + eggpodPatterns.length);
        Pattern[] combined = new Pattern[combinedSize];

        // Copy MCZ boss patterns starting at index 0
        System.arraycopy(bossPatterns, 0, combined, 0, bossPatterns.length);

        // Copy Eggpod patterns at offset $140 (320)
        for (int i = 0; i < eggpodPatterns.length && (eggpodOffset + i) < combinedSize; i++) {
            combined[eggpodOffset + i] = eggpodPatterns[i];
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MCZ_BOSS_ADDR);
        return new ObjectSpriteSheet(combined, mappings, 0, 1);
    }

    /**
     * Load MCZ Falling Rocks sprite sheet.
     * Uses ArtUnc_FallingRocks (256 bytes = 8 tiles uncompressed).
     * ROM: art_tile = make_art_tile(ArtTile_ArtUnc_FallingRocks,0,0) = $0560
     * Falling debris uses frames 0x0D (stone) and 0x14 (spike) from Obj57_MapUnc_316EC.
     * The 8-tile art covers tile indices 0-3 (stone, 2x2) and 4-7 (spike, 1x4).
     *
     * @return sprite sheet for falling rocks, or null on failure
     */
    public ObjectSpriteSheet loadMCZFallingRocksSheet() {
        Pattern[] rockPatterns = safeLoadUncompressedPatterns(
                Sonic2Constants.ART_UNC_FALLING_ROCKS_ADDR, 256, "FallingRocks");
        if (rockPatterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MCZ_BOSS_ADDR);
        return new ObjectSpriteSheet(rockPatterns, mappings, 0, 0);
    }

    // ========== MTZ Boss (Object 0x54) ==========

    /**
     * Load MTZ Boss sprite sheet.
     * Uses ArtNem_MTZBoss with mappings from Obj54_MapUnc_32DC6.
     * ROM: art_tile = make_art_tile(ArtTile_ArtNem_MTZBoss,0,0) = $037C
     *
     * PLC (PlrList_MtzBoss) loads 4 art sources:
     *   ArtNem_Eggpod      at tile $0500 (offset $184 from base)
     *   ArtNem_MTZBoss      at tile $037C (base)
     *   ArtNem_EggpodJets   at tile $0560 (offset $1E4 from base)
     *   ArtNem_FieryExplosion at tile $0580 (offset $204 from base)
     *
     * @return sprite sheet for MTZ boss, or null on failure
     */
    public ObjectSpriteSheet loadMTZBossSheet() {
        Pattern[] bossPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_MTZ_BOSS_ADDR, "MTZBoss");
        if (bossPatterns.length == 0) {
            return null;
        }

        // Load Eggpod patterns (Robotnik face/vehicle)
        Pattern[] eggpodPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EGGPOD_ADDR, "Eggpod");

        // Calculate offset: $0500 - $037C = $184 = 388 tiles
        int eggpodOffset = Sonic2Constants.ART_TILE_EGGPOD_4 - Sonic2Constants.ART_TILE_MTZ_BOSS;

        // Create combined array
        int combinedSize = Math.max(bossPatterns.length, eggpodOffset + eggpodPatterns.length);
        Pattern[] combined = new Pattern[combinedSize];

        // Copy MTZ boss patterns at base
        System.arraycopy(bossPatterns, 0, combined, 0, bossPatterns.length);

        // Copy Eggpod patterns at offset $184 (388)
        for (int i = 0; i < eggpodPatterns.length && (eggpodOffset + i) < combinedSize; i++) {
            combined[eggpodOffset + i] = eggpodPatterns[i];
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MTZ_BOSS_ADDR);
        return new ObjectSpriteSheet(combined, mappings, 0, 0);
    }

    public ObjectSpriteSheet loadOOZBossSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_OOZ_BOSS_ADDR, "OOZBoss");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_OOZ_BOSS_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    // ========== DEZ Silver Sonic / Mecha Sonic (Object 0xAF) ==========

    /**
     * Load Silver Sonic sprite sheet.
     * Uses ArtNem_SilverSonic with mappings from ObjAF_MapUnc_39E68.
     * ROM: art_tile = make_art_tile(ArtTile_ArtNem_SilverSonic,1,0) = $0380, palette line 1
     * 23 frames: 0-14 = Silver Sonic poses, 15-22 = spikeball projectiles
     *
     * @return sprite sheet for Silver Sonic, or null on failure
     */
    public ObjectSpriteSheet loadSilverSonicSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SILVER_SONIC_ADDR, "SilverSonic");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_SILVER_SONIC_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    /**
     * Load DEZ Window sprite sheet (Robotnik watching through window).
     * Uses ArtNem_DEZWindow with mappings from ObjAF_MapUnc_3A08C.
     * ROM: art_tile = make_art_tile(ArtTile_ArtNem_DEZWindow,0,0) = $0378, palette line 0
     * 8 frames of window/blinds animation
     *
     * The mapping data references two separate VRAM art regions:
     * - Tiles 0-7: DEZ window frame/blinds (from ArtNem_DEZWindow at VRAM $0378)
     * - Tiles $190-$19F: Robotnik's face (from ArtNem_RobotnikUpper at VRAM $0500)
     *
     * The offset $190 = $0500 - $0378 + 8 = Robotnik Upper tile 8. In the ROM,
     * both art sets are loaded to VRAM by PlrList_Dez2 and the mapping's tile
     * indices bridge across them. The engine must create a combined pattern array
     * so that the renderer can resolve both tile ranges.
     *
     * @return sprite sheet for DEZ window, or null on failure
     */
    public ObjectSpriteSheet loadDEZWindowSheet() {
        Pattern[] windowPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_DEZ_WINDOW_ADDR, "DEZWindow");
        if (windowPatterns.length == 0) {
            return null;
        }
        Pattern[] robotnikPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_ROBOTNIK_UPPER_ADDR, "RobotnikUpper");

        // ROM VRAM layout: DEZWindow at $0378, RobotnikUpper at $0500.
        // Mapping tile indices are relative to DEZWindow's art_tile ($0378).
        // RobotnikUpper offset = $0500 - $0378 = $0188 = 392 tiles from window base.
        // Mappings reference tiles $190-$19F (RobotnikUpper tiles 8-23).
        int robotnikOffset = 0x0500 - 0x0378; // 392 = where RobotnikUpper starts in combined array

        // Determine combined array size from max referenced tile
        int combinedSize = robotnikOffset + robotnikPatterns.length;
        Pattern[] combined = new Pattern[combinedSize];

        // Fill with empty patterns to avoid null references
        for (int i = 0; i < combinedSize; i++) {
            combined[i] = new Pattern();
        }

        // Copy window patterns at index 0
        System.arraycopy(windowPatterns, 0, combined, 0,
                Math.min(windowPatterns.length, combinedSize));

        // Copy RobotnikUpper patterns at offset 392
        int copyLen = Math.min(robotnikPatterns.length, combinedSize - robotnikOffset);
        if (copyLen > 0) {
            System.arraycopy(robotnikPatterns, 0, combined, robotnikOffset, copyLen);
        }

        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_DEZ_WINDOW_ADDR);
        return new ObjectSpriteSheet(combined, mappings, 0, 1);
    }

    // ========== DEZ Boss / Death Egg Robot (Object 0xC7) ==========

    /**
     * Load DEZ Boss (Death Egg Robot / Eggrobo) sprite sheet.
     * Uses ArtNem_DEZBoss with mappings from ObjC7_MapUnc_3E5F8.
     * ROM: art_tile = make_art_tile(ArtTile_ArtNem_DEZBoss,0,0) = $0330, palette line 0
     * 23 frames: body, shoulder, arm, forearm, thighs, legs, head, jet, bomb, sensor, lock
     *
     * @return sprite sheet for DEZ Boss, or null on failure
     */
    public ObjectSpriteSheet loadDEZBossSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_DEZ_BOSS_ADDR, "DEZBoss");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_DEZ_BOSS_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    // ========== DEZ Eggman Transition (ObjC6 State2) ==========

    /**
     * Load DEZ Eggman transition sprite sheet (Robotnik running to cockpit).
     * Uses three art blocks loaded by PlrList_Dez2:
     * - ArtNem_RobotnikUpper at VRAM $0500 (head/torso)
     * - ArtNem_RobotnikRunning at VRAM $0518 (running legs)
     * - ArtNem_RobotnikLower at VRAM $0564 (lower body/shared)
     *
     * Mappings from ObjC6_MapUnc_3D0EE reference tiles as absolute VRAM positions
     * (art_tile base = ArtTile_ArtKos_LevelArt = $0000). The combined pattern array
     * is offset by -$0500 so mapping tile $0500 maps to array index 0.
     *
     * ROM frames: 0=standing, 1=surprised, 2=jumping, 3-4=running (body variants),
     *             5=exhaust puff, 6-7=running (leg variants)
     *
     * @return sprite sheet for DEZ Eggman transition, or null on failure
     */
    public ObjectSpriteSheet loadDEZEggmanSheet() {
        Pattern[] upperPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_ROBOTNIK_UPPER_ADDR, "RobotnikUpper");
        Pattern[] runningPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_ROBOTNIK_RUNNING_ADDR, "RobotnikRunning");
        Pattern[] lowerPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_ROBOTNIK_LOWER_ADDR, "RobotnikLower");
        if (upperPatterns.length == 0 && runningPatterns.length == 0 && lowerPatterns.length == 0) {
            return null;
        }

        // VRAM layout: Upper at $0500, Running at $0518, Lower at $0564
        // Combined array indexed from $0500, so:
        //   Upper -> index 0 ($0500-$0500)
        //   Running -> index $18 = 24 ($0518-$0500)
        //   Lower -> index $64 = 100 ($0564-$0500)
        int runningOffset = 0x0518 - 0x0500; // 24
        int lowerOffset = 0x0564 - 0x0500;   // 100

        int combinedSize = lowerOffset + lowerPatterns.length;
        Pattern[] combined = new Pattern[combinedSize];

        // Fill with empty patterns to avoid null references
        for (int i = 0; i < combinedSize; i++) {
            combined[i] = new Pattern();
        }

        // Copy each art block at its correct offset
        System.arraycopy(upperPatterns, 0, combined, 0,
                Math.min(upperPatterns.length, combinedSize));
        if (runningOffset + runningPatterns.length <= combinedSize) {
            System.arraycopy(runningPatterns, 0, combined, runningOffset, runningPatterns.length);
        }
        if (lowerOffset + lowerPatterns.length <= combinedSize) {
            System.arraycopy(lowerPatterns, 0, combined, lowerOffset, lowerPatterns.length);
        }

        // Mappings reference absolute VRAM tiles starting at $0500; shift by -$0500
        List<SpriteMappingFrame> mappings = loadMappingFramesWithTileOffset(
                Sonic2Constants.MAP_UNC_WFZ_ROBOTNIK_ADDR, -0x0500);
        return new ObjectSpriteSheet(combined, mappings, 0, 1);
    }

    /**
     * Load DEZ barrier wall sprite sheet (ObjC6 subtype $A8).
     * ROM: ArtNem_ConstructionStripes at 0x827F8, palette line 1.
     * Art tile: make_art_tile(ArtTile_ArtNem_ConstructionStripes_1,1,0) = $0328 | palette 1.
     * 4 frames from ObjC6_MapUnc_3D1DE (construction stripe wall opening animation).
     *
     * @return sprite sheet for DEZ barrier wall, or null on failure
     */
    public ObjectSpriteSheet loadDEZWallSheet() {
        return buildArtSheetFromRom(Sonic2Constants.ART_NEM_CONSTRUCTION_STRIPES_ADDR,
                Sonic2Constants.MAP_UNC_DEZ_WALL_ADDR, 1, 1);
    }

    // ========== MTZ Nut (Object 0x69) ==========

    /**
     * Load MTZ Nut sprite sheet.
     * ROM: ArtNem_MtzAsstBlocks at 0xF1550, palette line 1
     * Art tile: make_art_tile(ArtTile_ArtNem_MtzAsstBlocks,1,0) = $0500 | palette 1
     */
    public ObjectSpriteSheet loadMTZNutSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_MTZ_ASST_BLOCKS_ADDR, "MtzAsstBlocks");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MTZ_NUT_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

    // ========== MTZ Lava Bubble (Object 0x71) ==========

    /**
     * Load MTZ Lava Bubble sprite sheet.
     * ROM: ArtNem_MtzLavaBubble at 0xF15C6, palette line 2.
     * Art tile: make_art_tile(ArtTile_ArtNem_MtzLavaBubble,2,0) = $0536 | palette 2, no priority.
     */
    public ObjectSpriteSheet loadMTZLavaBubbleSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(
                Sonic2Constants.ART_NEM_MTZ_LAVA_BUBBLE_ADDR, "MtzLavaBubble");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_MTZ_LAVA_BUBBLE_B_ADDR);
        // Palette line 2: make_art_tile(ArtTile_ArtNem_MtzLavaBubble,2,0)
        return new ObjectSpriteSheet(patterns, mappings, 2, 1);
    }

    // ========== WFZ Boss (Object 0xC5) ==========

    /**
     * Load WFZ Boss sprite sheet (laser case, walls, platforms, laser).
     * ROM: ArtNem_WfzBoss at 0x8E138, tile base $0379, palette line 0.
     * 19 frames from ObjC5_MapUnc_3CCD8.
     *
     * @return sprite sheet for WFZ boss, or null on failure
     */
    public ObjectSpriteSheet loadWFZBossSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_WFZ_BOSS_ADDR, "WfzBoss");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_WFZ_BOSS_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 0, 1);
    }

    /**
     * Load WFZ Robotnik sprite sheet.
     * ROM: ObjC6_SubObjData2 uses ObjC6_MapUnc_3D0EE with ArtTile_ArtKos_LevelArt ($0000).
     * PLCID_WfzBoss loads the Robotnik art blocks at absolute VRAM tile positions:
     * Upper=$0500, Running=$0518, Lower=$0564. The sheet is therefore composed from those
     * ROM Nemesis blocks and mappings are shifted by -$0500.
     *
     * @return sprite sheet for WFZ Robotnik, or null on failure
     */
    public ObjectSpriteSheet loadWFZRobotnikSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_ROBOTNIK_UPPER_ADDR, "WfzRobotnik");
        Pattern[] runningPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_ROBOTNIK_RUNNING_ADDR, "WfzRobotnikRunning");
        Pattern[] lowerPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_ROBOTNIK_LOWER_ADDR, "WfzRobotnikLower");
        if (patterns.length == 0 && runningPatterns.length == 0 && lowerPatterns.length == 0) {
            return null;
        }

        int runningOffset = 0x0518 - 0x0500;
        int lowerOffset = 0x0564 - 0x0500;
        int combinedSize = lowerOffset + lowerPatterns.length;
        Pattern[] combined = new Pattern[combinedSize];
        for (int i = 0; i < combinedSize; i++) {
            combined[i] = new Pattern();
        }

        System.arraycopy(patterns, 0, combined, 0, Math.min(patterns.length, combinedSize));
        if (runningOffset + runningPatterns.length <= combinedSize) {
            System.arraycopy(runningPatterns, 0, combined, runningOffset, runningPatterns.length);
        }
        if (lowerOffset + lowerPatterns.length <= combinedSize) {
            System.arraycopy(lowerPatterns, 0, combined, lowerOffset, lowerPatterns.length);
        }

        List<SpriteMappingFrame> mappings = loadMappingFramesWithTileOffset(
                Sonic2Constants.MAP_UNC_WFZ_ROBOTNIK_ADDR, -0x0500);
        return new ObjectSpriteSheet(combined, mappings, 0, 1);
    }

    /**
     * Load WFZ Robotnik Platform sprite sheet.
     * ROM: ArtNem_WfzFloatingPlatform at 0x8D96E, tile base $046D, palette line 1.
     * 1 frame from ObjC6_MapUnc_3CEBC.
     *
     * @return sprite sheet for WFZ Robotnik platform, or null on failure
     */
    public ObjectSpriteSheet loadWFZRobotnikPlatformSheet() {
        Pattern[] patterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_WFZ_FLOAT_PLATFORM_ADDR, "WfzRobotnikPlatform");
        if (patterns.length == 0) {
            return null;
        }
        List<SpriteMappingFrame> mappings = loadMappingFrames(Sonic2Constants.MAP_UNC_WFZ_ROBOTNIK_PLATFORM_ADDR);
        return new ObjectSpriteSheet(patterns, mappings, 1, 1);
    }

}
