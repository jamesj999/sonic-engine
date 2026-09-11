package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.LbzCupElevatorInstance;
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

class TestS3kLbzCupElevatorGraphRewind {
    private static final String ATTACH_CLASS =
            "com.openggf.game.sonic3k.objects.LbzCupElevatorInstance$AttachChild";
    private static final String BASE_CLASS =
            "com.openggf.game.sonic3k.objects.LbzCupElevatorInstance$BaseChild";
    private static final ObjectSpawn CUP_SPAWN =
            new ObjectSpawn(0x1800, 0x0600, Sonic3kObjectIds.LBZ_CUP_ELEVATOR, 0x03, 0, false, 15);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void cupElevatorChildrenRestoreFreshRelinkParentAndDoNotDuplicate() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        LbzCupElevatorInstance sourceElevator = objectManager.createDynamicObject(
                () -> new LbzCupElevatorInstance(CUP_SPAWN));
        sourceElevator.update(0, null);
        ObjectInstance sourceAttach = onlyChild(objectManager, ATTACH_CLASS);
        ObjectInstance sourceBase = onlyChild(objectManager, BASE_CLASS);
        ObjectRefId elevatorId = objectId(objectManager, sourceElevator);

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceAttach);
        objectManager.removeDynamicObject(sourceBase);
        ObjectInstance divergentAttach = objectManager.createDynamicObject(
                () -> constructChild(ATTACH_CLASS, sourceElevator));
        ObjectInstance divergentBase = objectManager.createDynamicObject(
                () -> constructChild(BASE_CLASS, sourceElevator));
        assertEquals(1, liveChildren(objectManager, ATTACH_CLASS).size(),
                "diverge step should leave one unrelated cup attach child before restore");
        assertEquals(1, liveChildren(objectManager, BASE_CLASS).size(),
                "diverge step should leave one unrelated cup base child before restore");

        rewindRegistry.restore(snapshot);

        LbzCupElevatorInstance restoredElevator =
                objectById(objectManager, LbzCupElevatorInstance.class, elevatorId);
        ObjectInstance restoredAttach = onlyChild(objectManager, ATTACH_CLASS);
        ObjectInstance restoredBase = onlyChild(objectManager, BASE_CLASS);
        assertNotSame(sourceElevator, restoredElevator,
                "restore must recreate the LBZ cup elevator parent");
        assertNotSame(sourceAttach, restoredAttach,
                "restore must recreate the LBZ cup attach child");
        assertNotSame(sourceBase, restoredBase,
                "restore must recreate the LBZ cup base child");
        assertNotSame(divergentAttach, restoredAttach,
                "restore must drop the divergent cup attach child");
        assertNotSame(divergentBase, restoredBase,
                "restore must drop the divergent cup base child");
        assertSame(restoredElevator, readObjectField(restoredAttach, "parent"),
                "cup attach child parent must resolve to the restored LBZ cup elevator");
        assertSame(restoredElevator, readObjectField(restoredBase, "parent"),
                "cup base child parent must resolve to the restored LBZ cup elevator");
        assertSame(restoredAttach, readObjectField(restoredElevator, "attachChild"),
                "restored LBZ cup elevator must point at the restored attach child");
        assertSame(restoredBase, readObjectField(restoredElevator, "baseChild"),
                "restored LBZ cup elevator must point at the restored base child");

        restoredElevator.update(1, null);
        assertEquals(1, liveChildren(objectManager, ATTACH_CLASS).size(),
                "post-restore update must not spawn a duplicate cup attach child");
        assertEquals(1, liveChildren(objectManager, BASE_CLASS).size(),
                "post-restore update must not spawn a duplicate cup base child");
    }

    @Test
    void cupElevatorFamilyUsesGenericRecreateWithoutExplicitS3kCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(LbzCupElevatorInstance.class),
                "LBZ cup elevator must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(ATTACH_CLASS)),
                "LBZ cup attach child must restore through generic graph recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(BASE_CLASS)),
                "LBZ cup base child must restore through generic graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        LbzCupElevatorInstance.class.getName()),
                "LBZ cup elevator must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(ATTACH_CLASS),
                "LBZ cup attach child must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(BASE_CLASS),
                "LBZ cup base child must not keep an explicit S3K dynamic codec");
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

    private static ObjectInstance constructChild(String childClass, LbzCupElevatorInstance parent) {
        try {
            Class<?> childType = Class.forName(childClass);
            Constructor<?> ctor = childType.getDeclaredConstructor(LbzCupElevatorInstance.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct LBZ cup child " + childClass, e);
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

    private static ObjectInstance onlyChild(ObjectManager objectManager, String childClass) {
        List<ObjectInstance> children = liveChildren(objectManager, childClass);
        assertEquals(1, children.size(), "expected exactly one live " + childClass);
        return children.getFirst();
    }

    private static List<ObjectInstance> liveChildren(ObjectManager objectManager, String childClass) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(childClass))
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
