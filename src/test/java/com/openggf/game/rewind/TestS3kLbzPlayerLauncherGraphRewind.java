package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.LbzPlayerLauncherInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kLbzPlayerLauncherGraphRewind {
    private static final String ARM_CLASS =
            "com.openggf.game.sonic3k.objects.LbzPlayerLauncherInstance$LauncherArmChild";
    private static final ObjectSpawn LAUNCHER_SPAWN =
            new ObjectSpawn(0x1200, 0x0520, Sonic3kObjectIds.LBZ_PLAYER_LAUNCHER, 0x02, 1, false, 15);
    private static final ObjectSpawn ARM_SPAWN =
            new ObjectSpawn(0x1200, 0x0520, Sonic3kObjectIds.LBZ_PLAYER_LAUNCHER, 0x02, 1, false, 16);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void launcherArmRestoresFreshRelinkParentAndDoesNotDuplicate() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        LbzPlayerLauncherInstance sourceLauncher = objectManager.createDynamicObject(
                () -> new LbzPlayerLauncherInstance(LAUNCHER_SPAWN));
        ObjectInstance sourceArm = objectManager.createDynamicObject(
                () -> constructArm(sourceLauncher, true));
        setIntField(sourceArm, "routine", 2);
        setIntField(sourceArm, "angle", 0xA0);
        ObjectRefId launcherId = objectId(objectManager, sourceLauncher);

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceArm);
        ObjectInstance divergentArm = objectManager.createDynamicObject(
                () -> constructArm(sourceLauncher, false));
        assertEquals(1, liveArms(objectManager).size(),
                "diverge step should leave one unrelated launcher arm before restore");

        rewindRegistry.restore(snapshot);

        LbzPlayerLauncherInstance restoredLauncher =
                objectById(objectManager, LbzPlayerLauncherInstance.class, launcherId);
        List<ObjectInstance> restoredArms = liveArms(objectManager);
        assertEquals(1, restoredArms.size(),
                "restore must keep exactly the captured launcher arm");
        ObjectInstance restoredArm = restoredArms.getFirst();
        assertNotSame(sourceLauncher, restoredLauncher,
                "restore must recreate the LBZ player launcher parent");
        assertNotSame(sourceArm, restoredArm, "restore must recreate the launcher arm");
        assertNotSame(divergentArm, restoredArm, "restore must drop the divergent launcher arm");
        assertSame(restoredLauncher, readObjectField(restoredArm, "parent"),
                "launcher arm parent must resolve to the restored LBZ player launcher");
        assertNotSame(sourceLauncher, readObjectField(restoredArm, "parent"),
                "launcher arm must not retain the stale pre-restore parent");
        assertEquals(2, readIntField(restoredArm, "routine"));
        assertEquals(0xA0, readIntField(restoredArm, "angle"));
    }

    @Test
    void launcherArmUsesGenericRecreateWithoutExplicitS3kCodec() throws Exception {
        Class<?> armType = Class.forName(ARM_CLASS);
        assertTrue(RewindRecreatable.class.isAssignableFrom(armType),
                "LBZ player launcher arm must restore through RewindRecreatable graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(ARM_CLASS),
                "LBZ player launcher arm must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    null,
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectInstance constructArm(LbzPlayerLauncherInstance parent, boolean nativeP1) {
        try {
            Class<?> armType = Class.forName(ARM_CLASS);
            Constructor<?> ctor = armType.getDeclaredConstructor(
                    ObjectSpawn.class, LbzPlayerLauncherInstance.class, boolean.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(ARM_SPAWN, parent, nativeP1);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct LBZ player launcher arm", e);
        }
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertTrue(id != null, "ObjectManager identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager,
            Class<T> type,
            ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow();
    }

    private static List<ObjectInstance> liveArms(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(ARM_CLASS))
                .filter(object -> !object.isDestroyed())
                .toList();
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

    private static Object readObjectField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.get(target);
    }

    private static int readIntField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.getInt(target);
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = field(target, name);
        field.setInt(target, value);
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
