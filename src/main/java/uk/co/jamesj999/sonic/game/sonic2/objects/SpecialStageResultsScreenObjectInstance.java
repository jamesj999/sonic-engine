package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.game.GameStateManager;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageDataLoader;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageManager;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Palette;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.PatternDesc;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.tools.NemesisReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Special Stage Results Screen.
 * <p>
 * Displays after completing or failing a special stage, showing:
 * - "SPECIAL STAGE" title
 * - "Sonic got a Chaos Emerald" (if emerald collected)
 * - "Sonic has all the Chaos Emeralds" (if all 7 collected)
 * - Collected emeralds (flashing)
 * - Ring bonus tally
 * - Emerald bonus (if applicable)
 * - Super Sonic message sequence (if all 7 emeralds collected)
 * <p>
 * Based on Obj6F from s2.asm.
 */
public class SpecialStageResultsScreenObjectInstance extends AbstractResultsScreen {
    private static final Logger LOGGER = Logger.getLogger(SpecialStageResultsScreenObjectInstance.class.getName());

    // Y positions for text elements (screen coordinates, Y increases downward)
    // From Obj6F_SubObjectMetaData in s2.asm
    private static final int TITLE_Y = 42;           // "SPECIAL STAGE" / "CHAOS EMERALD"
    private static final int GOT_TEXT_Y = 24;        // "SONIC GOT A" / "SONIC HAS ALL THE" (above TITLE_Y!)
    private static final int EMERALD_TEXT_Y = 42;    // Same as TITLE_Y (replaces it)
    private static final int EMERALDS_Y = 92;        // Emerald icons (center is ~92 based on emerald positions)

    // Emerald positions from Obj6F_SubObjectMetaData in s2.asm
    // Each emerald has unique X,Y coordinates forming a hexagonal pattern
    // Emeralds are displayed at their fixed index positions (gaps shown for uncollected)
    private static final int[][] EMERALD_POSITIONS = {
        {152, 68},   // Emerald 0: top center
        {176, 80},   // Emerald 1: top right
        {176, 104},  // Emerald 2: bottom right
        {152, 116},  // Emerald 3: bottom center
        {128, 104},  // Emerald 4: bottom left
        {128, 80},   // Emerald 5: top left
        {152, 92}    // Emerald 6: center
    };
    // Bonus tally Y positions - from Obj6F_SubObjectMetaData in s2.asm
    // Original game positions (2-player mode):
    // - Y=136: Score line (Frame 12)
    // - Y=152: Sonic Rings line (Frame 13)
    // - Y=168: Miles Rings line (Frame 14) - deleted in single-player
    // - Y=184: Gems Bonus line (Frame 16)
    // For single-player, we move GEMS BONUS up to Y=168 for consistent 16px spacing
    private static final int SCORE_LINE_Y = 136;
    private static final int SONIC_RINGS_Y = 152;
    private static final int GEMS_BONUS_Y = 168;  // Moved up from 184 for consistent spacing in single-player

    // Super Sonic message positions (from Obj6F_InitAndMoveSuperMsg in s2.asm)
    // The three messages stack vertically at the top of the screen,
    // replacing the "SONIC HAS ALL THE CHAOS EMERALDS" text.
    //
    // Analysis from disassembly:
    // - Original "Sonic got a" at Y=24, "Special Stage" at Y=42 (metadata uses 128+y internally)
    // - Obj6F_InitAndMoveSuperMsg subtracts 8 from both: 24-8=16, 42-8=34
    // - "SUPER SONIC" is created with y_pixel=$B4=180, which is 128+52, so screen Y=52
    // - Result: 18-pixel spacing between all three lines at top of screen
    private static final int NOW_SONIC_CAN_Y = 16;
    private static final int CHANGE_INTO_Y = 34;
    private static final int SUPER_SONIC_Y = 52;

    // States for Super Sonic message sequence
    // All three messages display simultaneously, then wait before ending
    private static final int STATE_SUPER_SONIC_DISPLAY = 10;  // Show all 3 messages
    private static final int STATE_SUPER_DONE = 11;

    private static final int SUPER_MSG_DURATION = 180;  // $B4 = 180 frames (~3 seconds) for all messages

    // Pattern array layout - UNIFIED VRAM-aligned array:
    // We create a pattern array indexed by VRAM address, just like the normal results screen.
    // This allows the mapping pieces to reference tiles by their original VRAM address.
    //
    // VRAM regions used by special stage results (from obj6F.asm):
    // - $0002-$003F: SS letters (A,C,D,G,H,I,L,M,P,R,S,T,U,W) - from TitleCard2
    // - $0520-$053F: Numbers - from ArtUnc_HUDNumbers
    // - $0580-$058F: Title card E,N,O,Z - from ArtNem_TitleCard
    // - $0590-$06C9: Results art - from ArtNem_SpecialStageResults
    // - $06CA-$0700: HUD text - from ArtNem_HUD
    //
    // We use VRAM base $02 as our array base, so:
    // - Pattern array index = VRAM address - $02
    private static final int VRAM_BASE = 0x0002;       // Lowest VRAM address used
    private static final int VRAM_SS_LETTERS = 0x0002;
    private static final int VRAM_NUMBERS = 0x0520;
    private static final int VRAM_TITLE_CARD = 0x0580;
    private static final int VRAM_RESULTS_ART = 0x0590;
    private static final int VRAM_HUD = 0x06CA;
    private static final int VRAM_END = 0x0710;        // End of HUD region (generous)

    private static final int PATTERN_BASE = 0x30000;   // High ID to avoid conflicts with other cached patterns
    private static final int SOURCE_DIGITS_PATTERN_BASE = 0x31000;  // Separate base for preserved source digits

    // Input data
    private final int ringsCollected;
    private final boolean gotEmerald;
    private final int stageIndex;
    private final int totalEmeraldCount;

    // Bonus values
    private int displayedRingCount;
    private int emeraldBonus;
    private int totalBonus;

    // Art cache
    private Pattern[] combinedPatterns;
    private boolean artCached = false;
    private boolean artLoaded = false;

    // Palette cache (4 palette lines for results screen)
    private Palette[] resultsPalettes;
    private boolean paletteLoaded = false;

    // Super Sonic sequence tracking
    private int superMsgTimer = 0;

    // Number rendering tracking - cache last values to avoid re-rendering
    private int lastDisplayedRingCount = Integer.MIN_VALUE;
    private int lastEmeraldBonus = Integer.MIN_VALUE;
    private final Pattern blankDigit = new Pattern();

    // Source digit patterns (preserved copy, since we modify the combined array in place)
    private Pattern[] sourceDigitPatterns;

