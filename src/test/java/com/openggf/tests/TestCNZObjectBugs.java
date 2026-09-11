package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.lang.reflect.Field;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless integration tests for Casino Night Zone (CNZ) object bugs in Sonic 2.
 *
 * <p>These are <b>bug reproduction tests</b> that should initially FAIL (proving the bugs
 * exist), and then PASS after the corresponding fixes are applied. Each test method
 * documents the bug number, expected behavior, and actual (broken) behavior.
 *
 * <h3>Bug Index</h3>
 * <ul>
 *   <li><b>#8</b>  - Bumper collision angles perpendicular to expected</li>
 *   <li><b>#9</b>  - CNZBigBlock Y-offset oscillation too extreme</li>
 *   <li><b>#11</b> - Wall sticking near lifts at (3311, 1305)</li>
 *   <li><b>#12</b> - Infinite bumper bounce trap near y=1097</li>
 *   <li><b>#13</b> - BonusBlock bounce direction sin/cos swap</li>
 *   <li><b>#14</b> - PointPokey capture kills Sonic (objectControlled doesn't disable damage)</li>
 *   <li><b>#15</b> - Slot machine rendered 8px too high (Y offset -12 instead of -4)</li>
 *   <li><b>#16</b> - PointPokey eject survival (camera must center during capture)</li>
 * </ul>
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestCNZObjectBugs {
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

    // Object IDs from Sonic2ObjectIds
    private static final int OBJ_BUMPER = 0x44;
    private static final int OBJ_BIG_BLOCK = 0xD4;
    private static final int OBJ_POINT_POKEY = 0xD6;
    private static final int OBJ_BONUS_BLOCK = 0xD8;

    // Bounce velocity threshold: $700 = 1792, but due to integer trig rounding
    // the actual value may be slightly less. Use a reasonable threshold.
    private static final int BOUNCE_VELOCITY_THRESHOLD = 0x600;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();

        // Reset object spawn windows (was in original @BeforeEach)
        GameServices.level().getObjectManager().reset(fixture.camera().getX());
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Logs the current sprite state for debugging.
     */
    private void logState(String label) {
        System.out.printf("%s: X=%d (0x%04X), Y=%d (0x%04X), GSpeed=%d, XSpeed=%d, YSpeed=%d, " +
                        "Air=%b, Dead=%b, Hurt=%b, Rings=%d%n",
                label,
                sprite.getX(), sprite.getX() & 0xFFFF,
                sprite.getY(), sprite.getY() & 0xFFFF,
                sprite.getGSpeed(),
                sprite.getXSpeed(),
                sprite.getYSpeed(),
                sprite.getAir(),
                sprite.getDead(),
                sprite.isHurt(),
                sprite.getRingCount());
    }

    /**
     * Searches active objects for the first instance with the given object ID.
     * Returns null if not found.
     */
    private ObjectInstance findActiveObject(int objectId) {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager == null) {
            return null;
        }
        Collection<ObjectInstance> active = objectManager.getActiveObjects();
        for (ObjectInstance obj : active) {
            if (obj.getSpawn().objectId() == objectId && !obj.isDestroyed()) {
                return obj;
            }
        }
        return null;
    }

    /**
     * Teleports Sonic to the given position and refreshes the camera/object spawn window.
     * This ensures objects near the target position are spawned.
     */
    private void teleportAndRefresh(int x, int y) {
        sprite.setX((short) x);
        sprite.setY((short) y);

        Camera camera = fixture.camera();
        camera.updatePosition(true);

        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager != null) {
            objectManager.reset(camera.getX());
        }

        // Step a few frames to let spawning take effect
        fixture.stepIdleFrames(5);
    }

    /**
     * Walks Sonic rightward through the level to trigger object spawning across a wide area.
     * Returns the first active object with the given ID found during the walk, or null.
     */
    private ObjectInstance walkAndSearch(int objectId, int startX, int endX, int y) {
        for (int x = startX; x <= endX; x += 256) {
            teleportAndRefresh(x, y);
            ObjectInstance found = findActiveObject(objectId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ========================================================================
    // Bug #8: Bumper collision angles
    // ========================================================================

    /**
     * Bug #8: Bumper bounce angles may be perpendicular to expected direction.
     *
     * <p>The BumperObjectInstance bounce code uses sin for X and cos for Y:
     * <pre>
     * int xVel = (int) (-StrictMath.sin(angle) * BOUNCE_VELOCITY);
     * int yVel = (int) (-StrictMath.cos(angle) * BOUNCE_VELOCITY);
     * </pre>
     * This swaps the usual sin/cos convention. The ROM's CalcAngle also uses this
     * convention, so it may be correct. This test verifies that dropping straight
     * down onto a bumper produces a primarily upward bounce (small X, large negative Y).
     *
     * <p><b>Expected:</b> YSpeed < 0 (upward), |XSpeed| small relative to |YSpeed|.
     * <p><b>Bug behavior:</b> Bounce direction is perpendicular - mostly horizontal
     * instead of vertical for a centered drop.
     */
    @Test
    public void testBumperBounceRadialDirection() {
        System.out.println("=== Bug #8: Bumper Bounce Radial Direction ===");

        // Search for a bumper (0x44) in CNZ1
        // CNZ1 has bumpers scattered throughout; search across the level width
        ObjectInstance bumper = walkAndSearch(OBJ_BUMPER, 0, 8192, 800);
        if (bumper == null) {
            bumper = walkAndSearch(OBJ_BUMPER, 0, 8192, 1200);
        }
        if (bumper == null) {
            bumper = walkAndSearch(OBJ_BUMPER, 0, 8192, 1600);
        }

        Assumptions.assumeTrue(bumper != null, "No bumper (0x44) found in CNZ1; skipping test");

        int bumperX = bumper.getSpawn().x();
        int bumperY = bumper.getSpawn().y();
        System.out.println("Found bumper at: (" + bumperX + ", " + bumperY + ")");

        // Position Sonic directly above the bumper center (use centre coords)
        sprite.setCentreX((short) bumperX);
        sprite.setCentreY((short) (bumperY - 20));
        sprite.setAir(true);
        sprite.setXSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setYSpeed((short) 0x200); // Falling downward

        Camera camera = fixture.camera();
        camera.updatePosition(true);

        logState("Before drop");

        // Step frames until bounce is detected (YSpeed becomes negative)
        boolean bounced = false;
        for (int i = 0; i < 60; i++) {
            fixture.stepFrame(false, false, false, false, false);

            short ySpeed = sprite.getYSpeed();
            if (ySpeed < 0) {
                bounced = true;
                logState("Bounced at frame " + (i + 1));
                break;
            }
        }

        assertTrue(bounced, "Sonic should have bounced off the bumper (YSpeed should become negative)");

        short xSpeed = sprite.getXSpeed();
        short ySpeed = sprite.getYSpeed();

        System.out.println("Post-bounce XSpeed=" + xSpeed + ", YSpeed=" + ySpeed);

        // For a centered drop, the bounce should be primarily upward
        assertTrue(ySpeed < 0, "YSpeed should be negative (bounced upward), was: " + ySpeed);
        assertTrue(Math.abs(xSpeed) < Math.abs(ySpeed), "|XSpeed| should be small relative to |YSpeed| for a centered drop. " +
                        "XSpeed=" + xSpeed + ", YSpeed=" + ySpeed);
    }

    // ========================================================================
    // Bug #9: CNZBigBlock Y-offset too extreme
    // ========================================================================

    /**
     * Bug #9: CNZBigBlock vertical oscillation amplitude is too extreme.
     *
     * <p>The BigBlock (0xD4) has oscillation logic where {@code velocityShifted = yVel << 8}
     * may over-scale the movement, causing the block to travel hundreds of pixels from
     * its spawn position instead of the intended range.
     *
     * <p><b>Expected:</b> Block oscillates within a reasonable range (< 128px from spawn).
     * <p><b>Bug behavior:</b> Block drifts far beyond intended oscillation bounds.
     */
    @Test
    public void testBigBlockVerticalOscillationAmplitude() {
        System.out.println("=== Bug #9: BigBlock Vertical Oscillation Amplitude ===");

        // Search for a BigBlock (0xD4) in CNZ1
        ObjectInstance bigBlock = walkAndSearch(OBJ_BIG_BLOCK, 0, 8192, 800);
        if (bigBlock == null) {
            bigBlock = walkAndSearch(OBJ_BIG_BLOCK, 0, 8192, 1200);
        }
        if (bigBlock == null) {
            bigBlock = walkAndSearch(OBJ_BIG_BLOCK, 0, 8192, 1600);
        }

        Assumptions.assumeTrue(bigBlock != null, "No BigBlock (0xD4) found in CNZ1; skipping test");

        int spawnX = bigBlock.getSpawn().x();
        int spawnY = bigBlock.getSpawn().y();
        System.out.println("Found BigBlock at: (" + spawnX + ", " + spawnY + ")");

        // Position Sonic nearby so the object stays active
        teleportAndRefresh(spawnX, spawnY - 64);

        // Record initial Y (which may differ from spawn Y due to initial offset logic)
        int initialY = bigBlock.getY();
        System.out.println("BigBlock initial Y (after init offset): " + initialY);

        // Step 300 frames, tracking the block's Y position
        int maxExcursion = 0;
        for (int frame = 0; frame < 300; frame++) {
            fixture.stepIdleFrames(1);

            int currentY = bigBlock.getY();
            int excursion = Math.abs(currentY - initialY);
            if (excursion > maxExcursion) {
                maxExcursion = excursion;
            }

            if (frame < 20 || frame % 50 == 0) {
                System.out.printf("Frame %3d: BigBlock Y=%d, excursion=%d%n",
                        frame, currentY, excursion);
            }
        }

        System.out.println("Max excursion from initial Y: " + maxExcursion + " pixels");

        assertTrue(maxExcursion < 128, "BigBlock Y excursion should be < 128 pixels (reasonable oscillation), " +
                        "but was: " + maxExcursion + " pixels. " +
                        "This suggests velocityShifted = yVel << 8 is over-scaling movement.");
    }

    // ========================================================================
    // Bug #11: CNZ1 wall sticking on lifts
    // ========================================================================

    /**
     * Bug #11: Sonic gets stuck on wall geometry when near lifts at (3311, 1305).
     *
     * <p><b>Expected:</b> Sonic can walk freely near the lift area.
     * <p><b>Bug behavior:</b> Sonic's X position does not change when holding LEFT;
     * he is stuck against an invisible wall near the lift geometry.
     */
    @Test
    public void testLiftNoWallSticking() {
        System.out.println("=== Bug #11: Wall Sticking Near Lifts ===");

        // Teleport to the reported bug location
        teleportAndRefresh(3311, 1305);

        // Let Sonic settle onto the ground
        fixture.stepIdleFrames(30);
        logState("After settling");

        // Record X position
        int xAfterSettle = sprite.getX();
        System.out.println("X after settling: " + xAfterSettle);

        // Hold LEFT for 30 frames
        for (int frame = 0; frame < 30; frame++) {
            fixture.stepFrame(false, false, true, false, false);
            if (frame % 10 == 0) {
                logState("Left frame " + frame);
            }
        }

        int xAfterWalk = sprite.getX();
        int xDelta = xAfterSettle - xAfterWalk; // positive if moved left (X decreased)
        System.out.println("X after walking left: " + xAfterWalk + ", delta: " + xDelta);

        assertTrue(xDelta >= 10, "Sonic should have moved at least 10 pixels to the left (not stuck). " +
                        "X delta was: " + xDelta + " (settle=" + xAfterSettle + ", final=" + xAfterWalk + ")");
    }

    // ========================================================================
    // Bug #12: Infinite bumper bounce
    // ========================================================================

    /**
     * Bug #12: Sonic gets trapped bouncing between bumpers near y=1097 indefinitely.
     *
     * <p>Starting from (4198, 1705), Sonic falls and becomes trapped in a bumper
     * loop around y=1080-1110, bouncing back and forth without escaping.
     *
     * <p><b>Expected:</b> Sonic escapes the bumper area within a reasonable time.
     * <p><b>Bug behavior:</b> Sonic's Y stays between 1080-1110 for > 100 consecutive
     * frames, indicating an infinite bounce trap.
     */
    @Test
    public void testNoBumperInfiniteLoop() {
        System.out.println("=== Bug #12: Infinite Bumper Bounce ===");

        // Teleport to bug location
        teleportAndRefresh(4198, 1705);
        logState("Initial position");

        // Hold DOWN for 10 frames (charge on spring)
        for (int frame = 0; frame < 10; frame++) {
            fixture.stepFrame(false, true, false, false, false);
        }
        logState("After charging");

        // Release and step 500 idle frames, tracking Y position
        int consecutiveTrappedFrames = 0;
        int maxConsecutiveTrapped = 0;
        int trappedYMin = 1080;
        int trappedYMax = 1110;

        for (int frame = 0; frame < 500; frame++) {
            fixture.stepIdleFrames(1);

            int currentY = sprite.getY();

            if (currentY >= trappedYMin && currentY <= trappedYMax) {
                consecutiveTrappedFrames++;
                if (consecutiveTrappedFrames > maxConsecutiveTrapped) {
                    maxConsecutiveTrapped = consecutiveTrappedFrames;
                }
            } else {
                consecutiveTrappedFrames = 0;
            }

            if (frame < 20 || frame % 50 == 0 || consecutiveTrappedFrames == 1) {
                System.out.printf("Frame %3d: Y=%d, consecutiveTrapped=%d%n",
                        frame, currentY, consecutiveTrappedFrames);
            }

            // Early exit if clearly trapped
            if (consecutiveTrappedFrames >= 100) {
                System.out.println(">>> Trapped for 100+ consecutive frames at frame " + frame);
                break;
            }
        }

        logState("Final");
        System.out.println("Max consecutive trapped frames (Y in " + trappedYMin + "-" + trappedYMax + "): "
                + maxConsecutiveTrapped);

        assertTrue(maxConsecutiveTrapped < 100, "Sonic should not be trapped in bumper loop for > 100 consecutive frames. " +
                        "Max consecutive frames in Y range [" + trappedYMin + "," + trappedYMax + "] was: "
                        + maxConsecutiveTrapped);
    }

    // ========================================================================
    // Bug #13: BonusBlock collision / sin-cos swap
    // ========================================================================

    /**
     * Bug #13: BonusBlock bounce direction has sin/cos swapped vs BumperObjectInstance.
     *
     * <p>BonusBlockObjectInstance (0xD8) uses:
     * <pre>
     * int xVel = (int) (-Math.cos(angle) * BOUNCE_VELOCITY);
     * int yVel = (int) (-Math.sin(angle) * BOUNCE_VELOCITY);
     * </pre>
     * While BumperObjectInstance (0x44) uses:
     * <pre>
     * int xVel = (int) (-StrictMath.sin(angle) * BOUNCE_VELOCITY);
     * int yVel = (int) (-StrictMath.cos(angle) * BOUNCE_VELOCITY);
     * </pre>
     * Both should use the same CalcAngle convention from the ROM. One of them is wrong.
     *
     * <p>This test approaches a BonusBlock from the LEFT (horizontal) and verifies the
     * bounce pushes Sonic primarily LEFT (horizontally away from the block).
     *
     * <p><b>Expected:</b> XSpeed < 0 (pushed left) and |XSpeed| > |YSpeed| for
     * a horizontal approach.
     * <p><b>Bug behavior:</b> If sin/cos are swapped, the bounce is mostly vertical
     * instead of horizontal.
     */
    @Test
    public void testBonusBlockBounceDirection() {
        System.out.println("=== Bug #13: BonusBlock Bounce Direction ===");

        // Search for a BonusBlock (0xD8) in CNZ1
        ObjectInstance bonusBlock = walkAndSearch(OBJ_BONUS_BLOCK, 0, 8192, 800);
        if (bonusBlock == null) {
            bonusBlock = walkAndSearch(OBJ_BONUS_BLOCK, 0, 8192, 1200);
        }
        if (bonusBlock == null) {
            bonusBlock = walkAndSearch(OBJ_BONUS_BLOCK, 0, 8192, 1600);
        }

        Assumptions.assumeTrue(bonusBlock != null, "No BonusBlock (0xD8) found in CNZ1; skipping test");

        int blockX = bonusBlock.getSpawn().x();
        int blockY = bonusBlock.getSpawn().y();
        int subtype = bonusBlock.getSpawn().subtype() & 0xFF;
        int baseAnimFrame = (subtype >> 6) & 0x03;
        System.out.println("Found BonusBlock at: (" + blockX + ", " + blockY +
                "), subtype=0x" + Integer.toHexString(subtype) +
                ", baseAnimFrame=" + baseAnimFrame);

        // ROM has 3 bounce behaviors based on orientation:
        //   baseAnimFrame 0/3: Y-only bounce (horizontal block pushes up/down)
        //   baseAnimFrame 1:   Velocity reflection (diagonal block)
        //   baseAnimFrame 2:   X-only bounce (narrow block pushes left/right)
        // Test the appropriate axis based on the found block's orientation.

        if (baseAnimFrame == 0 || baseAnimFrame == 3) {
            // Y-bounce block: approach from above, expect upward bounce
            sprite.setCentreX((short) blockX);
            sprite.setCentreY((short) (blockY - 20));
            sprite.setAir(true);
            sprite.setXSpeed((short) 0);
            sprite.setGSpeed((short) 0);
            sprite.setYSpeed((short) 0x400);  // Falling downward toward block

            fixture.camera().updatePosition(true);
            logState("Before approach (Y-bounce block, approaching from above)");

            boolean bounced = false;
            for (int i = 0; i < 60; i++) {
                fixture.stepFrame(false, false, false, false, false);
                if (sprite.getYSpeed() < 0) {
                    bounced = true;
                    logState("Bounced at frame " + (i + 1));
                    break;
                }
            }

            assertTrue(bounced, "Sonic should have bounced off Y-bounce BonusBlock (YSpeed should become negative)");
            assertTrue(sprite.getYSpeed() <= -BOUNCE_VELOCITY_THRESHOLD, "YSpeed should be strongly negative (pushed up), was: " + sprite.getYSpeed());

        } else if (baseAnimFrame == 2) {
            // X-bounce block: approach from the left, expect leftward bounce
            sprite.setCentreX((short) (blockX - 20));
            sprite.setCentreY((short) blockY);
            sprite.setAir(true);
            sprite.setXSpeed((short) 0x400);
            sprite.setGSpeed((short) 0x400);
            sprite.setYSpeed((short) 0);

            fixture.camera().updatePosition(true);
            logState("Before approach (X-bounce block, approaching from left)");

            boolean bounced = false;
            for (int i = 0; i < 60; i++) {
                fixture.stepFrame(false, false, false, false, false);
                if (sprite.getXSpeed() < 0) {
                    bounced = true;
                    logState("Bounced at frame " + (i + 1));
                    break;
                }
            }

            assertTrue(bounced, "Sonic should have bounced off X-bounce BonusBlock (XSpeed should become negative)");
            assertTrue(sprite.getXSpeed() <= -BOUNCE_VELOCITY_THRESHOLD, "XSpeed should be strongly negative (pushed left), was: " + sprite.getXSpeed());

        } else {
            // baseAnimFrame 1: velocity reflection block -- approach from left
            // The reflection should change velocity direction based on surface angle
            sprite.setCentreX((short) (blockX - 20));
            sprite.setCentreY((short) blockY);
            sprite.setAir(true);
            sprite.setXSpeed((short) 0x400);
            sprite.setGSpeed((short) 0x400);
            sprite.setYSpeed((short) 0x200);

            fixture.camera().updatePosition(true);
            logState("Before approach (velocity-reflection block)");

            short origXSpeed = sprite.getXSpeed();
            short origYSpeed = sprite.getYSpeed();
            boolean bounced = false;
            for (int i = 0; i < 60; i++) {
                fixture.stepFrame(false, false, false, false, false);
                // Velocity reflection changes both components
                if (sprite.getXSpeed() != origXSpeed || sprite.getYSpeed() != origYSpeed) {
                    if (Math.abs(sprite.getXSpeed()) > 0x200 || Math.abs(sprite.getYSpeed()) > 0x200) {
                        bounced = true;
                        logState("Bounced at frame " + (i + 1));
                        break;
                    }
                }
            }

            assertTrue(bounced, "Sonic should have bounced off velocity-reflection BonusBlock " +
                    "(velocity should change significantly)");
        }

        System.out.println("Post-bounce XSpeed=" + sprite.getXSpeed() + ", YSpeed=" + sprite.getYSpeed());
    }

    // ========================================================================
    // Bug #14: PointPokey death
    // ========================================================================

    /**
     * Bug #14: PointPokey capture kills Sonic because objectControlled does not
     * disable damage collision.
     *
     * <p>When Sonic enters a PointPokey cage (0xD6), the cage sets
     * {@code player.setObjectControlled(true)} which should lock all physics. However,
     * the touch response system still processes damage collision while captured,
     * causing Sonic to lose rings or die.
     *
     * <p><b>Expected:</b> Sonic remains alive with full ring count throughout the
     * entire capture duration (120 frames).
     * <p><b>Bug behavior:</b> Sonic takes damage during capture, losing rings or dying.
     */
    @Test
    public void testPointPokeyCaptureDoesNotKillSonic() {
        System.out.println("=== Bug #14: PointPokey Capture Death ===");

        // Give Sonic rings so damage is detectable
        sprite.setRingCount(10);

        // Search for a PointPokey (0xD6) in CNZ1
        ObjectInstance pointPokey = walkAndSearch(OBJ_POINT_POKEY, 0, 8192, 800);
        if (pointPokey == null) {
            pointPokey = walkAndSearch(OBJ_POINT_POKEY, 0, 8192, 1200);
        }
        if (pointPokey == null) {
            pointPokey = walkAndSearch(OBJ_POINT_POKEY, 0, 8192, 1600);
        }

        Assumptions.assumeTrue(pointPokey != null, "No PointPokey (0xD6) found in CNZ1; skipping test");

        int cageX = pointPokey.getSpawn().x();
        int cageY = pointPokey.getSpawn().y();
        System.out.println("Found PointPokey at: (" + cageX + ", " + cageY + ")");

        // Re-give Sonic 10 rings (may have been lost during walk-and-search)
        sprite.setRingCount(10);

        // Position Sonic above the cage, falling in
        sprite.setX((short) cageX);
        sprite.setY((short) (cageY - 32));
        sprite.setAir(true);
        sprite.setYSpeed((short) 0x200); // Falling downward
        sprite.setXSpeed((short) 0);
        sprite.setGSpeed((short) 0);

        Camera camera = fixture.camera();
        camera.updatePosition(true);

        logState("Before capture");

        // Step up to 120 frames (COUNTDOWN_FRAMES), checking each frame
        boolean tookDamage = false;
        int damageFrame = -1;

        for (int frame = 0; frame < 120; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            boolean dead = sprite.getDead();
            boolean hurt = sprite.isHurt();
            int rings = sprite.getRingCount();

            if (dead || hurt || rings < 10) {
                tookDamage = true;
                damageFrame = frame + 1;
                System.out.printf(">>> DAMAGE at frame %d: dead=%b, hurt=%b, rings=%d%n",
                        damageFrame, dead, hurt, rings);
                logState("Damage frame " + damageFrame);
                break;
            }

            if (frame < 10 || frame % 30 == 0) {
                logState("Frame " + (frame + 1));
            }
        }

        logState("Final");

        assertFalse(sprite.getDead(), "Sonic should NOT die during PointPokey capture. " +
                        "Damage detected at frame " + damageFrame + ". " +
                        "objectControlled should disable damage collision.");
        assertFalse(sprite.isHurt(), "Sonic should NOT be hurt during PointPokey capture. " +
                        "Damage detected at frame " + damageFrame + ". " +
                        "objectControlled should disable damage collision.");
        assertTrue(sprite.getRingCount() >= 10, "Sonic should not lose rings during capture. " +
                        "Ring loss detected at frame " + damageFrame + ".");
    }

    // ========================================================================
    // Bug #16: PointPokey eject survival
    // ========================================================================

    /**
     * Bug #16: Sonic must survive the PointPokey capture-eject cycle.
     *
     * <p>ROM behavior: SolidObject -> RideObject_SetRide (s2.asm:35761) clears
     * in_air when the player lands on the cage. ObjD6 capture code (s2.asm:58596-58608)
     * does not modify in_air. The player has air=false during the 120-frame capture.
     * Grounded camera scroll (6px/frame cap, bias=96) centers the camera on the cage
     * within ~6 frames. Eject sets air=true and yVel=+0x400 (downward).
     *
     * <p><b>Expected:</b> Camera centers on cage during capture; Sonic survives eject;
     * air=false during capture (ROM-accurate), air=true after eject.
     */
    @Test
    public void testPointPokeyCaptureAndEjectSurvival() {
        System.out.println("=== Bug #16: PointPokey Eject Survival ===");

        // Search for a simple PointPokey (subtype 0x00) first, fall back to any
        ObjectInstance pointPokey = findSimplePointPokey();
        if (pointPokey == null) {
            // Fall back to any PointPokey
            pointPokey = walkAndSearch(OBJ_POINT_POKEY, 0, 8192, 800);
            if (pointPokey == null) {
                pointPokey = walkAndSearch(OBJ_POINT_POKEY, 0, 8192, 1200);
            }
        }

        Assumptions.assumeTrue(pointPokey != null, "No PointPokey (0xD6) found in CNZ1; skipping test");

        int cageX = pointPokey.getSpawn().x();
        int cageY = pointPokey.getSpawn().y();
        int subtype = pointPokey.getSpawn().subtype() & 0xFF;
        boolean isSimpleMode = subtype == 0x00;
        System.out.println("Found PointPokey at: (" + cageX + ", " + cageY +
                "), subtype=0x" + Integer.toHexString(subtype) +
                (isSimpleMode ? " (simple)" : " (slot machine)"));

        // Give Sonic rings so he survives if something goes wrong
        sprite.setRingCount(10);

        // Position Sonic above the cage, falling fast (airborne)
        sprite.setCentreX((short) cageX);
        sprite.setCentreY((short) (cageY - 40));
        sprite.setAir(true);
        sprite.setXSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setYSpeed((short) 0x400); // Falling downward

        Camera camera = fixture.camera();
        camera.updatePosition(true);

        logState("Before capture");

        // Step frames until Sonic is captured (objectControlled becomes true)
        boolean captured = false;
        for (int i = 0; i < 30; i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (sprite.isObjectControlled()) {
                captured = true;
                System.out.println("Captured at frame " + (i + 1));
                break;
            }
        }

        Assumptions.assumeTrue(captured, "Sonic should have been captured by the PointPokey");

        // ROM-accurate: air=false during capture (SolidObject clears it at s2.asm:35761)
        assertFalse(sprite.getAir(), "Air flag should be false during capture (ROM: SolidObject clears in_air). ");

        logState("After capture");

        // Step through the capture period, tracking camera Y.
        // Grounded scroll at 6px/frame should center camera within ~6 frames.
        // ROM Obj_D6 release: subq.w #1, then bpl to continue (s2.asm:58739-58751);
        // the simple cage's $78 countdown therefore underflows on frame 121.
        int captureFrames = isSimpleMode ? 122 : 180;
        int cameraYAtCapture = camera.getY();
        System.out.println("Camera Y at capture: " + cameraYAtCapture);
        System.out.println("Cage Y: " + cageY);

        boolean ejected = false;
        for (int frame = 0; frame < captureFrames; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            // Check if ejected (objectControlled becomes false)
            if (!sprite.isObjectControlled() && frame > 5) {
                ejected = true;
                System.out.println("Ejected at frame " + (frame + 1));
                logState("At eject");
                break;
            }

            if (frame % 30 == 0) {
                System.out.printf("Frame %3d: CameraY=%d, SpriteY=%d, Air=%b, ObjCtrl=%b%n",
                        frame, camera.getY(), sprite.getY(), sprite.getAir(),
                        sprite.isObjectControlled());
            }
        }

        // For simple cages, eject should have happened
        if (isSimpleMode) {
            assertTrue(ejected, "Simple cage should eject after ~120 frames");

            // After eject: verify positive YSpeed (downward) and air=true
            short ySpeedAfterEject = sprite.getYSpeed();
            assertTrue(ySpeedAfterEject > 0, "After eject, YSpeed should be positive (downward +0x400), was: " + ySpeedAfterEject);
            assertTrue(sprite.getAir(), "After eject, air flag should be true (ROM: s2.asm:58745 bset in_air)");
        }

        // Step 60 more frames and verify Sonic doesn't die
        for (int frame = 0; frame < 60; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (sprite.getDead()) {
                logState("DEAD at frame " + (frame + 1) + " after eject");
                break;
            }
        }

        logState("Final");
        assertFalse(sprite.getDead(), "Sonic should NOT die after PointPokey eject. " +
                        "Camera should have centered during capture (grounded scroll 6px/frame).");
    }

    /**
     * Searches for a simple (subtype 0x00) PointPokey cage in CNZ1.
     * Returns null if none found.
     */
    private ObjectInstance findSimplePointPokey() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        for (int y : new int[]{800, 1200, 1600}) {
            for (int x = 0; x <= 8192; x += 256) {
                teleportAndRefresh(x, y);
                if (objectManager == null) continue;

                for (ObjectInstance obj : objectManager.getActiveObjects()) {
                    if (obj.getSpawn().objectId() == OBJ_POINT_POKEY
                            && !obj.isDestroyed()
                            && (obj.getSpawn().subtype() & 0xFF) == 0x00) {
                        return obj;
                    }
                }
            }
        }
        return null;
    }

    // ========================================================================
    // Bug #15: Slot machine rendered 8px too high
    // ========================================================================

    /**
     * Bug #15: The slot machine display is rendered 8 pixels too high.
     *
     * <p>In {@code PointPokeyObjectInstance.calculateSlotDisplayOffset()}, the Y offset
     * is calculated as:
     * <pre>
     * slotDisplayOffsetY = offset[1] - 12;
     * </pre>
     * It should be:
     * <pre>
     * slotDisplayOffsetY = offset[1] - 4;
     * </pre>
     * The extra -8 shifts the slot display up by one 8x8 pattern row.
     *
     * <p>This test uses reflection to read the {@code slotDisplayOffsetY} field from a
     * linked-mode PointPokey after the offset calculation has been triggered.
     *
     * <p><b>Expected:</b> The offset subtraction should use -4 (pattern center to top edge).
     * <p><b>Bug behavior:</b> The offset subtraction uses -12, shifting the display 8px up.
     *
     * <p>Note: If the PointPokey found is not linked mode (subtype != 0x01), the slot
     * display offset is irrelevant and the test is skipped. Similarly, if the pattern
     * scan fails to find slot tiles, the offset stays at DEFAULT_OFFSET_Y and the test
     * checks whether the calculation was even triggered.
     */
    @Test
    public void testPointPokeySlotDisplayOffsetY() throws Exception {
        System.out.println("=== Bug #15: Slot Display Y Offset ===");

        // Search for a linked-mode PointPokey (subtype 0x01) specifically
        ObjectInstance linkedPokey = null;
        for (int y : new int[]{800, 1200, 1600}) {
            for (int x = 0; x <= 8192; x += 256) {
                teleportAndRefresh(x, y);
                ObjectManager objectManager = GameServices.level().getObjectManager();
                if (objectManager == null) continue;

                for (ObjectInstance obj : objectManager.getActiveObjects()) {
                    if (obj.getSpawn().objectId() == OBJ_POINT_POKEY
                            && !obj.isDestroyed()
                            && (obj.getSpawn().subtype() & 0xFF) == 0x01) {
                        linkedPokey = obj;
                        break;
                    }
                }
                if (linkedPokey != null) break;
            }
            if (linkedPokey != null) break;
        }

        Assumptions.assumeTrue(linkedPokey != null, "No linked-mode PointPokey (0xD6, subtype 0x01) found in CNZ1; skipping test");

        int cageX = linkedPokey.getSpawn().x();
        int cageY = linkedPokey.getSpawn().y();
        System.out.println("Found linked PointPokey at: (" + cageX + ", " + cageY + ")");

        // Position Sonic nearby to ensure the object is active and rendering
        teleportAndRefresh(cageX, cageY - 64);

        // The slot display offset is calculated lazily on first render call.
        // In headless mode, render commands are not normally called, but the offset
        // calculation is also triggered by appendRenderCommands(). We call it to trigger
        // the offset calculation.
        try {
            linkedPokey.appendRenderCommands(new java.util.ArrayList<>());
        } catch (Exception e) {
            // May fail in headless mode due to renderer being null, but the offset
            // calculation happens before any actual rendering
            System.out.println("appendRenderCommands exception (expected in headless): " + e.getMessage());
        }

        // Read the slotDisplayOffsetY field via reflection. A reflection failure
        // (renamed/removed field) is a real test failure, not something to swallow.
        Field offsetYField = linkedPokey.getClass().getDeclaredField("slotDisplayOffsetY");
        offsetYField.setAccessible(true);
        int actualOffsetY = (int) offsetYField.get(linkedPokey);

        Field calculatedField = linkedPokey.getClass().getDeclaredField("slotDisplayOffsetCalculated");
        calculatedField.setAccessible(true);
        boolean wasCalculated = (boolean) calculatedField.get(linkedPokey);

        System.out.println("slotDisplayOffsetCalculated: " + wasCalculated);
        System.out.println("slotDisplayOffsetY: " + actualOffsetY);

        // The offset calculation must have been triggered for this linked-mode cage;
        // otherwise the bug under test cannot be verified.
        assertTrue(wasCalculated,
                "Linked-mode PointPokey slot display offset should have been calculated after appendRenderCommands()");

        // Bug #15: the Y offset must use -4 (pattern center -> pattern top edge), NOT -12.
        // The correct value is the raw pattern-scan offset minus 4. Recompute the raw
        // pattern offset the same way the object does, then assert the stored field
        // matches the documented (-4) formula. A -12 regression would be off by 8px.
        int[] rawOffset = GameServices.level().findPatternOffset(
                cageX, cageY,
                com.openggf.game.sonic2.slotmachine.CNZSlotMachineRenderer.SLOT_TILE_MIN,
                com.openggf.game.sonic2.slotmachine.CNZSlotMachineRenderer.SLOT_TILE_MAX,
                128);
        assertNotNull(rawOffset,
                "Slot display patterns must be found near the cage when the offset was calculated");

        int expectedOffsetY = rawOffset[1] - 4;
        assertEquals(expectedOffsetY, actualOffsetY,
                "slotDisplayOffsetY must use the -4 pattern-center-to-top-edge correction, not -12");
    }
}


