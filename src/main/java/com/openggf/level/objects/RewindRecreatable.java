package com.openggf.level.objects;

/**
 * Uniform recreate hook for dynamic objects that cannot be reconstructed from an
 * {@link ObjectSpawn} alone via the game registry's factory.
 *
 * <p>Implementing classes appear in the dynamic-object list at capture time (i.e.
 * runtime/routine-spawned dynamics, not construction-spawned children which are
 * handled by the adoption keystone). Their recreation during a rewind restore is
 * delegated to this method by
 * {@link ObjectRewindDynamicCodecs#genericRecreate(com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry, DynamicObjectRecreateContext)},
 * which is the entry point for Phase-2 generic recreate (Task 4).
 *
 * <p><strong>Contract for implementors:</strong>
 * <ul>
 *   <li>The returned instance must be of the same concrete class as the captured object.</li>
 *   <li>Object-reference fields (e.g. parent back-references) must <em>not</em> be
 *       wired here — they are resolved from the compact blob after recreate returns.</li>
 *   <li>Scalar fields set in the constructor may use placeholder values (e.g. 0); the
 *       standard scalar-restore pass applied after recreate will overwrite them with the
 *       exact captured values.</li>
 *   <li>The implementation must not call {@code services()} during its constructor (the
 *       injection ThreadLocal may not be set); use {@link RewindRecreateContext#objectServices()}
 *       if runtime context is needed during reconstruction.</li>
 * </ul>
 *
 * <p><strong>ArchUnit constraint:</strong> This interface lives in {@code level.objects}
 * (shared, no game dependency). Implementing classes in game-specific packages
 * ({@code game.sonic1}, {@code game.sonic2}, {@code game.sonic3k}) may freely
 * reference game-specific types in their {@code recreateForRewind} body —
 * the dependency flows game→shared, which is permitted.
 */
public interface RewindRecreatable {

    /**
     * Recreates this object's instance from its captured spawn and state during a
     * rewind restore. Called by
     * {@link ObjectRewindDynamicCodecs#genericRecreate} when this class is identified
     * as the captured class.
     *
     * <p>Object-reference fields are NOT set here — they are resolved by id post-recreate.
     *
     * @param ctx restore-time context carrying the captured {@link ObjectSpawn},
     *            the compact scalar state blob, and {@link ObjectServices}
     * @return the recreated instance (may be {@code this} or a new instance)
     */
    AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx);
}
