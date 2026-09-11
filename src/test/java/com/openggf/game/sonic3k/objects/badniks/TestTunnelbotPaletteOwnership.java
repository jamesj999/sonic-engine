package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestTunnelbotPaletteOwnership {
    private static final String TUNNELBOT_PALETTE_OWNER = "s3k.mgz.tunnelbot";
    private static final int[] FLASH_INDICES = {12, 13, 14};
    private static final int[] ORIGINAL_WORDS = {0x0222, 0x0644, 0x0AAA};
    private static final TouchResponseResult ENEMY_HIT =
            new TouchResponseResult(0x10, 0, 0, TouchCategory.ENEMY);

    @Test
    void hitFlashSubmitsTunnelbotPaletteOwnershipClaims() throws Exception {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        TunnelbotBadnikInstance tunnelbot = tunnelbotWithServices(level, registry);

        tunnelbot.onPlayerAttack(null, ENEMY_HIT);

        registry.beginFrame();
        invokeUpdateHitFlash(tunnelbot);
        registry.resolveInto(level.palettes(), null, null, null);

        for (int colorIndex : FLASH_INDICES) {
            assertEquals(TUNNELBOT_PALETTE_OWNER,
                    registry.ownerAt(PaletteSurface.NORMAL, 1, colorIndex),
                    "Tunnelbot flash color " + colorIndex + " should be owned by the Tunnelbot palette writer");
            assertColorWord(level.getPalette(1), colorIndex, 0x0EEE);
        }
    }

    @Test
    void hitFlashRestoreSubmitsTunnelbotPaletteOwnershipClaims() throws Exception {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        seedOriginalFlashColors(level.getPalette(1));
        TunnelbotBadnikInstance tunnelbot = tunnelbotWithServices(level, registry);

        tunnelbot.onPlayerAttack(null, ENEMY_HIT);
        setPrivateInt(tunnelbot, "hitFlashTimer", 1);

        registry.beginFrame();
        invokeUpdateHitFlash(tunnelbot);
        registry.resolveInto(level.palettes(), null, null, null);

        for (int i = 0; i < FLASH_INDICES.length; i++) {
            int colorIndex = FLASH_INDICES[i];
            assertEquals(TUNNELBOT_PALETTE_OWNER,
                    registry.ownerAt(PaletteSurface.NORMAL, 1, colorIndex),
                    "Tunnelbot restore color " + colorIndex + " should be owned by the Tunnelbot palette writer");
            assertColorWord(level.getPalette(1), colorIndex, ORIGINAL_WORDS[i]);
        }
    }

    private static TunnelbotBadnikInstance tunnelbotWithServices(StubLevel level, PaletteOwnershipRegistry registry) {
        TunnelbotBadnikInstance tunnelbot = new TunnelbotBadnikInstance(new ObjectSpawn(
                0x2800, 0x0300, Sonic3kObjectIds.TUNNELBOT, 0, 0, false, 0));
        tunnelbot.setServices(new StubObjectServices() {
            @Override
            public Level currentLevel() {
                return level;
            }

            @Override
            public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
                return registry;
            }
        });
        return tunnelbot;
    }

    private static void invokeUpdateHitFlash(TunnelbotBadnikInstance tunnelbot) throws Exception {
        Method method = TunnelbotBadnikInstance.class.getDeclaredMethod("updateHitFlash");
        method.setAccessible(true);
        method.invoke(tunnelbot);
    }

    private static void setPrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void seedOriginalFlashColors(Palette palette) {
        for (int i = 0; i < FLASH_INDICES.length; i++) {
            palette.getColor(FLASH_INDICES[i]).fromSegaFormat(segaWordBytes(ORIGINAL_WORDS[i]), 0);
        }
    }

    private static void assertColorWord(Palette palette, int colorIndex, int segaWord) {
        byte highByte = (byte) ((segaWord >> 8) & 0xFF);
        byte lowByte = (byte) (segaWord & 0xFF);
        int r3 = (lowByte >> 1) & 0x07;
        int g3 = (lowByte >> 5) & 0x07;
        int b3 = (highByte >> 1) & 0x07;
        int expectedR = (r3 * 255 + 3) / 7;
        int expectedG = (g3 * 255 + 3) / 7;
        int expectedB = (b3 * 255 + 3) / 7;
        assertEquals(expectedR, palette.getColor(colorIndex).r & 0xFF);
        assertEquals(expectedG, palette.getColor(colorIndex).g & 0xFF);
        assertEquals(expectedB, palette.getColor(colorIndex).b & 0xFF);
    }

    private static byte[] segaWordBytes(int segaWord) {
        return new byte[] {
                (byte) ((segaWord >> 8) & 0xFF),
                (byte) (segaWord & 0xFF)
        };
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
