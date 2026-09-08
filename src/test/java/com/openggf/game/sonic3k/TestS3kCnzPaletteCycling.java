package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Sonic 3&K Carnival Night Zone palette cycling.
 * Verifies that all three channels (bumpers, background, tertiary) animate
 * correctly from ROM data without requiring a full level load.
 *
 * <p>CNZ has three palette animation channels (AnPal_CNZ in sonic3k.asm):
 * <ul>
 *   <li>Channel 1 (bumpers): timer period 3, counter step +6 / wrap 0x60
 *       Ã¢â€ â€™ palette[3] colors 9-11</li>
 *   <li>Channel 2 (background): runs every frame, counter step +6 / wrap 0xB4
 *       Ã¢â€ â€™ palette[2] colors 9-11</li>
 *   <li>Channel 3 (tertiary): timer period 2, counter step +4 / wrap 0x40
 *       Ã¢â€ â€™ palette[2] colors 7-8</li>
 * </ul>
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kCnzPaletteCycling {
    private Sonic3kPaletteCycler cycler;
    private StubLevel level;

    @BeforeEach
    public void setUp() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        RomByteReader reader = RomByteReader.fromRom(rom);
        level = new StubLevel();
        // CNZ zone index = 3, act index = 0
        cycler = new Sonic3kPaletteCycler(reader, level, 3, 0);
    }

    @Test
    public void paletteLine3Color9ChangesAfter30Frames() {
        // Channel 1 fires every 4 frames (timer period 3 Ã¢â€ â€™ fires on frame 4, 8, ...)
        // After 30 frames, channel 1 should have fired at least 7 times.
        Palette.Color initial = snapshot(level.getPalette(3).getColor(9));

        for (int i = 0; i < 30; i++) {
            cycler.update();
        }

        Palette.Color after = level.getPalette(3).getColor(9);
        // The bumper palette cycles through 16 distinct frames; after 30 ticks
        // (>= 7 channel-1 fires, each advancing by 6 bytes in a 96-byte table),
        // color 9 must differ from its initial value.
        assertFalse(colorsEqual(initial, after), "palette[3] color 9 should change after 30 frames");
    }

    @Test
    public void paletteLine2Color7ChangesAfter30Frames() {
        // Channel 3 fires every 3 frames (timer period 2 Ã¢â€ â€™ fires on frame 3, 6, ...).
        // After 30 frames, it fires 10 times, advancing counter4 by 40 (>= 1 full wrap at 0x40).
        Palette.Color initial = snapshot(level.getPalette(2).getColor(7));

        for (int i = 0; i < 30; i++) {
            cycler.update();
        }

        Palette.Color after = level.getPalette(2).getColor(7);
        assertFalse(colorsEqual(initial, after), "palette[2] color 7 should change after 30 frames");
    }

    @Test
    public void paletteLine2Color9ChangesEachFrame() {
        // Channel 2 (background) runs every frame Ã¢â‚¬â€ color 9 should change on frame 1.
        Palette.Color before = snapshot(level.getPalette(2).getColor(9));
        cycler.update();
        Palette.Color after = level.getPalette(2).getColor(9);
        assertFalse(colorsEqual(before, after), "palette[2] color 9 should change on the very first frame (ch2 always runs)");
    }

    /**
     * Verifies CNZ palette cycling routes writes through the palette ownership
     * registry and uses the ROM's dedicated water palette tables when an
     * underwater surface is supplied.
     */
    @Test
    public void cnzPaletteCycleUsesCnzWaterTablesForUnderwaterSurface() throws IOException {
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] underwaterPalettes = blankPalettes();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());
        cycler = new Sonic3kPaletteCycler(
                reader,
                level,
                3,
                0,
                registry,
                underwaterPalettes);

        cycler.update();

        assertColorEquals(segaColor(reader, Sonic3kConstants.ANPAL_CNZ_2_ADDR),
                underwaterPalettes[3].getColor(9),
                "CNZ bumper cycle should use AnPal_PalCNZ_2 for underwater palette[3] color 9");
        assertColorEquals(segaColor(reader, Sonic3kConstants.ANPAL_CNZ_4_ADDR + 2),
                underwaterPalettes[2].getColor(10),
                "CNZ background cycle should use AnPal_PalCNZ_4 for underwater palette[2] color 10");
        assertTrue(colorsEqual(level.getPalette(2).getColor(7), underwaterPalettes[2].getColor(7)),
                "CNZ tertiary cycle should match normal palette[2] color 7 because the ROM reuses AnPal_PalCNZ_5");
        assertFalse(colorsEqual(level.getPalette(3).getColor(9), underwaterPalettes[3].getColor(9)),
                "CNZ underwater bumper color should not be a mirror of the normal surface");
        assertEquals(S3kPaletteOwners.CNZ_ANPAL,
                registry.ownerAt(PaletteSurface.NORMAL, 3, 9),
                "CNZ bumper cycle should claim palette[3] color 9 through the ownership registry");
        assertEquals(S3kPaletteOwners.CNZ_ANPAL,
                registry.ownerAt(PaletteSurface.NORMAL, 2, 9),
                "CNZ background cycle should claim palette[2] color 9 through the ownership registry");
        assertEquals(S3kPaletteOwners.CNZ_ANPAL,
                registry.ownerAt(PaletteSurface.UNDERWATER, 2, 7),
                "CNZ tertiary cycle should mirror its ownership claim into the underwater surface");
    }

    // ========== Specific color value assertions ==========

    /**
     * Verifies channel 1 (bumpers) applies ROM color values on first tick.
     * Channel 1 fires immediately (timer starts at 0) and writes palette[3] colors 9-11
     * from the bumper table frame 0. At least one of the 3 bumper colors must be non-zero,
     * as the cycle contains bright neon/electric colors (some individual entries may be black).
     */
    @Test
    public void bumperChannel1FirstTickAppliesColors() {
        cycler.update();

        Palette pal3 = level.getPalette(3);
        boolean anyNonZero = false;
        for (int c = 9; c <= 11; c++) {
            int r = pal3.getColor(c).r & 0xFF;
            int g = pal3.getColor(c).g & 0xFF;
            int b = pal3.getColor(c).b & 0xFF;
            if (r > 0 || g > 0 || b > 0) {
                anyNonZero = true;
            }
        }
        assertTrue(anyNonZero, "At least one bumper color (9-11) should be non-zero after first tick");
    }

    /**
     * Verifies channel 2 (background) applies specific ROM color values on first tick.
     * Channel 2 runs every frame and writes palette[2] colors 9-11.
     */
    @Test
    public void backgroundChannel2FirstTickAppliesNonZeroColors() {
        cycler.update();

        Palette pal2 = level.getPalette(2);
        for (int c = 9; c <= 11; c++) {
            int r = pal2.getColor(c).r & 0xFF;
            int g = pal2.getColor(c).g & 0xFF;
            int b = pal2.getColor(c).b & 0xFF;
            assertTrue(r > 0 || g > 0 || b > 0, "Background color " + c + " should be non-zero after first tick, got ("
                    + r + "," + g + "," + b + ")");
        }
    }

    /**
     * Verifies channel 3 (tertiary) applies specific ROM color values on first tick.
     * Channel 3 fires immediately (timer starts at 0) and writes palette[2] colors 7-8.
     */
    @Test
    public void tertiaryChannel3FirstTickAppliesNonZeroColors() {
        cycler.update();

        Palette pal2 = level.getPalette(2);
        for (int c = 7; c <= 8; c++) {
            int r = pal2.getColor(c).r & 0xFF;
            int g = pal2.getColor(c).g & 0xFF;
            int b = pal2.getColor(c).b & 0xFF;
            assertTrue(r > 0 || g > 0 || b > 0, "Tertiary color " + c + " should be non-zero after first tick, got ("
                    + r + "," + g + "," + b + ")");
        }
    }

    /**
     * Verifies bumper cycle produces multiple distinct palette states over 30 frames.
     * Channel 1 has 16 frames (step +6, wrap 0x60), timer period 3, so fires ~7 times
     * in 30 ticks Ã¢â‚¬â€ should yield at least 3 distinct color states.
     */
    @Test
    public void bumperCycleProducesMultipleDistinctValues() {
        int distinctCount = 0;
        int prevR = -1, prevG = -1, prevB = -1;

        for (int frame = 0; frame < 30; frame++) {
            cycler.update();
            Palette.Color c9 = level.getPalette(3).getColor(9);
            int r = c9.r & 0xFF;
            int g = c9.g & 0xFF;
            int b = c9.b & 0xFF;
            if (r != prevR || g != prevG || b != prevB) {
                distinctCount++;
                prevR = r;
                prevG = g;
                prevB = b;
            }
        }

        assertTrue(distinctCount >= 3, "Bumper cycle should produce at least 3 distinct color 9 values over 30 frames, got "
                + distinctCount);
    }

    /**
     * Verifies background cycle advances every frame producing many distinct values.
     * Channel 2 runs every frame with 30 entries Ã¢â‚¬â€ so 30 ticks should yield 30 distinct states.
     */
    @Test
    public void backgroundCycleAdvancesEveryFrame() {
        int distinctCount = 0;
        int prevR = -1, prevG = -1, prevB = -1;

        for (int frame = 0; frame < 30; frame++) {
            cycler.update();
            Palette.Color c9 = level.getPalette(2).getColor(9);
            int r = c9.r & 0xFF;
            int g = c9.g & 0xFF;
            int b = c9.b & 0xFF;
            if (r != prevR || g != prevG || b != prevB) {
                distinctCount++;
                prevR = r;
                prevG = g;
                prevB = b;
            }
        }

        // 30 frames with step +6 in a 180-byte table (30 frames) Ã¢â‚¬â€ each should be unique
        assertTrue(distinctCount >= 10, "Background cycle should produce at least 10 distinct color 9 values over 30 frames, got "
                + distinctCount);
    }

    @Test
    public void allChannelsMatchRomThroughTimerEdgesAndWraps() throws IOException {
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());
        Palette[] underwater = blankPalettes();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        cycler = new Sonic3kPaletteCycler(reader, level, 3, 0, registry, underwater);
        for (int tick = 0; tick < 192; tick++) {
            registry.beginFrame();
            cycler.update();
            assertCnzFrame(reader, underwater, tick);
        }
    }

    @Test
    public void rewindRestoresAllChannelOffsetsAndTimersWithReusablePatches() throws IOException {
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());
        Palette[] underwater = blankPalettes();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        cycler = new Sonic3kPaletteCycler(reader, level, 3, 0, registry, underwater);
        for (int tick = 0; tick < 59; tick++) {
            registry.beginFrame();
            cycler.update();
        }
        byte[] saved = cycler.captureCyclerState();
        for (int tick = 0; tick < 80; tick++) {
            registry.beginFrame();
            cycler.update();
        }
        cycler.restoreCyclerState(saved);
        assertArrayEquals(saved, cycler.captureCyclerState());
        // Palette surfaces are snapshotted separately in production. Allow each
        // channel to fire once here before comparing retained as well as new colors.
        for (int tick = 59; tick < 128; tick++) {
            registry.beginFrame();
            cycler.update();
            if (tick >= 62) {
                assertCnzFrame(reader, underwater, tick);
            }
        }
    }

    private void assertCnzFrame(RomByteReader reader, Palette[] underwater, int tick) {
        assertChannel(reader, Sonic3kConstants.ANPAL_CNZ_1_ADDR,
                Sonic3kConstants.ANPAL_CNZ_2_ADDR, underwater, 3, 9, 3, (tick / 4) % 16, tick);
        assertChannel(reader, Sonic3kConstants.ANPAL_CNZ_3_ADDR,
                Sonic3kConstants.ANPAL_CNZ_4_ADDR, underwater, 2, 9, 3, tick % 30, tick);
        assertChannel(reader, Sonic3kConstants.ANPAL_CNZ_5_ADDR,
                Sonic3kConstants.ANPAL_CNZ_5_ADDR, underwater, 2, 7, 2, (tick / 3) % 16, tick);
    }

    private void assertChannel(RomByteReader reader, int normalAddress, int waterAddress,
                               Palette[] underwater, int line, int start, int colors,
                               int tableFrame, int tick) {
        for (int color = 0; color < colors; color++) {
            int offset = (tableFrame * colors + color) * 2;
            String context = "tick " + tick + ", line " + line + ", color " + (start + color);
            assertColorEquals(segaColor(reader, normalAddress + offset),
                    level.getPalette(line).getColor(start + color), "normal " + context);
            assertColorEquals(segaColor(reader, waterAddress + offset),
                    underwater[line].getColor(start + color), "underwater " + context);
        }
    }

    // ===== helpers =====

    private static Palette[] blankPalettes() {
        Palette[] palettes = new Palette[4];
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = new Palette();
        }
        return palettes;
    }

    private static Palette.Color snapshot(Palette.Color c) {
        return new Palette.Color(c.r, c.g, c.b);
    }

    private static Palette.Color segaColor(RomByteReader reader, int addr) {
        Palette.Color color = new Palette.Color();
        color.fromSegaFormat(reader.slice(addr, 2), 0);
        return color;
    }

    private static void assertColorEquals(Palette.Color expected, Palette.Color actual, String message) {
        assertEquals(expected.r, actual.r, message);
        assertEquals(expected.g, actual.g, message);
        assertEquals(expected.b, actual.b, message);
    }

    private static boolean colorsEqual(Palette.Color a, Palette.Color b) {
        return a.r == b.r && a.g == b.g && a.b == b.b;
    }

    /**
     * Minimal Level stub Ã¢â‚¬â€ 4 palettes, no geometry needed for palette cycling tests.
     */
    private static class StubLevel implements Level {
        private final Palette[] palettes = new Palette[4];

        StubLevel() {
            for (int i = 0; i < palettes.length; i++) {
                palettes[i] = new Palette();
            }
        }

        @Override public int getPaletteCount() { return palettes.length; }
        @Override public Palette getPalette(int index) { return palettes[index]; }
        @Override public int getPatternCount() { return 0; }
        @Override public com.openggf.level.Pattern getPattern(int index) { return null; }
        @Override public int getChunkCount() { return 0; }
        @Override public com.openggf.level.Chunk getChunk(int index) { return null; }
        @Override public int getBlockCount() { return 0; }
        @Override public com.openggf.level.Block getBlock(int index) { return null; }
        @Override public com.openggf.level.SolidTile getSolidTile(int index) { return null; }
        @Override public com.openggf.level.Map getMap() { return null; }
        @Override public java.util.List<com.openggf.level.objects.ObjectSpawn> getObjects() { return java.util.List.of(); }
        @Override public java.util.List<com.openggf.level.rings.RingSpawn> getRings() { return java.util.List.of(); }
        @Override public com.openggf.level.rings.RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return 0; }
        @Override public int getZoneIndex() { return 3; }
    }
}


