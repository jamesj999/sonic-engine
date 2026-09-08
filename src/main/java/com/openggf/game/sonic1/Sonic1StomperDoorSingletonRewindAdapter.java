package com.openggf.game.sonic1;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.sonic1.objects.Sonic1StomperDoorObjectInstance;
import com.openggf.level.objects.ObjectManager;

/**
 * Rewind owner for the S1 SBZ3 {@code v_obj6B} singleton byte.
 *
 * <p>The ObjectManager restore clears and reconstructs object slots in two
 * phases. This adapter is registered before the ObjectManager adapter, so its
 * restore clears the old Java owner before any reconstructed door constructor
 * performs the ROM first-loaded-slot claim. The post-restore event callback
 * rebinds the Java owner reference to the restored live slot.
 */
public final class Sonic1StomperDoorSingletonRewindAdapter
        implements RewindSnapshottable<Sonic1StomperDoorSingletonRewindAdapter.Snapshot> {
    public static final String KEY = "s1-sbz3-door-singleton";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Snapshot capture() {
        // The ROM keeps v_obj6B as a byte, while this engine represents its set
        // bit with the owning Java object reference. The restored live slots
        // determine that reference by the ROM's first-loaded-slot invariant.
        return new Snapshot();
    }

    @Override
    public void restore(Snapshot snapshot) {
        Sonic1StomperDoorObjectInstance.prepareSbz3SingletonForRewindRestore();
    }

    @Override
    public void resetForMissingSnapshot() {
        Sonic1StomperDoorObjectInstance.prepareSbz3SingletonForRewindRestore();
    }

    /** Rebinds v_obj6B from live slots in ObjectManager slot order. */
    public static void rebindAfterObjectRestore(ObjectManager objectManager) {
        if (objectManager == null) {
            Sonic1StomperDoorObjectInstance.rebindSbz3Singleton(java.util.List.of());
            return;
        }
        Sonic1StomperDoorObjectInstance.rebindSbz3Singleton(
                objectManager.activeObjectsOfType(Sonic1StomperDoorObjectInstance.class));
    }

    /** Empty marker; ownership is reconstructed from restored live object slots. */
    public record Snapshot() {
    }
}
