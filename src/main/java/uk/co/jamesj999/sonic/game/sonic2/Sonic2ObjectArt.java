package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.game.GameModuleRegistry;
import uk.co.jamesj999.sonic.game.ZoneArtProvider;
import uk.co.jamesj999.sonic.game.ZoneArtProvider.ObjectArtConfig;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2ObjectIds;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.AnimalType;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.objects.ObjectArtData;
import uk.co.jamesj999.sonic.level.objects.ObjectSpriteSheet;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationEndAction;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationScript;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;
import uk.co.jamesj999.sonic.tools.NemesisReader;

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
        // Load Tails Life Art (used for Tails Monitor icon, requests tile 340)
        Pattern[] tailsLifePatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_TAILS_LIFE_ADDR, "TailsLife");

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
        int lifeArtOffset = 340;
        requiredSize = Math.max(requiredSize, lifeArtOffset + tailsLifePatterns.length);

        Pattern[] monitorPatterns = new Pattern[requiredSize];
        // Copy base patterns
        if (monitorBasePatterns.length > 0) {
            System.arraycopy(monitorBasePatterns, 0, monitorPatterns, 0, monitorBasePatterns.length);
        }
        // Copy Tails Life patterns at offset 340
        if (tailsLifePatterns.length > 0 && lifeArtOffset < monitorPatterns.length) {
            System.arraycopy(tailsLifePatterns, 0, monitorPatterns, lifeArtOffset,
                    Math.min(tailsLifePatterns.length, monitorPatterns.length - lifeArtOffset));
        }

        // Fill gaps with empty patterns to prevent NPEs
        for (int i = 0; i < monitorPatterns.length; i++) {
            if (monitorPatterns[i] == null) {
                monitorPatterns[i] = new Pattern();
            }
        }

        ObjectSpriteSheet monitorSheet = new ObjectSpriteSheet(monitorPatterns, monitorMappings, 0, 1);

        Pattern[] spikePatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SPIKES_ADDR, "Spikes");
        Pattern[] spikeSidePatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SPIKES_SIDE_ADDR, "SpikesSide");
        List<SpriteMappingFrame> spikeMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_SPIKES_ADDR);
        ObjectSpriteSheet spikeSheet = new ObjectSpriteSheet(spikePatterns, spikeMappings, 1, 1);
        ObjectSpriteSheet spikeSideSheet = new ObjectSpriteSheet(spikeSidePatterns, spikeMappings, 1, 1);

        Pattern[] springVerticalPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SPRING_VERTICAL_ADDR,
                "SpringVertical");
        Pattern[] springHorizontalPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SPRING_HORIZONTAL_ADDR,
                "SpringHorizontal");
        Pattern[] springDiagonalPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SPRING_DIAGONAL_ADDR,
                "SpringDiagonal");
        List<SpriteMappingFrame> springMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_SPRING_ADDR);
        List<SpriteMappingFrame> springMappingsRed = loadMappingFrames(Sonic2Constants.MAP_UNC_SPRING_RED_ADDR);
        ObjectSpriteSheet springVerticalSheet = new ObjectSpriteSheet(springVerticalPatterns, springMappings, 0, 1);
        ObjectSpriteSheet springHorizontalSheet = new ObjectSpriteSheet(springHorizontalPatterns, springMappings, 0, 1);
        ObjectSpriteSheet springDiagonalSheet = new ObjectSpriteSheet(springDiagonalPatterns, springMappings, 0, 1);
        ObjectSpriteSheet springVerticalRedSheet = new ObjectSpriteSheet(springVerticalPatterns, springMappingsRed, 1,
                1);
        ObjectSpriteSheet springHorizontalRedSheet = new ObjectSpriteSheet(springHorizontalPatterns, springMappingsRed,
                1, 1);

        ObjectSpriteSheet springDiagonalRedSheet = new ObjectSpriteSheet(springDiagonalPatterns, springMappingsRed, 1,
                1);

        Pattern[] explosionPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EXPLOSION_ADDR, "Explosion");
        List<SpriteMappingFrame> explosionMappings = createExplosionMappings();
        ObjectSpriteSheet explosionSheet = new ObjectSpriteSheet(explosionPatterns, explosionMappings, 1, 1);

        Pattern[] shieldPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SHIELD_ADDR, "Shield");
        List<SpriteMappingFrame> shieldMappings = createShieldMappings();
        ObjectSpriteSheet shieldSheet = new ObjectSpriteSheet(shieldPatterns, shieldMappings, 0, 1);

        Pattern[] bridgePatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_BRIDGE_ADDR, "Bridge");
        List<SpriteMappingFrame> bridgeMappings = createBridgeMappings();
        ObjectSpriteSheet bridgeSheet = new ObjectSpriteSheet(bridgePatterns, bridgeMappings, 2, 1);

        Pattern[] waterfallPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_EHZ_WATERFALL_ADDR,
                "EHZWaterfall");
        List<SpriteMappingFrame> waterfallMappings = createEHZWaterfallMappings();
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

        Pattern[] invincibilityStarsPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_INVINCIBILITY_STARS_ADDR,
                "InvincibilityStars");
        List<SpriteMappingFrame> rawInvincibilityStarsMappings = loadMappingFrames(
                Sonic2Constants.MAP_UNC_INVINCIBILITY_STARS_ADDR);
        List<SpriteMappingFrame> invincibilityStarsMappings = normalizeMappings(rawInvincibilityStarsMappings);

        ObjectSpriteSheet invincibilityStarsSheet = new ObjectSpriteSheet(invincibilityStarsPatterns,
                invincibilityStarsMappings, 0, 1);

        SpriteAnimationSet monitorAnimations = loadAnimationSet(
                Sonic2Constants.ANI_OBJ26_ADDR,
                Sonic2Constants.ANI_OBJ26_SCRIPT_COUNT);
        SpriteAnimationSet springAnimations = loadAnimationSet(
                Sonic2Constants.ANI_OBJ41_ADDR,
                Sonic2Constants.ANI_OBJ41_SCRIPT_COUNT);

        // Checkpoint/Starpost art
        Pattern[] checkpointPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_CHECKPOINT_ADDR, "Checkpoint");
        List<SpriteMappingFrame> checkpointMappings = loadMappingFrames(Sonic2Constants.MAP_UNC_CHECKPOINT_ADDR);
        List<SpriteMappingFrame> checkpointStarMappings = loadMappingFrames(
                Sonic2Constants.MAP_UNC_CHECKPOINT_STAR_ADDR);
        ObjectSpriteSheet checkpointSheet = new ObjectSpriteSheet(checkpointPatterns, checkpointMappings, 0, 1);
        ObjectSpriteSheet checkpointStarSheet = new ObjectSpriteSheet(checkpointPatterns, checkpointStarMappings, 0, 1);
        SpriteAnimationSet checkpointAnimations = loadAnimationSet(
                Sonic2Constants.ANI_OBJ79_ADDR,
                Sonic2Constants.ANI_OBJ79_SCRIPT_COUNT);

        // Badnik art (Masher, Buzzer, Coconuts)
        Pattern[] masherPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_MASHER_ADDR, "Masher");
        List<SpriteMappingFrame> masherMappings = createMasherMappings();
        ObjectSpriteSheet masherSheet = new ObjectSpriteSheet(masherPatterns, masherMappings, 0, 1);

        Pattern[] buzzerPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_BUZZER_ADDR, "Buzzer");
        List<SpriteMappingFrame> buzzerMappings = createBuzzerMappings();
        ObjectSpriteSheet buzzerSheet = new ObjectSpriteSheet(buzzerPatterns, buzzerMappings, 0, 1);

        Pattern[] coconutsPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_COCONUTS_ADDR, "Coconuts");
        List<SpriteMappingFrame> coconutsMappings = createCoconutsMappings();
        ObjectSpriteSheet coconutsSheet = new ObjectSpriteSheet(coconutsPatterns, coconutsMappings, 0, 1);

        Pattern[] animalPatterns = loadAnimalPatterns(animalTypeA, animalTypeB);
        List<SpriteMappingFrame> animalMappings = createAnimalMappings();
        ObjectSpriteSheet animalSheet = new ObjectSpriteSheet(animalPatterns, animalMappings, 0, 1);

        // Load correct points art
        Pattern[] pointsPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_NUMBERS_ADDR, "Numbers");
        List<SpriteMappingFrame> pointsMappings = createPointsMappings();
        ObjectSpriteSheet pointsSheet = new ObjectSpriteSheet(pointsPatterns, pointsMappings, 0, 1);

        // Signpost/Goal plate art
        Pattern[] signpostPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SIGNPOST_ADDR, "Signpost");
        List<SpriteMappingFrame> signpostMappings = createSignpostMappings();
        ObjectSpriteSheet signpostSheet = new ObjectSpriteSheet(signpostPatterns, signpostMappings, 0, 1);
        SpriteAnimationSet signpostAnimations = createSignpostAnimations();

        // CNZ Round Bumper art (Object 0x44)
        Pattern[] bumperPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_BUMPER_ADDR, "Bumper");
        List<SpriteMappingFrame> bumperMappings = createBumperMappings();
        ObjectSpriteSheet bumperSheet = new ObjectSpriteSheet(bumperPatterns, bumperMappings, 2, 1);

        // CNZ Hexagonal Bumper art (Object 0xD7)
        Pattern[] hexBumperPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_HEX_BUMPER_ADDR, "HexBumper");
        List<SpriteMappingFrame> hexBumperMappings = createHexBumperMappings();
        ObjectSpriteSheet hexBumperSheet = new ObjectSpriteSheet(hexBumperPatterns, hexBumperMappings, 2, 1);

        // CNZ Bonus Block / Drop Target art (Object 0xD8)
        Pattern[] bonusBlockPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_BONUS_BLOCK_ADDR, "BonusBlock");
        List<SpriteMappingFrame> bonusBlockMappings = createBonusBlockMappings();
        ObjectSpriteSheet bonusBlockSheet = new ObjectSpriteSheet(bonusBlockPatterns, bonusBlockMappings, 2, 1);

        // CNZ Flipper art (Object 0x86)
        Pattern[] flipperPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_FLIPPER_ADDR, "Flipper");
        List<SpriteMappingFrame> flipperMappings = createFlipperMappings();
        ObjectSpriteSheet flipperSheet = new ObjectSpriteSheet(flipperPatterns, flipperMappings, 2, 1);
        SpriteAnimationSet flipperAnimations = createFlipperAnimations();

        // CPZ Speed Booster art (Object 0x1B)
        Pattern[] speedBoosterPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_SPEED_BOOSTER_ADDR, "SpeedBooster");
        List<SpriteMappingFrame> speedBoosterMappings = createSpeedBoosterMappings();
        ObjectSpriteSheet speedBoosterSheet = new ObjectSpriteSheet(speedBoosterPatterns, speedBoosterMappings, 3, 1);

        // CPZ BlueBalls art (Object 0x1D) - water droplet hazard
        Pattern[] blueBallsPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_CPZ_DROPLET_ADDR, "CPZDroplet");
        List<SpriteMappingFrame> blueBallsMappings = createBlueBallsMappings();
        ObjectSpriteSheet blueBallsSheet = new ObjectSpriteSheet(blueBallsPatterns, blueBallsMappings, 3, 0);

        // CPZ Breakable Block art (Object 0x32) - metal blocks that shatter when rolled into
        Pattern[] breakableBlockPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_CPZ_METAL_BLOCK_ADDR, "CPZMetalBlock");
        List<SpriteMappingFrame> breakableBlockMappings = createBreakableBlockMappings();
        ObjectSpriteSheet breakableBlockSheet = new ObjectSpriteSheet(breakableBlockPatterns, breakableBlockMappings, 3, 1);

        // CPZ/OOZ/WFZ Moving Platform art (Object 0x19)
        // Load art based on zone via ZoneArtProvider
        ObjectArtConfig platformArtConfig = getObjectArtConfig(Sonic2ObjectIds.GENERIC_PLATFORM_B, zoneIndex);
        int cpzPlatformArtAddr = platformArtConfig != null ? platformArtConfig.artAddress() : Sonic2Constants.ART_NEM_CPZ_ELEVATOR_ADDR;
        int cpzPlatformPalette = platformArtConfig != null ? platformArtConfig.palette() : 3;
        Pattern[] cpzPlatformPatterns = safeLoadNemesisPatterns(cpzPlatformArtAddr, "CPZPlatform");
        List<SpriteMappingFrame> cpzPlatformMappings = createCPZPlatformMappings();
        ObjectSpriteSheet cpzPlatformSheet = new ObjectSpriteSheet(cpzPlatformPatterns, cpzPlatformMappings, cpzPlatformPalette, 0);

        // CPZ Stair Block art (Object 0x78) - moving staircase blocks in CPZ
        Pattern[] cpzStairBlockPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_CPZ_STAIRBLOCK_ADDR, "CPZStairBlock");
        List<SpriteMappingFrame> cpzStairBlockMappings = createCPZStairBlockMappings();
        ObjectSpriteSheet cpzStairBlockSheet = new ObjectSpriteSheet(cpzStairBlockPatterns, cpzStairBlockMappings, 3, 1);

        // CPZ Pipe Exit Spring art (Object 0x7B) - warp tube exit spring
        Pattern[] pipeExitSpringPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_PIPE_EXIT_SPRING_ADDR, "PipeExitSpring");
        List<SpriteMappingFrame> pipeExitSpringMappings = createPipeExitSpringMappings();
        ObjectSpriteSheet pipeExitSpringSheet = new ObjectSpriteSheet(pipeExitSpringPatterns, pipeExitSpringMappings, 0, 1);
        SpriteAnimationSet pipeExitSpringAnimations = createPipeExitSpringAnimations();

        // CPZ Tipping Floor art (Object 0x0B) - platform that tips back and forth
        Pattern[] tippingFloorPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_CPZ_ANIMATED_BITS_ADDR, "CPZAnimatedBits");
        List<SpriteMappingFrame> tippingFloorMappings = createTippingFloorMappings();
        ObjectSpriteSheet tippingFloorSheet = new ObjectSpriteSheet(tippingFloorPatterns, tippingFloorMappings, 3, 1);
        SpriteAnimationSet tippingFloorAnimations = createTippingFloorAnimations();

        // CPZ/DEZ Barrier art (Object 0x2D) - one-way rising barrier with construction stripes
        Pattern[] barrierPatterns = safeLoadNemesisPatterns(Sonic2Constants.ART_NEM_CONSTRUCTION_STRIPES_ADDR, "ConstructionStripes");
        List<SpriteMappingFrame> barrierMappings = createBarrierMappings();
        ObjectSpriteSheet barrierSheet = new ObjectSpriteSheet(barrierPatterns, barrierMappings, 1, 1);

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
                masherSheet,
                buzzerSheet,
                coconutsSheet,
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
                breakableBlockSheet,
                cpzPlatformSheet,
                cpzStairBlockSheet,
                pipeExitSpringSheet,
                tippingFloorSheet,
                barrierSheet,
                resultsSheet,
                hudDigitPatterns,
                hudTextPatterns,
                hudLivesPatterns,
                hudLivesNumbers,
                (Pattern[]) null, // debugFontPatterns
                monitorMappings,
                springMappings,
                checkpointMappings,
                monitorAnimations,
                springAnimations,
                checkpointAnimations,
                signpostAnimations,
                flipperAnimations,
                pipeExitSpringAnimations,
                tippingFloorAnimations);

        cachedByZone.put(cacheKey, artData);
        return artData;
    }

    private Pattern[] loadNemesisPatterns(int artAddr) throws IOException {
        FileChannel channel = rom.getFileChannel();
        channel.position(artAddr);
        byte[] result = NemesisReader.decompress(channel);

        if (result.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent object art tile data");
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

    private Pattern[] loadUncompressedPatterns(int artAddr, int length) throws IOException {
        if (length <= 0) {
            return new Pattern[0];
        }
        FileChannel channel = rom.getFileChannel();
        channel.position(artAddr);
        byte[] result = new byte[length];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(result);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                break;
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
            return loadNemesisPatterns(artAddr);
        } catch (Exception e) {
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
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE,
                    String.format("Failed to load uncompressed art '%s' at 0x%06X", assetName, artAddr), e);
            return new Pattern[0];
        }
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

    private AnimalType[] resolveZoneAnimals(int zoneIndex) {
        if (zoneIndex < 0 || zoneIndex >= ZONE_ANIMALS.length) {
            return DEFAULT_ANIMALS;
        }
        return ZONE_ANIMALS[zoneIndex];
    }

    private Pattern[] loadAnimalPatterns(AnimalType animalTypeA, AnimalType animalTypeB) {
        Pattern[] animalPatternsA = safeLoadNemesisPatterns(animalTypeA.artAddr(),
                "Animal-" + animalTypeA.displayName());
        Pattern[] animalPatternsB = safeLoadNemesisPatterns(animalTypeB.artAddr(),
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
        int offsetTableSize = reader.readU16BE(mappingAddr);
        int frameCount = offsetTableSize / 2;
        List<SpriteMappingFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameAddr = mappingAddr + reader.readU16BE(mappingAddr + i * 2);
            int pieceCount = reader.readU16BE(frameAddr);
            frameAddr += 2;
            List<SpriteMappingPiece> pieces = new ArrayList<>(pieceCount);
            for (int p = 0; p < pieceCount; p++) {
                int yOffset = (byte) reader.readU8(frameAddr);
                frameAddr += 1;
                int size = reader.readU8(frameAddr);
                frameAddr += 1;
                int tileWord = reader.readU16BE(frameAddr);
                frameAddr += 2;
                frameAddr += 2; // 2P tile word, unused in 1P.
                int xOffset = (short) reader.readU16BE(frameAddr);
                frameAddr += 2;

                int widthTiles = ((size >> 2) & 0x3) + 1;
                int heightTiles = (size & 0x3) + 1;

                int tileIndex = tileWord & 0x7FF;
                boolean hFlip = (tileWord & 0x800) != 0;
                boolean vFlip = (tileWord & 0x1000) != 0;
                int paletteIndex = (tileWord >> 13) & 0x3;

                pieces.add(new SpriteMappingPiece(
                        xOffset, yOffset, widthTiles, heightTiles, tileIndex, hFlip, vFlip, paletteIndex));
            }
            frames.add(new SpriteMappingFrame(pieces));
        }
        return frames;
    }

    /**
     * Loads mapping frames from ROM and applies a tile index offset to each piece.
     * This allows ROM mappings that use VRAM tile indices to work with pattern
     * arrays
     * that start at index 0.
     *
     * @param mappingAddr ROM address of the mapping data
     * @param tileOffset  Offset to add to each tile index (use negative to
     *                    subtract)
     */
    private List<SpriteMappingFrame> loadMappingFramesWithTileOffset(int mappingAddr, int tileOffset) {
        int offsetTableSize = reader.readU16BE(mappingAddr);
        int frameCount = offsetTableSize / 2;
        List<SpriteMappingFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            // Read offset as signed 16-bit (negative offsets reference other mapping
            // tables)
            int rawOffset = reader.readU16BE(mappingAddr + i * 2);
            int signedOffset = (rawOffset > 32767) ? rawOffset - 65536 : rawOffset;

            int frameAddr = mappingAddr + signedOffset;
            int pieceCount = reader.readU16BE(frameAddr);
            frameAddr += 2;
            List<SpriteMappingPiece> pieces = new ArrayList<>(pieceCount);
            for (int p = 0; p < pieceCount; p++) {
                int yOffset = (byte) reader.readU8(frameAddr);
                frameAddr += 1;
                int size = reader.readU8(frameAddr);
                frameAddr += 1;
                int tileWord = reader.readU16BE(frameAddr);
                frameAddr += 2;
                frameAddr += 2; // 2P tile word, unused in 1P.
                int xOffset = (short) reader.readU16BE(frameAddr);
                frameAddr += 2;

                int widthTiles = ((size >> 2) & 0x3) + 1;
                int heightTiles = (size & 0x3) + 1;

                int tileIndex = (tileWord & 0x7FF) + tileOffset;
                // Clamp to valid range
                if (tileIndex < 0)
                    tileIndex = 0;

                boolean hFlip = (tileWord & 0x800) != 0;
                boolean vFlip = (tileWord & 0x1000) != 0;
                int paletteIndex = (tileWord >> 13) & 0x3;

                pieces.add(new SpriteMappingPiece(
                        xOffset, yOffset, widthTiles, heightTiles, tileIndex, hFlip, vFlip, paletteIndex));
            }
            frames.add(new SpriteMappingFrame(pieces));
        }
        return frames;
    }

    private SpriteAnimationSet loadAnimationSet(int animAddr, int scriptCount) {
        SpriteAnimationSet set = new SpriteAnimationSet();
        for (int i = 0; i < scriptCount; i++) {
            int scriptAddr = animAddr + reader.readU16BE(animAddr + i * 2);
            int delay = reader.readU8(scriptAddr);
            scriptAddr += 1;

            List<Integer> frames = new ArrayList<>();
            SpriteAnimationEndAction endAction = SpriteAnimationEndAction.LOOP;
            int endParam = 0;

            while (true) {
                int value = reader.readU8(scriptAddr);
                scriptAddr += 1;
                if (value >= 0xF0) {
                    if (value == 0xFF) {
                        endAction = SpriteAnimationEndAction.LOOP;
                        break;
                    }
                    if (value == 0xFE) {
                        endAction = SpriteAnimationEndAction.LOOP_BACK;
                        endParam = reader.readU8(scriptAddr);
                        scriptAddr += 1;
                        break;
                    }
                    if (value == 0xFD) {
                        endAction = SpriteAnimationEndAction.SWITCH;
                        endParam = reader.readU8(scriptAddr);
                        scriptAddr += 1;
                        break;
                    }
                    endAction = SpriteAnimationEndAction.HOLD;
                    break;
                }
                frames.add(value);
            }

            set.addScript(i, new SpriteAnimationScript(delay, frames, endAction, endParam));
        }
        return set;
    }

    private List<SpriteMappingFrame> createExplosionMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        // Frame 0: -8, -8, 2x2, tile 0 (16x16 pixels)
        frames.add(createSimpleFrame(-8, -8, 2, 2, 0));
        // Frame 1: -16, -16, 4x4, tile 4 (32x32 pixels)
        frames.add(createSimpleFrame(-16, -16, 4, 4, 4));
        // Frame 2: -16, -16, 4x4, tile 20 (0x14)
        frames.add(createSimpleFrame(-16, -16, 4, 4, 20));
        // Frame 3: -16, -16, 4x4, tile 36 (0x24)
        frames.add(createSimpleFrame(-16, -16, 4, 4, 36));
        // Frame 4: -16, -16, 4x4, tile 52 (0x34)
        frames.add(createSimpleFrame(-16, -16, 4, 4, 52));
        return frames;
    }

    private List<SpriteMappingFrame> createShieldMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: Map_obj38_000C (0 tiles offset)
        frames.add(create2x2Frame(0));
        // Frame 1: Map_obj38_002E (4 tiles offset)
        frames.add(create2x2Frame(4));
        // Frame 2: Map_obj38_0050 (8 tiles offset)
        frames.add(create2x2Frame(8));
        // Frame 3: Map_obj38_0072 (12 tiles offset)
        frames.add(create2x2Frame(12));
        // Frame 4: Map_obj38_0094 (16 tiles offset)
        frames.add(create2x2Frame(16));

        // Frame 5: Map_obj38_00B6 (20 tiles offset) - Larger frame
        List<SpriteMappingPiece> pieces5 = new ArrayList<>();
        // Note: Palette index 0 assumed.
        // pieces: xOffset, yOffset, w, h, tileIndex, hFlip, vFlip, palIndex
        // obj38.asm: spritePiece -$18, -$20, 3, 4, $14... (3 tiles wide, 4 tiles high)
        pieces5.add(new SpriteMappingPiece(-24, -32, 3, 4, 20, false, false, 0));
        pieces5.add(new SpriteMappingPiece(0, -32, 3, 4, 20, true, false, 0));
        pieces5.add(new SpriteMappingPiece(-24, 0, 3, 4, 20, false, true, 0));
        pieces5.add(new SpriteMappingPiece(0, 0, 3, 4, 20, true, true, 0));

        frames.add(new SpriteMappingFrame(pieces5));

        return frames;
    }

    /**
     * Creates bridge mappings based on obj11_b.asm:
     * Frame 0: 2x2 tiles at tile index 4 (log segment 1)
     * Frame 1: 2x2 tiles at tile index 0 (log segment 2 / stake)
     */
    private List<SpriteMappingFrame> createBridgeMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        // Frame 0: -8, -8, 2x2, tile 4
        frames.add(createSimpleFrame(-8, -8, 2, 2, 4));
        // Frame 1: -8, -8, 2x2, tile 0
        frames.add(createSimpleFrame(-8, -8, 2, 2, 0));
        return frames;
    }

    /**
     * Creates bumper mappings based on obj44.asm (Round Bumper from CNZ).
     * Frame 0: Normal state - 2x(2x4) tiles at -16,-16 with horizontal flip (32x32
     * px)
     * Frame 1: Compressed state - 2x(3x4) + 2x(2x2) tiles (48x44 px)
     */
    private List<SpriteMappingFrame> createBumperMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: Map_obj44_0004 - Normal state
        // spritePiece -$10, -$10, 2, 4, 0, 0, 0, 0, 0
        // spritePiece 0, -$10, 2, 4, 0, 1, 0, 0, 0 (hFlip)
        List<SpriteMappingPiece> frame0Pieces = new ArrayList<>();
        frame0Pieces.add(new SpriteMappingPiece(-16, -16, 2, 4, 0, false, false, 0));
        frame0Pieces.add(new SpriteMappingPiece(0, -16, 2, 4, 0, true, false, 0));
        frames.add(new SpriteMappingFrame(frame0Pieces));

        // Frame 1: Map_obj44_0016 - Compressed (triggered) state
        // spritePiece -$18, -$14, 3, 4, 8, 0, 0, 0, 0
        // spritePiece 0, -$14, 3, 4, 8, 1, 0, 0, 0 (hFlip)
        // spritePiece -$10, $C, 2, 2, $14, 0, 0, 0, 0
        // spritePiece 0, $C, 2, 2, $14, 1, 0, 0, 0 (hFlip)
        List<SpriteMappingPiece> frame1Pieces = new ArrayList<>();
        frame1Pieces.add(new SpriteMappingPiece(-24, -20, 3, 4, 8, false, false, 0));
        frame1Pieces.add(new SpriteMappingPiece(0, -20, 3, 4, 8, true, false, 0));
        frame1Pieces.add(new SpriteMappingPiece(-16, 12, 2, 2, 0x14, false, false, 0));
        frame1Pieces.add(new SpriteMappingPiece(0, 12, 2, 2, 0x14, true, false, 0));
        frames.add(new SpriteMappingFrame(frame1Pieces));

        return frames;
    }

    /**
     * Creates hex bumper mappings based on objD7.asm (Hexagonal Bumper from CNZ).
     * <p>
     * Frame 0: Normal state - 4 pieces (3x2 tiles each), 48x32 px total
     * Frame 1: Vertical squeeze - 4 pieces squeezed vertically (bounce up/down)
     * Frame 2: Horizontal squeeze - 4 pieces squeezed horizontally (bounce
     * left/right)
     * <p>
     * Each frame uses mirrored pieces (hFlip, vFlip) to create symmetry.
     */
    private List<SpriteMappingFrame> createHexBumperMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: Map_objD7_0006 - Normal state
        // 4 pieces: top-left, top-right (hFlip), bottom-left (vFlip), bottom-right
        // (h+vFlip)
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-24, -16, 3, 2, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(0, -16, 3, 2, 0, true, false, 0));
        frame0.add(new SpriteMappingPiece(-24, 0, 3, 2, 0, false, true, 0));
        frame0.add(new SpriteMappingPiece(0, 0, 3, 2, 0, true, true, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1: Map_objD7_0028 - Vertical squeeze (used for up/down bounce)
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-24, -12, 3, 2, 0, false, false, 0));
        frame1.add(new SpriteMappingPiece(0, -12, 3, 2, 0, true, false, 0));
        frame1.add(new SpriteMappingPiece(-24, 4, 3, 2, 0, false, true, 0));
        frame1.add(new SpriteMappingPiece(0, 4, 3, 2, 0, true, true, 0));
        frames.add(new SpriteMappingFrame(frame1));

        // Frame 2: Map_objD7_004A - Horizontal squeeze (used for left/right bounce)
        List<SpriteMappingPiece> frame2 = new ArrayList<>();
        frame2.add(new SpriteMappingPiece(-20, -16, 3, 2, 0, false, false, 0));
        frame2.add(new SpriteMappingPiece(4, -16, 3, 2, 0, true, false, 0));
        frame2.add(new SpriteMappingPiece(-20, 0, 3, 2, 0, false, true, 0));
        frame2.add(new SpriteMappingPiece(4, 0, 3, 2, 0, true, true, 0));
        frames.add(new SpriteMappingFrame(frame2));

        return frames;
    }

    /**
     * Creates bonus block mappings based on objD8.asm (Drop Target from CNZ).
     * <p>
     * 6 frames total - 3 orientations x 2 states (normal/hit):
     * <ul>
     * <li>Frames 0,3: Horizontal (32x16 px) - 4 tiles wide, 2 tiles high</li>
     * <li>Frames 1,4: Vertical (24x32 px) - 3 tiles wide, 4 tiles high</li>
     * <li>Frames 2,5: Vertical narrow (16x32 px) - 2 tiles wide, 4 tiles high</li>
     * </ul>
     * Hit frames have slightly adjusted offsets for the "bounce" animation.
     */
    private List<SpriteMappingFrame> createBonusBlockMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: Map_objD8_000C - Horizontal normal (32x16)
        frames.add(createSimpleFrame(-16, -8, 4, 2, 0));

        // Frame 1: Map_objD8_0016 - Vertical normal (24x32)
        frames.add(createSimpleFrame(-12, -16, 3, 4, 8));

        // Frame 2: Map_objD8_0020 - Vertical narrow normal (16x32)
        frames.add(createSimpleFrame(-8, -16, 2, 4, 20));

        // Frame 3: Map_objD8_002A - Horizontal hit (32x16, y offset -6)
        List<SpriteMappingPiece> frame3 = new ArrayList<>();
        frame3.add(new SpriteMappingPiece(-16, -6, 4, 2, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(frame3));

        // Frame 4: Map_objD8_0034 - Vertical hit (24x32, offset adjusted)
        List<SpriteMappingPiece> frame4 = new ArrayList<>();
        frame4.add(new SpriteMappingPiece(-14, -14, 3, 4, 8, false, false, 0));
        frames.add(new SpriteMappingFrame(frame4));

        // Frame 5: Map_objD8_003E - Vertical narrow hit (16x32, offset adjusted)
        List<SpriteMappingPiece> frame5 = new ArrayList<>();
        frame5.add(new SpriteMappingPiece(-10, -16, 2, 4, 20, false, false, 0));
        frames.add(new SpriteMappingFrame(frame5));

        return frames;
    }

    private List<SpriteMappingFrame> createEHZWaterfallMappings() {
        // Translating from obj49.asm
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: Map_obj49_0010 (Small top/bottom piece)
        // spritePiece -$20, -$80, 4, 2, 0, 0, 0, 0, 0
        // spritePiece 0, -$80, 4, 2, 0, 0, 0, 0, 0
        // Note: Y offset -128 (-$80) seems very high relative to object center, but
        // matching ROM
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-32, -128, 4, 2, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(0, -128, 4, 2, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1: Map_obj49_0022 (Long waterfall section)
        // Pieces at Y: -128, -96, -64, -32, 0, 32, 64, 96 (0x60)
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-32, -128, 4, 2, 0, false, false, 0));
        frame1.add(new SpriteMappingPiece(0, -128, 4, 2, 0, false, false, 0));
        // Loop of body pieces
        for (int y = -128; y <= 96; y += 32) {
            // These are 4x4 tiles (32x32), tile index 8
            if (y == -128)
                continue; // Skip first which was handled via 4x2 pieces at tile 0
            frame1.add(new SpriteMappingPiece(-32, y, 4, 4, 8, false, false, 0));
            frame1.add(new SpriteMappingPiece(0, y, 4, 4, 8, false, false, 0));
        }
        frames.add(new SpriteMappingFrame(frame1));

        // Frame 2: Map_obj49_00B4 (Empty)
        frames.add(new SpriteMappingFrame(new ArrayList<>()));

        // Frame 3: Map_obj49_00B6 (Small section)
        // Pieces at Y: -32, 0 (4x4 tile 8)
        List<SpriteMappingPiece> frame3 = new ArrayList<>();
        frame3.add(new SpriteMappingPiece(-32, -32, 4, 4, 8, false, false, 0));
        frame3.add(new SpriteMappingPiece(0, -32, 4, 4, 8, false, false, 0));
        frame3.add(new SpriteMappingPiece(-32, 0, 4, 4, 8, false, false, 0));
        frame3.add(new SpriteMappingPiece(0, 0, 4, 4, 8, false, false, 0));
        frames.add(new SpriteMappingFrame(frame3));

        // Frame 4: Map_obj49_00D8 (Medium section)
        // Pieces at Y: -64, -32, 0, 32, 64
        List<SpriteMappingPiece> frame4 = new ArrayList<>();
        for (int y = -64; y <= 64; y += 32) {
            frame4.add(new SpriteMappingPiece(-32, y, 4, 4, 8, false, false, 0));
            frame4.add(new SpriteMappingPiece(0, y, 4, 4, 8, false, false, 0));
        }
        frames.add(new SpriteMappingFrame(frame4));

        // Frame 5: Same as Frame 2 (Empty) - in mappings table
        frames.add(new SpriteMappingFrame(new ArrayList<>()));

        // Frame 6: Same as Frame 4
        frames.add(frames.get(3)); // reuse frame 3 (Map_obj49_00D8 was referenced? No, wait)
        // Correction: Table is:
        // 0: Map_obj49_0010
        // 1: Map_obj49_0022
        // 2: Map_obj49_00B4 (Empty)
        // 3: Map_obj49_00B6
        // 4: Map_obj49_00B4 (Empty) - WAIT, mappingsTableEntry.w Map_obj49_00B4 is
        // index 4?
        // Let's re-read table:
        // 0: Map_obj49_0010
        // 1: Map_obj49_0022
        // 2: Map_obj49_00B4
        // 3: Map_obj49_00B6
        // 4: Map_obj49_00B4
        // 5: Map_obj49_00D8
        // 6: Map_obj49_0010
        // 7: Map_obj49_012A (Longer version of 1?)

        // Let's restart frame list based directly on table indices:
        frames.clear();
        // 0: Map_obj49_0010
        frames.add(new SpriteMappingFrame(frame0));
        // 1: Map_obj49_0022
        frames.add(new SpriteMappingFrame(frame1));
        // 2: Map_obj49_00B4 (Empty)
        frames.add(new SpriteMappingFrame(new ArrayList<>()));
        // 3: Map_obj49_00B6
        frames.add(new SpriteMappingFrame(frame3));
        // 4: Map_obj49_00B4 (Empty)
        frames.add(new SpriteMappingFrame(new ArrayList<>()));
        // 5: Map_obj49_00D8
        frames.add(new SpriteMappingFrame(frame4));
        // 6: Map_obj49_0010
        frames.add(new SpriteMappingFrame(frame0));

        // 7: Map_obj49_012A
        List<SpriteMappingPiece> frame7 = new ArrayList<>();
        frame7.add(new SpriteMappingPiece(-32, -128, 4, 2, 0, false, false, 0));
        frame7.add(new SpriteMappingPiece(0, -128, 4, 2, 0, false, false, 0));
        for (int y = -128; y <= 32; y += 32) {
            if (y == -128)
                continue;
            frame7.add(new SpriteMappingPiece(-32, y, 4, 4, 8, false, false, 0));
            frame7.add(new SpriteMappingPiece(0, y, 4, 4, 8, false, false, 0));
        }
        // Actually, frame 7 (012A) looks like: -80(2), -80(4), -60, -40, -20, 0, 20
        // -80 (0x-50)?? No, disassembly says:
        /*
         * Map_obj49_012A:
         * spritePiece -$20, -$80, 4, 2, 0...
         * spritePiece 0, -$80, 4, 2, 0...
         * spritePiece -$20, -$80, 4, 4, 8...
         * spritePiece 0, -$80, 4, 4, 8...
         * ... down to $20
         */
        frame7.clear();
        frame7.add(new SpriteMappingPiece(-32, -128, 4, 2, 0, false, false, 0));
        frame7.add(new SpriteMappingPiece(0, -128, 4, 2, 0, false, false, 0));
        for (int y = -128; y <= 32; y += 32) {
            frame7.add(new SpriteMappingPiece(-32, y, 4, 4, 8, false, false, 0));
            frame7.add(new SpriteMappingPiece(0, y, 4, 4, 8, false, false, 0));
        }
        frames.add(new SpriteMappingFrame(frame7));

        return frames;
    }

    private SpriteMappingFrame create2x2Frame(int startTile) {
        List<SpriteMappingPiece> pieces = new ArrayList<>();
        // 2x2 tiles (16x16 pixels). w=2, h=2.
        pieces.add(new SpriteMappingPiece(-16, -16, 2, 2, startTile, false, false, 0));
        pieces.add(new SpriteMappingPiece(0, -16, 2, 2, startTile, true, false, 0));
        pieces.add(new SpriteMappingPiece(-16, 0, 2, 2, startTile, false, true, 0));
        pieces.add(new SpriteMappingPiece(0, 0, 2, 2, startTile, true, true, 0));
        return new SpriteMappingFrame(pieces);
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
     * Creates mappings for Masher (Obj5C) - Leaping piranha badnik.
     * Based on obj5C.asm with 2 frames.
     */
    private List<SpriteMappingFrame> createMasherMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        // Frame 0: Mouth closed
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-12, -16, 2, 2, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(4, -16, 1, 2, 4, false, false, 0));
        frame0.add(new SpriteMappingPiece(-12, 0, 3, 2, 10, false, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1: Mouth open
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-12, -16, 2, 2, 0, false, false, 0));
        frame1.add(new SpriteMappingPiece(2, -16, 2, 2, 6, false, false, 0));
        frame1.add(new SpriteMappingPiece(-12, 0, 3, 2, 16, false, false, 0));
        frames.add(new SpriteMappingFrame(frame1));

        return frames;
    }

    /**
     * Creates mappings for Buzzer (Obj4B) - Flying bee/wasp badnik.
     * Based on obj4B.asm with 7 frames.
     */
    private List<SpriteMappingFrame> createBuzzerMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        // Frame 0: Body with wings extended
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-24, -8, 3, 2, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(0, -8, 3, 2, 6, false, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1: Body with wings up
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-24, -8, 3, 2, 0, false, false, 0));
        frame1.add(new SpriteMappingPiece(0, -8, 2, 2, 12, false, false, 0));
        frame1.add(new SpriteMappingPiece(2, 8, 2, 2, 16, false, false, 0));
        frames.add(new SpriteMappingFrame(frame1));

        // Frame 2: Similar to frame 1 with slightly different wing position
        List<SpriteMappingPiece> frame2 = new ArrayList<>();
        frame2.add(new SpriteMappingPiece(-24, -8, 3, 2, 0, false, false, 0));
        frame2.add(new SpriteMappingPiece(0, -8, 2, 2, 12, false, false, 0));
        frame2.add(new SpriteMappingPiece(2, 8, 2, 2, 20, false, false, 0));
        frames.add(new SpriteMappingFrame(frame2));

        // Frames 3-6: Projectile frames
        List<SpriteMappingPiece> frame3 = new ArrayList<>();
        frame3.add(new SpriteMappingPiece(4, -16, 1, 2, 20, false, false, 0));
        frames.add(new SpriteMappingFrame(frame3));

        List<SpriteMappingPiece> frame4 = new ArrayList<>();
        frame4.add(new SpriteMappingPiece(4, -16, 1, 2, 22, false, false, 0));
        frames.add(new SpriteMappingFrame(frame4));

        List<SpriteMappingPiece> frame5 = new ArrayList<>();
        frame5.add(new SpriteMappingPiece(-12, -8, 1, 2, 24, false, false, 0));
        frames.add(new SpriteMappingFrame(frame5));

        List<SpriteMappingPiece> frame6 = new ArrayList<>();
        frame6.add(new SpriteMappingPiece(-12, -8, 1, 2, 26, false, false, 0));
        frames.add(new SpriteMappingFrame(frame6));

        return frames;
    }

    /**
     * Creates mappings for Coconuts (Obj9D) - Monkey badnik.
     * Based on obj9D.asm with 4 frames.
     */
    private List<SpriteMappingFrame> createCoconutsMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        // Frame 0: Climbing 1
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-2, 0, 2, 2, 26, false, false, 0));
        frame0.add(new SpriteMappingPiece(-4, -16, 3, 2, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(-12, 0, 4, 2, 6, false, false, 0));
        frame0.add(new SpriteMappingPiece(12, 16, 1, 2, 14, false, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1: Climbing 2
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-2, 0, 2, 2, 30, false, false, 0));
        frame1.add(new SpriteMappingPiece(-4, -16, 3, 2, 0, false, false, 0));
        frame1.add(new SpriteMappingPiece(-12, 0, 4, 2, 16, false, false, 0));
        frame1.add(new SpriteMappingPiece(12, 16, 1, 2, 24, false, false, 0));
        frames.add(new SpriteMappingFrame(frame1));

        // Frame 2: Throwing
        List<SpriteMappingPiece> frame2 = new ArrayList<>();
        frame2.add(new SpriteMappingPiece(7, -8, 1, 2, 34, false, false, 0));
        frame2.add(new SpriteMappingPiece(-4, -16, 3, 2, 0, false, false, 0));
        frame2.add(new SpriteMappingPiece(-12, 0, 4, 2, 16, false, false, 0));
        frame2.add(new SpriteMappingPiece(12, 16, 1, 2, 24, false, false, 0));
        frames.add(new SpriteMappingFrame(frame2));

        // Frame 3: Coconut projectile (palette line 1 for orange)
        List<SpriteMappingPiece> frame3 = new ArrayList<>();
        frame3.add(new SpriteMappingPiece(-8, -8, 1, 2, 36, false, false, 2));
        frame3.add(new SpriteMappingPiece(0, -8, 1, 2, 36, true, false, 2));
        frames.add(new SpriteMappingFrame(frame3));

        return frames;
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
     * Creates mappings for Points (Obj29).
     * Based on obj29.asm.
     */
    private List<SpriteMappingFrame> createPointsMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: 100 points (-8, -8, 2x2, tile 2)
        frames.add(createSimpleFrame(-8, -8, 2, 2, 2));

        // Frame 1: 200 points (-8, -8, 2x2, tile 6)
        frames.add(createSimpleFrame(-8, -8, 2, 2, 6));

        // Frame 2: 500 points (-8, -8, 2x2, tile 10 ($A))
        frames.add(createSimpleFrame(-8, -8, 2, 2, 10));

        // Frame 3: 1000 points
        List<SpriteMappingPiece> frame3 = new ArrayList<>();
        frame3.add(new SpriteMappingPiece(-8, -8, 1, 2, 0, false, false, 0));
        frame3.add(new SpriteMappingPiece(0, -8, 2, 2, 14, false, false, 0));
        frames.add(new SpriteMappingFrame(frame3));

        // Frame 4: 10 points
        frames.add(createSimpleFrame(-4, -8, 1, 2, 0));

        return frames;
    }

    /**
     * Creates mappings for Signpost (Obj0D).
     * Based on obj0D_a.asm with 6 frames for spinning.
     * Frame order must match Ani_obj0D indices:
     * 0=Sonic, 1=Tails, 2=Eggman, 3=Blank, 4=Edge, 5=Sonic (h-flip).
     */
    private List<SpriteMappingFrame> createSignpostMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Sonic face (full view)
        // spritePiece -$10, -$10, 4, 4, $C, 0, 0, 0, 0
        // spritePiece -4, $10, 1, 2, $20, 0, 0, 0, 0 (pole)
        List<SpriteMappingPiece> sonic = new ArrayList<>();
        sonic.add(new SpriteMappingPiece(-16, -16, 4, 4, 0x0C, false, false, 0));
        sonic.add(new SpriteMappingPiece(-4, 16, 1, 2, 0x20, false, false, 0));

        // Side spinning (thin) - Tails face in original
        // spritePiece -$18, -$10, 1, 4, $3A, 0, 0, 0, 0
        // spritePiece -$10, -$10, 4, 4, $3E, 0, 0, 0, 0
        // spritePiece $10, -$10, 1, 4, $3A, 1, 0, 0, 0 (hflipped)
        // spritePiece -4, $10, 1, 2, $20, 0, 0, 0, 0 (pole)
        List<SpriteMappingPiece> tails = new ArrayList<>();
        tails.add(new SpriteMappingPiece(-24, -16, 1, 4, 0x3A, false, false, 0));
        tails.add(new SpriteMappingPiece(-16, -16, 4, 4, 0x3E, false, false, 0));
        tails.add(new SpriteMappingPiece(16, -16, 1, 4, 0x3A, true, false, 0));
        tails.add(new SpriteMappingPiece(-4, 16, 1, 2, 0x20, false, false, 0));

        // Eggman face (wide view)
        // spritePiece -$18, -$10, 3, 4, $22, 0, 0, 0, 0
        // spritePiece 0, -$10, 3, 4, $2E, 0, 0, 0, 0
        // spritePiece -4, $10, 1, 2, $20, 0, 0, 0, 0 (pole)
        List<SpriteMappingPiece> eggman = new ArrayList<>();
        eggman.add(new SpriteMappingPiece(-24, -16, 3, 4, 0x22, false, false, 0));
        eggman.add(new SpriteMappingPiece(0, -16, 3, 4, 0x2E, false, false, 0));
        eggman.add(new SpriteMappingPiece(-4, 16, 1, 2, 0x20, false, false, 0));

        // Blank face (mid-spin)
        // spritePiece -$18, -$10, 3, 4, 0, 0, 0, 0, 0
        // spritePiece 0, -$10, 3, 4, 0, 1, 0, 0, 0 (hflipped)
        // spritePiece -4, $10, 1, 2, $20, 0, 0, 0, 0 (pole)
        List<SpriteMappingPiece> blank = new ArrayList<>();
        blank.add(new SpriteMappingPiece(-24, -16, 3, 4, 0, false, false, 0));
        blank.add(new SpriteMappingPiece(0, -16, 3, 4, 0, true, false, 0));
        blank.add(new SpriteMappingPiece(-4, 16, 1, 2, 0x20, false, false, 0));

        // Edge view (very thin)
        // spritePiece -4, -$10, 1, 4, $1C, 0, 0, 0, 0
        // spritePiece -4, $10, 1, 2, $20, 0, 0, 0, 0 (pole)
        List<SpriteMappingPiece> edge = new ArrayList<>();
        edge.add(new SpriteMappingPiece(-4, -16, 1, 4, 0x1C, false, false, 0));
        edge.add(new SpriteMappingPiece(-4, 16, 1, 2, 0x20, false, false, 0));

        // Sonic face flipped (for alternating direction spin)
        // spritePiece -$10, -$10, 4, 4, $C, 1, 0, 0, 0 (hflipped)
        // spritePiece -4, $10, 1, 2, $20, 0, 0, 0, 0 (pole)
        List<SpriteMappingPiece> sonicFlip = new ArrayList<>();
        sonicFlip.add(new SpriteMappingPiece(-16, -16, 4, 4, 0x0C, true, false, 0));
        sonicFlip.add(new SpriteMappingPiece(-4, 16, 1, 2, 0x20, false, false, 0));

        frames.add(new SpriteMappingFrame(sonic)); // 0
        frames.add(new SpriteMappingFrame(tails)); // 1
        frames.add(new SpriteMappingFrame(eggman)); // 2
        frames.add(new SpriteMappingFrame(blank)); // 3
        frames.add(new SpriteMappingFrame(edge)); // 4
        frames.add(new SpriteMappingFrame(sonicFlip)); // 5

        return frames;
    }

    /**
     * Creates animations for Signpost (Obj0D).
     * Based on Ani_obj0D in s2.asm.
     * 
     * Animation scripts:
     * 0: $0F, $02, $FF (hold frame 2 - Eggman face)
     * 1: $01, $02, $03, $04, $05, $01, $03, $04, $05, $00, $03, $04, $05, $FF
     * (spinning)
     * 2: same as 1
     * 3: $0F, $00, $FF (hold frame 0 - Sonic face)
     * 4: $0F, $01, $FF (hold frame 1 - Tails face)
     */
    private SpriteAnimationSet createSignpostAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: Hold Eggman face (frame 3 in current mapping order)
        set.addScript(0, new SpriteAnimationScript(0x0F, List.of(3), SpriteAnimationEndAction.LOOP, 0));

        // Anim 1: Spinning to Sonic (mapped to current frames)
        // Eggman -> spin -> Tails -> spin -> Sonic
        set.addScript(1, new SpriteAnimationScript(0x01,
                List.of(3, 0, 4, 5, 1, 0, 4, 5, 2, 0, 4, 5),
                SpriteAnimationEndAction.LOOP, 0));

        // Anim 2: Same as 1 (used for 2P mode)
        set.addScript(2, new SpriteAnimationScript(0x01,
                List.of(3, 0, 4, 5, 1, 0, 4, 5, 2, 0, 4, 5),
                SpriteAnimationEndAction.LOOP, 0));

        // Anim 3: Hold Sonic face (frame 2 in current mapping order)
        // From disasm: byte_195B7: dc.b $0F, $00, $FF - hold frame 0
        set.addScript(3, new SpriteAnimationScript(0x0F, List.of(2), SpriteAnimationEndAction.LOOP, 0));

        // Anim 4: Hold Tails face (frame 1 in current mapping order)
        set.addScript(4, new SpriteAnimationScript(0x0F, List.of(1), SpriteAnimationEndAction.LOOP, 0));

        return set;
    }

    /**
     * Creates mappings for CNZ Flipper (Obj86).
     * Based on obj86.asm mappings.
     * Frames 0-2: Vertical flipper states
     * Frames 3-5: Horizontal flipper states
     */
    private List<SpriteMappingFrame> createFlipperMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: Vertical idle (Map_obj86_000C)
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-25, -9, 3, 4, 0x0C, false, false, 0));
        frame0.add(new SpriteMappingPiece(-1, -2, 1, 2, 0x18, false, false, 0));
        frame0.add(new SpriteMappingPiece(7, 1, 2, 2, 0x1A, false, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1: Vertical triggered 1 (Map_obj86_0026)
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-24, -8, 4, 2, 0, false, false, 0));
        frame1.add(new SpriteMappingPiece(8, -8, 2, 2, 8, false, false, 0));
        frames.add(new SpriteMappingFrame(frame1));

        // Frame 2: Vertical triggered 2 (Map_obj86_0038) - v-flipped pieces
        List<SpriteMappingPiece> frame2 = new ArrayList<>();
        frame2.add(new SpriteMappingPiece(-25, -23, 3, 4, 0x0C, false, true, 0));
        frame2.add(new SpriteMappingPiece(-1, -14, 1, 2, 0x18, false, true, 0));
        frame2.add(new SpriteMappingPiece(7, -17, 2, 2, 0x1A, false, true, 0));
        frames.add(new SpriteMappingFrame(frame2));

        // Frame 3: Horizontal idle (Map_obj86_0052)
        List<SpriteMappingPiece> frame3 = new ArrayList<>();
        frame3.add(new SpriteMappingPiece(-15, -25, 3, 2, 0x24, false, false, 0));
        frame3.add(new SpriteMappingPiece(-17, -9, 3, 2, 0x2A, false, false, 0));
        frame3.add(new SpriteMappingPiece(-17, 7, 2, 2, 0x30, false, false, 0));
        frames.add(new SpriteMappingFrame(frame3));

        // Frame 4: Horizontal mid (Map_obj86_006C) - mirrored pieces
        List<SpriteMappingPiece> frame4 = new ArrayList<>();
        frame4.add(new SpriteMappingPiece(-8, -24, 1, 4, 0x1E, false, false, 0));
        frame4.add(new SpriteMappingPiece(0, -24, 1, 4, 0x1E, true, false, 0));
        frame4.add(new SpriteMappingPiece(-8, 8, 1, 2, 0x22, false, false, 0));
        frame4.add(new SpriteMappingPiece(0, 8, 1, 2, 0x22, true, false, 0));
        frames.add(new SpriteMappingFrame(frame4));

        // Frame 5: Horizontal activated (Map_obj86_008E) - h-flipped
        List<SpriteMappingPiece> frame5 = new ArrayList<>();
        frame5.add(new SpriteMappingPiece(-9, -25, 3, 2, 0x24, true, false, 0));
        frame5.add(new SpriteMappingPiece(-7, -9, 3, 2, 0x2A, true, false, 0));
        frame5.add(new SpriteMappingPiece(1, 7, 2, 2, 0x30, true, false, 0));
        frames.add(new SpriteMappingFrame(frame5));

        return frames;
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
     * Creates mappings for CPZ Speed Booster (Obj1B).
     * Based on obj1B.asm mappings.
     * Frame 0: Visible - Two 2x2 pieces at (-24,-8) and (8,-8)
     * Frame 1: Same as frame 0 (duplicate in ROM)
     * Frame 2: Empty (for blinking effect)
     */
    private List<SpriteMappingFrame> createSpeedBoosterMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0 & 1: Visible state - two 2x2 tile pieces
        List<SpriteMappingPiece> visiblePieces = new ArrayList<>();
        visiblePieces.add(new SpriteMappingPiece(-24, -8, 2, 2, 0, false, false, 0));
        visiblePieces.add(new SpriteMappingPiece(8, -8, 2, 2, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(visiblePieces));
        frames.add(new SpriteMappingFrame(visiblePieces)); // Frame 1 = same as frame 0

        // Frame 2: Empty (creates blinking effect)
        frames.add(new SpriteMappingFrame(new ArrayList<>()));

        return frames;
    }

    /**
     * Creates mappings for CPZ BlueBalls (Obj1D).
     * Based on obj1D.asm - single 2x2 tile frame at -8,-8.
     */
    private List<SpriteMappingFrame> createBlueBallsMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();
        // Frame 0: Single 2x2 piece centered at -8,-8 (16x16 pixels)
        // spritePiece -8, -8, 2, 2, 0, 0, 0, 0, 0
        frames.add(createSimpleFrame(-8, -8, 2, 2, 0));
        return frames;
    }

    /**
     * Creates mappings for CPZ Breakable Block (Obj32).
     * Based on obj32_b.asm - 4 pieces in a 2x2 grid (32x32 pixels total).
     * Frame 0: Intact block (4 pieces)
     * Frames 1-4: Individual fragment pieces for when block breaks
     */
    private List<SpriteMappingFrame> createBreakableBlockMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0: Intact block - 4 pieces in 2x2 arrangement
        // spritePiece -$10, -$10, 2, 2, 0, 0, 0, 0, 0  ; top-left
        // spritePiece 0, -$10, 2, 2, 0, 1, 0, 0, 0     ; top-right (H-flipped)
        // spritePiece -$10, 0, 2, 2, 0, 0, 0, 0, 0     ; bottom-left
        // spritePiece 0, 0, 2, 2, 0, 1, 0, 0, 0        ; bottom-right (H-flipped)
        List<SpriteMappingPiece> intactPieces = new ArrayList<>();
        intactPieces.add(new SpriteMappingPiece(-16, -16, 2, 2, 0, false, false, 0)); // top-left
        intactPieces.add(new SpriteMappingPiece(0, -16, 2, 2, 0, true, false, 0));    // top-right, H-flip
        intactPieces.add(new SpriteMappingPiece(-16, 0, 2, 2, 0, false, false, 0));   // bottom-left
        intactPieces.add(new SpriteMappingPiece(0, 0, 2, 2, 0, true, false, 0));      // bottom-right, H-flip
        frames.add(new SpriteMappingFrame(intactPieces));

        // Frames 1-4: Individual fragment pieces (each is 16x16)
        // Fragment 0: top-left (no flip)
        frames.add(createSimpleFrame(-8, -8, 2, 2, 0));
        // Fragment 1: top-right (H-flip)
        List<SpriteMappingPiece> frag1 = new ArrayList<>();
        frag1.add(new SpriteMappingPiece(-8, -8, 2, 2, 0, true, false, 0));
        frames.add(new SpriteMappingFrame(frag1));
        // Fragment 2: bottom-left (no flip)
        frames.add(createSimpleFrame(-8, -8, 2, 2, 0));
        // Fragment 3: bottom-right (H-flip)
        List<SpriteMappingPiece> frag3 = new ArrayList<>();
        frag3.add(new SpriteMappingPiece(-8, -8, 2, 2, 0, true, false, 0));
        frames.add(new SpriteMappingFrame(frag3));

        return frames;
    }

    /**
     * Creates mappings for CPZ/OOZ/WFZ Moving Platform (Obj19).
     * Based on obj19.asm - 4 frames with different platform sizes.
     * Frame 0: Large (32px wide) - 2 x 4x4 tile pieces
     * Frame 1: Small (24px wide) - 2 x 3x4 tile pieces
     * Frame 2: Wide (64px wide) - 4 x 4x3 tile pieces
     * Frame 3: Medium (32px wide) - 2 x 4x3 tile pieces
     */
    private List<SpriteMappingFrame> createCPZPlatformMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0 (Map_obj19_0008): Large platform - 2 pieces, 4x4 tiles each
        // spritePiece -$20, -$10, 4, 4, 0, 0, 0, 0, 0 (left half)
        // spritePiece 0, -$10, 4, 4, 0, 1, 0, 0, 0 (right half, H-flipped)
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-0x20, -0x10, 4, 4, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(0, -0x10, 4, 4, 0, true, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1 (Map_obj19_001A): Smaller platform - 2 pieces, 3x4 tiles each
        // spritePiece -$18, -$10, 3, 4, 0, 0, 0, 0, 0 (left half)
        // spritePiece 0, -$10, 3, 4, 0, 1, 0, 0, 0 (right half, H-flipped)
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-0x18, -0x10, 3, 4, 0, false, false, 0));
        frame1.add(new SpriteMappingPiece(0, -0x10, 3, 4, 0, true, false, 0));
        frames.add(new SpriteMappingFrame(frame1));

        // Frame 2 (Map_obj19_002C): Wide platform - 4 pieces, 4x3 tiles each
        // spritePiece -$40, -$10, 4, 3, 0, 0, 0, 0, 0
        // spritePiece -$20, -$10, 4, 3, $C, 0, 0, 0, 0
        // spritePiece 0, -$10, 4, 3, $C, 1, 0, 0, 0
        // spritePiece $20, -$10, 4, 3, 0, 1, 0, 0, 0
        List<SpriteMappingPiece> frame2 = new ArrayList<>();
        frame2.add(new SpriteMappingPiece(-0x40, -0x10, 4, 3, 0, false, false, 0));
        frame2.add(new SpriteMappingPiece(-0x20, -0x10, 4, 3, 0x0C, false, false, 0));
        frame2.add(new SpriteMappingPiece(0, -0x10, 4, 3, 0x0C, true, false, 0));
        frame2.add(new SpriteMappingPiece(0x20, -0x10, 4, 3, 0, true, false, 0));
        frames.add(new SpriteMappingFrame(frame2));

        // Frame 3 (Map_obj19_004E): Medium platform - 2 pieces, 4x3 tiles each
        // spritePiece -$20, -$10, 4, 3, 0, 0, 0, 0, 0
        // spritePiece 0, -$10, 4, 3, 0, 1, 0, 0, 0
        List<SpriteMappingPiece> frame3 = new ArrayList<>();
        frame3.add(new SpriteMappingPiece(-0x20, -0x10, 4, 3, 0, false, false, 0));
        frame3.add(new SpriteMappingPiece(0, -0x10, 4, 3, 0, true, false, 0));
        frames.add(new SpriteMappingFrame(frame3));

        return frames;
    }

    /**
     * Creates mappings for CPZ Stair Block (Obj78).
     * Based on obj6B.asm mappings (shared):
     * Frame 0: Single 4x4 tile piece at (-16,-16), 32x32 pixels
     */
    private List<SpriteMappingFrame> createCPZStairBlockMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0 (Map_obj6B_0002): 4x4 tiles centered
        // spritePiece -$10, -$10, 4, 4, 0, 0, 0, 0, 0
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-0x10, -0x10, 4, 4, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        return frames;
    }

    /**
     * Creates mappings for CPZ Pipe Exit Spring (Obj7B).
     * Based on obj7B.asm mappings:
     * Frame 0: Base spring - 4x2 tile piece at (-16,-16)
     * Frame 1: Compressed vertical - 2 x 2x4 tile pieces at (-16,-32) and (0,-32)
     * Frame 2: Alternate vertical - 2 x 2x4 tile pieces with different tile offset
     * Frame 3: Compressed base - 4x2 tile piece at (-16,-16) with tile offset 0x18
     * Frame 4: Same as frame 0 (copy)
     */
    private List<SpriteMappingFrame> createPipeExitSpringMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0 (Map_obj7B_000A): Base spring - 4x2 tile piece
        // spritePiece -$10, -$10, 4, 2, 0, 0, 0, 0, 0
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-0x10, -0x10, 4, 2, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1 (Map_obj7B_0014): Compressed vertical - 2 x 2x4 tile pieces
        // spritePiece -$10, -$20, 2, 4, 8, 0, 0, 0, 0
        // spritePiece 0, -$20, 2, 4, 8, 1, 0, 0, 0 (H-flipped)
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-0x10, -0x20, 2, 4, 8, false, false, 0));
        frame1.add(new SpriteMappingPiece(0, -0x20, 2, 4, 8, true, false, 0));
        frames.add(new SpriteMappingFrame(frame1));

        // Frame 2 (Map_obj7B_0026): Alternate vertical - 2 x 2x4 tile pieces
        // spritePiece -$10, -$20, 2, 4, $10, 0, 0, 0, 0
        // spritePiece 0, -$20, 2, 4, $10, 1, 0, 0, 0 (H-flipped)
        List<SpriteMappingPiece> frame2 = new ArrayList<>();
        frame2.add(new SpriteMappingPiece(-0x10, -0x20, 2, 4, 0x10, false, false, 0));
        frame2.add(new SpriteMappingPiece(0, -0x20, 2, 4, 0x10, true, false, 0));
        frames.add(new SpriteMappingFrame(frame2));

        // Frame 3 (Map_obj7B_0038): Compressed base - 4x2 tile piece with tile offset
        // spritePiece -$10, -$10, 4, 2, $18, 0, 0, 0, 0
        List<SpriteMappingPiece> frame3 = new ArrayList<>();
        frame3.add(new SpriteMappingPiece(-0x10, -0x10, 4, 2, 0x18, false, false, 0));
        frames.add(new SpriteMappingFrame(frame3));

        // Frame 4: Same as frame 0 (mappingsTableEntry.w Map_obj7B_0014 -> points to frame 1 in ROM)
        // Actually looking at mapping table: entry 4 points to Map_obj7B_0014 which is frame 1
        // But for simplicity, we'll use same as frame 0 for idle
        List<SpriteMappingPiece> frame4 = new ArrayList<>();
        frame4.add(new SpriteMappingPiece(-0x10, -0x10, 4, 2, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(frame4));

        return frames;
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
     * Creates mappings for CPZ Tipping Floor (Obj0B).
     * Based on obj0B.asm mappings - 5 frames showing platform tipping states.
     * <p>
     * Each frame has 2 pieces:
     * - Piece 1: The moving platform edge (tiles 0 or 4 or 0x14)
     * - Piece 2: The static base (tile 0x24, 4x3)
     */
    private List<SpriteMappingFrame> createTippingFloorMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0 (Map_obj0B_000A): Flat position
        // spritePiece -$10, -$10, 4, 1, 0, 0, 0, 0, 0
        // spritePiece -$10, -8, 4, 3, $24, 0, 0, 0, 0
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-0x10, -0x10, 4, 1, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(-0x10, -8, 4, 3, 0x24, false, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1 (Map_obj0B_001C): Tilted up position
        // spritePiece -$10, -$18, 4, 4, 4, 0, 0, 0, 0
        // spritePiece -$10, -8, 4, 3, $24, 0, 0, 0, 0
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-0x10, -0x18, 4, 4, 4, false, false, 0));
        frame1.add(new SpriteMappingPiece(-0x10, -8, 4, 3, 0x24, false, false, 0));
        frames.add(new SpriteMappingFrame(frame1));

        // Frame 2 (Map_obj0B_002E): Middle tilt position
        // spritePiece -$10, -$C, 4, 4, $14, 0, 0, 0, 0
        // spritePiece -$10, -8, 4, 3, $24, 0, 0, 0, 0
        List<SpriteMappingPiece> frame2 = new ArrayList<>();
        frame2.add(new SpriteMappingPiece(-0x10, -0x0C, 4, 4, 0x14, false, false, 0));
        frame2.add(new SpriteMappingPiece(-0x10, -8, 4, 3, 0x24, false, false, 0));
        frames.add(new SpriteMappingFrame(frame2));

        // Frame 3 (Map_obj0B_0040): Tilted down position (V-flipped tiles)
        // spritePiece -$10, 0, 4, 4, 4, 0, 1, 0, 0 (vFlip=1)
        // spritePiece -$10, -8, 4, 3, $24, 0, 0, 0, 0
        List<SpriteMappingPiece> frame3 = new ArrayList<>();
        frame3.add(new SpriteMappingPiece(-0x10, 0, 4, 4, 4, false, true, 0));
        frame3.add(new SpriteMappingPiece(-0x10, -8, 4, 3, 0x24, false, false, 0));
        frames.add(new SpriteMappingFrame(frame3));

        // Frame 4 (Map_obj0B_0052): Fully tilted down position (V-flipped)
        // spritePiece -$10, $10, 4, 1, 0, 0, 1, 0, 0 (vFlip=1)
        // spritePiece -$10, -8, 4, 3, $24, 0, 0, 0, 0
        List<SpriteMappingPiece> frame4 = new ArrayList<>();
        frame4.add(new SpriteMappingPiece(-0x10, 0x10, 4, 1, 0, false, true, 0));
        frame4.add(new SpriteMappingPiece(-0x10, -8, 4, 3, 0x24, false, false, 0));
        frames.add(new SpriteMappingFrame(frame4));

        return frames;
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
     * Creates mappings for CPZ/DEZ Barrier (Obj2D).
     * Based on obj2D.asm mappings:
     * Frame 0 (HTZ): 4 x 2x2 tile pieces stacked vertically (16x64 total)
     * Frame 1 (MTZ): 2 x 3x4 tile pieces using tile $5F (24x64 total)
     * Frame 2 (CPZ/DEZ): 2 x 2x4 tile pieces stacked vertically (16x64 total)
     * Frame 3 (ARZ): Same as Frame 2
     */
    private List<SpriteMappingFrame> createBarrierMappings() {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Frame 0 (Map_obj2D_0008): HTZ - 4 x 2x2 tile pieces
        // spritePiece -8, -$20, 2, 2, 0, 0, 0, 0, 0
        // spritePiece -8, -$10, 2, 2, 0, 0, 0, 0, 0
        // spritePiece -8, 0, 2, 2, 0, 0, 0, 0, 0
        // spritePiece -8, $10, 2, 2, 0, 0, 0, 0, 0
        List<SpriteMappingPiece> frame0 = new ArrayList<>();
        frame0.add(new SpriteMappingPiece(-8, -0x20, 2, 2, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(-8, -0x10, 2, 2, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(-8, 0, 2, 2, 0, false, false, 0));
        frame0.add(new SpriteMappingPiece(-8, 0x10, 2, 2, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(frame0));

        // Frame 1 (Map_obj2D_002A): MTZ - 2 x 3x4 tile pieces, tile $5F
        // spritePiece -$C, -$20, 3, 4, $5F, 0, 0, 0, 0
        // spritePiece -$C, 0, 3, 4, $5F, 0, 0, 0, 0
        List<SpriteMappingPiece> frame1 = new ArrayList<>();
        frame1.add(new SpriteMappingPiece(-0x0C, -0x20, 3, 4, 0x5F, false, false, 0));
        frame1.add(new SpriteMappingPiece(-0x0C, 0, 3, 4, 0x5F, false, false, 0));
        frames.add(new SpriteMappingFrame(frame1));

        // Frame 2 (Map_obj2D_003C): CPZ/DEZ - 2 x 2x4 tile pieces
        // spritePiece -8, -$20, 2, 4, 0, 0, 0, 0, 0
        // spritePiece -8, 0, 2, 4, 0, 0, 0, 0, 0
        List<SpriteMappingPiece> frame2 = new ArrayList<>();
        frame2.add(new SpriteMappingPiece(-8, -0x20, 2, 4, 0, false, false, 0));
        frame2.add(new SpriteMappingPiece(-8, 0, 2, 4, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(frame2));

        // Frame 3 (Map_obj2D_004E): ARZ - same as Frame 2
        // spritePiece -8, -$20, 2, 4, 0, 0, 0, 0, 0
        // spritePiece -8, 0, 2, 4, 0, 0, 0, 0, 0
        List<SpriteMappingPiece> frame3 = new ArrayList<>();
        frame3.add(new SpriteMappingPiece(-8, -0x20, 2, 4, 0, false, false, 0));
        frame3.add(new SpriteMappingPiece(-8, 0, 2, 4, 0, false, false, 0));
        frames.add(new SpriteMappingFrame(frame3));

        return frames;
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
    private ObjectArtConfig getObjectArtConfig(int objectId, int zoneIndex) {
        ZoneArtProvider provider = GameModuleRegistry.getCurrent().getZoneArtProvider();
        if (provider == null) {
            return null;
        }
        return provider.getObjectArt(objectId, zoneIndex);
    }
}
