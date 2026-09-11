package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2PointPokeyRewind {

    private static final ObjectSpawn POKEY_SPAWN =
            new ObjectSpawn(0x1A40, 0x0350, Sonic2ObjectIds.POINT_POKEY, 0x01, 0, false, 611);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x4000, 0x1000, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void pointPokeyRestoresFreshWithoutDropsAndPreservesCageState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        PointPokeyObjectInstance pokey = objectManager.createDynamicObject(
                () -> new PointPokeyObjectInstance(POKEY_SPAWN, "PointPokey"));
        seedState(pokey);

        ObjectRefId pokeyId = objectId(objectManager, pokey);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(pokey);
        PointPokeyObjectInstance replacement = objectManager.createDynamicObject(
                () -> new PointPokeyObjectInstance(
                        new ObjectSpawn(0x0040, 0x0100, Sonic2ObjectIds.POINT_POKEY, 0, 0, false, 612),
                        "PointPokey"));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, PointPokeyObjectInstance.class).size(),
                "restore must keep exactly the captured Point Pokey");
        PointPokeyObjectInstance restored = objectById(objectManager, PointPokeyObjectInstance.class, pokeyId);
        assertNotSame(pokey, restored, "restore must recreate the Point Pokey");
        assertNotSame(replacement, restored, "restore must drop the divergent Point Pokey");

        assertEquals(POKEY_SPAWN.x(), restored.getX(), "Point Pokey X must rebuild from spawn");
        assertEquals(POKEY_SPAWN.y(), restored.getY(), "Point Pokey Y must rebuild from spawn");
        assertTrue(readBooleanField(restored, "isLinkedMode"),
                "Point Pokey linked mode must rebuild from subtype");
        assertEquals(3, readIntField(restored, "playerState"),
                "Point Pokey player state must restore exactly");
        assertEquals(17, readIntField(restored, "countdown"),
                "Point Pokey countdown must restore exactly");
        assertEquals(1, readIntField(restored, "mappingFrame"),
                "Point Pokey mapping frame must restore exactly");
        assertEquals(30, readIntField(restored, "slotReward"),
                "Point Pokey slot reward must restore exactly");
        assertEquals(7, readIntField(restored, "prizesToSpawn"),
                "Point Pokey prize count must restore exactly");
        assertEquals(0x55, readIntField(restored, "prizeAngle"),
                "Point Pokey prize angle must restore exactly");
        assertEquals(2, readActivePrizeCount(restored),
                "Point Pokey active prize count must restore exactly");
        assertEquals(222, readIntField(restored, "lastContactFrame"),
                "Point Pokey contact frame must restore exactly");
        assertEquals(1, readIntField(restored, "animationTimer"),
                "Point Pokey animation timer must restore exactly");
        assertTrue(readBooleanField(restored, "playerOccupied"),
                "Point Pokey occupied flag must restore exactly");
        assertEquals(12, readIntField(restored, "slotDisplayOffsetX"),
                "Point Pokey slot display X offset must restore exactly");
        assertEquals(-20, readIntField(restored, "slotDisplayOffsetY"),
                "Point Pokey slot display Y offset must restore exactly");
        assertTrue(readBooleanField(restored, "slotDisplayOffsetCalculated"),
                "Point Pokey slot display cache flag must restore exactly");
        assertNull(readObjectField(restored, "slotMachineManager"),
                "Point Pokey slot manager remains a transient service lookup");
    }

    @Test
    void pointPokeyUsesRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(PointPokeyObjectInstance.class),
                "PointPokeyObjectInstance must restore through generic recreate");
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

    private static void seedState(PointPokeyObjectInstance pokey) {
        setIntField(pokey, "playerState", 3);
        setIntField(pokey, "countdown", 17);
        setIntField(pokey, "mappingFrame", 1);
        setIntField(pokey, "slotReward", 30);
        setIntField(pokey, "prizesToSpawn", 7);
        setIntField(pokey, "prizeAngle", 0x55);
        ((int[]) readObjectField(pokey, "activePrizeCount"))[0] = 2;
        setIntField(pokey, "lastContactFrame", 222);
        setIntField(pokey, "animationTimer", 1);
        setBooleanField(pokey, "playerOccupied", true);
        setIntField(pokey, "slotDisplayOffsetX", 12);
        setIntField(pokey, "slotDisplayOffsetY", -20);
        setBooleanField(pokey, "slotDisplayOffsetCalculated", true);
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
                .sorted(Comparator.comparingInt(TestS2PointPokeyRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    private static int readActivePrizeCount(Object target) {
        return ((int[]) readObjectField(target, "activePrizeCount"))[0];
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
            @Override public short getWidth() { return 0x4000; }
            @Override public short getHeight() { return 0x1000; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
