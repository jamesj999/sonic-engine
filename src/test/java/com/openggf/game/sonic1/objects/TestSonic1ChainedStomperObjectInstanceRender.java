package com.openggf.game.sonic1.objects;

import com.openggf.game.ObjectArtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.animation.SpriteAnimationSet;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestSonic1ChainedStomperObjectInstanceRender {
    @BeforeEach
    public void setUp() {
    }

    @AfterEach
    public void tearDown() {
    }

    @Test
    public void chainedStomperSpikesUseVerticalFlip() throws Exception {
        RecordingRenderer stomperRenderer = new RecordingRenderer();
        RecordingRenderer spikeRenderer = new RecordingRenderer();
        ObjectRenderManager renderManager = new ObjectRenderManager(
                new StubObjectArtProvider(stomperRenderer, spikeRenderer));
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);

        Sonic1ChainedStomperObjectInstance stomper = new Sonic1ChainedStomperObjectInstance(
                new ObjectSpawn(100, 100, Sonic1ObjectIds.CHAINED_STOMPER, 0x00, 0, false, 0));
        stomper.setServices(new TestObjectServices().withLevelManager(levelManager));
        stomper.appendRenderCommands(new ArrayList<>());

        assertEquals(5, spikeRenderer.drawCount);

        int[] expectedX = {60, 80, 100, 120, 140};
        int[] actualX = spikeRenderer.calls.stream().mapToInt(call -> call.originX).toArray();
        assertArrayEquals(expectedX, actualX);

        for (RecordingRenderer.DrawCall call : spikeRenderer.calls) {
            assertEquals(2, call.frameIndex);
            assertFalse(call.hFlip);
            assertTrue(call.vFlip);
        }
    }

    @Test
    public void chainedStomperSpikeTouchRegionUsesSpikeRowPosition() {
        Sonic1ChainedStomperObjectInstance stomper = new Sonic1ChainedStomperObjectInstance(
                new ObjectSpawn(100, 100, Sonic1ObjectIds.CHAINED_STOMPER, 0x01, 0, false, 0));

        var regions = stomper.getMultiTouchRegions();
        assertNotNull(regions);
        assertEquals(1, regions.length);
        assertEquals(100, regions[0].x());
        assertEquals(128, regions[0].y());
        assertEquals(0x90, regions[0].collisionFlags());
    }

    @Test
    public void chainedStomperSubtype20DisablesSpikeTouchRegion() {
        Sonic1ChainedStomperObjectInstance stomper = new Sonic1ChainedStomperObjectInstance(
                new ObjectSpawn(100, 100, Sonic1ObjectIds.CHAINED_STOMPER, 0x21, 0, false, 0));

        assertNull(stomper.getMultiTouchRegions());
        assertEquals(0, stomper.getCollisionFlags());
    }

    @Test
    public void chainedStomperBalanceWidthUsesNativeActiveWidthNotSolidExtension() {
        Sonic1ChainedStomperObjectInstance wide = new Sonic1ChainedStomperObjectInstance(
                new ObjectSpawn(100, 100, Sonic1ObjectIds.CHAINED_STOMPER, 0x00, 0, false, 0));
        Sonic1ChainedStomperObjectInstance medium = new Sonic1ChainedStomperObjectInstance(
                new ObjectSpawn(100, 100, Sonic1ObjectIds.CHAINED_STOMPER, 0x10, 0, false, 0));
        Sonic1ChainedStomperObjectInstance small = new Sonic1ChainedStomperObjectInstance(
                new ObjectSpawn(100, 100, Sonic1ObjectIds.CHAINED_STOMPER, 0x20, 0, false, 0));

        assertEquals(0x38, wide.getBalanceWidthPixels());
        assertEquals(0x30, medium.getBalanceWidthPixels());
        assertEquals(0x10, small.getBalanceWidthPixels());
        assertEquals(0x38 + 0x0B, wide.getSolidParams().halfWidth(),
                "CStom's SolidObject-only $B extension must remain collision-local");
        assertTrue(wide.getSolidRoutineProfile().inclusiveRightEdge(),
                "CStom's SolidObject BHI must retain exact-edge side contact");
        assertTrue(wide.usesInstanceSolidStateLatchKey(),
                "CStom status bits belong to its live SST while its Y changes");
    }

    private static final class StubObjectArtProvider implements ObjectArtProvider {
        private final PatternSpriteRenderer stomperRenderer;
        private final PatternSpriteRenderer spikeRenderer;

        private StubObjectArtProvider(PatternSpriteRenderer stomperRenderer, PatternSpriteRenderer spikeRenderer) {
            this.stomperRenderer = stomperRenderer;
            this.spikeRenderer = spikeRenderer;
        }

        @Override
        public void loadArtForZone(int zoneIndex) {
        }

        @Override
        public PatternSpriteRenderer getRenderer(String key) {
            if (ObjectArtKeys.MZ_CHAINED_STOMPER.equals(key)) {
                return stomperRenderer;
            }
            if (ObjectArtKeys.SPIKE.equals(key)) {
                return spikeRenderer;
            }
            return null;
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
            return List.of(ObjectArtKeys.MZ_CHAINED_STOMPER, ObjectArtKeys.SPIKE);
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
        private final List<DrawCall> calls = new ArrayList<>();

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
            calls.add(new DrawCall(frameIndex, originX, originY, hFlip, vFlip));
        }

        private static final class DrawCall {
            private final int frameIndex;
            private final int originX;
            private final int originY;
            private final boolean hFlip;
            private final boolean vFlip;

            private DrawCall(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip) {
                this.frameIndex = frameIndex;
                this.originX = originX;
                this.originY = originY;
                this.hFlip = hFlip;
                this.vFlip = vFlip;
            }
        }

        private static ObjectSpriteSheet dummySheet() {
            Pattern[] patterns = {new Pattern()};
            SpriteMappingPiece piece = new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false);
            return new ObjectSpriteSheet(patterns, List.of(new SpriteMappingFrame(List.of(piece))), 0, 1);
        }
    }
}
