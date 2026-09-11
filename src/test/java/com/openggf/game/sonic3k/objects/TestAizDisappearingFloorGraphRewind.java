package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAizDisappearingFloorGraphRewind {

    private static final ObjectSpawn FLOOR_SPAWN =
            new ObjectSpawn(0x0180, 0x01C0, Sonic3kObjectIds.AIZ_DISAPPEARING_FLOOR,
                    0, 0x24, false, 10);
    private static final ObjectSpawn BORDER_SPAWN =
            new ObjectSpawn(0x0180, 0x01C0, Sonic3kObjectIds.AIZ_DISAPPEARING_FLOOR,
                    0, 0, false, 11);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x400, 0x300, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void disappearingFloorGraphRestoresFreshWithExactParentRelinkAndScalarState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        AizDisappearingFloorObjectInstance sourceParent =
                objectManager.createDynamicObject(
                        () -> new AizDisappearingFloorObjectInstance(FLOOR_SPAWN));
        AizDisappearingFloorObjectInstance.BorderChild sourceChild =
                objectManager.createDynamicObject(
                        () -> new AizDisappearingFloorObjectInstance.BorderChild(
                                BORDER_SPAWN, sourceParent));
        seedState(sourceParent, sourceChild);

        ObjectRefId parentId = objectId(objectManager, sourceParent);
        ObjectRefId childId = objectId(objectManager, sourceChild);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceChild);
        objectManager.removeDynamicObject(sourceParent);
        AizDisappearingFloorObjectInstance replacementParent =
                objectManager.createDynamicObject(() -> new AizDisappearingFloorObjectInstance(
                        new ObjectSpawn(0x0240, 0x01F0, Sonic3kObjectIds.AIZ_DISAPPEARING_FLOOR,
                                0, 0, false, 12)));
        AizDisappearingFloorObjectInstance.BorderChild replacementChild =
                objectManager.createDynamicObject(
                        () -> new AizDisappearingFloorObjectInstance.BorderChild(
                                new ObjectSpawn(0x0240, 0x01F0,
                                        Sonic3kObjectIds.AIZ_DISAPPEARING_FLOOR, 0, 0, false, 13),
                                replacementParent));

        rewindRegistry.restore(snapshot);

        assertEquals(1, liveObjects(objectManager, AizDisappearingFloorObjectInstance.class).size(),
                "restore must keep exactly the captured disappearing-floor parent");
        assertEquals(1, liveObjects(objectManager,
                        AizDisappearingFloorObjectInstance.BorderChild.class).size(),
                "restore must keep exactly the captured disappearing-floor border child");

        AizDisappearingFloorObjectInstance restoredParent =
                objectById(objectManager, AizDisappearingFloorObjectInstance.class, parentId);
        AizDisappearingFloorObjectInstance.BorderChild restoredChild =
                objectById(objectManager, AizDisappearingFloorObjectInstance.BorderChild.class, childId);

        assertNotSame(sourceParent, restoredParent, "restore must recreate the parent");
        assertNotSame(sourceChild, restoredChild, "restore must recreate the child");
        assertNotSame(replacementParent, restoredParent, "restore must drop the replacement parent");
        assertNotSame(replacementChild, restoredChild, "restore must drop the replacement child");
        assertSame(restoredParent, readObjectField(restoredChild, "parent"),
                "border child must point at the restored parent, not the stale pre-restore parent");
        assertNotSame(sourceParent, readObjectField(restoredChild, "parent"),
                "border child must not retain the stale pre-restore parent");
        assertNotSame(replacementParent, readObjectField(restoredChild, "parent"),
                "border child must not point at the divergent replacement parent");

        assertEquals(5, readIntField(restoredParent, "mappingFrame"));
        assertEquals(1, readIntField(restoredParent, "animIndex"));
        assertEquals(7, readIntField(restoredParent, "animStep"));
        assertEquals(0x33, readIntField(restoredParent, "frameTimer"));
        assertEquals(true, readBooleanField(restoredParent, "childSpawned"));
        assertEquals(2, readIntField(restoredChild, "frame"));
        assertEquals(0x12, readIntField(restoredChild, "timer"));

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(parentId, restoredTable.idFor(restoredParent),
                "restored parent must retain its captured rewind identity");
        assertEquals(childId, restoredTable.idFor(restoredChild),
                "restored child must retain its captured rewind identity");
    }

    @Test
    void disappearingFloorGraphClassesUseRewindRecreatableWithoutExplicitCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(AizDisappearingFloorObjectInstance.class),
                "AizDisappearingFloorObjectInstance must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(
                        AizDisappearingFloorObjectInstance.BorderChild.class),
                "BorderChild must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AizDisappearingFloorObjectInstance.class.getName()),
                "AizDisappearingFloorObjectInstance must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AizDisappearingFloorObjectInstance.BorderChild.class.getName()),
                "BorderChild must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
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
            return new Harness(objectManager);
        }
    }

    private static void seedState(
            AizDisappearingFloorObjectInstance parent,
            AizDisappearingFloorObjectInstance.BorderChild child) {
        setIntField(parent, "mappingFrame", 5);
        setIntField(parent, "animIndex", 1);
        setIntField(parent, "animStep", 7);
        setIntField(parent, "frameTimer", 0x33);
        setBooleanField(parent, "childSpawned", true);
        setIntField(child, "frame", 2);
        setIntField(child, "timer", 0x12);
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        return liveObjects(objectManager, type).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static <T extends ObjectInstance> List<T> liveObjects(
            ObjectManager objectManager,
            Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestAizDisappearingFloorGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
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
            @Override public short getWidth() { return 0x400; }
            @Override public short getHeight() { return 0x300; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
