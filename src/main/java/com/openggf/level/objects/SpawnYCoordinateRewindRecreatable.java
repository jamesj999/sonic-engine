package com.openggf.level.objects;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt from the captured {@link ObjectSpawn} and the spawn's Y coordinate
 * as a secondary constructor value.
 *
 * <p>Use this only when the concrete class has an {@code (ObjectSpawn, int)}
 * constructor and the integer specifically mirrors {@code spawn.y()}.
 */
public interface SpawnYCoordinateRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return RewindRecreateConstructors.instantiateExact(
                this,
                "SpawnYCoordinateRewindRecreatable",
                "(ObjectSpawn, int)",
                "spawn-Y-coordinate rewind recreate",
                new Class<?>[] {ObjectSpawn.class, int.class},
                ctx.spawn(),
                ctx.spawn().y());
    }
}
