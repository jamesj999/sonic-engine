package com.openggf.level.objects;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt directly from the captured spawn coordinates.
 *
 * <p>Use this only when the concrete class has an {@code (int x, int y)}
 * constructor and needs no parent/sibling lookup or placeholder constructor
 * arguments during recreate. Graph-linked objects should continue to implement
 * {@link RewindRecreatable#recreateForRewind(RewindRecreateContext)} directly.
 */
public interface SpawnCoordinateRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return RewindRecreateConstructors.instantiateExact(
                this,
                "SpawnCoordinateRewindRecreatable",
                "(int, int)",
                "spawn-coordinate rewind recreate",
                new Class<?>[] {int.class, int.class},
                ctx.spawn().x(),
                ctx.spawn().y());
    }
}
