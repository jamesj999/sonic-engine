package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.GroundMode;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Headless integration test verifying that the S3K big ring (Obj_SSEntryRing)
 * collision box matches the ROM's SSEntry_Range definition.
 * <p>
 * Scenario: Sonic starts rolling in AIZ2 at (4891, 839) with ground speed 2000,
 * holding down+right. He breaks through a rock wall, speeds through a winding
 * pipe section, breaks through another rock wall, and ends up on the far side
 * of a big ring at approximately (6619, 1097). He should hit a wall and stop
 * there without entering the special stage, even after 120 idle frames.
 * <p>
 * Bug: The collision box was too large because SSEntry_Range values were
 * interpreted as {min, max} offsets from center, but the ROM's
 * Check_PlayerInRange uses them as {offset, span} pairs:
 * <pre>
 *   left  = ring_x + range[0]
 *   right = left   + range[1]   (NOT ring_x + range[1])
 *   top   = ring_y + range[2]
 *   bottom = top   + range[3]   (NOT ring_y + range[3])
 * </pre>
 * This made the box 72x120 instead of the correct 48x80 pixels.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz2BigRingCollision {
    private static final int ZONE_AIZ = 0;
    private static final int ACT_2 = 1;

    // Start state: rolling through rock wall section
    private static final short START_X = 4891;
    private static final short START_Y = 839;
    private static final short START_GSPEED = 2000;

    // Minimum X threshold â€” Sonic must advance past here to prove he traversed the section
    private static final int MIN_PROGRESS_X = 6400;

    // Timeout for traversal (rock walls + pipes)
    private static final int TRAVERSAL_TIMEOUT_FRAMES = 15 * 60;  // 15 seconds at 60fps

    // Consecutive frames with near-zero gspeed to consider Sonic "stopped"
    private static final int STOPPED_FRAME_COUNT = 10;

    // Frames to idle at the stop position to confirm no special stage entry
    private static final int IDLE_FRAMES_AT_STOP = 120;

    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);

        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_2);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();
        GameServices.level().getObjectManager().reset(0);
    }

    /**
     * Sonic rolls through AIZ2's rock-wall/pipe section and stops at a wall
     * just past a big ring. He should NOT enter the special stage because
     * his final position is outside the ring's collision box.
     */
    @Test
    public void rollingPastBigRing_stopsAtWall_doesNotEnterSpecialStage() {
        applyStartState();

        int maxX = sprite.getX();
        int stoppedAtFrame = -1;
        int stoppedConsecutive = 0;

        // Phase 1: Roll rightward through the section (hold down+right)
        for (int frame = 0; frame < TRAVERSAL_TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, true, false, true, false);  // down + right

            // Check if Sonic has been captured by the big ring (the bug)
            if (sprite.isObjectControlled() || sprite.isHidden()) {
                fail("Sonic entered the special stage at frame " + frame
                        + " pos=(" + sprite.getX() + "," + sprite.getY() + ")"
                        + " centre=(" + sprite.getCentreX() + "," + sprite.getCentreY() + ")"
                        + " -- collision box is too large");
            }

            if (sprite.getDead()) {
                fail("Sonic died at frame " + frame
                        + " pos=(" + sprite.getX() + "," + sprite.getY() + ")");
            }

            int x = sprite.getX();
            if (x > maxX) maxX = x;

            // Detect Sonic stopped: near-zero ground speed for several consecutive frames
            if (x >= MIN_PROGRESS_X && Math.abs(sprite.getGSpeed()) < 128) {
                stoppedConsecutive++;
                if (stoppedConsecutive >= STOPPED_FRAME_COUNT) {
                    stoppedAtFrame = frame;
                    break;
                }
            } else {
                stoppedConsecutive = 0;
            }
        }

        assertTrue(stoppedAtFrame >= 0, "Sonic should advance past X=" + MIN_PROGRESS_X
                        + " and stop -- maxX=" + maxX
                        + " actual pos=(" + sprite.getX() + "," + sprite.getY() + ")"
                        + " gspeed=" + sprite.getGSpeed());

        // Phase 2: Idle at the stop position for 120 frames -- should NOT trigger big ring
        for (int frame = 0; frame < IDLE_FRAMES_AT_STOP; frame++) {
            fixture.stepIdleFrames(1);

            if (sprite.isObjectControlled() || sprite.isHidden()) {
                fail("Sonic entered the special stage while idle at frame "
                        + (stoppedAtFrame + frame)
                        + " pos=(" + sprite.getX() + "," + sprite.getY() + ")"
                        + " centre=(" + sprite.getCentreX() + "," + sprite.getCentreY() + ")"
                        + " -- collision box is too large");
            }
        }

        // Confirm Sonic is still alive and in normal gameplay state
        assertFalse(sprite.isObjectControlled(), "Sonic should not be object-controlled");
        assertFalse(sprite.isHidden(), "Sonic should not be hidden");
        assertFalse(sprite.getDead(), "Sonic should not be dead");
    }

    private void applyStartState() {
        sprite.setX(START_X);
        sprite.setY(START_Y);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed(START_GSPEED);
        sprite.setAngle((byte) 0);
        sprite.setGroundMode(GroundMode.GROUND);
        sprite.setAir(false);
        sprite.setRolling(true);
        sprite.setJumping(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);
        sprite.setHidden(false);
        sprite.setObjectMappingFrameControl(false);
        sprite.setForcedAnimationId(-1);

        Camera camera = fixture.camera();
        camera.setFrozen(false);
        camera.updatePosition(true);
        sprite.updateSensors(sprite.getX(), sprite.getY());
    }
}


