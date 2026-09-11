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

class TestS3kHczMechanismRewind {

    private static final ObjectSpawn BLOCK_SPAWN =
            new ObjectSpawn(0x1100, 0x0300, Sonic3kObjectIds.HCZ_BLOCK, 0x02, 0x00, false, 141);
    private static final ObjectSpawn CONVEYOR_SPIKE_SPAWN =
            new ObjectSpawn(0x0B28, 0x0380, Sonic3kObjectIds.HCZ_CONVEYOR_SPIKE, 0x00, 0x01, false, 142);
    private static final ObjectSpawn LARGE_FAN_SPAWN =
            new ObjectSpawn(0x1200, 0x0400, Sonic3kObjectIds.HCZ_LARGE_FAN, 0x00, 0x00, false, 143);
    private static final ObjectSpawn SPINNING_COLUMN_SPAWN =
            new ObjectSpawn(0x1300, 0x0500, Sonic3kObjectIds.HCZ_SPINNING_COLUMN, 0x21, 0x01, false, 144);
    private static final ObjectSpawn WATER_SPLASH_SPAWN =
            new ObjectSpawn(0x1400, 0x0600, Sonic3kObjectIds.HCZ_WATER_SPLASH, 0x00, 0x00, false, 145);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x2000, 0x800, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void hczMechanismsRestoreFreshWithoutDropsAndPreserveSpawnState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        HCZBlockObjectInstance block = objectManager.createDynamicObject(
                () -> new HCZBlockObjectInstance(BLOCK_SPAWN));
        HCZConveyorSpikeObjectInstance conveyorSpike = objectManager.createDynamicObject(
                () -> new HCZConveyorSpikeObjectInstance(CONVEYOR_SPIKE_SPAWN));
        HCZLargeFanObjectInstance largeFan = objectManager.createDynamicObject(
                () -> new HCZLargeFanObjectInstance(LARGE_FAN_SPAWN));
        HCZSpinningColumnObjectInstance spinningColumn = objectManager.createDynamicObject(
                () -> new HCZSpinningColumnObjectInstance(SPINNING_COLUMN_SPAWN));
        HCZWaterSplashObjectInstance waterSplash = objectManager.createDynamicObject(
                () -> new HCZWaterSplashObjectInstance(WATER_SPLASH_SPAWN));

        ObjectRefId blockId = objectId(objectManager, block);
        ObjectRefId conveyorSpikeId = objectId(objectManager, conveyorSpike);
        ObjectRefId largeFanId = objectId(objectManager, largeFan);
        ObjectRefId spinningColumnId = objectId(objectManager, spinningColumn);
        ObjectRefId waterSplashId = objectId(objectManager, waterSplash);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(block);
        objectManager.removeDynamicObject(conveyorSpike);
        objectManager.removeDynamicObject(largeFan);
        objectManager.removeDynamicObject(spinningColumn);
        objectManager.removeDynamicObject(waterSplash);
        HCZBlockObjectInstance replacementBlock = objectManager.createDynamicObject(
                () -> new HCZBlockObjectInstance(replacementSpawn(Sonic3kObjectIds.HCZ_BLOCK, 151)));
        HCZConveyorSpikeObjectInstance replacementConveyorSpike = objectManager.createDynamicObject(
                () -> new HCZConveyorSpikeObjectInstance(replacementSpawn(Sonic3kObjectIds.HCZ_CONVEYOR_SPIKE, 152)));
        HCZLargeFanObjectInstance replacementLargeFan = objectManager.createDynamicObject(
                () -> new HCZLargeFanObjectInstance(replacementSpawn(Sonic3kObjectIds.HCZ_LARGE_FAN, 153)));
        HCZSpinningColumnObjectInstance replacementSpinningColumn = objectManager.createDynamicObject(
                () -> new HCZSpinningColumnObjectInstance(replacementSpawn(Sonic3kObjectIds.HCZ_SPINNING_COLUMN, 154)));
        HCZWaterSplashObjectInstance replacementWaterSplash = objectManager.createDynamicObject(
                () -> new HCZWaterSplashObjectInstance(replacementSpawn(Sonic3kObjectIds.HCZ_WATER_SPLASH, 155)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, HCZBlockObjectInstance.class).size(),
                "restore must keep exactly the captured HCZ block");
        assertEquals(1, liveObjects(objectManager, HCZConveyorSpikeObjectInstance.class).size(),
                "restore must keep exactly the captured HCZ conveyor spike");
        assertEquals(1, liveObjects(objectManager, HCZLargeFanObjectInstance.class).size(),
                "restore must keep exactly the captured HCZ large fan");
        assertEquals(1, liveObjects(objectManager, HCZSpinningColumnObjectInstance.class).size(),
                "restore must keep exactly the captured HCZ spinning column");
        assertEquals(1, liveObjects(objectManager, HCZWaterSplashObjectInstance.class).size(),
                "restore must keep exactly the captured HCZ water splash");

