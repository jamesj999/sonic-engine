package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless integration test for HTZ Act 2 spring loop bug.
 *
 * <p>This test reproduces a scenario where Sonic enters a spring loop but fails
 * to trigger a spring he's facing opposite to, causing him to stop moving.
 *
 * <p>Level data is loaded once via {@link SharedLevel#load} in {@code @BeforeAll};
 * sprite, camera, and game state are reset per test via {@link HeadlessTestFixture}.
 *
 * <p>Test scenario:
 * <ol>
 *   <li>Spawn Sonic at position X=8473, Y=1465 in HTZ Act 2</li>
 *   <li>Hold Right for 1 frame to initiate movement</li>
 *   <li>Let simulation run for 300 frames with no input</li>
 *   <li>Pass condition: Sonic's GSpeed is non-zero (still bouncing between springs)</li>
 *   <li>Fail condition: Sonic's GSpeed is 0 (stopped, spring not triggered)</li>
 * </ol>
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestHtzSpringLoop {
    // Test position for HTZ Act 2 spring loop area (from debug overlay - decimal values)
    private static final short START_X = (short) 8475;  // X position from debug overlay
    private static final short START_Y = (short) 1465;  // Y position from debug overlay

    // Zone/Act indices for loadZoneAndAct (not ROM zone IDs)
    // HTZ is zone index 4 in Sonic2ZoneRegistry (EHZ=0, CPZ=1, ARZ=2, CNZ=3, HTZ=4)
    private static final int HTZ_ZONE_INDEX = 4;
    private static final int ACT_2_INDEX = 1;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, HTZ_ZONE_INDEX, ACT_2_INDEX);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();
    }

    /**
     * Tests that Sonic maintains movement in a spring loop.
     *
     * <p>In the original game, Sonic should bounce between springs indefinitely
     * as long as he has enough speed. This test verifies that springs trigger
     * correctly even when Sonic is facing the opposite direction.
     */
    @Test
    public void testSpringLoopMaintainsMovement() {
        // Position sprite at the spring loop test location
        sprite.setX(START_X);
        sprite.setY(START_Y);
        fixture.camera().updatePosition(true);

        // Reset the object manager's spawn window to the new camera position
        // so objects near our test position are spawned
        GameServices.level().getObjectManager().reset(fixture.camera().getX());

        // Allow the spawn window to populate: objects are marked active in the
        // placement set by reset(), but syncActiveSpawnsLoad() only instantiates
        // them during the first objectManager.update() call.  Without these idle
        // frames the spring instances do not exist when Sonic approaches them,
        // so the touch-response collision that triggers the spring bounce never fires.
        // 5 frames is enough for spring instances to be created and run their first
        // update, and for Sonic to settle onto the floor at the test position.
        fixture.stepIdleFrames(5);

        // Object spawn warmup can settle or nudge the player before the behavior
        // check begins. Re-anchor to the explicit debug-overlay start point so
        // this test continues to exercise the spring loop, not fixture drift.
        sprite.setX(START_X);
        sprite.setY(START_Y);
        fixture.camera().updatePosition(true);

        // Log initial state
        logState("Initial");

        // Verify we're at the correct starting position
        assertEquals(START_X, sprite.getX(), "Initial X position should match START_X");
        assertEquals(START_Y, sprite.getY(), "Initial Y position should match START_Y");

        // Step 1 frame holding right to initiate movement
        fixture.stepFrame(false, false, false, true, false);
        logState("After 1 frame right");

        // Let simulation run 300 frames with no input
        // Track when GSpeed hits 0 to diagnose the bug
        short prevGSpeed = sprite.getGSpeed();
        short prevXSpeed = sprite.getXSpeed();
        for (int i = 0; i < 300; i++) {
            fixture.stepIdleFrames(1);
            int frame = i + 2; // Frame number (1 was the initial right press)

            short gSpeed = sprite.getGSpeed();
            short xSpeed = sprite.getXSpeed();

            // Log when GSpeed transitions to 0 (this is when the bug occurs)
            if (gSpeed == 0 && prevGSpeed != 0) {
                System.out.printf("*** GSPEED HIT 0 at frame %d: X=%d, XSpeed=%d->%d, GSpeed=%d->%d, Air=%b%n",
                    frame, sprite.getX(), prevXSpeed, xSpeed, prevGSpeed, gSpeed, sprite.getAir());
            }

            // Log every frame for first 60 frames to see the spring interaction
            if (frame <= 60) {
                logState("Frame " + frame);
            } else if (frame % 50 == 0) {
                logState("Frame " + frame);
            }

            prevGSpeed = gSpeed;
            prevXSpeed = xSpeed;
        }

        logState("Final");

        // Verify Sonic is still moving
        short gSpeed = sprite.getGSpeed();
        assertNotEquals(0, gSpeed, "Sonic should still be moving after 300 frames in spring loop. " +
            "GSpeed=0 indicates spring was not triggered.");
    }

    /**
     * Helper method to log sprite state for debugging.
     */
    private void logState(String label) {
        System.out.printf("%s: X=%d (0x%04X), Y=%d (0x%04X), GSpeed=%d, XSpeed=%d, YSpeed=%d, Air=%b, Facing=%s%n",
            label,
            sprite.getX(), sprite.getX() & 0xFFFF,
            sprite.getY(), sprite.getY() & 0xFFFF,
            sprite.getGSpeed(),
            sprite.getXSpeed(),
            sprite.getYSpeed(),
            sprite.getAir(),
            sprite.getDirection());
    }
}


