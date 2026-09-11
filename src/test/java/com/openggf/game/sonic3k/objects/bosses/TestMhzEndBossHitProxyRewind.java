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
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestMhzEndBossHitProxyRewind {

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void hitProxyRestoresThroughGenericRecreateWithLiveMhzEndBossParent() throws Exception {
        ObjectManager objectManager = installObjectManager();
        ObjectSpawn capturedParentSpawn = new ObjectSpawn(
                0x3C40, 0x300, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 41);
        MhzEndBossInstance capturedParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(capturedParentSpawn));
        MhzEndBossHitProxyChild capturedProxy = objectManager.createDynamicObject(
                () -> new MhzEndBossHitProxyChild(capturedParent));
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "precondition: exactly one captured MHZ end boss parent is live before snapshot");
        assertEquals(1, liveObjects(objectManager, MhzEndBossHitProxyChild.class).size(),
                "precondition: exactly one captured hit proxy is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedParentId = captureTable.idFor(capturedParent);
        ObjectRefId capturedProxyId = captureTable.idFor(capturedProxy);
        assertNotNull(capturedParentId, "ObjectManager capture identity table must register the live boss");
        assertNotNull(capturedProxyId, "ObjectManager capture identity table must register the hit proxy");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(capturedProxy);
        objectManager.removeDynamicObject(capturedParent);
        MhzEndBossInstance divergentParent = objectManager.createDynamicObject(
                () -> new MhzEndBossInstance(new ObjectSpawn(
                        0x3D00, 0x340, Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 42)));
        MhzEndBossHitProxyChild divergentProxy = objectManager.createDynamicObject(
                () -> new MhzEndBossHitProxyChild(divergentParent));
        assertEquals(1, liveObjects(objectManager, MhzEndBossInstance.class).size(),
                "diverge step should leave one unrelated live parent before restore");
        assertEquals(1, liveObjects(objectManager, MhzEndBossHitProxyChild.class).size(),
                "diverge step should leave one unrelated live hit proxy before restore");

        registry.restore(snapshot);

        MhzEndBossInstance restoredParent = singleLiveObject(objectManager, MhzEndBossInstance.class);
        MhzEndBossHitProxyChild restoredProxy =
                singleLiveObject(objectManager, MhzEndBossHitProxyChild.class);
        assertFalse(restoredParent == capturedParent,
                "restore should not retain the removed captured parent instance");
        assertFalse(restoredParent == divergentParent,
                "restore should replace divergent live parents with the captured parent snapshot entry");
        assertFalse(restoredProxy == capturedProxy,
                "restore should not retain the removed captured proxy instance");
        assertFalse(restoredProxy == divergentProxy,
                "restore should replace divergent live proxies with the captured proxy snapshot entry");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedParentId, restoredTable.idFor(restoredParent),
                "restored boss must retain the captured ObjectManager rewind identity");
        assertEquals(capturedProxyId, restoredTable.idFor(restoredProxy),
                "restored hit proxy must retain the captured ObjectManager rewind identity");
        assertSame(restoredParent, readParent(restoredProxy),
                "restored hit proxy must relink to the restored MHZ end boss parent");

        restoredParent.getState().invulnerable = false;
        restoredParent.getState().defeated = false;
        assertEquals(0x25, restoredProxy.getCollisionFlags(),
                "restored proxy collision flags must derive from the restored parent state");
        restoredParent.getState().invulnerable = true;
        assertEquals(0, restoredProxy.getCollisionFlags(),
                "restored proxy must stop colliding while the restored parent is invulnerable");
        restoredParent.getState().invulnerable = false;
        int hitsBefore = restoredParent.getState().hitCount;
        restoredProxy.onPlayerAttack(null,
                new TouchResponseResult(0, 0, 0, TouchCategory.ENEMY));
        assertEquals(hitsBefore - 1, restoredParent.getState().hitCount,
                "restored proxy must delegate accepted attacks to the restored parent");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossHitProxyChild.class.getName()),
                "MhzEndBossHitProxyChild must restore through RewindRecreatable genericRecreate, "
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

    private static MhzEndBossInstance readParent(MhzEndBossHitProxyChild proxy)
            throws ReflectiveOperationException {
        Field field = MhzEndBossHitProxyChild.class.getDeclaredField("parent");
        field.setAccessible(true);
        return (MhzEndBossInstance) field.get(proxy);
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
