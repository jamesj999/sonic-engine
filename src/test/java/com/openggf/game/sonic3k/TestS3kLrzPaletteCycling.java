package com.openggf.game.sonic3k;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.animation.AnimatedPatternManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that S3K LRZ (Lava Reef Zone, zone 0x09) palette cycling is active
 * and modifies palette colors over time.
 *
 * <p>The ROM's {@code AnPal_LRZ1} routine uses three channels:
 * <ul>
 *   <li>Channel A (shared): {@code AnPal_PalLRZ12_1} Ã¢â€ â€™ palette[2] colors 1-4, timer period 16.</li>
 *   <li>Channel B (shared): {@code AnPal_PalLRZ12_2} Ã¢â€ â€™ palette[3] colors 1-2, same shared timer.</li>
 *   <li>Channel C (Act 1 only): {@code AnPal_PalLRZ1_3} Ã¢â€ â€™ palette[2] color 11, timer period 8.</li>
 * </ul>
 *
 * <p>This test loads LRZ Act 1 (zone 0x09, act 0), ticks the animation manager,
 * and verifies that palette[2] color 1 changes over 100 frames (the timer fires every 16 frames).
 * Note: in production the palette cycler runs inside {@code LevelManager.drawWithSpritePriority()}
 * (the draw path), so headless tests must manually tick the animation manager since draw is never
 * called.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kLrzPaletteCycling {
    private static final int ZONE_LRZ = 0x09;
    private static final int ACT_1 = 0;
    private static final int ACT_2 = 1;

    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_LRZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    /**
     * Ticks the animation manager once. In production this runs inside
     * drawWithSpritePriority(); headless tests must call it explicitly.
     */
    private void tickAnimation() {
        AnimatedPatternManager apm = GameServices.level().getAnimatedPatternManager();
        if (apm != null) {
            apm.update();
        }
    }

    @Test
    public void channelACycleModifiesPaletteLine3Color1() {
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        Palette pal2 = level.getPalette(2);
        assertNotNull(pal2, "Palette line 3 (index 2) must exist");

        // Record initial lava/rock color (palette line 3, color 1)
        Palette.Color color1 = pal2.getColor(1);
        int initialR = color1.r & 0xFF;
        int initialG = color1.g & 0xFF;
        int initialB = color1.b & 0xFF;

        // Channel A (shared timer) fires every 16 frames. After 100 frames (6+ ticks)
        // we expect at least one color change. The table has 16 frames of unique data.
        boolean colorChanged = false;
        for (int frame = 0; frame < 100; frame++) {
            fixture.stepIdleFrames(1);
            tickAnimation();

            int r = color1.r & 0xFF;
            int g = color1.g & 0xFF;
            int b = color1.b & 0xFF;
            if (r != initialR || g != initialG || b != initialB) {
                colorChanged = true;
                break;
            }
        }

        assertTrue(colorChanged, "Expected palette[2] color 1 (LRZ lava channel A) to change over 100 frames, "
                + "proving AnPal_PalLRZ12_1 cycling is active. "
                + "Initial RGB=(" + initialR + "," + initialG + "," + initialB + ")");
    }

    // ========== Direct cycler tests with specific color value assertions ==========

    /**
     * Verifies that channels A+B (shared timer) apply specific ROM values on the first tick.
     * Timer starts at 0, so the first update fires immediately.
     * Channel A: palette[2] colors 1-4 from LRZ12_1 table frame 0 (lava colors: warm).
     * Channel B: palette[3] colors 1-2 from LRZ12_2 table frame 0 (crystal colors).
     */
    @Test
    public void channelABFirstTickAppliesLavaAndCrystalColors() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        LrzStubLevel stubLevel = new LrzStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_LRZ, ACT_1);

        cycler.update();

        // Channel A: palette[2] colors 1-4 (lava colors should be warm tones)
        Palette pal2 = stubLevel.getPalette(2);
        for (int c = 1; c <= 4; c++) {
            int r = pal2.getColor(c).r & 0xFF;
            int g = pal2.getColor(c).g & 0xFF;
            int b = pal2.getColor(c).b & 0xFF;
            assertTrue(r > 0 || g > 0 || b > 0, "LRZ lava color " + c + " should be non-zero after first tick, got ("
                    + r + "," + g + "," + b + ")");
        }

        // Lava colors should be warm: at least one color should have R >= B
        int r1 = pal2.getColor(1).r & 0xFF;
        int b1 = pal2.getColor(1).b & 0xFF;
        assertTrue(r1 >= b1, "LRZ lava color 1 should be warm (R >= B), got R=" + r1 + " B=" + b1);

        // Channel B: palette[3] colors 1-2 (crystal)
        Palette pal3 = stubLevel.getPalette(3);
        for (int c = 1; c <= 2; c++) {
            int r = pal3.getColor(c).r & 0xFF;
            int g = pal3.getColor(c).g & 0xFF;
            int b = pal3.getColor(c).b & 0xFF;
            assertTrue(r > 0 || g > 0 || b > 0, "LRZ crystal color " + c + " should be non-zero after first tick, got ("
                    + r + "," + g + "," + b + ")");
        }
    }

    /**
     * Verifies that channel C (Act 1 only, independent timer period 8) applies palette[2]
     * color 11 from ROM data. This is the lava glow cycling color.
     */
    @Test
    public void channelCFirstTickAppliesLavaGlowColor() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        LrzStubLevel stubLevel = new LrzStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_LRZ, ACT_1);

        cycler.update();

        // Channel C writes palette[2] color 11
        Palette.Color color11 = stubLevel.getPalette(2).getColor(11);
        int r = color11.r & 0xFF;
        int g = color11.g & 0xFF;
        int b = color11.b & 0xFF;
        assertTrue(r > 0 || g > 0 || b > 0, "LRZ lava glow color 11 should be non-zero after first tick, got ("
                + r + "," + g + "," + b + ")");
    }

    @Test
    public void lrz1CycleSubmitsPaletteOwnershipClaims() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        LrzStubLevel stubLevel = new LrzStubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(
                reader, stubLevel, ZONE_LRZ, ACT_1, registry, null);

        cycler.update();

        for (int c = 1; c <= 4; c++) {
            assertEquals(S3kPaletteOwners.LRZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, c));
        }
        assertEquals(S3kPaletteOwners.LRZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, 11));
        assertEquals(S3kPaletteOwners.LRZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 3, 1));
        assertEquals(S3kPaletteOwners.LRZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 3, 2));
    }

    @Test
    public void lrz2CycleSubmitsPaletteOwnershipClaims() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        LrzStubLevel stubLevel = new LrzStubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(
                reader, stubLevel, ZONE_LRZ, ACT_2, registry, null);

        cycler.update();

        for (int c = 1; c <= 4; c++) {
            assertEquals(S3kPaletteOwners.LRZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, c));
        }
        for (int c : new int[] {1, 2, 11, 12, 13, 14}) {
            assertEquals(S3kPaletteOwners.LRZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 3, c),
                    "LRZ2 palette line 4 color " + c + " should be owned by the zone cycle");
        }
    }

    /**
     * Verifies channel A produces multiple distinct lava color values over a full cycle.
     * Channel A: 16 frames (step +8, wrap 0x80), timer period 16 Ã¢â€ â€™ fires every 16 ticks.
     * Over 256 ticks (16 Ãƒâ€” 16), the entire table is traversed.
     */
    @Test
    public void channelAProducesMultipleDistinctLavaValues() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        LrzStubLevel stubLevel = new LrzStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_LRZ, ACT_1);

        int distinctCount = 0;
        int prevR = -1, prevG = -1, prevB = -1;

        for (int frame = 0; frame < 256; frame++) {
            cycler.update();
            Palette.Color c1 = stubLevel.getPalette(2).getColor(1);
            int r = c1.r & 0xFF;
            int g = c1.g & 0xFF;
            int b = c1.b & 0xFF;
            if (r != prevR || g != prevG || b != prevB) {
                distinctCount++;
                prevR = r;
                prevG = g;
                prevB = b;
            }
        }

        assertTrue(distinctCount >= 3, "LRZ channel A should produce at least 3 distinct lava color 1 values "
                + "over 256 frames, got " + distinctCount);
    }

    /**
     * Verifies channel B produces at least 2 distinct crystal values. Channel B has 7 frames
     * (step +4, wrap 0x1C), shared timer with channel A (period 16).
     */
    @Test
    public void channelBProducesMultipleDistinctCrystalValues() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        LrzStubLevel stubLevel = new LrzStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_LRZ, ACT_1);

        int distinctCount = 0;
        int prevR = -1, prevG = -1, prevB = -1;

        for (int frame = 0; frame < 256; frame++) {
            cycler.update();
            Palette.Color c1 = stubLevel.getPalette(3).getColor(1);
            int r = c1.r & 0xFF;
            int g = c1.g & 0xFF;
            int b = c1.b & 0xFF;
            if (r != prevR || g != prevG || b != prevB) {
                distinctCount++;
                prevR = r;
                prevG = g;
                prevB = b;
            }
        }

        assertTrue(distinctCount >= 2, "LRZ channel B should produce at least 2 distinct crystal color 1 values "
                + "over 256 frames, got " + distinctCount);
    }

    /**
     * Minimal Level stub for direct LRZ palette cycling tests.
     */
    private static final class LrzStubLevel implements Level {
        private final Palette[] palettes = new Palette[4];

        LrzStubLevel() {
            for (int i = 0; i < palettes.length; i++) {
                palettes[i] = new Palette();
            }
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
        @Override public int getZoneIndex() { return ZONE_LRZ; }
    }
}


