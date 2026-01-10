package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.rings.RingFrame;
import uk.co.jamesj999.sonic.level.rings.RingFramePiece;
import uk.co.jamesj999.sonic.level.rings.RingManager;
import uk.co.jamesj999.sonic.level.rings.RingPlacementManager;
import uk.co.jamesj999.sonic.level.rings.RingRenderManager;
import uk.co.jamesj999.sonic.level.rings.RingSpawn;
import uk.co.jamesj999.sonic.level.rings.RingSpriteSheet;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestRingManager {
    @Test
    public void testRingCollectionAndSparkleLifecycle() {
        RingSpawn spawn = new RingSpawn(100, 100);
        RingPlacementManager placementManager = new RingPlacementManager(List.of(spawn));
        RingRenderManager renderManager = buildRenderManager();
        RingManager ringManager = new RingManager(placementManager, renderManager);
        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);

        ringManager.update(0, player, 0);

        int index = placementManager.getSpawnIndex(spawn);
        assertTrue(placementManager.isCollected(index));
        assertEquals(1, player.getRingCount());
        assertEquals(0, placementManager.getSparkleStartFrame(index));
        assertTrue(ringManager.isRenderable(spawn, 1));

        assertFalse(ringManager.isRenderable(spawn, 2));
        assertEquals(-1, placementManager.getSparkleStartFrame(index));
    }

    @Test
    public void testCollectedRingsPersistOffscreen() {
        RingSpawn spawn = new RingSpawn(100, 100);
        RingPlacementManager placementManager = new RingPlacementManager(List.of(spawn));
        RingRenderManager renderManager = buildRenderManager();
        RingManager ringManager = new RingManager(placementManager, renderManager);
        TestPlayableSprite player = new TestPlayableSprite((short) 100, (short) 100);

        ringManager.update(0, player, 0);

        int index = placementManager.getSpawnIndex(spawn);
        assertTrue(placementManager.isCollected(index));

        ringManager.update(10000, player, 1);
        assertTrue(placementManager.isCollected(index));
        assertEquals(1, player.getRingCount());

        ringManager.update(0, player, 2);
        assertTrue(placementManager.isCollected(index));
        assertEquals(1, player.getRingCount());
    }

    private RingRenderManager buildRenderManager() {
        Pattern pattern = new Pattern();
        pattern.setPixel(0, 0, (byte) 1);

        RingFramePiece piece = new RingFramePiece(0, 0, 1, 1, 0, false, false, 0);
        RingFrame frame = new RingFrame(List.of(piece));
        List<RingFrame> frames = new ArrayList<>();
        frames.add(frame);
        frames.add(frame);
        frames.add(frame);

        RingSpriteSheet spriteSheet = new RingSpriteSheet(new Pattern[] { pattern }, frames, 1, 1, 1, 2);
        RingRenderManager renderManager = new RingRenderManager(spriteSheet);
        renderManager.ensurePatternsCached(GraphicsManager.getInstance(), 0);
        return renderManager;
    }

    private static final class TestPlayableSprite extends AbstractPlayableSprite {
        private TestPlayableSprite(short x, short y) {
            super("TEST", x, y, true);
            setWidth(20);
            setHeight(20);
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 0;
            runDecel = 0;
            friction = 0;
            max = 0;
            jump = 0;
            angle = 0;
            slopeRunning = 0;
            slopeRollingDown = 0;
            slopeRollingUp = 0;
            rollDecel = 0;
            minStartRollSpeed = 0;
            minRollSpeed = 0;
            maxRoll = 0;
            rollHeight = 0;
            runHeight = 0;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        private int ringCount = 0;

        @Override
        public void addRings(int delta) {
            ringCount += delta;
        }

        @Override
        public int getRingCount() {
            return ringCount;
        }

        @Override
        public void draw() {

        }
    }
}
