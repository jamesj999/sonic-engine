package uk.co.jamesj999.sonic.tests;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.tools.NemesisReader;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Test to debug Results screen VRAM layout issues.
 */
public class TestResultsVramLayout {

    private Rom rom;
    private static final String ROM_PATH = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";

    @Before
    public void setUp() {
        File romFile = new File(ROM_PATH);
        assumeTrue("ROM file not found, skipping test", romFile.exists());
        rom = new Rom();
        boolean opened = rom.open(ROM_PATH);
        assumeTrue("Could not open ROM", opened);
    }

    @Test
    public void testVramLayoutDebug() throws IOException {
        System.out.println("=== VRAM Layout Debug ===");
        System.out.println();

        // Load pattern arrays
        Pattern[] perfectPatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_PERFECT_ADDR);
        Pattern[] titleCardPatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_TITLE_CARD_ADDR);
        Pattern[] resultsTextPatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_RESULTS_TEXT_ADDR);
        Pattern[] miniSonicPatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_MINI_SONIC_ADDR);
        Pattern[] hudTextPatterns = loadNemesisPatterns(Sonic2Constants.ART_NEM_HUD_ADDR);
        Pattern[] bonusDisplayPatterns = new Pattern[Sonic2Constants.RESULTS_BONUS_DIGIT_TILES];

        System.out.println("Pattern counts:");
        System.out.println("  bonusDigits:  " + bonusDisplayPatterns.length);
        System.out.println("  perfect:      " + perfectPatterns.length);
        System.out.println("  titleCard:    " + titleCardPatterns.length);
        System.out.println("  resultsText:  " + resultsTextPatterns.length);
        System.out.println("  miniSonic:    " + miniSonicPatterns.length);
        System.out.println("  hudText:      " + hudTextPatterns.length);
        System.out.println();

        System.out.println("VRAM bases (hex):");
        System.out.println("  NUMBERS:        0x" + Integer.toHexString(Sonic2Constants.VRAM_BASE_NUMBERS));
        System.out.println("  PERFECT:        0x" + Integer.toHexString(Sonic2Constants.VRAM_BASE_PERFECT));
        System.out.println("  TITLE_CARD:     0x" + Integer.toHexString(Sonic2Constants.VRAM_BASE_TITLE_CARD));
        System.out.println("  RESULTS_TEXT:   0x" + Integer.toHexString(Sonic2Constants.VRAM_BASE_RESULTS_TEXT));
        System.out.println("  MINI_CHARACTER: 0x" + Integer.toHexString(Sonic2Constants.VRAM_BASE_MINI_CHARACTER));
        System.out.println("  HUD_TEXT:       0x" + Integer.toHexString(Sonic2Constants.VRAM_BASE_HUD_TEXT));
        System.out.println();

        System.out.println("VRAM ranges (using base + length):");
        System.out.println("  Numbers/Bonus:  0x520 - 0x" +
                Integer.toHexString(Sonic2Constants.VRAM_BASE_NUMBERS + bonusDisplayPatterns.length - 1));
        System.out.println("  Perfect:        0x540 - 0x" +
                Integer.toHexString(Sonic2Constants.VRAM_BASE_PERFECT + perfectPatterns.length - 1));
        System.out.println("  TitleCard:      0x580 - 0x" +
                Integer.toHexString(Sonic2Constants.VRAM_BASE_TITLE_CARD + titleCardPatterns.length - 1));
        System.out.println("  ResultsText:    0x5B0 - 0x" +
                Integer.toHexString(Sonic2Constants.VRAM_BASE_RESULTS_TEXT + resultsTextPatterns.length - 1));
        System.out.println("  MiniCharacter:  0x5F4 - 0x" +
                Integer.toHexString(Sonic2Constants.VRAM_BASE_MINI_CHARACTER + miniSonicPatterns.length - 1));
        System.out.println("  HUD Text:       0x6CA - 0x" +
                Integer.toHexString(Sonic2Constants.VRAM_BASE_HUD_TEXT + hudTextPatterns.length - 1));
        System.out.println();

        // Check for specific tile references from EOL mappings
        System.out.println("Checking specific tile references from EOL_Sonic mappings:");
        int base = Sonic2Constants.VRAM_BASE_NUMBERS;

        // S = $5D0, O = $588, N = $584, I = $5C0, C = $5B4, G = $5B8, T = $5D4
        int[] tileRefs = {0x5D0, 0x588, 0x584, 0x5C0, 0x5B4, 0x5B8, 0x5D4};
        String[] chars = {"S", "O", "N", "I", "C", "G", "T"};

        for (int i = 0; i < tileRefs.length; i++) {
            int tileIndex = tileRefs[i];
            int arrayIndex = tileIndex - base;
            String source = getSourceForTile(tileIndex);
            System.out.println("  '" + chars[i] + "' at $" + Integer.toHexString(tileIndex) +
                    " -> array index " + arrayIndex + " (0x" + Integer.toHexString(arrayIndex) + ") from " + source);
        }
        System.out.println();

        // Check TIME/RING bonus tiles
        System.out.println("Checking TIME BONUS / RING BONUS tiles:");
        int[] hudTileRefs = {0x6DA, 0x6D2, 0x6CA, 0x6F0};
        String[] hudLabels = {"TIME first", "RING first", "HUD base", "trailing zero"};

        for (int i = 0; i < hudTileRefs.length; i++) {
            int tileIndex = hudTileRefs[i];
            int arrayIndex = tileIndex - base;
            int hudOffset = tileIndex - Sonic2Constants.VRAM_BASE_HUD_TEXT;
            System.out.println("  " + hudLabels[i] + " at $" + Integer.toHexString(tileIndex) +
                    " -> array index " + arrayIndex + " (HUD offset " + hudOffset + ")");

            // Check if within HUD patterns range
            if (hudOffset >= 0 && hudOffset < hudTextPatterns.length) {
                System.out.println("    ✓ Within HUD patterns range");
            } else if (hudOffset >= 0) {
                System.out.println("    ✗ OUTSIDE HUD patterns range (max index: " + (hudTextPatterns.length - 1) + ")");
            }
        }
        System.out.println();

        // Calculate required array size (with $6F0 fix)
        int maxEnd = base;
        maxEnd = Math.max(maxEnd, base + bonusDisplayPatterns.length);
        maxEnd = Math.max(maxEnd, Sonic2Constants.VRAM_BASE_PERFECT + perfectPatterns.length);
        maxEnd = Math.max(maxEnd, Sonic2Constants.VRAM_BASE_TITLE_CARD + titleCardPatterns.length);
        maxEnd = Math.max(maxEnd, Sonic2Constants.VRAM_BASE_RESULTS_TEXT + resultsTextPatterns.length);
        maxEnd = Math.max(maxEnd, Sonic2Constants.VRAM_BASE_MINI_CHARACTER + miniSonicPatterns.length);
        maxEnd = Math.max(maxEnd, Sonic2Constants.VRAM_BASE_HUD_TEXT + hudTextPatterns.length);
        maxEnd = Math.max(maxEnd, 0x6F2); // Include trailing blank at $6F0-$6F1

        int totalSize = maxEnd - base;
        System.out.println("Calculated array size (with $6F0 fix): " + totalSize + " (0x" + Integer.toHexString(totalSize) + ")");
        System.out.println("Max VRAM address: 0x" + Integer.toHexString(maxEnd - 1));
        System.out.println();

        // Check for overlaps
        System.out.println("Checking for VRAM range overlaps:");
        checkOverlap("Perfect/TitleCard",
                Sonic2Constants.VRAM_BASE_PERFECT, perfectPatterns.length,
                Sonic2Constants.VRAM_BASE_TITLE_CARD, titleCardPatterns.length);
        checkOverlap("TitleCard/ResultsText",
                Sonic2Constants.VRAM_BASE_TITLE_CARD, titleCardPatterns.length,
                Sonic2Constants.VRAM_BASE_RESULTS_TEXT, resultsTextPatterns.length);
        checkOverlap("ResultsText/MiniCharacter",
                Sonic2Constants.VRAM_BASE_RESULTS_TEXT, resultsTextPatterns.length,
                Sonic2Constants.VRAM_BASE_MINI_CHARACTER, miniSonicPatterns.length);
        checkOverlap("MiniCharacter/HUD",
                Sonic2Constants.VRAM_BASE_MINI_CHARACTER, miniSonicPatterns.length,
                Sonic2Constants.VRAM_BASE_HUD_TEXT, hudTextPatterns.length);

        System.out.println();
        System.out.println("Verifying TitleCard patterns for 'O' and 'N':");
        System.out.println("  TitleCard[4] (N at $584): " +
                (titleCardPatterns.length > 4 ? "exists" : "MISSING"));
        System.out.println("  TitleCard[8] (O at $588): " +
                (titleCardPatterns.length > 8 ? "exists" : "MISSING"));

        // Test the actual pattern array creation
        System.out.println();
        System.out.println("Testing unified VRAM array creation...");
        Pattern[] unified = createResultsVramPatterns(
                bonusDisplayPatterns, perfectPatterns, titleCardPatterns,
                resultsTextPatterns, miniSonicPatterns, hudTextPatterns);

        int nIndex = 0x584 - base;
        int oIndex = 0x588 - base;
        int sIndex = 0x5D0 - base;

        System.out.println("  Unified array size: " + unified.length);
        System.out.println("  N at index " + nIndex + ": " +
                (nIndex < unified.length ? (unified[nIndex] != null ? "exists" : "NULL") : "OUT OF BOUNDS"));
        System.out.println("  O at index " + oIndex + ": " +
                (oIndex < unified.length ? (unified[oIndex] != null ? "exists" : "NULL") : "OUT OF BOUNDS"));
        System.out.println("  S at index " + sIndex + ": " +
                (sIndex < unified.length ? (unified[sIndex] != null ? "exists" : "NULL") : "OUT OF BOUNDS"));

        // Check if N and O patterns are actually from TitleCard
        Pattern nFromTitleCard = titleCardPatterns.length > 4 ? titleCardPatterns[4] : null;
        Pattern nFromUnified = nIndex < unified.length ? unified[nIndex] : null;
        Pattern oFromTitleCard = titleCardPatterns.length > 8 ? titleCardPatterns[8] : null;
        Pattern oFromUnified = oIndex < unified.length ? unified[oIndex] : null;

        System.out.println("  N pattern matches TitleCard[4]: " +
                (nFromTitleCard != null && nFromTitleCard == nFromUnified ? "YES" : "NO"));
        System.out.println("  O pattern matches TitleCard[8]: " +
                (oFromTitleCard != null && oFromTitleCard == oFromUnified ? "YES" : "NO"));

        // Check if patterns have non-zero pixels
        System.out.println();
        System.out.println("Pattern pixel check (single tiles):");
        checkPatternNonEmpty("TitleCard[4] (N tile 0)", nFromTitleCard);
        checkPatternNonEmpty("TitleCard[8] (O tile 0)", oFromTitleCard);
        checkPatternNonEmpty("Unified[100] (N tile 0)", nFromUnified);
        checkPatternNonEmpty("Unified[104] (O tile 0)", oFromUnified);

        // Check all 4 tiles for 2x2 letters (column-major order)
        System.out.println();
        System.out.println("Full 2x2 letter tiles (column-major: 0,1,2,3):");
        System.out.println("  Letter E (starts at TitleCard[0], $580):");
        for (int i = 0; i < 4; i++) {
            checkPatternNonEmpty("    TitleCard[" + i + "]",
                    titleCardPatterns.length > i ? titleCardPatterns[i] : null);
        }
        System.out.println("  Letter N (starts at TitleCard[4], $584):");
        for (int i = 0; i < 4; i++) {
            int idx = 4 + i;
            checkPatternNonEmpty("    TitleCard[" + idx + "]",
                    titleCardPatterns.length > idx ? titleCardPatterns[idx] : null);
        }
        System.out.println("  Letter O (starts at TitleCard[8], $588):");
        for (int i = 0; i < 4; i++) {
            int idx = 8 + i;
            checkPatternNonEmpty("    TitleCard[" + idx + "]",
                    titleCardPatterns.length > idx ? titleCardPatterns[idx] : null);
        }

        // Also check if unified array has correct data at these positions
        System.out.println();
        System.out.println("Unified array check for TitleCard letters:");
        int titleCardOffset = Sonic2Constants.VRAM_BASE_TITLE_CARD - Sonic2Constants.VRAM_BASE_NUMBERS; // 0x60
        System.out.println("  TitleCard starts at unified index: " + titleCardOffset);
        System.out.println("  E at unified[" + titleCardOffset + "-" + (titleCardOffset + 3) + "]:");
        for (int i = 0; i < 4; i++) {
            int idx = titleCardOffset + i;
            checkPatternNonEmpty("    unified[" + idx + "]",
                    idx < unified.length ? unified[idx] : null);
        }
        System.out.println("  N at unified[" + (titleCardOffset + 4) + "-" + (titleCardOffset + 7) + "]:");
        for (int i = 0; i < 4; i++) {
            int idx = titleCardOffset + 4 + i;
            checkPatternNonEmpty("    unified[" + idx + "]",
                    idx < unified.length ? unified[idx] : null);
        }
        System.out.println("  O at unified[" + (titleCardOffset + 8) + "-" + (titleCardOffset + 11) + "]:");
        for (int i = 0; i < 4; i++) {
            int idx = titleCardOffset + 8 + i;
            checkPatternNonEmpty("    unified[" + idx + "]",
                    idx < unified.length ? unified[idx] : null);
        }

        // Check a few more pattern positions
        int sResultsOffset = 0x5D0 - Sonic2Constants.VRAM_BASE_RESULTS_TEXT; // S offset in ResultsText
        System.out.println();
        System.out.println("Checking S at ResultsText offset " + sResultsOffset + ":");
        Pattern sFromResultsText = resultsTextPatterns.length > sResultsOffset ? resultsTextPatterns[sResultsOffset] : null;
        checkPatternNonEmpty("ResultsText[" + sResultsOffset + "] (S)", sFromResultsText);
        checkPatternNonEmpty("Unified[" + sIndex + "] (S)", sIndex < unified.length ? unified[sIndex] : null);

        // Now test the mapping parsing
        System.out.println();
        System.out.println("Testing mapping frame parsing (MapUnc_EOLTitleCards):");
        testMappingFrameParsing();
    }

    private void testMappingFrameParsing() throws IOException {
        // Create a RomByteReader from ROM data
        byte[] romData = new byte[(int) rom.getSize()];
        rom.getFileChannel().position(0);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(romData);
        rom.getFileChannel().read(buf);
        RomByteReader reader = new RomByteReader(romData);

        // Read the mappings directly from ROM
        int mappingAddr = Sonic2Constants.MAPPINGS_EOL_TITLE_CARDS_ADDR;
        int tileOffset = -Sonic2Constants.VRAM_BASE_NUMBERS;

        // Read offset table to get frame count
        int offsetTableSize = reader.readU16BE(mappingAddr);
        int frameCount = offsetTableSize / 2;
        System.out.println("  Frame count: " + frameCount);

        // Parse Frame 0 (SONIC GOT) manually
        System.out.println();
        System.out.println("  Frame 0 (SONIC GOT) pieces:");
        int frameOffset = reader.readU16BE(mappingAddr + 0 * 2);
        int frameAddr = mappingAddr + frameOffset;
        int pieceCount = reader.readU16BE(frameAddr);
        frameAddr += 2;

        String[] expectedChars = {"S", "O", "N", "I", "C", "G", "O", "T"};
        int[] expectedRawTiles = {0x5D0, 0x588, 0x584, 0x5C0, 0x5B4, 0x5B8, 0x588, 0x5D4};

        for (int p = 0; p < pieceCount && p < expectedChars.length; p++) {
            int yOffset = (byte) reader.readU8(frameAddr);
            frameAddr += 1;
            int size = reader.readU8(frameAddr);
            frameAddr += 1;
            int tileWord = reader.readU16BE(frameAddr);
            frameAddr += 2;
            frameAddr += 2; // 2P tile word
            int xOffset = (short) reader.readU16BE(frameAddr);
            frameAddr += 2;

            int widthTiles = ((size >> 2) & 0x3) + 1;
            int heightTiles = (size & 0x3) + 1;
            int rawTileIndex = tileWord & 0x7FF;
            int adjustedTileIndex = rawTileIndex + tileOffset;
            if (adjustedTileIndex < 0) adjustedTileIndex = 0;

            boolean matchesExpected = (rawTileIndex == expectedRawTiles[p]);

            System.out.println("    Piece " + p + " (" + expectedChars[p] + "): " +
                    "raw=$" + Integer.toHexString(rawTileIndex) +
                    ", adjusted=" + adjustedTileIndex +
                    ", size=" + widthTiles + "x" + heightTiles +
                    (matchesExpected ? " ✓" : " ✗ (expected $" + Integer.toHexString(expectedRawTiles[p]) + ")"));
        }
    }

    private void checkPatternNonEmpty(String label, Pattern pattern) {
        if (pattern == null) {
            System.out.println("  " + label + ": NULL");
            return;
        }
        int nonZeroPixels = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if ((pattern.getPixel(x, y) & 0xFF) != 0) {
                    nonZeroPixels++;
                }
            }
        }
        System.out.println("  " + label + ": " + nonZeroPixels + " non-zero pixels" +
                (nonZeroPixels == 0 ? " (EMPTY!)" : ""));
    }

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
        Arrays.fill(result, new Pattern());

        copyPatterns(result, bonusDigits, Sonic2Constants.VRAM_BASE_NUMBERS - base);
        copyPatterns(result, perfect, Sonic2Constants.VRAM_BASE_PERFECT - base);
        copyPatterns(result, titleCard, Sonic2Constants.VRAM_BASE_TITLE_CARD - base);
        copyPatterns(result, resultsText, Sonic2Constants.VRAM_BASE_RESULTS_TEXT - base);
        copyPatterns(result, miniSonic, Sonic2Constants.VRAM_BASE_MINI_CHARACTER - base);
        copyPatterns(result, hudText, Sonic2Constants.VRAM_BASE_HUD_TEXT - base);

        return result;
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

    private String getSourceForTile(int tileIndex) {
        if (tileIndex >= Sonic2Constants.VRAM_BASE_HUD_TEXT) return "HUD";
        if (tileIndex >= Sonic2Constants.VRAM_BASE_MINI_CHARACTER) return "MiniCharacter";
        if (tileIndex >= Sonic2Constants.VRAM_BASE_RESULTS_TEXT) return "ResultsText";
        if (tileIndex >= Sonic2Constants.VRAM_BASE_TITLE_CARD) return "TitleCard";
        if (tileIndex >= Sonic2Constants.VRAM_BASE_PERFECT) return "Perfect";
        if (tileIndex >= Sonic2Constants.VRAM_BASE_NUMBERS) return "Numbers";
        return "Unknown";
    }

    private void checkOverlap(String name, int base1, int len1, int base2, int len2) {
        int end1 = base1 + len1;
        int gap = base2 - end1;
        if (gap < 0) {
            System.out.println("  ✗ " + name + ": OVERLAP by " + (-gap) + " tiles!");
        } else if (gap == 0) {
            System.out.println("  ✓ " + name + ": No gap, perfectly adjacent");
        } else {
            System.out.println("  ! " + name + ": Gap of " + gap + " tiles (0x" + Integer.toHexString(gap) + ")");
        }
    }

    private Pattern[] loadNemesisPatterns(int artAddr) throws IOException {
        FileChannel channel = rom.getFileChannel();
        channel.position(artAddr);
        byte[] result = NemesisReader.decompress(channel);

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
}
