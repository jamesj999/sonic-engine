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

class TestS3kIczIceObjectRewind {

    private static final ObjectSpawn BREAKABLE_WALL_SPAWN =
            new ObjectSpawn(0x0900, 0x0300, Sonic3kObjectIds.ICZ_BREAKABLE_WALL, 0x00, 0x01, false, 111);
    private static final ObjectSpawn HARMFUL_ICE_SPAWN =
            new ObjectSpawn(0x0980, 0x0340, Sonic3kObjectIds.ICZ_HARMFUL_ICE, 0x02, 0x01, false, 112);
    private static final ObjectSpawn ICE_BLOCK_SPAWN =
            new ObjectSpawn(0x0A00, 0x0380, Sonic3kObjectIds.ICZ_ICE_BLOCK, 0x00, 0x01, false, 113);
    private static final ObjectSpawn ICE_CUBE_SPAWN =
            new ObjectSpawn(0x0A80, 0x03C0, Sonic3kObjectIds.ICZ_ICE_CUBE, 0x00, 0x01, false, 114);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x1000, 0x600, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void iczIceObjectsRestoreFreshWithoutDropsAndPreserveSpawnState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        IczBreakableWallObjectInstance breakableWall = objectManager.createDynamicObject(
                () -> new IczBreakableWallObjectInstance(BREAKABLE_WALL_SPAWN));
        IczHarmfulIceObjectInstance harmfulIce = objectManager.createDynamicObject(
                () -> new IczHarmfulIceObjectInstance(HARMFUL_ICE_SPAWN));
        IczIceBlockObjectInstance iceBlock = objectManager.createDynamicObject(
                () -> new IczIceBlockObjectInstance(ICE_BLOCK_SPAWN));
        IczIceCubeObjectInstance iceCube = objectManager.createDynamicObject(
                () -> new IczIceCubeObjectInstance(ICE_CUBE_SPAWN));

        ObjectRefId breakableWallId = objectId(objectManager, breakableWall);
        ObjectRefId harmfulIceId = objectId(objectManager, harmfulIce);
        ObjectRefId iceBlockId = objectId(objectManager, iceBlock);
        ObjectRefId iceCubeId = objectId(objectManager, iceCube);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(breakableWall);
        objectManager.removeDynamicObject(harmfulIce);
        objectManager.removeDynamicObject(iceBlock);
        objectManager.removeDynamicObject(iceCube);
        IczBreakableWallObjectInstance replacementBreakableWall = objectManager.createDynamicObject(
                () -> new IczBreakableWallObjectInstance(replacementSpawn(Sonic3kObjectIds.ICZ_BREAKABLE_WALL, 121)));
        IczHarmfulIceObjectInstance replacementHarmfulIce = objectManager.createDynamicObject(
                () -> new IczHarmfulIceObjectInstance(replacementSpawn(Sonic3kObjectIds.ICZ_HARMFUL_ICE, 122)));
        IczIceBlockObjectInstance replacementIceBlock = objectManager.createDynamicObject(
                () -> new IczIceBlockObjectInstance(replacementSpawn(Sonic3kObjectIds.ICZ_ICE_BLOCK, 123)));
        IczIceCubeObjectInstance replacementIceCube = objectManager.createDynamicObject(
                () -> new IczIceCubeObjectInstance(replacementSpawn(Sonic3kObjectIds.ICZ_ICE_CUBE, 124)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, IczBreakableWallObjectInstance.class).size(),
                "restore must keep exactly the captured ICZ breakable wall");
        assertEquals(1, liveObjects(objectManager, IczHarmfulIceObjectInstance.class).size(),
                "restore must keep exactly the captured ICZ harmful ice");
        assertEquals(1, liveObjects(objectManager, IczIceBlockObjectInstance.class).size(),
                "restore must keep exactly the captured ICZ ice block");
        assertEquals(1, liveObjects(objectManager, IczIceCubeObjectInstance.class).size(),
                "restore must keep exactly the captured ICZ ice cube");

        IczBreakableWallObjectInstance restoredBreakableWall =
                objectById(objectManager, IczBreakableWallObjectInstance.class, breakableWallId);
        IczHarmfulIceObjectInstance restoredHarmfulIce =
                objectById(objectManager, IczHarmfulIceObjectInstance.class, harmfulIceId);
        IczIceBlockObjectInstance restoredIceBlock =
                objectById(objectManager, IczIceBlockObjectInstance.class, iceBlockId);
        IczIceCubeObjectInstance restoredIceCube =
                objectById(objectManager, IczIceCubeObjectInstance.class, iceCubeId);

        assertFresh(breakableWall, replacementBreakableWall, restoredBreakableWall);
        assertFresh(harmfulIce, replacementHarmfulIce, restoredHarmfulIce);
        assertFresh(iceBlock, replacementIceBlock, restoredIceBlock);
        assertFresh(iceCube, replacementIceCube, restoredIceCube);

        assertEquals(BREAKABLE_WALL_SPAWN, restoredBreakableWall.getSpawn());
        assertEquals(HARMFUL_ICE_SPAWN, restoredHarmfulIce.getSpawn());
        assertEquals(ICE_BLOCK_SPAWN, restoredIceBlock.getSpawn());
        assertEquals(ICE_CUBE_SPAWN, restoredIceCube.getSpawn());

        assertEquals(BREAKABLE_WALL_SPAWN.x(), restoredBreakableWall.getX());
        assertTrue(readBooleanField(restoredBreakableWall, "hFlip"),
                "ICZ breakable wall flip must rebuild from spawn flags");
        assertEquals(0xD7, restoredHarmfulIce.getCollisionFlags(),
                "ICZ harmful ice break collision must rebuild from subtype");
        assertEquals(ICE_BLOCK_SPAWN.y(), restoredIceBlock.getY(),
                "ICZ ice block Y must rebuild from spawn");
        assertEquals(0x2E, restoredIceCube.getCollisionFlags(),
                "ICZ ice cube collision flags must rebuild from constructor state");
    }

    @Test
    void iczIceObjectsUseRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczBreakableWallObjectInstance.class),
                "IczBreakableWallObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczHarmfulIceObjectInstance.class),
                "IczHarmfulIceObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczIceBlockObjectInstance.class),
                "IczIceBlockObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczIceCubeObjectInstance.class),
                "IczIceCubeObjectInstance must restore through generic recreate");
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
                .sorted(Comparator.comparingInt(TestS3kIczIceObjectRewind::slotIndex))
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
            @Override public short getWidth() { return 0x1000; }
            @Override public short getHeight() { return 0x600; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
