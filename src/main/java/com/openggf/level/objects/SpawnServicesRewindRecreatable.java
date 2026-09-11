package com.openggf.level.objects;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt directly from the captured {@link ObjectSpawn} and restore-time
 * {@link ObjectServices}.
 *
 * <p>Use this only when the concrete class has an
 * {@code (ObjectSpawn, ObjectServices)} constructor and that constructor does
 * not consume runtime state such as RNG, allocate children, or perform custom
 * art lookups that need a purpose-built rewind factory. Graph-linked objects
 * should continue to implement
 * {@link RewindRecreatable#recreateForRewind(RewindRecreateContext)} directly.
 */
public interface SpawnServicesRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return RewindRecreateConstructors.instantiateExact(
                this,
                "SpawnServicesRewindRecreatable",
                "(ObjectSpawn, ObjectServices)",
                "spawn-services rewind recreate",
                new Class<?>[] {ObjectSpawn.class, ObjectServices.class},
                ctx.spawn(),
                ctx.objectServices());
    }
}
