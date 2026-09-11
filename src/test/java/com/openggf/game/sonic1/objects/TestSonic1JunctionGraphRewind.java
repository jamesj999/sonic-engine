package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1JunctionGraphRewind {

    private static final ObjectSpawn CAPTURED_SPAWN =
            new ObjectSpawn(0x1490, 0x0170, Sonic1ObjectIds.JUNCTION, 0x02, 0, false, 30);
    private static final ObjectSpawn REPLACEMENT_SPAWN =
            new ObjectSpawn(0x1600, 0x0170, Sonic1ObjectIds.JUNCTION, 0x00, 0, false, 31);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void junctionParentAndDisplayChildRestoreFreshWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        Sonic1JunctionObjectInstance beforeParent = objectManager.createDynamicObject(
                () -> new Sonic1JunctionObjectInstance(CAPTURED_SPAWN));
        Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance beforeChild =
                objectManager.createDynamicObject(
                        () -> new Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance(CAPTURED_SPAWN));
        writeObject(beforeParent, "childInstance", beforeChild);
        writeInt(beforeParent, "mappingFrame", 7);
        writeInt(beforeParent, "frameDirection", -1);
        writeBoolean(beforeParent, "switchReversed", true);
        writeInt(beforeParent, "frameTimer", 3);
        writeInt(beforeParent, "grabFrame", 5);

        ObjectRefId parentId = objectId(objectManager, beforeParent);
        ObjectRefId childId = objectId(objectManager, beforeChild);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(beforeChild);
        objectManager.removeDynamicObject(beforeParent);
        Sonic1JunctionObjectInstance replacementParent = objectManager.createDynamicObject(
                () -> new Sonic1JunctionObjectInstance(REPLACEMENT_SPAWN));
        Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance replacementChild =
                objectManager.createDynamicObject(
                        () -> new Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance(REPLACEMENT_SPAWN));
        writeObject(replacementParent, "childInstance", replacementChild);

        rewindRegistry.restore(snapshot);

        assertEquals(1, liveParents(objectManager).size(),
                "restore must keep exactly the captured junction parent");
        assertEquals(1, liveChildren(objectManager).size(),
                "restore must keep exactly the captured junction display child");
        Sonic1JunctionObjectInstance restoredParent =
                assertInstanceOf(Sonic1JunctionObjectInstance.class, objectWithId(objectManager, parentId));
        Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance restoredChild =
                assertInstanceOf(
                        Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance.class,
                        objectWithId(objectManager, childId));
        assertNotSame(beforeParent, restoredParent, "restore must recreate the removed parent");
        assertNotSame(beforeChild, restoredChild, "restore must recreate the removed display child");
        assertNotSame(replacementParent, restoredParent, "restore must drop unrelated post-snapshot parents");
        assertNotSame(replacementChild, restoredChild,
                "restore must drop unrelated post-snapshot display children");
        assertSame(restoredChild, readObject(restoredParent, "childInstance"),
                "parent childInstance must point to the restored display child");
        assertEquals(7, readInt(restoredParent, "mappingFrame"), "mappingFrame scalar must restore exactly");
        assertEquals(-1, readInt(restoredParent, "frameDirection"),
                "frameDirection scalar must restore exactly");
        assertTrue(readBoolean(restoredParent, "switchReversed"),
                "switchReversed scalar must restore exactly");
        assertEquals(3, readInt(restoredParent, "frameTimer"), "frameTimer scalar must restore exactly");
        assertEquals(5, readInt(restoredParent, "grabFrame"), "grabFrame scalar must restore exactly");
        assertEquals(CAPTURED_SPAWN.subtype() & 0xFF, readInt(restoredParent, "switchIndex"),
                "switchIndex final scalar must be derived from captured spawn subtype");

        restoredParent.update(1, null);
        assertEquals(1, liveChildren(objectManager).size(),
                "restored parent update must not spawn a duplicate display child");
        assertSame(restoredChild, readObject(restoredParent, "childInstance"),
                "update must retain the restored child back-reference");
    }

    @Test
    void junctionChildBackrefStillRequiresRewindIdentity() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        Sonic1JunctionObjectInstance parent = objectManager.createDynamicObject(
                () -> new Sonic1JunctionObjectInstance(CAPTURED_SPAWN));
        Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance unmanagedChild =
                new Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance(CAPTURED_SPAWN);
        writeObject(parent, "childInstance", unmanagedChild);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> registryFor(objectManager).capture(),
                "required junction child backrefs must fail loudly when the target has no rewind identity");
        assertTrue(thrown.getMessage().contains("no registered id for object reference"));
    }

    @Test
    void junctionParentAndDisplayChildUseGenericRecreateWithoutExplicitDynamicCodecs() {
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1JunctionObjectInstance.class.getName()),
                "junction parent must restore through generic recreate, not a dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance.class.getName()),
                "junction display child must restore through generic recreate, not a dynamic codec");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            Sonic1SwitchManager switches = new Sonic1SwitchManager();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public <T> T gameService(Class<T> type) {
                    if (type == Sonic1SwitchManager.class) {
                        return type.cast(switches);
                    }
                    return null;
                }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(), new Sonic1ObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
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

    private static ObjectInstance objectWithId(ObjectManager objectManager, ObjectRefId id) {
        List<ObjectInstance> matches = objectManager.getActiveObjects().stream()
                .filter(object -> id.equals(objectManager.captureIdentityContext().requireIdentityTable().idFor(object)))
                .toList();
        assertEquals(1, matches.size(), "expected one live object for rewind id " + id);
        return matches.getFirst();
    }

    private static List<Sonic1JunctionObjectInstance> liveParents(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == Sonic1JunctionObjectInstance.class)
                .map(Sonic1JunctionObjectInstance.class::cast)
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static List<Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance> liveChildren(
            ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass()
                        == Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance.class)
                .map(Sonic1JunctionObjectInstance.Sonic1JunctionChildInstance.class::cast)
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static int readInt(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBoolean(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Object readObject(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void writeInt(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void writeBoolean(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void writeObject(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Walk superclass chain.
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
