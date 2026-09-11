package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGargoyleFireballRewind {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void fireballRestoresThroughGenericRecreateWithCapturedScalarState() throws Exception {
        ObjectManager objectManager = objectManager();
        Sonic1GargoyleObjectInstance.Fireball capturedFireball =
                objectManager.createDynamicObject(() -> new Sonic1GargoyleObjectInstance.Fireball(0x100, 0x80, true));
        setIntField(capturedFireball, "currentX", 0x114);
        setIntField(capturedFireball, "currentY", 0x8C);
        setIntField(capturedFireball, "velX", 0x200);
        setBooleanField(capturedFireball, "movingRight", true);
        setIntField(capturedFireball, "currentFrame", 3);

        ObjectRefId capturedId = objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(capturedFireball);
        assertNotNull(capturedId, "captured gargoyle fireball must have a dynamic rewind identity");

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(capturedFireball);
        Sonic1GargoyleObjectInstance.Fireball replacement =
                objectManager.createDynamicObject(() -> new Sonic1GargoyleObjectInstance.Fireball(0x140, 0x90, false));
        assertEquals(1, liveFireballs(objectManager).size(),
                "diverge step should leave exactly one replacement fireball before restore");

        rewindRegistry.restore(snapshot);

        List<Sonic1GargoyleObjectInstance.Fireball> restoredFireballs = liveFireballs(objectManager);
        assertEquals(1, restoredFireballs.size(), "restore must leave exactly one live gargoyle fireball");
        Sonic1GargoyleObjectInstance.Fireball restoredFireball = restoredFireballs.get(0);
        assertNotSame(capturedFireball, restoredFireball,
                "restore must recreate the removed captured fireball instead of reusing a stale object");
        assertNotSame(replacement, restoredFireball,
                "restore must remove divergent replacement objects");

        ObjectRefId restoredId = objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(restoredFireball);
        assertEquals(capturedId, restoredId,
                "restored gargoyle fireball must retain the captured dynamic identity");
        assertEquals(0x114, restoredFireball.getX());
        assertEquals(0x8C, restoredFireball.getY());
        assertEquals(0x200, intField(restoredFireball, "velX"));
        assertEquals(true, booleanField(restoredFireball, "movingRight"));
        assertEquals(3, intField(restoredFireball, "currentFrame"));

        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1GargoyleObjectInstance.Fireball.class),
                "gargoyle fireballs must opt into ObjectManager generic dynamic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1GargoyleObjectInstance.Fireball.class.getName()),
                "gargoyle fireballs must restore through generic recreate, not an explicit dynamic codec");
    }

    private static ObjectManager objectManager() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCameraAtOrigin();
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public Camera camera() {
                return camera;
            }
        };
        ObjectManager objectManager = new ObjectManager(
                List.of(),
                new Sonic1ObjectRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);
        return objectManager;
    }

    private static List<Sonic1GargoyleObjectInstance.Fireball> liveFireballs(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass() == Sonic1GargoyleObjectInstance.Fireball.class && !o.isDestroyed())
                .map(Sonic1GargoyleObjectInstance.Fireball.class::cast)
                .toList();
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static int intField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void setBooleanField(Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static Camera mockCameraAtOrigin() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
