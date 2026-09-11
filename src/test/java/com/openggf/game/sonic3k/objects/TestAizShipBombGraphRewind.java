package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAizShipBombGraphRewind {

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void shipBombRestoresThroughGenericRecreateWithLiveBattleshipParent() {
        ObjectManager objectManager = installObjectManager();
        ObjectSpawn shipSpawn = new ObjectSpawn(0x200, 0x08F0, 0, 0, 0, false, 40);
        AizBattleshipInstance capturedShip = objectManager.createDynamicObject(
                () -> new AizBattleshipInstance(shipSpawn, shipSpawn.y()));
        int capturedSourceSecondaryX = 0x3F5C;
        ObjectSpawn bombSpawn = new ObjectSpawn(0x260, 0x0120, 0, 0, 0, false, 41);
        AizShipBombInstance capturedBomb = objectManager.createDynamicObject(
                () -> new AizShipBombInstance(
                        bombSpawn, capturedShip, capturedSourceSecondaryX, bombSpawn.y()));

        for (int i = 0; i < 12; i++) {
            capturedBomb.update(i, null);
        }
        int capturedState = capturedBomb.stateForTest();
        int capturedPortYOffset = capturedBomb.portYOffsetForTest();
        assertTrue(capturedPortYOffset > 0x0A60,
                "precondition: bomb must advance beyond its spawn-default port Y");
        assertEquals(1, liveObjects(objectManager, AizBattleshipInstance.class).size(),
                "precondition: exactly one captured battleship is live before snapshot");
        assertEquals(1, liveObjects(objectManager, AizShipBombInstance.class).size(),
                "precondition: exactly one captured ship bomb is live before snapshot");

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId capturedShipId = captureTable.idFor(capturedShip);
        ObjectRefId capturedBombId = captureTable.idFor(capturedBomb);
        assertNotNull(capturedShipId, "ObjectManager identity table must register the live battleship");
        assertNotNull(capturedBombId, "ObjectManager identity table must register the live ship bomb");

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        removeAllActiveObjects(objectManager);
        AizBattleshipInstance divergentShip = objectManager.createDynamicObject(
                () -> new AizBattleshipInstance(
                        new ObjectSpawn(0x300, 0x0900, 0, 0, 0, false, 42), 0x0900));
        AizShipBombInstance divergentBomb = objectManager.createDynamicObject(
                () -> new AizShipBombInstance(
                        new ObjectSpawn(0x340, 0x0180, 0, 0, 0, false, 43),
                        divergentShip, 0x1111, 0x0180));
        assertEquals(1, liveObjects(objectManager, AizBattleshipInstance.class).size(),
                "diverge step should leave one unrelated battleship before restore");
        assertEquals(1, liveObjects(objectManager, AizShipBombInstance.class).size(),
                "diverge step should leave one unrelated ship bomb before restore");

        registry.restore(snapshot);

        AizBattleshipInstance restoredShip = singleLiveObject(objectManager, AizBattleshipInstance.class);
        AizShipBombInstance restoredBomb = singleLiveObject(objectManager, AizShipBombInstance.class);
        assertFalse(restoredShip == capturedShip,
                "restore should recreate the captured battleship instead of keeping the removed instance");
        assertFalse(restoredShip == divergentShip,
                "restore should replace the divergent battleship with the captured snapshot entry");
        assertFalse(restoredBomb == capturedBomb,
                "restore should recreate the captured bomb instead of keeping the removed instance");
        assertFalse(restoredBomb == divergentBomb,
                "restore should replace the divergent bomb with the captured snapshot entry");

        RewindIdentityTable restoredTable = objectManager.captureIdentityContext().requireIdentityTable();
        assertEquals(capturedShipId, restoredTable.idFor(restoredShip),
                "restored battleship must retain the captured rewind identity");
        assertEquals(capturedBombId, restoredTable.idFor(restoredBomb),
                "restored ship bomb must retain the captured rewind identity");
        assertSame(restoredShip, restoredBomb.sourceShipForTest(),
                "restored ship bomb must relink to the restored live battleship");
        assertSame(bombSpawn, restoredBomb.getSpawn(),
                "restored ship bomb must keep the captured ObjectSpawn identity");

        assertEquals(capturedState, restoredBomb.stateForTest(),
                "restored ship bomb must keep its captured state");
        assertEquals(capturedPortYOffset, restoredBomb.portYOffsetForTest(),
                "restored ship bomb must keep its captured port Y offset");
        assertEquals(capturedSourceSecondaryX, restoredBomb.sourceSecondaryXForTest(),
                "restored ship bomb must keep its captured source secondary X");

        assertTrue(RewindRecreatable.class.isAssignableFrom(AizShipBombInstance.class),
                "AizShipBombInstance must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(AizShipBombInstance.class.getName()),
                "AizShipBombInstance must restore through graph-tested generic recreate, "
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

    private static void removeAllActiveObjects(ObjectManager objectManager) {
        List.copyOf(objectManager.getActiveObjects()).forEach(objectManager::removeDynamicObject);
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
