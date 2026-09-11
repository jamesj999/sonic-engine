package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kUtilityMotionRewind {

    private static final ObjectSpawn AUTOMATIC_TUNNEL_SPAWN =
            new ObjectSpawn(0x0900, 0x0340, Sonic3kObjectIds.AUTOMATIC_TUNNEL, 0xC3, 0x00, false, 181);
    private static final ObjectSpawn AUTO_SPIN_SPAWN =
            new ObjectSpawn(0x0A00, 0x0400, Sonic3kObjectIds.AUTO_SPIN, 0xC6, 0x01, false, 182);
    private static final ObjectSpawn BUBBLER_SPAWN =
            new ObjectSpawn(0x0B00, 0x0500, Sonic3kObjectIds.BUBBLER, 0x85, 0x00, false, 183);
    private static final ObjectSpawn DOOR_SPAWN =
            new ObjectSpawn(0x0C00, 0x0600, Sonic3kObjectIds.DOOR, 0x82, 0x03, false, 184);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x2000, 0x900, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void s3kUtilityMotionObjectsRestoreFreshWithoutDropsAndPreserveSpawnState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        AutomaticTunnelObjectInstance automaticTunnel = objectManager.createDynamicObject(
                () -> new AutomaticTunnelObjectInstance(AUTOMATIC_TUNNEL_SPAWN));
        AutoSpinObjectInstance autoSpin = objectManager.createDynamicObject(
                () -> new AutoSpinObjectInstance(AUTO_SPIN_SPAWN));
        BubblerObjectInstance bubbler = objectManager.createDynamicObject(
                () -> new BubblerObjectInstance(BUBBLER_SPAWN));
        DoorObjectInstance door = objectManager.createDynamicObject(
                () -> new DoorObjectInstance(DOOR_SPAWN));

        ObjectRefId automaticTunnelId = objectId(objectManager, automaticTunnel);
        ObjectRefId autoSpinId = objectId(objectManager, autoSpin);
        ObjectRefId bubblerId = objectId(objectManager, bubbler);
        ObjectRefId doorId = objectId(objectManager, door);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(automaticTunnel);
        objectManager.removeDynamicObject(autoSpin);
        objectManager.removeDynamicObject(bubbler);
        objectManager.removeDynamicObject(door);
        AutomaticTunnelObjectInstance replacementAutomaticTunnel = objectManager.createDynamicObject(
                () -> new AutomaticTunnelObjectInstance(replacementSpawn(Sonic3kObjectIds.AUTOMATIC_TUNNEL, 191)));
        AutoSpinObjectInstance replacementAutoSpin = objectManager.createDynamicObject(
                () -> new AutoSpinObjectInstance(replacementSpawn(Sonic3kObjectIds.AUTO_SPIN, 192)));
        BubblerObjectInstance replacementBubbler = objectManager.createDynamicObject(
                () -> new BubblerObjectInstance(replacementSpawn(Sonic3kObjectIds.BUBBLER, 193)));
        DoorObjectInstance replacementDoor = objectManager.createDynamicObject(
                () -> new DoorObjectInstance(replacementSpawn(Sonic3kObjectIds.DOOR, 194)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, AutomaticTunnelObjectInstance.class).size(),
                "restore must keep exactly the captured automatic tunnel");
        assertEquals(1, liveObjects(objectManager, AutoSpinObjectInstance.class).size(),
                "restore must keep exactly the captured auto-spin trigger");
        assertEquals(1, liveObjects(objectManager, BubblerObjectInstance.class).size(),
                "restore must keep exactly the captured bubbler");
        assertEquals(1, liveObjects(objectManager, DoorObjectInstance.class).size(),
                "restore must keep exactly the captured door");

        AutomaticTunnelObjectInstance restoredAutomaticTunnel =
                objectById(objectManager, AutomaticTunnelObjectInstance.class, automaticTunnelId);
        AutoSpinObjectInstance restoredAutoSpin =
                objectById(objectManager, AutoSpinObjectInstance.class, autoSpinId);
        BubblerObjectInstance restoredBubbler =
                objectById(objectManager, BubblerObjectInstance.class, bubblerId);
        DoorObjectInstance restoredDoor =
                objectById(objectManager, DoorObjectInstance.class, doorId);

        assertFresh(automaticTunnel, replacementAutomaticTunnel, restoredAutomaticTunnel);
        assertFresh(autoSpin, replacementAutoSpin, restoredAutoSpin);
        assertFresh(bubbler, replacementBubbler, restoredBubbler);
        assertFresh(door, replacementDoor, restoredDoor);

        assertEquals(AUTOMATIC_TUNNEL_SPAWN, restoredAutomaticTunnel.getSpawn());
        assertEquals(AUTO_SPIN_SPAWN, restoredAutoSpin.getSpawn());
        assertEquals(BUBBLER_SPAWN, restoredBubbler.getSpawn());
        assertEquals(DOOR_SPAWN, restoredDoor.getSpawn());

        assertEquals(3, readIntField(restoredAutomaticTunnel, "pathId"),
                "automatic tunnel path id must rebuild from subtype");
        assertTrue(readBooleanField(restoredAutomaticTunnel, "reversePath"),
                "automatic tunnel reverse flag must rebuild from subtype");
        assertTrue(readBooleanField(restoredAutoSpin, "verticalMode"),
                "auto-spin vertical mode must rebuild from subtype");
        assertEquals(0x80, readIntField(restoredAutoSpin, "halfWidth"),
                "auto-spin trigger size must rebuild through BoxObjectInstance");
        assertTrue(readBooleanField(restoredBubbler, "maker"),
                "bubbler maker flag must rebuild from subtype");
        assertEquals(BUBBLER_SPAWN.x(), readIntField(restoredBubbler, "originalX"),
                "bubbler original X must rebuild from spawn");
        assertTrue(readBooleanField(restoredDoor, "horizontal"),
                "door orientation must rebuild from subtype");
        assertEquals(DOOR_SPAWN.x(), restoredDoor.getX(),
                "horizontal door X starts at base position before slide state");
    }

    @Test
    void s3kUtilityMotionObjectsUseRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(AutomaticTunnelObjectInstance.class),
                "AutomaticTunnelObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(AutoSpinObjectInstance.class),
                "AutoSpinObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(BubblerObjectInstance.class),
                "BubblerObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(DoorObjectInstance.class),
                "DoorObjectInstance must restore through generic recreate");
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
                .sorted(Comparator.comparingInt(TestS3kUtilityMotionRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    private static void assertFresh(Object original, Object replacement, Object restored) {
        assertNotSame(original, restored, "restore must recreate " + restored.getClass().getSimpleName());
        assertNotSame(replacement, restored, "restore must drop divergent "
                + restored.getClass().getSimpleName());
    }

    private static ObjectSpawn replacementSpawn(int objectId, int layoutIndex) {
        return new ObjectSpawn(0x0100, 0x0100, objectId, 0, 0, false, layoutIndex);
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
            @Override public short getWidth() { return 0x2000; }
            @Override public short getHeight() { return 0x900; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
