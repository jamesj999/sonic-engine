package com.openggf.level.rings;

import com.openggf.game.session.SessionManager;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code LostRingObjectInstance} (Obj37 spilled rings) survive a rewind
 * seek through the generic {@code RewindRecreatable} path: the restore path
 * clears {@code dynamicObjects} and recreates each captured ring from its
 * captured dynamic entry.
 */
class TestLostRingRewindGenericRestore {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    /** Empty registry — spilled rings are dynamic objects, not placement-managed. */
    private static final class EmptyRegistry implements ObjectRegistry {
        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return null;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {}

        @Override
        public String getPrimaryName(int objectId) {
            return "Obj37";
        }

        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_2;
        }
    }

    private static ObjectManager makeManager() {
        return new ObjectManager(List.of(), new EmptyRegistry(), 0, null, null);
    }

    private static List<int[]> positionsOf(List<LostRingObjectInstance> rings) {
        List<int[]> positions = new ArrayList<>();
        for (LostRingObjectInstance ring : rings) {
            positions.add(new int[] {
                    ring.getXSubpixelForTest(), ring.getYSubpixelForTest(),
                    ring.getXVelForTest(), ring.getYVelForTest(),
                    ring.getPhaseOffset()
            });
        }
        return positions;
    }

    @Test
    void ringsReappearOnSeekAcrossSpill() {
        ObjectManager manager = makeManager();
        manager.reset(0);

        SpillAnimationState spillAnimation = new SpillAnimationState();
        spillAnimation.reset();

        // Spill several rings into the dynamic-object exec loop at allocated slots.
        int previousSlot = 31;
        for (int i = 0; i < 6; i++) {
            int slot = manager.allocateSlotAfter(previousSlot);
            assertTrue(slot >= 0, "slot should allocate");
            int phase = 127 - slot;
            LostRingObjectInstance ring = LostRingObjectInstance.spawn(
                    0x100 + i * 8, 0x200 + i * 4,
                    0x180 - i * 0x10, -0x200 + i * 0x20,
                    phase, 0xFF, spillAnimation);
            manager.spawnLostRingObjectAtSlot(ring, slot);
            previousSlot = slot;
        }

        List<int[]> before =
                positionsOf(manager.activeObjectsOfType(LostRingObjectInstance.class));
        assertEquals(6, before.size());

        // Capture, then mutate the live rings so a survivor would be detectable.
        RewindSnapshottable<ObjectManagerSnapshot> snap = manager.rewindSnapshottable();
        ObjectManagerSnapshot snapshot = snap.capture();
        for (LostRingObjectInstance ring : manager.activeObjectsOfType(LostRingObjectInstance.class)) {
            ring.stepPhysicsForTest(0x18, false);
            ring.stepPhysicsForTest(0x18, false);
        }
        // sanity: live state diverged from the captured state
        assertFalse(before.equals(
                positionsOf(manager.activeObjectsOfType(LostRingObjectInstance.class))));

        // Restore must clear dynamicObjects and recreate each ring via generic recreate.
        snap.restore(snapshot);

        List<int[]> after =
                positionsOf(manager.activeObjectsOfType(LostRingObjectInstance.class));
        assertEquals(before.size(), after.size(),
                "rings must be recreated by the codec, not left empty");
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i)[0], after.get(i)[0], "xSubpixel[" + i + "]");
            assertEquals(before.get(i)[1], after.get(i)[1], "ySubpixel[" + i + "]");
            assertEquals(before.get(i)[2], after.get(i)[2], "xVel[" + i + "]");
            assertEquals(before.get(i)[3], after.get(i)[3], "yVel[" + i + "]");
            assertEquals(before.get(i)[4], after.get(i)[4], "phaseOffset[" + i + "]");
        }
    }

    @Test
    void recreatedRingsAreDistinctInstances() {
        ObjectManager manager = makeManager();
        manager.reset(0);

        SpillAnimationState spillAnimation = new SpillAnimationState();
        spillAnimation.reset();

        int slot = manager.allocateSlotAfter(31);
        LostRingObjectInstance original = LostRingObjectInstance.spawn(
                0x140, 0x240, 0x100, -0x180, 127 - slot, 0xFF, spillAnimation);
        manager.spawnLostRingObjectAtSlot(original, slot);

        RewindSnapshottable<ObjectManagerSnapshot> snap = manager.rewindSnapshottable();
        ObjectManagerSnapshot snapshot = snap.capture();
        snap.restore(snapshot);

        List<LostRingObjectInstance> restored =
                manager.activeObjectsOfType(LostRingObjectInstance.class);
        assertEquals(1, restored.size());
        AbstractObjectInstance recreated = restored.get(0);
        assertFalse(recreated == original, "restore recreates a fresh ring instance");
        assertEquals(original.getXSubpixelForTest(), restored.get(0).getXSubpixelForTest());
        assertEquals(original.getYSubpixelForTest(), restored.get(0).getYSubpixelForTest());
        assertEquals(slot, restored.get(0).getSlotIndex());
    }
}
