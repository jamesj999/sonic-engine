package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestDezBarrierWallRewind {

    private static final String BARRIER_WALL_CLASS =
            "com.openggf.game.sonic2.objects.bosses.Sonic2DEZEggmanInstance$BarrierWall";

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void barrierWallRestoresThroughGenericRecreateAndRelinksParentBackReference() throws Exception {
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

        ObjectSpawn parentSpawn = new ObjectSpawn(
                160, 240, Sonic2ObjectIds.DEZ_EGGMAN, 0xA6, 0, false, 0);
        ObjectManager objectManager = new ObjectManager(
                List.of(parentSpawn),
                new Sonic2ObjectRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                camera,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);

        Sonic2DEZEggmanInstance parent = liveParent(objectManager);
        parent.update(0, null);

        Sonic2DEZEggmanInstance.BarrierWall capturedWall = onlyLiveWall(objectManager);
        assertSame(capturedWall, barrierWallField(parent),
                "precondition: production spawnChild path must wire the parent wall reference");

        ObjectRefId capturedId = objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(capturedWall);
        assertNotNull(capturedId, "captured barrier wall must have a dynamic rewind identity");

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(capturedWall);
        objectManager.createDynamicObject(() -> new Sonic2DEZEggmanInstance.BarrierWall(0x500, 0x160));
        assertEquals(1, countLiveWalls(objectManager),
                "diverge step should leave exactly one replacement wall before restore");

        rewindRegistry.restore(snapshot);

        List<Sonic2DEZEggmanInstance.BarrierWall> restoredWalls = liveWalls(objectManager);
        assertEquals(1, restoredWalls.size(), "restore must leave exactly one live barrier wall");
        Sonic2DEZEggmanInstance.BarrierWall restoredWall = restoredWalls.get(0);
        ObjectRefId restoredId = objectManager.captureIdentityContext()
                .requireIdentityTable()
                .idFor(restoredWall);
        assertEquals(capturedId, restoredId,
                "restored barrier wall must retain the captured dynamic identity");

        Sonic2DEZEggmanInstance restoredParent = liveParent(objectManager);
        assertSame(restoredWall, barrierWallField(restoredParent),
                "restored parent must point at the restored barrier wall");

        setIntField(restoredParent, "routineSecondary", 4);
        setIntField(restoredParent, "timer", 0);
        restoredParent.update(1, null);
        assertTrueBooleanField(restoredWall, "eggmanRunning",
                "parent run transition must signal the restored wall, not the removed wall");

        assertNoRegisteredS2DynamicCodec(BARRIER_WALL_CLASS);
    }

    private static Sonic2DEZEggmanInstance liveParent(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass() == Sonic2DEZEggmanInstance.class && !o.isDestroyed())
                .map(Sonic2DEZEggmanInstance.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected one live DEZ Eggman parent"));
    }

    private static Sonic2DEZEggmanInstance.BarrierWall onlyLiveWall(ObjectManager objectManager) {
        List<Sonic2DEZEggmanInstance.BarrierWall> walls = liveWalls(objectManager);
        assertEquals(1, walls.size(), "expected exactly one live DEZ barrier wall");
        return walls.get(0);
    }

    private static int countLiveWalls(ObjectManager objectManager) {
        return liveWalls(objectManager).size();
    }

    private static List<Sonic2DEZEggmanInstance.BarrierWall> liveWalls(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(o -> o.getClass() == Sonic2DEZEggmanInstance.BarrierWall.class && !o.isDestroyed())
                .map(Sonic2DEZEggmanInstance.BarrierWall.class::cast)
                .toList();
    }

    private static Sonic2DEZEggmanInstance.BarrierWall barrierWallField(
            Sonic2DEZEggmanInstance parent) throws Exception {
        Field field = Sonic2DEZEggmanInstance.class.getDeclaredField("barrierWall");
        field.setAccessible(true);
        return (Sonic2DEZEggmanInstance.BarrierWall) field.get(parent);
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void assertTrueBooleanField(Object target, String name, String message) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        assertEquals(true, field.getBoolean(target), message);
    }

    private static void assertNoRegisteredS2DynamicCodec(String className) {
        boolean hasCodec = DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(className);
        assertFalse(hasCodec, className
                + " must restore through RewindRecreatable generic recreate, not an explicit S2 codec");
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
