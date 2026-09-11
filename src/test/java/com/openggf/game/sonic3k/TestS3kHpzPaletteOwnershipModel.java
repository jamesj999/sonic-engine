package com.openggf.game.sonic3k;

import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.palette.PaletteWrite;
import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestS3kHpzPaletteOwnershipModel {

    @Test
    void masterEmeraldOverridesZoneGlowOnSharedRange() {
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] normal = blankPalettes();

        registry.beginFrame();
        registry.submit(PaletteWrite.normal(
                S3kPaletteOwners.HPZ_ZONE_CYCLE,
                S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                3,
                1,
                new byte[] { 0x00, 0x24, 0x00, 0x46 }));
        registry.submit(PaletteWrite.normal(
                S3kPaletteOwners.HPZ_MASTER_EMERALD,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                3,
                1,
                new byte[] { 0x00, 0x6A, 0x00, 0x60 }));

        registry.resolveInto(normal, null, null, null);

        assertEquals(S3kPaletteOwners.HPZ_MASTER_EMERALD,
                registry.ownerAt(PaletteSurface.NORMAL, 3, 1));
        assertEquals(S3kPaletteOwners.HPZ_MASTER_EMERALD,
                registry.ownerAt(PaletteSurface.NORMAL, 3, 2));
        assertColorWord(normal[3], 1, 0x006A);
        assertColorWord(normal[3], 2, 0x0060);
    }

    @Test
    void introMainSwapOnOtherLinesDoesNotInterfereWithLine4GlowOwnership() {
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] normal = blankPalettes();

        registry.beginFrame();
        registry.submit(PaletteWrite.normal(S3kPaletteOwners.HPZ_PALETTE_CONTROL, S3kPaletteOwners.PRIORITY_ZONE_EVENT, 1, 0, repeated(32, (byte) 0x0E)));
        registry.submit(PaletteWrite.normal(
                S3kPaletteOwners.HPZ_ZONE_CYCLE,
                S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                3,
                1,
                new byte[] { 0x00, 0x24, 0x00, 0x46 }));

        registry.resolveInto(normal, null, null, null);

        assertEquals(S3kPaletteOwners.HPZ_PALETTE_CONTROL, registry.ownerAt(PaletteSurface.NORMAL, 1, 0));
        assertEquals(S3kPaletteOwners.HPZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 3, 1));
    }

    private static Palette[] blankPalettes() {
        Palette[] palettes = new Palette[4];
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = new Palette();
        }
        return palettes;
    }

    private static byte[] repeated(int length, byte value) {
        byte[] data = new byte[length];
        java.util.Arrays.fill(data, value);
        return data;
    }

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord) {
        byte highByte = (byte) ((segaWord >> 8) & 0xFF);
        byte lowByte = (byte) (segaWord & 0xFF);
        int r3 = (lowByte >> 1) & 0x07;
        int g3 = (lowByte >> 5) & 0x07;
        int b3 = (highByte >> 1) & 0x07;
        int expectedR = (r3 * 255 + 3) / 7;
        int expectedG = (g3 * 255 + 3) / 7;
        int expectedB = (b3 * 255 + 3) / 7;
        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF,
                "Red for color " + colorIndex);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF,
                "Green for color " + colorIndex);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF,
                "Blue for color " + colorIndex);
    }
}
