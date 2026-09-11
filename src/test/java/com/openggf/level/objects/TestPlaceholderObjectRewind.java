package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPlaceholderObjectRewind {

    private static final ObjectSpawn PLACEHOLDER_SPAWN =
            new ObjectSpawn(0x0300, 0x0180, 0xFFFF, 0x03, 0x01, false, 751);

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
    void placeholderObjectRestoresFreshWithoutDropOrDuplicate() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        PlaceholderObjectInstance placeholder = objectManager.createDynamicObject(
                () -> new PlaceholderObjectInstance(PLACEHOLDER_SPAWN, "Placeholder"));
        ObjectRefId placeholderId = objectId(objectManager, placeholder);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(placeholder);
        PlaceholderObjectInstance replacement = objectManager.createDynamicObject(
                () -> new PlaceholderObjectInstance(
                        new ObjectSpawn(0x0100, 0x0100, 0xFFFF, 0, 0, false, 752),
                        "Replacement"));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, PlaceholderObjectInstance.class).size(),
                "restore must keep exactly the captured placeholder object");
        PlaceholderObjectInstance restored =
                objectById(objectManager, PlaceholderObjectInstance.class, placeholderId);
        assertNotSame(placeholder, restored, "restore must recreate the placeholder object");
        assertNotSame(replacement, restored, "restore must drop the divergent placeholder object");

        assertEquals(PLACEHOLDER_SPAWN, restored.getSpawn());
        assertEquals(PLACEHOLDER_SPAWN.x(), restored.getX());
        assertEquals(PLACEHOLDER_SPAWN.y(), restored.getY());
    }

    @Test
    void placeholderObjectUsesRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(PlaceholderObjectInstance.class),
                "PlaceholderObjectInstance must restore through generic recreate");
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
                    new Sonic2ObjectRegistry(),
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
                .sorted(Comparator.comparingInt(TestPlaceholderObjectRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
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
