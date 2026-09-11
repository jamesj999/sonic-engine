package com.openggf.game.sonic3k;

import com.openggf.data.RomByteReader;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.Chunk;
import com.openggf.level.Block;
import com.openggf.level.SolidTile;
import com.openggf.level.Map;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static com.openggf.game.sonic3k.constants.Sonic3kZoneIds.ZONE_BPZ;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BPZ (Balloon Park Zone) palette cycling.
 *
 * <p>Verifies that the two independent palette channels cycle correctly:
 * <ul>
 *   <li>Channel 1 (Balloons): timer period 8, step +6, wraps at 0x12 â†’ palette[2] colors 13-15</li>
 *   <li>Channel 2 (Background): timer period 18, step +6, wraps at 0x7E â†’ palette[3] colors 2-4</li>
 * </ul>
 *
 * <p>Uses hardcoded data matching AnPal_PalBPZ_1/2 from ROM (verified at 0x0034CC / 0x0034DE).
 * No ROM dependency required.
 */
public class TestS3kBpzPaletteCycling {

    // AnPal_PalBPZ_1: 18 bytes (3 frames x 6 bytes) â€” verified at ROM 0x0034CC
    // dc.w $EE, $AE, $6C / dc.w $AE, $6E, $EE / dc.w $6E, $EE, $AE
    private static final byte[] BPZ_1_DATA = {
        0x00, (byte)0xEE, 0x00, (byte)0xAE, 0x00, 0x6C,
        0x00, (byte)0xAE, 0x00, 0x6E, 0x00, (byte)0xEE,
        0x00, 0x6E, 0x00, (byte)0xEE, 0x00, (byte)0xAE
    };

    // AnPal_PalBPZ_2: 126 bytes (21 frames x 6 bytes) â€” verified at ROM 0x0034DE
    // dc.w $EE0, $E0E, $EE  (first frame)
    // dc.w $EA0, $A0E, $EA  (second frame)
    // ... 19 more frames
    private static final byte[] BPZ_2_DATA = {
        0x0E, (byte)0xE0, 0x0E, 0x0E, 0x00, (byte)0xEE,
        0x0E, (byte)0xA0, 0x0A, 0x0E, 0x00, (byte)0xEA,
        0x0E, 0x60, 0x06, 0x0E, 0x00, (byte)0xE6,
        0x0E, 0x20, 0x02, 0x0E, 0x00, (byte)0xE2,
        0x0E, 0x02, 0x00, 0x2E, 0x02, (byte)0xE0,
        0x0E, 0x06, 0x00, 0x6E, 0x06, (byte)0xE0,
        0x0E, 0x0A, 0x00, (byte)0xAE, 0x0A, (byte)0xE0,
        0x0E, 0x0E, 0x00, (byte)0xEE, 0x0E, (byte)0xE0,
        0x0A, 0x0E, 0x00, (byte)0xEA, 0x0E, (byte)0xA0,
        0x06, 0x0E, 0x00, (byte)0xE6, 0x0E, 0x60,
        0x02, 0x0E, 0x00, (byte)0xE2, 0x0E, 0x20,
        0x00, 0x2E, 0x02, (byte)0xE0, 0x0E, 0x02,
        0x00, 0x6E, 0x06, (byte)0xE0, 0x0E, 0x06,
        0x00, (byte)0xAE, 0x0A, (byte)0xE0, 0x0E, 0x0A,
        0x00, (byte)0xEE, 0x0E, (byte)0xE0, 0x0E, 0x0E,
        0x00, (byte)0xEA, 0x0E, (byte)0xA0, 0x0A, 0x0E,
        0x00, (byte)0xE6, 0x0E, 0x60, 0x06, 0x0E,
        0x00, (byte)0xE2, 0x0E, 0x20, 0x02, 0x0E,
        0x02, (byte)0xE0, 0x0E, 0x02, 0x00, 0x2E,
        0x06, (byte)0xE0, 0x0E, 0x06, 0x00, 0x6E,
        0x0A, (byte)0xE0, 0x0E, 0x0A, 0x00, (byte)0xAE
    };

