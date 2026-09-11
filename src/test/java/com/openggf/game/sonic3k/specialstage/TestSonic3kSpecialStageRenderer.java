package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.PlayerCharacter;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kSpecialStageRenderer {
    @Test
    void equalScreenYSpritesRetainGridTraversalOrder() {
        RecordingGraphicsManager graphics = new RecordingGraphicsManager();
        Sonic3kSpecialStageRenderer renderer = configuredGridRenderer(graphics);
        Sonic3kSpecialStageManager manager = configuredGridManager();
        manager.getGrid().setCellByIndex(0x2C8, Sonic3kSpecialStageConstants.CELL_BLUE);
        manager.getGrid().setCellByIndex(0x2C9, Sonic3kSpecialStageConstants.CELL_RING);

        renderer.render(manager);

        assertEquals(List.of(
                new RenderCall(0x1000 + 0x74, 2),
                new RenderCall(0x2000 + 0x48, 2)), graphics.calls);
    }

    @Test
    void spritePrimitiveBuffersGrowOnceThenReuseBackingArraysFor600Frames() {
        RecordingGraphicsManager graphics = new RecordingGraphicsManager();
        Sonic3kSpecialStageRenderer renderer = configuredGridRenderer(graphics);
        Sonic3kSpecialStageManager manager = configuredGridManager();
        manager.getGrid().setCellByIndex(0x2C8, Sonic3kSpecialStageConstants.CELL_BLUE);
        renderer.render(manager);

        int[] initialCellTypes = intArrayField(renderer, "spriteCellTypes");
        int initialCapacity = initialCellTypes.length;

        for (int i = 0; i < Sonic3kSpecialStageConstants.GRID_CELL_COUNT; i++) {
            manager.getGrid().setCellByIndex(i, Sonic3kSpecialStageConstants.CELL_BLUE);
        }
        graphics.calls.clear();
        renderer.render(manager);

        int[] grownCellTypes = intArrayField(renderer, "spriteCellTypes");
        assertTrue(grownCellTypes.length > initialCapacity);
        assertTrue(grownCellTypes.length >= intField(renderer, "spriteCount"));
        int[] grownScreenXs = intArrayField(renderer, "spriteScreenXs");
        int[] grownScreenYs = intArrayField(renderer, "spriteScreenYs");
        int[] grownSizeIndices = intArrayField(renderer, "spriteSizeIndices");

        for (int frame = 0; frame < 600; frame++) {
            graphics.calls.clear();
            renderer.render(manager);
            assertSame(grownCellTypes, intArrayField(renderer, "spriteCellTypes"));
            assertSame(grownScreenXs, intArrayField(renderer, "spriteScreenXs"));
            assertSame(grownScreenYs, intArrayField(renderer, "spriteScreenYs"));
            assertSame(grownSizeIndices, intArrayField(renderer, "spriteSizeIndices"));
        }
    }

    @Test
    void hudEmeraldAndRingMappingsArePrivateStaticFinalFlatTables() {
        assertStableFlatTable("HUD_TEMPLATE_BORDER_COLUMNS");
        assertStableFlatTable("EMERALD_SIZE_MAP");
        assertStableFlatTable("RING_FRONT_SIZE_MAP");
        assertStableFlatTable("RING_SIDE_SIZE_MAP");
    }

    @Test
    void soloTailsMainSpriteAndTailAppendageUsePlayer2PaletteLine() {
        RecordingGraphicsManager graphics = new RecordingGraphicsManager();
        Sonic3kSpecialStageRenderer renderer = new Sonic3kSpecialStageRenderer(graphics);
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();

        set(manager, "playerCharacter", PlayerCharacter.TAILS_ALONE);
        set(manager, "tailsTailsMappingFrame", 0);
        manager.getPlayer().initialize(Sonic3kSpecialStageConstants.ANGLE_NORTH, 0x1000, 0x1000, false);

        int playerBase = 0x5000;
        int tailsTailBase = 0x6000;
        renderer.setPlayerPatternBase(playerBase);
        renderer.setTailsTailsPatternBase(tailsTailBase);
        renderer.setSonicMappingData(singleTileMappingData(12), singleTileMappingData(12));
        renderer.setTailsTailsMappingData(singleTileMappingData(15));
        renderer.setArtLoaded(true);

        renderer.render(manager);

        assertEquals(List.of(
                        new RenderCall(playerBase, 1),
                        new RenderCall(tailsTailBase, 1)),
                graphics.calls);
    }

    private static byte[] singleTileMappingData(int frameCount) {
        int dplcHeader = frameCount * 2;
        int dplcFrame = frameCount * 4;
        int mapFrame = dplcFrame + 4;
        byte[] data = new byte[mapFrame + 8];
        writeWord(data, 0, mapFrame);
        writeWord(data, dplcHeader, dplcFrame - dplcHeader);
        writeWord(data, dplcFrame, 1);
        writeWord(data, dplcFrame + 2, 0);
        writeWord(data, mapFrame, 1);
        data[mapFrame + 2] = 0;
        data[mapFrame + 3] = 0;
        writeWord(data, mapFrame + 4, 0);
        writeWord(data, mapFrame + 6, 0);
        return data;
    }

    private static Sonic3kSpecialStageRenderer configuredGridRenderer(RecordingGraphicsManager graphics) {
        Sonic3kSpecialStageRenderer renderer = new Sonic3kSpecialStageRenderer(graphics);
        renderer.setArtLoaded(true);
        renderer.setSpherePatternBase(0x1000);
        renderer.setRingPatternBase(0x2000);
        renderer.setPerspectiveMaps(perspectiveMapWithUniformDepth());
        return renderer;
    }

    private static Sonic3kSpecialStageManager configuredGridManager() {
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        manager.getPlayer().initialize(Sonic3kSpecialStageConstants.ANGLE_NORTH, 0x1000, 0x1000, false);
        set(manager, "playerCharacter", PlayerCharacter.SONIC_ALONE);
        set(manager, "tailsEnabled", false);
        return manager;
    }

    private static byte[] perspectiveMapWithUniformDepth() {
        int pointerTableLength = 24 * 4;
        byte[] data = new byte[pointerTableLength + 16 * 15 * 6];
        writeLong(data, 0, 0x00FF0000 + pointerTableLength);
        for (int i = 0; i < 16 * 15; i++) {
            int offset = pointerTableLength + i * 6;
            writeWord(data, offset, 18 << 2);
            writeWord(data, offset + 2, 128 + i);
            writeWord(data, offset + 4, 128 + 100);
        }
        return data;
    }

    private static void writeLong(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    private static int[] intArrayField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (int[]) field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Missing reusable primitive buffer " + name, e);
        }
    }

    private static int intField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Missing primitive count " + name, e);
        }
    }

    private static void assertStableFlatTable(String name) {
        try {
            Field field = Sonic3kSpecialStageRenderer.class.getDeclaredField(name);
            int modifiers = field.getModifiers();
            assertTrue(Modifier.isPrivate(modifiers));
            assertTrue(Modifier.isStatic(modifiers));
            assertTrue(Modifier.isFinal(modifiers));
            assertEquals(int[].class, field.getType());
            field.setAccessible(true);
            Object identity = field.get(null);
            new Sonic3kSpecialStageRenderer(new RecordingGraphicsManager());
            assertSame(identity, field.get(null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Missing stable mapping table " + name, e);
        }
    }

    private static void writeWord(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private record RenderCall(int patternId, int paletteIndex) {}

    private static final class RecordingGraphicsManager extends GraphicsManager {
        final List<RenderCall> calls = new ArrayList<>();

        @Override
        public void beginPatternBatch() {
        }

        @Override
        public void flushPatternBatch() {
        }

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
            calls.add(new RenderCall(patternId, desc.getPaletteIndex()));
        }
    }
}
