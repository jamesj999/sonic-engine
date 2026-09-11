package com.openggf.level.objects;

/**
 * Marker for spawn-based rewind recreation that must run with restore-time
 * {@link ObjectServices} installed in the construction context.
 *
 * <p>Use this only when the concrete class has an {@code ObjectSpawn}
 * constructor and that constructor or its superclass initialization expects
 * {@link ObjectConstructionContext#construct(ObjectServices, java.util.function.Supplier)}.
 */
public interface SpawnConstructionContextRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(
                ctx.objectServices(),
                () -> RewindRecreateConstructors.instantiateExact(
                        this,
                        "SpawnConstructionContextRewindRecreatable",
                        "ObjectSpawn",
                        "spawn construction-context rewind recreate",
                        new Class<?>[] {ObjectSpawn.class},
                        ctx.spawn()));
    }
}
