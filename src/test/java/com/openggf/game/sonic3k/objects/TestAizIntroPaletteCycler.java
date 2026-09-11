package com.openggf.game.sonic3k.objects;

import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestAizIntroPaletteCycler {

    @Test
    public void initialPaletteFrameIs0x24() {
        var cycler = new AizIntroPaletteCycler();
        cycler.init();
        assertEquals(0x24, cycler.getPaletteFrame());
    }

    @Test
    public void timerCountsDownFrom6() {
        var cycler = new AizIntroPaletteCycler();
        cycler.init();
        // Advance 5 frames - timer should decrement but not trigger
        for (int i = 0; i < 5; i++) {
            cycler.advance();
        }
        assertEquals(0x24, cycler.getPaletteFrame()); // Not advanced yet
    }

    @Test
    public void paletteAdvancesAfter6Frames() {
        var cycler = new AizIntroPaletteCycler();
        cycler.init();
        // Advance 7 frames (timer starts at 6, fires on reaching 0)
        for (int i = 0; i < 7; i++) {
            cycler.advance();
        }
        assertEquals(0x2A, cycler.getPaletteFrame()); // 0x24 + 6
    }

    @Test
    public void paletteWrapsFrom0x36BackTo0x24() {
        var cycler = new AizIntroPaletteCycler();
        cycler.init();
        cycler.setPaletteFrame(0x36);
        // Force one more cycle
        for (int i = 0; i < 7; i++) {
            cycler.advance();
        }
        // 0x36 + 6 = 0x3C > 0x36, should wrap to 0x24
        assertEquals(0x24, cycler.getPaletteFrame());
    }

    @Test
    public void mappingFrameAlternatesOnVblankParity() {
        var cycler = new AizIntroPaletteCycler();
        assertEquals(0x21, cycler.getMappingFrame(0)); // even frame
        assertEquals(0x22, cycler.getMappingFrame(1)); // odd frame
        assertEquals(0x21, cycler.getMappingFrame(2)); // even frame
    }

    @Test
    public void applyToGpuSubmitsPaletteOwnershipClaims() throws Exception {
        byte[] cycleData = new byte[0x40];
        cycleData[0x24] = 0x00;
        cycleData[0x25] = 0x02;
        cycleData[0x26] = 0x00;
        cycleData[0x27] = 0x04;
        cycleData[0x28] = 0x00;
        cycleData[0x29] = 0x06;
        setSuperSonicPaletteCycleData(cycleData);

        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        var cycler = new AizIntroPaletteCycler(new StubObjectServices() {
            @Override
            public Level currentLevel() {
                return level;
            }

            @Override
            public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
                return registry;
            }
        });
        cycler.init();

        cycler.applyToGpu();
        registry.resolveInto(level.palettes(), null, null, null);

        for (int color = 2; color <= 4; color++) {
            assertEquals(S3kPaletteOwners.AIZ_INTRO_SUPER_PALETTE,
                    registry.ownerAt(PaletteSurface.NORMAL, 0, color),
                    "AIZ intro Super Sonic palette cycle should claim line 0 color " + color);
        }
    }

    private static void setSuperSonicPaletteCycleData(byte[] data) throws Exception {
        Field field = AizIntroArtLoader.class.getDeclaredField("superSonicPaletteCycleData");
        field.setAccessible(true);
        field.set(null, data);
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


