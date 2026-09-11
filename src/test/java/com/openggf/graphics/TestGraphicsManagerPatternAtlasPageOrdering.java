package com.openggf.graphics;

import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGraphicsManagerPatternAtlasPageOrdering {
    private GraphicsManager manager;
    private int pageOneId;
    private PatternAtlas atlas;

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
        setField(manager, "patternAtlas", atlas);
        setField(manager, "combinedPaletteTextureId", 1);

        InstancedPatternRenderer renderer = new InstancedPatternRenderer(
                manager, com.openggf.game.GameServices.configuration());
        setField(renderer, "supported", true);
        setField(manager, "instancedPatternRenderer", renderer);
        manager.setHeadlessMode(false); // batching construction is GL-free; commands are not executed
    }

    @AfterEach
    void tearDown() {
        manager.setHeadlessMode(true);
        GraphicsManager.destroyForReinit();
    }

    @Test
    void generalBatchingFlushesInSubmissionOrderAcrossAtlasPages() throws Exception {
        PatternDesc desc = new PatternDesc();
        manager.beginPatternBatch();
        manager.renderPatternWithId(0, desc, 10, 10);
        manager.renderPatternWithId(pageOneId, desc, 20, 10);
        manager.renderPatternWithId(0, desc, 30, 10);
        manager.flushPatternBatch();

        assertEquals(List.of(0, 1, 0), queuedAtlasPages());
    }

    @Test
    void scaledDirectDrawFlushesBeforeItAndRestartsBatchAfterIt() throws Exception {
        PatternDesc desc = new PatternDesc();
        manager.beginPatternBatch();
        manager.renderPatternWithId(0, desc, 10, 10);
        manager.renderPatternWithIdScaled(pageOneId, desc, 20, 10, 16, 16);
        manager.renderPatternWithId(0, desc, 30, 10);
        manager.flushPatternBatch();

        assertEquals(List.of("InstancedBatchCommand", "PatternRenderCommand", "InstancedBatchCommand"),
                manager.commands.stream().map(c -> c.getClass().getSimpleName()).toList());
    }

    @Test
    void stripPatternsBatchOnOverflowAtlasPage() throws Exception {
        PatternDesc desc = new PatternDesc();
        manager.beginPatternBatch();
        manager.renderStripPatternWithId(pageOneId, desc, 10, 10, 0);
        manager.flushPatternBatch();

        assertEquals(List.of(1), queuedAtlasPages());
    }

    @Test
    void stripFallsBackDirectlyWhenInstancingIsInactiveAndPreservesUvs() throws Exception {
        setField(manager, "instancedPatternRenderer", null);
        PatternDesc desc = new PatternDesc();
        desc.setHFlip(true);
        desc.setVFlip(false);

        manager.renderStripPatternWithId(pageOneId, desc, 10, 20, 2);

        assertEquals(1, manager.commands.size());
        Object command = manager.commands.get(0);
        PatternAtlas.Entry entry = atlas.getEntry(pageOneId);
        float rowStep = (entry.v1() - entry.v0()) / 8f;
        float stripTop = entry.v0() + rowStep * 3.5f;
        float stripBottom = entry.v0() + rowStep * 2.5f;
        assertEquals("PatternRenderCommand", command.getClass().getSimpleName());
        assertEquals(entry.u1(), (float) commandField(command, "u0"));
        assertEquals(entry.u0(), (float) commandField(command, "u1"));
        assertEquals(stripBottom, (float) commandField(command, "v0"));
        assertEquals(stripTop, (float) commandField(command, "v1"));
        assertEquals(2f, (float) commandField(command, "height"));
    }

    @Test
    void stripFallsBackDirectlyWhenBatchingIsDisabled() {
        manager.setBatchingEnabled(false);
        manager.renderStripPatternWithId(pageOneId, new PatternDesc(), 10, 20, 0);
        assertEquals(1, manager.commands.size());
        assertTrue(manager.commands.get(0) instanceof PatternRenderCommand);
    }

    @Test
    void directStripUsesEffectiveUnderwaterPalette() throws Exception {
        manager.setBatchingEnabled(false);
        setField(manager, "combinedPaletteTextureId", 11);
        setField(manager, "underwaterPaletteTextureId", 22);
        manager.setUseUnderwaterPaletteForBackground(true);

        manager.renderStripPatternWithId(pageOneId, new PatternDesc(), 10, 20, 0);

        assertEquals(1, manager.commands.size());
        assertEquals(22, commandField(manager.commands.get(0), "paletteTextureId"));
    }

    @Test
    void directStripQueuesWhenOnlyEffectiveUnderwaterPaletteExists() throws Exception {
        manager.setBatchingEnabled(false);
        setField(manager, "combinedPaletteTextureId", null);
        setField(manager, "underwaterPaletteTextureId", 22);
        manager.setUseUnderwaterPaletteForBackground(true);

        manager.renderStripPatternWithId(pageOneId, new PatternDesc(), 10, 20, 0);

        assertEquals(1, manager.commands.size());
        assertEquals(22, commandField(manager.commands.get(0), "paletteTextureId"));
    }

    @Test
    void legacyBatchFullFlushesAndRetriesStripWithoutDroppingIt() throws Exception {
        setField(manager, "instancedPatternRenderer", null);
        manager.setInstancedBatchingEnabled(false);
        PatternDesc desc = new PatternDesc();
        manager.beginPatternBatch();
        for (int i = 0; i < 4097; i++) {
            manager.renderStripPatternWithId(0, desc, i, 20, 0);
        }
        manager.flushPatternBatch();

        assertEquals(2, manager.commands.size(), "full legacy batch + retried one-strip batch");
        assertEquals(4096, commandField(manager.commands.get(0), "patternCount"));
        assertEquals(1, commandField(manager.commands.get(1), "patternCount"));
    }

    @Test
    void ghostStateChangeCreatesAnOrderedBatchBoundary() throws Exception {
        PatternDesc desc = new PatternDesc();
        manager.beginPatternBatch();
        manager.renderPatternWithId(0, desc, 10, 10);
        manager.beginGhostRenderEffect(0.5f);
        manager.renderPatternWithId(0, desc, 20, 10);
        manager.endGhostRenderEffect();
        manager.flushPatternBatch();

        assertEquals(2, manager.commands.size());
        assertEquals(false, commandField(manager.commands.get(0), "capturedGhostEffectActive"));
        assertEquals(true, commandField(manager.commands.get(1), "capturedGhostEffectActive"));
        assertEquals(0.5f, (float) commandField(manager.commands.get(1), "capturedGhostAlpha"));
    }

    private List<Integer> queuedAtlasPages() {
        return manager.commands.stream().map(command -> {
            try {
                Field field = command.getClass().getDeclaredField("atlasIndex");
                field.setAccessible(true);
                return field.getInt(command);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(command.getClass().getSimpleName() + " has no atlas page", e);
            }
        }).toList();
    }

    private static Object commandField(Object command, String name) {
        try {
            Field field = command.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(command);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        if (value instanceof Boolean b) {
            field.setBoolean(target, b);
        } else {
            field.set(target, value);
        }
    }
}
