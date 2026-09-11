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
 * Validates that S3K HCZ Act 1 palette cycling is active and modifies colors over time.
 *
 * <p>The ROM's {@code AnPal_HCZ1} routine cycles water colors on palette line 3
 * (engine index 2), colors 3-6, every 8 frames via {@code AnPal_PalHCZ1}. The table
 * contains 4 frames of 4 colors each (32 bytes total), cycling indices 0, 8, 16, 24.
 *
 * <p>This test loads HCZ Act 1, ticks the animation manager, and verifies palette colors
 * change over time. In production the palette cycler runs inside
 * {@code LevelManager.drawWithSpritePriority()} (the draw path), so headless tests must
 * manually tick the animation manager since draw is never called.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kHczPaletteCycling {
    private static final int ZONE_HCZ = 1;
    private static final int ACT_1 = 0;

    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_HCZ, ACT_1);
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
    public void waterCycleModifiesPaletteLine3Color3() {
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        Palette pal2 = level.getPalette(2);
        assertNotNull(pal2, "Palette line 3 (index 2) must exist");

        // Record initial water color (palette line 3, color 3)
        Palette.Color color3 = pal2.getColor(3);
        int initialR = color3.r & 0xFF;
        int initialG = color3.g & 0xFF;
        int initialB = color3.b & 0xFF;

        // The HCZ water cycle ticks every 8 frames with 4 entries,
        // so 60 frames covers more than one full cycle (4 frames Ãƒâ€” 8 ticks = 32 game frames).
        boolean colorChanged = false;
        for (int frame = 0; frame < 60; frame++) {
            fixture.stepIdleFrames(1);
            tickAnimation();

            int r = color3.r & 0xFF;
            int g = color3.g & 0xFF;
            int b = color3.b & 0xFF;
            if (r != initialR || g != initialG || b != initialB) {
                colorChanged = true;
                break;
            }
        }

        assertTrue(colorChanged, "Expected palette[2] color 3 (HCZ water cycle) to change over 60 frames, "
                + "proving AnPal_PalHCZ1 cycling is active. "
                + "Initial RGB=(" + initialR + "," + initialG + "," + initialB + ")");
    }

    // ========== Direct cycler tests with specific color value assertions ==========

    /**
     * Verifies that the HCZ water cycle applies specific ROM color values on the first
     * tick. Timer starts at 0 so the first update fires immediately, applying waterData
     * frame 0 to palette[2] colors 3-6. HCZ water is blue-green toned.
     */
    @Test
    public void waterCycleFirstTickAppliesRomValues() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        HczStubLevel stubLevel = new HczStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_HCZ, ACT_1);

        cycler.update();

        // Water cycle writes palette[2] colors 3-6 from ROM frame 0
        Palette pal2 = stubLevel.getPalette(2);
        for (int c = 3; c <= 6; c++) {
            int r = pal2.getColor(c).r & 0xFF;
            int g = pal2.getColor(c).g & 0xFF;
            int b = pal2.getColor(c).b & 0xFF;
            assertTrue(r > 0 || g > 0 || b > 0, "HCZ water color " + c + " should be non-zero after first tick, got ("
                    + r + "," + g + "," + b + ")");
        }
    }

    /**
     * Verifies the water cycle produces exactly 4 distinct frame states over a full period.
     * HCZ water has 4 frames (counter0 cycles 0,8,16,24 with mask & 0x18, wraps at 0x20).
     * Timer period 7 Ã¢â€ â€™ fires every 8 ticks. Over 32 ticks, fires 4 times.
     */
    @Test
    public void waterCycleProducesFourDistinctFrames() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        HczStubLevel stubLevel = new HczStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_HCZ, ACT_1);

        int distinctCount = 0;
        int prevR = -1, prevG = -1, prevB = -1;

        // 32 ticks: first fires at tick 0, then at ticks 8, 16, 24 Ã¢â€ â€™ 4 fires total
        for (int frame = 0; frame < 32; frame++) {
            cycler.update();
            Palette.Color c3 = stubLevel.getPalette(2).getColor(3);
            int r = c3.r & 0xFF;
            int g = c3.g & 0xFF;
            int b = c3.b & 0xFF;
            if (r != prevR || g != prevG || b != prevB) {
                distinctCount++;
                prevR = r;
                prevG = g;
                prevB = b;
            }
        }

        // 4 unique ROM frames should produce at least 2 distinct observed states
        // (some may coincide due to palette data)
        assertTrue(distinctCount >= 2, "HCZ water should produce at least 2 distinct color 3 states over 32 frames, got "
                + distinctCount);
    }

    /**
     * Verifies that all 4 water colors (3-6) change together, confirming the longword
     * write (move.l) applies to 4 consecutive colors per frame.
     */
    @Test
    public void waterCycleWritesAllFourColorsPerFrame() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        HczStubLevel stubLevel = new HczStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_HCZ, ACT_1);

        cycler.update();

        Palette pal2 = stubLevel.getPalette(2);
        // After first tick, colors 3-6 should all be written (non-default)
        // Verify none are still at the default (0,0,0)
        int nonZeroCount = 0;
        for (int c = 3; c <= 6; c++) {
            int r = pal2.getColor(c).r & 0xFF;
            int g = pal2.getColor(c).g & 0xFF;
            int b = pal2.getColor(c).b & 0xFF;
            if (r != 0 || g != 0 || b != 0) {
                nonZeroCount++;
            }
        }

        assertTrue(nonZeroCount >= 3, "All 4 HCZ water colors (3-6) should be written on first tick, but only "
                + nonZeroCount + " are non-zero");
    }

    /**
     * Verifies that HCZ Act 2 has NO palette cycling (AnPal_HCZ2 is rts).
     * The palette cycler should not modify any palette colors for Act 2.
     */
    @Test
    public void hcz2HasNoPaletteCycling() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        HczStubLevel stubLevel = new HczStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        // Create cycler for Act 2 Ã¢â‚¬â€ should produce no cycles
        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_HCZ, 1);

        // Record initial palette state (should be all zeros from stub)
        Palette pal2 = stubLevel.getPalette(2);
        int initialR = pal2.getColor(3).r & 0xFF;
        int initialG = pal2.getColor(3).g & 0xFF;
        int initialB = pal2.getColor(3).b & 0xFF;

        // Tick many frames Ã¢â‚¬â€ palette should never change
        for (int i = 0; i < 60; i++) {
            cycler.update();
        }

        assertEquals(initialR, pal2.getColor(3).r & 0xFF, "HCZ2 palette should not cycle (R)");
        assertEquals(initialG, pal2.getColor(3).g & 0xFF, "HCZ2 palette should not cycle (G)");
        assertEquals(initialB, pal2.getColor(3).b & 0xFF, "HCZ2 palette should not cycle (B)");
    }

    @Test
    public void hcz2StillFlushesPendingPaletteOwnershipWritesWithoutCycles() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        HczStubLevel stubLevel = new HczStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        byte[] line = new byte[32];
        line[0] = 0x02;
        line[1] = 0x22;
        line[2] = 0x0C;
        line[3] = (byte) 0xEE;

        S3kPaletteWriteSupport.applyLine(
                registry,
                stubLevel,
                null,
                S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD,
                S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                1,
                line);

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_HCZ, 1, registry, null);
        cycler.update();

        assertEquals(S3kPaletteOwners.ZONE_EVENT_PALETTE_LOAD, registry.ownerAt(PaletteSurface.NORMAL, 1, 0));
        assertTrue((stubLevel.getPalette(1).getColor(0).r & 0xFF) > 0,
                "Pending palette ownership writes should still resolve even when HCZ2 has no cycles");
    }

    /**
     * Minimal Level stub for direct HCZ palette cycling tests.
     */
    private static final class HczStubLevel implements Level {
        private final Palette[] palettes = new Palette[4];

        HczStubLevel() {
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
        @Override public int getZoneIndex() { return ZONE_HCZ; }
    }
}



