package com.openggf.game;

import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.palette.PaletteWrite;
import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPaletteOwnershipRegistry {

    @Test
    void higherPriorityWriteOverridesOverlappingColorsOnly() {
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] normal = blankPalettes();

        registry.beginFrame();
        registry.submit(PaletteWrite.normal("zone.low", 100, 3, 1, new byte[] {
                0x00, 0x22, 0x00, 0x44
        }));
        registry.submit(PaletteWrite.normal("zone.high", 200, 3, 2, new byte[] {
                0x00, 0x66
        }));

        registry.resolveInto(normal, null, null, null);

        assertColorWord(normal[3], 1, 0x0022);
        assertColorWord(normal[3], 2, 0x0066);
    }

    @Test
    void mirrorWriteCopiesToUnderwaterSurface() {
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] normal = blankPalettes();
        Palette[] underwater = blankPalettes();

        registry.beginFrame();
        registry.submit(PaletteWrite.normal("hcz.water", 100, 2, 3, new byte[] {
                0x00, 0x22, 0x00, 0x44, 0x00, 0x66, 0x00, (byte) 0x88
        }).mirrorToUnderwater());

        registry.resolveInto(normal, underwater, null, null);

        assertColorWord(normal[2], 3, 0x0022);
        assertColorWord(normal[2], 6, 0x0088);
        assertColorWord(underwater[2], 3, 0x0022);
        assertColorWord(underwater[2], 6, 0x0088);
    }

    @Test
    void underwaterWriteTargetsUnderwaterSurfaceOnly() {
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] normal = blankPalettes();
        Palette[] underwater = blankPalettes();

        registry.beginFrame();
        registry.submit(PaletteWrite.underwater("boss.water", 200, 1, 0, new byte[] {
                0x00, 0x6A, 0x00, 0x60
        }));

        registry.resolveInto(normal, underwater, null, null);

        assertEquals("none", registry.ownerAt(PaletteSurface.NORMAL, 1, 0));
        assertEquals("boss.water", registry.ownerAt(PaletteSurface.UNDERWATER, 1, 0));
        assertColorWord(normal[1], 0, 0x0000);
        assertColorWord(underwater[1], 0, 0x006A);
        assertColorWord(underwater[1], 1, 0x0060);
    }

    @Test
    void beginFrameClearsPreviousClaims() {
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] normal = blankPalettes();

        registry.beginFrame();
        registry.submit(PaletteWrite.normal("frame.one", 100, 1, 0, new byte[] { 0x00, 0x0E }));
        registry.resolveInto(normal, null, null, null);
        assertColorWord(normal[1], 0, 0x000E);

        registry.beginFrame();
        registry.resolveInto(normal, null, null, null);
        assertColorWord(normal[1], 0, 0x000E);
        assertEquals("none", registry.ownerAt(PaletteSurface.NORMAL, 1, 0));
    }

    @Test
    void reusedWritesAndMirrorsRemainImmutableAfterSourceAndGetterMutation() {
        byte[] source = {0x00, 0x22, 0x00, 0x44};
        PaletteWrite original = PaletteWrite.normal("immutable", 100, 1, 5, source);
        PaletteWrite mirrored = original.mirrorToUnderwater();
        source[1] = 0x66;
        original.segaData()[1] = 0x66;
        mirrored.segaData()[3] = 0x66;
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] normal = blankPalettes();
        Palette[] underwater = blankPalettes();

        for (int frame = 0; frame < 3; frame++) {
            registry.beginFrame();
            registry.submit(mirrored);
            registry.resolveInto(normal, underwater, null, null);
            assertColorWord(normal[1], 5, 0x0022);
            assertColorWord(normal[1], 6, 0x0044);
            assertColorWord(underwater[1], 5, 0x0022);
            assertColorWord(underwater[1], 6, 0x0044);
            // Mutating the destination must not affect a reusable write either.
            normal[1].getColor(5).r = 0;
            underwater[1].getColor(6).g = 0;
        }
        assertEquals(false, original.mirrorToUnderwaterEnabled());
        assertEquals(true, mirrored.mirrorToUnderwaterEnabled());
    }

    @Test
    void applyingWriteDoesNotExposeOwnedBytesToPaletteSubclass() {
        Palette[] normal = blankPalettes();
        normal[1].colors[5] = new Palette.Color() {
            @Override
            public void fromSegaFormat(byte[] bytes, int offset) {
                throw new AssertionError("Private write bytes must not escape to palette callbacks");
            }
        };
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        registry.submit(PaletteWrite.normal("immutable", 100, 1, 5, new byte[] {0, 0x22}));
        registry.resolveInto(normal, null, null, null);
        assertColorWord(normal[1], 5, 0x0022);
    }

    @Test
    void packedColorConversionPreservesAllComponentLevelsAndIgnoresUnusedBits() {
        Palette.Color color = new Palette.Color();
        for (int word = 0; word <= 0xFFFF; word++) {
            color.fromSegaFormat(word);
            assertEquals((((word >>> 1) & 7) * 255 + 3) / 7, color.r & 0xFF);
            assertEquals((((word >>> 5) & 7) * 255 + 3) / 7, color.g & 0xFF);
            assertEquals((((word >>> 9) & 7) * 255 + 3) / 7, color.b & 0xFF);
        }
    }

    @Test
    void reusedEqualPriorityWritesPreserveSubmissionOrder() {
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] normal = blankPalettes();
        PaletteWrite first = PaletteWrite.normal("first", 100, 2, 9, new byte[] {0, 0x22});
        PaletteWrite second = PaletteWrite.normal("second", 100, 2, 9, new byte[] {0, 0x44});
        for (int frame = 0; frame < 2; frame++) {
            registry.beginFrame();
            registry.submit(frame == 0 ? first : second);
            registry.submit(frame == 0 ? second : first);
            registry.resolveInto(normal, null, null, null);
            assertColorWord(normal[2], 9, frame == 0 ? 0x0044 : 0x0022);
            assertEquals(frame == 0 ? "second" : "first",
                    registry.ownerAt(PaletteSurface.NORMAL, 2, 9));
        }
    }

    private static Palette[] blankPalettes() {
        Palette[] palettes = new Palette[4];
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = new Palette();
        }
        return palettes;
    }

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord) {
        // Convert the segaWord to big-endian bytes and use the same logic as Palette.Color.fromSegaFormat
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
