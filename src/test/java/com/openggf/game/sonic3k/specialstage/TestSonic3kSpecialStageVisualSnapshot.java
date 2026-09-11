package com.openggf.game.sonic3k.specialstage;

import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.PatternDesc;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

class TestSonic3kSpecialStageVisualSnapshot {
    private static final int BG_TILE_COUNT = 64 * 32;
    private static final int FLOOR_TILE_COUNT = 40 * 28;
    private static final int BG_DRAW_COUNT = 41 * 29;
    private static final int BG_WORD = 0xF800 | (Sonic3kSpecialStageConstants.ART_TILE_BG + 3);
    private static final int FLOOR_WORD = 0xF800 | 0x12;

    @Test
    void unchangedSteadyFrameValidatesBackgroundAndOnlySelectedFloorOnce() {
        RenderFixture fixture = renderFixture();
        fixture.renderer.render(fixture.manager);
        invokeCounterReset(fixture.renderer);

        fixture.graphics.calls.clear();
        fixture.renderer.render(fixture.manager);

        assertEquals(1, invokeCounter(fixture.renderer, "backgroundContentValidationCountForTesting"));
        assertEquals(1, invokeCounter(fixture.renderer, "floorContentValidationCountForTesting"));
        assertEquals(fixture.bgMap.length + FLOOR_TILE_COUNT * 2,
                invokeCounter(fixture.renderer, "logicalContentValidationBytesForTesting"));
        assertEquals(goldenInitialDraw(), fixture.graphics.calls);
    }

    @Test
    void inactiveFloorMutationRebuildsLazilyWhenThatNonzeroFrameIsSelected() throws Exception {
        RenderFixture fixture = renderFixture();
        fixture.renderer.render(fixture.manager);
        Object[] initialFloors = (Object[]) get(fixture.renderer, "cachedFloorGeometry");
        Object initialFrameOne = initialFloors[1];
        writeWord(fixture.floorMap, FLOOR_TILE_COUNT * 2, 0x4000 | 0x35);

        fixture.graphics.calls.clear();
        fixture.renderer.render(fixture.manager);
        assertEquals(10, fixture.renderer.staticGeometryBuildCountForTesting(),
                "inactive floor frames must not be scanned or rebuilt on the current frame");
        assertSame(initialFrameOne, ((Object[]) get(fixture.renderer, "cachedFloorGeometry"))[1]);

        fixture.manager.getPlayer().initialize(Sonic3kSpecialStageConstants.ANGLE_NORTH, 0, -1, false);
        fixture.graphics.calls.clear();
        fixture.renderer.render(fixture.manager);
        assertEquals(11, fixture.renderer.staticGeometryBuildCountForTesting());
        assertNotSame(initialFrameOne, ((Object[]) get(fixture.renderer, "cachedFloorGeometry"))[1]);
        assertEquals(new RenderCall(0x2035, 0, 2, false, false, false, 0, 0),
                fixture.graphics.calls.get(BG_DRAW_COUNT));
    }

    @Test
    void nullTruncatedAndExtendedFloorMapsTransitionWithoutStaleDrawsOrRebuilds() {
        RenderFixture fixture = renderFixture();
        fixture.renderer.render(fixture.manager);

        fixture.renderer.setFloorMapData(null);
        fixture.graphics.calls.clear();
        fixture.renderer.render(fixture.manager);
        assertEquals(BG_DRAW_COUNT, fixture.graphics.calls.size());
        assertEquals(10, fixture.renderer.staticGeometryBuildCountForTesting());

        fixture.renderer.setFloorMapData(new byte[FLOOR_TILE_COUNT * 2 - 1]);
        fixture.graphics.calls.clear();
        fixture.renderer.render(fixture.manager);
        assertEquals(BG_DRAW_COUNT, fixture.graphics.calls.size());
        assertEquals(10, fixture.renderer.staticGeometryBuildCountForTesting());

        byte[] extended = Arrays.copyOf(fixture.floorMap, fixture.floorMap.length + 2);
        extended[extended.length - 2] = 0x55;
        extended[extended.length - 1] = (byte) 0xAA;
        fixture.renderer.setFloorMapData(extended);
        fixture.graphics.calls.clear();
        fixture.renderer.render(fixture.manager);
        assertEquals(goldenInitialDraw(), fixture.graphics.calls);
        assertEquals(10, fixture.renderer.staticGeometryBuildCountForTesting(),
                "bytes beyond the bounded nine-frame domain do not affect geometry");
    }

