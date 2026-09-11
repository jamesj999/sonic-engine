package com.openggf.level;

import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.TilemapGpuRenderer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLevelRendererBackgroundViewport {

    @Test
    void staleBackgroundTileGenerationAlsoSuppressesItsPairedComposite() {
        LevelRenderer renderer = new LevelRenderer(null);
        TilemapGpuRenderer tilemap = new TilemapGpuRenderer();
        tilemap.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, new byte[32], 8, 1);
        int generation = tilemap.getBackgroundContentGeneration();
        renderer.setBackgroundCompositeStateForTesting(40, generation, 40, generation, generation);
        assertTrue(renderer.isBackgroundCompositeCurrentForTesting(tilemap));

        tilemap.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, new byte[32], 8, 1);
        assertFalse(renderer.isBackgroundCompositeCurrentForTesting(tilemap),
                "a newer tile registration must suppress the retained composite");

        int current = tilemap.getBackgroundContentGeneration();
        renderer.setBackgroundCompositeStateForTesting(41, current, 40, current, current);
        assertFalse(renderer.isBackgroundCompositeCurrentForTesting(tilemap),
                "a composite cannot reuse a completed FBO from another frame");
    }

    @Test
    void deferredFrameSnapshotsRetainViewportAndScrollAndReuseBoundedBackings() {
        LevelRenderer.FrameCommandPool pool = new LevelRenderer.FrameCommandPool();
        int[] viewportN = {1, 2, 320, 224};
        int[] scrollN = {3, 5, 7};
        LevelRenderer.FrameCommand frameN = pool.obtainForTesting(viewportN, scrollN);
        viewportN[0] = 11;
        scrollN[0] = 13;
        LevelRenderer.FrameCommand frameNPlusOne = pool.obtainForTesting(viewportN, scrollN);

        assertTrue(frameN.viewportAt(0) == 1 && frameN.scrollAt(0) == 3);
        assertTrue(frameNPlusOne.viewportAt(0) == 11 && frameNPlusOne.scrollAt(0) == 13);
        frameN.discard();
        frameNPlusOne.discard();

        Set<Object> commands = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> viewports = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> scrolls = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int frame = 0; frame < 600; frame++) {
            LevelRenderer.FrameCommand current = pool.obtainForTesting(viewportN, scrollN);
            LevelRenderer.FrameCommand next = pool.obtainForTesting(viewportN, scrollN);
            commands.add(current);
            commands.add(next);
            viewports.add(current.viewportBackingIdentity());
            viewports.add(next.viewportBackingIdentity());
            scrolls.add(current.scrollBackingIdentity());
            scrolls.add(next.scrollBackingIdentity());
            current.discard();
            next.discard();
        }
        assertTrue(commands.size() == 2 && viewports.size() == 2 && scrolls.size() == 2);
    }

    @Test
    void deferredScrollScratchGrowsWithoutReallocationOnShrinkAndPreservesLogicalLengths() {
        LevelRenderer.FrameCommandPool pool = new LevelRenderer.FrameCommandPool();
        int[] viewport = {0, 0, 320, 224};
        int[] maxInts = sequence(17, 100);
        short[] maxShorts = shortSequence(16, 200);
        short[] maxColumnShorts = shortSequence(15, 300);

        LevelRenderer.FrameCommand warmN = pool.obtainForTesting(viewport, maxInts, maxShorts, maxColumnShorts);
        LevelRenderer.FrameCommand warmNPlusOne = pool.obtainForTesting(viewport, maxInts, maxShorts, maxColumnShorts);
        Object intBackingN = warmN.scrollBackingIdentity();
        Object intBackingNPlusOne = warmNPlusOne.scrollBackingIdentity();
        Object shortBackingN = warmN.shortScrollBackingIdentity();
        Object shortBackingNPlusOne = warmNPlusOne.shortScrollBackingIdentity();
        Object columnShortBackingN = warmN.columnShortScrollBackingIdentity();
        Object columnShortBackingNPlusOne = warmNPlusOne.columnShortScrollBackingIdentity();
        warmN.discard();
        warmNPlusOne.discard();

        Set<Object> intBackings = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> shortBackings = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> columnShortBackings = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int frame = 0; frame < 600; frame++) {
            int firstLength = switch (frame & 3) { case 0 -> 2; case 1 -> 11; case 2 -> 5; default -> 17; };
            int secondLength = switch (frame & 3) { case 0 -> 13; case 1 -> 3; case 2 -> 9; default -> 1; };
            int[] firstInts = sequence(firstLength, frame * 10);
            int[] secondInts = sequence(secondLength, frame * 10 + 1000);
            short[] firstShorts = shortSequence(Math.max(1, firstLength - 1), frame);
            short[] secondShorts = shortSequence(Math.max(1, secondLength - 1), frame + 1000);
            short[] firstColumnShorts = shortSequence(Math.max(1, firstLength - 2), frame + 2000);
            short[] secondColumnShorts = shortSequence(Math.max(1, secondLength - 2), frame + 3000);

            LevelRenderer.FrameCommand first = pool.obtainForTesting(
                    viewport, firstInts, firstShorts, firstColumnShorts);
            LevelRenderer.FrameCommand second = pool.obtainForTesting(
                    viewport, secondInts, secondShorts, secondColumnShorts);
            assertEquals(firstInts.length, first.scrollLength());
            assertEquals(secondInts.length, second.scrollLength());
            assertEquals(firstShorts.length, first.shortScrollLength());
            assertEquals(secondShorts.length, second.shortScrollLength());
            assertEquals(firstColumnShorts.length, first.columnShortScrollLength());
            assertEquals(secondColumnShorts.length, second.columnShortScrollLength());
            assertTrue(first.scrollCapacity() >= 17 && second.scrollCapacity() >= 17);
            assertTrue(first.shortScrollCapacity() >= 16 && second.shortScrollCapacity() >= 16);
            assertTrue(first.columnShortScrollCapacity() >= 15 && second.columnShortScrollCapacity() >= 15);
            for (int i = 0; i < firstInts.length; i++) assertEquals(firstInts[i], first.scrollAt(i));
            for (int i = 0; i < secondInts.length; i++) assertEquals(secondInts[i], second.scrollAt(i));
            for (int i = 0; i < firstShorts.length; i++) assertEquals(firstShorts[i], first.shortScrollAt(i));
            for (int i = 0; i < secondShorts.length; i++) assertEquals(secondShorts[i], second.shortScrollAt(i));
            for (int i = 0; i < firstColumnShorts.length; i++) {
                assertEquals(firstColumnShorts[i], first.columnShortScrollAt(i));
            }
            for (int i = 0; i < secondColumnShorts.length; i++) {
                assertEquals(secondColumnShorts[i], second.columnShortScrollAt(i));
            }
            assertThrows(IndexOutOfBoundsException.class, () -> first.scrollAt(firstInts.length));
            assertThrows(IndexOutOfBoundsException.class, () -> first.shortScrollAt(firstShorts.length));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> first.columnShortScrollAt(firstColumnShorts.length));
            intBackings.add(first.scrollBackingIdentity());
            intBackings.add(second.scrollBackingIdentity());
            shortBackings.add(first.shortScrollBackingIdentity());
            shortBackings.add(second.shortScrollBackingIdentity());
            columnShortBackings.add(first.columnShortScrollBackingIdentity());
            columnShortBackings.add(second.columnShortScrollBackingIdentity());
            first.discard();
            second.discard();
        }

        assertEquals(Set.of(intBackingN, intBackingNPlusOne), intBackings);
        assertEquals(Set.of(shortBackingN, shortBackingNPlusOne), shortBackings);
        assertEquals(Set.of(columnShortBackingN, columnShortBackingNPlusOne), columnShortBackings);
    }

    @Test
    void levelResetCancelsOutstandingFrameCommandsUntilTheirStaleQueueEntriesDrain() throws Exception {
        LevelRenderer renderer = new LevelRenderer(null);
        Field poolField = LevelRenderer.class.getDeclaredField("frameCommandPool");
        poolField.setAccessible(true);
        LevelRenderer.FrameCommandPool pool = (LevelRenderer.FrameCommandPool) poolField.get(renderer);
        int[] viewport = {0, 0, 320, 224};

        LevelRenderer.FrameCommand foreground = pool.obtainForTesting(
                viewport, new int[] {1, 2}, null, LevelRenderer.FrameCommand.Kind.FG_LOW);
        LevelRenderer.FrameCommand background = pool.obtainForTesting(
                viewport, new int[] {3, 4}, new short[] {5}, LevelRenderer.FrameCommand.Kind.BG_RENDER);
        GraphicsManager graphics = new GraphicsManager();
        graphics.initHeadless();
        graphics.registerCommand(foreground);
        graphics.registerCommand(background);

        renderer.resetState();

        assertTrue(foreground.isCancelledForTesting());
        assertTrue(background.isCancelledForTesting());
        LevelRenderer.FrameCommand replacement = pool.obtainForTesting(viewport, new int[] {9});
        assertNotSame(foreground, replacement, "A stale queued reference must not become a new live lease.");
        assertNotSame(background, replacement, "A stale queued reference must not become a new live lease.");

        graphics.flush();
        assertFalse(foreground.isLeasedForTesting());
        assertFalse(background.isLeasedForTesting());
        replacement.discard();

        Set<Object> recycled = Collections.newSetFromMap(new IdentityHashMap<>());
        LevelRenderer.FrameCommand first = pool.obtainForTesting(viewport, new int[] {10});
        LevelRenderer.FrameCommand second = pool.obtainForTesting(viewport, new int[] {11});
        LevelRenderer.FrameCommand third = pool.obtainForTesting(viewport, new int[] {12});
        recycled.add(first);
        recycled.add(second);
        recycled.add(third);
        assertEquals(Set.of(foreground, background, replacement), recycled,
                "Reset-owned leases should return to the renderer pool after stale queue entries drain.");
        first.discard();
        second.discard();
        third.discard();
    }

    @Test
    void retainedForegroundCommandsUseTheirCapturedVerticalWrapState() throws Exception {
        LevelRenderer.FrameCommandPool pool = new LevelRenderer.FrameCommandPool();
        int[] viewport = {0, 0, 320, 224};
        LevelRenderer.FrameCommand low = pool.obtainForTesting(
                viewport, null, null, LevelRenderer.FrameCommand.Kind.FG_LOW, false);
        LevelRenderer.FrameCommand high = pool.obtainForTesting(
                viewport, null, null, LevelRenderer.FrameCommand.Kind.FG_HIGH, true);
        LevelRenderer.FrameCommand mask = pool.obtainForTesting(
                viewport, null, null, LevelRenderer.FrameCommand.Kind.HIGH_FBO, false);

        high.execute(0, 0, 320, 224);
        mask.execute(0, 0, 320, 224);
        low.execute(0, 0, 320, 224);

        assertTrue(high.verticalWrapForTesting());
        assertFalse(mask.verticalWrapForTesting());
        assertFalse(low.verticalWrapForTesting());
    }

    private static int[] sequence(int length, int base) {
        int[] values = new int[length];
        for (int i = 0; i < length; i++) values[i] = base + i;
        return values;
    }

    private static short[] shortSequence(int length, int base) {
        short[] values = new short[length];
        for (int i = 0; i < length; i++) values[i] = (short) (base + i);
        return values;
    }

    @Test
    void backgroundTilePassUsesFboViewportDimensions() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/level/LevelRenderer.java"));
        int commandStart = source.indexOf("private final GLCommand bgTilePassCommand");
        int commandEnd = source.indexOf("private final GLCommand highPriorityFboCommand", commandStart);
        if (commandEnd < 0) {
            commandEnd = source.indexOf("LevelRenderer(LevelManager levelManager)", commandStart);
        }
        String commandBody = source.substring(commandStart, commandEnd).replace("\r\n", "\n");

        assertTrue(commandBody.matches("(?s).*TilemapGpuRenderer\\.Layer\\.BACKGROUND,\\s+"
                        + "pendingBgTilePassRenderWidth,\\s+"
                        + "pendingBgTilePassRenderHeight,\\s+"
                        + "0,\\s+"
                        + "0,\\s+"
                        + "pendingBgTilePassRenderWidth,\\s+"
                        + "pendingBgTilePassRenderHeight,\\s+"
                        + "pendingBgTilePassBgTilemapWorldOffsetX,.*"),
                "The background tile pass renders into the BG FBO; its tilemap shader viewport must be the FBO viewport, not the cached screen viewport.");
    }

    @Test
    void highPriorityForegroundMaskUsesForegroundVScrollOrigin() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/level/LevelRenderer.java"));
        String normalizedSource = source.replace("\r\n", "\n");

        assertTrue(normalizedSource.contains("float fgWorldOffsetY = lm.parallaxManager.getVscrollFactorFG();"),
                "The high-priority mask must use the same foreground VScroll origin as the visible Plane A pass.");
        assertFalse(normalizedSource.contains("float fgWorldOffsetY = camera.getYWithShake();"),
                "Using camera Y for the mask shifts low-priority sprite occlusion away from visible foreground tiles.");
    }

    @Test
    void logicFrameParallaxPublishesRuntimeStateBeforeAnimatedTilesReadIt() throws Exception {
        String levelManagerSource = Files.readString(Path.of("src/main/java/com/openggf/level/LevelManager.java"));
        assertTrue(levelManagerSource.contains("frameRuntimeUpdater.updateParallaxAndAnimatedContent();"),
                "LevelManager.update must advance parallax and animated content during logic frames.");

        String source = Files.readString(Path.of("src/main/java/com/openggf/level/LevelFrameRuntimeUpdater.java"));
        String method = methodBody(source.replace("\r\n", "\n"), "void updateParallaxAndAnimatedContent(");

        int parallaxUpdate = method.indexOf("parallaxManager.update(");
        int animatedPatternUpdate = method.indexOf("animatedPatternManager.update();");

        assertTrue(parallaxUpdate >= 0, "Logic-frame runtime update must update parallax each frame.");
        assertTrue(animatedPatternUpdate >= 0, "Logic-frame runtime update must update animated patterns each frame.");
        assertTrue(parallaxUpdate < animatedPatternUpdate,
                "Parallax/deform runtime state must be published before S3K animated tiles read it.");
    }

    @Test
    void logicFrameParallaxDoesNotQueryLegacyGameBackgroundScrollApi() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/level/LevelFrameRuntimeUpdater.java"));
        assertFalse(source.contains("getBackgroundScroll("),
                "logic-frame parallax must use the active zone handler; the legacy Game background-scroll API is not authoritative");

        String rendererSource = Files.readString(Path.of("src/main/java/com/openggf/level/LevelRenderer.java"));
        assertFalse(rendererSource.contains("renderBackgroundShader(List<GLCommand> commands,"),
                "background rendering must not retain a legacy background-Y hint parameter");
    }

    @Test
    void backgroundTilemapCacheTracksRuntimeFullWidthRequirement() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/level/LevelTilemapManager.java"));

        assertTrue(source.contains("lastRequiresFullWidthBgTilemap"),
                "BG tilemap cache must remember the runtime full-width requirement used for the last build.");
        assertTrue(source.contains("if (lastRequiresFullWidthBgTilemap != null")
                        && source.contains("lastRequiresFullWidthBgTilemap != requiresFullWidthBgTilemap"),
                "ensureBackgroundTilemapData must dirty the BG cache when runtime full-width mode changes.");
        assertTrue(source.contains("lastRequiresFullWidthBgTilemap = requiresFullWidthBgTilemap"),
                "The last built full-width mode must be recorded after a BG rebuild.");
    }

    private static String methodBody(String text, String signature) {
        int start = text.indexOf(signature);
        assertTrue(start >= 0, "Missing method signature: " + signature);
        int brace = text.indexOf('{', start);
        assertTrue(brace >= 0, "Missing method body: " + signature);
        int depth = 0;
        for (int i = brace; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(brace + 1, i);
                }
            }
        }
        throw new AssertionError("Unclosed method body: " + signature);
    }
}