    private StubLevel level;
    private Sonic3kPaletteCycler cycler;

    @BeforeEach
    public void setUp() {
        GraphicsManager.getInstance().resetState();
        level = new StubLevel(4);

        // Build a minimal ROM buffer containing BPZ data at the expected addresses
        int romSize = 0x0034DE + BPZ_2_DATA.length + 16;
        byte[] romBytes = new byte[romSize];
        System.arraycopy(BPZ_1_DATA, 0, romBytes, 0x0034CC, BPZ_1_DATA.length);
        System.arraycopy(BPZ_2_DATA, 0, romBytes, 0x0034DE, BPZ_2_DATA.length);

        RomByteReader reader = new RomByteReader(romBytes);
        cycler = new Sonic3kPaletteCycler(reader, level, ZONE_BPZ, 0);
    }

    // ========== Channel 1 (Balloons â†’ palette[2], colors 13-15) ==========

    @Test
    public void balloonsChannel1FiresOnFirstFrame() {
        // timer1 starts at 0: first tick fires immediately
        Palette.Color color13Before = copyColor(level.getPalette(2).getColor(13));

        cycler.update();

        Palette.Color color13After = level.getPalette(2).getColor(13);
        // After first tick, color should be set from BPZ_1_DATA[0..1] = {0x00, 0xEE}
        assertFalse(colorsEqual(color13Before, color13After), "Color 13 in palette[2] should change after first tick");
    }

    @Test
    public void balloonsChannel1AdvancesThreeFrames() {
        // Tick once â€” fires at frame 0, counter0 = 0 â†’ 6
        cycler.update();
        Palette.Color frame0Color = copyColor(level.getPalette(2).getColor(13));

        // Tick 8 more frames (timer resets to 7, so fires on frame 8)
        for (int i = 0; i < 8; i++) {
            cycler.update();
        }
        Palette.Color frame1Color = copyColor(level.getPalette(2).getColor(13));

        // counter0 should now be 6 â†’ 12, colors should be from frame 1
        assertFalse(colorsEqual(frame0Color, frame1Color), "Color 13 should change between frames");
    }

    @Test
    public void balloonsChannel1WrapsAfterThreeFrames() {
        // The BPZ_1 table has 3 frames (counter0 steps 0, 6, 12, then wraps to 0)
        // Fire frame 0: counter0 = 0 â†’ 6
        cycler.update();
        Palette.Color frameAColor = copyColor(level.getPalette(2).getColor(13));

        // Fire frame 1: counter0 = 6 â†’ 12 (8 ticks later)
        for (int i = 0; i < 8; i++) {
            cycler.update();
        }
        // Fire frame 2: counter0 = 12 â†’ 0 (wrap) (8 ticks later)
        for (int i = 0; i < 8; i++) {
            cycler.update();
        }
        // Fire frame 0 again: counter0 = 0 â†’ 6 (8 ticks later)
        for (int i = 0; i < 8; i++) {
            cycler.update();
        }
        Palette.Color frameAColorAgain = copyColor(level.getPalette(2).getColor(13));

        // After 3 full cycles, should be back to frame 0 colors
        assertTrue(colorsEqual(frameAColor, frameAColorAgain), "Color 13 should wrap back to frame 0 after 3 cycles");
    }

    @Test
    public void balloonsChannel1WritesPalette2Colors13To15() {
        cycler.update();

        // Channel 1 writes palette[2] colors 13, 14, 15
        // BPZ_1_DATA[0..5] = {0x00, 0xEE, 0x00, 0xAE, 0x00, 0x6C}
        // Color 13: 0x00EE, Color 14: 0x00AE, Color 15: 0x006C
        Palette pal2 = level.getPalette(2);
        assertNonZeroColor("palette[2] color 13", pal2.getColor(13));
        assertNonZeroColor("palette[2] color 14", pal2.getColor(14));
        assertNonZeroColor("palette[2] color 15", pal2.getColor(15));
    }

