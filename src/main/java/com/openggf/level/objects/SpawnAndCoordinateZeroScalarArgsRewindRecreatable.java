package com.openggf.level.objects;

import java.lang.reflect.Constructor;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt from the captured spawn, captured spawn coordinates, and zero/false
 * scalar placeholders.
 *
 * <p>Use this only when the concrete class has a constructor beginning with
 * {@code (ObjectSpawn spawn, int x, int y)} followed only by {@code int} or
 * {@code boolean} parameters, and every trailing constructor value is restored
 * immediately by the generic scalar pass.
 */
public interface SpawnAndCoordinateZeroScalarArgsRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Class<? extends AbstractObjectInstance> objectClass =
                RewindRecreateConstructors.objectClass(this);
        try {
            Constructor<?> constructor = findConstructor(objectClass);
            Object[] args = RewindRecreateConstructors.zeroScalarArgs(constructor);
            args[0] = ctx.spawn();
            args[1] = ctx.spawn().x();
            args[2] = ctx.spawn().y();
            return RewindRecreateConstructors.instantiateSelected(
                    this,
                    "SpawnAndCoordinateZeroScalarArgsRewindRecreatable",
                    "unique (ObjectSpawn, int, int, zero scalars...)",
                    "spawn-and-coordinate zero-scalar rewind recreate",
                    constructor,
                    args);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    objectClass.getName()
                            + " implements SpawnAndCoordinateZeroScalarArgsRewindRecreatable "
                            + "but has no unique (ObjectSpawn, int, int, zero scalars...) constructor",
                    e);
        }
    }

    private static Constructor<?> findConstructor(Class<? extends AbstractObjectInstance> objectClass)
            throws NoSuchMethodException {
        return RewindRecreateConstructors.findLongest(
                objectClass,
                "spawn-and-coordinate zero-scalar",
                constructor -> {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    return parameterTypes.length >= 3
                            && parameterTypes[0] == ObjectSpawn.class
                            && parameterTypes[1] == int.class
                            && parameterTypes[2] == int.class
                            && RewindRecreateConstructors.allZeroScalars(parameterTypes, 3);
                },
                true);
    }
}
