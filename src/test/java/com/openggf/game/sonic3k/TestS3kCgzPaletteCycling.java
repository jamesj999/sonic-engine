package com.openggf.game.sonic3k;
import org.junit.jupiter.api.Test;
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
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that the Sonic 3&K CGZ (Chrome Gadget Zone, zone 0x11) palette cycling
 * is active and modifies palette[2] colors 2-5 over time.
 *
 * <p>The ROM's {@code AnPal_CGZ} routine cycles light colors on palette line 3
 * (engine index 2), colors 2-5, every 10 frames via {@code AnPal_PalCGZ}. The table
 * contains 10 frames of 4 colors each (80 bytes total), producing a chrome-like
 * light animation from bright to dark and back.
 *
 * <p>This test directly instantiates {@link Sonic3kPaletteCycler} with a synthetic
 * test level and ticks it, verifying that palette[2] colors 2-5 change after enough
 * frames. No full level load is needed because CGZ is a competition zone not in the
 * normal zone registry.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kCgzPaletteCycling {
    // Chrome Gadget is ROM zone $11: OffsAnPal entries 34/35
    // (skdisasm/sonic3k.asm:3152-3153) and LevelSizes rows 34/35
    // (sonic3k.asm:38114-38115), both indexed zone*2 + act.
    private static final int ZONE_CGZ = 0x11;
    private static final int ACT_1 = 0;

    @Test
    public void lightAnimationCycleModifiesPaletteLine3Colors2to5() throws IOException {
        GraphicsManager.getInstance().initHeadless();

        // Build a synthetic level with 4 palette lines populated.
        CgzTestLevel level = new CgzTestLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, level, ZONE_CGZ, ACT_1);

        Palette pal2 = level.getPalette(2);
        Palette.Color color2 = pal2.getColor(2);

        // Sample initial values
        int initialR = color2.r & 0xFF;
        int initialG = color2.g & 0xFF;
        int initialB = color2.b & 0xFF;

        // The CGZ cycle fires every 10 frames (timer resets to 9).
        // 60 ticks covers 6 full periods Ã¢â‚¬â€ at least one color update must occur.
        boolean colorChanged = false;
        for (int frame = 0; frame < 60; frame++) {
            cycler.update();

            int r = color2.r & 0xFF;
            int g = color2.g & 0xFF;
            int b = color2.b & 0xFF;
            if (r != initialR || g != initialG || b != initialB) {
                colorChanged = true;
                break;
            }
        }

        assertTrue(colorChanged, "Expected palette[2] color 2 (CGZ light cycle) to change over 60 frames, "
                + "proving AnPal_PalCGZ cycling is active. "
                + "Initial RGB=(" + initialR + "," + initialG + "," + initialB + ")");
    }

    @Test
    public void cgzPaletteDataMatchesRomSpec() throws IOException {
        GraphicsManager.getInstance().initHeadless();

        CgzTestLevel level = new CgzTestLevel();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, level, ZONE_CGZ, ACT_1);

        // Tick once to fire the first cycle update (timer=0 on first call Ã¢â€ â€™ fires immediately).
        // Table frame 0: $000E, $0008, $0004, $0EEE applied to palette[2] colors 2-5.
        cycler.update(); // fires: applies table frame 0 (counter0=0 Ã¢â€ â€™ index 0)

        Palette pal2 = level.getPalette(2);

        // MD palette format: word $000E stored as bytes [0x00, 0x0E]
        // fromSegaFormat: r3=(byte1>>1)&7=(0x0E>>1)&7=7 Ã¢â€ â€™ r=255, g3=(byte1>>5)&7=0 Ã¢â€ â€™ g=0,
        // b3=(byte0>>1)&7=0 Ã¢â€ â€™ b=0. So $000E = RED (r=255, g=0, b=0).
        int color2R = pal2.getColor(2).r & 0xFF;
        int color2G = pal2.getColor(2).g & 0xFF;
        int color2B = pal2.getColor(2).b & 0xFF;
        assertTrue(color2R > 200 && color2G == 0 && color2B == 0, "After first CGZ tick, color 2 should be red from $000E (r=7,g=0,b=0)");

        // Fourth entry in frame 0: $0EEE Ã¢â‚¬â€ bytes [0x0E, 0xEE]
        // r3=(0xEE>>1)&7=7 Ã¢â€ â€™ r=255, g3=(0xEE>>5)&7=7 Ã¢â€ â€™ g=255, b3=(0x0E>>1)&7=7 Ã¢â€ â€™ b=255.
        // So $0EEE = WHITE.
        int color5R = pal2.getColor(5).r & 0xFF;
        int color5G = pal2.getColor(5).g & 0xFF;
        int color5B = pal2.getColor(5).b & 0xFF;
        assertTrue(color5R > 200 && color5G > 200 && color5B > 200, "Color 5 should be white from $0EEE (r=7,g=7,b=7)");
    }

    @Test
    public void cgzCycleSubmitsPaletteOwnershipClaims() throws IOException {
        GraphicsManager.getInstance().initHeadless();

        CgzTestLevel level = new CgzTestLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());

        Sonic3kPaletteCycler cycler = new Sonic3kPaletteCycler(reader, level, ZONE_CGZ, ACT_1, registry, null);

        registry.beginFrame();
        cycler.update();
        registry.resolveInto(level.palettes(), null, null, null);

        for (int c = 2; c <= 5; c++) {
            assertEquals(S3kPaletteOwners.CGZ_ZONE_CYCLE, registry.ownerAt(PaletteSurface.NORMAL, 2, c));
        }
        int color2R = level.getPalette(2).getColor(2).r & 0xFF;
        int color5R = level.getPalette(2).getColor(5).r & 0xFF;
        int color5G = level.getPalette(2).getColor(5).g & 0xFF;
        int color5B = level.getPalette(2).getColor(5).b & 0xFF;
        assertTrue(color2R > 200, "CGZ owned color 2 should resolve from ROM frame 0");
        assertTrue(color5R > 200 && color5G > 200 && color5B > 200,
                "CGZ owned color 5 should resolve from ROM frame 0");
    }

    /**
     * Minimal Level implementation that only provides 4 palette lines.
     * Sufficient for testing palette cycling without a full level load.
     */
    private static final class CgzTestLevel implements Level {
        private final Palette[] palettes = new Palette[4];

        CgzTestLevel() {
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
        @Override public int getZoneIndex() { return ZONE_CGZ; }
    }
}


