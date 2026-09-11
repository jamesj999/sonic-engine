package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.palette.PaletteWriteSupport;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic2ArzBossPaletteFlash {

    @Test
    void hitFlashTargetsBossPaletteWithoutChangingSonicPalette() {
        StubLevel level = new StubLevel();
        level.getPalette(0).getColor(1).fromSegaFormat(new byte[] { 0x00, (byte) 0xE0 }, 0);
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        ExposedArzBoss boss = new ExposedArzBoss();
        boss.setServices(new StubObjectServices() {
            @Override
            public Level currentLevel() {
                return level;
            }

            @Override
            public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
                return registry;
            }
        });

        boss.flashOnce();
        registry.resolveInto(level.palettes, null, null, level.getPalette(0));

        assertEquals("none",
                registry.ownerAt(PaletteSurface.NORMAL, 0, 1),
                "Sonic's palette line must remain untouched by the ARZ boss flash");
        assertEquals("boss.flash", registry.ownerAt(PaletteSurface.NORMAL, 1, 1));
        assertEquals(0x00E0,
                PaletteWriteSupport.segaWordFromColor(level.getPalette(0).getColor(1)),
                "Sonic's resolved palette color must remain unchanged");
        assertEquals(0x0000,
                PaletteWriteSupport.segaWordFromColor(level.getPalette(1).getColor(1)),
                "the first ARZ boss flash frame must resolve to black");
    }

    private static final class ExposedArzBoss extends Sonic2ARZBossInstance {
        private ExposedArzBoss() {
            super(new ObjectSpawn(0, 0, 0x89, 0, 0, false, 0));
        }

        private void flashOnce() {
            paletteFlasher.startFlash();
            paletteFlasher.update();
        }
    }

    private static final class StubLevel implements Level {
        private final Palette[] palettes = {
                new Palette(), new Palette(), new Palette(), new Palette()
        };

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
