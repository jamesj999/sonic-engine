package uk.co.jamesj999.sonic.sprites.managers;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.lang.reflect.Method;

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
}
