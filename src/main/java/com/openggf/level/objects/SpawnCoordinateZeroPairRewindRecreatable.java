package com.openggf.level.objects;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt from spawn coordinates plus two zero placeholder constructor values.
 *
 * <p>Use this only when the concrete class has an {@code (int x, int y, int, int)}
 * constructor and the two trailing values are scalar-restored immediately after
 * recreate. Graph-linked objects should continue to implement
 * {@link RewindRecreatable#recreateForRewind(RewindRecreateContext)} directly.
 */
public interface SpawnCoordinateZeroPairRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return RewindRecreateConstructors.instantiateExact(
                this,
                "SpawnCoordinateZeroPairRewindRecreatable",
                "(int, int, int, int)",
                "spawn-coordinate zero-pair rewind recreate",
                new Class<?>[] {int.class, int.class, int.class, int.class},
                ctx.spawn().x(),
                ctx.spawn().y(),
                0,
                0);
    }
}
