package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.objects.badniks.GrounderBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public interface GrounderZeroIndexChildRewindRecreatable extends RewindRecreatable {

    @Override
    default AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn spawn = ctx.spawn();
        GrounderBadnikInstance parent = Sonic2GrounderChildRewindLinks.nearestGrounder(ctx);
        Class<? extends AbstractObjectInstance> objectClass =
                getClass().asSubclass(AbstractObjectInstance.class);
        try {
            Constructor<? extends AbstractObjectInstance> constructor = objectClass.getDeclaredConstructor(
                    int.class,
                    int.class,
                    int.class,
                    GrounderBadnikInstance.class);
            constructor.setAccessible(true);
            return constructor.newInstance(spawn.x(), spawn.y(), 0, parent);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    objectClass.getName() + " implements GrounderZeroIndexChildRewindRecreatable "
                            + "but has no (int, int, int, GrounderBadnikInstance) constructor",
                    e);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(
                    objectClass.getName() + " failed Grounder zero-index child rewind recreate",
                    e);
        }
    }
}
