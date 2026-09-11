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

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAiz1StaticSceneryRewind {

    private static final ObjectSpawn TREE_SPAWN =
            new ObjectSpawn(0x0180, 0x0100, Sonic3kObjectIds.AIZ1_TREE, 0, 0, false, 31);
    private static final ObjectSpawn PEG_SPAWN =
            new ObjectSpawn(0x01C0, 0x00D0, Sonic3kObjectIds.AIZ1_ZIPLINE_PEG, 0, 0, false, 32);

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
    void staticSceneryRestoresFreshWithoutDropsOrDuplicates() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Aiz1TreeObjectInstance tree = objectManager.createDynamicObject(
                () -> new Aiz1TreeObjectInstance(TREE_SPAWN));
        Aiz1ZiplinePegObjectInstance peg = objectManager.createDynamicObject(
                () -> new Aiz1ZiplinePegObjectInstance(PEG_SPAWN));

        ObjectRefId treeId = objectId(objectManager, tree);
        ObjectRefId pegId = objectId(objectManager, peg);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(tree);
        objectManager.removeDynamicObject(peg);
        Aiz1TreeObjectInstance replacementTree = objectManager.createDynamicObject(
                () -> new Aiz1TreeObjectInstance(
                        new ObjectSpawn(0x0200, 0x0200, Sonic3kObjectIds.AIZ1_TREE, 0, 0, false, 33)));
        Aiz1ZiplinePegObjectInstance replacementPeg = objectManager.createDynamicObject(
                () -> new Aiz1ZiplinePegObjectInstance(
                        new ObjectSpawn(0x0240, 0x0200, Sonic3kObjectIds.AIZ1_ZIPLINE_PEG, 0, 0, false, 34)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, Aiz1TreeObjectInstance.class).size(),
                "restore must keep exactly the captured AIZ1 tree");
        assertEquals(1, liveObjects(objectManager, Aiz1ZiplinePegObjectInstance.class).size(),
                "restore must keep exactly the captured AIZ1 zipline peg");

        Aiz1TreeObjectInstance restoredTree =
                objectById(objectManager, Aiz1TreeObjectInstance.class, treeId);
        Aiz1ZiplinePegObjectInstance restoredPeg =
                objectById(objectManager, Aiz1ZiplinePegObjectInstance.class, pegId);

        assertNotSame(tree, restoredTree, "restore must recreate the tree");
        assertNotSame(peg, restoredPeg, "restore must recreate the zipline peg");
        assertNotSame(replacementTree, restoredTree, "restore must drop the divergent tree");
        assertNotSame(replacementPeg, restoredPeg, "restore must drop the divergent zipline peg");
        assertEquals(TREE_SPAWN, restoredTree.getSpawn(),
                "tree spawn must restore exactly");
        assertEquals(PEG_SPAWN, restoredPeg.getSpawn(),
                "zipline peg spawn must restore exactly");
    }

    @Test
    void staticSceneryUsesRewindRecreatableWithoutExplicitCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Aiz1TreeObjectInstance.class),
                "Aiz1TreeObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Aiz1ZiplinePegObjectInstance.class),
                "Aiz1ZiplinePegObjectInstance must restore through generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Aiz1TreeObjectInstance.class.getName()),
                "Aiz1TreeObjectInstance must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Aiz1ZiplinePegObjectInstance.class.getName()),
                "Aiz1ZiplinePegObjectInstance must not keep an explicit S3K dynamic codec");
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
                .sorted(Comparator.comparingInt(TestAiz1StaticSceneryRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
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
