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
    private static final int TITLE_Y = 32;
    private static final int GOT_TEXT_Y = 56;
    private static final int EMERALD_TEXT_Y = 72;
    private static final int EMERALDS_Y = 104;
    private static final int RING_BONUS_Y = 144;
    private static final int EMERALD_BONUS_Y = 160;
    private static final int TOTAL_Y = 184;

    // Super Sonic message positions
    private static final int SUPER_MSG_Y = 88;

    // States for Super Sonic message sequence
    private static final int STATE_SUPER_MSG_1 = 10;  // "NOW SONIC CAN"
    private static final int STATE_SUPER_MSG_2 = 11;  // "CHANGE INTO"
    private static final int STATE_SUPER_MSG_3 = 12;  // "SUPER SONIC"
    private static final int STATE_SUPER_DONE = 13;

    private static final int SUPER_MSG_DURATION = 60;  // Frames per message

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

    // Input data
    private final int ringsCollected;
    private final boolean gotEmerald;
    private final int stageIndex;
    private final int totalEmeraldCount;

    // Bonus values
    private int ringBonus;
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
                ", ringBonus=" + ringBonus + ", emeraldBonus=" + emeraldBonus);
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
                System.out.println("[SS Results] ROM not available for results art loading");
                return;
            }
            Rom rom = romManager.getRom();

            // Load ArtNem_TitleCard2 - contains other letters (A,B,C,D,F,G,H,I,J,K,L,M,P,Q,R,S,T,U,V,W,X,Y)
            Pattern[] titleCard2Patterns = loadNemesisPatterns(rom,
                    Sonic2Constants.ART_NEM_TITLE_CARD2_ADDR, "TitleCard2");
            System.out.println("[SS Results] Loaded " + (titleCard2Patterns != null ? titleCard2Patterns.length : 0) + " title card 2 patterns");

            // Load ArtUnc_HUDNumbers - HUD digit numbers (0-9)
            Pattern[] numbersPatterns = loadUncompressedPatterns(rom,
                    Sonic2Constants.ART_UNC_HUD_NUMBERS_ADDR,
                    Sonic2Constants.ART_UNC_HUD_NUMBERS_SIZE, "HUDNumbers");
            System.out.println("[SS Results] Loaded " + (numbersPatterns != null ? numbersPatterns.length : 0) + " number patterns");

            // Load ArtNem_TitleCard - contains E, N, O, Z at the start (for "ZONE")
            Pattern[] titleCardPatterns = loadNemesisPatterns(rom,
                    Sonic2Constants.ART_NEM_TITLE_CARD_ADDR, "TitleCard");
            System.out.println("[SS Results] Loaded " + (titleCardPatterns != null ? titleCardPatterns.length : 0) + " title card patterns (E,N,O,Z)");

            // Load special stage results art from DataLoader
            Pattern[] resultsArtPatterns = null;
            Sonic2SpecialStageManager manager = Sonic2SpecialStageManager.getInstance();
            if (manager != null) {
                Sonic2SpecialStageDataLoader dataLoader = manager.getDataLoader();
                if (dataLoader != null) {
                    resultsArtPatterns = dataLoader.getResultsArtPatterns();
                }
            }
            System.out.println("[SS Results] Loaded " + (resultsArtPatterns != null ? resultsArtPatterns.length : 0) + " results art patterns");

            // Load ArtNem_HUD - HUD text (SCORE/TIME/RING/etc)
            Pattern[] hudPatterns = loadNemesisPatterns(rom,
                    Sonic2Constants.ART_NEM_HUD_ADDR, "HUD");
            System.out.println("[SS Results] Loaded " + (hudPatterns != null ? hudPatterns.length : 0) + " HUD patterns");

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
            System.out.println("[SS Results] Extracted SS letters to indices 0-61");

            // Copy Numbers to VRAM $520 (index $520 - $02 = $51E = 1310)
            int numbersOffset = VRAM_NUMBERS - VRAM_BASE;
            copyPatterns(numbersPatterns, numbersOffset);
            System.out.println("[SS Results] Copied " + numbersPatterns.length + " number patterns to index " + numbersOffset + " (0x" + Integer.toHexString(numbersOffset) + ")");

            // Copy Title Card E,N,O,Z to VRAM $580 (index $580 - $02 = $57E = 1406)
            int titleCardOffset = VRAM_TITLE_CARD - VRAM_BASE;
            int enoTiles = Math.min(16, titleCardPatterns.length);
            for (int i = 0; i < enoTiles && (titleCardOffset + i) < combinedPatterns.length; i++) {
                combinedPatterns[titleCardOffset + i] = titleCardPatterns[i];
            }
            System.out.println("[SS Results] Copied " + enoTiles + " title card patterns to index " + titleCardOffset + " (0x" + Integer.toHexString(titleCardOffset) + ")");

            // Copy Results Art to VRAM $590 (index $590 - $02 = $58E = 1422)
            int resultsOffset = VRAM_RESULTS_ART - VRAM_BASE;
            copyPatterns(resultsArtPatterns, resultsOffset);
            System.out.println("[SS Results] Copied " + resultsArtPatterns.length + " results patterns to index " + resultsOffset + " (0x" + Integer.toHexString(resultsOffset) + ")");

            // Copy HUD to VRAM $6CA (index $6CA - $02 = $6C8 = 1736)
            int hudOffset = VRAM_HUD - VRAM_BASE;
            copyPatterns(hudPatterns, hudOffset);
            System.out.println("[SS Results] Copied " + hudPatterns.length + " HUD patterns to index " + hudOffset + " (0x" + Integer.toHexString(hudOffset) + ")");

            artLoaded = true;
            System.out.println("[SS Results] Art loaded successfully: total " + totalSize + " pattern slots");

            // Load the results screen palette
            loadPalette(rom);

        } catch (IOException e) {
            System.out.println("[SS Results] Failed to load results art: " + e.getMessage());
            e.printStackTrace();
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
            System.out.println("[SS Results] Loaded results palette (4 lines, 64 colors)");

        } catch (Exception e) {
            System.out.println("[SS Results] Failed to load palette: " + e.getMessage());
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
            System.out.println("[SS Results] TitleCard2 patterns is null or empty");
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

        System.out.println("[SS Results] TitleCard2 has " + titleCard2Patterns.length + " tiles");

        for (int i = 0; i < letterTable.length; i++) {
            int srcOffset = letterTable[i][0];
            int destOffset = letterTable[i][1];
            int tileCount = letterTable[i][2];

            if (srcOffset + tileCount <= titleCard2Patterns.length) {
                System.out.println("[SS Results] Copying letter " + letterNames[i] + ": src " + srcOffset +
                        " -> dest " + destOffset + " (" + tileCount + " tiles)");
                for (int t = 0; t < tileCount; t++) {
                    if (destOffset + t < 64) {  // Stay within SS letters region (0-63)
                        combinedPatterns[destOffset + t] = titleCard2Patterns[srcOffset + t];
                    }
                }
                copiedLetters++;
            } else {
                System.out.println("[SS Results] Warning: Not enough tiles for letter " + letterNames[i] +
                        " (need " + (srcOffset + tileCount) + ", have " + titleCard2Patterns.length + ")");
            }
        }

        System.out.println("[SS Results] Extracted " + copiedLetters + " letters from TitleCard2");
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
            System.out.println("[SS Results] ensureArtCached: combinedPatterns is null after loadArt, artLoaded=" + artLoaded);
            return;
        }

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        if (graphicsManager == null) {
            System.out.println("[SS Results] ensureArtCached: GraphicsManager is null");
            return;
        }

        // Cache all combined patterns to GPU
        System.out.println("[SS Results] Caching " + combinedPatterns.length + " patterns at base 0x" + Integer.toHexString(PATTERN_BASE));
        for (int i = 0; i < combinedPatterns.length; i++) {
            if (combinedPatterns[i] != null) {
                graphicsManager.cachePatternTexture(combinedPatterns[i], PATTERN_BASE + i);
            }
        }

        // Cache the results palettes
        // The mappings use palette indices 0, 1, 2, 3 - we need to ensure those point to our results palettes
        if (paletteLoaded && resultsPalettes != null) {
            System.out.println("[SS Results] Caching results palettes (4 lines)");
            for (int i = 0; i < 4; i++) {
                if (resultsPalettes[i] != null) {
                    graphicsManager.cachePaletteTexture(resultsPalettes[i], i);
                }
            }
        }

        artCached = true;
        System.out.println("[SS Results] Art and palettes cached successfully");
    }

    private void calculateBonuses() {
        // Ring bonus: rings collected * 10
        ringBonus = ringsCollected * Sonic2SpecialStageConstants.RESULTS_RING_MULTIPLIER;

        // Emerald bonus: 1000 if collected an emerald
        emeraldBonus = gotEmerald ? Sonic2SpecialStageConstants.RESULTS_EMERALD_BONUS : 0;

        // Total starts at 0 and counts up as bonuses tally
        totalBonus = 0;
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

        // Decrement ring bonus
        int[] ringResult = decrementBonus(ringBonus);
        ringBonus = ringResult[0];
        totalIncrement += ringResult[1];
        if (ringResult[1] > 0) anyRemaining = true;

        // Decrement emerald bonus
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
            state = STATE_SUPER_MSG_1;
            stateTimer = 0;
            superMsgTimer = 0;
        } else {
            complete = true;
        }
    }

    @Override
    protected void updateWait() {
        // Handle Super Sonic message sequence states
        if (state >= STATE_SUPER_MSG_1 && state <= STATE_SUPER_MSG_3) {
            superMsgTimer++;
            if (superMsgTimer >= SUPER_MSG_DURATION) {
                state++;
                superMsgTimer = 0;
                if (state > STATE_SUPER_MSG_3) {
                    state = STATE_SUPER_DONE;
                    complete = true;
                }
            }
            return;
        }
        if (state == STATE_SUPER_DONE) {
            complete = true;
            return;
        }

        // Normal wait state
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

        // Debug output (once per render)
        if (frameCounter == 0) {
            System.out.println("[SS Results] appendRenderCommands: artLoaded=" + artLoaded +
                    ", combinedPatterns=" + (combinedPatterns != null ? combinedPatterns.length : "null") +
                    ", artCached=" + artCached + ", useRomArt=" + useRomArt);
            // Debug: Show first frame's piece info
            var pieces = Sonic2SpecialStageResultsMappings.getFrame(0);
            System.out.println("[SS Results] Frame 0 has " + pieces.length + " pieces:");
            for (int i = 0; i < Math.min(3, pieces.length); i++) {
                var p = pieces[i];
                System.out.println("  Piece " + i + ": tileIndex=" + p.tileIndex() +
                    " (0x" + Integer.toHexString(p.tileIndex()) + ")" +
                    ", artType=" + p.artType() + ", palette=" + p.paletteIndex() +
                    ", size=" + p.widthTiles() + "x" + p.heightTiles());
            }
        }

        // "SPECIAL STAGE" title - slides from right
        int titleX = SCREEN_CENTER_X + (int) ((1 - slideAlpha) * 200);
        if (useRomArt) {
            renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_SPECIAL_STAGE,
                    titleX, TITLE_Y);
        } else {
            renderPlaceholderText(commands, titleX, TITLE_Y,
                    "SPECIAL STAGE", 1.0f, 0.8f, 0.2f);
        }

        // Result text based on emerald count
        if (gotEmerald) {
            int gotTextX = (int) (slideAlpha * SCREEN_CENTER_X);

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
        if (useRomArt) {
            renderEmeralds(0, 0, slideAlpha);
        } else {
            renderEmeraldsPlaceholder(commands, 0, 0, slideAlpha);
        }

        // Bonus displays - only after slide-in complete
        if (state >= STATE_TALLY) {
            if (useRomArt) {
                // Render ring bonus using mapping frame
                renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_RING_BONUS,
                        SCREEN_CENTER_X, RING_BONUS_Y);

                if (gotEmerald) {
                    renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_EMERALD_BONUS,
                            SCREEN_CENTER_X, EMERALD_BONUS_Y);
                }

                renderMappingFrame(Sonic2SpecialStageResultsMappings.FRAME_TOTAL,
                        SCREEN_CENTER_X, TOTAL_Y);
            } else {
                renderPlaceholderText(commands, SCREEN_CENTER_X, RING_BONUS_Y,
                        "RING BONUS: " + ringBonus, 1.0f, 1.0f, 0.5f);

                if (gotEmerald) {
                    renderPlaceholderText(commands, SCREEN_CENTER_X, EMERALD_BONUS_Y,
                            "EMERALD BONUS: " + emeraldBonus, 0.5f, 1.0f, 0.5f);
                }

                renderPlaceholderText(commands, SCREEN_CENTER_X, TOTAL_Y,
                        "TOTAL: " + totalBonus, 1.0f, 1.0f, 1.0f);
            }
        }

        // Super Sonic message sequence
        if (state >= STATE_SUPER_MSG_1 && state <= STATE_SUPER_MSG_3) {
            if (useRomArt) {
                int superMsgFrame = switch (state) {
                    case STATE_SUPER_MSG_1 -> Sonic2SpecialStageResultsMappings.FRAME_NOW_SONIC_CAN;
                    case STATE_SUPER_MSG_2 -> Sonic2SpecialStageResultsMappings.FRAME_CHANGE_INTO;
                    case STATE_SUPER_MSG_3 -> Sonic2SpecialStageResultsMappings.FRAME_SUPER_SONIC;
                    default -> -1;
                };
                if (superMsgFrame >= 0) {
                    renderMappingFrame(superMsgFrame, SCREEN_CENTER_X, SUPER_MSG_Y);
                }
            } else {
                String msg = switch (state) {
                    case STATE_SUPER_MSG_1 -> "NOW SONIC CAN";
                    case STATE_SUPER_MSG_2 -> "CHANGE INTO";
                    case STATE_SUPER_MSG_3 -> "SUPER SONIC";
                    default -> "";
                };
                if (!msg.isEmpty()) {
                    renderPlaceholderText(commands, SCREEN_CENTER_X, SUPER_MSG_Y,
                            msg, 1.0f, 0.8f, 0.0f);
                }
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

        int emeraldSpacing = 28;
        int startX = SCREEN_CENTER_X - (3 * emeraldSpacing);

        for (int i = 0; i < 7; i++) {
            boolean hasThisEmerald = GameStateManager.getInstance().hasEmerald(i);

            if (hasThisEmerald) {
                // Flash effect: show every other 8 frames
                if ((frameCounter & 4) != 0) {
                    int emeraldX = baseX + startX + (i * emeraldSpacing);
                    int slideOffsetY = (int) ((1 - slideAlpha) * 80);
                    int emeraldY = baseY + EMERALDS_Y + slideOffsetY;

                    renderMappingFrame(emeraldFrames[i], emeraldX, emeraldY);
                }
            }
        }
    }

    /**
     * Renders collected emeralds as placeholder colored boxes (fallback).
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

        int emeraldSpacing = 28;
        int startX = SCREEN_CENTER_X - (3 * emeraldSpacing);

        for (int i = 0; i < 7; i++) {
            boolean hasThisEmerald = GameStateManager.getInstance().hasEmerald(i);

            if (hasThisEmerald) {
                // Flash effect: show every other 8 frames
                if ((frameCounter & 4) != 0) {
                    int emeraldX = startX + (i * emeraldSpacing);
                    int slideOffsetY = (int) ((1 - slideAlpha) * 80);

                    float[] color = emeraldColors[i];
                    renderPlaceholderBox(commands,
                            baseX + emeraldX - 8,
                            baseY + EMERALDS_Y + slideOffsetY - 8,
                            16, 16,
                            color[0], color[1], color[2]);
                }
            }
        }
    }

    // Getters for testing
    public int getRingBonus() { return ringBonus; }
    public int getEmeraldBonus() { return emeraldBonus; }
    public int getTotalBonus() { return totalBonus; }
    public boolean didGetEmerald() { return gotEmerald; }
}
