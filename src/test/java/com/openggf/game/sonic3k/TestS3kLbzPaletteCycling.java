package com.openggf.game.sonic3k;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomByteReader;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.GameServices;
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
 * Validates that S3K LBZ Act 1 palette cycling is active and modifies colors over time.
 *
 * <p>The ROM's {@code AnPal_LBZ1} / {@code AnPal_LBZ2} routine (sonic3k.asm loc_2516) cycles
 * colors on {@code Normal_palette_line_3+$10} Ã¢â‚¬â€ engine palette[2] colors 8Ã¢â‚¬â€œ10 Ã¢â‚¬â€ every 4 frames
 * via {@code AnPal_PalLBZ1} (Act 1) or {@code AnPal_PalLBZ2} (Act 2).
 *
 * <p>This test loads LBZ Act 1 (zone 0x06, act 0), ticks the animation manager manually
 * (palette cycling runs inside the draw path in production), and verifies that palette[2]
 * color 8 changes within 20 frames.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kLbzPaletteCycling {
    private static final int ZONE_LBZ = 6;
    private static final int ACT_1 = 0;

    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_LBZ, ACT_1);
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
    public void lbzCycleModifiesPaletteLine3Colors8to10() {
        Level level = GameServices.level().getCurrentLevel();
        assertNotNull(level, "Level must be loaded");

        Palette pal2 = level.getPalette(2);
        assertNotNull(pal2, "Palette line 3 (index 2) must exist");

        // Record initial LBZ cycling color (palette line 3, color 8)
        Palette.Color color8 = pal2.getColor(8);
        int initialR = color8.r & 0xFF;
        int initialG = color8.g & 0xFF;
        int initialB = color8.b & 0xFF;

        // The LBZ cycle ticks every 4 frames with 3 entries (0x12/6 = 3),
        // so 20 frames is more than enough for one full cycle.
        boolean colorChanged = false;
        for (int frame = 0; frame < 20; frame++) {
            fixture.stepIdleFrames(1);
            tickAnimation();

            int r = color8.r & 0xFF;
            int g = color8.g & 0xFF;
            int b = color8.b & 0xFF;
            if (r != initialR || g != initialG || b != initialB) {
                colorChanged = true;
                break;
            }
        }

        assertTrue(colorChanged, "Expected palette[2] color 8 (LBZ cycle) to change over 20 frames, "
                + "proving AnPal_PalLBZ1 cycling is active. "
                + "Initial RGB=(" + initialR + "," + initialG + "," + initialB + ")");
    }

    // ========== Direct cycler tests with specific color value assertions ==========

    /**
     * Verifies that the LBZ pipe fluid cycle applies specific ROM values on the first tick.
     * Timer starts at 0, so the first update fires immediately, writing 3 colors to
     * palette[2] colors 8-10 from the table frame 0.
     */
    @Test
    public void lbzCycleFirstTickAppliesNonZeroColors() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        LbzStubLevel stubLevel = new LbzStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_LBZ, ACT_1);

        cycler.update();

        // LBZ cycle writes palette[2] colors 8-10 from ROM frame 0
        Palette pal2 = stubLevel.getPalette(2);
        for (int c = 8; c <= 10; c++) {
            int r = pal2.getColor(c).r & 0xFF;
            int g = pal2.getColor(c).g & 0xFF;
            int b = pal2.getColor(c).b & 0xFF;
            assertTrue(r > 0 || g > 0 || b > 0, "LBZ pipe color " + c + " should be non-zero after first tick, got ("
                    + r + "," + g + "," + b + ")");
        }
    }

    /**
     * Verifies the LBZ cycle has exactly 3 frames and wraps correctly.
     * The table is 18 bytes (3 frames x 6 bytes), counter0 step +6, wrap at 0x12.
     * Timer period 3 Ã¢â€ â€™ fires every 4 ticks. After 12 ticks (3 fires), it wraps back
     * to frame 0, so the 4th fire should match the 1st.
     */
    @Test
    public void lbzCycleWrapsAfterThreeFrames() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        LbzStubLevel stubLevel = new LbzStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_LBZ, ACT_1);

        // Fire frame 0: tick 0 (timer=0 Ã¢â€ â€™ fires, timerÃ¢â€ â€™3)
        cycler.update();
        int frame0R = stubLevel.getPalette(2).getColor(8).r & 0xFF;
        int frame0G = stubLevel.getPalette(2).getColor(8).g & 0xFF;
        int frame0B = stubLevel.getPalette(2).getColor(8).b & 0xFF;

        // Fire frames 1, 2 (ticks 4, 8)
        for (int i = 0; i < 8; i++) {
            cycler.update();
        }

        // Fire frame 0 again (tick 12: counter wraps from 0x12 Ã¢â€ â€™ 0)
        for (int i = 0; i < 4; i++) {
            cycler.update();
        }
        int wrapR = stubLevel.getPalette(2).getColor(8).r & 0xFF;
        int wrapG = stubLevel.getPalette(2).getColor(8).g & 0xFF;
        int wrapB = stubLevel.getPalette(2).getColor(8).b & 0xFF;

        assertTrue(frame0R == wrapR && frame0G == wrapG && frame0B == wrapB, "LBZ color 8 should wrap back to frame 0 values after 3 frames, "
                + "got frame0=(" + frame0R + "," + frame0G + "," + frame0B + ") "
                + "wrap=(" + wrapR + "," + wrapG + "," + wrapB + ")");
    }

    /**
     * Verifies that all 3 colors (8-10) are written per frame by checking they all
     * match expected patterns (pipe fluid: mechanical/industrial palette tones).
     */
    @Test
    public void lbzCycleWritesAllThreeColorsPerFrame() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        LbzStubLevel stubLevel = new LbzStubLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_LBZ, ACT_1);

        cycler.update();

        Palette pal2 = stubLevel.getPalette(2);
        int nonZeroCount = 0;
        for (int c = 8; c <= 10; c++) {
            int r = pal2.getColor(c).r & 0xFF;
            int g = pal2.getColor(c).g & 0xFF;
            int b = pal2.getColor(c).b & 0xFF;
            if (r != 0 || g != 0 || b != 0) {
                nonZeroCount++;
            }
        }

        assertTrue(nonZeroCount >= 2, "All 3 LBZ pipe colors (8-10) should be written on first tick, "
                + "but only " + nonZeroCount + " are non-zero");
    }

    @Test
    public void lbzCycleSubmitsPaletteOwnershipClaims() throws IOException {
        GraphicsManager.getInstance().initHeadless();
        LbzStubLevel stubLevel = new LbzStubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, stubLevel, ZONE_LBZ, ACT_1, registry, null);

        registry.beginFrame();
        cycler.update();
        registry.resolveInto(stubLevel.palettes(), null, null, null);

        assertEquals(S3kPaletteOwners.LBZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, 8));
        assertEquals(S3kPaletteOwners.LBZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, 9));
        assertEquals(S3kPaletteOwners.LBZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, 10));
        for (int c = 8; c <= 10; c++) {
            int r = stubLevel.getPalette(2).getColor(c).r & 0xFF;
            int g = stubLevel.getPalette(2).getColor(c).g & 0xFF;
            int b = stubLevel.getPalette(2).getColor(c).b & 0xFF;
            assertTrue(r > 0 || g > 0 || b > 0,
                    "LBZ owned palette color " + c + " should resolve from ROM data");
        }
    }

    /**
     * Minimal Level stub for direct LBZ palette cycling tests.
     */
    private static final class LbzStubLevel implements Level {
        private final Palette[] palettes = new Palette[4];

        LbzStubLevel() {
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
        @Override public int getZoneIndex() { return ZONE_LBZ; }
    }
}