    // Digit pattern offsets in combinedPatterns array
    // Numbers are at VRAM $520 (VRAM_NUMBERS - VRAM_BASE = 0x51E)
    // Each digit is 2 tiles (1 wide x 2 tall)
    // Digits 0-9 occupy tiles 0-19 in the numbers section
    private static final int DIGIT_TILES_PER_DIGIT = 2;
    private static final int DIGIT_COUNT = 10;

    // Number display positions in the mapping frames:
    // Frame 13 (RING_BONUS): numbers at tile $528 - $520 = 8 (offset 8 in numbers area)
    // Frame 16 (PERFECT_BONUS): numbers at tile $520 - $520 = 0 (offset 0 in numbers area)
    // Note: Frame 12 (EMERALD_BONUS) doesn't have a numbers piece
    private static final int RING_BONUS_DIGIT_OFFSET = 8;   // $528 - $520 for Frame 13
    private static final int PERFECT_DIGIT_OFFSET = 0;      // $520 - $520 for Frame 16
    private static final int DIGITS_PER_VALUE = 8;          // 4 digits x 2 tiles each

    public SpecialStageResultsScreenObjectInstance(int ringsCollected, boolean gotEmerald,
                                                    int stageIndex, int totalEmeraldCount) {
        super("ss_results_screen");
        this.ringsCollected = ringsCollected;
        this.gotEmerald = gotEmerald;
        this.stageIndex = stageIndex;
        this.totalEmeraldCount = totalEmeraldCount;

        calculateBonuses();
        // Don't load art in constructor - do it lazily in ensureArtCached()
        // The ROM and special stage manager may not be ready yet

        LOGGER.info("Special Stage Results: rings=" + ringsCollected + ", gotEmerald=" + gotEmerald +
                ", stage=" + (stageIndex + 1) + ", totalEmeralds=" + totalEmeraldCount +
                ", displayedRingCount=" + displayedRingCount + ", emeraldBonus=" + emeraldBonus);
    }

    /**
     * Loads all art sets needed for the results screen and combines them into
     * a unified VRAM-aligned pattern array. This mirrors the approach used by
     * the normal results screen in Sonic2ObjectArt.createResultsVramPatterns().
     *
     * Art sources:
     * 1. Special stage letters (A,C,D,G,H,I,L,M,P,R,S,T,U,W) - extracted from ArtNem_TitleCard2 -> VRAM $02-$3F
     * 2. Numbers - from ArtUnc_HUDNumbers -> VRAM $520-$53F
     * 3. Title card E,N,O,Z letters - from ArtNem_TitleCard -> VRAM $580-$58F
     * 4. Special stage results art - from SS DataLoader -> VRAM $590-$6C9
     * 5. HUD text (SCORE/TIME/RING) - from ArtNem_HUD -> VRAM $6CA-$700
     */
    private void loadArt() {
        try {
            RomManager romManager = RomManager.getInstance();
            if (!romManager.isRomAvailable()) {
                LOGGER.warning("ROM not available for results art loading");
                return;
            }
            Rom rom = romManager.getRom();

            // Load ArtNem_TitleCard2 - contains other letters (A,B,C,D,F,G,H,I,J,K,L,M,P,Q,R,S,T,U,V,W,X,Y)
            Pattern[] titleCard2Patterns = loadNemesisPatterns(rom,
                    Sonic2Constants.ART_NEM_TITLE_CARD2_ADDR, "TitleCard2");
            LOGGER.fine("Loaded " + (titleCard2Patterns != null ? titleCard2Patterns.length : 0) + " title card 2 patterns");

            // Load ArtUnc_HUDNumbers - HUD digit numbers (0-9)
            Pattern[] numbersPatterns = loadUncompressedPatterns(rom,
                    Sonic2Constants.ART_UNC_HUD_NUMBERS_ADDR,
                    Sonic2Constants.ART_UNC_HUD_NUMBERS_SIZE, "HUDNumbers");
            LOGGER.fine("Loaded " + (numbersPatterns != null ? numbersPatterns.length : 0) + " number patterns");

            // Load ArtNem_TitleCard - contains E, N, O, Z at the start (for "ZONE")
            Pattern[] titleCardPatterns = loadNemesisPatterns(rom,
                    Sonic2Constants.ART_NEM_TITLE_CARD_ADDR, "TitleCard");
            LOGGER.fine("Loaded " + (titleCardPatterns != null ? titleCardPatterns.length : 0) + " title card patterns (E,N,O,Z)");

            // Load special stage results art from DataLoader
            Pattern[] resultsArtPatterns = null;
            Sonic2SpecialStageManager manager = Sonic2SpecialStageManager.getInstance();
            if (manager != null) {
                Sonic2SpecialStageDataLoader dataLoader = manager.getDataLoader();
                if (dataLoader != null) {
                    resultsArtPatterns = dataLoader.getResultsArtPatterns();
                }
            }
            LOGGER.fine("Loaded " + (resultsArtPatterns != null ? resultsArtPatterns.length : 0) + " results art patterns");

            // Load ArtNem_HUD - HUD text (SCORE/TIME/RING/etc)
            Pattern[] hudPatterns = loadNemesisPatterns(rom,
                    Sonic2Constants.ART_NEM_HUD_ADDR, "HUD");
            LOGGER.fine("Loaded " + (hudPatterns != null ? hudPatterns.length : 0) + " HUD patterns");

            // Null safety
            if (titleCard2Patterns == null) titleCard2Patterns = new Pattern[0];
            if (numbersPatterns == null) numbersPatterns = new Pattern[0];
            if (titleCardPatterns == null) titleCardPatterns = new Pattern[0];
            if (resultsArtPatterns == null) resultsArtPatterns = new Pattern[0];
            if (hudPatterns == null) hudPatterns = new Pattern[0];

            // Create unified VRAM-aligned pattern array
            // Array index = VRAM address - VRAM_BASE
            int totalSize = VRAM_END - VRAM_BASE;
            combinedPatterns = new Pattern[totalSize];
            Pattern emptyPattern = new Pattern();
            Arrays.fill(combinedPatterns, emptyPattern);

            // Extract SS letters from TitleCard2 to VRAM $02-$3F (indices 0-61)
            extractSSLettersFromTitleCard2(titleCard2Patterns);
            LOGGER.fine("Extracted SS letters to indices 0-61");

            // Copy Numbers to VRAM $520 (index $520 - $02 = $51E = 1310)
            int numbersOffset = VRAM_NUMBERS - VRAM_BASE;
            copyPatterns(numbersPatterns, numbersOffset);
            LOGGER.fine("Copied " + numbersPatterns.length + " number patterns to index " + numbersOffset + " (0x" + Integer.toHexString(numbersOffset) + ")");

            // Preserve a copy of source digit patterns (0-9) before any modifications
            // These are needed because we modify the combined array in place during tally
            int numSourceDigits = Math.min(DIGIT_COUNT * DIGIT_TILES_PER_DIGIT, numbersPatterns.length);
            sourceDigitPatterns = new Pattern[numSourceDigits];
            for (int i = 0; i < numSourceDigits; i++) {
                sourceDigitPatterns[i] = new Pattern();
                if (numbersPatterns[i] != null) {
                    sourceDigitPatterns[i].copyFrom(numbersPatterns[i]);
                }
            }
            LOGGER.fine("Preserved " + numSourceDigits + " source digit patterns");

            // Copy Title Card E,N,O,Z to VRAM $580 (index $580 - $02 = $57E = 1406)
            int titleCardOffset = VRAM_TITLE_CARD - VRAM_BASE;
            int enoTiles = Math.min(16, titleCardPatterns.length);
            for (int i = 0; i < enoTiles && (titleCardOffset + i) < combinedPatterns.length; i++) {
                combinedPatterns[titleCardOffset + i] = titleCardPatterns[i];
            }
            LOGGER.fine("Copied " + enoTiles + " title card patterns to index " + titleCardOffset + " (0x" + Integer.toHexString(titleCardOffset) + ")");

            // Copy Results Art to VRAM $590 (index $590 - $02 = $58E = 1422)
            int resultsOffset = VRAM_RESULTS_ART - VRAM_BASE;
            copyPatterns(resultsArtPatterns, resultsOffset);
            LOGGER.fine("Copied " + resultsArtPatterns.length + " results patterns to index " + resultsOffset + " (0x" + Integer.toHexString(resultsOffset) + ")");

            // Copy HUD to VRAM $6CA (index $6CA - $02 = $6C8 = 1736)
            int hudOffset = VRAM_HUD - VRAM_BASE;
            copyPatterns(hudPatterns, hudOffset);
            LOGGER.fine("Copied " + hudPatterns.length + " HUD patterns to index " + hudOffset + " (0x" + Integer.toHexString(hudOffset) + ")");

            artLoaded = true;
            LOGGER.fine("Art loaded successfully: total " + totalSize + " pattern slots");

            // Load the results screen palette
            loadPalette(rom);

        } catch (IOException e) {
            LOGGER.warning("Failed to load results art: " + e.getMessage());
            combinedPatterns = null;
        }
    }

