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

class TestS2MovingVineRewind {

    private static final ObjectSpawn VINE_SPAWN =
            new ObjectSpawn(0x1700, 0x0480, Sonic2ObjectIds.MOVING_VINE, 0x92, 0, false, 501);

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
    void movingVineRestoresFreshWithoutDropsAndPreservesState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        MovingVineObjectInstance vine = objectManager.createDynamicObject(
                () -> new MovingVineObjectInstance(VINE_SPAWN, "MovingVine"));
        seedState(vine);

        ObjectRefId vineId = objectId(objectManager, vine);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(vine);
        MovingVineObjectInstance replacement = objectManager.createDynamicObject(
                () -> new MovingVineObjectInstance(
                        new ObjectSpawn(0x0100, 0x0100, Sonic2ObjectIds.MOVING_VINE, 0, 0, false, 502),
                        "MovingVine"));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, MovingVineObjectInstance.class).size(),
                "restore must keep exactly the captured moving vine");
        MovingVineObjectInstance restored = objectById(objectManager, MovingVineObjectInstance.class, vineId);
        assertNotSame(vine, restored, "restore must recreate the moving vine");
        assertNotSame(replacement, restored, "restore must drop the divergent moving vine");

        assertEquals(VINE_SPAWN.x(), restored.getX(), "moving vine X must rebuild from spawn");
        assertEquals(0x04F0, restored.getY(), "moving vine Y must restore from captured extension state");
        assertEquals(VINE_SPAWN.y(), readIntField(restored, "initialY"),
                "moving vine initial Y must rebuild from spawn");
        assertEquals(0xB0, readIntField(restored, "maxExtension"),
                "MCZ moving vine max extension must rebuild from zone defaults");
        assertTrue(readBooleanField(restored, "reversedMode"),
                "moving vine reversed mode must rebuild from subtype");
        assertTrue(readBooleanField(restored, "buttonVineMode"),
                "moving vine button mode must rebuild from subtype");
        assertEquals(2, readIntField(restored, "buttonSwitchId"),
                "moving vine switch id must rebuild from subtype");
        assertEquals(0x70, readIntField(restored, "currentExtension"),
                "moving vine extension must restore exactly");
        assertTrue(readBooleanField(restored, "player1Grabbed"),
                "moving vine P1 grab state must restore exactly");
        assertEquals(3, readIntField(restored, "player1ReleaseDelay"),
                "moving vine P1 release delay must restore exactly");
        assertEquals(4, readIntField(restored, "mappingFrame"),
                "moving vine mapping frame must restore exactly");
    }

    @Test
    void movingVineUsesRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(MovingVineObjectInstance.class),
                "MovingVineObjectInstance must restore through generic recreate");
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

    private static void seedState(MovingVineObjectInstance vine) {
        setIntField(vine, "currentExtension", 0x70);
        setIntField(vine, "currentY", 0x04F0);
        setIntField(vine, "mappingFrame", 4);
        setBooleanField(vine, "player1Grabbed", true);
        setIntField(vine, "player1ReleaseDelay", 3);
        setBooleanField(vine, "player2Grabbed", false);
        setIntField(vine, "player2ReleaseDelay", 0);
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
                .sorted(Comparator.comparingInt(TestS2MovingVineRewind::slotIndex))
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
