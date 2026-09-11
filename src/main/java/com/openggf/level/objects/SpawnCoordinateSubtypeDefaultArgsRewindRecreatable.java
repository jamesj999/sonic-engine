package com.openggf.level.objects;

import java.lang.reflect.Constructor;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt from captured spawn coordinates, captured spawn subtype, and default
 * placeholder constructor arguments.
 *
 * <p>Use this only when the concrete class has a constructor beginning with
 * {@code (int x, int y, int subtype)} followed only by nullable references,
 * {@code int}, or {@code boolean} parameters. Trailing placeholders are
 * {@code null}, {@code 0}, or {@code false}; any captured state they stand in
 * for must be restored immediately by the generic scalar pass.
 */
public interface SpawnCoordinateSubtypeDefaultArgsRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Class<? extends AbstractObjectInstance> objectClass =
                RewindRecreateConstructors.objectClass(this);
        try {
            Constructor<?> constructor = findConstructor(objectClass);
            Object[] args = defaultArgs(constructor);
            args[0] = ctx.spawn().x();
            args[1] = ctx.spawn().y();
            args[2] = ctx.spawn().subtype();
            return RewindRecreateConstructors.instantiateSelected(
                    this,
                    "SpawnCoordinateSubtypeDefaultArgsRewindRecreatable",
                    "unique (int, int, int, default args...)",
                    "spawn-coordinate subtype-default rewind recreate",
                    constructor,
                    args);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    objectClass.getName()
                            + " implements SpawnCoordinateSubtypeDefaultArgsRewindRecreatable "
                            + "but has no unique (int, int, int, default args...) constructor",
                    e);
        }
    }

    private static Constructor<?> findConstructor(Class<? extends AbstractObjectInstance> objectClass)
            throws NoSuchMethodException {
        return RewindRecreateConstructors.findLongest(
                objectClass,
                "spawn-coordinate subtype-default",
                constructor -> {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    return parameterTypes.length >= 4
                            && parameterTypes[0] == int.class
                            && parameterTypes[1] == int.class
                            && parameterTypes[2] == int.class
                            && allDefaultable(parameterTypes, 3);
                },
                true);
    }

    private static boolean allDefaultable(Class<?>[] parameterTypes, int offset) {
        for (int i = offset; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (parameterType.isPrimitive()
                    && parameterType != int.class
                    && parameterType != boolean.class) {
                return false;
            }
        }
        return true;
    }

    private static Object[] defaultArgs(Constructor<?> constructor) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == boolean.class) {
                args[i] = Boolean.FALSE;
            } else if (parameterTypes[i] == int.class) {
                args[i] = 0;
            } else {
                args[i] = null;
            }
        }
        return args;
    }
}