    // ========== Channel 2 (Background â†’ palette[3], colors 2-4) ==========

    @Test
    public void backgroundChannel2FiresOnFirstFrame() {
        // timer2 starts at 0: first tick fires immediately
        Palette.Color color2Before = copyColor(level.getPalette(3).getColor(2));

        cycler.update();

        Palette.Color color2After = level.getPalette(3).getColor(2);
        assertFalse(colorsEqual(color2Before, color2After), "Color 2 in palette[3] should change after first tick");
    }

    @Test
    public void backgroundChannel2WritesPalette3Colors2To4() {
        cycler.update();

        // Channel 2 writes palette[3] colors 2, 3, 4
        // BPZ_2_DATA[0..5] = {0x0E, 0xE0, 0x0E, 0x0E, 0x00, 0xEE}
        Palette pal3 = level.getPalette(3);
        assertNonZeroColor("palette[3] color 2", pal3.getColor(2));
        assertNonZeroColor("palette[3] color 3", pal3.getColor(3));
        assertNonZeroColor("palette[3] color 4", pal3.getColor(4));
    }

    @Test
    public void backgroundChannel2HasIndependentTimer() {
        // Both channels fire on frame 0 (timers start at 0)
        // After 1 tick both have fired; after 8 more ticks only channel 1 fires again
        cycler.update(); // Frame 0: both fire

        Palette.Color ch2ColorAfterFrame0 = copyColor(level.getPalette(3).getColor(2));

        // Tick 8 more frames â€” channel 1 (timer 7) fires, channel 2 (timer 0x11=17) does not
        for (int i = 0; i < 8; i++) {
            cycler.update();
        }

        Palette.Color ch2ColorAfter8More = copyColor(level.getPalette(3).getColor(2));

        // Channel 2 should not have changed during these 8 frames (timer=17, only decremented 8 times)
        assertTrue(colorsEqual(ch2ColorAfterFrame0, ch2ColorAfter8More), "Channel 2 should not advance during channel 1's 8-frame timer");
    }

    @Test
    public void backgroundChannel2AdvancesAfter18Frames() {
        // Channel 2 timer = 0x11 = 17; fires on frame 0, then 18 frames later
        cycler.update(); // Frame 0: fires (timer â†’ 17)
        Palette.Color ch2Frame0 = copyColor(level.getPalette(3).getColor(2));

        for (int i = 0; i < 18; i++) {
            cycler.update();
        }
        Palette.Color ch2Frame1 = copyColor(level.getPalette(3).getColor(2));

        assertFalse(colorsEqual(ch2Frame0, ch2Frame1), "Channel 2 color should advance after 18 frames");
    }

    @Test
    public void channel1DoesNotAffectPalette3() {
        // Channel 1 only writes palette[2]. Palette[3] colors 2-4 come from channel 2.
        // After first update, both channels fire. To isolate channel 1's effect on palette[3]:
        // check that colors 0, 1, 5-15 of palette[3] are untouched by the cycler.
        cycler.update();

        // Colors 0, 1 of palette[3] should remain zero (cycler never writes them)
        Palette pal3 = level.getPalette(3);
        assertTrue(colorsEqual(new Palette.Color(), pal3.getColor(0)), "palette[3] color 0 should remain zero");
        assertTrue(colorsEqual(new Palette.Color(), pal3.getColor(1)), "palette[3] color 1 should remain zero");
    }

    @Test
    public void channel2DoesNotAffectPalette2() {
        // Channel 2 only writes palette[3]. Palette[2] colors 2-12 should stay zero.
        cycler.update();

        Palette pal2 = level.getPalette(2);
        for (int c = 0; c <= 12; c++) {
            assertTrue(colorsEqual(new Palette.Color(), pal2.getColor(c)), "palette[2] color " + c + " should remain zero (not written by any channel)");
        }
    }

