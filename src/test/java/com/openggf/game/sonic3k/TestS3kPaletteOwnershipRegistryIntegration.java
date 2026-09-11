package com.openggf.game.sonic3k;

import com.openggf.data.RomByteReader;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.graphics.GraphicsManager;
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
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Integration test verifying that HCZ palette cycling routes writes through the
 * {@link PaletteOwnershipRegistry} and correctly mirrors water colors into
 * underwater palettes.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kPaletteOwnershipRegistryIntegration {

    private static final int ZONE_HCZ = 0x01;

    @Test
    void hczCycleMirrorsWaterColorsIntoUnderwaterPalette() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        StubLevel level = new StubLevel();
        Palette[] underwater = blankPalettes();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(
                reader, level, ZONE_HCZ, 0x00, registry, underwater);

        cycler.update();

        // The water cycle writes palette[2] colors 3-6 and mirrors to underwater.
        // After resolve, the underwater palette line 2 should match normal palette line 2
        // at the written color indices.
        assertColorEquals(level.getPalette(2), 3, underwater[2], 3);
        assertColorEquals(level.getPalette(2), 4, underwater[2], 4);
        assertColorEquals(level.getPalette(2), 5, underwater[2], 5);
        assertColorEquals(level.getPalette(2), 6, underwater[2], 6);
    }

    @Test
    void hczCycleStillUpdatesNormalPaletteColors() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(
                reader, level, ZONE_HCZ, 0x00, registry, null);

        // Record initial state (blank palettes = all zeros)
        int beforeR = level.getPalette(2).getColor(3).r & 0xFF;
        int beforeG = level.getPalette(2).getColor(3).g & 0xFF;
        int beforeB = level.getPalette(2).getColor(3).b & 0xFF;

        cycler.update();

        int afterR = level.getPalette(2).getColor(3).r & 0xFF;
        int afterG = level.getPalette(2).getColor(3).g & 0xFF;
        int afterB = level.getPalette(2).getColor(3).b & 0xFF;

        // At least one channel should differ (ROM water data is non-zero)
        boolean changed = (afterR != beforeR) || (afterG != beforeG) || (afterB != beforeB);
        assertNotEquals(false, changed,
                "HCZ water cycle should modify palette[2] color 3 on first tick");

        assertEquals(S3kPaletteOwners.HCZ_WATER_CYCLE,
                registry.ownerAt(PaletteSurface.NORMAL, 2, 3));
    }

    @Test
    void hczCyclePreservesPreSubmittedClaimsForTheFrame() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(TestEnvironment.currentRom());

        registry.beginFrame();
        registry.submit(com.openggf.game.palette.PaletteWrite.normal(
                "preexisting.owner",
                500,
                2,
                3,
                new byte[] { 0x0E, (byte) 0xEE, 0x0C, (byte) 0xCC, 0x0A, (byte) 0xAA, 0x08, (byte) 0x88 }));

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(
                reader, level, ZONE_HCZ, 0x00, registry, null);

        cycler.update();

        assertEquals("preexisting.owner", registry.ownerAt(PaletteSurface.NORMAL, 2, 3));
        assertColorWord(level.getPalette(2), 3, 0x0EEE);
    }

    // ========== Helpers ==========

    private static void assertColorEquals(Palette expected, int expectedIdx,
                                          Palette actual, int actualIdx) {
        Palette.Color e = expected.getColor(expectedIdx);
        Palette.Color a = actual.getColor(actualIdx);
        assertEquals(e.r & 0xFF, a.r & 0xFF,
                "Red mismatch at color " + actualIdx);
        assertEquals(e.g & 0xFF, a.g & 0xFF,
                "Green mismatch at color " + actualIdx);
        assertEquals(e.b & 0xFF, a.b & 0xFF,
                "Blue mismatch at color " + actualIdx);
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

    static Palette[] blankPalettes() {
        Palette[] palettes = new Palette[4];
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = new Palette();
        }
        return palettes;
    }

    /**
     * Minimal Level stub for HCZ palette cycling integration tests.
     */
    private static final class StubLevel implements Level {
        private final Palette[] palettes = blankPalettes();

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
        @Override public int getZoneIndex() { return ZONE_HCZ; }
    }
}
