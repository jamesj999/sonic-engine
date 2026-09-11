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

class TestS3kStandaloneControllerRewind {

    private static final ObjectSpawn LBZ_BARRIER_SPAWN =
            new ObjectSpawn(0x3BC0, 0x0100, 0x00, 0, 0, false, 401);
    private static final ObjectSpawn LBZ_ALARM_SPAWN =
            new ObjectSpawn(0x2200, 0x0320, Sonic3kObjectIds.LBZ_ALARM, 0x03, 0, false, 402);
    private static final ObjectSpawn HCZ_WATER_DROP_SPAWN =
            new ObjectSpawn(0x1200, 0x0300, Sonic3kObjectIds.HCZ_WATER_DROP, 0x08, 0, false, 403);
    private static final ObjectSpawn MHZ_POLLEN_SPAWN =
            new ObjectSpawn(0, 0, 0, 0, 0, false, 404);

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
    void standaloneControllersRestoreFreshWithoutDropsAndPreserveState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        LbzInvisibleBarrierInstance barrier = objectManager.createDynamicObject(
                () -> new LbzInvisibleBarrierInstance(LBZ_BARRIER_SPAWN));
        LbzAlarmObjectInstance alarm = objectManager.createDynamicObject(
                () -> new LbzAlarmObjectInstance(LBZ_ALARM_SPAWN));
        HCZWaterDropObjectInstance waterDrop = objectManager.createDynamicObject(
                () -> new HCZWaterDropObjectInstance(HCZ_WATER_DROP_SPAWN));
        MhzPollenSpawnerInstance pollen = objectManager.createDynamicObject(
                () -> new MhzPollenSpawnerInstance(MHZ_POLLEN_SPAWN));
        Mgz2PostBossPaletteFadeController mgzPalette = objectManager.createDynamicObject(
                Mgz2PostBossPaletteFadeController::new);
        Mgz2PostBossSequenceController mgzSequence = objectManager.createDynamicObject(
                Mgz2PostBossSequenceController::new);

        seedState(alarm, waterDrop, pollen, mgzPalette);

        ObjectRefId barrierId = objectId(objectManager, barrier);
        ObjectRefId alarmId = objectId(objectManager, alarm);
        ObjectRefId waterDropId = objectId(objectManager, waterDrop);
        ObjectRefId pollenId = objectId(objectManager, pollen);
        ObjectRefId mgzPaletteId = objectId(objectManager, mgzPalette);
        ObjectRefId mgzSequenceId = objectId(objectManager, mgzSequence);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(barrier);
        objectManager.removeDynamicObject(alarm);
        objectManager.removeDynamicObject(waterDrop);
        objectManager.removeDynamicObject(pollen);
        objectManager.removeDynamicObject(mgzPalette);
        objectManager.removeDynamicObject(mgzSequence);
        LbzInvisibleBarrierInstance replacementBarrier = objectManager.createDynamicObject(
                () -> new LbzInvisibleBarrierInstance(replacementSpawn(0, 411)));
        LbzAlarmObjectInstance replacementAlarm = objectManager.createDynamicObject(
                () -> new LbzAlarmObjectInstance(replacementSpawn(Sonic3kObjectIds.LBZ_ALARM, 412)));
        HCZWaterDropObjectInstance replacementWaterDrop = objectManager.createDynamicObject(
                () -> new HCZWaterDropObjectInstance(replacementSpawn(Sonic3kObjectIds.HCZ_WATER_DROP, 413)));
        MhzPollenSpawnerInstance replacementPollen = objectManager.createDynamicObject(
                () -> new MhzPollenSpawnerInstance(replacementSpawn(0, 414)));
        Mgz2PostBossPaletteFadeController replacementMgzPalette = objectManager.createDynamicObject(
                Mgz2PostBossPaletteFadeController::new);
        Mgz2PostBossSequenceController replacementMgzSequence = objectManager.createDynamicObject(
                Mgz2PostBossSequenceController::new);

        registryFor(objectManager).restore(snapshot);

