package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMhzEndBossWeatherMachineRewind {
    private static final int PARENT_SIGNAL_FLAG_OFFSET = 0x38;
    private static final int PARENT_SIGNAL_FLAG = 0x04;
    private static final int DESTRUCTION_WAIT_AFTER_SIGNAL_UPDATE = 0x3E;

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void weatherMachineRestoresThroughGenericRecreateWithLiveMhzEndBossParent() throws Exception {
        ObjectManager objectManager = installObjectManager();
        ObjectSpawn capturedParentSpawn = new ObjectSpawn(
                0x3C40, 0x300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 41);
        MhzEndBossInstance capturedParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(capturedParentSpawn));
        MhzEndBossWeatherMachineChild capturedWeather = objectManager.createDynamicObject(
                () -> new MhzEndBossWeatherMachineChild(capturedParent));
        writeInt(capturedWeather, "x", 0x4567);
        writeInt(capturedWeather, "y", 0x02B1);
        writeInt(capturedWeather, "collisionFlags", 0);
        writeBoolean(capturedWeather, "destructionSignalled", true);
        writeBoolean(capturedWeather, "visualChildrenSpawned", true);
        writeInt(capturedWeather, "weatherSoundTimer", 13);
        writeInt(capturedWeather, "destructionWaitTimer", 7);
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "precondition: exactly one captured MHZ end boss parent is live before snapshot");
        assertEquals(1, liveObjects(objectManager, MhzEndBossWeatherMachineChild.class).size(),
                "precondition: exactly one captured weather machine is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedParentId = captureTable.idFor(capturedParent);
        ObjectRefId capturedWeatherId = captureTable.idFor(capturedWeather);
        assertNotNull(capturedParentId, "ObjectManager capture identity table must register the live boss");
        assertNotNull(capturedWeatherId, "ObjectManager capture identity table must register the weather machine");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        removeAllActiveObjects(objectManager);
        MhzEndBossInstance divergentParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(new ObjectSpawn(
                        0x3D00, 0x340, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 42)));
        MhzEndBossWeatherMachineChild divergentWeather = objectManager.createDynamicObject(
                () -> new MhzEndBossWeatherMachineChild(divergentParent));
        writeInt(divergentWeather, "x", 0x1111);
        writeInt(divergentWeather, "y", 0x0222);
        writeInt(divergentWeather, "collisionFlags", 0x11);
        writeBoolean(divergentWeather, "destructionSignalled", false);
        writeBoolean(divergentWeather, "visualChildrenSpawned", false);
        writeInt(divergentWeather, "weatherSoundTimer", 1);
        writeInt(divergentWeather, "destructionWaitTimer", 0);
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "diverge step should leave one unrelated live parent before restore");
        assertEquals(1, liveObjects(objectManager, MhzEndBossWeatherMachineChild.class).size(),
                "diverge step should leave one unrelated live weather machine before restore");

        registry.restore(snapshot);

        MhzEndBossInstance restoredParent = singleLiveObject(objectManager, MhzEndBossInstance.class);
        MhzEndBossWeatherMachineChild restoredWeather =
                singleLiveObject(objectManager, MhzEndBossWeatherMachineChild.class);
        assertFalse(restoredParent == capturedParent,
                "restore should not retain the removed captured parent instance");
        assertFalse(restoredParent == divergentParent,
                "restore should replace divergent live parents with the captured parent snapshot entry");
        assertFalse(restoredWeather == capturedWeather,
                "restore should not retain the removed captured weather machine instance");
        assertFalse(restoredWeather == divergentWeather,
                "restore should replace divergent live weather machines with the captured snapshot entry");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedParentId, restoredTable.idFor(restoredParent),
                "restored boss must retain the captured ObjectManager rewind identity");
        assertEquals(capturedWeatherId, restoredTable.idFor(restoredWeather),
                "restored weather machine must retain the captured ObjectManager rewind identity");
        assertSame(restoredParent, readParent(restoredWeather),
                "restored weather machine must relink to the restored MHZ end boss parent");

        assertEquals(0x4567, readInt(restoredWeather, "x"),
                "restored weather machine must retain captured X before the next update");
        assertEquals(0x02B1, readInt(restoredWeather, "y"),
                "restored weather machine must retain captured Y before the next update");
        assertEquals(0, readInt(restoredWeather, "collisionFlags"),
                "restored weather machine must retain captured collision flags");
        assertTrue(readBoolean(restoredWeather, "destructionSignalled"),
                "restored weather machine must retain captured destruction signal state");
        assertTrue(readBoolean(restoredWeather, "visualChildrenSpawned"),
                "restored weather machine must retain captured visual-spawn latch");
        assertEquals(13, readInt(restoredWeather, "weatherSoundTimer"),
                "restored weather machine must retain captured weather sound timer");
        assertEquals(7, readInt(restoredWeather, "destructionWaitTimer"),
                "restored weather machine must retain captured destruction wait timer");

        int visualCountBeforeUpdate = liveObjects(objectManager, MhzEndBossWeatherVisualChild.class).size();
        restoredParent.getState().x = 0x4800;
        restoredParent.getState().y = 0x0380;
        restoredWeather.update(1, null);
        assertEquals(0x4567, restoredWeather.getX(),
                "weather machine keeps its captured X; update does not refresh from the parent");
        assertEquals(0x02B1, restoredWeather.getY(),
                "weather machine keeps its captured Y; update does not refresh from the parent");
        assertEquals(visualCountBeforeUpdate,
                liveObjects(objectManager, MhzEndBossWeatherVisualChild.class).size(),
                "restored visualChildrenSpawned=true latch must prevent duplicate weather visual children");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossWeatherMachineChild.class.getName()),
                "MhzEndBossWeatherMachineChild must restore through RewindRecreatable genericRecreate, "
                        + "not a handwritten S3K dynamic codec");
    }

    @Test
    void restoredWeatherMachineSignalsParentWhenCollisionIsCleared() throws Exception {
        ObjectManager objectManager = installObjectManager();
        MhzEndBossInstance capturedParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(new ObjectSpawn(
                        0x3C40, 0x300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 41)));
        MhzEndBossWeatherMachineChild capturedWeather = objectManager.createDynamicObject(
                () -> new MhzEndBossWeatherMachineChild(capturedParent));
        writeInt(capturedWeather, "collisionFlags", 0);
        writeBoolean(capturedWeather, "destructionSignalled", false);
        writeBoolean(capturedWeather, "visualChildrenSpawned", true);
        writeInt(capturedWeather, "weatherSoundTimer", 5);
        writeInt(capturedWeather, "destructionWaitTimer", 0);

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        removeAllActiveObjects(objectManager);
        MhzEndBossInstance divergentParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(new ObjectSpawn(
                        0x3D00, 0x340, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 42)));
        objectManager.createDynamicObject(() -> new MhzEndBossWeatherMachineChild(divergentParent));

        registry.restore(snapshot);

        MhzEndBossInstance restoredParent = singleLiveObject(objectManager, MhzEndBossInstance.class);
        MhzEndBossWeatherMachineChild restoredWeather =
                singleLiveObject(objectManager, MhzEndBossWeatherMachineChild.class);
        assertEquals(0, restoredParent.getCustomFlag(PARENT_SIGNAL_FLAG_OFFSET) & PARENT_SIGNAL_FLAG,
                "precondition: restored parent signal bit should be clear before the restored update");
        assertFalse(readBoolean(restoredWeather, "destructionSignalled"),
                "precondition: restored weather machine should be ready to signal destruction");
        assertEquals(0, readInt(restoredWeather, "collisionFlags"),
                "precondition: restored weather machine should be in destroyed-collision state");

        restoredWeather.update(2, null);

        assertEquals(PARENT_SIGNAL_FLAG,
                restoredParent.getCustomFlag(PARENT_SIGNAL_FLAG_OFFSET) & PARENT_SIGNAL_FLAG,
                "collisionFlags=0 and destructionSignalled=false must set parent $38 bit 0x04");
        assertTrue(readBoolean(restoredWeather, "destructionSignalled"),
                "restored weather machine must latch destructionSignalled after notifying the parent");
        assertEquals(DESTRUCTION_WAIT_AFTER_SIGNAL_UPDATE, readInt(restoredWeather, "destructionWaitTimer"),
                "signal update should seed the ROM wait timer and consume the current frame coherently");
        assertEquals(0, readInt(restoredWeather, "collisionFlags"),
                "collision flags should remain cleared after the destruction signal update");
    }

    private static ObjectManager installObjectManager() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = mockCamera();
        StubObjectServices services = new StubObjectServices() {
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
        return objectManager;
    }

    private static void removeAllActiveObjects(ObjectManager objectManager) {
        List.copyOf(objectManager.getActiveObjects()).forEach(objectManager::removeDynamicObject);
    }

    private static MhzEndBossInstance readParent(MhzEndBossWeatherMachineChild weatherMachine)
            throws ReflectiveOperationException {
        Field field = MhzEndBossWeatherMachineChild.class.getDeclaredField("parent");
        field.setAccessible(true);
        return (MhzEndBossInstance) field.get(weatherMachine);
    }

    private static int readInt(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void writeInt(Object target, String fieldName, int value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static boolean readBoolean(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void writeBoolean(Object target, String fieldName, boolean value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static <T> T singleLiveObject(ObjectManager objectManager, Class<T> type) {
        List<T> live = liveObjects(objectManager, type);
        assertEquals(1, live.size(), "expected exactly one live " + type.getSimpleName());
        return live.getFirst();
    }

    private static <T> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
