package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFZCylinderCadence {

    @Test
    void topRetractionCompletesOnlyAfterSubtractionBorrowsPastZero() throws Exception {
        Sonic1FZBossInstance boss = new Sonic1FZBossInstance(
                new ObjectSpawn(0, 0, Sonic1ObjectIds.FZ_BOSS, 0, 0, false, 0));
        TestObjectServices services = new TestObjectServices();
        boss.setServices(services);
        FZCylinder cylinder = new FZCylinder(boss, 4);
        cylinder.setServices(services);
        write(cylinder, "active", true);
        write(cylinder, "direction", 0);
        write(cylinder, "extensionFixed", 0x20000);

        cylinder.update(1, null);

        assertTrue(readBoolean(cylinder, "active"),
                "subi.l from +2 to exactly zero has no borrow, so bcc keeps routine 4 active");

        cylinder.update(2, null);

        assertFalse(readBoolean(cylinder, "active"),
                "the following zero-to-negative subtraction borrows and completes retraction");
    }

    private static void write(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean readBoolean(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }
}
