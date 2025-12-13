package uk.co.jamesj999.sonic.sprites.managers;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestPlayableSpriteMovementManager {

    private PlayableSpriteMovementManager manager;
    private AbstractPlayableSprite mockSprite;

    @Before
    public void setUp() {
        mockSprite = new AbstractPlayableSprite("sonic", (short)0, (short)0, false) {
            @Override protected void defineSpeeds() { }
            @Override protected void createSensorLines() { }
            @Override public void draw() { }
        };
        manager = new PlayableSpriteMovementManager(mockSprite);
    }

    @Test
    public void testCalculateLandingRightSlope() throws Exception {
        // Angle 0x20 (32). Slope \ (Down-Right).
        // ySpeed 500 (falling). xSpeed 0.
        // Expected gSpeed positive (slide right).

        mockSprite.setAngle((byte) 0x20);
        mockSprite.setYSpeed((short) 500);
        mockSprite.setXSpeed((short) 0);
        mockSprite.setGSpeed((short) 0);

        Method method = PlayableSpriteMovementManager.class.getDeclaredMethod("calculateLanding", AbstractPlayableSprite.class);
        method.setAccessible(true);
        method.invoke(manager, mockSprite);

        assertTrue("gSpeed should be positive for right-facing slope, but was " + mockSprite.getGSpeed(), mockSprite.getGSpeed() > 0);
    }

    @Test
    public void testCalculateLandingLeftSlope() throws Exception {
        // Angle 0xE0 (224). Slope / (Up-Right).
        // ySpeed 500.
        // Expected gSpeed negative (slide left).

        mockSprite.setAngle((byte) 0xE0);
        mockSprite.setYSpeed((short) 500);
        mockSprite.setXSpeed((short) 0);
        mockSprite.setGSpeed((short) 0);

        Method method = PlayableSpriteMovementManager.class.getDeclaredMethod("calculateLanding", AbstractPlayableSprite.class);
        method.setAccessible(true);
        method.invoke(manager, mockSprite);

        assertTrue("gSpeed should be negative for left-facing slope, but was " + mockSprite.getGSpeed(), mockSprite.getGSpeed() < 0);
    }

    @Test
    public void testTerrainCollisionWithFlaggedAngle() throws Exception {
        // Test cases: Current Angle -> Snapped Angle
        // 0x00 (0 deg) -> 0x00
        // 0x10 (22.5 deg) -> 0x00
        // 0x30 (67.5 deg) -> 0x40 (90 deg)
        // 0x70 (157.5 deg) -> 0x80 (180 deg)

        byte[][] testCases = {
                {(byte) 0x00, (byte) 0x00},
                {(byte) 0x10, (byte) 0x00},
                {(byte) 0x30, (byte) 0x40},
                {(byte) 0x70, (byte) 0x80},
                {(byte) 0xE0, (byte) 0x00} // -32 -> 0
        };

        Method method = PlayableSpriteMovementManager.class.getDeclaredMethod("doTerrainCollision", AbstractPlayableSprite.class, SensorResult[].class);
        method.setAccessible(true);

        for (byte[] testCase : testCases) {
            byte currentAngle = testCase[0];
            byte expectedSnappedAngle = testCase[1];

            mockSprite.setAngle(currentAngle);
            // On Ground, speed required > 0 to stick.
            mockSprite.setXSpeed((short) 500);
            mockSprite.setAir(false);

            // Construct SensorResults with Angle 0xFF
            SensorResult result = new SensorResult((byte) 0xFF, (byte) 0, 0, Direction.DOWN);
            SensorResult[] results = {result, result};

            method.invoke(manager, mockSprite, results);

            assertEquals("Angle " + String.format("0x%02X", currentAngle) + " should snap to " + String.format("0x%02X", expectedSnappedAngle),
                    expectedSnappedAngle, mockSprite.getAngle());
        }
    }

    @Test
    public void testLandingFromAirWithStaleRightWallAngle() throws Exception {
        // Setup:
        // Sprite is in Air.
        // GroundMode is GROUND (automatically set when Air=true).
        // Angle is STALE (0xC0 = 192 = Right Wall).
        // Landing on a flat tile (0xFF).

        mockSprite.setAngle((byte) 0xC0); // Right Wall Angle
        mockSprite.setAir(true);          // In Air
        mockSprite.setGroundMode(GroundMode.GROUND); // Ensure ground mode is GROUND (setAir does this usually)

        // Simulating falling down
        mockSprite.setYSpeed((short) 1000);
        mockSprite.setXSpeed((short) 0);

        // Landing on 0xFF tile
        SensorResult result = new SensorResult((byte) 0xFF, (byte) -1, 0, Direction.DOWN);
        SensorResult[] results = {result, result};

        Method method = PlayableSpriteMovementManager.class.getDeclaredMethod("doTerrainCollision", AbstractPlayableSprite.class, SensorResult[].class);
        method.setAccessible(true);
        method.invoke(manager, mockSprite, results);

        // Verification:
        // The fix should ensure the angle snaps to 0x00 (Ground) based on the GroundMode, not the stale angle 0xC0.

        assertEquals("Angle should be reset to 0x00 when landing from Air on flat ground, ignoring stale angle.",
                (byte) 0x00, mockSprite.getAngle());
    }
}
