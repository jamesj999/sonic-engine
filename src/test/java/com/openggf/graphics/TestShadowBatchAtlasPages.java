package com.openggf.graphics;

import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestShadowBatchAtlasPages {
    private GraphicsManager manager;
    private PatternAtlas atlas;
    private int pageOneId;

    @BeforeEach
    void setUp() throws Exception {
        GraphicsManager.destroyForReinit();
        TestEnvironment.resetAll();
        manager = GraphicsManager.getInstance();
        manager.initHeadless();
        atlas = new PatternAtlas(8, 8);
        pageOneId = PatternAtlasRange.OBJECTS.base();
        atlas.cachePatternHeadless(new Pattern(), 0);
        atlas.cachePatternHeadless(new Pattern(), pageOneId);
        atlas.assignHeadlessTextureIdsForTesting();
        setField(manager, "patternAtlas", atlas);
        manager.setHeadlessMode(false);
    }

    @AfterEach
    void tearDown() {
        manager.setHeadlessMode(true);
        GraphicsManager.destroyForReinit();
    }

    @Test
    void shadowPageTransitionsSplitInOrderAndResolveTheirOwnTexture() throws Exception {
        PatternDesc desc = new PatternDesc();
        manager.beginShadowBatch();
        manager.addShadowPattern(0, desc, 10, 10);
        manager.addShadowPattern(pageOneId, desc, 20, 10);
        manager.addShadowPattern(0, desc, 30, 10);
        manager.flushShadowBatch();

        assertEquals(3, manager.commands.size());
        assertEquals(List.of(0, 1, 0), manager.commands.stream().map(this::atlasPage).toList());
        assertEquals(atlas.getTextureId(0), resolvedTexture(manager.commands.get(0)));
        assertEquals(atlas.getTextureId(1), resolvedTexture(manager.commands.get(1)));
        assertNotEquals(resolvedTexture(manager.commands.get(0)), resolvedTexture(manager.commands.get(1)));
    }

    @Test
    void shadowBatchFullFlushesAndRetriesOnTheSamePage() throws Exception {
        PatternDesc desc = new PatternDesc();
        manager.beginShadowBatch();
        for (int i = 0; i < 4097; i++) {
            manager.addShadowPattern(0, desc, i, 10);
        }
        manager.flushShadowBatch();

        assertEquals(2, manager.commands.size());
        assertEquals(4096, field(manager.commands.get(0), "patternCount"));
        assertEquals(1, field(manager.commands.get(1), "patternCount"));
        assertEquals(List.of(0, 0), manager.commands.stream().map(this::atlasPage).toList());
    }

    private int atlasPage(Object command) {
        try {
            Field field = command.getClass().getDeclaredField("atlasIndex");
            field.setAccessible(true);
            return field.getInt(command);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private int field(Object command, String name) throws Exception {
        Field field = command.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(command);
    }

    private int resolvedTexture(Object command) throws Exception {
        Method method = command.getClass().getDeclaredMethod("resolveAtlasTextureId");
        method.setAccessible(true);
        return (Integer) method.invoke(command);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
