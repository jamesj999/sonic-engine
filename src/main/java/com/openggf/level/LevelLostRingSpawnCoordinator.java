package com.openggf.level;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.Sprite;
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
final class LevelLostRingSpawnCoordinator
        implements RewindSnapshottable<LevelLostRingSpawnCoordinator.Snapshot> {
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

    void reset() {
        releaseReservedSlots();
        pending.clear();
    }

    @Override
    public String key() {
        return "level-lost-ring-spawns";
    }

    @Override
    public Snapshot capture() {
        PendingLostRingSpawnSnapshot[] pendingSnapshots = new PendingLostRingSpawnSnapshot[pending.size()];
        for (int i = 0; i < pending.size(); i++) {
            PendingLostRingSpawn spawn = pending.get(i);
            pendingSnapshots[i] = new PendingLostRingSpawnSnapshot(
                    spawn.player().getCode(), spawn.ringCount(), spawn.x(), spawn.y(), spawn.frameCounter(),
                    spawn.preallocatedSlots(), spawn.slotsFullyReserved(), spawn.deferOwnerRingClear());
        }
        return new Snapshot(pendingSnapshots);
    }

    @Override
    public void restore(Snapshot snapshot) {
        // The object-manager snapshot deliberately owns only object-backed slots,
        // not these queue reservations. During rewind it rebuilds its own slot
        // bitmap later in the registry, so releasing the current reservations
        // here would mutate an allocator that is about to be restored anyway.
        // Keep lifecycle reset separate: it still releases live reservations.
        pending.clear();
        for (PendingLostRingSpawnSnapshot saved : snapshot.pending()) {
            Sprite sprite = levelManager.spriteManager != null
                    ? levelManager.spriteManager.getSprite(saved.playerCode()) : null;
            if (!(sprite instanceof AbstractPlayableSprite player)) {
                throw new IllegalStateException(
                        "Lost-ring rewind owner is unavailable: " + saved.playerCode());
            }
            pending.add(new PendingLostRingSpawn(
                    player, saved.ringCount(), saved.x(), saved.y(), saved.frameCounter(),
                    saved.preallocatedSlots(), saved.slotsFullyReserved(), saved.deferOwnerRingClear()));
        }
    }

    @Override
    public void resetForMissingSnapshot() {
        // See restore: ObjectManager restores its authoritative occupancy as part
        // of the same rewind operation. A missing queue snapshot means no queued
        // external reservations should be reclaimed afterwards.
        pending.clear();
    }

    /**
     * Reclaims saved queue-owned slots after all rewindable subsystem owners
     * have restored their own state. Kept separate from {@link #restore(Snapshot)}
     * because ObjectManager intentionally excludes external reservations from its
     * slot snapshot.
     */
    void reconcileReservationsAfterRewindRestore() {
        ObjectManager objectManager = levelManager.objectManager;
        if (objectManager == null) {
            return;
        }
        for (PendingLostRingSpawn spawn : pending) {
            for (int slot : spawn.preallocatedSlots()) {
                if (!objectManager.reserveDynamicSlot(slot)) {
                    throw new IllegalStateException(
                            "Lost-ring rewind reservation is occupied: " + slot);
                }
            }
        }
    }

    private void releaseReservedSlots() {
        if (levelManager.objectManager == null) {
            return;
        }
        for (PendingLostRingSpawn spawn : pending) {
            for (int slot : spawn.preallocatedSlots()) {
                levelManager.objectManager.releaseDynamicSlot(slot);
            }
        }
    }

    private record PendingLostRingSpawn(
            AbstractPlayableSprite player, int ringCount, int x, int y, int frameCounter,
            int[] preallocatedSlots, boolean slotsFullyReserved, boolean deferOwnerRingClear) {
        private PendingLostRingSpawn {
            preallocatedSlots = preallocatedSlots.clone();
        }

        @Override
        public int[] preallocatedSlots() {
            return preallocatedSlots.clone();
        }
    }

    record Snapshot(PendingLostRingSpawnSnapshot[] pending) {
        Snapshot {
            pending = pending.clone();
        }

        @Override
        public PendingLostRingSpawnSnapshot[] pending() {
            return pending.clone();
        }
    }

    private record PendingLostRingSpawnSnapshot(
            String playerCode, int ringCount, int x, int y, int frameCounter,
            int[] preallocatedSlots, boolean slotsFullyReserved, boolean deferOwnerRingClear) {
        private PendingLostRingSpawnSnapshot {
            preallocatedSlots = preallocatedSlots.clone();
        }

        @Override
        public int[] preallocatedSlots() {
            return preallocatedSlots.clone();
        }
    }
}
