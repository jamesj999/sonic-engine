package com.openggf.game.sonic2.titlescreen;

import com.openggf.data.Rom;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code CopyrightText} (s2.asm word_3E82) as read from the ROM and stamped at
 * {@code planeLoc(40,28,26)} of the Plane A logo map.
 */
class TestTitleScreenCopyrightText {

    /** {@code ArtTile_ArtNem_FontStuff_TtlScr}. */
    private static final int FONT = 0x680;

    /**
     * Encodes a character with the title-screen {@code charset} in effect for
     * {@code CopyrightText}: '0'..'9' = 0-9, '*' = $A, '@' = $B, ':' = $C, '.' = $D,
     * 'A'..'Z' = $E onwards, all as make_art_tile(FontStuff_TtlScr + chr, 0, 0);
     * spaces are make_art_tile(ArtTile_VRAM_Start, 0, 0).
     */
    private static int expectedWord(char c) {
        if (c == ' ') {
            return 0;
        }
        if (c >= '0' && c <= '9') {
            return FONT + (c - '0');
        }
        if (c == '@') {
            return FONT + 0xB;
        }
        return FONT + 0xE + (c - 'A');
    }

    @Test
    void romTableMatchesCopyrightTextCharsetEncoding() {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        assumeTrue(romFile != null && romFile.isFile(), "Sonic 2 ROM not available");

        int[] words;
        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "ROM failed to open: " + romFile);
            words = TitleScreenCopyrightText.readWords(rom);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        int[] expected = "@ 1992 SEGA".chars().map(c -> expectedWord((char) c)).toArray();
        assertArrayEquals(expected, words, Arrays.toString(words));
    }

    @Test
    void writeIntoStampsRow26Column28OfA40WideMap() {
        int[] map = new int[40 * 28];
        Arrays.fill(map, 0x1234);
        int[] words = new int[Sonic2Constants.TITLE_COPYRIGHT_TEXT_WORDS];
        for (int i = 0; i < words.length; i++) {
            words[i] = 0x700 + i;
        }
        TitleScreenCopyrightText.writeInto(map, 40, words);

        int base = 26 * 40 + 28;
        for (int i = 0; i < words.length; i++) {
            assertEquals(words[i], map[base + i], "word " + i);
        }
        assertEquals(0x1234, map[base - 1]);
        assertEquals(0x1234, map[base + words.length]);
        assertEquals(26 * 40 + 39, base + words.length, "text occupies columns 28-38, leaving column 39");
    }
}
