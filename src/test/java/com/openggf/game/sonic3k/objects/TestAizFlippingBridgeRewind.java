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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAizFlippingBridgeRewind {

    private static final ObjectSpawn FLIPPED_SHALLOW_SPAWN =
            new ObjectSpawn(0x0300, 0x0200, Sonic3kObjectIds.AIZ_FLIPPING_BRIDGE,
                    0xB6, 1, true, 17);

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
    void flippingBridgeRestoresFreshWithoutDropsAndPreservesSpawnDerivedState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        AizFlippingBridgeObjectInstance source =
                objectManager.createDynamicObject(
                        () -> new AizFlippingBridgeObjectInstance(FLIPPED_SHALLOW_SPAWN));
        seedState(source);

        ObjectRefId sourceId = objectId(objectManager, source);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(source);
        AizFlippingBridgeObjectInstance replacement =
                objectManager.createDynamicObject(
                        () -> new AizFlippingBridgeObjectInstance(
                                new ObjectSpawn(0x0100, 0x0100,
                                        Sonic3kObjectIds.AIZ_FLIPPING_BRIDGE,
                                        0, 0, false, 18)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, AizFlippingBridgeObjectInstance.class).size(),
                "restore must keep exactly the captured flipping bridge");

        AizFlippingBridgeObjectInstance restored =
                objectById(objectManager, AizFlippingBridgeObjectInstance.class, sourceId);

        assertNotSame(source, restored, "restore must recreate the flipping bridge");
        assertNotSame(replacement, restored, "restore must drop the divergent replacement bridge");
        assertEquals(0x0300, readIntField(restored, "x"),
                "x must be rebuilt from the captured spawn");
        assertEquals(0x0200, readIntField(restored, "y"),
                "y must be rebuilt from the captured spawn");
        assertTrue(readBooleanField(restored, "hFlip"),
                "horizontal flip must be rebuilt from the captured spawn render flags");
        assertEquals(3, readIntField(restored, "animPeriod"),
                "animation period must be rebuilt from the captured spawn subtype");
        assertEquals(21, readIntField(restored, "maxFrame"),
                "max frame must account for the captured flipped subtype");
        assertEquals(-1, readIntField(restored, "animDirection"),
                "animation direction must be rebuilt from the captured flip state");
        assertSeededState(restored);

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(sourceId, restoredTable.idFor(restored),
                "restored bridge must retain its captured rewind identity");
    }

    @Test
    void flippingBridgeUsesRewindRecreatableWithoutExplicitCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(AizFlippingBridgeObjectInstance.class),
                "AizFlippingBridgeObjectInstance must restore through generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AizFlippingBridgeObjectInstance.class.getName()),
                "AizFlippingBridgeObjectInstance must not keep an explicit S3K dynamic codec");
    }

    @Test
    void flippingBridgeReservesItsNativeMultispriteChildSlot() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        AizFlippingBridgeObjectInstance bridge = objectManager.createDynamicObject(
                () -> new AizFlippingBridgeObjectInstance(FLIPPED_SHALLOW_SPAWN));

        bridge.update(0, null);

        assertEquals(2, objectManager.getAllocatedSlotCount(),
                "parent plus loc_2AA78 must both occupy the native SST");
        assertFalse(objectManager.reserveDynamicSlot(bridge.getSlotIndex() + 1),
                "AllocateObjectAfterCurrent must reserve the first free slot after the bridge");
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

    private static void seedState(AizFlippingBridgeObjectInstance bridge) {
        setIntField(bridge, "animTimer", 0x23);
        int[] frames = readMutableIntArrayField(bridge, "segmentFrames");
        for (int i = 0; i < frames.length; i++) {
            frames[i] = 5 + i;
        }
    }

    private static void assertSeededState(AizFlippingBridgeObjectInstance bridge) {
        assertEquals(0x23, readIntField(bridge, "animTimer"),
                "animation timer must restore exactly");
        assertArrayEquals(new int[]{5, 6, 7, 8, 9, 10, 11, 12},
                readIntArrayField(bridge, "segmentFrames"),
                "segment mapping frames must restore exactly");
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
                .sorted(Comparator.comparingInt(TestAizFlippingBridgeRewind::slotIndex))
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

    private static int[] readIntArrayField(Object target, String fieldName) {
        try {
            int[] value = (int[]) findField(target.getClass(), fieldName).get(target);
            return Arrays.copyOf(value, value.length);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static int[] readMutableIntArrayField(Object target, String fieldName) {
        try {
            return (int[]) findField(target.getClass(), fieldName).get(target);
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
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x500; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
