package com.openggf.game.sonic3k.objects;

import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.sonic3k.S3kPaletteOwners;
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
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCnzMinibossPaletteOwnership {
    private static final int[] FLASH_INDICES = {2, 3, 4, 7, 14};
    private static final int[] FLASH_WORDS = {0x0888, 0x0AAA, 0x0CCC, 0x0888, 0x0AAA};
    private static final int[] ORIGINAL_WORDS = {0x0222, 0x0444, 0x0666, 0x0246, 0x0EEE};

    @Test
    void hitFlashSubmitsCnzMinibossOwnershipClaims() throws Exception {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        CnzMinibossInstance miniboss = minibossWithServices(level, registry);

        registry.beginFrame();
        invokePaletteMethod(miniboss, "applyBossFlash", new Class<?>[] {int[].class}, (Object) FLASH_WORDS);
        registry.resolveInto(level.palettes(), null, null, null);

        for (int i = 0; i < FLASH_INDICES.length; i++) {
            int colorIndex = FLASH_INDICES[i];
            assertEquals(S3kPaletteOwners.CNZ_MINIBOSS,
                    registry.ownerAt(PaletteSurface.NORMAL, 1, colorIndex),
                    "CNZ miniboss flash color " + colorIndex + " should be owned by the miniboss palette writer");
            assertColorWord(level.getPalette(1), colorIndex, FLASH_WORDS[i]);
        }
    }

    @Test
    void hitFlashRestoreSubmitsCnzMinibossOwnershipClaims() throws Exception {
        StubLevel level = new StubLevel();
        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        seedOriginalBossFlashColors(level.getPalette(1));
        CnzMinibossInstance miniboss = minibossWithServices(level, registry);

        registry.beginFrame();
        invokePaletteMethod(miniboss, "applyBossFlash", new Class<?>[] {int[].class}, (Object) FLASH_WORDS);
        registry.resolveInto(level.palettes(), null, null, null);

        registry.beginFrame();
        invokePaletteMethod(miniboss, "restoreBossFlashColors", new Class<?>[0]);
        registry.resolveInto(level.palettes(), null, null, null);

        for (int i = 0; i < FLASH_INDICES.length; i++) {
            int colorIndex = FLASH_INDICES[i];
            assertEquals(S3kPaletteOwners.CNZ_MINIBOSS,
                    registry.ownerAt(PaletteSurface.NORMAL, 1, colorIndex),
                    "CNZ miniboss restore color " + colorIndex + " should be owned by the miniboss palette writer");
            assertColorWord(level.getPalette(1), colorIndex, ORIGINAL_WORDS[i]);
        }
    }

    private static CnzMinibossInstance minibossWithServices(StubLevel level, PaletteOwnershipRegistry registry) {
        CnzMinibossInstance miniboss = new CnzMinibossInstance(new ObjectSpawn(
                0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        miniboss.setServices(new StubObjectServices() {
            @Override
            public Level currentLevel() {
                return level;
            }

            @Override
            public PaletteOwnershipRegistry paletteOwnershipRegistryOrNull() {
                return registry;
            }
        });
        return miniboss;
    }

    private static void invokePaletteMethod(CnzMinibossInstance miniboss, String name,
                                            Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = CnzMinibossInstance.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(miniboss, args);
    }

    private static void seedOriginalBossFlashColors(Palette palette) {
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
