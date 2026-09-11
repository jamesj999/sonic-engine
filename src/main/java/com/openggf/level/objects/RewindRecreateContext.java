package com.openggf.level.objects;

import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;

/**
 * Context passed to {@link RewindRecreatable#recreateForRewind(RewindRecreateContext)} during
 * a rewind restore. Exposes the captured spawn, the compact field-state blob, the
 * restore-time object services, the restoring object manager, and the captured dynamic
 * entry when the object was restored from the dynamic-object surface.
 *
 * <p>Object-reference fields from the compact blob are <em>not</em> available here;
 * they are resolved after recreate returns. Implementations may inspect the
 * restore-time {@link ObjectManager} through {@link #objectServices()} for
 * structural relinks that are constructor-required, but must not attempt to decode
 * compact object-reference fields from this context.
 */
public record RewindRecreateContext(
        ObjectSpawn spawn,
        PerObjectRewindSnapshot state,
        ObjectServices objectServices,
        ObjectManager objectManager,
        ObjectManagerSnapshot.DynamicObjectEntry dynamicEntry) {

    public RewindRecreateContext(
            ObjectSpawn spawn,
            PerObjectRewindSnapshot state,
            ObjectServices objectServices) {
        this(spawn, state, objectServices, null, null);
    }

    public RewindRecreateContext(
            ObjectSpawn spawn,
            PerObjectRewindSnapshot state,
            ObjectServices objectServices,
            ObjectManagerSnapshot.DynamicObjectEntry dynamicEntry) {
        this(spawn, state, objectServices, null, dynamicEntry);
    }

    /**
     * Queues the captured dynamic entry for the post-restore player-bound
     * power-up refresh path. This preserves the old deferred restore behavior:
     * {@code recreateForRewind} returns {@code null}, then the live player
     * refresh asks the power-up spawner to recreate and relink the concrete
     * object using the captured slot and field state.
     */
    public void enqueuePendingPlayerBoundEntry(Class<?> baseType) {
        if (dynamicEntry == null || baseType == null) {
            return;
        }
        ObjectManager manager = objectManager;
        if (manager == null && objectServices != null) {
            manager = objectServices.objectManager();
        }
        if (manager != null) {
            manager.enqueuePendingPlayerBoundEntry(baseType, dynamicEntry);
        }
    }
}
