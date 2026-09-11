package com.openggf.level.objects;

import java.lang.reflect.Constructor;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt from captured spawn coordinates plus zero/false scalar placeholders.
 *
 * <p>Use this only when the concrete class has a constructor beginning with
 * {@code (int x, int y)} followed only by {@code int} or {@code boolean}
 * parameters, and every trailing constructor value is restored immediately by
 * the generic scalar pass. When several such constructors exist, the longest
 * one is used unless another constructor has the same arity.
 */
public interface SpawnCoordinateZeroScalarArgsRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Class<? extends AbstractObjectInstance> objectClass =
                RewindRecreateConstructors.objectClass(this);
        try {
            Constructor<?> constructor = findConstructor(objectClass);
            Object[] args = RewindRecreateConstructors.zeroScalarArgs(constructor);
            args[0] = ctx.spawn().x();
            args[1] = ctx.spawn().y();
            return RewindRecreateConstructors.instantiateSelected(
                    this,
                    "SpawnCoordinateZeroScalarArgsRewindRecreatable",
                    "unique (int, int, zero scalars...)",
                    "spawn-coordinate zero-scalar rewind recreate",
                    constructor,
                    args);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    objectClass.getName() + " implements SpawnCoordinateZeroScalarArgsRewindRecreatable "
                            + "but has no unique (int, int, zero scalars...) constructor",
                    e);
        }
    }

    private static Constructor<?> findConstructor(Class<? extends AbstractObjectInstance> objectClass)
            throws NoSuchMethodException {
        return RewindRecreateConstructors.findLongest(
                objectClass,
                "spawn-coordinate zero-scalar",
                constructor -> {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    return parameterTypes.length >= 3
                            && parameterTypes[0] == int.class
                            && parameterTypes[1] == int.class
                            && RewindRecreateConstructors.allZeroScalars(parameterTypes, 2);
                },
                true);
    }
}
