package com.openggf.level.objects;

import java.lang.reflect.Constructor;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt from the captured {@link ObjectSpawn}, restore-time
 * {@link ObjectServices}, and default placeholder constructor arguments.
 *
 * <p>Use this only when the concrete class has a constructor beginning with
 * {@code (ObjectSpawn, ObjectServices)} followed only by nullable references,
 * {@code int}, or {@code boolean} parameters. Trailing placeholders are
 * {@code null}, {@code 0}, or {@code false}; any captured state they stand in
 * for must be restored immediately by the generic scalar pass. Graph-linked
 * objects should continue to implement
 * {@link RewindRecreatable#recreateForRewind(RewindRecreateContext)} directly.
 */
public interface SpawnServicesDefaultArgsRewindRecreatable extends SpawnServicesRewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Class<? extends AbstractObjectInstance> objectClass =
                RewindRecreateConstructors.objectClass(this);
        try {
            Constructor<?> constructor = findConstructor(objectClass);
            Object[] args = RewindRecreateConstructors.defaultArgs(constructor);
            args[0] = ctx.spawn();
            args[1] = ctx.objectServices();
            return RewindRecreateConstructors.instantiateSelected(
                    this,
                    "SpawnServicesDefaultArgsRewindRecreatable",
                    "unique (ObjectSpawn, ObjectServices, default args...)",
                    "spawn-services default-args rewind recreate",
                    constructor,
                    args);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    objectClass.getName()
                            + " implements SpawnServicesDefaultArgsRewindRecreatable "
                            + "but has no unique (ObjectSpawn, ObjectServices, default args...) constructor",
                    e);
        }
    }

    private static Constructor<?> findConstructor(Class<? extends AbstractObjectInstance> objectClass)
            throws NoSuchMethodException {
        return RewindRecreateConstructors.findLongest(
                objectClass,
                "spawn-services default-args",
                constructor -> {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    return parameterTypes.length >= 3
                            && parameterTypes[0] == ObjectSpawn.class
                            && parameterTypes[1] == ObjectServices.class
                            && RewindRecreateConstructors.allDefaultable(parameterTypes, 2);
                },
                true);
    }
}
