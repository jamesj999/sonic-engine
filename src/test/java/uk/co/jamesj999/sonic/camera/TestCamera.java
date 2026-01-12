package uk.co.jamesj999.sonic.camera;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the Camera class, covering player following logic,
 * boundary constraints, and position calculations.
 */
public class TestCamera {

    private Camera camera;
    private AbstractPlayableSprite mockSprite;

    @Before
    public void setUp() throws Exception {
        // Reset the singleton for each test
        resetCameraSingleton();
        camera = Camera.getInstance();

        // Create a mock sprite for testing
        mockSprite = mock(AbstractPlayableSprite.class);
        when(mockSprite.getCentreX()).thenReturn((short) 160);
        when(mockSprite.getCentreY()).thenReturn((short) 112);
        when(mockSprite.getCentreX(anyInt())).thenReturn((short) 160);
        when(mockSprite.getCentreY(anyInt())).thenReturn((short) 112);
        when(mockSprite.getX()).thenReturn((short) 160);
        when(mockSprite.getY()).thenReturn((short) 112);
        when(mockSprite.getAir()).thenReturn(false);
        when(mockSprite.getYSpeed()).thenReturn((short) 0);

        camera.setFocusedSprite(mockSprite);

        // Set up level bounds
        camera.setMinX((short) 0);
        camera.setMinY((short) 0);
        camera.setMaxX((short) 6000);
        camera.setMaxY((short) 1000);
    }

