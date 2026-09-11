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

class TestMhzEndBossSpikeRewind {
    private static final int DASH_PHASE_FLAG_OFFSET = 0x38;
    private static final int DASH_PHASE_FLAG = 0x40;
    private static final int ACTIVE_COLLISION_FLAGS = 0x8B;

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void spikeRestoresThroughGenericRecreateWithLiveMhzEndBossParent() throws Exception {
        ObjectManager objectManager = installObjectManager();
        ObjectSpawn capturedParentSpawn = new ObjectSpawn(
                0x3C40, 0x300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 41);
        MhzEndBossInstance capturedParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(capturedParentSpawn));
        MhzEndBossSpikeChild capturedSpike = objectManager.createDynamicObject(
                () -> new MhzEndBossSpikeChild(capturedParent, 1, -0x2C, 0x18));
        capturedParent.setCustomFlag(DASH_PHASE_FLAG_OFFSET, DASH_PHASE_FLAG);
        capturedSpike.update(7, null);
        assertEquals(ACTIVE_COLLISION_FLAGS, capturedSpike.getCollisionFlags(),
                "precondition: subtype 1 spike should be active on odd frames while parent dash flag is set");
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "precondition: exactly one captured MHZ end boss parent is live before snapshot");
        assertEquals(1, liveObjects(objectManager, MhzEndBossSpikeChild.class).size(),
                "precondition: exactly one captured spike is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedParentId = captureTable.idFor(capturedParent);
        ObjectRefId capturedSpikeId = captureTable.idFor(capturedSpike);
        assertNotNull(capturedParentId, "ObjectManager capture identity table must register the live boss");
        assertNotNull(capturedSpikeId, "ObjectManager capture identity table must register the spike");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(capturedSpike);
        objectManager.removeDynamicObject(capturedParent);
        MhzEndBossInstance divergentParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(new ObjectSpawn(
                        0x3D00, 0x340, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 42)));
        MhzEndBossSpikeChild divergentSpike = objectManager.createDynamicObject(
                () -> new MhzEndBossSpikeChild(divergentParent, 0, -0x14, 0x18));
        divergentParent.setCustomFlag(DASH_PHASE_FLAG_OFFSET, 0);
        divergentSpike.update(8, null);
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "diverge step should leave one unrelated live parent before restore");
        assertEquals(1, liveObjects(objectManager, MhzEndBossSpikeChild.class).size(),
                "diverge step should leave one unrelated live spike before restore");

        registry.restore(snapshot);

        MhzEndBossInstance restoredParent = singleLiveObject(objectManager, MhzEndBossInstance.class);
        MhzEndBossSpikeChild restoredSpike = singleLiveObject(objectManager, MhzEndBossSpikeChild.class);
        assertFalse(restoredParent == capturedParent,
                "restore should not retain the removed captured parent instance");
        assertFalse(restoredParent == divergentParent,
                "restore should replace divergent live parents with the captured parent snapshot entry");
        assertFalse(restoredSpike == capturedSpike,
                "restore should not retain the removed captured spike instance");
        assertFalse(restoredSpike == divergentSpike,
                "restore should replace divergent live spikes with the captured spike snapshot entry");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedParentId, restoredTable.idFor(restoredParent),
                "restored boss must retain the captured ObjectManager rewind identity");
        assertEquals(capturedSpikeId, restoredTable.idFor(restoredSpike),
                "restored spike must retain the captured ObjectManager rewind identity");
        assertSame(restoredParent, readParent(restoredSpike),
                "restored spike must relink to the restored MHZ end boss parent");

        assertEquals(1, readInt(restoredSpike, "subtype"),
                "restored spike must retain captured subtype");
        assertEquals(-0x2C, readInt(restoredSpike, "xOffset"),
                "restored spike must retain captured X offset");
        assertEquals(0x18, readInt(restoredSpike, "yOffset"),
                "restored spike must retain captured Y offset");
        assertEquals(ACTIVE_COLLISION_FLAGS, readInt(restoredSpike, "collisionFlags"),
                "restored spike must retain captured collision flags before the next update");

        restoredParent.getState().x = 0x4120;
        restoredParent.getState().y = 0x380;
        restoredParent.setCustomFlag(DASH_PHASE_FLAG_OFFSET, DASH_PHASE_FLAG);
        restoredSpike.update(9, null);
        assertEquals(restoredParent.getX() - 0x2C, restoredSpike.getX(),
                "restored spike X must continue deriving from the restored parent plus captured offset");
        assertEquals(restoredParent.getY() + 0x18, restoredSpike.getY(),
                "restored spike Y must continue deriving from the restored parent plus captured offset");
        assertEquals(ACTIVE_COLLISION_FLAGS, restoredSpike.getCollisionFlags(),
                "restored subtype 1 spike must collide on odd frames when the restored parent dash flag is set");

        restoredSpike.update(10, null);
        assertEquals(0, restoredSpike.getCollisionFlags(),
                "restored subtype 1 spike must stop colliding on even frames");
        restoredParent.setCustomFlag(DASH_PHASE_FLAG_OFFSET, 0);
        restoredSpike.update(11, null);
        assertEquals(0, restoredSpike.getCollisionFlags(),
                "restored spike must stop colliding when the restored parent dash flag is clear");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossSpikeChild.class.getName()),
                "MhzEndBossSpikeChild must restore through RewindRecreatable genericRecreate, "
                        + "not a handwritten S3K dynamic codec");
    }

    @Test
    void spikeCollisionParityMatchesParentDashFlagAndSubtype() {
        MhzEndBossInstance parent = new MhzEndBossInstance(new ObjectSpawn(
                0x3C40, 0x300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 41));
        MhzEndBossSpikeChild subtypeZeroSpike =
                new MhzEndBossSpikeChild(parent, 0, -0x14, 0x18);
        MhzEndBossSpikeChild subtypeOneSpike =
                new MhzEndBossSpikeChild(parent, 1, -0x2C, 0x18);

        parent.setCustomFlag(DASH_PHASE_FLAG_OFFSET, 0);
        subtypeZeroSpike.update(8, null);
        subtypeOneSpike.update(9, null);
        assertEquals(0, subtypeZeroSpike.getCollisionFlags(),
                "parent flag $38 bit $40 clear must disable subtype 0 spikes");
        assertEquals(0, subtypeOneSpike.getCollisionFlags(),
                "parent flag $38 bit $40 clear must disable nonzero subtype spikes");

        parent.setCustomFlag(DASH_PHASE_FLAG_OFFSET, DASH_PHASE_FLAG);
        subtypeZeroSpike.update(8, null);
        subtypeOneSpike.update(8, null);
        assertEquals(ACTIVE_COLLISION_FLAGS, subtypeZeroSpike.getCollisionFlags(),
                "subtype 0 spike must be active on even frames");
        assertEquals(0, subtypeOneSpike.getCollisionFlags(),
                "nonzero subtype spike must be inactive on even frames");

        subtypeZeroSpike.update(9, null);
        subtypeOneSpike.update(9, null);
        assertEquals(0, subtypeZeroSpike.getCollisionFlags(),
                "subtype 0 spike must be inactive on odd frames");
        assertEquals(ACTIVE_COLLISION_FLAGS, subtypeOneSpike.getCollisionFlags(),
                "nonzero subtype spike must be active on odd frames");
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

    private static MhzEndBossInstance readParent(MhzEndBossSpikeChild spike)
            throws ReflectiveOperationException {
        Field field = MhzEndBossSpikeChild.class.getDeclaredField("parent");
        field.setAccessible(true);
        return (MhzEndBossInstance) field.get(spike);
    }

    private static int readInt(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
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
