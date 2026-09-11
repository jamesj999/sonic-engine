package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.HCZHandLauncherObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kHczHandLauncherGraphRewind {
    private static final String ARM_CLASS =
            "com.openggf.game.sonic3k.objects.HCZHandLauncherObjectInstance$HandLauncherArmChild";
    private static final ObjectSpawn LAUNCHER_SPAWN =
            new ObjectSpawn(0x1600, 0x0480, Sonic3kObjectIds.HCZ_HAND_LAUNCHER, 0, 0, false, 12);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void handLauncherArmRestoresFreshRelinkParentAndDoesNotDuplicate() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        HCZHandLauncherObjectInstance sourceLauncher = objectManager.createDynamicObject(
                () -> new HCZHandLauncherObjectInstance(LAUNCHER_SPAWN));
        sourceLauncher.update(0, null);
        ObjectInstance sourceArm = onlyArm(objectManager);
        ObjectRefId launcherId = objectId(objectManager, sourceLauncher);

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceArm);
        ObjectInstance divergentArm = objectManager.createDynamicObject(
                () -> constructArm(sourceLauncher));
        assertEquals(1, liveArms(objectManager).size(),
                "diverge step should leave one unrelated hand-launcher arm before restore");

        rewindRegistry.restore(snapshot);

        HCZHandLauncherObjectInstance restoredLauncher =
                objectById(objectManager, HCZHandLauncherObjectInstance.class, launcherId);
        ObjectInstance restoredArm = onlyArm(objectManager);
        assertNotSame(sourceLauncher, restoredLauncher,
                "restore must recreate the HCZ hand launcher parent");
        assertNotSame(sourceArm, restoredArm,
                "restore must recreate the HCZ hand-launcher arm");
        assertNotSame(divergentArm, restoredArm,
                "restore must drop the divergent hand-launcher arm");
        assertSame(restoredLauncher, readObjectField(restoredArm, "parent"),
                "hand-launcher arm parent must resolve to the restored launcher");
        assertSame(restoredArm, readObjectField(restoredLauncher, "armChild"),
                "restored HCZ hand launcher must point at the restored arm child");

        restoredLauncher.update(1, null);
        assertEquals(1, liveArms(objectManager).size(),
                "post-restore update must not spawn a duplicate hand-launcher arm");
    }

    @Test
    void handLauncherFamilyUsesGenericRecreateWithoutExplicitS3kCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(HCZHandLauncherObjectInstance.class),
                "HCZ hand launcher must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(ARM_CLASS)),
                "HCZ hand-launcher arm must restore through generic graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        HCZHandLauncherObjectInstance.class.getName()),
                "HCZ hand launcher must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(ARM_CLASS),
                "HCZ hand-launcher arm must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(() -> null, List::of);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public ObjectPlayerQuery playerQuery() { return playerQuery; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    new Sonic3kObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager, services);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectInstance constructArm(HCZHandLauncherObjectInstance parent) {
        try {
            Class<?> armType = Class.forName(ARM_CLASS);
            Constructor<?> ctor = armType.getDeclaredConstructor(ObjectSpawn.class, HCZHandLauncherObjectInstance.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(LAUNCHER_SPAWN, parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct HCZ hand-launcher arm", e);
        }
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass());
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

    private static ObjectInstance onlyArm(ObjectManager objectManager) {
        List<ObjectInstance> arms = liveArms(objectManager);
        assertEquals(1, arms.size(), "expected exactly one live HCZ hand-launcher arm");
        return arms.getFirst();
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
