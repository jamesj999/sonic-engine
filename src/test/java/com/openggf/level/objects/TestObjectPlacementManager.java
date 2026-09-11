package com.openggf.level.objects;

import com.openggf.game.sonic2.objects.S2ObjectWindowing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestObjectPlacementManager {
    @Test
    void twoAxisCursorKeepsOrderedLoadsOwnedUntilConstructionOutcome() {
        ObjectSpawn remembered = new ObjectSpawn(0x0100, 0x0100, 0x01, 0, 0, false, 0);
        ObjectSpawn dormant = new ObjectSpawn(0x0180, 0x0100, 0x02, 0, 0, false, 0);
        ObjectSpawn firstLater = new ObjectSpawn(0x0280, 0x0900, 0x03, 0, 0, false, 0);
        ObjectSpawn laterEligible = new ObjectSpawn(0x02C0, 0x0100, 0x04, 0, 0, false, 0);
        ObjectPlacementController manager = new ObjectPlacementController(
                List.of(remembered, dormant, firstLater, laterEligible), () -> 320);
        manager.setTwoAxisCursorPlacement(true);

        manager.reset(0);
        manager.pendingCursorLoadSpawns();
        manager.completePendingCursorLoad(remembered);
        manager.completePendingCursorLoad(dormant);
        manager.finishPendingCursorLoadBatch();
        manager.markRemembered(remembered);
        manager.markDormant(dormant);

        // Exercise the controller-level post-camera rejection seam without committing
        // the primary cursor. Production two-axis Y eligibility/order is covered in
        // TestObjectManagerVerticalPlacement; this seam proves rejected observations
        // cannot leave stale order entries before the next primary cursor crossing.
        manager.extendForPostCamera(0x80, (spawn, counter) -> false);
        var beforeCrossing = manager.captureRewindState(0, Integer.MIN_VALUE);
        ObjectPlacementController restoredBeforeCrossing = new ObjectPlacementController(
                List.of(remembered, dormant, firstLater, laterEligible), () -> 320);
        restoredBeforeCrossing.setTwoAxisCursorPlacement(true);
        restoredBeforeCrossing.restoreRewindState(beforeCrossing);

        manager.update(0x80);
        List<ObjectSpawn> pending = manager.pendingCursorLoadSpawns();
        assertEquals(List.of(firstLater, laterEligible), pending);
        restoredBeforeCrossing.update(0x80);
        assertEquals(pending, restoredBeforeCrossing.pendingCursorLoadSpawns(),
                "restore immediately before the cursor crossing must reproduce pending ROM order");

        // Draining is only a read of the cursor-owned work. ObjectManager has not yet
        // reported whether either entry was constructed, vertically deferred, or
        // blocked by FindFreeObj, so rewind must retain the exact ordered ownership.
        var snapshot = manager.captureRewindState(0, Integer.MIN_VALUE);
        assertArrayEquals(new int[] {2, 3}, snapshot.pendingCursorLoadOrder());

        ObjectPlacementController restored = new ObjectPlacementController(
                List.of(remembered, dormant, firstLater, laterEligible), () -> 320);
        restored.setTwoAxisCursorPlacement(true);
        restored.restoreRewindState(snapshot);
        assertEquals(List.of(firstLater, laterEligible),
                restored.pendingCursorLoadSpawns(),
                "restore immediately before construction must preserve pending ROM order");
    }

    @Test
    public void testWindowingAndRememberedObjects() {
        ObjectSpawn spawnA = new ObjectSpawn(0, 0, 0x01, 0, 0, false, 0);
        // ROM parity: only respawn-tracked layout entries own a respawn-table
        // slot (obRespawnNo != 0) and can persist destruction. Use a
        // respawn-tracked spawn here (id-byte remember bit / yWord bit 15).
        ObjectSpawn spawnB = new ObjectSpawn(800, 0, 0x02, 0, 0, true, 0x8000);

        ObjectPlacementController manager = new ObjectPlacementController(List.of(spawnA, spawnB), () -> 320);
        manager.reset(0);

        assertTrue(manager.getActiveSpawns().contains(spawnA));
        assertFalse(manager.getActiveSpawns().contains(spawnB));

        manager.update(500);
        assertTrue(manager.getActiveSpawns().contains(spawnB));

        manager.markRemembered(spawnB);
        // Spawn stays in active window but is marked as remembered
        assertTrue(manager.getActiveSpawns().contains(spawnB));
        assertTrue(manager.isRemembered(spawnB));

        manager.update(2000);
        // Spawn scrolled out of window
        assertFalse(manager.getActiveSpawns().contains(spawnB));
        // But still remembered
        assertTrue(manager.isRemembered(spawnB));

        manager.update(0);
        // Spawn not in window at camera X=0
        assertFalse(manager.getActiveSpawns().contains(spawnB));
    }

    @Test
    public void testNonRespawnTrackedSpawnIsNotRemembered() {
        // ROM parity: a layout entry without the remember bit gets obRespawnNo == 0,
        // so RememberState simply DeleteObjects it without touching any respawn-table
        // bit (docs/s1disasm/_incObj/sub RememberState.asm:16-21). The next ObjPosLoad
        // cursor crossing re-creates a fresh copy, so markRemembered must be a no-op
        // for non-respawn-tracked spawns (e.g. MZ3's below-screen SmashBlock cluster).
        ObjectSpawn spawn = new ObjectSpawn(500, 0, 0x51, 0, 0, false, 0);
        ObjectPlacementController manager = new ObjectPlacementController(List.of(spawn), () -> 320);
        manager.reset(0);
        assertTrue(manager.getActiveSpawns().contains(spawn));

        manager.markRemembered(spawn);
        assertFalse(manager.isRemembered(spawn),
                "Non-respawn-tracked spawns cannot persist destruction (ROM obRespawnNo == 0)");

        // Scroll away and back: the spawn re-loads fresh because it was never remembered.
        manager.update(2000);
        assertFalse(manager.getActiveSpawns().contains(spawn));
        manager.update(0);
        assertTrue(manager.getActiveSpawns().contains(spawn),
                "Non-respawn-tracked spawn re-creates fresh on camera return");
    }

    @Test
    public void testS3kTwoAxisPlacementTracksEveryLayoutEntry() {
        ObjectSpawn monitor = new ObjectSpawn(0x2CE8, 0x0670, 0x01, 0x07, 0,
                false, 0x0670);
        ObjectPlacementController manager = new ObjectPlacementController(
                List.of(monitor), () -> 320);
        manager.setTwoAxisCursorPlacement(true);
        manager.reset(0x2C00);

        manager.markRemembered(monitor);

        assertTrue(manager.isRemembered(monitor),
                "S3K Load_Sprites assigns respawn_addr to every six-byte layout entry, "
                        + "independent of the Y word's high bit");
    }

    @Test
    public void testRememberedObjectDoesNotRespawnOnCameraReturn() {
        // Simulates: break a block, scroll away, scroll back - block should NOT reappear
        ObjectSpawn spawn = new ObjectSpawn(500, 0, 0x32, 0, 0, true, 0x8000);
        ObjectPlacementController manager = new ObjectPlacementController(List.of(spawn), () -> 320);

        // Camera starts at 0 - spawn at x=500 is within load-ahead range (0x280=640)
        manager.reset(0);
        assertTrue(manager.getActiveSpawns().contains(spawn), "Spawn should be in active window initially");

        // Break the block: mark as remembered (single-arg - used by Placement directly)
        manager.markRemembered(spawn);
        assertTrue(manager.getActiveSpawns().contains(spawn), "Spawn should still be in active (single-arg doesn't remove)");
        assertTrue(manager.isRemembered(spawn), "Spawn should be remembered");

        // Scroll far right - spawn goes out of window
        manager.update(2000);
        assertFalse(manager.getActiveSpawns().contains(spawn), "Spawn should have left the window");
        assertTrue(manager.isRemembered(spawn), "Spawn should still be remembered");

        // Scroll back to original position - spawn should NOT reappear
        manager.update(0);
        assertFalse(manager.getActiveSpawns().contains(spawn), "Remembered spawn should NOT reappear on camera return");
        assertTrue(manager.isRemembered(spawn), "Spawn should still be remembered after return");

        // Try again: scroll to have spawn in range
        manager.update(300);
        assertFalse(manager.getActiveSpawns().contains(spawn), "Remembered spawn should NOT reappear when scrolling into range");
    }

    @Test
    public void testRememberedObjectStaysInActiveWhenCameraDoesNotMove() {
        // BUG SCENARIO: break a block, camera stays at same X (player falls vertically)
        // The spawn stays in the active window the whole time. After the object is
        // destroyed, syncActiveSpawns will see the spawn still in activeSpawns,
        // the old instance gone from activeObjects, and will create a NEW instance.
        // This is the "broken blocks reappear" bug.
        ObjectSpawn spawn = new ObjectSpawn(500, 0, 0x32, 0, 0, true, 0x8000);
        ObjectPlacementController manager = new ObjectPlacementController(List.of(spawn), () -> 320);

        manager.reset(0);
        assertTrue(manager.getActiveSpawns().contains(spawn), "Spawn should be in active window");

        // Mark as remembered using single-arg (as if the object broke)
        // IMPORTANT: The single-arg version does NOT remove from active
        manager.markRemembered(spawn);
        assertTrue(manager.isRemembered(spawn), "Spawn is remembered");

        // Camera doesn't move (player falls vertically)
        manager.update(0);

        // The spawn is still in the active window AND remembered.
        // syncActiveSpawns should check isRemembered before creating a new instance.
        // But since the spawn is still in active, it will be iterated.
        assertTrue(manager.getActiveSpawns().contains(spawn), "Spawn is still in active (camera didn't move)");
        assertTrue(manager.isRemembered(spawn), "Spawn is still remembered");
    }

    @Test
    public void testMarkRememberedWithDifferentReferenceViaEqualsFallback() {
        // Tests the equals-based fallback in getSpawnIndex:
        // When markRemembered is called with a spawn reference that is structurally
        // equal but NOT identity-equal to the canonical reference in the spawns list,
        // the fallback should still find the correct index and set the remembered bit.
        ObjectSpawn canonical = new ObjectSpawn(500, 0, 0x32, 0, 0, true, 0x8000);
        ObjectPlacementController manager = new ObjectPlacementController(List.of(canonical), () -> 320);

        manager.reset(0);
        assertTrue(manager.getActiveSpawns().contains(canonical), "Spawn should be in active window");

        // Create a structurally equal but identity-different spawn
        ObjectSpawn differentRef = new ObjectSpawn(500, 0, 0x32, 0, 0, true, 0x8000);
        assertTrue(canonical.equals(differentRef), "Spawns should be equal by value");
        assertFalse(canonical == differentRef, "Spawns should NOT be identity-equal");

        // Mark remembered using the different reference - should work via fallback
        manager.markRemembered(differentRef);
        assertTrue(manager.isRemembered(canonical), "Spawn should be remembered even with different reference");
        assertTrue(manager.isRemembered(differentRef), "Spawn should also be remembered when queried with different ref");

        // Scroll away and back - spawn should NOT reappear
        manager.update(2000);
        assertFalse(manager.getActiveSpawns().contains(canonical), "Spawn should have left the window");

        manager.update(0);
        assertFalse(manager.getActiveSpawns().contains(canonical), "Remembered spawn should NOT reappear on camera return");
    }

    @Test
    public void testRemoveFromActiveLatchesPermanentlyUntilLevelReset() {
        // ROM parity: bit 7 of Object_respawn_table (sonic3k.asm Touch_EnemyNormal
        // line 20945; S2/S1 RememberState in sub RememberState.asm) is set on spawn
        // and cleared only when a still-alive object self-destructs via
        // Sprite_OnScreen_Test family (sonic3k.asm:37271-37388, bclr #7,(a2)).
        // After a player kill the badnik becomes Obj_Explosion and never walks
        // that path, so destroyedInWindow stays latched permanently for the rest
        // of the level (until level-init wipes the table at sonic3k.asm loc_1B784).
        // See AIZ trace F2202 fix.
        ObjectSpawn spawn = new ObjectSpawn(500, 0, 0x1A, 0, 0, false, 0);
        ObjectPlacementController manager = new ObjectPlacementController(List.of(spawn), () -> 320);
        manager.enablePermanentDestroyLatch();

        manager.reset(0);
        assertTrue(manager.getActiveSpawns().contains(spawn));

        // Object destroyed while still in window: should not respawn immediately.
        manager.removeFromActive(spawn);
        assertFalse(manager.getActiveSpawns().contains(spawn));
        manager.update(0);
        assertFalse(manager.getActiveSpawns().contains(spawn));

        // Move forward far enough that spawn leaves the active window.
        // ROM parity: cursor advancement past a destroyed badnik does NOT
        // clear bit 7 of Object_respawn_table.
        manager.update(1400);
        assertFalse(manager.getActiveSpawns().contains(spawn));

        // Returning camera into range must NOT respawn the destroyed badnik:
        // ROM keeps bit 7 of Object_respawn_table set permanently after a kill.
        manager.update(200);
        assertFalse(manager.getActiveSpawns().contains(spawn),
                "ROM keeps destroyed badniks permanently absent (Object_respawn_table bit 7 stays set)");

        // Wholesale reset (level reload) clears the latch.
        manager.reset(0);
        assertTrue(manager.getActiveSpawns().contains(spawn),
                "Level reset (sonic3k.asm loc_1B784 clr.l (a3)+) wipes the respawn table");
    }

    @Test
    public void testDormantNonCounterSpawnSurvivesSmallBackwardCameraMotion() {
        ObjectSpawn spawn = new ObjectSpawn(0x17C0, 0x0860, 0x41, 0, 0, false, 0x0860);
        ObjectPlacementController manager = new ObjectPlacementController(List.of(spawn), () -> 320);

        manager.reset(0x1736);
        assertTrue(manager.getActiveSpawns().contains(spawn));

        manager.markDormant(spawn);
        manager.update(0x16F0);

        assertTrue(manager.getActiveSpawns().contains(spawn),
                "Spawn remains inside the front/back cursor window");
        assertTrue(manager.isDormant(spawn),
                "Backward camera movement inside the same cursor window must not re-enable it");

        manager.update(0x1540);
        assertFalse(manager.getActiveSpawns().contains(spawn),
                "Retreating the front cursor past the spawn removes it from the active window");
        assertFalse(manager.isDormant(spawn),
                "Once the cursor has passed the entry, a later scan can load it again");
    }

    @Test
    public void testBackwardCounteredNonTrackedSpawnCreatesInlineDuringUpdateAndLoad() {
        ObjectSpawn spawn = new ObjectSpawn(0x0AD0, 0, 0x32, 0, 0, false, 0);
        ObjectPlacementController manager = new ObjectPlacementController(List.of(spawn), () -> 320);
        manager.enableCounterBasedRespawn();
        manager.reset(0x0B82);

        List<ObjectSpawn> created = new ArrayList<>();
        manager.updateAndLoad(0x0B7E, (newSpawn, counterValue) -> {
            created.add(newSpawn);
            return true;
        });

        assertTrue(manager.getActiveSpawns().contains(spawn),
                "Backward scan should add the non-tracked spawn to the active window");
        assertEquals(List.of(spawn), created,
                "Backward counter-based post-camera scan should inline-create non-tracked spawns");
    }

    @Test
    public void postCameraBackwardScanCreatesLeftGapSpawnsInRomOrder() {
        ObjectSpawn outside = new ObjectSpawn(0x04C0, 0, 0x06, 0, 0, false, 0);
        ObjectSpawn left = new ObjectSpawn(0x0520, 0, 0x65, 0, 0, false, 0);
        ObjectSpawn right = new ObjectSpawn(0x0568, 0, 0x9F, 0, 0, false, 0);
        ObjectSpawn inWindow = new ObjectSpawn(0x0600, 0, 0x47, 0, 0, false, 0);
        ObjectPlacementController manager =
                new ObjectPlacementController(List.of(outside, left, right, inWindow), () -> 320);
        manager.setWindowingStrategy(S2ObjectWindowing.INSTANCE);
        manager.reset(0x0600);

        List<ObjectSpawn> created = new ArrayList<>();
        manager.extendForPostCamera(0x05FF, (newSpawn, counterValue) -> {
            created.add(newSpawn);
            return true;
        });

        assertEquals(List.of(right, left), created,
                "S2 backward ObjPosLoad scans descending X across the newly exposed left gap");
        assertFalse(manager.getActiveSpawns().contains(outside),
                "Spawns at or left of the new left edge remain outside the load window");
        assertTrue(manager.getActiveSpawns().contains(left));
        assertTrue(manager.getActiveSpawns().contains(right));
        assertEquals(0x0600, manager.getLastCameraChunk(),
                "Post-camera gap creation must not advance the primary placement cursor");
    }
}
