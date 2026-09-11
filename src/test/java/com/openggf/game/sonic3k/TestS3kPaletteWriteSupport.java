package com.openggf.game.sonic3k;

import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestS3kPaletteWriteSupport {

    @Test
    void applyColorsSubmitsOwnershipClaimsWhenRegistryPresent() {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();

        S3kPaletteWriteSupport.applyColors(
                registry,
                level,
                null,
                S3kPaletteOwners.AIZ_MINIBOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                new int[] {7, 10},
                new int[] {0x0EEE, 0x0888});

        registry.resolveInto(level.palettes(), null, null, null);

        assertEquals(S3kPaletteOwners.AIZ_MINIBOSS, registry.ownerAt(PaletteSurface.NORMAL, 1, 7));
        assertEquals(S3kPaletteOwners.AIZ_MINIBOSS, registry.ownerAt(PaletteSurface.NORMAL, 1, 10));
        assertColorWord(level.getPalette(1), 7, 0x0EEE);
        assertColorWord(level.getPalette(1), 10, 0x0888);
    }

    @Test
    void applyLineSubmitsHczOwnershipClaimsWhenRegistryPresent() {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        byte[] line = new byte[32];
        line[0] = 0x02;
        line[1] = 0x22;
        line[2] = 0x0C;
        line[3] = (byte) 0xEE;

        S3kPaletteWriteSupport.applyLine(
                registry,
                level,
                null,
                S3kPaletteOwners.HCZ_END_BOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                line);

        registry.resolveInto(level.palettes(), null, null, null);

        assertEquals(S3kPaletteOwners.HCZ_END_BOSS, registry.ownerAt(PaletteSurface.NORMAL, 1, 0));
        assertEquals(S3kPaletteOwners.HCZ_END_BOSS, registry.ownerAt(PaletteSurface.NORMAL, 1, 1));
        assertColorWord(level.getPalette(1), 0, 0x0222);
        assertColorWord(level.getPalette(1), 1, 0x0CEE);
    }

    @Test
    void applyLineCanResolveImmediatelyWhenRequested() {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        byte[] line = new byte[32];
        line[0] = 0x02;
        line[1] = 0x22;
        line[2] = 0x0C;
        line[3] = (byte) 0xEE;

        S3kPaletteWriteSupport.applyLine(
                registry,
                level,
                null,
                S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD,
                S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                1,
                line,
                true);

        assertEquals(S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD, registry.ownerAt(PaletteSurface.NORMAL, 1, 0));
        assertEquals(S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD, registry.ownerAt(PaletteSurface.NORMAL, 1, 1));
        assertColorWord(level.getPalette(1), 0, 0x0222);
        assertColorWord(level.getPalette(1), 1, 0x0CEE);
    }

    @Test
    void applyColorsSubmitsHczMinibossOwnershipClaimsWhenRegistryPresent() {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();

        S3kPaletteWriteSupport.applyColors(
                registry,
                level,
                null,
                S3kPaletteOwners.HCZ_MINIBOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                new int[] {4, 13},
                new int[] {0x0EEE, 0x0000});

        registry.resolveInto(level.palettes(), null, null, null);

        assertEquals(S3kPaletteOwners.HCZ_MINIBOSS, registry.ownerAt(PaletteSurface.NORMAL, 1, 4));
        assertEquals(S3kPaletteOwners.HCZ_MINIBOSS, registry.ownerAt(PaletteSurface.NORMAL, 1, 13));
        assertColorWord(level.getPalette(1), 4, 0x0EEE);
        assertColorWord(level.getPalette(1), 13, 0x0000);
    }

    @Test
    void applyLineFallsBackToDirectPaletteMutationWithoutRegistry() {
        StubLevel level = new StubLevel();
        byte[] line = new byte[32];
        line[0] = 0x0E;
        line[1] = (byte) 0xEE;
        line[2] = 0x08;
        line[3] = (byte) 0x88;

        S3kPaletteWriteSupport.applyLine(
                null,
                level,
                null,
                S3kPaletteOwners.AIZ_END_BOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                line);

        assertColorWord(level.getPalette(1), 0, 0x0EEE);
        assertColorWord(level.getPalette(1), 1, 0x0888);
    }

    @Test
    void applyContiguousPatchFallsBackToDirectPaletteMutationWithoutRegistry() {
        StubLevel level = new StubLevel();

        S3kPaletteWriteSupport.applyContiguousPatch(
                null,
                level,
                null,
                S3kPaletteOwners.AIZ_BOSS_SMALL,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                1,
                new byte[] {0x0A, (byte) 0xAA, 0x06, 0x44});

        assertColorWord(level.getPalette(1), 1, 0x0AAA);
        assertColorWord(level.getPalette(1), 2, 0x0644);
    }

    @Test
    void applyContiguousPatchSubmitsOwnershipClaimsWhenRegistryPresent() {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();

        S3kPaletteWriteSupport.applyContiguousPatch(
                registry,
                level,
                null,
                S3kPaletteOwners.LBZ_ZONE_CYCLE,
                S3kPaletteOwners.PRIORITY_ZONE_CYCLE,
                2,
                8,
                new byte[] {0x0A, (byte) 0xAA, 0x06, 0x44, 0x02, 0x22});

        registry.resolveInto(level.palettes(), null, null, null);

        assertEquals(S3kPaletteOwners.LBZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, 8));
        assertEquals(S3kPaletteOwners.LBZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, 9));
        assertEquals(S3kPaletteOwners.LBZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, 10));
        assertColorWord(level.getPalette(2), 8, 0x0AAA);
        assertColorWord(level.getPalette(2), 9, 0x0644);
        assertColorWord(level.getPalette(2), 10, 0x0222);
    }

    @Test
    void applyUnderwaterContiguousPatchSubmitsUnderwaterOwnershipClaims() {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] underwater = new Palette[] {new Palette(), new Palette(), new Palette(), new Palette()};

        S3kPaletteWriteSupport.applyUnderwaterContiguousPatch(
                registry,
                level,
                null,
                S3kPaletteOwners.SUPER_PALETTE,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                0,
                2,
                new byte[] {0x0A, (byte) 0x82, 0x08, 0x60, 0x06, 0x40});

        registry.resolveInto(level.palettes(), underwater, null, null);

        assertEquals(S3kPaletteOwners.SUPER_PALETTE, registry.ownerAt(PaletteSurface.UNDERWATER, 0, 2));
        assertEquals(S3kPaletteOwners.SUPER_PALETTE, registry.ownerAt(PaletteSurface.UNDERWATER, 0, 4));
        assertColorWord(underwater[0], 2, 0x0A82);
        assertColorWord(underwater[0], 3, 0x0860);
        assertColorWord(underwater[0], 4, 0x0640);
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
        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF);
    }

    private static final class StubLevel implements Level {
        private final Palette[] palettes = new Palette[] {
                new Palette(), new Palette(), new Palette(), new Palette()
        };

        Palette[] palettes() {
            return palettes;
        }

        @Override public int getPaletteCount() { return palettes.length; }
        @Override public Palette getPalette(int index) { return palettes[index]; }
        @Override public int getPatternCount() { return 0; }
        @Override public Pattern getPattern(int index) { throw new UnsupportedOperationException(); }
        @Override public int getChunkCount() { return 0; }
        @Override public Chunk getChunk(int index) { throw new UnsupportedOperationException(); }
        @Override public int getBlockCount() { return 0; }
        @Override public Block getBlock(int index) { throw new UnsupportedOperationException(); }
        @Override public SolidTile getSolidTile(int index) { throw new UnsupportedOperationException(); }
        @Override public Map getMap() { return null; }
        @Override public List<ObjectSpawn> getObjects() { return List.of(); }
        @Override public List<RingSpawn> getRings() { return List.of(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return 0; }
        @Override public int getZoneIndex() { return 0; }
    }
}
