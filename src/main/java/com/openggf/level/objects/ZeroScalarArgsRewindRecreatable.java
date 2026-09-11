package com.openggf.level.objects;

import java.lang.reflect.Constructor;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt with zero/false scalar placeholder constructor arguments.
 *
 * <p>Use this only when the concrete class has a constructor whose parameters
 * are all {@code int} or {@code boolean}, and every constructor value is
 * restored immediately by the generic scalar pass. When several such
 * constructors exist, the longest one is used unless another constructor has
 * the same arity. Objects needing spawn coordinates, services, or graph
 * relinking should use a more specific recreate hook.
 */
public interface ZeroScalarArgsRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Class<? extends AbstractObjectInstance> objectClass =
                RewindRecreateConstructors.objectClass(this);
        try {
            Constructor<?> constructor = findConstructor(objectClass);
            return RewindRecreateConstructors.instantiateSelected(
                    this,
                    "ZeroScalarArgsRewindRecreatable",
                    "unique zero-scalar",
                    "zero-scalar rewind recreate",
                    constructor,
                    RewindRecreateConstructors.zeroScalarArgs(constructor));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    objectClass.getName() + " implements ZeroScalarArgsRewindRecreatable "
                            + "but has no unique zero-scalar constructor",
                    e);
        }
    }

    private static Constructor<?> findConstructor(Class<? extends AbstractObjectInstance> objectClass)
            throws NoSuchMethodException {
        return RewindRecreateConstructors.findLongest(
                objectClass,
                "zero-scalar",
                constructor -> constructor.getParameterCount() > 0
                        && RewindRecreateConstructors.allZeroScalars(
                                constructor.getParameterTypes(), 0),
                true);
    }
}
