package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kCorkeyNozzleGraphRewind {
    private static final ObjectSpawn CORKEY_SPAWN =
            new ObjectSpawn(0x0200, 0x0100, Sonic3kObjectIds.CORKEY, 0, 0, false, 80);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x800, 0x500, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void nozzleRestoresFreshAndRelinksToRestoredCorkeyParent() {
        Harness harness = Harness.create(List.of(CORKEY_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        CorkeyBadnikInstance parent = only(objectManager, CorkeyBadnikInstance.class);
        CorkeyBadnikInstance.CorkeyNozzleChild nozzle = objectManager.createDynamicObject(
                () -> new CorkeyBadnikInstance.CorkeyNozzleChild(
                        new ObjectSpawn(0x01F4, 0x0124, Sonic3kObjectIds.CORKEY, 0, 0, false, 81),
                        parent));
        setNozzleState(nozzle, 0x01F4, 0x0124, "FIRING", 3, 5, 6, 1, 2, 0);

        ObjectRefId parentId = objectId(objectManager, parent);
        ObjectRefId nozzleId = objectId(objectManager, nozzle);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(nozzle);
        CorkeyBadnikInstance divergentParent = objectManager.createDynamicObject(
                () -> new CorkeyBadnikInstance(new ObjectSpawn(
                        0x0400, 0x0180, Sonic3kObjectIds.CORKEY, 0, 0, false, 82)));
        CorkeyBadnikInstance.CorkeyNozzleChild divergentNozzle = objectManager.createDynamicObject(
                () -> new CorkeyBadnikInstance.CorkeyNozzleChild(
                        new ObjectSpawn(0x0400, 0x018C, Sonic3kObjectIds.CORKEY, 0, 0, false, 83),
                        divergentParent));

        rewindRegistry.restore(snapshot);

        assertEquals(1, liveObjects(objectManager, CorkeyBadnikInstance.class).size(),
                "restore must keep exactly the captured Corkey parent");
        assertEquals(1, liveObjects(objectManager, CorkeyBadnikInstance.CorkeyNozzleChild.class).size(),
                "restore must recreate exactly the captured Corkey nozzle");

        CorkeyBadnikInstance restoredParent =
                objectById(objectManager, CorkeyBadnikInstance.class, parentId);
        CorkeyBadnikInstance.CorkeyNozzleChild restoredNozzle =
                objectById(objectManager, CorkeyBadnikInstance.CorkeyNozzleChild.class, nozzleId);

        assertNotSame(parent, restoredParent, "restore must recreate the Corkey parent");
        assertNotSame(nozzle, restoredNozzle, "restore must recreate the nozzle");
        assertNotSame(divergentNozzle, restoredNozzle, "restore must drop divergent post-snapshot nozzles");

        assertSame(restoredParent, readObjectField(restoredNozzle, "parent"),
                "nozzle parent must relink to the restored Corkey instance");
        assertNotSame(parent, readObjectField(restoredNozzle, "parent"),
                "nozzle must not retain the stale pre-restore parent");
        assertNotSame(divergentParent, readObjectField(restoredNozzle, "parent"),
                "nozzle must not relink to a divergent parent");

        assertNozzleState(restoredNozzle, 0x01F4, 0x0124, "FIRING", 3, 5, 6, 1, 2, 0);
    }

    @Test
    void nozzleUsesRewindRecreatableWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(
                        CorkeyBadnikInstance.CorkeyNozzleChild.class),
                "Corkey nozzle must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        CorkeyBadnikInstance.CorkeyNozzleChild.class.getName()),
                "Corkey nozzle must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public int romZoneId() { return Sonic3kZoneIds.ZONE_LBZ; }
                @Override public int featureZoneId() { return Sonic3kZoneIds.ZONE_LBZ; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new LbzTestRegistry(),
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

    private static final class LbzTestRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_LBZ;
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

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> live = liveObjects(objectManager, type);
        assertEquals(1, live.size(), "expected exactly one live " + type.getSimpleName());
        return live.getFirst();
    }

    private static <T extends ObjectInstance> List<T> liveObjects(
            ObjectManager objectManager,
            Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS3kCorkeyNozzleGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    private static void setNozzleState(
            CorkeyBadnikInstance.CorkeyNozzleChild nozzle,
            int currentX,
            int currentY,
            String stateName,
            int mappingFrame,
            int rawDelay,
            int rawLoopCounter,
            int rawFrameIndex,
            int rawFrameTimer,
            int retractTimer) {
        setIntField(nozzle, "currentX", currentX);
        setIntField(nozzle, "currentY", currentY);
        setEnumField(nozzle, "state", stateName);
        setIntField(nozzle, "mappingFrame", mappingFrame);
        setIntField(nozzle, "rawDelay", rawDelay);
        setIntField(nozzle, "rawLoopCounter", rawLoopCounter);
        setIntField(nozzle, "rawFrameIndex", rawFrameIndex);
        setIntField(nozzle, "rawFrameTimer", rawFrameTimer);
        setIntField(nozzle, "retractTimer", retractTimer);
    }

    private static void assertNozzleState(
            CorkeyBadnikInstance.CorkeyNozzleChild nozzle,
            int currentX,
            int currentY,
            String stateName,
            int mappingFrame,
            int rawDelay,
            int rawLoopCounter,
            int rawFrameIndex,
            int rawFrameTimer,
            int retractTimer) {
        assertEquals(currentX, readIntField(nozzle, "currentX"), "currentX must restore exactly");
        assertEquals(currentY, readIntField(nozzle, "currentY"), "currentY must restore exactly");
        assertEquals(stateName, readObjectField(nozzle, "state").toString(), "state must restore exactly");
        assertEquals(mappingFrame, readIntField(nozzle, "mappingFrame"), "mappingFrame must restore exactly");
        assertEquals(rawDelay, readIntField(nozzle, "rawDelay"), "rawDelay must restore exactly");
        assertEquals(rawLoopCounter, readIntField(nozzle, "rawLoopCounter"),
                "rawLoopCounter must restore exactly");
        assertEquals(rawFrameIndex, readIntField(nozzle, "rawFrameIndex"),
                "rawFrameIndex must restore exactly");
        assertEquals(rawFrameTimer, readIntField(nozzle, "rawFrameTimer"),
                "rawFrameTimer must restore exactly");
        assertEquals(retractTimer, readIntField(nozzle, "retractTimer"),
                "retractTimer must restore exactly");
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setEnumField(Object target, String fieldName, String value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.set(target, Enum.valueOf((Class<? extends Enum>) field.getType(), value));
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
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x500; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
