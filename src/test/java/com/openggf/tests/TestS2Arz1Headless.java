package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.game.GroundMode;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Grouped headless tests for Sonic 2 ARZ Act 1.
 *
 * <p>Level data is loaded once via {@link SharedLevel#load} in {@code @BeforeAll};
 * sprite, camera, and game state are reset per test via {@link HeadlessTestFixture}.
 *
 * <p>Merged from:
 * <ul>
 *   <li>TestArzRunRight - plane switcher diagnostics and run-right tests</li>
 *   <li>TestArzSpringLoop - spring launch and full loop traversal</li>
 *   <li>TestArzDebug - debug run through spring-loop section</li>
 * </ul>
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Arz1Headless {
    private static final int ZONE_ARZ = 2;
    private static final int ACT_1 = 0;

    // Common start position for ARZ spring-loop tests
    private static final short START_X = 2468;
    private static final short START_Y = 841;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_ARZ, ACT_1);

        // ARZ tests need object manager reset with camera position
        Camera camera = GameServices.camera();
        var om = GameServices.level().getObjectManager();
        if (om != null) om.reset(camera.getX());
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

    // ========== From TestArzRunRight -- Plane Switcher Diagnostics ==========

    /**
     * Diagnostic: dump all plane switchers in ARZ1.
     *
     * <p>Key findings from investigation:
     * <ul>
     *   <li>Primary path (0x0C/0x0D): NO wall at X=2595 (chunk 204 has primaryCollisionIdx=0)</li>
     *   <li>Secondary path (0x0E/0x0F): WALL at X=2595 (chunk 204 has altCollisionIdx=251, fully solid)</li>
     *   <li>Plane switcher at (2576, 576, subtype=0x0A) switches to SECONDARY when
     *       playerX crosses 2576 while playerY is in [448, 704)</li>
     * </ul>
     */
    @Test
    public void dumpPlaneSwitchers() {
        sprite.setX(START_X);
        sprite.setY(START_Y);

        var level = GameServices.level().getCurrentLevel();
        System.out.println("=== ALL PLANE SWITCHERS IN ARZ1 ===");
        boolean foundDocumentedSwitcher = false;
        for (var obj : level.getObjects()) {
            if (obj.objectId() == 0x03) {
                // The documented switcher that swaps to the SECONDARY path lives at (2576, 576).
                if (obj.x() == 2576 && obj.y() == 576) {
                    foundDocumentedSwitcher = true;
                }
                int sub = obj.subtype();
                int sizeIdx = sub & 0x03;
                int[] spans = {0x20, 0x40, 0x80, 0x100};
                boolean horiz = (sub & 0x04) != 0;
                int pathSide1 = (sub & 0x08) != 0 ? 1 : 0;
                int pathSide0 = (sub & 0x10) != 0 ? 1 : 0;
                boolean groundedOnly = (sub & 0x80) != 0;
                String type = horiz ? "Y-based(horizontal)" : "X-based(vertical)";
                int half = spans[sizeIdx];

                String spanDesc;
                if (horiz) {
                    spanDesc = String.format("X\u2208[%d,%d) side=Y\u2277%d",
                            obj.x() - half, obj.x() + half, obj.y());
                } else {
                    spanDesc = String.format("Y\u2208[%d,%d) side=X\u2277%d",
                            obj.y() - half, obj.y() + half, obj.x());
                }

                System.out.printf("PS: X=%d Y=%d sub=0x%02X flags=0x%02X | %s half=%d %s " +
                                "left/above\u2192path%d right/below\u2192path%d%s%n",
                        obj.x(), obj.y(), sub, obj.renderFlags(),
                        type, half, spanDesc,
                        pathSide0, pathSide1,
                        groundedOnly ? " [grounded-only]" : "");
            }
        }

        assertTrue(foundDocumentedSwitcher,
                "Expected a plane switcher (objectId 0x03) at the documented (2576, 576) location in ARZ1");
    }

    @Test
    public void dumpCollisionDataAtWall() {
        sprite.setX(START_X);
        sprite.setY(START_Y);

        LevelManager lm = GameServices.level();
        System.out.println("Chunk solidity at wall location (X=2580-2610, Y=440-850):");
        // Documented: the SECONDARY path (0x0E/0x0F) has a solid wall at X=2595
        // (chunk 204 altCollisionIdx=251, fully solid), while the PRIMARY path
        // (0x0C/0x0D) has no wall there. Verify the secondary wall solidity exists
        // somewhere along the wall's vertical span at X=2595.
        boolean secondaryWallAt2595 = false;
        for (int y = 440; y <= 850; y += 10) {
            for (int x = 2560; x <= 2610; x += 8) {
                var desc = lm.getChunkDescAt((byte) 0, x, y);
                if (desc != null) {
                    boolean pTop = desc.isSolidityBitSet(0x0C);
                    boolean pLrb = desc.isSolidityBitSet(0x0D);
                    boolean sTop = desc.isSolidityBitSet(0x0E);
                    boolean sLrb = desc.isSolidityBitSet(0x0F);
                    if (pTop || pLrb || sTop || sLrb) {
                        System.out.printf("  (%d,%d) chunk=%d | P:%b/%b S:%b/%b%n",
                                x, y, desc.getChunkIndex(), pTop, pLrb, sTop, sLrb);
                    }
                }
            }
            var wallDesc = lm.getChunkDescAt((byte) 0, 2595, y);
            if (wallDesc != null
                    && (wallDesc.isSolidityBitSet(0x0E) || wallDesc.isSolidityBitSet(0x0F))) {
                secondaryWallAt2595 = true;
            }
        }

        assertTrue(secondaryWallAt2595,
                "Expected the documented SECONDARY-path solid wall at X=2595 in ARZ1");
    }

    /**
     * Test running right from (2468, 841) on PRIMARY path (default).
     * This should succeed - primary path has no wall at X=2595.
     */
    @Test
    public void testRunRightOnPrimaryPath() {
        sprite.setX(START_X);
        sprite.setY(START_Y);

        // Verify starting on primary
        assertEquals(0x0C, sprite.getTopSolidBit(), "Should start on primary top");
        assertEquals(0x0D, sprite.getLrbSolidBit(), "Should start on primary lrb");

        for (int f = 0; f < 400; f++) {
            fixture.camera().updateBoundaryEasing();
            fixture.stepFrame(false, false, false, true, false); // hold right

            if (sprite.getX() > 2700) {
                break;
            }
        }
        assertTrue(sprite.getX() > 2600, "Sonic should pass X=2600 on primary path. Actual X=" + sprite.getX());
    }

    /**
     * Simulate spring-loop with RIGHT held after spring bounce.
     * This more closely matches actual gameplay where the user holds right.
     */
    @Test
    public void debugSpringLoopHoldingRight() {
        sprite.setX(START_X);
        sprite.setY(START_Y);

        Camera cam = fixture.camera();

        // Phase 1: Run left to hit the spring
        boolean hitSpring = false;
        for (int f = 0; f < 600; f++) {
            cam.updateBoundaryEasing();
            fixture.stepFrame(false, false, true, false, false);
            if (sprite.getGSpeed() > 0x200) {
                hitSpring = true;
                System.out.printf("SPRING at f%d: X=%d Y=%d G=%d%n",
                        f, sprite.getX(), sprite.getY(), sprite.getGSpeed());
                break;
            }
        }

        assertTrue(hitSpring, "Sonic should hit a spring while running left (gSpeed should exceed 0x200). "
                + "Final gSpeed=" + sprite.getGSpeed() + ", X=" + sprite.getX());

        // Phase 2: Hold RIGHT after spring bounce
        byte prevTop = sprite.getTopSolidBit();
        byte prevLrb = sprite.getLrbSolidBit();
        int prevX = sprite.getX();
        short prevG = sprite.getGSpeed();

        // Same loop-traversal oracle as testSpringLaunchAndLoopTraversal:
        // GROUND -> RIGHTWALL -> CEILING -> LEFTWALL -> GROUND.
        boolean enteredCeiling = false;
        boolean returnedToGround = false;
        boolean gotStuck = false;

        for (int f = 0; f < 600; f++) {
            cam.updateBoundaryEasing();
            fixture.stepFrame(false, false, false, true, false); // hold RIGHT

            int x = sprite.getX();
            int y = sprite.getY();
            short g = sprite.getGSpeed();
            byte top = sprite.getTopSolidBit();
            byte lrb = sprite.getLrbSolidBit();
            int dx = x - prevX;

            GroundMode mode = sprite.getGroundMode();
            if (mode == GroundMode.CEILING) {
                enteredCeiling = true;
            }
            if (enteredCeiling && mode == GroundMode.GROUND && !sprite.getAir()) {
                returnedToGround = true;
            }

            if (top != prevTop || lrb != prevLrb) {
                System.out.printf("*** PATH CHANGE f%d: X=%d Y=%d 0x%02X/0x%02X -> 0x%02X/0x%02X%n",
                        f, x, y, prevTop, prevLrb, top, lrb);
                prevTop = top;
                prevLrb = lrb;
            }

            boolean anomaly = (g == 0 && prevG != 0 && !sprite.getAir()) || (dx < -3);
            boolean nearTarget = (x >= 2550 && x <= 2650);
            if (f < 20 || f % 30 == 0 || nearTarget || anomaly) {
                System.out.printf("f%d: X=%d Y=%d G=%d Air=%b Ang=0x%02X Mode=%s path=0x%02X/0x%02X dx=%d%n",
                        f, x, y, g, sprite.getAir(), sprite.getAngle() & 0xFF,
                        sprite.getGroundMode(), top, lrb, dx);
            }

            if (g == 0 && prevG != 0 && !sprite.getAir() && f > 5) {
                gotStuck = true;
                System.out.printf("*** STUCK at f%d: X=%d Y=%d path=0x%02X/0x%02X%n",
                        f, x, y, top, lrb);
                break;
            }

            if (returnedToGround) {
                break;
            }

            if (x > 3200) {
                System.out.printf("*** PASSED 3200 at f%d%n", f);
                break;
            }

            prevX = x;
            prevG = g;
        }

        System.out.printf("FINAL: X=%d Y=%d G=%d path=0x%02X/0x%02X%n",
                sprite.getX(), sprite.getY(), sprite.getGSpeed(),
                sprite.getTopSolidBit(), sprite.getLrbSolidBit());

        assertFalse(gotStuck, "gSpeed should not be reset to 0 (Sonic stuck) during loop traversal.");
        assertTrue(enteredCeiling, "Sonic should enter CEILING mode while traversing the ARZ1 loop.");
        assertTrue(returnedToGround,
                "Sonic should return to GROUND mode after completing the full 360-degree ARZ1 loop. "
                        + "Final X=" + sprite.getX() + ", final mode=" + sprite.getGroundMode());
    }

    // ========== From TestArzSpringLoop -- Spring Launch and Loop Traversal ==========

    // Maximum frames to allow for running left + spring + loop traversal
    private static final int MAX_FRAMES = 600;

    /**
     * Headless regression test for ARZ1 spring-to-loop traversal.
     *
     * <p>Sonic starts at (2468, 841) in ARZ Act 1, runs left until he hits a
     * horizontal spring, then must maintain enough speed to traverse the full
     * loop to the right. The test verifies that:
     * <ol>
     *   <li>gSpeed is not incorrectly reset to 0 after the spring bounce</li>
     *   <li>Sonic traverses the full 360-degree loop (enters CEILING mode and
     *       returns to GROUND mode)</li>
     * </ol>
     */
    @Test
    public void testSpringLaunchAndLoopTraversal() {
        sprite.setX(START_X);
        sprite.setY(START_Y);

        logState("Initial");

        // Phase 1: Run left until we hit the spring.
        // The spring will reverse our direction (gSpeed goes from negative to positive).
        boolean hitSpring = false;
        int springFrame = -1;

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            fixture.stepFrame(false, false, true, false, false); // hold left

            short gSpeed = sprite.getGSpeed();

            if (frame < 10 || frame % 20 == 0) {
                logState("Run-left frame " + frame);
            }

            // Detect spring hit: gSpeed switches from negative/zero to positive
            if (!hitSpring && gSpeed > 0x200) {
                hitSpring = true;
                springFrame = frame;
                logState("*** SPRING HIT at frame " + frame);
                break;
            }
        }

        assertTrue(hitSpring, "Sonic should hit a spring while running left (gSpeed should become positive). "
                + "Final gSpeed=" + sprite.getGSpeed() + ", X=" + sprite.getX());

        // Phase 2: After the spring, let Sonic proceed to the right with no input.
        // Track ground mode transitions to verify full 360-degree loop traversal:
        // GROUND -> RIGHTWALL -> CEILING -> LEFTWALL -> GROUND
        boolean enteredCeiling = false;
        boolean returnedToGround = false;
        boolean gSpeedWasReset = false;
        int resetFrame = -1;
        int loopCompleteFrame = -1;
        short prevGSpeed = sprite.getGSpeed();

        for (int frame = springFrame + 1; frame < MAX_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false); // no input

            short gSpeed = sprite.getGSpeed();
            GroundMode mode = sprite.getGroundMode();

            if (frame <= springFrame + 30 || frame % 20 == 0) {
                logState("Post-spring frame " + frame);
            }

            // Track loop progression through ground modes
            if (mode == GroundMode.CEILING) {
                enteredCeiling = true;
            }
            if (enteredCeiling && mode == GroundMode.GROUND && !sprite.getAir()) {
                returnedToGround = true;
                loopCompleteFrame = frame;
                logState("*** LOOP COMPLETED at frame " + frame);
                break;
            }

            // Detect gSpeed reset to 0 while still in the loop (the bug we're testing for)
            if (!returnedToGround && gSpeed == 0 && prevGSpeed != 0 && !sprite.getAir()) {
                gSpeedWasReset = true;
                resetFrame = frame;
                logState("*** GSPEED RESET TO 0 at frame " + frame);
                break;
            }

            prevGSpeed = gSpeed;
        }

        logState("Final");

        assertFalse(gSpeedWasReset, "gSpeed should not be reset to 0 during loop traversal (was reset at frame "
                + resetFrame + "). This indicates a physics bug preventing loop completion.");

        assertTrue(enteredCeiling, "Sonic should enter CEILING mode during the loop traversal.");

        assertTrue(returnedToGround, "Sonic should return to GROUND mode after completing the full 360-degree loop. "
                + "Loop complete frame=" + loopCompleteFrame + ", final X=" + sprite.getX()
                + ", final mode=" + sprite.getGroundMode());
    }

    // ========== From TestArzDebug -- Debug Run ==========

    @Test
    public void debugRun() {
        sprite.setX(START_X);
        sprite.setY(START_Y);

        // Run left to hit spring
        boolean hitSpring = false;
        for (int f = 0; f < 600; f++) {
            fixture.stepFrame(false, false, true, false, false);
            if (sprite.getGSpeed() > 0x200) {
                hitSpring = true;
                System.out.printf("SPRING at frame %d: X=%d Y=%d GSpeed=%d%n",
                        f, sprite.getX(), sprite.getY(), sprite.getGSpeed());
                break;
            }
        }
        assertTrue(hitSpring, "Sonic should hit a spring while running left. Final gSpeed=" + sprite.getGSpeed());

        // Now run with no input for 600 frames, track everything
        int minY = sprite.getY(), maxAngle = 0;
        for (int f = 0; f < 600; f++) {
            fixture.stepFrame(false, false, false, false, false);
            int angle = sprite.getAngle() & 0xFF;
            if (sprite.getY() < minY) minY = sprite.getY();
            if (angle > 0x20 && angle < 0xE0 && angle > maxAngle) maxAngle = angle;
            if (f < 80 || f % 30 == 0 || sprite.getGSpeed() == 0) {
                System.out.printf("f%d: X=%d Y=%d G=%d X=%d Y=%d Air=%b Ang=0x%02X Mode=%s%n",
                        f, sprite.getX(), sprite.getY(), sprite.getGSpeed(),
                        sprite.getXSpeed(), sprite.getYSpeed(),
                        sprite.getAir(), angle, sprite.getGroundMode());
            }
            if (sprite.getGSpeed() == 0 && !sprite.getAir() && f > 5) {
                System.out.printf("*** STOPPED at frame %d%n", f);
                break;
            }
        }
        System.out.printf("Min Y reached: %d, Max non-flat angle: 0x%02X%n", minY, maxAngle);

        // Running through the spring-loop section, Sonic should climb above his
        // starting Y (loop apex) and rotate off flat ground (non-flat angle).
        // characterization guard: exact apex/angle depend on ROM physics; we assert
        // the qualitative bounds the diagnostic prints (minY climbs past start,
        // maxAngle leaves the flat band 0x20..0xE0 that the tracker already excludes).
        assertTrue(minY < START_Y,
                "Sonic should climb above the start Y (loop apex). minY=" + minY + ", startY=" + START_Y);
        assertTrue(maxAngle > 0x20,
                "Sonic should reach a non-flat angle while traversing the loop. maxAngle=0x"
                        + Integer.toHexString(maxAngle));
    }

    // ========== Shared Helpers ==========

    private void logState(String label) {
        System.out.printf("%s: X=%d, Y=%d, GSpeed=%d, XSpeed=%d, YSpeed=%d, Air=%b, Angle=0x%02X, Mode=%s, Facing=%s%n",
                label,
                sprite.getX(), sprite.getY(),
                sprite.getGSpeed(), sprite.getXSpeed(), sprite.getYSpeed(),
                sprite.getAir(),
                sprite.getAngle() & 0xFF,
                sprite.getGroundMode(),
                sprite.getDirection());
    }
}


