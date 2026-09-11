package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.CameraBounds;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for vertical Y-wrapping in Sonic 1 Labyrinth Zone Act 3.
 * <p>
 * LZ3 uses vertical wrapping (top boundary = 0xFF00 = -256), creating a
 * seamless vertical loop. When Sonic jumps near Y=0 and the camera wraps,
 * Sonic and nearby objects must remain visible â€” they must not vanish.
 * <p>
 * ROM reference: DeformLayers.asm lines 549-558 (upward wrap),
 * lines 572-580 (downward wrap).
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Lz3VerticalWrap {
    // Zone registry uses gameplay progression order: GHZ=0, MZ=1, SYZ=2, LZ=3
    // (differs from ROM zone IDs in Sonic1Constants)
    private static final int ZONE_LZ = 3;
    private static final int ACT_3 = 2; // 0-indexed

    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_LZ, ACT_3);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();

        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager != null) {
            objectManager.reset(fixture.camera().getX());
        }
    }

    /**
     * Directly tests the upward vertical wrap by positioning Sonic just above
     * the wrap threshold (camera Y approaching -256). When the camera wraps,
     * both Sonic's screen-relative Y and the camera's new position must be
     * consistent â€” Sonic must remain visible on screen.
     */
    @Test
    public void sonicRemainsVisibleThroughUpwardWrap() {
        AbstractPlayableSprite sprite = fixture.sprite();
        Camera camera = fixture.camera();

        // Position Sonic so the camera is near the upward wrap threshold.
        // Camera Y = spriteY - 96. For camera Y to reach -256 (the wrap
        // threshold at y <= -0x100), spriteY must be at or below -256+96 = -160.
        sprite.setCentreX((short) 1500);
        sprite.setCentreY((short) -170);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) -0x400); // Moving upward
        sprite.setGSpeed((short) 0);
        sprite.setAir(true);
        sprite.setAngle((byte) 0);

        // Snap camera to sprite position
        camera.updatePosition(true);

        boolean wrapOccurred = false;
        boolean sonicVanished = false;
        int vanishFrame = -1;
        int screenHeight = camera.getHeight();

        for (int frame = 0; frame < 120; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            int spriteScreenY = sprite.getCentreY() - camera.getY();

            if (camera.didWrapLastFrame()) {
                wrapOccurred = true;
            }

            // After wrap, check Sonic is still on screen (generous margin for sprite size)
            boolean onScreenY = spriteScreenY >= -64 && spriteScreenY <= screenHeight + 64;

            if (wrapOccurred && !onScreenY && vanishFrame < 0) {
                sonicVanished = true;
                vanishFrame = frame;
            }

            // Stop early once we've confirmed wrap + visibility for several frames
            if (wrapOccurred && !sonicVanished && frame > 10) {
                break;
            }
        }

        assertTrue(wrapOccurred, "Vertical wrap should have occurred during the test");
        assertFalse(sonicVanished, "Sonic should remain visible after camera vertical wrap (vanished at frame "
                + vanishFrame + ")");
    }

    /**
     * Tests the downward vertical wrap by positioning Sonic near the bottom
     * of the wrap range (Y approaching 2048). Same invariant: Sonic must stay
     * visible through the wrap.
     */
    @Test
    public void sonicRemainsVisibleThroughDownwardWrap() {
        AbstractPlayableSprite sprite = fixture.sprite();
        Camera camera = fixture.camera();

        // Camera Y = spriteY - 96. For camera Y >= 2048 (VERTICAL_WRAP_RANGE),
        // spriteY must be >= 2048+96 = 2144. Place Sonic at Y=2120 falling down.
        sprite.setCentreX((short) 1500);
        sprite.setCentreY((short) 2120);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0x400); // Moving downward
        sprite.setGSpeed((short) 0);
        sprite.setAir(true);
        sprite.setAngle((byte) 0);

        camera.updatePosition(true);

        boolean wrapOccurred = false;
        boolean sonicVanished = false;
        int vanishFrame = -1;
        int screenHeight = camera.getHeight();

        for (int frame = 0; frame < 120; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            int spriteScreenY = sprite.getCentreY() - camera.getY();

            if (camera.didWrapLastFrame()) {
                wrapOccurred = true;
            }

            boolean onScreenY = spriteScreenY >= -64 && spriteScreenY <= screenHeight + 64;

            if (wrapOccurred && !onScreenY && vanishFrame < 0) {
                sonicVanished = true;
                vanishFrame = frame;
            }

            if (wrapOccurred && !sonicVanished && frame > 10) {
                break;
            }
        }

        assertTrue(wrapOccurred, "Vertical wrap should have occurred during the test");
        assertFalse(sonicVanished, "Sonic should remain visible after camera downward wrap (vanished at frame "
                + vanishFrame + ")");
    }

    /**
     * Tests that CameraBounds.contains() correctly identifies objects at
     * wrapped Y positions as on-screen when vertical wrapping is active.
     * <p>
     * In S1 LZ3 (12-bit Y layout), objects near the "top" of the level at
     * Y=-150 are stored as Y=3946 (unsigned 12-bit: 4096-150). After an
     * upward camera wrap to Y=1788, the modular distance is only 110 pixels
     * â€” the object should be on-screen.
     * <p>
     * Without the fix, CameraBounds.contains() does a linear Y check and
     * reports the object as off-screen (3946 vs camera range 1788-2012).
     */
    @Test
    public void cameraBoundsContainsWrappedY() {
        int wrapRange = Camera.VERTICAL_WRAP_RANGE; // 2048

        // Simulate camera at Y=1788 (just after upward wrap from ~-260)
        CameraBounds bounds = new CameraBounds(1400, 1788, 1720, 1788 + 224);
        bounds.setVerticalWrapRange(wrapRange);

        // Object at Y=3946 (unsigned 12-bit for Y=-150: 4096-150=3946)
        // Modular distance from camera top: (3946-1788) % 2048 = 2158 % 2048 = 110
        // 110 < 224 (screen height), so it's on screen.
        assertTrue(bounds.contains(1500, 3946), "Object at Y=3946 should be on-screen with camera at Y=1788 (wrap range 2048)");

        // Object at Y=1900 (directly in the camera range)
        assertTrue(bounds.contains(1500, 1900), "Object at Y=1900 should be on-screen with camera at Y=1788");

        // Object at Y=100 (far from camera in modular space: distance 360)
        // (100-1788) % 2048 + 2048 = 360. 360 > 224, so off screen.
        assertFalse(bounds.contains(1500, 100), "Object at Y=100 should be off-screen with camera at Y=1788");

        // Without wrapping, the Y=3946 object would be off-screen
        CameraBounds linearBounds = new CameraBounds(1400, 1788, 1720, 1788 + 224);
        // verticalWrapRange defaults to 0 (no wrapping)
        assertFalse(linearBounds.contains(1500, 3946), "Without wrapping, Y=3946 should be off-screen (linear check)");
    }

    /**
     * Tests the Y adjustment logic used by GraphicsManager.renderPatternWithId()
     * to compute correct rendering positions for objects on the "wrong side"
     * of a vertical wrap boundary.
     * <p>
     * Verifies that modular Y adjustment brings the object world Y into the
     * same wrap period as the camera, matching VDP modular sprite behavior.
     */
    @Test
    public void renderYAdjustmentMatchesVdpWrap() {
        int wrapRange = Camera.VERTICAL_WRAP_RANGE; // 2048

        // Simulate: camera at Y=1788, object at Y=3946
        // Expected: adjusted Y brings object into range near camera
        int cameraY = 1788;
        int objY = 3946;
        int adjustedY = adjustYForWrap(objY, cameraY, wrapRange);
        int screenY = adjustedY - cameraY;

        assertTrue(screenY >= 0 && screenY <= 224, "Adjusted screen Y should be on-screen (0-224), got " + screenY);
        assertEquals(110, screenY, "Modular screen Y should be 110 (3946-1788=2158, 2158%2048=110)");

        // Object at Y=100, camera at Y=1788 â€” should remain off-screen
        int adjustedFar = adjustYForWrap(100, cameraY, wrapRange);
        int screenFar = adjustedFar - cameraY;
        // Modular distance: (100-1788+2048) % 2048 = 360. > 224, so off-screen.
        assertTrue(screenFar > 224 || screenFar < -64, "Object at Y=100 should stay off-screen, screenY=" + screenFar);

        // Object already near camera (Y=1900, camera=1788) â€” no significant change
        int adjustedNear = adjustYForWrap(1900, cameraY, wrapRange);
        assertEquals(1900, adjustedNear, "Object at Y=1900 should not change");
    }

    /**
     * Replicates the Y adjustment logic from GraphicsManager.renderPatternWithId()
     * for testing purposes.
     */
    private static int adjustYForWrap(int y, int cameraY, int wrapRange) {
        int diff = y - cameraY;
        diff = ((diff % wrapRange) + wrapRange) % wrapRange;
        if (diff > wrapRange / 2) {
            diff -= wrapRange;
        }
        return cameraY + diff;
    }
}


