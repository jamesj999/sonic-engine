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
 * Validates that S3K AIZ Act 2 palette cycling is active and modifies colors over time.
 *
 * <p>The ROM's {@code AnPal_AIZ2} routine cycles fire/torch colors on palette line 4
 * (engine index 3), color 1, every 2 frames via {@code AnPal_PalAIZ2_4/5}. Without this
 * cycling, fire {@code AnimatedStillSprite}s render green (vegetation palette) instead of
 * orange/red fire colors.
 *
 * <p>This test loads AIZ Act 2, ticks the animation manager, and verifies palette colors
 * change over time. Note: in production the palette cycler runs inside
 * {@code LevelManager.drawWithSpritePriority()} (the draw path), so headless tests must
 * manually tick the animation manager since draw is never called.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz2PaletteCycling {
    private static final int ZONE_AIZ = 0;
    private static final int ACT_2 = 1;

    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_2);
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
    public void torchGlowCycleModifiesPaletteLine4Color1() {
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        Palette pal3 = level.getPalette(3);
        assertNotNull(pal3, "Palette line 4 (index 3) must exist");

        // Record initial torch color (palette line 4, color 1)
        Palette.Color color1 = pal3.getColor(1);
        int initialR = color1.r & 0xFF;
        int initialG = color1.g & 0xFF;
        int initialB = color1.b & 0xFF;

        // Step enough frames for the torch cycle to advance.
        // The torch cycle ticks every 2 frames with 26 entries (0x34/2 = 26),
        // so 60 frames covers more than one full cycle.
        boolean colorChanged = false;
        for (int frame = 0; frame < 60; frame++) {
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

        assertTrue(colorChanged, "Expected palette[3] color 1 (torch glow) to change over 60 frames, "
                + "proving AnPal_PalAIZ2_4/5 cycling is active. "
                + "Initial RGB=(" + initialR + "," + initialG + "," + initialB + ")");
    }

    @Test
    public void waterCycleModifiesPaletteLine4Colors12to15() {
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        Palette pal3 = level.getPalette(3);

        // Record initial water color (palette line 4, color 12)
        Palette.Color color12 = pal3.getColor(12);
        int initialR = color12.r & 0xFF;
        int initialG = color12.g & 0xFF;
        int initialB = color12.b & 0xFF;

        // The water cycle ticks every 6 frames with 4 entries,
        // so 30 frames covers more than one full cycle.
        boolean colorChanged = false;
        for (int frame = 0; frame < 30; frame++) {
            fixture.stepIdleFrames(1);
            tickAnimation();

            int r = color12.r & 0xFF;
            int g = color12.g & 0xFF;
            int b = color12.b & 0xFF;
            if (r != initialR || g != initialG || b != initialB) {
                colorChanged = true;
                break;
            }
        }

        assertTrue(colorChanged, "Expected palette[3] color 12 (water cycle) to change over 30 frames, "
                + "proving AnPal_PalAIZ2_1 cycling is active. "
                + "Initial RGB=(" + initialR + "," + initialG + "," + initialB + ")");
    }

    // ========== Direct cycler tests with specific color value assertions ==========

    /**
     * Verifies that the torch glow cycle (AnPal_PalAIZ2_4) applies correct ROM values
     * on the first tick. Uses Sonic3kPaletteCycler directly with a StubLevel, bypassing
     * full level load. Timer starts at 0 so the first update fires immediately.
     *
     * <p>The torch pre-fire table writes palette[3] color 1 from ROM data frame 0.
     * MD palette: 3 bits per channel (0-7) scaled to 0-255. Torch colors are warm
     * (fire tones with high R, moderate G, low B).
     */
    @Test
    public void torchGlowFirstTickAppliesRomValues() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        Aiz2StubLevel stubLevel = new Aiz2StubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_AIZ, ACT_2);

        // First tick fires immediately (timer=0), applying frame 0 of torch table.
        cycler.update();

        Palette.Color color1 = stubLevel.getPalette(3).getColor(1);
        int r = color1.r & 0xFF;
        int g = color1.g & 0xFF;
        int b = color1.b & 0xFF;

        // Torch glow color must be non-zero and warm (fire palette: high R, some G, low B)
        assertTrue(r > 0, "Torch color 1 should have R > 0 after first tick, got " + r);
        assertTrue(r >= g, "Torch fire color should have R >= G (warm tone), got R=" + r + " G=" + g);
        assertTrue(r >= b, "Torch fire color should have R >= B (warm tone), got R=" + r + " B=" + b);
    }

    @Test
    public void torchGlowCycleSubmitsPaletteOwnershipClaim() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        Aiz2StubLevel stubLevel = new Aiz2StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(
                reader, stubLevel, ZONE_AIZ, ACT_2, registry, null);

        cycler.update();

        assertEquals(S3kPaletteOwners.AIZ2_TORCH_CYCLE,
                registry.ownerAt(PaletteSurface.NORMAL, 3, 1));
    }

    @Test
    public void waterCycleSubmitsPaletteOwnershipClaims() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        Aiz2StubLevel stubLevel = new Aiz2StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(
                reader, stubLevel, ZONE_AIZ, ACT_2, registry, null);

        cycler.update();

        for (int color = 12; color <= 15; color++) {
            assertEquals(S3kPaletteOwners.AIZ2_WATER_CYCLE,
                    registry.ownerAt(PaletteSurface.NORMAL, 3, color),
                    "AIZ2 water cycle should claim palette[3] color " + color);
        }
        assertEquals(S3kPaletteOwners.AIZ2_WATER_CYCLE,
                registry.ownerAt(PaletteSurface.NORMAL, 2, 4));
        assertEquals(S3kPaletteOwners.AIZ2_WATER_CYCLE,
                registry.ownerAt(PaletteSurface.NORMAL, 2, 8));
        assertEquals(S3kPaletteOwners.AIZ2_WATER_CYCLE,
                registry.ownerAt(PaletteSurface.NORMAL, 2, 14));
        assertEquals(S3kPaletteOwners.AIZ2_WATER_CYCLE,
                registry.ownerAt(PaletteSurface.NORMAL, 3, 11));
    }

    @Test
    public void aiz1IntroCycleSubmitsPaletteOwnershipClaims() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        GameServices.camera().setX((short) 0);
        GameServices.camera().setLevelStarted(false);
        Aiz2StubLevel stubLevel = new Aiz2StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(
                reader, stubLevel, ZONE_AIZ, 0, registry, null);

        cycler.update();

        for (int color = 2; color <= 5; color++) {
            assertEquals(S3kPaletteOwners.AIZ1_ANPAL,
                    registry.ownerAt(PaletteSurface.NORMAL, 3, color),
                    "AIZ1 intro cycle should claim palette[3] color " + color);
        }
        for (int color = 13; color <= 15; color++) {
            assertEquals(S3kPaletteOwners.AIZ1_ANPAL,
                    registry.ownerAt(PaletteSurface.NORMAL, 3, color),
                    "AIZ1 intro cycle should claim palette[3] color " + color);
        }
    }

    @Test
    public void aiz1GameplayCycleSubmitsPaletteOwnershipClaims() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        GameServices.camera().setX((short) 0x1000);
        GameServices.camera().setLevelStarted(true);
        Aiz2StubLevel stubLevel = new Aiz2StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(
                reader, stubLevel, ZONE_AIZ, 0, registry, null);

        cycler.update();

        for (int color = 11; color <= 14; color++) {
            assertEquals(S3kPaletteOwners.AIZ1_ANPAL,
                    registry.ownerAt(PaletteSurface.NORMAL, 2, color),
                    "AIZ1 gameplay cycle should claim palette[2] color " + color);
        }
        for (int color = 12; color <= 14; color++) {
            assertEquals(S3kPaletteOwners.AIZ1_ANPAL,
                    registry.ownerAt(PaletteSurface.NORMAL, 3, color),
                    "AIZ1 gameplay cycle should claim palette[3] color " + color);
        }
    }

    /**
     * Verifies that the water cycle (AnPal_PalAIZ2_1) applies correct ROM values on
     * the first tick. Palette[3] colors 12-15 receive water data frame 0.
     */
    @Test
    public void waterCycleFirstTickAppliesRomValues() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        Aiz2StubLevel stubLevel = new Aiz2StubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_AIZ, ACT_2);

        cycler.update();

        // Water cycle writes palette[3] colors 12-15 from waterData frame 0.
        Palette pal3 = stubLevel.getPalette(3);
        int r12 = pal3.getColor(12).r & 0xFF;
        int g12 = pal3.getColor(12).g & 0xFF;
        int b12 = pal3.getColor(12).b & 0xFF;

        // Water colors should be non-zero Ã¢â‚¬â€ at least one channel must have a value.
        assertTrue(r12 > 0 || g12 > 0 || b12 > 0, "Water color 12 should be non-zero after first tick, got ("
                + r12 + "," + g12 + "," + b12 + ")");

        // Colors 13-15 must also be set
        for (int c = 13; c <= 15; c++) {
            int r = pal3.getColor(c).r & 0xFF;
            int g = pal3.getColor(c).g & 0xFF;
            int b = pal3.getColor(c).b & 0xFF;
            assertTrue(r > 0 || g > 0 || b > 0, "Water color " + c + " should be non-zero after first tick, got ("
                    + r + "," + g + "," + b + ")");
        }
    }

    /**
     * Verifies that the torch cycle produces multiple distinct color values over a
     * full cycle period. The torch table has 26 frames (wraps at 0x34), timer period 2,
     * so 52 ticks covers a full cycle.
     */
    @Test
    public void torchCycleProducesMultipleDistinctValues() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        Aiz2StubLevel stubLevel = new Aiz2StubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_AIZ, ACT_2);

        int distinctCount = 0;
        int prevR = -1, prevG = -1, prevB = -1;

        for (int frame = 0; frame < 52; frame++) {
            cycler.update();
            Palette.Color color1 = stubLevel.getPalette(3).getColor(1);
            int r = color1.r & 0xFF;
            int g = color1.g & 0xFF;
            int b = color1.b & 0xFF;
            if (r != prevR || g != prevG || b != prevB) {
                distinctCount++;
                prevR = r;
                prevG = g;
                prevB = b;
            }
        }

        // Torch table has 26 unique frames; with timer period 2, fires 26 times in 52 ticks.
        // Expect at least 3 distinct values in practice (many are unique fire tones).
        assertTrue(distinctCount >= 3, "Torch cycle should produce at least 3 distinct colors over 52 frames, got "
                + distinctCount);
    }

    /**
     * Minimal Level stub for direct Sonic3kPaletteCycler testing.
     * 4 palette lines, no geometry needed.
     */
    private static final class Aiz2StubLevel implements Level {
        private final Palette[] palettes = new Palette[4];

        Aiz2StubLevel() {
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
        @Override public int getZoneIndex() { return ZONE_AIZ; }
    }
}


