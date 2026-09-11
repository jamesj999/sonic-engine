package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.game.sonic2.objects.BreakableBlockObjectInstance;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Headless integration test for Bug #4: breakable blocks reappear after going off-screen.
 *
 * <p>This is a <b>bug reproduction test</b> that is expected to <b>FAIL</b> until the
 * underlying persistence/respawn bug is fixed. When the block is destroyed, it calls
 * {@code objectManager.markRemembered(spawn)}, and the constructor checks
 * {@code objectManager.isRemembered(spawn)} to skip re-creation. Despite this, the block
 * currently respawns intact when the camera window re-enters the spawn area.
 *
 * <p>Level data is loaded once via {@link SharedLevel#load} in {@code @BeforeAll};
 * sprite, camera, and game state are reset per test via {@link HeadlessTestFixture}.
 *
 * <p>Test scenario:
 * <ol>
 *   <li>Load CPZ Act 1 (zone index 1, act 0)</li>
 *   <li>Run Sonic right through CPZ1 looking for a breakable block (objectId 0x32)</li>
 *   <li>Position Sonic near the block, roll into it at speed to break it</li>
 *   <li>Verify the block's spawn is marked as remembered</li>
 *   <li>Teleport Sonic far away (&gt;400px) so the block despawns</li>
 *   <li>Teleport Sonic back to the original position and reset the spawn window</li>
 *   <li>Verify no intact (non-destroyed) breakable block exists at the original position</li>
 * </ol>
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestBreakableBlockDespawn {
    // CPZ is zone index 1 in Sonic2ZoneRegistry (EHZ=0, CPZ=1, ARZ=2, CNZ=3, HTZ=4)
    private static final int ZONE_CPZ = 1;
    private static final int ACT_1 = 0;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_CPZ, ACT_1);
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

        // Reset the object manager's spawn window to the new camera position
        // so objects near our test position are spawned
        GameServices.level().getObjectManager().reset(fixture.camera().getX());
    }

    /**
     * Tests that a breakable block does NOT reappear intact after being destroyed
     * and the camera leaves and returns to the area.
     *
     * <p>Expected to <b>FAIL</b> while Bug #4 is open: the remembered state is not
     * surviving the despawn/respawn cycle, so the block comes back intact.
     */
    @Test
    public void testBrokenBlockDoesNotReappearOnCameraReturn() {
        ObjectManager objMgr = GameServices.level().getObjectManager();

        // Walk right through CPZ1 looking for a breakable block
        ObjectSpawn blockSpawn = null;
        int blockX = 0;

        // First, build some speed running right
        for (int frame = 0; frame < 120; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }

        logState("After 120 frames running right");

        // Now look for breakable blocks while continuing to run
        // Hold down+right to enter a roll (spin attack can break blocks)
        sprite.setRolling(true);

        for (int frame = 0; frame < 600; frame++) {
            fixture.stepFrame(false, true, false, true, false); // down+right to maintain roll

            // Check active objects for breakable blocks
            for (var obj : objMgr.getActiveObjects()) {
                if (obj.getSpawn().objectId() == 0x32 && !obj.isDestroyed()) {
                    // Found an intact breakable block - record it
                    blockSpawn = obj.getSpawn();
                    blockX = obj.getSpawn().x();
                    break;
                }
            }
            if (blockSpawn != null) break;
        }

        Assumptions.assumeTrue(blockSpawn != null, "No breakable block (0x32) found in CPZ1");

        System.out.printf("Found breakable block at X=%d, Y=%d%n", blockSpawn.x(), blockSpawn.y());

        // Position Sonic ABOVE the block in a rolling jump so SolidObject seats him
        // on top with status.standing set + rolling anim active. ROM Obj32_Main only
        // breaks the block when the player is STANDING on it AND rolling
        // (s2.asm:48889-48959). Side-roll-into-block no longer breaks (engine matches
        // ROM after iter-3 fix).
        // Block half-height is 16, Sonic standing y_radius is ~19, rolling y_radius=14.
        // Sonic's centre when standing on block top = block.y - block_halfH - sonic_yRadius.
        // Place Sonic ~40px above so he falls a clean rolling distance onto the block.
        sprite.setCentreX((short) blockX);
        sprite.setCentreY((short) (blockSpawn.y() - 60));
        sprite.setRolling(true);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0x100);
        sprite.setAir(true);
        fixture.camera().updatePosition(true);
        objMgr.reset(fixture.camera().getX());

        logState("Positioned above block, rolling+falling");

        // Step frames so the player lands rolling on top of the block, satisfying
        // ROM's standing+rolling break condition.
        boolean blockBroken = false;
        for (int frame = 0; frame < 120; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            // Keep rolling state asserted: setAir() resets rolling, so re-roll if
            // the engine cleared it (we want the player rolling at impact).
            if (!sprite.getRolling()) {
                sprite.setRolling(true);
            }
            if (objMgr.isRemembered(blockSpawn)) {
                blockBroken = true;
                System.out.printf("Block broken at frame %d, sprite Y=%d, blockY=%d%n",
                        frame, sprite.getY(), blockSpawn.y());
                break;
            }
        }

        Assumptions.assumeTrue(blockBroken, "Failed to break the block by landing rolling on top");

        System.out.printf("Block at X=%d broken and remembered%n", blockX);

        // Verify block is remembered
        assertTrue(objMgr.isRemembered(blockSpawn), "Block should be marked as remembered after breaking");

        logState("After block broken");

        // Move Sonic far away to trigger despawn (>400px from block X)
        // Use centre coordinates for correct alignment (CLAUDE.md)
        sprite.setCentreX((short) (blockX + 500));
        sprite.setCentreY((short) blockSpawn.y());
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        fixture.camera().updatePosition(true);

        logState("Teleported far away for despawn");

        // Step frames for despawn processing Ã¢â‚¬â€ use stepIdleFrames which calls
        // objMgr.update() (via Placement.update -> refreshWindow -> trySpawn),
        // preserving the remembered BitSet unlike reset() which clears it.
        for (int frame = 0; frame < 60; frame++) {
            fixture.stepIdleFrames(1);
        }

        logState("After despawn frames");

        // Verify block is still remembered even after despawn cycle
        assertTrue(objMgr.isRemembered(blockSpawn), "Block should still be remembered after despawn");

        // Move back to the original block position.
        // Do NOT call objMgr.reset() Ã¢â‚¬â€ that clears the remembered BitSet.
        // Instead, teleport and step frames so update() re-spawns via refreshWindow,
        // which checks remembered state and skips remembered spawns.
        sprite.setCentreX((short) blockX);
        sprite.setCentreY((short) blockSpawn.y());
        sprite.setAir(false);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        fixture.camera().updatePosition(true);

        logState("Returned to original block position");

        // Step frames for respawn processing
        for (int frame = 0; frame < 30; frame++) {
            fixture.stepIdleFrames(1);
        }

        logState("After respawn frames");

        // Verify the block's spawn is still remembered after the full cycle
        assertTrue(objMgr.isRemembered(blockSpawn), "Block spawn should still be remembered after return");

        // Check: no BreakableBlockObjectInstance (the parent block) should exist at
        // the original position. BreakableBlockFragmentInstance (flying debris from
        // the original break) may linger as dynamic objects Ã¢â‚¬â€ these are NOT respawned
        // blocks and should not trigger the assertion.
        boolean foundIntactBlock = false;
        for (var obj : objMgr.getActiveObjects()) {
            if (obj instanceof BreakableBlockObjectInstance
                    && obj.getSpawn().objectId() == 0x32
                    && obj.getSpawn().x() == blockX
                    && !obj.isDestroyed()) {
                foundIntactBlock = true;
                System.out.printf("BUG: Found intact block at X=%d, Y=%d after respawn cycle%n",
                    obj.getSpawn().x(), obj.getSpawn().y());
                break;
            }
        }

        assertFalse(foundIntactBlock, "Broken block should NOT reappear as intact when camera returns. " +
            "Block at X=" + blockX + " was remembered but respawned anyway.");
    }

    /**
     * Helper method to log sprite state for debugging.
     */
    private void logState(String label) {
        System.out.printf("%s: X=%d (0x%04X), Y=%d (0x%04X), GSpeed=%d, XSpeed=%d, YSpeed=%d, " +
                "Air=%b, Rolling=%b, Facing=%s%n",
            label,
            sprite.getX(), sprite.getX() & 0xFFFF,
            sprite.getY(), sprite.getY() & 0xFFFF,
            sprite.getGSpeed(),
            sprite.getXSpeed(),
            sprite.getYSpeed(),
            sprite.getAir(),
            sprite.getRolling(),
            sprite.getDirection());
    }
}