    /**
     * Helper to copy patterns into the combined array at a specific offset.
     */
    private void copyPatterns(Pattern[] src, int destOffset) {
        for (int i = 0; i < src.length && (destOffset + i) < combinedPatterns.length; i++) {
            combinedPatterns[destOffset + i] = src[i];
        }
    }

    /**
     * Loads uncompressed patterns from ROM.
     */
    private Pattern[] loadUncompressedPatterns(Rom rom, int address, int size, String name) {
        try {
            byte[] data = rom.readBytes(address, size);
            int patternCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;
            Pattern[] patterns = new Pattern[patternCount];
            for (int i = 0; i < patternCount; i++) {
                patterns[i] = new Pattern();
                byte[] subArray = Arrays.copyOfRange(data,
                        i * Pattern.PATTERN_SIZE_IN_ROM,
                        (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                patterns[i].fromSegaFormat(subArray);
            }
            return patterns;
        } catch (IOException e) {
            LOGGER.warning("Failed to load " + name + " patterns: " + e.getMessage());
            return new Pattern[0];
        }
    }

    /**
     * Loads the Special Stage Results Screen palette (Pal_Result).
     * This palette has 4 lines (128 bytes) and defines colors for text and emeralds.
     */
    private void loadPalette(Rom rom) {
        try {
            byte[] paletteData = rom.readBytes(
                    Sonic2SpecialStageConstants.RESULTS_PALETTE_OFFSET,
                    Sonic2SpecialStageConstants.RESULTS_PALETTE_SIZE);

            resultsPalettes = new Palette[4];
            for (int line = 0; line < 4; line++) {
                resultsPalettes[line] = new Palette();
                int offset = line * 32;  // 32 bytes per line (16 colors x 2 bytes)
                for (int c = 0; c < 16; c++) {
                    int byteOffset = offset + (c * 2);
                    if (byteOffset + 1 < paletteData.length) {
                        // Read big-endian Genesis color: ----BBB0GGG0RRR0
                        int genesisColor = ((paletteData[byteOffset] & 0xFF) << 8) |
                                (paletteData[byteOffset + 1] & 0xFF);
                        int r = ((genesisColor >> 1) & 0x7) * 36;
                        int g = ((genesisColor >> 5) & 0x7) * 36;
                        int b = ((genesisColor >> 9) & 0x7) * 36;
                        resultsPalettes[line].setColor(c, new Palette.Color((byte) r, (byte) g, (byte) b));
                    }
                }
            }
            paletteLoaded = true;
            LOGGER.fine("Loaded results palette (4 lines, 64 colors)");

        } catch (Exception e) {
            LOGGER.warning("Failed to load palette: " + e.getMessage());
            paletteLoaded = false;
        }
    }

    /**
     * Extracts the specific letters used by special stage results from ArtNem_TitleCard2.
     * The letters "ACDGHILMPRSTUW." are copied to VRAM $02 in the original game.
     *
     * The charset in s2.asm defines the source offsets in ArtNem_TitleCard2:
     * charset 'A',0
     * charset 'B',"\4\8\xC\4\x10\x14\x18\x1C\x1E\x22\x26\x2A\4\4\x30\x34\x38\x3C\x40\x44\x48\x4C\x52\x56\4"
     * charset 'a',"\4\4\4\4\4\4\4\4\2\4\4\4\6\4\4\4\4\4\4\4\4\4\6\4\4"
     *
     * Parsed offsets (charset 'B' maps B,C,D,E,F,G,H,I,J,K,L,M,N,O,P,Q,R,S,T,U,V,W,X,Y,Z):
     *   A = 0 (separate line)
     *   B = 4, C = 8, D = 12, E = 4 (placeholder)
     *   F = 16, G = 20 (0x14), H = 24 (0x18), I = 28 (0x1C)
     *   J = 30 (0x1E), K = 34 (0x22), L = 38 (0x26), M = 42 (0x2A)
     *   N = 4, O = 4 (placeholders)
     *   P = 48 (0x30), Q = 52 (0x34), R = 56 (0x38), S = 60 (0x3C)
     *   T = 64 (0x40), U = 68 (0x44), V = 72 (0x48), W = 76 (0x4C)
     *   X = 82 (0x52), Y = 86 (0x56), Z = 4 (placeholder)
     *
     * Target VRAM layout (relative to $02):
     *   $02 (idx 0): A
     *   $06 (idx 4): C
     *   $0A (idx 8): D
     *   $0E (idx 12): G
     *   $12 (idx 16): H
     *   $16 (idx 20): I (2 tiles only)
     *   $18 (idx 22): L
     *   $1C (idx 26): M (6 tiles)
     *   $22 (idx 32): P
     *   $26 (idx 36): R
     *   $2A (idx 40): S
     *   $2E (idx 44): T
     *   $32 (idx 48): U
     *   $36 (idx 52): W (6 tiles)
     */
    private void extractSSLettersFromTitleCard2(Pattern[] titleCard2Patterns) {
        if (titleCard2Patterns == null || titleCard2Patterns.length == 0) {
            LOGGER.warning("TitleCard2 patterns is null or empty");
            return;
        }

        // Letter extraction table: {source offset in TitleCard2, dest VRAM offset (rel to $02), tile count}
        // Source offsets from charset parsing: A=0, C=8, D=12, G=20, H=24, I=28, L=38, M=42, P=48, R=56, S=60, T=64, U=68, W=76
        int[][] letterTable = {
                {0,  0,  4},   // A: source 0, dest $02-$02=0, 4 tiles
                {8,  4,  4},   // C: source 8, dest $06-$02=4, 4 tiles
                {12, 8,  4},   // D: source 12, dest $0A-$02=8, 4 tiles
                {20, 12, 4},   // G: source 20 (0x14), dest $0E-$02=12, 4 tiles
                {24, 16, 4},   // H: source 24 (0x18), dest $12-$02=16, 4 tiles
                {28, 20, 2},   // I: source 28 (0x1C), dest $16-$02=20, 2 tiles
                {38, 22, 4},   // L: source 38, dest $18-$02=22, 4 tiles
                {42, 26, 6},   // M: source 42, dest $1C-$02=26, 6 tiles
                {48, 32, 4},   // P: source 48, dest $22-$02=32, 4 tiles
                {56, 36, 4},   // R: source 56, dest $26-$02=36, 4 tiles
                {60, 40, 4},   // S: source 60, dest $2A-$02=40, 4 tiles
                {64, 44, 4},   // T: source 64, dest $2E-$02=44, 4 tiles
                {68, 48, 4},   // U: source 68, dest $32-$02=48, 4 tiles
                {76, 52, 6},   // W: source 76, dest $36-$02=52, 6 tiles
        };

        String[] letterNames = {"A", "C", "D", "G", "H", "I", "L", "M", "P", "R", "S", "T", "U", "W"};
        int copiedLetters = 0;

        LOGGER.fine("TitleCard2 has " + titleCard2Patterns.length + " tiles");

        for (int i = 0; i < letterTable.length; i++) {
            int srcOffset = letterTable[i][0];
            int destOffset = letterTable[i][1];
            int tileCount = letterTable[i][2];

            if (srcOffset + tileCount <= titleCard2Patterns.length) {
                LOGGER.fine("Copying letter " + letterNames[i] + ": src " + srcOffset +
                        " -> dest " + destOffset + " (" + tileCount + " tiles)");
                for (int t = 0; t < tileCount; t++) {
                    if (destOffset + t < 64) {  // Stay within SS letters region (0-63)
                        combinedPatterns[destOffset + t] = titleCard2Patterns[srcOffset + t];
                    }
                }
                copiedLetters++;
            } else {
                LOGGER.warning("Not enough tiles for letter " + letterNames[i] +
                        " (need " + (srcOffset + tileCount) + ", have " + titleCard2Patterns.length + ")");
            }
        }

        LOGGER.fine("Extracted " + copiedLetters + " letters from TitleCard2");
    }

    private Pattern[] loadNemesisPatterns(Rom rom, int address, String name) {
        try {
            // Read a generous amount - Nemesis compression is variable
            byte[] compressed = rom.readBytes(address, 8192);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = NemesisReader.decompress(channel);
                int patternCount = decompressed.length / Pattern.PATTERN_SIZE_IN_ROM;
                Pattern[] patterns = new Pattern[patternCount];
                for (int i = 0; i < patternCount; i++) {
                    patterns[i] = new Pattern();
                    byte[] subArray = Arrays.copyOfRange(decompressed,
                            i * Pattern.PATTERN_SIZE_IN_ROM,
                            (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                    patterns[i].fromSegaFormat(subArray);
                }
                return patterns;
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load " + name + " patterns: " + e.getMessage());
            return new Pattern[0];
        }
    }

    private void ensureArtCached() {
        if (artCached) {
            return;
        }

        // Lazy load art if not yet loaded
        if (!artLoaded) {
            loadArt();
        }

        if (combinedPatterns == null) {
            LOGGER.warning("ensureArtCached: combinedPatterns is null after loadArt, artLoaded=" + artLoaded);
            return;
        }

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        if (graphicsManager == null) {
            LOGGER.warning("ensureArtCached: GraphicsManager is null");
            return;
        }

        // Cache all combined patterns to GPU
        LOGGER.fine("Caching " + combinedPatterns.length + " patterns at base 0x" + Integer.toHexString(PATTERN_BASE));
        for (int i = 0; i < combinedPatterns.length; i++) {
            if (combinedPatterns[i] != null) {
                graphicsManager.cachePatternTexture(combinedPatterns[i], PATTERN_BASE + i);
            }
        }

        // Cache the preserved source digit patterns at a separate base
        // These are used by renderBonusNumber() and won't be overwritten by updateBonusPatterns()
        if (sourceDigitPatterns != null) {
            LOGGER.fine("Caching " + sourceDigitPatterns.length + " source digit patterns at base 0x" + Integer.toHexString(SOURCE_DIGITS_PATTERN_BASE));
            for (int i = 0; i < sourceDigitPatterns.length; i++) {
                if (sourceDigitPatterns[i] != null) {
                    graphicsManager.cachePatternTexture(sourceDigitPatterns[i], SOURCE_DIGITS_PATTERN_BASE + i);
                }
            }
        }

        // Cache the results palettes
        // The mappings use palette indices 0, 1, 2, 3 - we need to ensure those point to our results palettes
        if (paletteLoaded && resultsPalettes != null) {
            LOGGER.fine("Caching results palettes (4 lines)");
            for (int i = 0; i < 4; i++) {
                if (resultsPalettes[i] != null) {
                    graphicsManager.cachePaletteTexture(resultsPalettes[i], i);
                }
            }
        }

        artCached = true;
        LOGGER.fine("Art and palettes cached successfully");
    }

    private void calculateBonuses() {
        // Display actual ring count (score tally will multiply by 10 when adding to score)
        displayedRingCount = ringsCollected;

        // Emerald bonus: 1000 if collected an emerald
        emeraldBonus = gotEmerald ? Sonic2SpecialStageConstants.RESULTS_EMERALD_BONUS : 0;

        // Total starts at 0 and counts up as bonuses tally
        totalBonus = 0;
    }

    /**
     * Updates the digit patterns in the combined pattern array to show current bonus values.
     * This modifies the patterns at the VRAM offsets referenced by the mapping frames.
     */
    private void updateBonusPatterns() {
        if (combinedPatterns == null || !artLoaded || sourceDigitPatterns == null) {
            return;
        }

        // Check if values have changed
        if (displayedRingCount == lastDisplayedRingCount && emeraldBonus == lastEmeraldBonus) {
            return;
        }

        // Use the preserved source digit patterns (not from combinedPatterns which gets modified)
        int numbersOffset = VRAM_NUMBERS - VRAM_BASE;

        // Update ring bonus digits (at VRAM $528, which is offset 8 in numbers area)
        // Used by Frame 13 (RING_BONUS) - 4 tiles wide x 2 tall = 8 tiles for 4 digits
        writeBonusValue(numbersOffset + RING_BONUS_DIGIT_OFFSET, displayedRingCount, sourceDigitPatterns);

        // Update emerald bonus digits (at VRAM $520, which is offset 0 in numbers area)
        // Used by Frame 16 (PERFECT_BONUS) when showing gems bonus
        if (gotEmerald) {
            writeBonusValue(numbersOffset + PERFECT_DIGIT_OFFSET, emeraldBonus, sourceDigitPatterns);
        }

        // Re-cache the updated patterns to GPU
        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        if (graphicsManager != null) {
            // Re-cache ring bonus digit patterns (offset 8-15)
            for (int i = 0; i < DIGITS_PER_VALUE; i++) {
                int patternIndex = numbersOffset + RING_BONUS_DIGIT_OFFSET + i;
                if (patternIndex < combinedPatterns.length && combinedPatterns[patternIndex] != null) {
                    graphicsManager.cachePatternTexture(combinedPatterns[patternIndex], PATTERN_BASE + patternIndex);
                }
            }
            // Re-cache emerald bonus digit patterns (offset 0-7)
            if (gotEmerald) {
                for (int i = 0; i < DIGITS_PER_VALUE; i++) {
                    int patternIndex = numbersOffset + PERFECT_DIGIT_OFFSET + i;
                    if (patternIndex < combinedPatterns.length && combinedPatterns[patternIndex] != null) {
                        graphicsManager.cachePatternTexture(combinedPatterns[patternIndex], PATTERN_BASE + patternIndex);
                    }
                }
            }
        }

        lastDisplayedRingCount = displayedRingCount;
        lastEmeraldBonus = emeraldBonus;
    }

    /**
     * Writes a bonus value (up to 4 digits) into the pattern array.
     * Leading zeros are suppressed except for the ones place.
     *
     * @param destIndex Index in combinedPatterns where the first digit tile goes
     * @param value     The numeric value to display (0-9999)
     * @param digits    Source digit patterns (0-9, each 2 tiles)
     */
    private void writeBonusValue(int destIndex, int value, Pattern[] digits) {
        int[] divisors = {1000, 100, 10, 1};
        boolean hasDigit = false;

        for (int i = 0; i < divisors.length; i++) {
            int divisor = divisors[i];
            int digit = value / divisor;
            value %= divisor;

            // Calculate tile index for this digit position
            // Each digit is 2 tiles (column-major: top tile, bottom tile)
            int tileIndex = destIndex + (i * DIGIT_TILES_PER_DIGIT);

            // Always show the last digit (ones place), even if value is 0
            boolean isLastDigit = (i == divisors.length - 1);

            if (digit != 0 || hasDigit || isLastDigit) {
                hasDigit = true;
                copyDigit(tileIndex, digit, digits);
            } else {
                // Leading zero - write blank patterns
                if (tileIndex < combinedPatterns.length) {
                    combinedPatterns[tileIndex] = blankDigit;
                }
                if (tileIndex + 1 < combinedPatterns.length) {
                    combinedPatterns[tileIndex + 1] = blankDigit;
                }
            }
        }
    }

    /**
     * Copies a single digit's patterns from the source digit array to the destination.
     *
     * @param destIndex Index in combinedPatterns for the digit
     * @param digit     Digit value (0-9)
     * @param digits    Source digit patterns
     */
    private void copyDigit(int destIndex, int digit, Pattern[] digits) {
        int srcIndex = digit * DIGIT_TILES_PER_DIGIT;
        if (srcIndex + 1 >= digits.length || destIndex + 1 >= combinedPatterns.length) {
            return;
        }
        // Copy the two tiles for this digit
        if (digits[srcIndex] != null) {
            combinedPatterns[destIndex] = digits[srcIndex];
        }
        if (digits[srcIndex + 1] != null) {
            combinedPatterns[destIndex + 1] = digits[srcIndex + 1];
        }
    }

    @Override
    protected int getSlideDuration() {
        return Sonic2SpecialStageConstants.RESULTS_SLIDE_DURATION;
    }

    @Override
    protected int getWaitDuration() {
        return Sonic2SpecialStageConstants.RESULTS_WAIT_DURATION;
    }

    @Override
    protected int getTallyTickInterval() {
        return Sonic2SpecialStageConstants.RESULTS_TALLY_TICK_INTERVAL;
    }

    @Override
    protected int getTallyDecrement() {
        return Sonic2SpecialStageConstants.RESULTS_TALLY_DECREMENT;
    }

    @Override
    protected TallyResult performTallyStep() {
        boolean anyRemaining = false;
        int totalIncrement = 0;

        // Decrement ring count by 1, add multiplied value (10) to score
        // This matches original game behavior: display shows ring count, score gets 10 pts per ring
        if (displayedRingCount > 0) {
            displayedRingCount--;
            totalIncrement += Sonic2SpecialStageConstants.RESULTS_RING_MULTIPLIER;
            anyRemaining = true;
        }

        // Decrement emerald bonus (still uses standard decrement logic)
        int[] emeraldResult = decrementBonus(emeraldBonus);
        emeraldBonus = emeraldResult[0];
        totalIncrement += emeraldResult[1];
        if (emeraldResult[1] > 0) anyRemaining = true;

        // Update total
        totalBonus += totalIncrement;

        return new TallyResult(anyRemaining, totalIncrement);
    }

    @Override
    protected void onExitReady() {
        // If all 7 emeralds collected, start Super Sonic message sequence
        if (totalEmeraldCount >= 7 && gotEmerald) {
            state = STATE_SUPER_SONIC_DISPLAY;
            stateTimer = 0;
            superMsgTimer = 0;
        } else {
            complete = true;
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        this.frameCounter = frameCounter;
        stateTimer++;
        totalFrames++;

        // Handle Super Sonic message state directly (parent's switch doesn't handle states > 3)
        if (state == STATE_SUPER_SONIC_DISPLAY) {
            updateSuperSonicMessages();
            return;
        }
        if (state == STATE_SUPER_DONE) {
            complete = true;
            return;
        }

        // Handle normal states via parent
        switch (state) {
            case STATE_SLIDE_IN -> updateSlideIn();
            case STATE_PRE_TALLY_DELAY -> updatePreTallyDelay();
            case STATE_TALLY -> updateTally();
            case STATE_WAIT -> updateWait();
            case STATE_EXIT -> complete = true;
        }
    }

    /**
     * Updates the Super Sonic message display timer.
     * All three messages display simultaneously for SUPER_MSG_DURATION frames.
     */
    private void updateSuperSonicMessages() {
        superMsgTimer++;
        if (superMsgTimer >= SUPER_MSG_DURATION) {
            state = STATE_SUPER_DONE;
            complete = true;
        }
    }

    @Override
    protected void updateWait() {
        // Normal wait state only - Super Sonic states are now handled in update()
        super.updateWait();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Ensure art is cached for rendering
        ensureArtCached();

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        if (graphicsManager == null) {
            return;
        }

        // Use screen coordinates (not world coordinates) for results screen
        // Results screen is a full-screen overlay, not world-relative
        float slideAlpha = getSlideAlpha();

        // Use ROM art if available, otherwise fall back to placeholders
        boolean useRomArt = artLoaded && combinedPatterns != null;

        // All elements use ROM-accurate 16 pixels/frame slide speed
        // From Obj6F_SubObjectMetaData in s2.asm - all elements start sliding at frame 0

        // "SPECIAL STAGE" title - slides from right (only shown when emerald NOT collected)
        // start=320+128=448, target=160, distance=288
        int titleOffset = getSlideOffset(288);
        int titleX = SCREEN_CENTER_X + titleOffset;
        if (!gotEmerald) {
            if (useRomArt) {
                renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_SPECIAL_STAGE,
                        titleX, TITLE_Y);
            } else {
                renderPlaceholderText(commands, titleX, TITLE_Y,
                        "SPECIAL STAGE", 1.0f, 0.8f, 0.2f);
            }
        }

        // Result text based on emerald count
        // Hide during Super Sonic message sequence (replaced by the three Super Sonic messages)
        if (gotEmerald && state < STATE_SUPER_SONIC_DISPLAY) {
            // "Sonic got a" slides from left: start=-128, target=160, distance=288
            int gotTextOffset = getSlideOffset(288);
            int gotTextX = SCREEN_CENTER_X - gotTextOffset;

            if (totalEmeraldCount >= 7) {
                // "SONIC HAS ALL THE" + "CHAOS EMERALDS"
                if (useRomArt) {
                    renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_SONIC_HAS_ALL,
                            gotTextX, GOT_TEXT_Y);
                    renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_CHAOS_EMERALDS,
                            gotTextX, EMERALD_TEXT_Y);
                } else {
                    renderPlaceholderText(commands, gotTextX, GOT_TEXT_Y,
                            "SONIC HAS ALL THE", 0.2f, 0.8f, 1.0f);
                    renderPlaceholderText(commands, gotTextX, EMERALD_TEXT_Y,
                            "CHAOS EMERALDS", 0.2f, 0.8f, 1.0f);
                }
            } else {
                // "SONIC GOT A" + "CHAOS EMERALD"
                if (useRomArt) {
                    renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_SONIC_GOT_A,
                            gotTextX, GOT_TEXT_Y);
                    renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_CHAOS_EMERALD,
                            gotTextX, EMERALD_TEXT_Y);
                } else {
                    renderPlaceholderText(commands, gotTextX, GOT_TEXT_Y,
                            "SONIC GOT A", 0.2f, 0.8f, 1.0f);
                    renderPlaceholderText(commands, gotTextX, EMERALD_TEXT_Y,
                            "CHAOS EMERALD", 0.2f, 0.8f, 1.0f);
                }
            }
        }

        // Render collected emeralds
        // Hide during Super Sonic message sequence
        if (state < STATE_SUPER_SONIC_DISPLAY) {
            if (useRomArt) {
                renderEmeralds(0, 0, slideAlpha);
            } else {
                renderEmeraldsPlaceholder(commands, 0, 0, slideAlpha);
            }
        }

        // Bonus displays - all start sliding at frame 0 along with title elements
        // From Obj6F_SubObjectMetaData in s2.asm:
        // - Y=136, routine $14, frame $C (12): SCORE - starts at 320+368, target=160, distance=528
        // - Y=152, routine $16, frame $D (13): SONIC RINGS - starts at 320+384, target=160, distance=544
        // - Y=184, routine $1A, frame $10 (16): GEMS BONUS - starts at 320+416, target=160, distance=576

        // Update digit patterns for display (numbers show initial values until tally changes them)
        if (useRomArt) {
            updateBonusPatterns();
        }

        if (useRomArt) {
            // Render bonus lines with slide-in animation
            // All numbers rendered at X offset 0x38 (56) from center for consistency
            int numbersXOffset = 0x38;

            // Line 1: Y=136 - "SCORE" - distance = 528 (688 - 160)
            int scoreOffset = getSlideOffset(528);
            int scoreX = SCREEN_CENTER_X + scoreOffset;
            renderMappingFrameWithoutNumbers(Sonic2SpecialStageResultsMappings.FRAME_EMERALD_BONUS,
                    scoreX, SCORE_LINE_Y);
            // Numbers always visible with the label
            renderBonusNumber(scoreX + numbersXOffset, SCORE_LINE_Y, totalBonus);

            // Line 2: Y=152 - "SONIC RINGS" - distance = 544 (704 - 160)
            int ringsOffset = getSlideOffset(544);
            int ringsX = SCREEN_CENTER_X + ringsOffset;
            renderMappingFrameWithoutNumbers(Sonic2SpecialStageResultsMappings.FRAME_RING_BONUS,
                    ringsX, SONIC_RINGS_Y);
            // Numbers always visible with the label
            renderBonusNumber(ringsX + numbersXOffset, SONIC_RINGS_Y, displayedRingCount);

            // Line 3: Y=184 - "GEMS BONUS" - distance = 576 (736 - 160)
            if (gotEmerald) {
                int gemsOffset = getSlideOffset(576);
                int gemsX = SCREEN_CENTER_X + gemsOffset;
                renderMappingFrameWithoutNumbers(Sonic2SpecialStageResultsMappings.FRAME_PERFECT_BONUS,
                        gemsX, GEMS_BONUS_Y);
                // Numbers always visible with the label
                renderBonusNumber(gemsX + numbersXOffset, GEMS_BONUS_Y, emeraldBonus);
            }
        } else {
            // Placeholder rendering with slide-in
            int scoreOffset = getSlideOffset(528);
            int ringsOffset = getSlideOffset(544);

            renderPlaceholderText(commands, SCREEN_CENTER_X + scoreOffset, SCORE_LINE_Y,
                    "SCORE: " + totalBonus, 1.0f, 1.0f, 0.5f);

            renderPlaceholderText(commands, SCREEN_CENTER_X + ringsOffset, SONIC_RINGS_Y,
                    "SONIC RINGS: " + displayedRingCount, 1.0f, 1.0f, 0.5f);

            if (gotEmerald) {
                int gemsOffset = getSlideOffset(576);
                renderPlaceholderText(commands, SCREEN_CENTER_X + gemsOffset, GEMS_BONUS_Y,
                        "GEMS BONUS: " + emeraldBonus, 0.5f, 1.0f, 0.5f);
            }
        }

        // Super Sonic message sequence - all three messages display simultaneously
        if (state == STATE_SUPER_SONIC_DISPLAY) {
            if (useRomArt) {
                // Render all three messages at their respective Y positions
                renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_NOW_SONIC_CAN,
                        SCREEN_CENTER_X, NOW_SONIC_CAN_Y);
                renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_CHANGE_INTO,
                        SCREEN_CENTER_X, CHANGE_INTO_Y);
                renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_SUPER_SONIC,
                        SCREEN_CENTER_X, SUPER_SONIC_Y);
            } else {
                // Placeholder text for all three messages
                renderPlaceholderText(commands, SCREEN_CENTER_X, NOW_SONIC_CAN_Y,
                        "NOW SONIC CAN", 1.0f, 0.8f, 0.0f);
                renderPlaceholderText(commands, SCREEN_CENTER_X, CHANGE_INTO_Y,
                        "CHANGE INTO", 1.0f, 0.8f, 0.0f);
                renderPlaceholderText(commands, SCREEN_CENTER_X, SUPER_SONIC_Y,
                        "SUPER SONIC", 1.0f, 0.8f, 0.0f);
            }
        }

        // Flush pattern batch
        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders a mapping frame from Sonic2SpecialStageResultsMappings.
     * Translates the piece data to render each 8x8 tile from the combined pattern array.
     *
     * @param frameIndex Index into Sonic2SpecialStageResultsMappings.FRAMES
     * @param centerX    Screen X coordinate for center of the frame
     * @param centerY    Screen Y coordinate for the frame
     */
    private void renderMappingFrame(int frameIndex, int centerX, int centerY) {
        renderMappingFrameInternal(frameIndex, centerX, centerY, false);
    }

    /**
     * Renders a mapping frame but skips any number pieces.
     * Used when we want to render the label text but render numbers separately
     * for consistent positioning.
     *
     * @param frameIndex Index into Sonic2SpecialStageResultsMappings.FRAMES
     * @param centerX    Screen X coordinate for center of the frame
     * @param centerY    Screen Y coordinate for the frame
     */
    private void renderMappingFrameWithoutNumbers(int frameIndex, int centerX, int centerY) {
        renderMappingFrameInternal(frameIndex, centerX, centerY, true);
    }

    /**
     * Internal method to render a mapping frame with optional filtering of number pieces.
     */
    private void renderMappingFrameInternal(int frameIndex, int centerX, int centerY, boolean skipNumbers) {
        if (!artLoaded || combinedPatterns == null) {
            return;
        }

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        if (graphicsManager == null) {
            return;
        }

        Sonic2SpecialStageResultsMappings.ResultsPiece[] pieces =
                Sonic2SpecialStageResultsMappings.getFrame(frameIndex);

        // Render pieces in reverse order (painter's algorithm - first piece on top)
        for (int i = pieces.length - 1; i >= 0; i--) {
            Sonic2SpecialStageResultsMappings.ResultsPiece piece = pieces[i];
            // Skip number pieces if requested
            if (skipNumbers && piece.artType() == Sonic2SpecialStageResultsMappings.ART_TYPE_NUMBERS) {
                continue;
            }
            renderMappingPiece(graphicsManager, piece, centerX, centerY);
        }
    }

    /**
     * Renders a single mapping piece (multi-tile sprite piece).
     *
     * The combined pattern array is VRAM-aligned, so we can convert from the piece's
     * original VRAM-relative tile index to an array index by:
     * - For SS Letters (artType 0): index = tileIndex (already relative to $02, which is VRAM_BASE)
     * - For Title Card (artType 1): index = VRAM_TITLE_CARD - VRAM_BASE + tileIndex
     * - For Results Art (artType 2): index = VRAM_RESULTS_ART - VRAM_BASE + tileIndex
     * - For HUD (artType 3): index = VRAM_HUD - VRAM_BASE + tileIndex
     * - For Numbers (artType 4): index = VRAM_NUMBERS - VRAM_BASE + tileIndex
     *
     * However, the ResultsPiece stores tileIndex already normalized relative to its art type's base.
     * So we just need to add the correct VRAM offset.
     */
    private void renderMappingPiece(GraphicsManager graphicsManager,
                                     Sonic2SpecialStageResultsMappings.ResultsPiece piece,
                                     int originX, int originY) {
        int baseTileIndex = piece.tileIndex();
        int artType = piece.artType();

        // Calculate pattern array index based on art type
        // The combined array is indexed by (VRAM address - VRAM_BASE)
        // The piece.tileIndex() is already relative to its art type's VRAM base
        int vramOffset;
        switch (artType) {
            case Sonic2SpecialStageResultsMappings.ART_TYPE_SS_LETTERS:
                vramOffset = VRAM_SS_LETTERS - VRAM_BASE;  // = 0
                break;
            case Sonic2SpecialStageResultsMappings.ART_TYPE_TITLE_CARD:
                vramOffset = VRAM_TITLE_CARD - VRAM_BASE;  // = $57E
                break;
            case Sonic2SpecialStageResultsMappings.ART_TYPE_RESULTS:
                vramOffset = VRAM_RESULTS_ART - VRAM_BASE; // = $58E
                break;
            case Sonic2SpecialStageResultsMappings.ART_TYPE_HUD:
                vramOffset = VRAM_HUD - VRAM_BASE;         // = $6C8
                break;
            case Sonic2SpecialStageResultsMappings.ART_TYPE_NUMBERS:
                vramOffset = VRAM_NUMBERS - VRAM_BASE;     // = $51E
                break;
            default:
                vramOffset = VRAM_RESULTS_ART - VRAM_BASE;
        }

        int widthTiles = piece.widthTiles();
        int heightTiles = piece.heightTiles();

        // Render each 8x8 tile in the piece
        // VDP uses column-major order
        for (int tx = 0; tx < widthTiles; tx++) {
            for (int ty = 0; ty < heightTiles; ty++) {
                int tileOffset = tx * heightTiles + ty;
                int localTileIndex = baseTileIndex + tileOffset;
                int patternIndex = vramOffset + localTileIndex;

                // Skip if out of range
                if (patternIndex < 0 || patternIndex >= combinedPatterns.length) {
                    continue;
                }

                int patternId = PATTERN_BASE + patternIndex;

                // Calculate screen position
                int tileX = originX + piece.xOffset() + (tx * 8);
                int tileY = originY + piece.yOffset() + (ty * 8);

                // Handle flipping
                if (piece.hFlip()) {
                    tileX = originX + piece.xOffset() + ((widthTiles - 1 - tx) * 8);
                }
                if (piece.vFlip()) {
                    tileY = originY + piece.yOffset() + ((heightTiles - 1 - ty) * 8);
                }

                // Build PatternDesc
                int descIndex = patternId & 0x7FF;
                if (piece.hFlip()) {
                    descIndex |= 0x800;
                }
                if (piece.vFlip()) {
                    descIndex |= 0x1000;
                }
                descIndex |= (piece.paletteIndex() & 0x3) << 13;
                PatternDesc desc = new PatternDesc(descIndex);

                graphicsManager.renderPatternWithId(patternId, desc, tileX, tileY);
            }
        }
    }

    /**
     * Renders collected emeralds using ROM art.
     * From s2.asm Obj6F_Emerald0:
     *   btst #0,(Vint_runcount+3).w  ; test bit 0 of frame counter
     *   beq.s +                       ; skip display on even frames
     *   bsr.w DisplaySprite           ; display on odd frames only
     * Emeralds are at fixed positions (no sliding) and flash on/off every frame.
     */
    private void renderEmeralds(int baseX, int baseY, float slideAlpha) {
        // Emerald frame indices from mappings
        int[] emeraldFrames = {
                Sonic2SpecialStageResultsMappings.FRAME_EMERALD_BLUE,
                Sonic2SpecialStageResultsMappings.FRAME_EMERALD_YELLOW,
                Sonic2SpecialStageResultsMappings.FRAME_EMERALD_PINK,
                Sonic2SpecialStageResultsMappings.FRAME_EMERALD_GREEN,
                Sonic2SpecialStageResultsMappings.FRAME_EMERALD_ORANGE,
                Sonic2SpecialStageResultsMappings.FRAME_EMERALD_PURPLE,
                Sonic2SpecialStageResultsMappings.FRAME_EMERALD_GRAY
        };

        for (int i = 0; i < 7; i++) {
            boolean hasThisEmerald = GameStateManager.getInstance().hasEmerald(i);

            if (hasThisEmerald) {
                // ROM-accurate flash: display on odd frames only (btst #0)
                if ((totalFrames & 1) != 0) {
                    // Fixed positions from EMERALD_POSITIONS - no sliding
                    int emeraldX = baseX + EMERALD_POSITIONS[i][0];
                    int emeraldY = baseY + EMERALD_POSITIONS[i][1];

                    renderMappingFrame(emeraldFrames[i], emeraldX, emeraldY);
                }
            }
        }
    }

    /**
     * Renders collected emeralds as placeholder colored boxes (fallback).
     * Emeralds are at fixed positions (no sliding) and flash on/off every frame.
     */
    private void renderEmeraldsPlaceholder(List<GLCommand> commands, int baseX, int baseY, float slideAlpha) {
        // Emerald colors (approximating the original colors)
        float[][] emeraldColors = {
                {0.2f, 0.4f, 1.0f},   // Blue
                {1.0f, 1.0f, 0.2f},   // Yellow
                {1.0f, 0.5f, 0.8f},   // Pink
                {0.2f, 0.9f, 0.3f},   // Green
                {1.0f, 0.6f, 0.2f},   // Orange
                {0.7f, 0.3f, 0.9f},   // Purple
                {0.6f, 0.6f, 0.6f}    // Gray
        };

        for (int i = 0; i < 7; i++) {
            boolean hasThisEmerald = GameStateManager.getInstance().hasEmerald(i);

            if (hasThisEmerald) {
                // ROM-accurate flash: display on odd frames only (btst #0)
                if ((totalFrames & 1) != 0) {
                    // Fixed positions from EMERALD_POSITIONS - no sliding
                    int emeraldX = EMERALD_POSITIONS[i][0];
                    int emeraldY = EMERALD_POSITIONS[i][1];

                    float[] color = emeraldColors[i];
                    renderPlaceholderBox(commands,
                            baseX + emeraldX - 8,
                            baseY + emeraldY - 8,
                            16, 16,
                            color[0], color[1], color[2]);
                }
            }
        }
    }

    /**
     * Renders a 4-digit bonus number at the specified screen position.
     * Used for the score line which has no built-in numbers in Frame 12.
     *
     * @param x     Screen X position for the number (right-aligned numbers position)
     * @param y     Screen Y position for the number
     * @param value The numeric value to display (0-9999)
     */
    private void renderBonusNumber(int x, int y, int value) {
        if (!artLoaded || combinedPatterns == null || sourceDigitPatterns == null) {
            return;
        }

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        if (graphicsManager == null) {
            return;
        }

        int[] divisors = {1000, 100, 10, 1};
        boolean hasDigit = false;

        for (int i = 0; i < divisors.length; i++) {
            int divisor = divisors[i];
            int digit = value / divisor;
            value %= divisor;

            // Calculate X position for this digit (each digit is 8 pixels wide)
            int digitX = x + (i * 8);

            // Always show the last digit (ones place), even if value is 0
            boolean isLastDigit = (i == divisors.length - 1);

            if (digit != 0 || hasDigit || isLastDigit) {
                hasDigit = true;
                // Render the two tiles for this digit (column-major: top, bottom)
                // Use SOURCE_DIGITS_PATTERN_BASE which has the unmodified digit patterns
                int srcTileIndex = digit * DIGIT_TILES_PER_DIGIT;
                if (srcTileIndex + 1 < sourceDigitPatterns.length) {
                    // Top tile - use SOURCE_DIGITS_PATTERN_BASE for preserved digits
                    int patternId = SOURCE_DIGITS_PATTERN_BASE + srcTileIndex;
                    PatternDesc desc = new PatternDesc(patternId & 0x7FF);
                    graphicsManager.renderPatternWithId(patternId, desc, digitX, y);

                    // Bottom tile
                    patternId = SOURCE_DIGITS_PATTERN_BASE + srcTileIndex + 1;
                    desc = new PatternDesc(patternId & 0x7FF);
                    graphicsManager.renderPatternWithId(patternId, desc, digitX, y + 8);
                }
            }
            // Leading zeros are blank (not rendered)
        }
    }

    // Getters for testing
    public int getDisplayedRingCount() { return displayedRingCount; }
    public int getEmeraldBonus() { return emeraldBonus; }
    public int getTotalBonus() { return totalBonus; }
    public boolean didGetEmerald() { return gotEmerald; }
}