    @Test
    void initialStaticGeometryMatchesIndependentGoldenDrawStream() {
        RenderFixture fixture = renderFixture();

        fixture.renderer.render(fixture.manager);

        assertEquals(goldenInitialDraw(), fixture.graphics.calls);
    }

    @Test
    void inPlaceBackgroundMutationRebuildsOnlyStarfieldAndChangesExactDraw() throws Exception {
        RenderFixture fixture = renderFixture();
        fixture.renderer.render(fixture.manager);
        Object initialStars = get(fixture.renderer, "cachedStarfieldGeometry");
        Object initialFloors = get(fixture.renderer, "cachedFloorGeometry");
        Object initialStarContent = get(fixture.renderer, "cachedStarfieldContent");
        Object initialFloorContent = ((Object[]) get(fixture.renderer, "cachedFloorContent"))[0];

        writeWord(fixture.bgMap, 0, 0x2000 | (Sonic3kSpecialStageConstants.ART_TILE_BG + 7));
        fixture.graphics.calls.clear();
        fixture.renderer.render(fixture.manager);

        assertEquals(11, fixture.renderer.staticGeometryBuildCountForTesting());
        assertNotSame(initialStars, get(fixture.renderer, "cachedStarfieldGeometry"));
        assertSame(initialFloors, get(fixture.renderer, "cachedFloorGeometry"));
        assertSame(initialStarContent, get(fixture.renderer, "cachedStarfieldContent"));
        assertSame(initialFloorContent, ((Object[]) get(fixture.renderer, "cachedFloorContent"))[0]);
        assertEquals(new RenderCall(0x1007, 0, 1, false, false, false, 0, 0),
                fixture.graphics.calls.get(0));
        assertEquals(goldenInitialDraw().subList(1, BG_DRAW_COUNT),
                fixture.graphics.calls.subList(1, BG_DRAW_COUNT));
        assertEquals(goldenInitialDraw().subList(BG_DRAW_COUNT, goldenInitialDraw().size()),
                fixture.graphics.calls.subList(BG_DRAW_COUNT, fixture.graphics.calls.size()));
    }

    @Test
    void inPlaceFloorMutationRebuildsOnlyAffectedBatchAndChangesSelectedFrameDraw() throws Exception {
        RenderFixture fixture = renderFixture();
        fixture.renderer.render(fixture.manager);
        Object[] initialFloors = (Object[]) get(fixture.renderer, "cachedFloorGeometry");
        Object initialFrameZero = initialFloors[0];
        Object initialFrameOne = initialFloors[1];
        Object initialStarContent = get(fixture.renderer, "cachedStarfieldContent");
        Object initialFrameZeroContent = ((Object[]) get(fixture.renderer, "cachedFloorContent"))[0];

        writeWord(fixture.floorMap, 0, 0x4000 | 0x34);
        fixture.graphics.calls.clear();
        fixture.renderer.render(fixture.manager);

        Object[] rebuiltFloors = (Object[]) get(fixture.renderer, "cachedFloorGeometry");
        assertEquals(11, fixture.renderer.staticGeometryBuildCountForTesting());
        assertNotSame(initialFrameZero, rebuiltFloors[0]);
        assertSame(initialFrameOne, rebuiltFloors[1]);
        assertSame(initialStarContent, get(fixture.renderer, "cachedStarfieldContent"));
        assertSame(initialFrameZeroContent, ((Object[]) get(fixture.renderer, "cachedFloorContent"))[0]);
        assertEquals(new RenderCall(0x2034, 0, 2, false, false, false, 0, 0),
                fixture.graphics.calls.get(BG_DRAW_COUNT));
    }

    @Test
    void equalContentReplacementDoesNotRebuildStaticGeometry() throws Exception {
        RenderFixture fixture = renderFixture();
        fixture.renderer.render(fixture.manager);
        Object initialStars = get(fixture.renderer, "cachedStarfieldGeometry");
        Object initialFloors = get(fixture.renderer, "cachedFloorGeometry");

        fixture.renderer.setBgMapData(fixture.bgMap.clone());
        fixture.renderer.setFloorMapData(fixture.floorMap.clone());
        fixture.graphics.calls.clear();
        fixture.renderer.render(fixture.manager);

        assertEquals(10, fixture.renderer.staticGeometryBuildCountForTesting());
        assertSame(initialStars, get(fixture.renderer, "cachedStarfieldGeometry"));
        assertSame(initialFloors, get(fixture.renderer, "cachedFloorGeometry"));
        assertEquals(goldenInitialDraw(), fixture.graphics.calls);
    }

