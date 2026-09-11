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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2MtzNutRewind {

    private static final ObjectSpawn NUT_SPAWN =
            new ObjectSpawn(0x1800, 0x0500, Sonic2ObjectIds.NUT, 0x83, 0, false, 301);

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
    void mtzNutRestoresFreshWithoutDropsAndPreservesState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        NutObjectInstance nut = objectManager.createDynamicObject(
                () -> new NutObjectInstance(NUT_SPAWN, "Nut"));
        seedState(nut);

        ObjectRefId nutId = objectId(objectManager, nut);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(nut);
        NutObjectInstance replacement = objectManager.createDynamicObject(
                () -> new NutObjectInstance(new ObjectSpawn(
                        0x0100, 0x0100, Sonic2ObjectIds.NUT, 0x00, 0, false, 302), "Nut"));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, NutObjectInstance.class).size(),
                "restore must keep exactly the captured MTZ nut");
        NutObjectInstance restored = objectById(objectManager, NutObjectInstance.class, nutId);
        assertNotSame(nut, restored, "restore must recreate the MTZ nut");
        assertNotSame(replacement, restored, "restore must drop the divergent MTZ nut");

        assertEquals(NUT_SPAWN.x(), restored.getX(), "nut X must rebuild from spawn");
        assertEquals(0x0570, restored.getY(), "nut Y must restore exactly");
        assertEquals(NUT_SPAWN.y(), readIntField(restored, "baseY"),
                "nut base Y must rebuild from spawn");
        assertEquals((NUT_SPAWN.subtype() & 0x7F) << 3, readIntField(restored, "maxTravel"),
                "nut max travel must rebuild from subtype");
        assertTrue(readBooleanField(restored, "fallsOff"),
                "nut falls-off flag must rebuild from subtype bit 7");
        assertEquals(0x0038, readIntField(restored, "accumulator"),
                "nut accumulator must restore exactly");
        assertEquals(2, readIntField(restored, "mappingFrame"),
                "nut mapping frame must restore exactly");
        assertEquals(4, readIntField(restored, "routine"),
                "nut routine must restore exactly");
        assertEquals(0x0120, readIntField(restored, "yVel"),
                "nut falling velocity must restore exactly");
    }

    @Test
    void mtzNutUsesRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(NutObjectInstance.class),
                "NutObjectInstance must restore through generic recreate");
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

    private static void seedState(NutObjectInstance nut) {
        setIntField(nut, "y", 0x0570);
        setIntField(nut, "accumulator", 0x0038);
        setIntField(nut, "mappingFrame", 2);
        setIntField(nut, "routine", 4);
        setIntField(nut, "yVel", 0x0120);
        setIntField(nut, "ySub", 0x0040);
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
                .sorted(Comparator.comparingInt(TestS2MtzNutRewind::slotIndex))
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
            @Override public short getWidth() { return 0x4000; }
            @Override public short getHeight() { return 0x1000; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