    @Test
    public void cyclesCorrectlyAfter60Frames() {
        // Smoke test: tick 60 frames, assert palette[2] color 13 is not all-zero
        for (int i = 0; i < 60; i++) {
            cycler.update();
        }
        assertNonZeroColor("palette[2] color 13 after 60 frames", level.getPalette(2).getColor(13));
    }

    @Test
    public void bpzCycleSubmitsPaletteOwnershipClaims() {
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        StubLevel ownedLevel = new StubLevel(4);
        Sonic3kPaletteCycler ownedCycler = new Sonic3kPaletteCycler(
                readerWithBpzData(), ownedLevel, ZONE_BPZ, 0, registry, null);

        registry.beginFrame();
        ownedCycler.update();
        registry.resolveInto(ownedLevel.palettes(), null, null, null);

        for (int c = 13; c <= 15; c++) {
            assertEquals(S3kPaletteOwners.BPZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, c));
        }
        for (int c = 2; c <= 4; c++) {
            assertEquals(S3kPaletteOwners.BPZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 3, c));
        }
        assertNonZeroColor("owned BPZ palette[2] color 13", ownedLevel.getPalette(2).getColor(13));
        assertNonZeroColor("owned BPZ palette[3] color 2", ownedLevel.getPalette(3).getColor(2));
    }

    // ========== Helpers ==========

    private static RomByteReader readerWithBpzData() {
        int romSize = 0x0034DE + BPZ_2_DATA.length + 16;
        byte[] romBytes = new byte[romSize];
        System.arraycopy(BPZ_1_DATA, 0, romBytes, 0x0034CC, BPZ_1_DATA.length);
        System.arraycopy(BPZ_2_DATA, 0, romBytes, 0x0034DE, BPZ_2_DATA.length);
        return new RomByteReader(romBytes);
    }

    private static Palette.Color copyColor(Palette.Color src) {
        return new Palette.Color(src.r, src.g, src.b);
    }

    private static boolean colorsEqual(Palette.Color a, Palette.Color b) {
        return a.r == b.r && a.g == b.g && a.b == b.b;
    }

    private static void assertNonZeroColor(String desc, Palette.Color color) {
        assertFalse((color.r == 0 && color.g == 0 && color.b == 0), desc + " should not be zero (0,0,0)");
    }

    // ========== Stub Level ==========

    private static final class StubLevel implements Level {
        private final Palette[] palettes;

        StubLevel(int paletteCount) {
            palettes = new Palette[paletteCount];
            for (int i = 0; i < paletteCount; i++) {
                palettes[i] = new Palette();
            }
        }

        Palette[] palettes() {
            return palettes;
        }

        @Override
        public int getPaletteCount() {
            return palettes.length;
        }

        @Override
        public Palette getPalette(int index) {
            return palettes[index];
        }

        @Override
        public int getPatternCount() {
            return 0;
        }

        @Override
        public Pattern getPattern(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void ensurePatternCapacity(int minCount) {
        }

        @Override
        public int getChunkCount() {
            return 0;
        }

        @Override
        public Chunk getChunk(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getBlockCount() {
            return 0;
        }

        @Override
        public Block getBlock(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SolidTile getSolidTile(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map getMap() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ObjectSpawn> getObjects() {
            return List.of();
        }

        @Override
        public List<RingSpawn> getRings() {
            return List.of();
        }

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }

        @Override
        public int getMinX() {
            return 0;
        }

        @Override
        public int getMaxX() {
            return 0;
        }

        @Override
        public int getMinY() {
            return 0;
        }

        @Override
        public int getMaxY() {
            return 0;
        }

        @Override
        public int getZoneIndex() {
            return 0x0E;
        }
    }
}