        HCZBlockObjectInstance restoredBlock =
                objectById(objectManager, HCZBlockObjectInstance.class, blockId);
        HCZConveyorSpikeObjectInstance restoredConveyorSpike =
                objectById(objectManager, HCZConveyorSpikeObjectInstance.class, conveyorSpikeId);
        HCZLargeFanObjectInstance restoredLargeFan =
                objectById(objectManager, HCZLargeFanObjectInstance.class, largeFanId);
        HCZSpinningColumnObjectInstance restoredSpinningColumn =
                objectById(objectManager, HCZSpinningColumnObjectInstance.class, spinningColumnId);
        HCZWaterSplashObjectInstance restoredWaterSplash =
                objectById(objectManager, HCZWaterSplashObjectInstance.class, waterSplashId);

        assertFresh(block, replacementBlock, restoredBlock);
        assertFresh(conveyorSpike, replacementConveyorSpike, restoredConveyorSpike);
        assertFresh(largeFan, replacementLargeFan, restoredLargeFan);
        assertFresh(spinningColumn, replacementSpinningColumn, restoredSpinningColumn);
        assertFresh(waterSplash, replacementWaterSplash, restoredWaterSplash);

        assertEquals(BLOCK_SPAWN, restoredBlock.getSpawn());
        assertEquals(CONVEYOR_SPIKE_SPAWN, restoredConveyorSpike.getSpawn());
        assertEquals(LARGE_FAN_SPAWN, restoredLargeFan.getSpawn());
        assertEquals(SPINNING_COLUMN_SPAWN, restoredSpinningColumn.getSpawn());
        assertEquals(WATER_SPLASH_SPAWN, restoredWaterSplash.getSpawn());

        assertEquals(0x30, readIntField(restoredBlock, "halfWidth"),
                "HCZ block width must rebuild from subtype");
        assertEquals(CONVEYOR_SPIKE_SPAWN.y(), readIntField(restoredConveyorSpike, "centerY"),
                "HCZ conveyor spike center Y must rebuild from spawn");
        assertEquals(LARGE_FAN_SPAWN.x(), restoredLargeFan.getX(),
                "HCZ large fan X must rebuild from spawn");
        assertTrue(readBooleanField(restoredSpinningColumn, "xFlipped"),
                "HCZ spinning column flip must rebuild from render flags");
        assertEquals(WATER_SPLASH_SPAWN.y(), restoredWaterSplash.getY(),
                "HCZ water splash Y must rebuild from spawn");
    }

    @Test
    void hczMechanismsUseRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(HCZBlockObjectInstance.class),
                "HCZBlockObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(HCZConveyorSpikeObjectInstance.class),
                "HCZConveyorSpikeObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(HCZLargeFanObjectInstance.class),
                "HCZLargeFanObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(HCZSpinningColumnObjectInstance.class),
                "HCZSpinningColumnObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(HCZWaterSplashObjectInstance.class),
                "HCZWaterSplashObjectInstance must restore through generic recreate");
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
                .sorted(Comparator.comparingInt(TestS3kHczMechanismRewind::slotIndex))
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
            @Override public short getHeight() { return 0x800; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
