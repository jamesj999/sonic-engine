package com.openggf.level.objects;

import java.lang.reflect.Constructor;
import java.util.Arrays;

/**
 * Marker for rewind-recreatable dynamic objects whose restore constructor
 * derives final render-flip fields from the captured spawn render flags.
 *
 * <p>Supported constructor shapes are {@code (int x, int y, boolean xFlip)}
 * and {@code (ObjectSpawn spawn, int x, int y, boolean xFlip, boolean yFlip)}.
 */
public interface SpawnRenderFlipRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Class<? extends AbstractObjectInstance> objectClass =
                RewindRecreateConstructors.objectClass(this);
        try {
            Constructor<?> constructor = findConstructor(objectClass);
            ObjectSpawn spawn = ctx.spawn();
            boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
            boolean yFlip = (spawn.renderFlags() & 0x02) != 0;
            Object[] args = coordinateFlipConstructor(constructor)
                    ? new Object[] { spawn.x(), spawn.y(), xFlip }
                    : new Object[] { spawn, spawn.x(), spawn.y(), xFlip, yFlip };
            return RewindRecreateConstructors.instantiateSelected(
                    this,
                    "SpawnRenderFlipRewindRecreatable",
                    "unique render-flip recreate",
                    "spawn render-flip rewind recreate",
                    constructor,
                    args);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    objectClass.getName() + " implements SpawnRenderFlipRewindRecreatable "
                            + "but has no unique supported render-flip constructor",
                    e);
        }
    }

    private static Constructor<?> findConstructor(Class<? extends AbstractObjectInstance> objectClass)
            throws NoSuchMethodException {
        return RewindRecreateConstructors.findOnly(
                objectClass,
                "render-flip recreate",
                constructor -> coordinateFlipConstructor(constructor)
                        || spawnCoordinateFlipConstructor(constructor));
    }

    private static boolean coordinateFlipConstructor(Constructor<?> constructor) {
        return Arrays.equals(
                constructor.getParameterTypes(),
                new Class<?>[] { int.class, int.class, boolean.class });
    }

    private static boolean spawnCoordinateFlipConstructor(Constructor<?> constructor) {
        return Arrays.equals(
                constructor.getParameterTypes(),
                new Class<?>[] {
                        ObjectSpawn.class,
                        int.class,
                        int.class,
                        boolean.class,
                        boolean.class
                });
    }
}
