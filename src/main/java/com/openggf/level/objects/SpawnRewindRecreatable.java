package com.openggf.level.objects;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt directly from the captured {@link ObjectSpawn}.
 *
 * <p>Use this only when the concrete class has an {@code ObjectSpawn}
 * constructor and needs no parent/sibling lookup or placeholder constructor
 * arguments during recreate. Graph-linked objects should continue to implement
 * {@link RewindRecreatable#recreateForRewind(RewindRecreateContext)} directly.
 */
public interface SpawnRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return RewindRecreateConstructors.instantiateExact(
                this,
                "SpawnRewindRecreatable",
                "ObjectSpawn",
                "spawn-based rewind recreate",
                new Class<?>[] {ObjectSpawn.class},
                ctx.spawn());
    }
}
