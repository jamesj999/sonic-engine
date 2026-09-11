package com.openggf.level;

import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Owns lost-ring scattering and the deferred spawn queue behind it.
 *
 * <p>A hit taken inside an object's own tick cannot scatter its rings there:
 * the ROM's ring owner claims its object slots on the following pass. This
 * collaborator holds the queued spawns between those two passes, together with
 * the slots reserved on the object manager so the owner lands where the ROM
 * puts it.
 */
final class LevelLostRingSpawnCoordinator {
    private final LevelManager levelManager;
    private final List<PendingLostRingSpawn> pending = new ArrayList<>();

    LevelLostRingSpawnCoordinator(LevelManager levelManager) {
        this.levelManager = levelManager;
    }

    void spawnImmediately(AbstractPlayableSprite player, int frameCounter) {
        if (levelManager.ringManager == null || player == null) {
            return;
        }
        int count = player.getRingCount();
        if (count <= 0) {
            return;
        }
        levelManager.ringManager.spawnLostRings(player, count, frameCounter);
    }

    void queue(AbstractPlayableSprite player, int scheduledFrame, boolean deferOwnerRingClear) {
        if (player == null || levelManager.ringManager == null) {
            return;
        }
        int count = player.getRingCount();
        if (count <= 0) {
            return;
        }
        ObjectManager objectManager = levelManager.objectManager;
        int preallocatedFirstSlot = -1;
        if (objectManager != null && objectManager.preallocatesLostRingOwnerSlot()) {
            preallocatedFirstSlot = objectManager.allocateDynamicSlotAvoidingCurrentPassFrees();
        }
        int[] preallocatedSlots = preallocatedFirstSlot >= 0
                ? new int[] {preallocatedFirstSlot}
                : new int[0];
        boolean slotsFullyReserved = false;
        if (preallocatedFirstSlot >= 0 && objectManager != null
                && objectManager.lostRingRemainderAllocatesAfterOwnerSlot()) {
            int requested = Math.min(count, 32);
            int[] reserved = new int[requested];
            reserved[0] = preallocatedFirstSlot;
            int reservedCount = 1;
            int previousSlot = preallocatedFirstSlot;
            while (reservedCount < requested) {
                int slot = objectManager.allocateSlotAfter(previousSlot);
                if (slot < 0) {
                    break;
                }
                reserved[reservedCount++] = slot;
                previousSlot = slot;
            }
            preallocatedSlots = Arrays.copyOf(reserved, reservedCount);
            slotsFullyReserved = true;
        }
        pending.add(new PendingLostRingSpawn(
                player, count, player.getCentreX(), player.getCentreY(), scheduledFrame,
                preallocatedSlots, slotsFullyReserved, deferOwnerRingClear));
    }

    void processPending() {
        if (pending.isEmpty() || levelManager.ringManager == null) {
            return;
        }
        int frameCounter = levelManager.frameCounter;
        Iterator<PendingLostRingSpawn> iterator = pending.iterator();
        while (iterator.hasNext()) {
            PendingLostRingSpawn spawn = iterator.next();
            if (frameCounter <= spawn.frameCounter()) {
                continue;
            }
            if (spawn.player().getRingCount() > 0) {
                levelManager.ringManager.spawnLostRingsWithInitialObjectStep(
                        spawn.player(), spawn.ringCount(), frameCounter,
                        spawn.x(), spawn.y(), spawn.preallocatedSlots(),
                        spawn.slotsFullyReserved(), spawn.deferOwnerRingClear());
            } else if (levelManager.objectManager != null) {
                for (int slot : spawn.preallocatedSlots()) {
                    levelManager.objectManager.releaseDynamicSlot(slot);
                }
            }
            iterator.remove();
        }
    }

    private record PendingLostRingSpawn(
            AbstractPlayableSprite player, int ringCount, int x, int y, int frameCounter,
            int[] preallocatedSlots, boolean slotsFullyReserved, boolean deferOwnerRingClear) {
    }
}
