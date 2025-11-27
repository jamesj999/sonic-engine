package uk.co.jamesj999.sonic.sprites.managers;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestEdgeLanding {

    private PlayableSpriteMovementManager manager;
    private AbstractPlayableSprite mockSprite;

    private SensorResult mockGroundResult;
    private SensorResult mockPushResult;

    @Before
    public void setUp() {
        mockSprite = new AbstractPlayableSprite("sonic", (short)0, (short)0, false) {
            @Override protected void defineSpeeds() {
                max = 1536;
                runAccel = 12;
                runDecel = 128;
                friction = 12;
            }
            @Override protected void createSensorLines() {
                groundSensors = new Sensor[2];
                groundSensors[0] = new MockSensor(this, Direction.DOWN, (byte)-9, (byte)20, true);
                groundSensors[1] = new MockSensor(this, Direction.DOWN, (byte)9, (byte)20, true);

                pushSensors = new Sensor[2];
                pushSensors[0] = new MockSensor(this, Direction.LEFT, (byte)-10, (byte)0, false);
                pushSensors[1] = new MockSensor(this, Direction.RIGHT, (byte)10, (byte)0, false);

                ceilingSensors = new Sensor[2];
                ceilingSensors[0] = new MockSensor(this, Direction.UP, (byte)-9, (byte)-20, false);
                ceilingSensors[1] = new MockSensor(this, Direction.UP, (byte)9, (byte)-20, false);
            }
            @Override public void draw() { }
        };
        manager = new PlayableSpriteMovementManager(mockSprite);
    }

    @Test
    public void testLandingOnSteepSlopeAndNextFrameStuck() {
        // Frame 1: Landing
        mockSprite.setAir(true);
        mockSprite.setXSpeed((short) 500);
        mockSprite.setYSpeed((short) 500);
        mockSprite.setGSpeed((short) 0);
        mockSprite.setAngle((byte) 0);

        // Landing on a steep slope (e.g., 45 degrees, angle 32/0x20)
        // One sensor hits (distance 0), other misses (distance 100).
        mockGroundResult = new SensorResult((byte) 0x20, (byte) 0, 0, Direction.DOWN);
        ((MockSensor)mockSprite.getGroundSensors()[0]).setResult(mockGroundResult);
        ((MockSensor)mockSprite.getGroundSensors()[1]).setResult(null); // Miss

        // Run Frame 1
        manager.handleMovement(false, false, false, false, false, false);

        // Verify Landing
        assertTrue("Sprite should have landed", !mockSprite.getAir());
        assertEquals("Angle should be updated to 0x20", (byte)0x20, mockSprite.getAngle());

        // Frame 2: Grounded
        // Issue: On steep slope, doWallCollision (Ground) might trigger if PushSensors are active.
        // updateSensors() logic: Math.abs(angle) <= 64. 0x20 is 32. 32 <= 64. Active!
        // Push Sensors active.
        // Simulate Push Sensor hitting the slope (distance 0 or negative)
        SensorResult wallResult = new SensorResult((byte) 0x20, (byte) -2, 0, Direction.RIGHT);
        ((MockSensor)mockSprite.getPushSensors()[1]).setResult(wallResult);
        ((MockSensor)mockSprite.getPushSensors()[1]).setActive(true); // Manually set active for test setup if needed, but updateSensors will run.

        // Run Frame 2
        manager.handleMovement(false, false, false, false, false, false);

        // If bug exists: XSpeed becomes 0 because Wall Collision stopped him.
        assertTrue("XSpeed should be preserved (non-zero) on slope, but was " + mockSprite.getXSpeed(), mockSprite.getXSpeed() != 0);
    }

    private class MockSensor extends Sensor {
        private SensorResult result;

        public MockSensor(AbstractPlayableSprite sprite, Direction direction, byte x, byte y, boolean active) {
            super(sprite, direction, x, y, active);
        }

        public void setResult(SensorResult result) {
            this.result = result;
        }

        @Override
        protected SensorResult doScan(short dx, short dy) {
            if (!active && result == null) return null; // If inactive, return null unless forced
            return result;
        }
    }
}
