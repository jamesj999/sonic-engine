package com.openggf.game.sonic3k.specialstage;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kObjectArt;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.Pattern;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kSpecialStageResultsArt {

    @Test
    void specialStageHudInitialTilesMatchHudDrawInitialSequence() throws Exception {
        Rom rom = GameServices.rom().getRom();

        Sonic3kObjectArt objectArt = new Sonic3kObjectArt(null, RomByteReader.fromRom(rom));
        Pattern[] patterns = objectArt.loadSSResultsArt(PlayerCharacter.SONIC_AND_TAILS);
        assertNotNull(patterns);

        Pattern[] hudDigits = loadUncompressedPatterns(rom,
                Sonic3kConstants.ART_UNC_HUD_DIGITS_ADDR,
                Sonic3kConstants.ART_UNC_HUD_DIGITS_SIZE);

        int base = Sonic3kConstants.VRAM_SS_RESULTS_BASE;
        char[] expected = "E      00:00  0".toCharArray();
        for (int i = 0; i < expected.length; i++) {
            int tileIndex = 0x6E2 + (i * 2);
            int patternIndex = tileIndex - base;
            assertTrue(patternIndex >= 0 && patternIndex + 1 < patterns.length,
                    "Pattern index out of range for tile $" + Integer.toHexString(tileIndex));

            if (expected[i] == ' ') {
                assertBlank(patterns[patternIndex], tileIndex);
                assertBlank(patterns[patternIndex + 1], tileIndex + 1);
                continue;
            }

            int srcIndex = switch (expected[i]) {
                case '0' -> 0;
                case ':' -> 0x14;
                case 'E' -> 0x16;
                default -> throw new IllegalStateException("Unexpected glyph: " + expected[i]);
            };
            assertPatternEquals(hudDigits[srcIndex], patterns[patternIndex], tileIndex);
            assertPatternEquals(hudDigits[srcIndex + 1], patterns[patternIndex + 1], tileIndex + 1);
        }
    }

    @Test
    void scoreRowDigitsUseLiveGameScoreTiles() throws Exception {
        Rom rom = GameServices.rom().getRom();
        GameServices.gameState().resetSession();
        GameServices.gameState().addScore(2200);

        S3kSpecialStageResultsScreen screen = new S3kSpecialStageResultsScreen(
                0, false, 0, 0, PlayerCharacter.SONIC_AND_TAILS);
        Method updateScorePatterns = S3kSpecialStageResultsScreen.class
                .getDeclaredMethod("updateDynamicScorePatterns");
        updateScorePatterns.setAccessible(true);
        updateScorePatterns.invoke(screen);

        Field combinedPatternsField = S3kSpecialStageResultsScreen.class.getDeclaredField("combinedPatterns");
        combinedPatternsField.setAccessible(true);
        Pattern[] patterns = (Pattern[]) combinedPatternsField.get(screen);
        assertNotNull(patterns);

        Pattern[] hudDigits = loadUncompressedPatterns(rom,
                Sonic3kConstants.ART_UNC_HUD_DIGITS_ADDR,
                Sonic3kConstants.ART_UNC_HUD_DIGITS_SIZE);

        int patternIndex = 0x6E4 - Sonic3kConstants.VRAM_SS_RESULTS_BASE;
        char[] expectedDigits = "   2200".toCharArray();
        for (int i = 0; i < expectedDigits.length; i++) {
            int tileOffset = patternIndex + (i * 2);
            if (expectedDigits[i] == ' ') {
                assertBlank(patterns[tileOffset], 0x6E4 + (i * 2));
                assertBlank(patterns[tileOffset + 1], 0x6E5 + (i * 2));
                continue;
            }

            int digit = expectedDigits[i] - '0';
            int sourceIndex = digit * 2;
            assertPatternEquals(hudDigits[sourceIndex], patterns[tileOffset], 0x6E4 + (i * 2));
            assertPatternEquals(hudDigits[sourceIndex + 1], patterns[tileOffset + 1],
                    0x6E5 + (i * 2));
        }
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

    private void assertBlank(Pattern pattern, int tileIndex) {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if ((pattern.getPixel(x, y) & 0xFF) != 0) {
                    throw new AssertionError("Expected blank tile at $" + Integer.toHexString(tileIndex));
                }
            }
        }
    }

    private void assertPatternEquals(Pattern expected, Pattern actual, int tileIndex) {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (expected.getPixel(x, y) != actual.getPixel(x, y)) {
                    throw new AssertionError("Pattern mismatch at tile $" + Integer.toHexString(tileIndex)
                            + " pixel (" + x + "," + y + ")");
                }
            }
        }
    }
}