        LbzInvisibleBarrierInstance restoredBarrier =
                assertSingleRestored(objectManager, LbzInvisibleBarrierInstance.class, barrierId);
        LbzAlarmObjectInstance restoredAlarm =
                assertSingleRestored(objectManager, LbzAlarmObjectInstance.class, alarmId);
        HCZWaterDropObjectInstance restoredWaterDrop =
                assertSingleRestored(objectManager, HCZWaterDropObjectInstance.class, waterDropId);
        MhzPollenSpawnerInstance restoredPollen =
                assertSingleRestored(objectManager, MhzPollenSpawnerInstance.class, pollenId);
        Mgz2PostBossPaletteFadeController restoredMgzPalette =
                assertSingleRestored(objectManager, Mgz2PostBossPaletteFadeController.class, mgzPaletteId);
        Mgz2PostBossSequenceController restoredMgzSequence =
                assertSingleRestored(objectManager, Mgz2PostBossSequenceController.class, mgzSequenceId);

        assertFresh(barrier, replacementBarrier, restoredBarrier);
        assertFresh(alarm, replacementAlarm, restoredAlarm);
        assertFresh(waterDrop, replacementWaterDrop, restoredWaterDrop);
        assertFresh(pollen, replacementPollen, restoredPollen);
        assertFresh(mgzPalette, replacementMgzPalette, restoredMgzPalette);
        assertFresh(mgzSequence, replacementMgzSequence, restoredMgzSequence);

        assertEquals(0x3BC0, restoredBarrier.getX(), "LBZ barrier X must rebuild from constructor");
        assertEquals(0x0100, restoredBarrier.getY(), "LBZ barrier Y must rebuild from constructor");
        assertEquals(0x03, readIntField(restoredAlarm, "subtype"),
                "LBZ alarm subtype must rebuild from spawn");
        assertEquals(0x44, readIntField(restoredAlarm, "alarmTimer"),
                "LBZ alarm timer must restore exactly");
        assertEquals(1, readIntField(restoredAlarm, "collisionProperty"),
                "LBZ alarm collision property must restore exactly");
        assertEquals(HCZ_WATER_DROP_SPAWN.x(), restoredWaterDrop.getX(),
                "HCZ water-drop X must rebuild from spawn");
        assertEquals(HCZ_WATER_DROP_SPAWN.y(), restoredWaterDrop.getY(),
                "HCZ water-drop Y must rebuild from spawn");
        assertEquals((HCZ_WATER_DROP_SPAWN.subtype() & 0xFF) * 4,
                readIntField(restoredWaterDrop, "spawnInterval"),
                "HCZ water-drop interval must rebuild from subtype");
        assertEquals(7, readIntField(restoredWaterDrop, "spawnTimer"),
                "HCZ water-drop timer must restore exactly");
        assertEquals(0x0120, restoredPollen.getPlayerOneStoredYVelocity(),
                "MHZ pollen P1 stored Y velocity must restore exactly");
        assertEquals(0x0220, restoredPollen.getPlayerTwoStoredYVelocity(),
                "MHZ pollen P2 stored Y velocity must restore exactly");
        assertEquals(9, readIntField(restoredMgzPalette, "step"),
                "MGZ palette step must restore exactly");
        assertEquals(4, readIntField(restoredMgzPalette, "timer"),
                "MGZ palette timer must restore exactly");
    }

    @Test
    void standaloneControllersUseRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(LbzInvisibleBarrierInstance.class),
                "LbzInvisibleBarrierInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(LbzAlarmObjectInstance.class),
                "LbzAlarmObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(HCZWaterDropObjectInstance.class),
                "HCZWaterDropObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(MhzPollenSpawnerInstance.class),
                "MhzPollenSpawnerInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Mgz2PostBossPaletteFadeController.class),
                "Mgz2PostBossPaletteFadeController must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Mgz2PostBossSequenceController.class),
                "Mgz2PostBossSequenceController must restore through generic recreate");
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
            LbzAlarmObjectInstance alarm,
            HCZWaterDropObjectInstance waterDrop,
            MhzPollenSpawnerInstance pollen,
            Mgz2PostBossPaletteFadeController mgzPalette) {
        setIntField(alarm, "alarmTimer", 0x44);
        setIntField(alarm, "collisionProperty", 1);
        setIntField(waterDrop, "spawnTimer", 7);
        setIntField(pollen, "playerOneStoredYVelocity", 0x0120);
        setIntField(pollen, "playerTwoStoredYVelocity", 0x0220);
        setIntField(mgzPalette, "step", 9);
        setIntField(mgzPalette, "timer", 4);
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

    private static <T extends ObjectInstance> T assertSingleRestored(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        assertEquals(1, liveObjects(objectManager, type).size(),
                "restore must keep exactly one " + type.getSimpleName());
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
                .sorted(Comparator.comparingInt(TestS3kStandaloneControllerRewind::slotIndex))
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
