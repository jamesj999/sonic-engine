package com.openggf.tests;

import com.openggf.game.sonic1.objects.badniks.Sonic1CrabmeatBadnikInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1CrabmeatBadnikInstance {

    @Test
    void firstOnScreenWaitExpiryStartsWalkingBeforeFirstFireCycle() throws Exception {
        Sonic1CrabmeatBadnikInstance crabmeat = new OnScreenCrabmeat();
        crabmeat.setServices(new StubObjectServices());
        setField(crabmeat, "renderFlagClearFromInvisibleInit", false);

        invokePrivate(crabmeat, "updateWaitFire");

        assertEquals(1, getIntField(crabmeat, "secondaryState"),
                "S1 Obj1F bchg #1 branches on the old clear fire bit, so the first on-screen expiry moves");
        assertEquals(127, getIntField(crabmeat, "timeDelay"),
                "Crabmeat should load the walk timer on the first on-screen expiry");
        assertEquals(0x02, getIntField(crabmeat, "crabMode") & 0x02,
                "The fire-mode bit is still toggled for the next wait/fire cycle");
        assertEquals(-0x80, getIntField(crabmeat, "xVelocity"),
                "Initial status bit clear makes the first walk move left in ROM coordinates");
    }

    private static void invokePrivate(Object target, String methodName) throws Exception {
        Class<?> type = target.getClass();
        Method method = null;
        while (type != null && method == null) {
            try {
                method = type.getDeclaredMethod(methodName);
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        if (method == null) throw new NoSuchMethodException(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static int getIntField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class OnScreenCrabmeat extends Sonic1CrabmeatBadnikInstance {
        private OnScreenCrabmeat() {
            super(new ObjectSpawn(0x100, 0x80, 0x1F, 0, 0, false, 0));
        }

        @Override
        protected boolean isWithinRenderSpriteBounds(int xMargin, int yMargin) {
            return true;
        }
    }
}
