package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.CnzWaterLevelCorkFloorInstance;
import com.openggf.game.sonic3k.objects.CorkFloorObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kCnzWaterLevelCorkFloorGraphRewind {
    private static final ObjectSpawn HELPER_SPAWN =
            new ObjectSpawn(0x2680, 0x05A0, Sonic3kObjectIds.CNZ_WATER_LEVEL_CORK_FLOOR, 0, 0, false, 42);
    private static final ObjectSpawn CORK_SPAWN =
            new ObjectSpawn(0x2680, 0x05A0, Sonic3kObjectIds.CORK_FLOOR, 1, 0, false, 43);
    private static final ObjectSpawn DIVERGENT_HELPER_SPAWN =
            new ObjectSpawn(0x2700, 0x05E0, Sonic3kObjectIds.CNZ_WATER_LEVEL_CORK_FLOOR, 0, 0, false, 44);
    private static final ObjectSpawn DIVERGENT_CORK_SPAWN =
            new ObjectSpawn(0x2700, 0x05E0, Sonic3kObjectIds.CORK_FLOOR, 1, 0, false, 45);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void cnzWaterLevelCorkFloorRestoresChildLinkWithoutDropsOrStaleRefs() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        CnzWaterLevelCorkFloorInstance sourceHelper = objectManager.createDynamicObject(
                () -> new CnzWaterLevelCorkFloorInstance(HELPER_SPAWN));
        CorkFloorObjectInstance sourceCork = objectManager.createDynamicObject(
                () -> new CorkFloorObjectInstance(CORK_SPAWN));
        sourceHelper.forceFloorReleasedForTest();
        sourceCork.forceBreakForTest();
        setObjectField(sourceHelper, "corkFloor", sourceCork);

        ObjectRefId helperId = objectId(objectManager, sourceHelper);
        ObjectRefId corkId = objectId(objectManager, sourceCork);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(sourceCork);
        objectManager.removeDynamicObject(sourceHelper);
        CnzWaterLevelCorkFloorInstance divergentHelper = objectManager.createDynamicObject(
                () -> new CnzWaterLevelCorkFloorInstance(DIVERGENT_HELPER_SPAWN));
        CorkFloorObjectInstance divergentCork = objectManager.createDynamicObject(
                () -> new CorkFloorObjectInstance(DIVERGENT_CORK_SPAWN));
        setObjectField(divergentHelper, "corkFloor", divergentCork);
        assertEquals(1, liveObjects(objectManager, CnzWaterLevelCorkFloorInstance.class).size(),
                "diverge step should leave one unrelated CNZ water-level cork helper before restore");
        assertEquals(1, liveObjects(objectManager, CorkFloorObjectInstance.class).size(),
                "diverge step should leave one unrelated cork floor before restore");

        registryFor(objectManager).restore(snapshot);

        CnzWaterLevelCorkFloorInstance restoredHelper =
                objectById(objectManager, CnzWaterLevelCorkFloorInstance.class, helperId);
        CorkFloorObjectInstance restoredCork =
                objectById(objectManager, CorkFloorObjectInstance.class, corkId);
        assertEquals(1, liveObjects(objectManager, CnzWaterLevelCorkFloorInstance.class).size(),
                "restore must keep exactly the captured CNZ water-level cork helper");
        assertEquals(1, liveObjects(objectManager, CorkFloorObjectInstance.class).size(),
                "restore must keep exactly the captured cork floor");
        assertNotSame(sourceHelper, restoredHelper, "restore must recreate the CNZ water-level cork helper");
        assertNotSame(sourceCork, restoredCork, "restore must recreate the cork floor");
        assertNotSame(divergentHelper, restoredHelper, "restore must drop the divergent helper");
        assertNotSame(divergentCork, restoredCork, "restore must drop the divergent cork floor");
        assertSame(restoredCork, readObjectField(restoredHelper, "corkFloor"),
                "CNZ helper corkFloor must resolve to the restored cork floor");
        assertNotSame(sourceCork, readObjectField(restoredHelper, "corkFloor"),
                "CNZ helper must not retain the stale pre-restore cork floor");
        assertTrue(readBooleanField(restoredHelper, "floorReleasedForTest"),
                "helper release latch must restore from compact state");
        assertTrue(restoredCork.isBroken(),
                "cork floor broken state must restore from compact state");
        assertEquals(CORK_SPAWN.x(), readIntField(restoredCork, "x"),
                "cork floor x must restore from spawn/compact state");
        assertEquals(CORK_SPAWN.y(), readIntField(restoredCork, "y"),
                "cork floor y must restore from spawn/compact state");
    }

    @Test
    void cnzWaterLevelCorkFloorFamilyUsesGenericRecreateWithoutExplicitDynamicCodecs() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzWaterLevelCorkFloorInstance.class),
                "CNZ water-level cork helper must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CorkFloorObjectInstance.class),
                "Cork floor must restore through generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        CnzWaterLevelCorkFloorInstance.class.getName()),
                "CNZ water-level cork helper must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(CorkFloorObjectInstance.class.getName()),
                "Cork floor must not keep an explicit S3K dynamic codec");
    }

    @Test
    void captureFailsWhenCnzHelperCorkFloorHasNoRewindIdentity() {
        Harness harness = Harness.create();
        CnzWaterLevelCorkFloorInstance helper = harness.objectManager().createDynamicObject(
                () -> new CnzWaterLevelCorkFloorInstance(HELPER_SPAWN));
        CorkFloorObjectInstance unmanagedCork = new CorkFloorObjectInstance(CORK_SPAWN);
        unmanagedCork.setServices(harness.services());
        setObjectField(helper, "corkFloor", unmanagedCork);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, registryFor(harness.objectManager())::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing cork-floor identity must fail loudly");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public int romZoneId() { return Sonic3kZoneIds.ZONE_CNZ; }
                @Override public int currentAct() { return 1; }
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
            objectManager.reset(camera.getX());
            return new Harness(objectManager, services);
        }
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

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS3kCnzWaterLevelCorkFloorGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance instance ? instance.getSlotIndex() : -1;
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

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
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
            @Override public short getX() { return 0x2600; }
            @Override public short getY() { return 0x0520; }
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x700; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
