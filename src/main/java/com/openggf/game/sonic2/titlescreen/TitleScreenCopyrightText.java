package com.openggf.game.sonic2.titlescreen;

import com.openggf.data.Rom;
import com.openggf.game.sonic2.constants.Sonic2Constants;

import java.io.IOException;

/**
 * The "@ 1992 SEGA" copyright line the Sonic 2 title screen stamps into the
 * emblem plane map before uploading it to Plane A.
 *
 * <p>From {@code TitleScreen} in {@code s2.asm}: after {@code MapEng_TitleLogo} is
 * Enigma-decoded into {@code Chunk_Table}, the {@code CopyrightText} words
 * ({@code word_3E82}, ROM {@code $3E82}) are copied to
 * {@code Chunk_Table + planeLoc(40,28,26)} (column 28, row 26 of the 40x28 map), and
 * the whole map is then sent to {@code VRAM_TtlScr_Plane_A_Name_Table}.
 *
 * <p>The table already holds finished plane-map words: each character is
 * {@code make_art_tile(ArtTile_ArtNem_FontStuff_TtlScr + chr, 0, 0)} under the
 * title-screen charset and spaces are {@code make_art_tile(ArtTile_VRAM_Start, 0, 0)},
 * so the engine reads the words from the ROM rather than re-encoding the text. The
 * font is the standard menu font ({@code ArtNem_FontStuff}) uploaded to
 * {@code ArtTile_ArtNem_FontStuff_TtlScr} ($680) during title-screen setup.
 */
public final class TitleScreenCopyrightText {

    private TitleScreenCopyrightText() {
    }

    /**
     * Reads the {@code CopyrightText} plane-map words from the ROM.
     */
    public static int[] readWords(Rom rom) throws IOException {
        byte[] bytes = rom.readBytes(Sonic2Constants.TITLE_COPYRIGHT_TEXT_ADDR,
                Sonic2Constants.TITLE_COPYRIGHT_TEXT_WORDS * 2);
        int[] words = new int[Sonic2Constants.TITLE_COPYRIGHT_TEXT_WORDS];
        for (int i = 0; i < words.length; i++) {
            words[i] = ((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF);
        }
        return words;
    }

    /**
     * Writes the copyright words into a Plane A map in place, mirroring the
     * {@code move.w (a2)+,(a1)+} copy loop in {@code TitleScreen}.
     *
     * @param planeMap   the Plane A map (row-major, {@code planeWidth} columns)
     * @param planeWidth width of the map in tiles (40 on the title screen)
     * @param words      the words from {@link #readWords(Rom)}
     */
    public static void writeInto(int[] planeMap, int planeWidth, int[] words) {
        if (planeMap == null || words == null) {
            return;
        }
        int base = Sonic2Constants.TITLE_COPYRIGHT_PLANE_ROW * planeWidth
                + Sonic2Constants.TITLE_COPYRIGHT_PLANE_COLUMN;
        for (int i = 0; i < words.length && base + i < planeMap.length; i++) {
            planeMap[base + i] = words[i];
        }
    }
}
