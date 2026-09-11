package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.objects.BridgeObjectInstance;
import com.openggf.game.sonic2.objects.BridgeSegmentObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Graph-rewind coverage for Obj11's parent-owned bridge segment slots. */
class TestS2BridgeSegmentGraphRewind {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void bothSegmentsRestoreThroughLiveParentWithPreservedIdentity() {
        ObjectManager objectManager = createObjectManager();
        BridgeObjectInstance parent = objectManager.createDynamicObject(() ->
                new BridgeObjectInstance(
                        new ObjectSpawn(0x0400, 0x0300, 0x11, 12, 0, false, 0),
                        "Bridge"));
        parent.update(0, null);

        List<BridgeSegmentObjectInstance> before = segmentsOf(objectManager);
        assertEquals(2, before.size(), "a 12-log bridge must allocate both Obj11 segment slots");
        List<ObjectRefId> beforeIds = before.stream()
                .map(segment -> objectId(objectManager, segment))
                .toList();

        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();
        before.forEach(objectManager::removeDynamicObject);

        registry.restore(snapshot);

        List<BridgeSegmentObjectInstance> restored = segmentsOf(objectManager);
        BridgeObjectInstance restoredParent = objectManager.getActiveObjects().stream()
                .filter(BridgeObjectInstance.class::isInstance)
                .map(BridgeObjectInstance.class::cast)
                .findFirst()
                .orElse(null);
        assertNotNull(restoredParent, "restore must recreate the Obj11 parent");
        assertEquals(2, restored.size(), "restore must recreate both Obj11 segment slots");
        assertEquals(beforeIds, restored.stream()
                .map(segment -> objectId(objectManager, segment))
                .toList(), "segment dynamic identities must be preserved");
        for (int i = 0; i < restored.size(); i++) {
            assertNotSame(before.get(i), restored.get(i), "restore must recreate each segment");
            assertSame(restoredParent, restored.get(i).getParentBridge(),
                    "recreateForRewind must reattach each segment to its live parent");
        }
    }

    private static List<BridgeSegmentObjectInstance> segmentsOf(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(BridgeSegmentObjectInstance.class::isInstance)
                .map(BridgeSegmentObjectInstance.class::cast)
                .toList();
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static ObjectManager createObjectManager() {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(() -> null, List::of);
        Camera camera = new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public ObjectPlayerQuery playerQuery() { return playerQuery; }
        };
        ObjectManager objectManager = new ObjectManager(
                List.of(), null, 0, null, null, GraphicsManager.getInstance(), camera, services);
        holder[0] = objectManager;
        objectManager.reset(0);
        return objectManager;
    }
}