    @Test
    void staticPlaneGeometryReusesNineFloorBatchesAndInvalidatesByGenerationAndReset() throws Exception {
        RenderFixture fixture = renderFixture();
        RecordingGraphicsManager graphics = fixture.graphics;
        Sonic3kSpecialStageRenderer renderer = fixture.renderer;
        Sonic3kSpecialStageManager manager = fixture.manager;

        Method buildCount = Sonic3kSpecialStageRenderer.class
                .getDeclaredMethod("staticGeometryBuildCountForTesting");
        Method contextChange = Sonic3kSpecialStageRenderer.class
                .getDeclaredMethod("onRenderContextGenerationChanged", Object.class);
        Field starGeometry = Sonic3kSpecialStageRenderer.class.getDeclaredField("cachedStarfieldGeometry");
        Field floorGeometry = Sonic3kSpecialStageRenderer.class.getDeclaredField("cachedFloorGeometry");
        for (var accessible : List.of(buildCount, contextChange, starGeometry, floorGeometry)) {
            accessible.setAccessible(true);
        }

        renderer.render(manager);
        List<RenderCall> firstDraw = goldenInitialDraw();
        assertEquals(firstDraw, graphics.calls);
        Object firstStars = starGeometry.get(renderer);
        Object firstFloors = floorGeometry.get(renderer);
        assertEquals(10, buildCount.invoke(renderer), "one starfield plus nine floor batches are built");

        graphics.calls.clear();
        renderer.render(manager);
        assertEquals(firstDraw, graphics.calls, "geometry caching must preserve exact draw output and order");
        assertEquals(10, buildCount.invoke(renderer));
        assertSame(firstStars, starGeometry.get(renderer));
        assertSame(firstFloors, floorGeometry.get(renderer));

        contextChange.invoke(renderer, new Object());
        graphics.calls.clear();
        renderer.render(manager);
        assertEquals(firstDraw, graphics.calls);
        assertEquals(20, buildCount.invoke(renderer));
        assertNotSame(firstStars, starGeometry.get(renderer));
        assertNotSame(firstFloors, floorGeometry.get(renderer));

        set(manager, "renderer", renderer);
        manager.reset();
        byte[] resetBg = fixture.bgMap.clone();
        byte[] resetFloor = fixture.floorMap.clone();
        writeWord(resetBg, 0, 0x2000 | (Sonic3kSpecialStageConstants.ART_TILE_BG + 7));
        writeWord(resetFloor, 0, 0x4000 | 0x35);
        renderer.setBgMapData(resetBg);
        renderer.setFloorMapData(resetFloor);
        graphics.calls.clear();
        renderer.render(manager);
        assertEquals(new RenderCall(0x1007, 0, 1, false, false, false, 0, 0), graphics.calls.get(0));
        assertEquals(new RenderCall(0x2035, 0, 2, false, false, false, 0, 0),
                graphics.calls.get(BG_DRAW_COUNT));
        assertEquals(firstDraw.size(), graphics.calls.size());
        assertEquals(30, buildCount.invoke(renderer));
    }

    @Test
    void cachedGeometryUsesPackedPrimitiveArraysWithoutPaletteColors() throws Exception {
        Field starfield = Sonic3kSpecialStageRenderer.class.getDeclaredField("cachedStarfieldGeometry");
        Field floors = Sonic3kSpecialStageRenderer.class.getDeclaredField("cachedFloorGeometry");
        assertEquals(int[].class, starfield.getType());
        assertEquals(int[][].class, floors.getType());
        for (Class<?> nested : Sonic3kSpecialStageRenderer.class.getDeclaredClasses()) {
            assertEquals(false, nested.getSimpleName().contains("StaticTileGeometry"));
            for (Field field : nested.getDeclaredFields()) {
                assertEquals(false, field.getType().equals(Palette.Color.class));
            }
        }
    }

