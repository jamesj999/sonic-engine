package com.openggf.game.sonic1.objects;

import com.openggf.game.GameServices;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.animation.SpriteAnimationSet;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1GargoyleObjectInstanceRender {

    private Field objectRenderManagerField;
    private ObjectRenderManager originalRenderManager;
    private LevelManager runtimeLevelManager;

    @BeforeEach
    public void setUp() throws Exception {
        TestEnvironment.activeGameplayMode();
        runtimeLevelManager = GameServices.level();
        objectRenderManagerField = LevelManager.class.getDeclaredField("objectRenderManager");
        objectRenderManagerField.setAccessible(true);
        originalRenderManager = (ObjectRenderManager) objectRenderManagerField.get(runtimeLevelManager);
    }

    @AfterEach
    public void tearDown() throws Exception {
        objectRenderManagerField.set(runtimeLevelManager, originalRenderManager);
        SessionManager.clear();
    }

    @Test
    public void headRenderFlipMatchesStatusBit0() throws Exception {
        RecordingRenderer renderer = new RecordingRenderer();
        installRenderer(renderer);

        Sonic1GargoyleObjectInstance leftFacing = new Sonic1GargoyleObjectInstance(
                new ObjectSpawn(100, 100, Sonic1ObjectIds.GARGOYLE, 0, 0, false, 0));
        leftFacing.setServices(new TestObjectServices().withLevelManager(runtimeLevelManager));
        leftFacing.appendRenderCommands(new ArrayList<>());

        assertEquals(1, renderer.drawCount);
        assertFalse(renderer.lastHFlip, "Status bit 0 clear should render without H-flip (facing left)");

        Sonic1GargoyleObjectInstance rightFacing = new Sonic1GargoyleObjectInstance(
                new ObjectSpawn(120, 100, Sonic1ObjectIds.GARGOYLE, 0, 1, false, 0));
        rightFacing.setServices(new TestObjectServices().withLevelManager(runtimeLevelManager));
        rightFacing.appendRenderCommands(new ArrayList<>());

        assertEquals(2, renderer.drawCount);
        assertTrue(renderer.lastHFlip, "Status bit 0 set should render with H-flip (facing right)");
    }

    @Test
    public void fireballRendersWithPaletteZero() throws Exception {
        RecordingRenderer renderer = new RecordingRenderer();
        installRenderer(renderer);

        Sonic1GargoyleObjectInstance.Fireball fireball =
                new Sonic1GargoyleObjectInstance.Fireball(100, 100, true);
        fireball.setServices(new TestObjectServices().withLevelManager(runtimeLevelManager));
        fireball.appendRenderCommands(new ArrayList<>());

        assertEquals(1, renderer.drawCount);
        assertTrue(renderer.usedPaletteOverride, "Fireball render path should call palette override variant");
        assertEquals(0, renderer.lastPaletteOverride, "Gar_FireBall uses make_art_tile(...,0,0), so palette line must be 0");
    }

    private void installRenderer(RecordingRenderer renderer) throws Exception {
        ObjectRenderManager renderManager = new ObjectRenderManager(new StubObjectArtProvider(renderer));
        objectRenderManagerField.set(runtimeLevelManager, renderManager);
    }

    private static final class StubObjectArtProvider implements ObjectArtProvider {
        private final PatternSpriteRenderer renderer;

        private StubObjectArtProvider(PatternSpriteRenderer renderer) {
            this.renderer = renderer;
        }

        @Override
        public void loadArtForZone(int zoneIndex) {
        }

        @Override
        public PatternSpriteRenderer getRenderer(String key) {
            return ObjectArtKeys.LZ_GARGOYLE.equals(key) ? renderer : null;
        }

        @Override
        public ObjectSpriteSheet getSheet(String key) {
            return null;
        }

        @Override
        public SpriteAnimationSet getAnimations(String key) {
            return null;
        }

        @Override
        public int getZoneData(String key, int zoneIndex) {
            return -1;
        }

        @Override
        public Pattern[] getHudDigitPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesNumbers() {
            return new Pattern[0];
        }

        @Override
        public List<String> getRendererKeys() {
            return List.of(ObjectArtKeys.LZ_GARGOYLE);
        }

        @Override
        public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    private static final class RecordingRenderer extends PatternSpriteRenderer {
        private int drawCount;
        private boolean lastHFlip;
        private boolean usedPaletteOverride;
        private int lastPaletteOverride = -1;

        private RecordingRenderer() {
            super(dummySheet());
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void drawFrameIndex(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip) {
            drawCount++;
            lastHFlip = hFlip;
            usedPaletteOverride = false;
            lastPaletteOverride = -1;
        }

        @Override
        public void drawFrameIndex(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip,
                                   int paletteOverride) {
            drawCount++;
            lastHFlip = hFlip;
            usedPaletteOverride = true;
            lastPaletteOverride = paletteOverride;
        }

        private static ObjectSpriteSheet dummySheet() {
            Pattern[] patterns = {new Pattern()};
            SpriteMappingPiece piece = new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false);
            return new ObjectSpriteSheet(patterns, List.of(new SpriteMappingFrame(List.of(piece))), 0, 1);
        }
    }
}


