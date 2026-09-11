package com.openggf.tests;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless integration tests for HTZ (Hill Top Zone) and related object bugs
 * in the Sonic 2 engine reimplementation.
 *
 * <p>These are bug reproduction tests that should <b>FAIL</b> initially (proving the
 * bugs exist), then <b>PASS</b> after the corresponding fixes are applied.
 *
 * <p><b>Bug #3 - Rising Pillar Too Tall:</b> Rising pillar (0x2B) in ARZ has Y-radius
 * 0x20 (32px) and rises 4px/frame for 6 frames. When fully extended, Sonic should be
 * able to clear it from a standing jump on adjacent terrain.
 *
 * <p><b>Bug #5 - Springboard Speed Skip:</b> Springboard (0x40) relies on
 * {@code contact.standing()} which requires per-frame overlap. At high speed, Sonic
 * passes over without triggering the launch.
 *
 * <p><b>Bug #6 - Seesaw Ghost Ball:</b> Seesaw (0x14) spawns a ball on first update.
 * On camera re-entry, a duplicate ball may spawn if the seesaw re-initialises.
 *
 * <p><b>Bug #7 - Diagonal Spring Not Activating:</b> Diagonal spring (0x41)
 * {@code isDiagonalXThresholdMet()} requires Sonic be 4px past the spring centre.
 * Standing on the flat portion of a diagonal spring never meets this threshold.
 *
 * <p><b>Bug #10 - 90-Degree Corner Bounce:</b> Landing on the exact corner between
 * flat terrain and a cliff can produce an incorrect bounce instead of a clean landing.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestHTZObjectBugs {
    private Sonic sprite;
    private HeadlessTestRunner testRunner;

    // Zone/Act indices for loadZoneAndAct (not ROM zone IDs)
    // EHZ=0, CPZ=1, ARZ=2, CNZ=3, HTZ=4
    private static final int ZONE_EHZ = 0;
    private static final int ZONE_ARZ = 2;
    private static final int ZONE_HTZ = 4;
    private static final int ACT_1 = 0;

    // Object IDs from Sonic 2 layout
    private static final int OBJ_SEESAW = 0x14;
    private static final int OBJ_RISING_PILLAR = 0x2B;
    private static final int OBJ_SPRINGBOARD = 0x40;
    private static final int OBJ_SPRING = 0x41;

    // Diagonal spring type value: (subtype >> 3) & 0xE == 6
    private static final int SPRING_TYPE_DIAGONAL_UP = 6;

    @BeforeEach
    public void setUp() throws Exception {
        // Initialize headless graphics (no GL context needed)
        GraphicsManager.getInstance().initHeadless();

        // Create Sonic sprite at origin (position set after level load)
        SonicConfigurationService configService = SonicConfigurationService.getInstance();
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        sprite = new Sonic(mainCode, (short) 0, (short) 0);

        // Add sprite to SpriteManager
        GameServices.sprites().addSprite(sprite);

        // Set camera focus - must be done BEFORE level load
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sprite);
        // Reset camera to ensure clean state (frozen may be left over from death in other tests)
        camera.setFrozen(false);

        // Load HTZ Act 1 (zone index 4, act index 0)
        GameServices.level().loadZoneAndAct(ZONE_HTZ, ACT_1);

        // Ensure GroundSensor uses the current LevelManager instance
        // (static field may be stale from earlier tests)
        GroundSensor.setLevelManager(GameServices.level());

        // Fix camera position - loadZoneAndAct sets bounds AFTER updatePosition,
        // so the camera may have been clamped incorrectly. Force update again now
        // that bounds are set.
        camera.updatePosition(true);

        // Reset the object manager's spawn window to the new camera position
        // so objects near our test position are spawned
        GameServices.level().getObjectManager().reset(camera.getX());

        // Create the headless test runner
        testRunner = new HeadlessTestRunner(sprite);
    }

    // -----------------------------------------------------------------------
    // Bug #3: Rising Pillar Too Tall (ARZ)
    // -----------------------------------------------------------------------

    /**
     * Tests that Sonic can jump over a fully extended rising pillar (0x2B) in ARZ.
     *
     * <p>The rising pillar has Y-radius 0x20 (32px), and rises 4px/frame for 6 frames
     * (24px total extension), with a 3-frame delay between each rise step. When fully
     * extended, the pillar top is at {@code pillarY - 0x20 - 24}. Sonic's normal jump
     * should be able to clear this height from flat terrain at the same Y level.
     *
     * <p>If no rising pillar is found in the ARZ1 traversal range, the test is skipped
     * via {@code Assume}.
     */
    @Test
    public void testRisingPillarSonicCanClearFromTop() throws Exception {
        // Rising pillars are in ARZ (zone 2), reload level
        GameServices.level().loadZoneAndAct(ZONE_ARZ, ACT_1);
        GroundSensor.setLevelManager(GameServices.level());
        GameServices.camera().updatePosition(true);
        GameServices.level().getObjectManager().reset(GameServices.camera().getX());

        logState("ARZ loaded");

        ObjectManager objMgr = GameServices.level().getObjectManager();
        ObjectInstance pillarObj = null;

        // Walk right through ARZ1 looking for a rising pillar (0x2B)
        for (int frame = 0; frame < 900; frame++) {
            testRunner.stepFrame(false, false, false, true, false);

            for (ObjectInstance obj : objMgr.getActiveObjects()) {
                if (obj.getSpawn().objectId() == OBJ_RISING_PILLAR && !obj.isDestroyed()) {
                    pillarObj = obj;
                    break;
                }
            }
            if (pillarObj != null) {
                break;
            }

            if (frame % 200 == 0) {
                logState("Searching frame " + (frame + 1));
            }
        }

        Assumptions.assumeTrue(pillarObj != null, "No rising pillar (0x2B) found in ARZ1 traversal range");

        int pillarX = pillarObj.getX();
        int pillarY = pillarObj.getY();
        logState("Pillar found at (" + pillarX + ", " + pillarY + ")");

        // Position Sonic near the pillar to trigger extension (within 64px X range).
        // Use center coordinates since the trigger checks player.getCentreX().
        sprite.setCentreX((short) (pillarX - 48));
        sprite.setCentreY((short) pillarY);
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        GameServices.camera().updatePosition(true);
        objMgr.reset(GameServices.camera().getX());

        // Wait 30 frames for pillar to fully extend (3-frame delay + 6 rise steps)
        for (int i = 0; i < 30; i++) {
            testRunner.stepIdleFrames(1);
        }

        logState("After pillar extension");

        // Calculate expected extended pillar top Y:
        // INITIAL_Y_RADIUS = 0x18 (24), extension = 4px * 6 = 24px
        // Pillar Y decreases by rise amount, yRadius increases by rise amount
        // After extension: Y = pillarY - 24, yRadius = 0x18 + 24 = 48
        // Solid top = (pillarY - 24) - 48 = pillarY - 72
        int extendedTopY = pillarY - 72;

        // Position Sonic just to the left of the pillar, on flat ground
        sprite.setCentreX((short) (pillarX - 40));
        sprite.setCentreY((short) pillarY);
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        GameServices.camera().updatePosition(true);

        // Jump and HOLD the button during ascent to get full jump height.
        // A single-frame press triggers short-jump cap (-0x400), giving only ~39px.
        // Full jump requires holding jump during ascent for ~100px height.
        testRunner.stepFrame(false, false, false, false, true); // Press jump
        logState("Jump initiated");

        int minY = sprite.getY();
        for (int i = 0; i < 60; i++) {
            // Hold jump while ascending (ySpeed < 0), release once falling
            boolean holdJump = sprite.getYSpeed() < 0;
            testRunner.stepFrame(false, false, false, false, holdJump);
            if (sprite.getY() < minY) {
                minY = sprite.getY();
            }
        }

        logState("After jump apex (minY=" + minY + ", extendedTopY=" + extendedTopY + ")");

        // Sonic's apex should be above (less than) the extended pillar top
        assertTrue(minY < extendedTopY, "Sonic's jump apex (Y=" + minY + ") should clear the extended pillar top " +
                "(Y=" + extendedTopY + "). Pillar may be too tall for a standing jump.");
    }

    // -----------------------------------------------------------------------
    // Bug #5: Springboard Speed Skip (HTZ)
    // -----------------------------------------------------------------------

    /**
     * Tests that the springboard (0x40) launches Sonic when he crosses it at high speed.
     *
     * <p>The springboard relies on {@code contact.standing()} which requires per-frame
     * overlap. At high speed (16+ px/frame), Sonic may pass over the springboard without
     * triggering the launch because his collision box never overlaps with the springboard
     * for a full frame.
     *
     * <p>If no springboard is found in HTZ1, the test is skipped via {@code Assume}.
     */
    @Test
    public void testSpringboardHighSpeedLaunch() throws Exception {
        // Springboards (0x40) exist in CPZ, ARZ, MCZ Ã¢â‚¬â€ not HTZ. Load ARZ Act 1.
        GameServices.level().loadZoneAndAct(ZONE_ARZ, ACT_1);
        GroundSensor.setLevelManager(GameServices.level());
        GameServices.camera().updatePosition(true);

        ObjectManager objMgr = GameServices.level().getObjectManager();
        ObjectInstance springboardObj = null;

        // Scan the level for a springboard by teleporting in 256px increments
        for (int scanX = 0; scanX < 10240; scanX += 256) {
            sprite.setX((short) scanX);
            sprite.setY((short) 512);
            GameServices.camera().updatePosition(true);
            objMgr.reset(GameServices.camera().getX());
            testRunner.stepIdleFrames(2);

            for (ObjectInstance obj : objMgr.getActiveObjects()) {
                if (obj.getSpawn().objectId() == OBJ_SPRINGBOARD && !obj.isDestroyed()) {
                    springboardObj = obj;
                    break;
                }
            }
            if (springboardObj != null) break;
        }

        Assumptions.assumeTrue(springboardObj != null, "No springboard (0x40) found in ARZ1");

        int sbX = springboardObj.getX();
        int sbY = springboardObj.getY();
        logState("Springboard found at (" + sbX + ", " + sbY + ")");

        // Position Sonic well above the springboard and let gravity find terrain.
        // Clear ALL velocities to avoid stale state from the scan phase.
        sprite.setCentreX((short) (sbX - 64));
        sprite.setCentreY((short) (sbY - 64));
        sprite.setAir(true);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setRolling(false);
        GameServices.camera().updatePosition(true);
        objMgr.reset(GameServices.camera().getX());

        // Let Sonic fall onto terrain (up to 30 frames)
        for (int i = 0; i < 30; i++) {
            testRunner.stepIdleFrames(1);
            if (!sprite.getAir()) break;
        }

        Assumptions.assumeTrue(!sprite.getAir(), "Sonic could not find ground near springboard at X=" + (sbX - 64));

        logState("Grounded near springboard");

        // Inject high ground speed: 0x1000 (16 px/frame)
        sprite.setGSpeed((short) 0x1000);
        sprite.setXSpeed((short) 0x1000);
        sprite.setYSpeed((short) 0);

        logState("Before high-speed approach");

        // Step 30 frames moving right Ã¢â‚¬â€ Sonic should reach the springboard
        boolean launched = false;
        for (int i = 0; i < 30; i++) {
            testRunner.stepFrame(false, false, false, true, false);
            if (sprite.getYSpeed() < -0x200) {
                launched = true;
                break;
            }
        }

        logState("After high-speed pass");

        // The springboard should have launched Sonic upward
        assertTrue(launched, "Springboard should launch Sonic upward at high speed. " +
                "Final YSpeed=" + sprite.getYSpeed() + " (expected a launch below -0x200 during the pass). " +
                "At 16px/frame, Sonic may have passed over without triggering.");
    }

    // -----------------------------------------------------------------------
    // Bug #6: Seesaw Ghost Ball (HTZ)
    // -----------------------------------------------------------------------

    /**
     * Tests that seesaw (0x14) does not spawn a duplicate ball on camera re-entry.
     *
     * <p>The seesaw spawns a ball child on its first update. When Sonic moves far
     * enough away for the seesaw to despawn and then returns, the seesaw may respawn
     * and create another ball, resulting in duplicate balls at the same position.
     *
     * <p>If no seesaw is found in HTZ1, the test is skipped via {@code Assume}.
     */
    @Test
    public void testSeesawBallNoDuplicateOnReentry() {
        ObjectManager objMgr = GameServices.level().getObjectManager();

        // First HTZ1 seesaw is at (1920, 1000). Teleport near it.
        int seesawX = 1920;
        int seesawY = 1000;

        sprite.setX((short) seesawX);
        sprite.setY((short) (seesawY - 32));
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        GameServices.camera().updatePosition(true);
        objMgr.reset(GameServices.camera().getX());

        // Step frames to spawn the seesaw and its ball
        testRunner.stepIdleFrames(10);

        // Verify seesaw was actually spawned
        ObjectInstance seesawObj = null;
        for (ObjectInstance obj : objMgr.getActiveObjects()) {
            if (obj.getSpawn().objectId() == OBJ_SEESAW && !obj.isDestroyed()) {
                seesawObj = obj;
                break;
            }
        }
        Assumptions.assumeTrue(seesawObj != null, "Seesaw (0x14) not spawned at (" + seesawX + "," + seesawY + ")");

        logState("Seesaw found at (" + seesawX + ", " + seesawY + ")");

        // Position Sonic near the seesaw to ensure ball is spawned
        sprite.setX((short) seesawX);
        sprite.setY((short) (seesawY - 32));
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        GameServices.camera().updatePosition(true);
        objMgr.reset(GameServices.camera().getX());

        // Step frames so the seesaw spawns its ball
        testRunner.stepIdleFrames(10);

        // Count SeesawBall instances among active objects
        int initialBallCount = countSeesawBalls(objMgr);
        System.out.printf("Initial SeesawBall count near seesaw at (%d,%d): %d%n",
                seesawX, seesawY, initialBallCount);

        // Move Sonic far away (>320px from seesaw X) to trigger despawn
        sprite.setX((short) (seesawX + 500));
        sprite.setY((short) seesawY);
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        GameServices.camera().updatePosition(true);
        objMgr.reset(GameServices.camera().getX());

        // Step frames for despawn processing
        testRunner.stepIdleFrames(60);

        logState("After moving away");

        // Move Sonic back near the seesaw to trigger respawn
        sprite.setX((short) seesawX);
        sprite.setY((short) (seesawY - 32));
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        GameServices.camera().updatePosition(true);
        objMgr.reset(GameServices.camera().getX());

        // Step frames for respawn processing
        testRunner.stepIdleFrames(60);

        logState("After returning");

        // Count SeesawBall instances again
        int newBallCount = countSeesawBalls(objMgr);
        System.out.printf("SeesawBall count after re-entry: %d (was %d)%n",
                newBallCount, initialBallCount);

        // There should not be more balls than before (no duplicates)
        assertTrue(newBallCount <= initialBallCount, "Seesaw at (" + seesawX + "," + seesawY + ") spawned duplicate balls " +
                "on camera re-entry. Ball count went from " + initialBallCount +
                " to " + newBallCount + ".");
    }

    // -----------------------------------------------------------------------
    // Bug #7: Diagonal Spring Not Activating (general)
    // -----------------------------------------------------------------------

    /**
     * Tests that a diagonal spring (0x41) activates when Sonic stands on its flat portion.
     *
     * <p>The diagonal spring's {@code isDiagonalXThresholdMet()} requires Sonic to be
     * 4px past the spring's centre. When standing on the flat portion of the diagonal
     * surface, the player may never meet this threshold, causing the spring to never fire.
     *
     * <p>If no diagonal spring is found in HTZ1, the test is skipped via {@code Assume}.
     */
    @Test
    public void testDiagonalSpringActivatesFromGround() {
        ObjectManager objMgr = GameServices.level().getObjectManager();

        // First diagonal spring in HTZ1 is at (1744, 752) subtype 0x30.
        // Teleport Sonic near it so the object spawns.
        int springX = 1744;
        int springY = 752;

        sprite.setX((short) springX);
        sprite.setY((short) (springY - 16));
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        GameServices.camera().updatePosition(true);
        objMgr.reset(GameServices.camera().getX());

        // Step a few frames to let the spring spawn
        testRunner.stepIdleFrames(5);

        // Verify the diagonal spring actually spawned
        ObjectInstance diagonalSpringObj = null;
        for (ObjectInstance obj : objMgr.getActiveObjects()) {
            if (obj.getSpawn().objectId() == OBJ_SPRING && !obj.isDestroyed()) {
                int springType = (obj.getSpawn().subtype() >> 3) & 0xE;
                if (springType == SPRING_TYPE_DIAGONAL_UP) {
                    diagonalSpringObj = obj;
                    break;
                }
            }
        }

        Assumptions.assumeTrue(diagonalSpringObj != null, "Diagonal spring (0x41) not spawned near (" + springX + "," + springY + ")");

        // Use the actual spawned position
        springX = diagonalSpringObj.getX();
        springY = diagonalSpringObj.getY();
        logState("Diagonal spring found at (" + springX + ", " + springY + ")");

        // Position Sonic slightly left of the spring's centre, standing on flat
        // terrain. Then walk right so they enter the spring's sloped contact zone.
        // Use setCentreX/setCentreY for exact placement matching ROM coordinates.
        // The spring at (1744, 752) has slope surface at ~Y=756 for the centre.
        // Player centreY on that surface = surfaceY - yRadius ≈ 756 - 19 = 737.
        sprite.setCentreX((short) (springX - 10));
        sprite.setCentreY((short) (springY - 4));
        sprite.setAir(false);
        sprite.setGSpeed((short) 0x200);
        sprite.setXSpeed((short) 0x200);
        sprite.setYSpeed((short) 0);
        GameServices.camera().updatePosition(true);

        logState("Approaching diagonal spring");

        // Step frames and check if the spring fires
        boolean launched = false;
        for (int i = 0; i < 30; i++) {
            testRunner.stepFrame(false, false, false, true, false);
            if (sprite.getYSpeed() < -0x400) {
                launched = true;
                logState("Spring launched at frame " + (i + 1));
                break;
            }
        }

        logState("After standing on diagonal spring");

        assertTrue(launched, "Diagonal spring at (" + springX + "," + springY + ") should launch " +
                "Sonic when standing on its flat portion. YSpeed=" + sprite.getYSpeed() +
                " (expected < -0x400). The isDiagonalXThresholdMet() check may be " +
                "preventing activation from the flat area.");
    }

    // -----------------------------------------------------------------------
    // Bug #17: Diagonal Spring at Specific HTZ1 Coordinates
    // -----------------------------------------------------------------------

    /**
     * Tests that the diagonal spring at HTZ1 coordinates (3456, 793) activates correctly.
     *
     * <p>This is a specific bug report location where Sonic should be launched by a
     * diagonal spring when standing at or near these coordinates. The spring's
     * {@code isDiagonalXThresholdMet()} guard may prevent activation if Sonic is
     * positioned on the flat portion.
     */
    @Test
    public void testDiagonalSpringAtHTZ1_3456x793() {
        ObjectManager objMgr = GameServices.level().getObjectManager();

        // The original report used the debug HUD's top-left coordinate display.
        // Resolve the actual diagonal spring in that area and place Sonic on the
        // flat lip at the spring's ROM X threshold instead of relying on stale
        // screen-space coordinates.
        final int reportX = 3456;
        final int reportY = 793;
        sprite.setX((short) reportX);
        sprite.setY((short) reportY);
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        GameServices.camera().updatePosition(true);
        objMgr.reset(GameServices.camera().getX());
        testRunner.stepIdleFrames(5);

        ObjectInstance diagonalSpringObj = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ObjectInstance obj : objMgr.getActiveObjects()) {
            if (obj.getSpawn().objectId() != OBJ_SPRING || obj.isDestroyed()) {
                continue;
            }
            int springType = (obj.getSpawn().subtype() >> 3) & 0xE;
            if (springType != SPRING_TYPE_DIAGONAL_UP) {
                continue;
            }
            int distance = Math.abs(obj.getSpawn().x() - reportX);
            if (distance < bestDistance) {
                bestDistance = distance;
                diagonalSpringObj = obj;
            }
        }

        Assumptions.assumeTrue(diagonalSpringObj != null,
                "Diagonal spring (0x41) not spawned near reported HTZ1 coordinates (" + reportX + "," + reportY + ")");

        int springX = diagonalSpringObj.getSpawn().x();
        int springY = diagonalSpringObj.getY();
        sprite.setCentreX((short) (springX - 3));
        sprite.setCentreY((short) (springY - 4));
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        GameServices.camera().updatePosition(true);

        logState("Positioned on specific diagonal spring near reported HTZ1 coordinates");

        // Step 5 idle frames to settle and check for spring activation
        boolean launched = false;
        for (int i = 0; i < 5; i++) {
            testRunner.stepIdleFrames(1);
            if (sprite.getYSpeed() < -0x400) {
                launched = true;
                logState("Spring activated within first 5 frames at frame " + (i + 1));
                break;
            }
        }

        // If not launched in 5 frames, try 10 more with slight movement
        if (!launched) {
            logState("Not launched after 5 frames, stepping 10 more");
            for (int i = 0; i < 10; i++) {
                testRunner.stepIdleFrames(1);
                if (sprite.getYSpeed() < -0x400) {
                    launched = true;
                    logState("Spring activated at frame " + (i + 6));
                    break;
                }
            }
        }

        logState("Final state near reported HTZ1 spring");

        assertTrue(launched, "Diagonal spring at HTZ1 (3456, 793) should launch Sonic. " +
                "YSpeed=" + sprite.getYSpeed() + " (expected < -0x400). " +
                "The spring may not be activating due to the X threshold check.");
    }

    // -----------------------------------------------------------------------
    // Bug #10: 90-Degree Corner Bounce (EHZ)
    // -----------------------------------------------------------------------

    /**
     * Tests that Sonic lands cleanly on terrain corners without bouncing.
     *
     * <p>When Sonic falls diagonally onto the exact corner between flat terrain and
     * a cliff/drop, the collision system may produce an incorrect bounce or fail to
     * land him. This test uses EHZ1 for simpler, more predictable terrain geometry.
     *
     * <p>The test walks Sonic right until he is on flat ground, then positions him
     * above and to the right of his current position and drops him with diagonal
     * velocity. He should land within 60 frames.
     */
    @Test
    public void testLandingOnCornerDoesNotBounce() throws Exception {
        // Use EHZ for simpler terrain
        GameServices.level().loadZoneAndAct(ZONE_EHZ, ACT_1);
        GroundSensor.setLevelManager(GameServices.level());
        GameServices.camera().updatePosition(true);
        GameServices.level().getObjectManager().reset(GameServices.camera().getX());

        logState("EHZ loaded");

        // Walk Sonic right until he is on flat ground with positive gSpeed
        int groundX = -1;
        int groundY = -1;
        for (int frame = 0; frame < 300; frame++) {
            testRunner.stepFrame(false, false, false, true, false);

            if (!sprite.getAir() && sprite.getGSpeed() > 0) {
                // Verify the ground angle is approximately flat (near 0 or 0xFF/0x00)
                int angle = sprite.getAngle() & 0xFF;
                if (angle <= 0x10 || angle >= 0xF0) {
                    groundX = sprite.getX();
                    groundY = sprite.getY();
                    break;
                }
            }
        }

        Assumptions.assumeTrue(groundX > 0, "Could not find flat ground in EHZ1");

        logState("Flat ground found at (" + groundX + ", " + groundY + ")");

        // Position Sonic 64px to the right and 32px above the known ground position.
        // This simulates approaching terrain from a diagonal trajectory that could
        // hit a corner between floor and empty space.
        int testX = groundX + 64;
        int testY = groundY - 32;

        sprite.setX((short) testX);
        sprite.setY((short) testY);
        sprite.setAir(true);
        sprite.setXSpeed((short) 0x200);  // Moving right
        sprite.setYSpeed((short) 0x200);  // Falling down
        sprite.setGSpeed((short) 0);
        GameServices.camera().updatePosition(true);

        logState("Dropped from (" + testX + ", " + testY + ") with diagonal velocity");

        // Step frames until Sonic lands or 60 frames elapse
        boolean landed = false;
        int landFrame = -1;
        for (int i = 0; i < 60; i++) {
            testRunner.stepIdleFrames(1);

            if (!sprite.getAir()) {
                landed = true;
                landFrame = i + 1;
                logState("Landed at frame " + landFrame);

                // Verify the ground angle is reasonable (not a wild bounce angle)
                int angle = sprite.getAngle() & 0xFF;
                boolean angleReasonable = angle <= 0x20 || angle >= 0xE0;
                assertTrue(angleReasonable, "Landing angle should be near-flat when landing on terrain corner. " +
                        "Angle=0x" + Integer.toHexString(angle) + " is too steep, " +
                        "suggesting a corner collision artifact.");
                break;
            }
        }

        logState("Final state (landed=" + landed + ")");

        assertTrue(landed, "Sonic should land within 60 frames when dropped near terrain corner " +
                "at (" + testX + "," + testY + ") with diagonal velocity. " +
                "Still airborne suggests a corner bounce ejected Sonic.");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Counts the number of SeesawBall instances among active objects.
     * Checks the class name to identify ball objects spawned by seesaws.
     */
    private int countSeesawBalls(ObjectManager objMgr) {
        int count = 0;
        for (ObjectInstance obj : objMgr.getActiveObjects()) {
            if (obj.getClass().getSimpleName().contains("SeesawBall") && !obj.isDestroyed()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Helper method to log sprite state for debugging.
     */
    private void logState(String label) {
        System.out.printf("%s: X=%d (0x%04X), Y=%d (0x%04X), GSpeed=%d, XSpeed=%d, " +
                "YSpeed=%d, Air=%b, Rolling=%b, Angle=0x%02X, Facing=%s%n",
                label,
                sprite.getX(), sprite.getX() & 0xFFFF,
                sprite.getY(), sprite.getY() & 0xFFFF,
                sprite.getGSpeed(),
                sprite.getXSpeed(),
                sprite.getYSpeed(),
                sprite.getAir(),
                sprite.getRolling(),
                sprite.getAngle() & 0xFF,
                sprite.getDirection());
    }
}


