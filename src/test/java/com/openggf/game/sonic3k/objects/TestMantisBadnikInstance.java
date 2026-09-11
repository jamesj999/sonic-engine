package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.objects.badniks.MantisBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMantisBadnikInstance {

    @Test
    void collisionResponseListReadsLiveMantisPosition() {
        MantisBadnikInstance mantis = new MantisBadnikInstance(
                new ObjectSpawn(0x0CA0, 0x05D0, 0x9D, 0, 0, false, 0));

        assertTrue(mantis.usesCurrentTouchResponseState(),
                "S3K retains the Mantis SST pointer, so TouchResponse reads its live x_pos/y_pos");
    }

    @Test
    void landingAnimationCompletionRetainsItsComposedYPosition() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        MantisBadnikInstance mantis = new MantisBadnikInstance(
                new ObjectSpawn(0x0200, 0x0669, 0x9D, 0, 0, false, 0));
        mantis.setServices(new StubObjectServices());
        setField(mantis, "initialized", true);
        setEnumField(mantis, "state", "LAND");
        setField(mantis, "currentY", 0x0680);
        setField(mantis, "animIndex", 3);
        setField(mantis, "animTimer", 0);

        mantis.update(12, null);

        assertEquals("WAIT", readField(mantis, "state").toString());
        assertEquals(0x0680, mantis.getY(),
                "loc_88F48 only writes routine=2; it does not restore the floor-hit Y");
    }

    @Test
    void unloadingChildClearsParentRewindReference() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 1024, 1024, 0);
        MantisBadnikInstance mantis = new MantisBadnikInstance(
                new ObjectSpawn(0x0200, 0x0100, 0x9D, 0, 0, false, 0));
        mantis.setServices(new StubObjectServices());
        mantis.refreshPostCameraRenderState();
        mantis.update(0, null);
        mantis.update(1, null);
        AbstractObjectInstance child = (AbstractObjectInstance) readField(mantis, "child");

        child.onUnload();

        assertNull(readField(mantis, "child"),
                "A retired child must not remain reachable after its rewind id is unregistered");
    }

    @Test
    void waitOffscreenDefersInitializationUntilPlaceholderIsVisible() throws Exception {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        MantisBadnikInstance mantis = new MantisBadnikInstance(
                new ObjectSpawn(0x0100, 0x0490, 0x9D, 0, 0, false, 0));
        mantis.setServices(new StubObjectServices());

        mantis.update(0, null);
        mantis.update(1, null);

        assertFalse((boolean) readField(mantis, "initialized"));
        assertNull(readField(mantis, "child"),
                "Obj_WaitOffscreen suppresses Mantis_Init and its child allocation while hidden");
        assertEquals(0x0490, mantis.getY());
        assertEquals(0, mantis.getCollisionFlags());

        AbstractObjectInstance.updateCameraBounds(0, 0x0400, 320, 0x04E0, 0);
        mantis.refreshPostCameraRenderState();
        mantis.update(2, null);

        assertFalse((boolean) readField(mantis, "initialized"),
                "Obj_WaitOffscreen only restores the saved Mantis operation on this pass");
        assertNull(readField(mantis, "child"),
                "the restore pass returns before Mantis_Init can allocate its child");

        mantis.update(3, null);

        AbstractObjectInstance child = (AbstractObjectInstance) readField(mantis, "child");
        assertTrue((boolean) readField(mantis, "initialized"));
        assertNotNull(child, "Mantis_Init must allocate its visual child on the following dispatch");
        assertSame(mantis, readField(child, "parent"),
                "the managed visual child must keep its owning Mantis reference");
        assertSame(child, readField(mantis, "child"),
                "the initialized Mantis must retain the child it allocated");
        assertEquals(0x1A, mantis.getCollisionFlags());
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setEnumField(Object target, String name, String constant) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, Enum.valueOf((Class<? extends Enum>) field.getType(), constant));
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue through the shared badnik base classes.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
