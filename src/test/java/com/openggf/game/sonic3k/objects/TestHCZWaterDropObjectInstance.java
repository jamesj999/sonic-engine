package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHCZWaterDropObjectInstance {

    @Test
    void childUsesExplicitContinuousSingleRegionTouchProfile() throws Exception {
        TouchResponseProvider child = newWaterDropChild();

        TouchResponseProfile expected = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                true,
                true,
                false,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        assertEquals(expected, child.getTouchResponseProfile());
        assertEquals(expected, child.getTouchResponseProfile(false));
        assertTrue(declares(child.getClass(), "getTouchResponseProfile"));
        assertTrue(declares(child.getClass(), "getTouchResponseProfile", boolean.class));
        assertFalse(declares(child.getClass(), "requiresContinuousTouchCallbacks"));
    }

    private static TouchResponseProvider newWaterDropChild() throws Exception {
        Class<?> childClass = Class.forName(
                "com.openggf.game.sonic3k.objects.HCZWaterDropObjectInstance$WaterDropChild");
        Constructor<?> constructor = childClass.getDeclaredConstructor(ObjectSpawn.class);
        constructor.setAccessible(true);
        Object child = constructor.newInstance(new ObjectSpawn(0x100, 0x180, 0x6E, 0, 0, false, 0));
        return assertInstanceOf(TouchResponseProvider.class, child);
    }

    private static boolean declares(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method ignored = type.getDeclaredMethod(name, parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