    @Test
    void perspectiveBackgroundHudAndBannerRestoreState() {
        Sonic3kSpecialStagePerspective perspective = new Sonic3kSpecialStagePerspective();
        set(perspective, "animFrame", 12);
        set(perspective, "paletteFrame", 8);
        Sonic3kSpecialStageSnapshot.PerspectiveSnapshot perspectiveSnapshot =
                perspective.captureRewindSnapshot();
        set(perspective, "animFrame", 1);
        set(perspective, "paletteFrame", 2);
        perspective.restoreRewindSnapshot(perspectiveSnapshot);
        assertEquals(12, perspective.getAnimFrame());
        assertEquals(8, perspective.getPaletteFrame());

        Sonic3kSpecialStageBackground background = new Sonic3kSpecialStageBackground();
        set(background, "vScroll", 40);
        set(background, "hScroll", 80);
        set(background, "prevXPos", 0x1111);
        set(background, "prevYPos", 0x2222);
        Sonic3kSpecialStageSnapshot.BackgroundSnapshot backgroundSnapshot =
                background.captureRewindSnapshot();
        background.reset();
        background.restoreRewindSnapshot(backgroundSnapshot);
        assertEquals(40, background.getVScroll());
        assertEquals(80, background.getHScroll());
        assertEquals(0x1111, get(background, "prevXPos"));
        assertEquals(0x2222, get(background, "prevYPos"));

        Sonic3kSpecialStageHud hud = new Sonic3kSpecialStageHud();
        hud.initialize();
        hud.update(17, 42);
        hud.clearSphereDirty();
        Sonic3kSpecialStageSnapshot.HudSnapshot hudSnapshot = hud.captureRewindSnapshot();
        hud.update(1, 2);
        hud.restoreRewindSnapshot(hudSnapshot);
        assertEquals(17, hud.getDisplayedSphereCount());
        assertEquals(42, hud.getDisplayedRingCount());
        assertEquals(false, hud.isSphereDirty());
        assertEquals(true, hud.isRingDirty());

        Sonic3kSpecialStageBanner banner = new Sonic3kSpecialStageBanner();
        banner.initialize();
        set(banner, "phase", Sonic3kSpecialStageBanner.Phase.SLIDING_IN);
        set(banner, "slideOffset", 33);
        set(banner, "displayTimer", 44);
        set(banner, "triggeredAdvance", true);
        set(banner, "showPerfect", true);
        Sonic3kSpecialStageSnapshot.BannerSnapshot bannerSnapshot = banner.captureRewindSnapshot();
        banner.initialize();
        banner.restoreRewindSnapshot(bannerSnapshot);
        assertEquals(Sonic3kSpecialStageBanner.Phase.SLIDING_IN, banner.getPhase());
        assertEquals(33, banner.getSlideOffset());
        assertEquals(44, get(banner, "displayTimer"));
        assertEquals(true, get(banner, "triggeredAdvance"));
        assertEquals(true, banner.isShowPerfect());
    }

    @Test
    void paletteSnapshotDeepCopiesPalettesAndStageData() {
        Sonic3kSpecialStagePalette palette = new Sonic3kSpecialStagePalette();
        Palette[] livePalettes = (Palette[]) get(palette, "palettes");
        for (int i = 0; i < livePalettes.length; i++) {
            livePalettes[i] = new Palette();
            Palette.Color color = livePalettes[i].getColor(i);
            color.r = (byte) (10 + i);
            color.g = (byte) (20 + i);
            color.b = (byte) (30 + i);
        }
        byte[] stageData = new byte[]{1, 2, 3, 4};
        set(palette, "stagePaletteData", stageData);
        set(palette, "fadeActive", true);

        Sonic3kSpecialStageSnapshot.PaletteSnapshot snapshot = palette.captureRewindSnapshot();
        stageData[0] = 99;
        for (int i = 0; i < livePalettes.length; i++) {
            Palette.Color color = livePalettes[i].getColor(i);
            color.r = (byte) (100 + i);
            color.g = (byte) (110 + i);
            color.b = (byte) (120 + i);
        }
        set(palette, "fadeActive", false);
        palette.restoreRewindSnapshot(snapshot);

        assertEquals(true, get(palette, "fadeActive"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, (byte[]) get(palette, "stagePaletteData"));
        assertNotSame(snapshot.palettes(), palette.getPalettes());
        assertNotSame(snapshot.palettes()[0], palette.getPalette(0));
        for (int i = 0; i < livePalettes.length; i++) {
            Palette.Color color = palette.getPalette(i).getColor(i);
            assertEquals((byte) (10 + i), color.r);
            assertEquals((byte) (20 + i), color.g);
            assertEquals((byte) (30 + i), color.b);
        }
    }

    @Test
    void ringConverterSnapshotRestoresSeedField() {
        Sonic3kSpecialStageRingConverter converter = new Sonic3kSpecialStageRingConverter();
        set(converter, "seedBlueConverted", 5);
        Sonic3kSpecialStageSnapshot.RingConverterSnapshot snapshot = converter.captureRewindSnapshot();
        set(converter, "seedBlueConverted", 0);
        converter.restoreRewindSnapshot(snapshot);
        assertEquals(5, get(converter, "seedBlueConverted"));
    }

    private static Object get(Object target, String field) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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

    private static byte[] repeatedWords(int count, int word) {
        byte[] data = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            data[i * 2] = (byte) (word >>> 8);
            data[i * 2 + 1] = (byte) word;
        }
        return data;
    }

