package com.openggf.level.objects;

import java.lang.reflect.Constructor;

/**
 * Marker for rewind-recreatable dynamic objects whose restore instance can be
 * rebuilt from the captured {@link ObjectSpawn} plus one nullable reference.
 *
 * <p>Use this only when the concrete class has exactly one
 * {@code (ObjectSpawn, reference)} constructor and the second constructor value
 * is deliberately nullable during recreate. The generic scalar-restore pass
 * must restore any captured state immediately after construction. Parent- or
 * sibling-linked objects that must resolve live graph references should continue
 * to implement
 * {@link RewindRecreatable#recreateForRewind(RewindRecreateContext)} directly.
 */
public interface SpawnNullableReferenceRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Class<? extends AbstractObjectInstance> objectClass =
                RewindRecreateConstructors.objectClass(this);
        try {
            Constructor<?> constructor = findConstructor(objectClass);
            return RewindRecreateConstructors.instantiateSelected(
                    this,
                    "SpawnNullableReferenceRewindRecreatable",
                    "unique (ObjectSpawn, reference)",
                    "spawn nullable-reference rewind recreate",
                    constructor,
                    ctx.spawn(),
                    null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    objectClass.getName() + " implements SpawnNullableReferenceRewindRecreatable "
                            + "but has no unique (ObjectSpawn, reference) constructor",
                    e);
        }
    }

    private static Constructor<?> findConstructor(Class<? extends AbstractObjectInstance> objectClass)
            throws NoSuchMethodException {
        return RewindRecreateConstructors.findOnly(
                objectClass,
                "ObjectSpawn, reference",
                constructor -> {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    return parameterTypes.length == 2
                            && parameterTypes[0] == ObjectSpawn.class
                            && !parameterTypes[1].isPrimitive();
                });
    }
}
