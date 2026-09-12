package com.openggf.level;

import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.RewindSnapshottable;

/** Internal bridge for registering the level-owned lost-ring rewind adapter. */
public final class LevelLostRingSpawnRewindAccess {
    private static final String POST_RESTORE_KEY = "level-lost-ring-reservation-reconcile";

    private LevelLostRingSpawnRewindAccess() {
    }

    public static void unregister(RewindRegistry registry) {
        registry.deregister("level-lost-ring-spawns");
        registry.deregisterPostRestoreCallback(POST_RESTORE_KEY);
    }

    public static RewindSnapshottable<?> create(LevelManager manager) {
        return manager.createLostRingSpawnRewindAdapterInternal();
    }

    /**
     * Registers the queue snapshot and its post-restore slot reconciliation.
     * ObjectManager restores its object-backed slot bitmap during the same pass;
     * the callback then restores this collaborator's external reservations.
     */
    public static void register(LevelManager manager, RewindRegistry registry) {
        LevelLostRingSpawnCoordinator coordinator = manager.createLostRingSpawnRewindAdapterInternal();
        if (coordinator == null) {
            return;
        }
        registry.register(coordinator);
        registry.registerPostRestoreCallback(POST_RESTORE_KEY,
                coordinator::reconcileReservationsAfterRewindRestore);
    }
}
