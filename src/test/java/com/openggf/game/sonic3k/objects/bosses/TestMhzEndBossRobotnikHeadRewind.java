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

class TestMhzEndBossRobotnikHeadRewind {

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void robotnikHeadRestoresThroughGenericRecreateWithLiveMhzEndBossParent() throws Exception {
        ObjectManager objectManager = installObjectManager();
        ObjectSpawn capturedParentSpawn = new ObjectSpawn(
                0x3C40, 0x300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 41);
        MhzEndBossInstance capturedParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(capturedParentSpawn));
        MhzEndBossRobotnikHeadChild capturedHead = objectManager.createDynamicObject(
                () -> new MhzEndBossRobotnikHeadChild(capturedParent));
        writeInt(capturedHead, "animationTimer", 4);
        writeInt(capturedHead, "rawMappingFrame", 1);
        writeInt(capturedHead, "mappingFrame", 2);
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "precondition: exactly one captured MHZ end boss parent is live before snapshot");
        assertEquals(1, liveObjects(objectManager, MhzEndBossRobotnikHeadChild.class).size(),
                "precondition: exactly one captured Robotnik head is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedParentId = captureTable.idFor(capturedParent);
        ObjectRefId capturedHeadId = captureTable.idFor(capturedHead);
        assertNotNull(capturedParentId, "ObjectManager capture identity table must register the live boss");
        assertNotNull(capturedHeadId, "ObjectManager capture identity table must register the Robotnik head");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(capturedHead);
        objectManager.removeDynamicObject(capturedParent);
        MhzEndBossInstance divergentParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(new ObjectSpawn(
                        0x3D00, 0x340, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 42)));
        MhzEndBossRobotnikHeadChild divergentHead = objectManager.createDynamicObject(
                () -> new MhzEndBossRobotnikHeadChild(divergentParent));
        writeInt(divergentHead, "animationTimer", 0);
        writeInt(divergentHead, "rawMappingFrame", 0);
        writeInt(divergentHead, "mappingFrame", 0);
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "diverge step should leave one unrelated live parent before restore");
        assertEquals(1, liveObjects(objectManager, MhzEndBossRobotnikHeadChild.class).size(),
                "diverge step should leave one unrelated live Robotnik head before restore");

        registry.restore(snapshot);

        MhzEndBossInstance restoredParent = singleLiveObject(objectManager, MhzEndBossInstance.class);
        MhzEndBossRobotnikHeadChild restoredHead =
                singleLiveObject(objectManager, MhzEndBossRobotnikHeadChild.class);
        assertFalse(restoredParent == capturedParent,
                "restore should not retain the removed captured parent instance");
        assertFalse(restoredParent == divergentParent,
                "restore should replace divergent live parents with the captured parent snapshot entry");
        assertFalse(restoredHead == capturedHead,
                "restore should not retain the removed captured head instance");
        assertFalse(restoredHead == divergentHead,
                "restore should replace divergent live heads with the captured head snapshot entry");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedParentId, restoredTable.idFor(restoredParent),
                "restored boss must retain the captured ObjectManager rewind identity");
        assertEquals(capturedHeadId, restoredTable.idFor(restoredHead),
                "restored Robotnik head must retain the captured ObjectManager rewind identity");
        assertSame(restoredParent, readParent(restoredHead),
                "restored Robotnik head must relink to the restored MHZ end boss parent");

        assertEquals(4, readInt(restoredHead, "animationTimer"),
                "restored Robotnik head must retain captured animation timer state");
        assertEquals(1, readInt(restoredHead, "rawMappingFrame"),
                "restored Robotnik head must retain captured raw animation frame state");
        assertEquals(2, readInt(restoredHead, "mappingFrame"),
                "restored Robotnik head must retain captured mapping frame state");

        restoredParent.getState().x = 0x4120;
        restoredParent.getState().y = 0x380;
        restoredHead.update(0, null);
        assertEquals(restoredParent.getX(), restoredHead.getX(),
                "restored Robotnik head X must continue deriving from the restored parent");
        assertEquals(restoredParent.getY() - 0x1C, restoredHead.getY(),
                "restored Robotnik head Y must continue deriving from the restored parent offset");

        writeInt(restoredParent, "priorityBucket", 2);
        writeBoolean(restoredParent, "highPriority", true);
        assertEquals(2, restoredHead.getPriorityBucket(),
                "restored Robotnik head priority bucket must derive from the restored parent");
        assertEquals(restoredParent.isHighPriority(), restoredHead.isHighPriority(),
                "restored Robotnik head high-priority flag must derive from the restored parent");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossRobotnikHeadChild.class.getName()),
                "MhzEndBossRobotnikHeadChild must restore through RewindRecreatable genericRecreate, "
                        + "not a handwritten S3K dynamic codec");
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

    private static MhzEndBossInstance readParent(MhzEndBossRobotnikHeadChild head)
            throws ReflectiveOperationException {
        Field field = MhzEndBossRobotnikHeadChild.class.getDeclaredField("parent");
        field.setAccessible(true);
        return (MhzEndBossInstance) field.get(head);
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