    private static void writeWord(byte[] data, int byteOffset, int word) {
        data[byteOffset] = (byte) (word >>> 8);
        data[byteOffset + 1] = (byte) word;
    }

    private static void invokeCounterReset(Sonic3kSpecialStageRenderer renderer) {
        try {
            Method method = Sonic3kSpecialStageRenderer.class
                    .getDeclaredMethod("resetContentValidationCountersForTesting");
            method.setAccessible(true);
            method.invoke(renderer);
        } catch (ReflectiveOperationException e) {
            fail("renderer must expose focused content-validation counters", e);
        }
    }

    private static int invokeCounter(Sonic3kSpecialStageRenderer renderer, String methodName) {
        try {
            Method method = Sonic3kSpecialStageRenderer.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            return (int) method.invoke(renderer);
        } catch (ReflectiveOperationException e) {
            return fail("missing focused counter " + methodName, e);
        }
    }

    private static RenderFixture renderFixture() {
        RecordingGraphicsManager graphics = new RecordingGraphicsManager();
        graphics.initHeadless();
        Sonic3kSpecialStageRenderer renderer = new Sonic3kSpecialStageRenderer(graphics);
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        manager.getPlayer().initialize(Sonic3kSpecialStageConstants.ANGLE_NORTH, 0, 0, false);
        byte[] bgMap = repeatedWords(BG_TILE_COUNT, BG_WORD);
        byte[] floorMap = new byte[9 * FLOOR_TILE_COUNT * 2];
        for (int frame = 0; frame < 9; frame++) {
            int frameWord = (FLOOR_WORD & 0xF800) | (0x12 + frame);
            for (int tile = 0; tile < FLOOR_TILE_COUNT; tile++) {
                writeWord(floorMap, (frame * FLOOR_TILE_COUNT + tile) * 2, frameWord);
            }
        }
        renderer.setBgPatternBase(0x1000);
        renderer.setFloorPatternBase(0x2000);
        renderer.setBgMapData(bgMap);
        renderer.setFloorMapData(floorMap);
        renderer.setArtLoaded(true);
        return new RenderFixture(graphics, renderer, manager, bgMap, floorMap);
    }

    private static List<RenderCall> goldenInitialDraw() {
        ArrayList<RenderCall> expected = new ArrayList<>(BG_DRAW_COUNT + FLOOR_TILE_COUNT);
        for (int sy = 0; sy <= 28; sy++) {
            for (int sx = 0; sx <= 40; sx++) {
                expected.add(new RenderCall(0x1003, 0, 3, false, true, true, sx * 8, sy * 8));
            }
        }
        // Player position/angle selects floor frame zero for this fixture.
        for (int ty = 0; ty < 28; ty++) {
            for (int tx = 0; tx < 40; tx++) {
                expected.add(new RenderCall(0x2012, 0, 3, true, true, true, tx * 8, ty * 8));
            }
        }
        return List.copyOf(expected);
    }

    private record RenderFixture(
            RecordingGraphicsManager graphics,
            Sonic3kSpecialStageRenderer renderer,
            Sonic3kSpecialStageManager manager,
            byte[] bgMap,
            byte[] floorMap) {
    }

    private record RenderCall(
            int patternId,
            int descriptorPatternIndex,
            int paletteIndex,
            boolean priority,
            boolean hFlip,
            boolean vFlip,
            int x,
            int y) {
    }

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
            calls.add(new RenderCall(
                    patternId,
                    desc.getPatternIndex(),
                    desc.getPaletteIndex(),
                    desc.getPriority(),
                    desc.getHFlip(),
                    desc.getVFlip(),
                    x,
                    y));
        }
    }
}
