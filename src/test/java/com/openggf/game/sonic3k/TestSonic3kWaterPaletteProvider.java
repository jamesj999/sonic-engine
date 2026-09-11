package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.Palette;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kWaterPaletteProvider {
    private static final int PAL_POINTER_CNZ_WATER_INDEX = 0x3A;

    @Test
    void cnz2SonicUnderwaterPaletteMatchesPalPointersWaterPalette() throws IOException {
        Rom rom = GameServices.rom().getRom();
        Sonic3kWaterDataProvider provider = new Sonic3kWaterDataProvider();

        Palette[] palettes = provider.getUnderwaterPalette(rom,
                Sonic3kZoneIds.ZONE_CNZ, 1, PlayerCharacter.SONIC_AND_TAILS);

        assertNotNull(palettes, "CNZ2 Sonic/Tails should load Pal_CNZ_Water");

        int entryAddr = Sonic3kConstants.PAL_POINTERS_ADDR
                + PAL_POINTER_CNZ_WATER_INDEX * Sonic3kConstants.PAL_POINTER_ENTRY_SIZE;
        int sourceAddr = rom.read32BitAddr(entryAddr) & 0x00FFFFFF;
        int countMinusOne = rom.read16BitAddr(entryAddr + 6) & 0xFFFF;
        byte[] expected = rom.readBytes(sourceAddr, (countMinusOne + 1) * 4);

        assertEquals(128, expected.length, "Pal_CNZ_Water should be a full four-line palette");
        for (int line = 0; line < 4; line++) {
            for (int color = 0; color < 16; color++) {
                assertColorWord(palettes[line], color, segaWord(expected, line * 32 + color * 2),
                        "CNZ2 underwater palette line " + line + " color " + color);
            }
        }
    }

    private static int segaWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord, String message) {
        byte highByte = (byte) ((segaWord >> 8) & 0xFF);
        byte lowByte = (byte) (segaWord & 0xFF);
        int r3 = (lowByte >> 1) & 0x07;
        int g3 = (lowByte >> 5) & 0x07;
        int b3 = (highByte >> 1) & 0x07;
        assertEquals((r3 * 255 + 3) / 7, palette.getColor(colorIndex).r & 0xFF, message + " red");
        assertEquals((g3 * 255 + 3) / 7, palette.getColor(colorIndex).g & 0xFF, message + " green");
        assertEquals((b3 * 255 + 3) / 7, palette.getColor(colorIndex).b & 0xFF, message + " blue");
    }
}
