package uk.co.jamesj999.sonic.level.objects;

import org.junit.Test;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestSolidObjectManager {
    @Test
    public void testStandingContactOnFlatObject() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 100, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        int centreY = 100 - params.groundHalfHeight() - player.getYRadius();
        player.setCentreY((short) centreY);
        player.setYSpeed((short) 0);

        assertTrue(manager.hasStandingContact(player));
    }

    @Test
    public void testHeadroomDistanceUpward() {
        SolidObjectParams params = new SolidObjectParams(16, 8, 8);
        TestSolidObject object = new TestSolidObject(100, 70, params);
        ObjectManager manager = buildManager(object);

        TestPlayableSprite player = new TestPlayableSprite((short) 0, (short) 0);
        player.setWidth(20);
        player.setHeight(20);
        player.setCentreX((short) 100);
        player.setCentreY((short) 100);

        int distance = manager.getHeadroomDistance(player, 0x00);

        assertEquals(3, distance);
    }

    private ObjectManager buildManager(ObjectInstance instance) {
        ObjectRegistry registry = new ObjectRegistry() {
            @Override
            public ObjectInstance create(ObjectSpawn spawn) {
                return instance;
            }

            @Override
            public void reportCoverage(List<ObjectSpawn> spawns) {
                // No-op for tests.
            }

            @Override
            public String getPrimaryName(int objectId) {
                return "TEST";
            }
        };

        ObjectManager objectManager = new ObjectManager(List.of(), registry, 0, null, null);
        objectManager.reset(0);
        objectManager.addDynamicObject(instance);
        return objectManager;
    }

    private static final class TestSolidObject implements ObjectInstance, SolidObjectProvider {
        private final ObjectSpawn spawn;
        private final SolidObjectParams params;

        private TestSolidObject(int x, int y, SolidObjectParams params) {
            this.spawn = new ObjectSpawn(x, y, 0, 0, 0, false, 0);
            this.params = params;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return spawn;
        }

        @Override
        public void update(int frameCounter, AbstractPlayableSprite player) {
            // No-op for tests.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // No-op for tests.
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public boolean isDestroyed() {
            return false;
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return params;
        }
    }

    private static final class TestPlayableSprite extends AbstractPlayableSprite {
        private TestPlayableSprite(short x, short y) {
            super("TEST", x, y);
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

        @Override
        public void draw() {
            // No-op for tests.
        }
    }
}
