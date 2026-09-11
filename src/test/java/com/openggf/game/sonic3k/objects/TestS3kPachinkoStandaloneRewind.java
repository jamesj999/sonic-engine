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
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kPachinkoStandaloneRewind {

    private static final ObjectSpawn FLOATING_PLATFORM_SPAWN =
            new ObjectSpawn(0x0900, 0x0340, Sonic3kObjectIds.FLOATING_PLATFORM, 0x9C, 0x01, false, 201);
    private static final ObjectSpawn GUMBALL_TRIANGLE_BUMPER_SPAWN =
            new ObjectSpawn(0x0A00, 0x0400, Sonic3kObjectIds.GUMBALL_TRIANGLE_BUMPER, 0x02, 0x03, false, 202);
    private static final ObjectSpawn PACHINKO_BUMPER_SPAWN =
            new ObjectSpawn(0x0B00, 0x0500, Sonic3kObjectIds.BUMPER, 0x00, 0x00, false, 203);
    private static final ObjectSpawn PACHINKO_MAGNET_ORB_SPAWN =
            new ObjectSpawn(0x0C00, 0x0600, Sonic3kObjectIds.PACHINKO_MAGNET_ORB, 0x00, 0x01, false, 204);
    private static final ObjectSpawn PACHINKO_PLATFORM_SPAWN =
            new ObjectSpawn(0x0D00, 0x0700, Sonic3kObjectIds.PACHINKO_PLATFORM, 0x00, 0x02, false, 205);
    private static final ObjectSpawn PACHINKO_TRIANGLE_BUMPER_SPAWN =
            new ObjectSpawn(0x0E00, 0x0800, Sonic3kObjectIds.PACHINKO_TRIANGLE_BUMPER, 0x00, 0x03, false, 206);

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
    void s3kPachinkoStandaloneObjectsRestoreFreshWithoutDropsAndPreserveSpawnState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        FloatingPlatformObjectInstance floatingPlatform = objectManager.createDynamicObject(
                () -> new FloatingPlatformObjectInstance(FLOATING_PLATFORM_SPAWN));
        GumballTriangleBumperObjectInstance gumballTriangleBumper = objectManager.createDynamicObject(
                () -> new GumballTriangleBumperObjectInstance(GUMBALL_TRIANGLE_BUMPER_SPAWN));
        PachinkoBumperObjectInstance pachinkoBumper = objectManager.createDynamicObject(
                () -> new PachinkoBumperObjectInstance(PACHINKO_BUMPER_SPAWN));
        PachinkoMagnetOrbObjectInstance pachinkoMagnetOrb = objectManager.createDynamicObject(
                () -> new PachinkoMagnetOrbObjectInstance(PACHINKO_MAGNET_ORB_SPAWN));
        PachinkoPlatformObjectInstance pachinkoPlatform = objectManager.createDynamicObject(
                () -> new PachinkoPlatformObjectInstance(PACHINKO_PLATFORM_SPAWN));
        PachinkoTriangleBumperObjectInstance pachinkoTriangleBumper = objectManager.createDynamicObject(
                () -> new PachinkoTriangleBumperObjectInstance(PACHINKO_TRIANGLE_BUMPER_SPAWN));

        ObjectRefId floatingPlatformId = objectId(objectManager, floatingPlatform);
        ObjectRefId gumballTriangleBumperId = objectId(objectManager, gumballTriangleBumper);
        ObjectRefId pachinkoBumperId = objectId(objectManager, pachinkoBumper);
        ObjectRefId pachinkoMagnetOrbId = objectId(objectManager, pachinkoMagnetOrb);
        ObjectRefId pachinkoPlatformId = objectId(objectManager, pachinkoPlatform);
        ObjectRefId pachinkoTriangleBumperId = objectId(objectManager, pachinkoTriangleBumper);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(floatingPlatform);
        objectManager.removeDynamicObject(gumballTriangleBumper);
        objectManager.removeDynamicObject(pachinkoBumper);
        objectManager.removeDynamicObject(pachinkoMagnetOrb);
        objectManager.removeDynamicObject(pachinkoPlatform);
        objectManager.removeDynamicObject(pachinkoTriangleBumper);
        FloatingPlatformObjectInstance replacementFloatingPlatform = objectManager.createDynamicObject(
                () -> new FloatingPlatformObjectInstance(replacementSpawn(Sonic3kObjectIds.FLOATING_PLATFORM, 211)));
        GumballTriangleBumperObjectInstance replacementGumballTriangleBumper = objectManager.createDynamicObject(
                () -> new GumballTriangleBumperObjectInstance(
                        replacementSpawn(Sonic3kObjectIds.GUMBALL_TRIANGLE_BUMPER, 212)));
        PachinkoBumperObjectInstance replacementPachinkoBumper = objectManager.createDynamicObject(
                () -> new PachinkoBumperObjectInstance(replacementSpawn(Sonic3kObjectIds.BUMPER, 213)));
        PachinkoMagnetOrbObjectInstance replacementPachinkoMagnetOrb = objectManager.createDynamicObject(
                () -> new PachinkoMagnetOrbObjectInstance(
                        replacementSpawn(Sonic3kObjectIds.PACHINKO_MAGNET_ORB, 214)));
        PachinkoPlatformObjectInstance replacementPachinkoPlatform = objectManager.createDynamicObject(
                () -> new PachinkoPlatformObjectInstance(replacementSpawn(Sonic3kObjectIds.PACHINKO_PLATFORM, 215)));
        PachinkoTriangleBumperObjectInstance replacementPachinkoTriangleBumper = objectManager.createDynamicObject(
                () -> new PachinkoTriangleBumperObjectInstance(
                        replacementSpawn(Sonic3kObjectIds.PACHINKO_TRIANGLE_BUMPER, 216)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, FloatingPlatformObjectInstance.class).size(),
                "restore must keep exactly the captured floating platform");
        assertEquals(1, liveObjects(objectManager, GumballTriangleBumperObjectInstance.class).size(),
                "restore must keep exactly the captured gumball triangle bumper");
        assertEquals(1, liveObjects(objectManager, PachinkoBumperObjectInstance.class).size(),
                "restore must keep exactly the captured pachinko bumper");
        assertEquals(1, liveObjects(objectManager, PachinkoMagnetOrbObjectInstance.class).size(),
                "restore must keep exactly the captured pachinko magnet orb");
        assertEquals(1, liveObjects(objectManager, PachinkoPlatformObjectInstance.class).size(),
                "restore must keep exactly the captured pachinko platform");
        assertEquals(1, liveObjects(objectManager, PachinkoTriangleBumperObjectInstance.class).size(),
                "restore must keep exactly the captured pachinko triangle bumper");

        FloatingPlatformObjectInstance restoredFloatingPlatform =
                objectById(objectManager, FloatingPlatformObjectInstance.class, floatingPlatformId);
        GumballTriangleBumperObjectInstance restoredGumballTriangleBumper =
                objectById(objectManager, GumballTriangleBumperObjectInstance.class, gumballTriangleBumperId);
        PachinkoBumperObjectInstance restoredPachinkoBumper =
                objectById(objectManager, PachinkoBumperObjectInstance.class, pachinkoBumperId);
        PachinkoMagnetOrbObjectInstance restoredPachinkoMagnetOrb =
                objectById(objectManager, PachinkoMagnetOrbObjectInstance.class, pachinkoMagnetOrbId);
        PachinkoPlatformObjectInstance restoredPachinkoPlatform =
                objectById(objectManager, PachinkoPlatformObjectInstance.class, pachinkoPlatformId);
        PachinkoTriangleBumperObjectInstance restoredPachinkoTriangleBumper =
                objectById(objectManager, PachinkoTriangleBumperObjectInstance.class, pachinkoTriangleBumperId);

        assertFresh(floatingPlatform, replacementFloatingPlatform, restoredFloatingPlatform);
        assertFresh(gumballTriangleBumper, replacementGumballTriangleBumper, restoredGumballTriangleBumper);
        assertFresh(pachinkoBumper, replacementPachinkoBumper, restoredPachinkoBumper);
        assertFresh(pachinkoMagnetOrb, replacementPachinkoMagnetOrb, restoredPachinkoMagnetOrb);
        assertFresh(pachinkoPlatform, replacementPachinkoPlatform, restoredPachinkoPlatform);
        assertFresh(pachinkoTriangleBumper, replacementPachinkoTriangleBumper, restoredPachinkoTriangleBumper);

        assertEquals(FLOATING_PLATFORM_SPAWN, restoredFloatingPlatform.getSpawn());
        assertEquals(GUMBALL_TRIANGLE_BUMPER_SPAWN, restoredGumballTriangleBumper.getSpawn());
        assertEquals(PACHINKO_BUMPER_SPAWN, restoredPachinkoBumper.getSpawn());
        assertEquals(PACHINKO_MAGNET_ORB_SPAWN, restoredPachinkoMagnetOrb.getSpawn());
        assertEquals(PACHINKO_PLATFORM_SPAWN, restoredPachinkoPlatform.getSpawn());
        assertEquals(PACHINKO_TRIANGLE_BUMPER_SPAWN, restoredPachinkoTriangleBumper.getSpawn());

        assertEquals(FLOATING_PLATFORM_SPAWN.x(), readIntField(restoredFloatingPlatform, "baseX"));
        assertEquals(FLOATING_PLATFORM_SPAWN.y(), readIntField(restoredFloatingPlatform, "baseY"));
        assertEquals(0x0C, readIntField(restoredFloatingPlatform, "moveType"));
        assertEquals(0x18, readIntField(restoredFloatingPlatform, "halfWidth"));
        assertEquals(0x0C, readIntField(restoredFloatingPlatform, "halfHeight"));
        assertEquals(FLOATING_PLATFORM_SPAWN.x() + 0x100,
                readIntField(restoredFloatingPlatform, "outOfRangeReferenceX"));
        assertEquals(0x380, readIntField(restoredFloatingPlatform, "outOfRangeLimit"));
        assertFalse(readBooleanField(restoredGumballTriangleBumper, "consumed"));
        assertEquals(0, readIntField(restoredPachinkoBumper, "animFrame"));
        assertEquals(0, readIntField(restoredPachinkoBumper, "animTimer"));
        assertTrue(((Map<?, ?>) readObjectField(restoredPachinkoMagnetOrb, "playerStates")).isEmpty(),
                "pachinko magnet orb must restore with an empty per-player capture map");
        SolidObjectParams platformSolidParams = restoredPachinkoPlatform.getSolidParams();
        assertEquals(0x2B, platformSolidParams.halfWidth());
        assertEquals(0x0C, platformSolidParams.airHalfHeight());
        assertEquals(0x0D, platformSolidParams.groundHalfHeight());
        assertEquals(-1, readIntField(restoredPachinkoTriangleBumper, "hitAnimationFrame"));
    }

    @Test
    void s3kPachinkoStandaloneObjectsUseRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(FloatingPlatformObjectInstance.class),
                "FloatingPlatformObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(GumballTriangleBumperObjectInstance.class),
                "GumballTriangleBumperObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(PachinkoBumperObjectInstance.class),
                "PachinkoBumperObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(PachinkoMagnetOrbObjectInstance.class),
                "PachinkoMagnetOrbObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(PachinkoPlatformObjectInstance.class),
                "PachinkoPlatformObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(PachinkoTriangleBumperObjectInstance.class),
                "PachinkoTriangleBumperObjectInstance must restore through generic recreate");
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
                .sorted(Comparator.comparingInt(TestS3kPachinkoStandaloneRewind::slotIndex))
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

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
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
            @Override public short getWidth() { return 0x2000; }
            @Override public short getHeight() { return 0x900; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
