package com.openggf.game.sonic2.specialstage;

import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.PatternDesc;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class Sonic2SpecialStageRendererDeterminismTest {

    @Test
    void capturedFailureExecutesArmedFboEndOnceAndDiscardsOrdinaryTail() {
        GraphicsManager graphics = new GraphicsManager();
        graphics.setGlInitialized(true);
        Sonic2SpecialStageManager.BackgroundCommandPool pool =
                new Sonic2SpecialStageManager.BackgroundCommandPool();
        SpecialStageBackgroundRenderer recordingRenderer = mock(SpecialStageBackgroundRenderer.class);
        TrackingDiscardCommand ordinaryTail = new TrackingDiscardCommand();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> graphics.executeCapturedCommands(() -> {
                    graphics.registerCommand(pool.obtainBegin(recordingRenderer));
                    graphics.registerCommand((cameraX, cameraY, cameraWidth, cameraHeight) -> {
                        throw new IllegalStateException("middle");
                    });
                    graphics.registerCommand(pool.obtainEnd(recordingRenderer));
                    graphics.registerCommand(ordinaryTail);
                }, 0, 0, 320, 224));

        assertEquals("middle", failure.getMessage());
        verify(recordingRenderer, times(1)).beginTilePass(Sonic2SpecialStageManager.H32_HEIGHT);
        verify(recordingRenderer, times(1)).endTilePass();
        assertEquals(0, ordinaryTail.executions);
        assertEquals(1, ordinaryTail.discards);
    }

    @Test
    void deferredBackgroundCommandsRetainFramesReleaseOnFailureAndReuseTwoIdentities() {
        Sonic2SpecialStageManager.BackgroundCommandPool pool =
                new Sonic2SpecialStageManager.BackgroundCommandPool();
        Sonic2SpecialStageManager.BackgroundCommand frameN = pool.obtainShader(null, 3, 5.0f);
        Sonic2SpecialStageManager.BackgroundCommand frameNPlusOne = pool.obtainShader(null, 7, 11.0f);

        assertEquals(3, frameN.hScroll());
        assertEquals(5.0f, frameN.vScroll());
        assertEquals(7, frameNPlusOne.hScroll());
        assertEquals(11.0f, frameNPlusOne.vScroll());
        frameN.discard();
        frameNPlusOne.discard();

        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int frame = 0; frame < 600; frame++) {
            Sonic2SpecialStageManager.BackgroundCommand current = pool.obtainShader(null, frame, frame + 0.5f);
            Sonic2SpecialStageManager.BackgroundCommand next = pool.obtainShader(null, frame + 1, frame + 1.5f);
            identities.add(current);
            identities.add(next);
            current.discard();
            next.discard();
        }
        assertEquals(2, identities.size());

        SpecialStageBackgroundRenderer throwingRenderer = mock(SpecialStageBackgroundRenderer.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("expected"))
                .when(throwingRenderer).renderWithShader(anyFloat());
        Sonic2SpecialStageManager.BackgroundCommand throwing =
                pool.obtainShader(throwingRenderer, 13, 17.0f);
        assertThrows(IllegalStateException.class, () -> throwing.execute(0, 0, 320, 224));
        Sonic2SpecialStageManager.BackgroundCommand reused = pool.obtainShader(null, 0, 0.0f);
        assertSame(throwing, reused, "execute failure must release the command in finally");
        reused.discard();
    }

    private static final class TrackingDiscardCommand implements GLCommandable {
        private int executions;
        private int discards;

        @Override
        public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            executions++;
        }

        @Override
        public void discard() {
            discards++;
        }
    }

    @Test
    void unchangedSnapshotRestoreKeepsStaticFboAndQueuesShaderAfterOrderedBuild() throws Exception {
        FboFixture fixture = fboFixture();

        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("beginTilePass", "mappingBatch", "endTilePass", "shader"),
                fixture.graphics.events);
        Sonic2SpecialStageSnapshot snapshot = fixture.manager.captureRewindSnapshot();

        fixture.graphics.clearFrame();
        fixture.manager.restoreRewindSnapshot(snapshot);
        fixture.manager.draw();
        fixture.graphics.executeQueued();

        assertEquals(List.of("shader"), fixture.graphics.events,
                "unchanged exact-keyframe restores must retain the existing static FBO");

        fixture.graphics.clearFrame();
        fixture.renderer.beginStaticBackgroundStage(2);
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("beginTilePass", "mappingBatch", "endTilePass", "shader"),
                fixture.graphics.events,
                "a real stage generation change must rebuild before the live shader draw");
    }

    @Test
    void onlyPaletteRowsContributingToMappingsInvalidateStaticFbo() throws Exception {
        FboFixture fixture = fboFixture();
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        Object paletteContent = getField(fixture.renderer, "cachedBackgroundPaletteContent");

        fixture.graphics.clearFrame();
        Palette[] palettes = (Palette[]) getField(fixture.manager, "palettes");
        palettes[1].getColor(3).r++;
        palettes[3].getColor(7).g++;
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("shader"),
                fixture.graphics.events,
                "unused palette rows do not contribute to palette-resolved FBO pixels");
        assertSame(paletteContent, getField(fixture.renderer, "cachedBackgroundPaletteContent"),
                "same-sized palette snapshots must reuse retained byte storage");

        fixture.graphics.clearFrame();
        palettes[0].getColor(3).r++;
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("beginTilePass", "mappingBatch", "endTilePass", "shader"),
                fixture.graphics.events,
                "the palette row selected by the non-empty mapping must rebuild once");

        fixture.graphics.clearFrame();
        Palette[] equalReplacement = new Palette[palettes.length];
        for (int i = 0; i < palettes.length; i++) {
            equalReplacement[i] = palettes[i].deepCopy();
        }
        setField(fixture.manager, "palettes", equalReplacement);
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("shader"), fixture.graphics.events,
                "equal palette content must remain a static-target cache hit");
        assertSame(paletteContent, getField(fixture.renderer, "cachedBackgroundPaletteContent"));

        fixture.graphics.clearFrame();
        byte[] mappings = (byte[]) getField(fixture.manager, "combinedBackgroundMappings");
        mappings[0] = 0x20; // select palette row 1
        mappings[1] = 1;
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("beginTilePass", "mappingBatch", "endTilePass", "shader"),
                fixture.graphics.events);
        assertEquals(1 << 1, getField(fixture.renderer, "cachedBackgroundPaletteRowMask"));

        fixture.graphics.clearFrame();
        equalReplacement[0].getColor(3).b++;
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("shader"), fixture.graphics.events,
                "removing row 0 from mappings removes its palette contribution");

        fixture.graphics.clearFrame();
        equalReplacement[1].getColor(3).b++;
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("beginTilePass", "mappingBatch", "endTilePass", "shader"),
                fixture.graphics.events,
                "newly introduced row 1 becomes a live contribution");

        fixture.graphics.clearFrame();
        equalReplacement[1] = null;
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("beginTilePass", "mappingBatch", "endTilePass", "shader"),
                fixture.graphics.events, "a contributing row becoming absent invalidates once");

        fixture.graphics.clearFrame();
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("shader"), fixture.graphics.events,
                "a stable absent contributing row is cacheable");

        fixture.graphics.clearFrame();
        mappings[0] = 0;
        mappings[1] = 0;
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("beginTilePass", "mappingBatch", "endTilePass", "shader"),
                fixture.graphics.events);
        assertEquals(0, getField(fixture.renderer, "cachedBackgroundPaletteRowMask"));
        assertSame(paletteContent, getField(fixture.renderer, "cachedBackgroundPaletteContent"),
                "palette contribution storage stays bounded and reusable as the mask changes");

        fixture.graphics.clearFrame();
        equalReplacement[0].getColor(5).g++;
        fixture.manager.draw();
        fixture.graphics.executeQueued();
        assertEquals(List.of("shader"), fixture.graphics.events,
                "an empty mapping plane has no contributing palette rows");
    }

    @Test
    void unchangedBackgroundMappingsBuildStaticTargetOnlyOnce() {
        RecordingGraphicsManager graphics = new RecordingGraphicsManager();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);
        renderer.setPatternBases(0x2000, 0x3000);
        byte[] mappings = new byte[32 * 2];
        mappings[1] = 1;

        renderer.renderBackgroundToFBO(mappings);
        renderer.renderBackgroundToFBO(mappings);

        assertEquals(1, graphics.renderCount,
                "unchanged static mappings must not be rebuilt into the FBO every frame");

        renderer.onRenderContextGenerationChanged(new Object());
        renderer.renderBackgroundToFBO(mappings);
        assertEquals(2, graphics.renderCount, "a new render context must rebuild the static target");

        renderer.beginStaticBackgroundStage(1);
        renderer.renderBackgroundToFBO(mappings);
        assertEquals(3, graphics.renderCount, "a new stage generation must rebuild the static target");

        mappings[1] = 2;
        renderer.renderBackgroundToFBO(mappings);
        assertEquals(4, graphics.renderCount, "in-place authoritative mapping changes must invalidate by content");
    }

    @Test
    void decodedTrackCacheUsesCompleteBoundedKeyAndDefensiveCopies() throws Exception {
        Method decodeCached = Sonic2TrackFrameDecoder.class.getDeclaredMethod(
                "decodeFrameCached", int.class, byte[].class, boolean.class);
        Method clearCache = Sonic2TrackFrameDecoder.class.getDeclaredMethod("invalidateDecodedFrameCache");
        Method buildCount = Sonic2TrackFrameDecoder.class.getDeclaredMethod("decodedFrameBuildCountForTesting");
        Method cacheSize = Sonic2TrackFrameDecoder.class.getDeclaredMethod("decodedFrameCacheSizeForTesting");
        Method identity = Sonic2TrackFrameDecoder.class.getDeclaredMethod(
                "decodedFrameIdentityForTesting", int.class, boolean.class);
        for (Method method : List.of(decodeCached, clearCache, buildCount, cacheSize, identity)) {
            method.setAccessible(true);
        }
        clearCache.invoke(null);

        byte[] frame = new byte[12];
        int[] first = (int[]) decodeCached.invoke(null, 7, frame, false);
        Object cachedIdentity = identity.invoke(null, 7, false);
        first[0] = 0x7FFF;
        int[] repeated = (int[]) decodeCached.invoke(null, 7, frame.clone(), false);

        assertEquals(1, buildCount.invoke(null));
        assertEquals(1, cacheSize.invoke(null));
        assertEquals(0, repeated[0], "callers must not mutate the cached decoded array");
        assertNotSame(first, repeated, "cached arrays must be defensively copied to consumers");
        assertSame(cachedIdentity, identity.invoke(null, 7, false),
                "an unchanged complete key must reuse the same internal immutable entry");

        decodeCached.invoke(null, 7, frame, true);
        decodeCached.invoke(null, 8, frame, false);
        assertEquals(3, buildCount.invoke(null), "frame and flip are both output-affecting key parts");
        assertEquals(3, cacheSize.invoke(null));
        assertNotSame(identity.invoke(null, 7, false), identity.invoke(null, 7, true));
        assertNotSame(identity.invoke(null, 7, false), identity.invoke(null, 8, false));

        Object beforeContentChange = identity.invoke(null, 7, false);
        frame[0] = 1;
        decodeCached.invoke(null, 7, frame, false);
        assertEquals(4, buildCount.invoke(null), "authoritative bytes complete the bounded slot key");
        assertEquals(3, cacheSize.invoke(null), "changed content replaces its finite frame/flip slot");
        assertNotSame(beforeContentChange, identity.invoke(null, 7, false));
        assertTrue((int) cacheSize.invoke(null) <= 56 * 2, "finite 56-frame domain must bound the cache");
    }

    /**
     * The player invulnerability flash must be driven purely by the manager-owned
     * special-stage frame counter, not by wall-clock time. This is verified
     * behaviourally: feeding the renderer the same frame counter must always yield the
     * same flash state, and advancing the counter across the flash period must flip it
     * deterministically.
     */
    @Test
    public void playerFlashingUsesSpecialStageFrameCounterInsteadOfWallClock() throws Exception {
        // GraphicsManager is not touched by the flash predicate, so null is fine here.
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);

        Sonic2SpecialStagePlayer player = mock(Sonic2SpecialStagePlayer.class);
        when(player.isInvulnerable()).thenReturn(true);

        Method flashHidden = Sonic2SpecialStageRenderer.class
                .getDeclaredMethod("isInvulnerabilityFlashHidden", Sonic2SpecialStagePlayer.class);
        flashHidden.setAccessible(true);

        // Same frame counter -> identical flash state, repeatedly (no wall-clock sampling).
        renderer.setFrameCounter(0);
        boolean firstReadAtZero = (boolean) flashHidden.invoke(renderer, player);
        renderer.setFrameCounter(0);
        boolean secondReadAtZero = (boolean) flashHidden.invoke(renderer, player);
        assertEquals(firstReadAtZero, secondReadAtZero,
                "Flash state must be a deterministic function of the frame counter, not wall-clock time");
        assertFalse(firstReadAtZero, "Flash should be visible (not hidden) at frame counter 0");

        // Advancing the counter into the next flash half-period flips the state.
        renderer.setFrameCounter(8); // (8 >> 3) & 1 == 1 -> hidden
        boolean readAtEight = (boolean) flashHidden.invoke(renderer, player);
        assertTrue(readAtEight, "Flash should be hidden at frame counter 8");

        // Returning to an earlier counter restores the earlier state (pure function of counter).
        renderer.setFrameCounter(0);
        boolean readBackAtZero = (boolean) flashHidden.invoke(renderer, player);
        assertEquals(firstReadAtZero, readBackAtZero,
                "Re-applying an earlier frame counter must reproduce the earlier flash state");
    }

    @Test
    public void mixedSpawnStateRendersOnlySpawnedPlayer() {
        GraphicsManager graphics = mock(GraphicsManager.class);
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);

        Sonic2SpecialStagePlayer spawned = mock(Sonic2SpecialStagePlayer.class);
        when(spawned.isSpawned()).thenReturn(true);
        when(spawned.getRoutine()).thenReturn(Sonic2SpecialStagePlayer.RoutineState.NORMAL);
        when(spawned.getPlayerType()).thenReturn(Sonic2SpecialStagePlayer.PlayerType.SONIC);

        Sonic2SpecialStagePlayer spawnedInit = mock(Sonic2SpecialStagePlayer.class);
        when(spawnedInit.isSpawned()).thenReturn(true);
        when(spawnedInit.getRoutine()).thenReturn(Sonic2SpecialStagePlayer.RoutineState.INIT);
        when(spawnedInit.getPlayerType()).thenReturn(Sonic2SpecialStagePlayer.PlayerType.SONIC);

        Sonic2SpecialStagePlayer unspawned = mock(Sonic2SpecialStagePlayer.class);
        when(unspawned.isSpawned()).thenReturn(false);
        when(unspawned.getPlayerType()).thenReturn(Sonic2SpecialStagePlayer.PlayerType.TAILS);

        renderer.setPlayers(List.of(spawned, spawnedInit, unspawned));
        renderer.renderPlayers();

        verify(spawned).getMappingFrame();
        verify(spawnedInit, never()).getMappingFrame();
        verify(unspawned, never()).getMappingFrame();
    }

    @Test
    void tailsUsesObj10SourcePatternsAndRendersObj88BeforeItsBody() {
        PatternRecordingGraphicsManager graphics = new PatternRecordingGraphicsManager();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);
        renderer.setPlayerPatternBase(0x4000);

        Sonic2SpecialStagePlayer tails = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.TAILS, false,
                new Sonic2SpecialStageManager());
        tails.initializeScalarStateFromRomObjectRoutine();
        tails.setSpawned(true);
        renderer.setPlayers(List.of(tails));

        renderer.renderPlayers();

        assertEquals(0x4000 + Sonic2SpecialStageSpriteMappings.TAILS_TAILS_UPRIGHT_ART_BASE,
                graphics.patternIds.get(0),
                "Obj88 priority is one below the Tails body, so its first translated source tile draws first");
        assertEquals(0x4000 + Sonic2SpecialStageSpriteMappings.TAILS_UPRIGHT_ART_BASE,
                graphics.patternIds.get(6),
                "Obj10 frame 0 must use Tails' DPLC-translated source art instead of Sonic mappings plus an offset");
        assertEquals(22, graphics.patternIds.size(), "Obj88 frame 0 has 6 tiles and Obj10 frame 0 has 16");
    }

    @Test
    void obj88IsPrioritySortedAgainstOverlappingPlayerBodies() throws Exception {
        PatternRecordingGraphicsManager graphics = new PatternRecordingGraphicsManager();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);
        renderer.setPlayerPatternBase(0x4000);
        Sonic2SpecialStageManager owner = new Sonic2SpecialStageManager();
        Sonic2SpecialStagePlayer sonic = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true, owner);
        Sonic2SpecialStagePlayer tails = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.TAILS, false, owner);
        sonic.initializeScalarStateFromRomObjectRoutine();
        tails.initializeScalarStateFromRomObjectRoutine();
        sonic.setSpawned(true);
        tails.setSpawned(true);
        setField(sonic, "priority", tails.getPriority() - 1);
        renderer.setPlayers(List.of(sonic, tails));

        renderer.renderPlayers();

        int sonicBody = graphics.patternIds.indexOf(0x4000);
        int tailsTails = graphics.patternIds.indexOf(
                0x4000 + Sonic2SpecialStageSpriteMappings.TAILS_TAILS_UPRIGHT_ART_BASE);
        int tailsBody = graphics.patternIds.indexOf(
                0x4000 + Sonic2SpecialStageSpriteMappings.TAILS_UPRIGHT_ART_BASE);
        assertTrue(tailsTails < sonicBody,
                "Obj88 submits first so the later-submitted earlier-slot player body visually wins a priority tie");
        assertTrue(tailsTails < tailsBody, "Obj88 remains one priority bucket behind its parent body");
    }

    @Test
    void horizontalTailsBodyUsesPaletteTwoAndNoCorrespondingSonicSources() throws Exception {
        PatternRecordingGraphicsManager graphics = new PatternRecordingGraphicsManager();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);
        renderer.setPlayerPatternBase(0x4000);
        Sonic2SpecialStagePlayer tails = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.TAILS, false,
                new Sonic2SpecialStageManager());
        setField(tails, "mappingFrame", 12);

        Method renderPlayer = Sonic2SpecialStageRenderer.class
                .getDeclaredMethod("renderPlayer", Sonic2SpecialStagePlayer.class);
        renderPlayer.setAccessible(true);
        renderPlayer.invoke(renderer, tails);

        Set<Integer> sonicSources = sourcePatternIds(
                Sonic2SpecialStageSpriteMappings.getSonicFrame(12), 0x4000);
        assertTrue(graphics.paletteIndexes.stream().allMatch(palette -> palette == 2),
                "every Obj10 body tile uses Tails' special-stage palette line");
        assertTrue(graphics.patternIds.stream().noneMatch(sonicSources::contains),
                "horizontal Obj10 body tiles must not fall back to the corresponding Obj09 source IDs");
        assertTrue(graphics.patternIds.stream().allMatch(id ->
                        id >= 0x4000 + Sonic2SpecialStageSpriteMappings.TAILS_HORIZONTAL_ART_BASE),
                "representative horizontal art comes from the Tails horizontal source section");
    }

    private static Set<Integer> sourcePatternIds(
            Sonic2SpecialStageSpriteMappings.SpriteFrame frame, int patternBase) {
        Set<Integer> result = new java.util.HashSet<>();
        for (Sonic2SpecialStageSpriteMappings.SpritePiece piece : frame.pieces) {
            for (int tile = 0; tile < piece.widthTiles * piece.heightTiles; tile++) {
                result.add(patternBase + piece.tileIndex + tile);
            }
        }
        return result;
    }

    private static final class RecordingGraphicsManager extends GraphicsManager {
        int renderCount;

        @Override
        public void beginPatternBatch() {
        }

        @Override
        public void flushPatternBatch() {
        }

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
            renderCount++;
        }
    }

    private static final class PatternRecordingGraphicsManager extends GraphicsManager {
        final List<Integer> patternIds = new ArrayList<>();
        final List<Integer> paletteIndexes = new ArrayList<>();

        @Override
        public void beginPatternBatch() {
        }

        @Override
        public void flushPatternBatch() {
        }

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
            patternIds.add(patternId);
            paletteIndexes.add(desc.getPaletteIndex());
        }
    }

    private static FboFixture fboFixture() throws Exception {
        QueueRecordingGraphicsManager graphics = new QueueRecordingGraphicsManager();
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager(
                new com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug(), null, graphics);
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(graphics);
        SpecialStageBackgroundRenderer background = mock(SpecialStageBackgroundRenderer.class);
        when(background.isInitialized()).thenReturn(true);
        doAnswer(invocation -> {
            graphics.events.add("beginTilePass");
            return null;
        }).when(background).beginTilePass(Sonic2SpecialStageManager.H32_HEIGHT);
        doAnswer(invocation -> {
            graphics.events.add("endTilePass");
            return null;
        }).when(background).endTilePass();
        doAnswer(invocation -> {
            graphics.events.add("shader");
            return null;
        }).when(background).renderWithShader(anyFloat());

        byte[] mappings = new byte[32 * 2];
        mappings[1] = 1;
        setField(manager, "combinedBackgroundMappings", mappings);
        setField(manager, "palettes", createTestPalettes());
        setField(manager, "renderer", renderer);
        setField(manager, "bgRenderer", background);
        setField(manager, "initialized", true);
        setField(manager, "alignmentTestMode", true);
        setField(manager, "planeDebugMode", enumFieldValue(manager, "planeDebugMode", "PLANE_B_ONLY"));
        return new FboFixture(manager, renderer, graphics);
    }

    private static Palette[] createTestPalettes() {
        Palette[] palettes = new Palette[4];
        for (int line = 0; line < palettes.length; line++) {
            palettes[line] = new Palette();
            for (int color = 0; color < Palette.PALETTE_SIZE; color++) {
                palettes[line].setColor(color, new Palette.Color(
                        (byte) (line * 16 + color),
                        (byte) (line * 16 + color + 1),
                        (byte) (line * 16 + color + 2)));
            }
        }
        return palettes;
    }

    private static Object enumFieldValue(Object target, String fieldName, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        for (Object constant : field.getType().getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException(value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record FboFixture(
            Sonic2SpecialStageManager manager,
            Sonic2SpecialStageRenderer renderer,
            QueueRecordingGraphicsManager graphics) {
    }

    private static final class QueueRecordingGraphicsManager extends GraphicsManager {
        final List<GLCommandable> queued = new ArrayList<>();
        final List<String> events = new ArrayList<>();

        @Override
        public void registerCommand(GLCommandable command) {
            queued.add(command);
        }

        @Override
        public void beginPatternBatch() {
        }

        @Override
        public void flushPatternBatch() {
            registerCommand((cameraX, cameraY, cameraWidth, cameraHeight) -> events.add("mappingBatch"));
        }

        @Override
        public void renderPatternWithId(int patternId, PatternDesc desc, int x, int y) {
        }

        void executeQueued() {
            List<GLCommandable> frame = List.copyOf(queued);
            queued.clear();
            for (GLCommandable command : frame) {
                command.execute(0, 0, 320, 224);
            }
        }

        void clearFrame() {
            queued.clear();
            events.clear();
        }
    }
}
