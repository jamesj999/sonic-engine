package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2SwingingPlatformGraphRewind {
    private static final String DISPLAY_CHILD_CLASS =
            "com.openggf.game.sonic2.objects.SwingingPlatformObjectInstance$SwingingPlatformDisplayChild";
    private static final ObjectSpawn PARENT_SPAWN =
            new ObjectSpawn(0x260, 0x150, Sonic2ObjectIds.SWINGING_PLATFORM, 0x04, 0, false, 0, 123);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void swingingPlatformGraphRestoresDisplayChildWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create(List.of(PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        objectManager.update(0, null, null, 0, false);

        SwingingPlatformObjectInstance beforeParent = parentPlatform(objectManager);
        ObjectInstance beforeChild = displayChild(objectManager);
        assertSame(beforeChild, readObjectField(beforeParent, "displayChild"),
                "fixture parent must link to the spawned display child");
        assertSame(beforeParent, readObjectField(beforeChild, "parent"),
                "fixture display child must link back to the parent");
        setIntField(beforeParent, "x", PARENT_SPAWN.x() + 17);
        setIntField(beforeParent, "y", PARENT_SPAWN.y() + 29);
        setIntField(beforeParent, "displayChildX", PARENT_SPAWN.x() + 19);
        setIntField(beforeParent, "displayChildY", PARENT_SPAWN.y() + 71);
        setIntField(beforeChild, "x", PARENT_SPAWN.x() + 19);
        setIntField(beforeChild, "y", PARENT_SPAWN.y() + 71);

        ObjectRefId parentId = objectId(objectManager, beforeParent);
        ObjectRefId childId = objectId(objectManager, beforeChild);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(beforeChild);

        rewindRegistry.restore(snapshot);

        SwingingPlatformObjectInstance restoredParent =
                objectById(objectManager, SwingingPlatformObjectInstance.class, parentId);
        ObjectInstance restoredChild = childById(objectManager, childId);

        assertEquals(1, liveParents(objectManager).size(),
                "restore must keep exactly the captured Swinging Platform parent");
        assertEquals(1, liveDisplayChildren(objectManager).size(),
                "restore must keep exactly the captured Swinging Platform display child");
        assertNotSame(beforeParent, restoredParent, "restore must recreate the placed parent");
        assertNotSame(beforeChild, restoredChild, "restore must recreate the display child");
        assertSame(restoredChild, readObjectField(restoredParent, "displayChild"),
                "restored parent must point at the restored display child");
        assertSame(restoredParent, readObjectField(restoredChild, "parent"),
                "restored display child must point back at the restored parent");
        assertNotSame(beforeChild, readObjectField(restoredParent, "displayChild"),
                "restored parent must not retain a stale pre-restore child ref");
        assertNotSame(beforeParent, readObjectField(restoredChild, "parent"),
                "restored child must not retain a stale pre-restore parent ref");

        assertEquals(PARENT_SPAWN.x() + 17, restoredParent.getX(),
                "parent x scalar must round-trip");
        assertEquals(PARENT_SPAWN.y() + 29, restoredParent.getY(),
                "parent y scalar must round-trip");
        assertEquals(PARENT_SPAWN.x() + 19, restoredChild.getX(),
                "child x scalar must round-trip");
        assertEquals(PARENT_SPAWN.y() + 71, restoredChild.getY(),
                "child y scalar must round-trip");
    }

    @Test
    void swingingPlatformDisplayChildStillRequiresRewindIdentity() {
        Harness harness = Harness.create(List.of(PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.update(0, null, null, 0, false);
        SwingingPlatformObjectInstance parent = parentPlatform(objectManager);
        ObjectInstance unmanagedChild = newDisplayChild(parent);
        setObjectField(parent, "displayChild", unmanagedChild);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, registryFor(objectManager)::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing non-null display-child identity must fail loudly");
    }

    @Test
    void swingingPlatformUsesRewindRecreatableWithoutExplicitDynamicCodec() throws ClassNotFoundException {
        Class<?> childClass = Class.forName(DISPLAY_CHILD_CLASS);
        assertTrue(RewindRecreatable.class.isAssignableFrom(SwingingPlatformObjectInstance.class),
                "Swinging Platform must restore through RewindRecreatable");
        assertTrue(RewindRecreatable.class.isAssignableFrom(childClass),
                "Swinging Platform display child must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        SwingingPlatformObjectInstance.class.getName()),
                "Swinging Platform must not use an explicit S2 dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(DISPLAY_CHILD_CLASS),
                "Swinging Platform display child must not use an explicit S2 dynamic codec");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new Sonic2ObjectRegistry(),
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

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        return liveParents(objectManager).stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static ObjectInstance childById(ObjectManager objectManager, ObjectRefId id) {
        return liveDisplayChildren(objectManager).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored child " + id));
    }

    private static SwingingPlatformObjectInstance parentPlatform(ObjectManager objectManager) {
        List<SwingingPlatformObjectInstance> parents = liveParents(objectManager);
        assertEquals(1, parents.size(),
                "expected one Swinging Platform parent: " + describeObjects(objectManager));
        return parents.getFirst();
    }

    private static ObjectInstance displayChild(ObjectManager objectManager) {
        List<ObjectInstance> children = liveDisplayChildren(objectManager);
        assertEquals(1, children.size(),
                "expected one Swinging Platform display child: " + describeObjects(objectManager));
        return children.getFirst();
    }

    private static List<SwingingPlatformObjectInstance> liveParents(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(SwingingPlatformObjectInstance.class::isInstance)
                .map(SwingingPlatformObjectInstance.class::cast)
                .filter(parent -> !parent.isDestroyed())
                .toList();
    }

    private static List<ObjectInstance> liveDisplayChildren(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(DISPLAY_CHILD_CLASS))
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static String describeObjects(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .map(object -> object.getClass().getSimpleName()
                        + "@(" + object.getX() + "," + object.getY() + ")"
                        + ", destroyed=" + object.isDestroyed())
                .toList()
                .toString();
    }

    private static ObjectInstance newDisplayChild(SwingingPlatformObjectInstance parent) {
        try {
            Class<?> childClass = Class.forName(DISPLAY_CHILD_CLASS);
            Constructor<?> constructor = childClass.getDeclaredConstructor(SwingingPlatformObjectInstance.class);
            constructor.setAccessible(true);
            return assertInstanceOf(ObjectInstance.class, constructor.newInstance(parent));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
