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

class TestS3kIczPlatformHazardRewind {

    private static final ObjectSpawn PATH_FOLLOW_PLATFORM_SPAWN =
            new ObjectSpawn(0x1500, 0x0380, Sonic3kObjectIds.ICZ_PATH_FOLLOW_PLATFORM, 0x02, 0x01, false, 161);
    private static final ObjectSpawn SWINGING_PLATFORM_SPAWN =
            new ObjectSpawn(0x1600, 0x0400, Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 0x01, 0x01, false, 162);
    private static final ObjectSpawn STALAGTITE_SPAWN =
            new ObjectSpawn(0x1700, 0x0300, Sonic3kObjectIds.ICZ_STALAGTITE, 0x00, 0x01, false, 163);
    private static final ObjectSpawn SNOW_PILE_SPAWN =
            new ObjectSpawn(0x1800, 0x0500, Sonic3kObjectIds.ICZ_SNOW_PILE, 0x10, 0x01, false, 164);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x2400, 0x900, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void iczPlatformHazardsRestoreFreshWithoutDropsAndPreserveSpawnState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        IczPathFollowPlatformObjectInstance pathFollowPlatform = objectManager.createDynamicObject(
                () -> new IczPathFollowPlatformObjectInstance(PATH_FOLLOW_PLATFORM_SPAWN));
        IczSwingingPlatformObjectInstance swingingPlatform = objectManager.createDynamicObject(
                () -> new IczSwingingPlatformObjectInstance(SWINGING_PLATFORM_SPAWN));
        IczStalagtiteObjectInstance stalagtite = objectManager.createDynamicObject(
                () -> new IczStalagtiteObjectInstance(STALAGTITE_SPAWN));
        IczSnowPileObjectInstance snowPile = objectManager.createDynamicObject(
                () -> new IczSnowPileObjectInstance(SNOW_PILE_SPAWN));

        ObjectRefId pathFollowPlatformId = objectId(objectManager, pathFollowPlatform);
        ObjectRefId swingingPlatformId = objectId(objectManager, swingingPlatform);
        ObjectRefId stalagtiteId = objectId(objectManager, stalagtite);
        ObjectRefId snowPileId = objectId(objectManager, snowPile);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(pathFollowPlatform);
        objectManager.removeDynamicObject(swingingPlatform);
        objectManager.removeDynamicObject(stalagtite);
        objectManager.removeDynamicObject(snowPile);
        IczPathFollowPlatformObjectInstance replacementPathFollowPlatform = objectManager.createDynamicObject(
                () -> new IczPathFollowPlatformObjectInstance(
                        replacementSpawn(Sonic3kObjectIds.ICZ_PATH_FOLLOW_PLATFORM, 171)));
        IczSwingingPlatformObjectInstance replacementSwingingPlatform = objectManager.createDynamicObject(
                () -> new IczSwingingPlatformObjectInstance(
                        replacementSpawn(Sonic3kObjectIds.ICZ_SWINGING_PLATFORM, 172)));
        IczStalagtiteObjectInstance replacementStalagtite = objectManager.createDynamicObject(
                () -> new IczStalagtiteObjectInstance(replacementSpawn(Sonic3kObjectIds.ICZ_STALAGTITE, 173)));
        IczSnowPileObjectInstance replacementSnowPile = objectManager.createDynamicObject(
                () -> new IczSnowPileObjectInstance(replacementSpawn(Sonic3kObjectIds.ICZ_SNOW_PILE, 174)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, IczPathFollowPlatformObjectInstance.class).size(),
                "restore must keep exactly the captured ICZ path-follow platform");
        assertEquals(1, liveObjects(objectManager, IczSwingingPlatformObjectInstance.class).size(),
                "restore must keep exactly the captured ICZ swinging platform");
        assertEquals(1, liveObjects(objectManager, IczStalagtiteObjectInstance.class).size(),
                "restore must keep exactly the captured ICZ stalagtite");
        assertEquals(1, liveObjects(objectManager, IczSnowPileObjectInstance.class).size(),
                "restore must keep exactly the captured ICZ snow pile");

        IczPathFollowPlatformObjectInstance restoredPathFollowPlatform =
                objectById(objectManager, IczPathFollowPlatformObjectInstance.class, pathFollowPlatformId);
        IczSwingingPlatformObjectInstance restoredSwingingPlatform =
                objectById(objectManager, IczSwingingPlatformObjectInstance.class, swingingPlatformId);
        IczStalagtiteObjectInstance restoredStalagtite =
                objectById(objectManager, IczStalagtiteObjectInstance.class, stalagtiteId);
        IczSnowPileObjectInstance restoredSnowPile =
                objectById(objectManager, IczSnowPileObjectInstance.class, snowPileId);

        assertFresh(pathFollowPlatform, replacementPathFollowPlatform, restoredPathFollowPlatform);
        assertFresh(swingingPlatform, replacementSwingingPlatform, restoredSwingingPlatform);
        assertFresh(stalagtite, replacementStalagtite, restoredStalagtite);
        assertFresh(snowPile, replacementSnowPile, restoredSnowPile);

        assertEquals(PATH_FOLLOW_PLATFORM_SPAWN, restoredPathFollowPlatform.getSpawn());
        assertEquals(SWINGING_PLATFORM_SPAWN, restoredSwingingPlatform.getSpawn());
        assertEquals(STALAGTITE_SPAWN, restoredStalagtite.getSpawn());
        assertEquals(SNOW_PILE_SPAWN, restoredSnowPile.getSpawn());

        assertEquals(0x06, restoredPathFollowPlatform.getRoutineByteForTesting(),
                "ICZ path-follow platform routine must rebuild from subtype");
        assertTrue(readBooleanField(restoredSwingingPlatform, "xFlip"),
                "ICZ swinging platform flip must rebuild from render flags");
        assertEquals("OFFSCREEN", restoredStalagtite.getPhaseNameForTesting(),
                "ICZ stalagtite phase must rebuild from constructor state before compact restore");
        assertTrue(restoredSnowPile.isHighPriority(),
                "ICZ snow pile launch variant must rebuild from subtype");
    }

    @Test
    void iczPlatformHazardsUseRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczPathFollowPlatformObjectInstance.class),
                "IczPathFollowPlatformObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczSwingingPlatformObjectInstance.class),
                "IczSwingingPlatformObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczStalagtiteObjectInstance.class),
                "IczStalagtiteObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczSnowPileObjectInstance.class),
                "IczSnowPileObjectInstance must restore through generic recreate");
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
                .sorted(Comparator.comparingInt(TestS3kIczPlatformHazardRewind::slotIndex))
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
            @Override public short getWidth() { return 0x2400; }
            @Override public short getHeight() { return 0x900; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
