package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.game.GroundMode;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Grouped headless tests for Sonic 2 CNZ Act 1.
 *
 * Level data is loaded once via {@link SharedLevel#load} in {@code @BeforeAll};
 * sprite, camera, and game state are reset per test via {@link HeadlessTestFixture}.
 *
 * Merged from:
 * <ul>
 *   <li>TestCNZCeilingStateExit</li>
 *   <li>TestCNZFlipperLaunch</li>
 *   <li>TestCNZForcedSpinTunnel</li>
 * </ul>
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Cnz1Headless {
    private static final int ZONE_CNZ = 3;
    private static final int ACT_1 = 0;

    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_CNZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
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
    }

    // ========== From TestCNZCeilingStateExit ==========

    // Starting position - first spring in the chain
    private static final short CEILING_START_X = 3621;
    private static final short CEILING_START_Y = 1820;

    // Test parameters for ceiling state exit
    private static final int CHARGE_FRAMES = 130;
    private static final int CEILING_Y_THRESHOLD = 858;
    private static final int MAX_CEILING_FRAMES = 180;
    private static final int MAX_CEILING_TEST_FRAMES = 600;

    /**
     * Test that Sonic properly exits ceiling state when walking off a ceiling.
     *
     * The test sequence (two-spring chain required to trigger the bug):
     * 1. Charge and launch from first diagonal spring at (3621, 1860)
     * 2. Player goes around a loop
     * 3. Player lands on second vertical spring at (4208, 1776)
     * 4. Charge and launch from second spring
     * 5. Sonic enters ceiling state
     * 6. Sonic should exit ceiling state when walking off the ceiling end
     *
     * The bug: Sonic stays in ceiling mode even when standing on the ground below.
     * This only triggers when chaining the two springs in sequence.
     */
    @Test
    public void testCeilingStateExitsWhenWalkingOffCeiling() throws Exception {
        // Set start position and enable pinball mode for spring interaction
        sprite.setX(CEILING_START_X);
        sprite.setY(CEILING_START_Y);
        sprite.setPinballMode(true);
        fixture.camera().updatePosition(true);

        System.out.println("=== CNZ Ceiling State Exit Test (Two-Spring Chain) ===");
        System.out.println("Start position: (" + CEILING_START_X + ", " + CEILING_START_Y + ")");
        System.out.println("Pinball mode: " + sprite.getPinballMode());
        System.out.println("Initial ground mode: " + sprite.getGroundMode());
        System.out.println();

        // Run a few frames first to trigger object spawning
        System.out.println("Running initial frames to spawn objects...");
        Camera camera = fixture.camera();

        for (int i = 0; i < 5; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        // Debug: Print active launcher springs
        var objectManager = GameServices.level().getObjectManager();
        if (objectManager != null) {
            System.out.println("Total active objects: " + objectManager.getActiveObjects().size());
            System.out.println("Active launcher springs (obj 0x85):");
            for (ObjectInstance obj : objectManager.getActiveObjects()) {
                if (obj.getSpawn().objectId() == 0x85) {
                    System.out.println("  - at (" + obj.getSpawn().x() + ", " + obj.getSpawn().y() +
                            ") subtype=0x" + Integer.toHexString(obj.getSpawn().subtype()) +
                            " class=" + obj.getClass().getSimpleName());
                }
            }
        }

        // Reposition sprite above the first spring
        sprite.setX(CEILING_START_X);
        sprite.setY(CEILING_START_Y);
        sprite.setAir(true);
        sprite.setYSpeed((short) 0);
        sprite.setPinballMode(true);
        System.out.println("Repositioned player to: (" + sprite.getX() + ", " + sprite.getY() + ") (in air)");
        System.out.println();

        // Tracking variables
        boolean enteredCeilingState = false;
        boolean ceilingAtImpossibleY = false;
        int ceilingFrameCount = 0;
        int totalFrames = 0;
        int minYReached = Integer.MAX_VALUE;
        int springLaunchCount = 0;

        // ============================================
        // PHASE 1: Charge and launch from FIRST spring
        // ============================================
        System.out.println("=== PHASE 1: First Spring (diagonal at ~3621, 1860) ===");
        System.out.println("Charging for " + CHARGE_FRAMES + " frames...");

        for (int frame = 0; frame < CHARGE_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, true);  // Hold jump
            totalFrames++;
        }
        System.out.println("Releasing first spring at frame " + totalFrames);
        System.out.printf("Position: (%d, %d), ObjCtrl=%s%n",
                sprite.getX(), sprite.getY(), sprite.isObjectControlled() ? "Y" : "N");

        // ============================================
        // PHASE 2: Travel through loop, land on second spring
        // ============================================
        System.out.println();
        System.out.println("=== PHASE 2: Traveling to second spring ===");
        System.out.println("Frame | X Pos | Y Pos | YSpeed | GroundMode | Air | ObjCtrl");
        System.out.println("------|-------|-------|--------|------------|-----|-------");

        boolean reachedSecondSpring = false;
        int secondSpringX = 4208;
        int maxTravelFrames = 300;

        for (int frame = 0; frame < maxTravelFrames && !reachedSecondSpring; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            totalFrames++;

            camera.updatePosition(false);

            short ySpeed = sprite.getYSpeed();
            if (ySpeed < -1000) {
                springLaunchCount++;
            }

            if (sprite.isObjectControlled() && Math.abs(sprite.getX() - secondSpringX) < 50) {
                reachedSecondSpring = true;
                System.out.println(">>> Reached second spring at frame " + totalFrames);
            }

            if (frame < 20 || (frame + 1) % 20 == 0 || sprite.isObjectControlled()) {
                System.out.printf("%5d | %5d | %5d | %6d | %10s | %3s | %s%n",
                        totalFrames, sprite.getX(), sprite.getY(), ySpeed,
                        sprite.getGroundMode(), sprite.getAir() ? "YES" : "NO",
                        sprite.isObjectControlled() ? "YES" : "NO");
            }
        }

        if (!reachedSecondSpring) {
            System.out.println("Did not land directly on second spring - repositioning player");
            System.out.println("Current position: (" + sprite.getX() + ", " + sprite.getY() + ")");

            sprite.setX((short) 4208);
            sprite.setY((short) 1710);
            sprite.setAir(true);
            sprite.setYSpeed((short) 0);
            sprite.setPinballMode(true);
            System.out.println("Repositioned to: (" + sprite.getX() + ", " + sprite.getY() + ") above second spring");

            camera.updatePosition(true);

            System.out.println("Active launcher springs after camera update:");
            for (ObjectInstance obj : objectManager.getActiveObjects()) {
                if (obj.getSpawn().objectId() == 0x85) {
                    System.out.println("  - at (" + obj.getSpawn().x() + ", " + obj.getSpawn().y() +
                            ") subtype=0x" + Integer.toHexString(obj.getSpawn().subtype()));
                }
            }

            for (int i = 0; i < 30 && !sprite.isObjectControlled(); i++) {
                fixture.stepFrame(false, false, false, false, false);
                camera.updatePosition(false);
                totalFrames++;
            }
            System.out.println("After landing: (" + sprite.getX() + ", " + sprite.getY() + "), ObjCtrl=" +
                    (sprite.isObjectControlled() ? "Y" : "N"));
        }

        // ============================================
        // PHASE 3: Charge and launch from SECOND spring
        // ============================================
        System.out.println();
        System.out.println("=== PHASE 3: Second Spring (vertical at ~4208, 1776) ===");
        System.out.println("Charging for " + CHARGE_FRAMES + " frames...");

        for (int frame = 0; frame < CHARGE_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, true);
            totalFrames++;
        }
        System.out.println("Releasing second spring at frame " + totalFrames);
        System.out.printf("Position: (%d, %d), ObjCtrl=%s%n",
                sprite.getX(), sprite.getY(), sprite.isObjectControlled() ? "Y" : "N");

        // ============================================
        // PHASE 4: Monitor ceiling state after second launch
        // ============================================
        System.out.println();
        System.out.println("=== PHASE 4: Monitoring ceiling state ===");
        System.out.println("Frame | X Pos | Y Pos | YSpeed | GroundMode | Air | Angle | CeilFrames");
        System.out.println("------|-------|-------|--------|------------|-----|-------|----------");

        for (int frame = 0; frame < MAX_CEILING_TEST_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            totalFrames++;

            short currentY = sprite.getY();
            short currentYSpeed = sprite.getYSpeed();
            GroundMode groundMode = sprite.getGroundMode();
            boolean inCeilingState = (groundMode == GroundMode.CEILING);
            short gSpeed = sprite.getGSpeed();

            if (currentYSpeed < -1000) {
                springLaunchCount++;
            }

            if (currentY < minYReached) {
                minYReached = currentY;
            }

            if (inCeilingState) {
                if (!enteredCeilingState) {
                    enteredCeilingState = true;
                    System.out.println(">>> ENTERED CEILING STATE at frame " + totalFrames +
                            ", Y=" + currentY + ", gSpeed=" + gSpeed);
                }
                ceilingFrameCount++;

                if (currentY < CEILING_Y_THRESHOLD) {
                    ceilingAtImpossibleY = true;
                    System.out.println(">>> BUG DETECTED: In ceiling state at Y=" + currentY +
                            " (above threshold " + CEILING_Y_THRESHOLD + " - impossible position!)");
                }
            } else if (enteredCeilingState && ceilingFrameCount > 0) {
                System.out.println(">>> EXITED CEILING STATE at frame " + totalFrames +
                        ", Y=" + currentY + ", gSpeed=" + gSpeed + " after " + ceilingFrameCount + " frames");
            }

            if (frame < 50 || (frame + 1) % 20 == 0 || inCeilingState) {
                System.out.printf("%5d | %5d | %5d | %6d | %10s | %3s | 0x%02X | %5d%n",
                        totalFrames, sprite.getX(), currentY, currentYSpeed,
                        groundMode, sprite.getAir() ? "YES" : "NO",
                        sprite.getAngle() & 0xFF, ceilingFrameCount);
            }

            if (ceilingFrameCount > MAX_CEILING_FRAMES) {
                System.out.println();
                System.out.println(">>> TEST STOPPING: Ceiling state exceeded " + MAX_CEILING_FRAMES + " frames");
                break;
            }

            if (enteredCeilingState && !inCeilingState && ceilingFrameCount > 0) {
                System.out.println();
                System.out.println(">>> Ceiling state properly exited");
                break;
            }
        }

        System.out.println();
        System.out.println("=== Test Results ===");
        System.out.println("Spring launches detected: " + springLaunchCount);
        System.out.println("Minimum Y reached: " + minYReached);
        System.out.println("Entered ceiling state: " + enteredCeilingState);
        System.out.println("Total frames in ceiling state: " + ceilingFrameCount);
        System.out.println("Ceiling at impossible Y (< " + CEILING_Y_THRESHOLD + "): " + ceilingAtImpossibleY);
        System.out.println("Final position: (" + sprite.getX() + ", " + sprite.getY() + ")");
        System.out.println("Final ground mode: " + sprite.getGroundMode());
        System.out.println("Final gSpeed: " + sprite.getGSpeed());
        System.out.println();

        // Assertions
        assertTrue(enteredCeilingState, "Should enter ceiling state during test sequence. " +
                        "Spring launches: " + springLaunchCount + ", min Y reached: " + minYReached + ". " +
                        "If this fails, the two-spring chain may not be working correctly.");

        assertFalse(ceilingAtImpossibleY, "Should NOT be in ceiling state at Y < " + CEILING_Y_THRESHOLD + ". " +
                        "This indicates Sonic is stuck in ceiling state and moving to impossible positions.");

        assertTrue(ceilingFrameCount <= MAX_CEILING_FRAMES, "Ceiling state should not exceed " + MAX_CEILING_FRAMES + " frames (3 seconds). " +
                        "Sonic should either slow down and drop off, or roll off the end of the ceiling. " +
                        "This is the ceiling state exit bug.");
    }

    // ========== From TestCNZFlipperLaunch ==========

    // Starting position (on top of vertical flipper)
    private static final short FLIPPER_START_X = 612;
    private static final short FLIPPER_START_Y = 857;

    // Target Y position - pass if Sonic ever reaches below this
    private static final int FLIPPER_TARGET_Y = 700;

    // Maximum frames to run before failing
    private static final int FLIPPER_MAX_FRAMES = 30;

    /**
     * Test that the CNZ horizontal flipper launches Sonic to the expected height.
     *
     * The horizontal flipper (subtype 0x01) auto-launches when Sonic pushes against it.
     * We walk Sonic into the flipper to trigger the push, then track the minimum Y position.
     * If Sonic reaches below TARGET_Y (700), the test passes.
     */
    @Test
    public void testFlipperLaunchReachesTargetHeight() throws Exception {
        sprite.setX(FLIPPER_START_X);
        sprite.setY(FLIPPER_START_Y);
        fixture.camera().updatePosition(true);

        System.out.println("=== CNZ Horizontal Flipper Launch Test ===");
        System.out.println("Requested start position: (" + FLIPPER_START_X + ", " + FLIPPER_START_Y + ")");
        System.out.println("Actual initial position: (" + sprite.getX() + ", " + sprite.getY() + ")");
        System.out.println("Initial air state: " + sprite.getAir());
        System.out.println("Initial YSpeed: " + sprite.getYSpeed());
        System.out.println("Target Y: < " + FLIPPER_TARGET_Y);
        System.out.println();

        int minY = sprite.getY();

        System.out.println("Frame | X Pos | Y Pos | YSpeed | Air | MinY");
        System.out.println("------|-------|-------|--------|-----|-----");

        for (int frame = 0; frame < FLIPPER_MAX_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);

            short currentX = sprite.getX();
            short currentY = sprite.getY();
            short ySpeed = sprite.getYSpeed();
            boolean inAir = sprite.getAir();

            if (currentY < minY) {
                minY = currentY;
            }

            System.out.printf("%5d | %5d | %5d | %6d | %3s | %4d%n",
                    frame + 1, currentX, currentY, ySpeed, inAir ? "YES" : "NO", minY);

            if (minY < FLIPPER_TARGET_Y) {
                System.out.println();
                System.out.println("SUCCESS: Reached target height at frame " + (frame + 1));
                break;
            }
        }

        System.out.println();
        System.out.println("Final minimum Y: " + minY);
        System.out.println("Target was: < " + FLIPPER_TARGET_Y);

        assertTrue(minY < FLIPPER_TARGET_Y, "Flipper should launch Sonic to Y < " + FLIPPER_TARGET_Y +
                ", but minimum Y reached was " + minY +
                ". This indicates the flipper launch is not working correctly.");
    }

    // ========== From TestCNZForcedSpinTunnel ==========

    // Starting position (approaching the forced spin tunnel)
    private static final short TUNNEL_START_X = 7787;
    private static final short TUNNEL_START_Y = 921;

    // Target X position - pass if Sonic reaches beyond this (traveled through tunnel)
    private static final int TUNNEL_TARGET_X = 7915;

    // Maximum frames to run before failing
    private static final int TUNNEL_MAX_FRAMES = 100;

    /**
     * Test that Sonic can enter and travel through the forced spin tunnel.
     *
     * The forced spin tunnel should cause Sonic to automatically roll when entering.
     * We walk Sonic into the tunnel entrance and verify he passes through to the other side.
     * If Sonic's X position exceeds TARGET_X (7915), the test passes.
     */
    @Test
    public void testForcedSpinTunnelEntry() throws Exception {
        sprite.setX(TUNNEL_START_X);
        sprite.setY(TUNNEL_START_Y);
        fixture.camera().updatePosition(true);

        System.out.println("=== CNZ Forced Spin Tunnel Test ===");
        System.out.println("Requested start position: (" + TUNNEL_START_X + ", " + TUNNEL_START_Y + ")");
        System.out.println("Actual initial position: (" + sprite.getX() + ", " + sprite.getY() + ")");
        System.out.println("Initial air state: " + sprite.getAir());
        System.out.println("Initial XSpeed: " + sprite.getXSpeed());
        System.out.println("Target X: > " + TUNNEL_TARGET_X);
        System.out.println();

        int maxX = sprite.getX();

        System.out.println("Frame | X Pos | Y Pos | XSpeed | YSpeed | Air | Rolling | MaxX");
        System.out.println("------|-------|-------|--------|--------|-----|---------|-----");

        for (int frame = 0; frame < TUNNEL_MAX_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);

            short currentX = sprite.getX();
            short currentY = sprite.getY();
            short xSpeed = sprite.getXSpeed();
            short ySpeed = sprite.getYSpeed();
            boolean inAir = sprite.getAir();
            boolean rolling = sprite.getRolling();

            if (currentX > maxX) {
                maxX = currentX;
            }

            System.out.printf("%5d | %5d | %5d | %6d | %6d | %3s | %7s | %4d%n",
                    frame + 1, currentX, currentY, xSpeed, ySpeed,
                    inAir ? "YES" : "NO", rolling ? "YES" : "NO", maxX);

            if (currentX > TUNNEL_TARGET_X) {
                System.out.println();
                System.out.println("SUCCESS: Passed through tunnel at frame " + (frame + 1));
                break;
            }
        }

        System.out.println();
        System.out.println("Final maximum X: " + maxX);
        System.out.println("Target was: > " + TUNNEL_TARGET_X);

        assertTrue(maxX > TUNNEL_TARGET_X, "Sonic should pass through the forced spin tunnel to X > " + TUNNEL_TARGET_X +
                ", but maximum X reached was " + maxX +
                ". This indicates Sonic bounced off the tunnel entrance instead of entering.");
    }
}