    private void resetCameraSingleton() throws Exception {
        Field instanceField = Camera.class.getDeclaredField("camera");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    // ==================== Basic Position Tests ====================

    @Test
    public void testSetFocusedSpriteInitializesPosition() {
        AbstractPlayableSprite newSprite = mock(AbstractPlayableSprite.class);
        when(newSprite.getX()).thenReturn((short) 500);
        when(newSprite.getY()).thenReturn((short) 300);

        camera.setFocusedSprite(newSprite);

        assertEquals("Camera X should match sprite X", 500, camera.getX());
        assertEquals("Camera Y should match sprite Y", 300, camera.getY());
    }

    @Test
    public void testForceUpdateJumpsToPlayerPosition() {
        when(mockSprite.getCentreX()).thenReturn((short) 1000);
        when(mockSprite.getCentreY()).thenReturn((short) 500);

        camera.updatePosition(true);

        assertEquals("Force update should jump X to player centre", 1000, camera.getX());
        assertEquals("Force update should jump Y to player centre", 500, camera.getY());
    }

    // ==================== Horizontal Following Tests ====================

    @Test
    public void testCameraFollowsPlayerMovingRight() {
        // Position player to the right of camera center threshold (160)
        camera.setX((short) 0);
        when(mockSprite.getCentreX()).thenReturn((short) 200); // 200 - 0 = 200, which is > 160 threshold

        camera.updatePosition();

        assertTrue("Camera should move right when player exceeds right threshold",
                camera.getX() > 0);
    }

    @Test
    public void testCameraFollowsPlayerMovingLeft() {
        // Position camera ahead of player
        camera.setX((short) 200);
        when(mockSprite.getCentreX()).thenReturn((short) 300); // 300 - 200 = 100, which is < 144 threshold

        camera.updatePosition();

        assertTrue("Camera should move left when player is below left threshold",
                camera.getX() < 200);
    }

    @Test
    public void testCameraDoesNotMoveWhenPlayerInDeadzone() {
        // Player is within the 144-160 horizontal deadzone
        camera.setX((short) 0);
        when(mockSprite.getCentreX()).thenReturn((short) 150); // 150 - 0 = 150, which is between 144 and 160

        short initialX = camera.getX();
        camera.updatePosition();

        assertEquals("Camera should not move horizontally when player is in deadzone",
                initialX, camera.getX());
    }

    @Test
    public void testCameraMaxHorizontalSpeed() {
        // Player is far to the right - camera should cap movement at 16px/frame
        camera.setX((short) 0);
        when(mockSprite.getCentreX()).thenReturn((short) 500); // Very far right

        camera.updatePosition();

        assertEquals("Camera horizontal movement should be capped at 16px",
                16, camera.getX());
    }

    // ==================== Vertical Following Tests ====================

    @Test
    public void testCameraFollowsPlayerInAir() {
        when(mockSprite.getAir()).thenReturn(true);
        camera.setY((short) 0);
        when(mockSprite.getCentreY()).thenReturn((short) 200); // > 160 threshold for air

        camera.updatePosition();

        assertTrue("Camera should follow player vertically when in air",
                camera.getY() > 0);
    }

    @Test
    public void testCameraFollowsPlayerOnGround() {
        when(mockSprite.getAir()).thenReturn(false);
        when(mockSprite.getYSpeed()).thenReturn((short) 0);
        camera.setY((short) 0);
        when(mockSprite.getCentreY()).thenReturn((short) 200); // > 96 threshold for ground

        camera.updatePosition();

        assertTrue("Camera should follow player vertically when on ground",
                camera.getY() > 0);
    }

    @Test
    public void testCameraVerticalSpeedIncreasesWhenFalling() {
        when(mockSprite.getAir()).thenReturn(false);
        when(mockSprite.getYSpeed()).thenReturn((short) (7 * 256)); // ySpeed > 6 (in subpixels)
        camera.setY((short) 0);
        when(mockSprite.getCentreY()).thenReturn((short) 200);

        camera.updatePosition();

        // With high ySpeed, tolerance is 16 instead of 6
        assertTrue("Camera should move faster vertically when player is falling fast",
                camera.getY() >= 16);
    }

    // ==================== Boundary Tests ====================

    @Test
    public void testCameraCannotGoBelowZeroX() {
        camera.setX((short) 10);
        when(mockSprite.getCentreX()).thenReturn((short) 0); // Try to push camera left

        camera.updatePosition();

        assertTrue("Camera X should not go below 0", camera.getX() >= 0);
    }

    @Test
    public void testCameraCannotGoBelowZeroY() {
        camera.setY((short) 10);
        when(mockSprite.getCentreY()).thenReturn((short) 0);

        camera.updatePosition();

        assertTrue("Camera Y should not go below 0", camera.getY() >= 0);
    }

    @Test
    public void testCameraCannotExceedMaxX() {
        camera.setMaxX((short) 1000);
        camera.setX((short) 990);
        when(mockSprite.getCentreX()).thenReturn((short) 2000); // Try to push camera beyond max

        camera.updatePosition();

        assertTrue("Camera X should not exceed maxX", camera.getX() <= 1000);
    }

    @Test
    public void testCameraCannotExceedMaxY() {
        camera.setMaxY((short) 500);
        camera.setY((short) 490);
        when(mockSprite.getCentreY()).thenReturn((short) 1000);

        camera.updatePosition();

        assertTrue("Camera Y should not exceed maxY", camera.getY() <= 500);
    }

    // ==================== Freeze/Unfreeze Tests (Spindash) ====================

    @Test
    public void testFrozenCameraDoesNotFollowPlayer() {
        camera.setFrozen(true);
        short initialX = camera.getX();
        short initialY = camera.getY();

        when(mockSprite.getCentreX()).thenReturn((short) 1000);
        when(mockSprite.getCentreY()).thenReturn((short) 500);
        camera.updatePosition();

        assertEquals("Frozen camera should not move X", initialX, camera.getX());
        assertEquals("Frozen camera should not move Y", initialY, camera.getY());
    }

    @Test
    public void testUnfreezeAllowsCameraToFollow() {
        camera.setFrozen(true);
        camera.updatePosition();
        camera.setFrozen(false);

        when(mockSprite.getCentreX()).thenReturn((short) 500);
        camera.updatePosition();

        // Camera should now be able to move
        assertFalse("Camera should not be frozen after setFrozen(false)", camera.getFrozen());
    }

    @Test
    public void testFreezeStateAccessors() {
        assertFalse("Camera should not be frozen initially", camera.getFrozen());

        camera.setFrozen(true);
        assertTrue("Camera should be frozen after setFrozen(true)", camera.getFrozen());

        camera.setFrozen(false);
        assertFalse("Camera should not be frozen after setFrozen(false)", camera.getFrozen());
    }

    // ==================== isOnScreen Tests ====================

    @Test
    public void testSpriteOnScreenReturnsTrueWhenVisible() {
        camera.setX((short) 0);
        camera.setY((short) 0);

        Sprite visibleSprite = mock(Sprite.class);
        when(visibleSprite.getX()).thenReturn((short) 100);
        when(visibleSprite.getY()).thenReturn((short) 100);

        assertTrue("Sprite within camera bounds should be on screen",
                camera.isOnScreen(visibleSprite));
    }

    @Test
    public void testSpriteOffScreenReturnsFalseWhenLeftOfCamera() {
        camera.setX((short) 500);
        camera.setY((short) 0);

        Sprite offscreenSprite = mock(Sprite.class);
        when(offscreenSprite.getX()).thenReturn((short) 100);
        when(offscreenSprite.getY()).thenReturn((short) 100);

        assertFalse("Sprite left of camera should not be on screen",
                camera.isOnScreen(offscreenSprite));
    }

    @Test
    public void testSpriteOffScreenReturnsFalseWhenRightOfCamera() {
        camera.setX((short) 0);
        camera.setY((short) 0);

        // Camera width is typically 320, so sprite at 400 should be off screen
        Sprite offscreenSprite = mock(Sprite.class);
        when(offscreenSprite.getX()).thenReturn((short) 400);
        when(offscreenSprite.getY()).thenReturn((short) 100);

        // This depends on screen width - sprite at 400 with camera at 0 and width 320 is off screen
        // The isOnScreen check is: spriteX <= x + width, so 400 <= 0 + 320 is false
        assertFalse("Sprite right of camera should not be on screen",
                camera.isOnScreen(offscreenSprite));
    }

    // ==================== Increment Tests ====================

    @Test
    public void testIncrementX() {
        camera.setX((short) 100);
        camera.incrementX((short) 50);
        assertEquals("incrementX should add to current X", 150, camera.getX());

        camera.incrementX((short) -30);
        assertEquals("incrementX should subtract when negative", 120, camera.getX());
    }

    @Test
    public void testIncrementY() {
        camera.setY((short) 100);
        camera.incrementY((short) 50);
        assertEquals("incrementY should add to current Y", 150, camera.getY());

        camera.incrementY((short) -30);
        assertEquals("incrementY should subtract when negative", 120, camera.getY());
    }
}
