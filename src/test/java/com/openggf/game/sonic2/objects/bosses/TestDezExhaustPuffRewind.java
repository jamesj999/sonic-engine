package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
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

class TestDezExhaustPuffRewind {

    private static final String EXHAUST_PUFF_CLASS =
            "com.openggf.game.sonic2.objects.bosses.Sonic2DEZEggmanInstance$ExhaustPuff";

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void exhaustPuffRestoresThroughGenericRecreateWithCapturedScalarState() throws Exception {
        ObjectManager objectManager = objectManager();
        Sonic2DEZEggmanInstance.ExhaustPuff capturedPuff =
                objectManager.createDynamicObject(() -> new Sonic2DEZEggmanInstance.ExhaustPuff(0x790, 0x148));
        setIntField(capturedPuff, "currentX", 0x782);
        setIntField(capturedPuff, "currentY", 0x12C);
        setIntField(capturedPuff, "xFixed", 0x782 << 16);
        setIntField(capturedPuff, "yFixed", 0x12C << 16);
        setIntField(capturedPuff, "xVel", -0x180);
        setIntField(capturedPuff, "yVel", 0x30);
        setIntField(capturedPuff, "timer", 4);
        setIntField(capturedPuff, "currentFrame", 5);

        ObjectRefId capturedId = objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(capturedPuff);
        assertNotNull(capturedId, "captured exhaust puff must have a dynamic rewind identity");

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(capturedPuff);
        Sonic2DEZEggmanInstance.ExhaustPuff replacement =
                objectManager.createDynamicObject(() -> new Sonic2DEZEggmanInstance.ExhaustPuff(0x600, 0x120));
        assertEquals(1, livePuffs(objectManager).size(),
                "diverge step should leave exactly one replacement puff before restore");

        rewindRegistry.restore(snapshot);

        List<Sonic2DEZEggmanInstance.ExhaustPuff> restoredPuffs = livePuffs(objectManager);
        assertEquals(1, restoredPuffs.size(), "restore must leave exactly one live exhaust puff");
        Sonic2DEZEggmanInstance.ExhaustPuff restoredPuff = restoredPuffs.get(0);
        assertNotSame(capturedPuff, restoredPuff,
                "restore must recreate the removed captured puff instead of reusing a stale object");
        assertNotSame(replacement, restoredPuff,
                "restore must remove divergent replacement objects");

        ObjectRefId restoredId = objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(restoredPuff);
        assertEquals(capturedId, restoredId,
                "restored exhaust puff must retain the captured dynamic identity");
        assertEquals(0x782, restoredPuff.getX());
        assertEquals(0x12C, restoredPuff.getY());
        assertEquals(0x782 << 16, intField(restoredPuff, "xFixed"));
        assertEquals(0x12C << 16, intField(restoredPuff, "yFixed"));
        assertEquals(-0x180, intField(restoredPuff, "xVel"));
        assertEquals(0x30, intField(restoredPuff, "yVel"));
        assertEquals(4, intField(restoredPuff, "timer"));
        assertEquals(5, intField(restoredPuff, "currentFrame"));

        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2DEZEggmanInstance.ExhaustPuff.class),
                "DEZ exhaust puffs must opt into ObjectManager generic dynamic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(EXHAUST_PUFF_CLASS),
                "DEZ exhaust puffs must restore through generic recreate, not an explicit dynamic codec");
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
                new Sonic2ObjectRegistry(),
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

    private static List<Sonic2DEZEggmanInstance.ExhaustPuff> livePuffs(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass() == Sonic2DEZEggmanInstance.ExhaustPuff.class && !o.isDestroyed())
                .map(Sonic2DEZEggmanInstance.ExhaustPuff.class::cast)
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
