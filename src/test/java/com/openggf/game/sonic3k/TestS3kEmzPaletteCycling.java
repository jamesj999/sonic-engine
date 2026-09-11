package com.openggf.game.sonic3k;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.data.RomByteReader;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
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
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that S3K EMZ (Endless Mine Zone, competition) palette cycling logic is correct.
 *
 * <p>The ROM's {@code AnPal_EMZ} routine drives two independent channels:
 * <ol>
 *   <li><b>Emerald glow</b> (timer period 8, reset to 7): {@code AnPal_PalEMZ_1} via {@code 4(a0,d0.w)}
 *       Ã¢â€ â€™ {@code Normal_palette_line_3+$1C} = palette[2] color 14.
 *       Counter0 steps +2, wraps at {@code 0x3C} (30 frames per cycle).</li>
 *   <li><b>Background</b> (timer period 32, reset to 0x1F; {@code Palette_cycle_counters+$08}):
 *       {@code AnPal_PalEMZ_2} via {@code (a0,d0.w)} Ã¢â€ â€™ {@code Normal_palette_line_4+$12} =
 *       palette[3] colors 9-10 ({@code move.l} = 2 colors).
 *       Counter ({@code counters+$02}) steps +4, wraps at {@code 0x34} (13 frames per cycle).</li>
 * </ol>
 *
 * <p>This test validates that ROM data is present at the expected addresses and that the
 * palette cycle logic produces value changes when applied against a mock Level/Palette,
 * without requiring a full EMZ level load (competition zones are not yet in the zone registry).
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kEmzPaletteCycling {
    private RomByteReader reader;

    @BeforeEach
    public void setUp() throws java.io.IOException {
        reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());
    }

    /**
     * Verifies AnPal_PalEMZ_1 ROM data: 60 bytes of emerald green color values
     * at the expected address.
     */
    @Test
    public void emz1DataPresentAndNonZero() {
        byte[] data = reader.slice(Sonic3kConstants.ANPAL_EMZ1_ADDR, Sonic3kConstants.ANPAL_EMZ1_SIZE);
        assertNotNull(data, "EMZ glow data must be present");
        assertTrue(data.length == 64, "EMZ glow data must be 64 bytes");

        // First word at offset 4 (first value actually used by ROM with 4(a0,d0.w)) should be non-zero.
        // From disassembly: AnPal_PalEMZ_1 starts with dc.w 6 ($0006 = green component).
        int firstWord = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        assertTrue(firstWord != 0, "First used word at offset 4 should be non-zero (emerald color component)");
    }

    /**
     * Verifies AnPal_PalEMZ_2 ROM data: 52 bytes of background color pairs.
     */
    @Test
    public void emz2DataPresentAndNonZero() {
        byte[] data = reader.slice(Sonic3kConstants.ANPAL_EMZ2_ADDR, Sonic3kConstants.ANPAL_EMZ2_SIZE);
        assertNotNull(data, "EMZ background data must be present");
        assertTrue(data.length == 52, "EMZ background data must be 52 bytes");

        // From disassembly: AnPal_PalEMZ_2 starts with dc.w 0,$E ($0000,$000E).
        // The longword at offset 0 should equal 0x0000_000E.
        int longword = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                     | ((data[2] & 0xFF) << 8)  |  (data[3] & 0xFF);
        assertTrue(longword == 0x0000000E, "First longword of EMZ2 should be 0x0000000E (first two colors: 0 and 0x0E)");
    }

    /**
     * Verifies that the emerald glow cycle (Channel 1) modifies palette[2] color 14 over time.
     *
     * <p>Uses the same ROM data and cycle logic as {@code EmzCycle} but drives it directly
     * via a mock Level/Palette, bypassing the need for a full level load.
     */
    @Test
    public void emeraldGlowCycleModifiesPaletteLine3Color14() {
        byte[] glowData = reader.slice(Sonic3kConstants.ANPAL_EMZ1_ADDR, Sonic3kConstants.ANPAL_EMZ1_SIZE);
        byte[] bgData   = reader.slice(Sonic3kConstants.ANPAL_EMZ2_ADDR, Sonic3kConstants.ANPAL_EMZ2_SIZE);

        Palette pal2 = new Palette();
        Palette pal3 = new Palette();

        // Simulate the Sonic3kPaletteCycler.EmzCycle glow channel:
        // timer period = 7 (reset to 7), counter step = +2, wrap at 0x3C
        // ROM: move.w 4(a0,d0.w) Ã¢â€ â€™ palette_line_3+$1C = palette[2] color 14
        int glowTimer = 0;
        int glowCounter = 0;
        int initialR = pal2.getColor(14).r & 0xFF;
        int initialG = pal2.getColor(14).g & 0xFF;
        int initialB = pal2.getColor(14).b & 0xFF;

        boolean colorChanged = false;
        for (int frame = 0; frame < 60; frame++) {
            if (glowTimer > 0) {
                glowTimer--;
            } else {
                glowTimer = 7;
                int d0 = glowCounter;
                glowCounter += 2;
                if (glowCounter >= 0x3C) {
                    glowCounter = 0;
                }
                pal2.getColor(14).fromSegaFormat(glowData, 4 + d0);
            }

            int r = pal2.getColor(14).r & 0xFF;
            int g = pal2.getColor(14).g & 0xFF;
            int b = pal2.getColor(14).b & 0xFF;
            if (r != initialR || g != initialG || b != initialB) {
                colorChanged = true;
                break;
            }
        }

        assertTrue(colorChanged, "Expected palette[2] color 14 to change over 60 frames, "
                + "proving AnPal_PalEMZ_1 data is valid and cycle logic is correct. "
                + "Initial RGB=(" + initialR + "," + initialG + "," + initialB + ")");
    }

    /**
     * Verifies that the background cycle (Channel 2) modifies palette[3] colors 9-10.
     *
     * <p>Timer period = 0x1F (32), counter step = +4, wrap at 0x34 (13 frames/cycle).
     * ROM: move.l (a0,d0.w) Ã¢â€ â€™ palette_line_4+$12 = palette[3] colors 9-10.
     */
    @Test
    public void backgroundCycleModifiesPaletteLine4Colors9to10() {
        byte[] bgData = reader.slice(Sonic3kConstants.ANPAL_EMZ2_ADDR, Sonic3kConstants.ANPAL_EMZ2_SIZE);

        Palette pal3 = new Palette();

        int bgTimer = 0;
        int bgCounter = 0;
        int initialR = pal3.getColor(9).r & 0xFF;
        int initialG = pal3.getColor(9).g & 0xFF;
        int initialB = pal3.getColor(9).b & 0xFF;

        boolean colorChanged = false;
        for (int frame = 0; frame < 500; frame++) {
            if (bgTimer > 0) {
                bgTimer--;
            } else {
                bgTimer = 0x1F;
                int d0 = bgCounter;
                bgCounter += 4;
                if (bgCounter >= 0x34) {
                    bgCounter = 0;
                }
                pal3.getColor(9).fromSegaFormat(bgData, d0);
                pal3.getColor(10).fromSegaFormat(bgData, d0 + 2);
            }

            int r = pal3.getColor(9).r & 0xFF;
            int g = pal3.getColor(9).g & 0xFF;
            int b = pal3.getColor(9).b & 0xFF;
            if (r != initialR || g != initialG || b != initialB) {
                colorChanged = true;
                break;
            }
        }

        assertTrue(colorChanged, "Expected palette[3] color 9 to change over 500 frames, "
                + "proving AnPal_PalEMZ_2 data is valid and cycle logic is correct. "
                + "Initial RGB=(" + initialR + "," + initialG + "," + initialB + ")");
    }

    // ========== Direct cycler tests with specific color value assertions ==========

    /**
     * Verifies that the Sonic3kPaletteCycler for EMZ applies specific color values
     * on the first tick. The emerald glow channel writes palette[2] color 14 from
     * ROM offset 4 of the glow table (ROM uses 4(a0,d0.w) addressing).
     *
     * <p>ROM frame 0 at offset 4: $0006 Ã¢â€ â€™ Sega format: R=3 (bits 3-1 of byte 1),
     * G=0, B=0 Ã¢â€ â€™ scaled R~109, G=0, B=0. The "emerald glow" table starts dim red
     * and cycles through green tones as it progresses.
     */
    @Test
    public void cyclerFirstTickAppliesEmeraldGlowColor() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        EmzStubLevel level = new EmzStubLevel();
        RomByteReader cyclerReader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        // EMZ is ROM zone $12: OffsAnPal entries 36/37
        // (skdisasm/sonic3k.asm:3154-3155), indexed zone*2 + act.
        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(cyclerReader, level, 0x12, 0);

        cycler.update();

        // Emerald glow: palette[2] color 14 should be set from ROM frame 0 data.
        Palette.Color color14 = level.getPalette(2).getColor(14);
        int r = color14.r & 0xFF;
        int g = color14.g & 0xFF;
        int b = color14.b & 0xFF;
        assertTrue(r > 0 || g > 0 || b > 0, "EMZ emerald glow color 14 should be non-zero after first tick, got ("
                + r + "," + g + "," + b + ")");
        // ROM $0006: R=3 Ã¢â€ â€™ scaled ~109, should be > 50
        assertTrue(r > 50, "EMZ emerald glow first frame should have R > 50 from $0006, got R=" + r);
    }

    /**
     * Verifies the background cycle applies specific values on its first fire.
     * Channel 2 has timer period 0x1F (32), so it fires on the very first tick
     * (timer starts at 0). ROM frame 0 is $0000,$000E Ã¢â€ â€™ color 9 = black, color 10 = red.
     */
    @Test
    public void cyclerFirstTickAppliesBackgroundColors() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        EmzStubLevel level = new EmzStubLevel();
        RomByteReader cyclerReader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(cyclerReader, level, 0x12, 0);

        cycler.update();

        // Background: palette[3] colors 9-10
        // ROM frame 0: $0000 (black), $000E (red: r=7,g=0,b=0)
        Palette.Color color9 = level.getPalette(3).getColor(9);
        Palette.Color color10 = level.getPalette(3).getColor(10);

        // Color 10 ($000E): r3=7 Ã¢â€ â€™ r=255, g=0, b=0
        int r10 = color10.r & 0xFF;
        int g10 = color10.g & 0xFF;
        int b10 = color10.b & 0xFF;
        assertTrue(r10 > 200, "EMZ background color 10 should be red ($000E): R > 200, got R=" + r10);
        assertTrue(g10 == 0, "EMZ background color 10 should have G=0, got G=" + g10);
        assertTrue(b10 == 0, "EMZ background color 10 should have B=0, got B=" + b10);
    }

    /**
     * Verifies that the emerald glow cycle produces multiple distinct values,
     * confirming the full table is being traversed.
     */
    @Test
    public void emeraldGlowCycleProducesMultipleDistinctValues() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        EmzStubLevel level = new EmzStubLevel();
        RomByteReader cyclerReader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(cyclerReader, level, 0x12, 0);

        int distinctCount = 0;
        int prevR = -1, prevG = -1, prevB = -1;

        // Glow channel: timer period 7, 30 entries (wraps at 0x3C).
        // In 240 ticks (30 Ãƒâ€” 8), it fires 30 times, cycling the entire table.
        for (int frame = 0; frame < 240; frame++) {
            cycler.update();
            Palette.Color c14 = level.getPalette(2).getColor(14);
            int r = c14.r & 0xFF;
            int g = c14.g & 0xFF;
            int b = c14.b & 0xFF;
            if (r != prevR || g != prevG || b != prevB) {
                distinctCount++;
                prevR = r;
                prevG = g;
                prevB = b;
            }
        }

        assertTrue(distinctCount >= 3, "EMZ emerald glow should produce at least 3 distinct values over 240 frames, got "
                + distinctCount);
    }

    @Test
    public void emzCycleSubmitsPaletteOwnershipClaims() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        EmzStubLevel level = new EmzStubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader cyclerReader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(cyclerReader, level, 0x12, 0, registry, null);

        registry.beginFrame();
        cycler.update();
        registry.resolveInto(level.palettes(), null, null, null);

        assertEquals(S3kPaletteOwners.EMZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, 14));
        assertEquals(S3kPaletteOwners.EMZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 3, 9));
        assertEquals(S3kPaletteOwners.EMZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 3, 10));
        assertTrue((level.getPalette(2).getColor(14).r & 0xFF) > 50,
                "EMZ owned glow color should resolve from ROM frame 0");
        assertTrue((level.getPalette(3).getColor(10).r & 0xFF) > 200,
                "EMZ owned background color should resolve from ROM frame 0");
    }

    /**
     * Minimal Level stub for EMZ palette cycling tests.
     */
    private static final class EmzStubLevel implements Level {
        private final Palette[] palettes = new Palette[4];

        EmzStubLevel() {
            for (int i = 0; i < palettes.length; i++) {
                palettes[i] = new Palette();
            }
        }

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
        @Override public int getZoneIndex() { return 0x12; }
    }
}


