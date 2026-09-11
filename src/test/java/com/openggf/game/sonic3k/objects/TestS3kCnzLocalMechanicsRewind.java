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

class TestS3kCnzLocalMechanicsRewind {

    private static final ObjectSpawn BALLOON_SPAWN =
            new ObjectSpawn(0x0280, 0x0140, Sonic3kObjectIds.CNZ_BALLOON, 0x82, 0, false, 81);
    private static final ObjectSpawn RISING_PLATFORM_SPAWN =
            new ObjectSpawn(0x0340, 0x0180, Sonic3kObjectIds.CNZ_RISING_PLATFORM, 0x20, 0, false, 82);
    private static final ObjectSpawn LIGHT_BULB_SPAWN =
            new ObjectSpawn(0x03C0, 0x0120, Sonic3kObjectIds.CNZ_LIGHT_BULB, 0x00, 0, false, 83);
    private static final ObjectSpawn BARBER_POLE_SPAWN =
            new ObjectSpawn(0x0440, 0x0160, Sonic3kObjectIds.CNZ_BARBER_POLE, 0x01, 0, false, 84);

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
    void cnzLocalMechanicsRestoreFreshWithoutDropsAndPreserveSpawnState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        CnzBalloonInstance balloon = objectManager.createDynamicObject(
                () -> new CnzBalloonInstance(BALLOON_SPAWN));
        CnzRisingPlatformInstance risingPlatform = objectManager.createDynamicObject(
                () -> new CnzRisingPlatformInstance(RISING_PLATFORM_SPAWN));
        CnzLightBulbInstance lightBulb = objectManager.createDynamicObject(
                () -> new CnzLightBulbInstance(LIGHT_BULB_SPAWN));
        CnzBarberPoleObjectInstance barberPole = objectManager.createDynamicObject(
                () -> new CnzBarberPoleObjectInstance(BARBER_POLE_SPAWN));

        ObjectRefId balloonId = objectId(objectManager, balloon);
        ObjectRefId risingPlatformId = objectId(objectManager, risingPlatform);
        ObjectRefId lightBulbId = objectId(objectManager, lightBulb);
        ObjectRefId barberPoleId = objectId(objectManager, barberPole);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(balloon);
        objectManager.removeDynamicObject(risingPlatform);
        objectManager.removeDynamicObject(lightBulb);
        objectManager.removeDynamicObject(barberPole);
        CnzBalloonInstance replacementBalloon = objectManager.createDynamicObject(
                () -> new CnzBalloonInstance(new ObjectSpawn(
                        0x0100, 0x0100, Sonic3kObjectIds.CNZ_BALLOON, 0x00, 0, false, 85)));
        CnzRisingPlatformInstance replacementRisingPlatform = objectManager.createDynamicObject(
                () -> new CnzRisingPlatformInstance(new ObjectSpawn(
                        0x0140, 0x0100, Sonic3kObjectIds.CNZ_RISING_PLATFORM, 0x00, 0, false, 86)));
        CnzLightBulbInstance replacementLightBulb = objectManager.createDynamicObject(
                () -> new CnzLightBulbInstance(new ObjectSpawn(
                        0x0180, 0x0100, Sonic3kObjectIds.CNZ_LIGHT_BULB, 0x00, 0, false, 87)));
        CnzBarberPoleObjectInstance replacementBarberPole = objectManager.createDynamicObject(
                () -> new CnzBarberPoleObjectInstance(new ObjectSpawn(
                        0x01C0, 0x0100, Sonic3kObjectIds.CNZ_BARBER_POLE, 0x00, 0, false, 88)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, CnzBalloonInstance.class).size(),
                "restore must keep exactly the captured CNZ balloon");
        assertEquals(1, liveObjects(objectManager, CnzRisingPlatformInstance.class).size(),
                "restore must keep exactly the captured CNZ rising platform");
        assertEquals(1, liveObjects(objectManager, CnzLightBulbInstance.class).size(),
                "restore must keep exactly the captured CNZ light bulb");
        assertEquals(1, liveObjects(objectManager, CnzBarberPoleObjectInstance.class).size(),
                "restore must keep exactly the captured CNZ barber pole");

        CnzBalloonInstance restoredBalloon =
                objectById(objectManager, CnzBalloonInstance.class, balloonId);
        CnzRisingPlatformInstance restoredRisingPlatform =
                objectById(objectManager, CnzRisingPlatformInstance.class, risingPlatformId);
        CnzLightBulbInstance restoredLightBulb =
                objectById(objectManager, CnzLightBulbInstance.class, lightBulbId);
        CnzBarberPoleObjectInstance restoredBarberPole =
                objectById(objectManager, CnzBarberPoleObjectInstance.class, barberPoleId);

        assertNotSame(balloon, restoredBalloon, "restore must recreate the CNZ balloon");
        assertNotSame(risingPlatform, restoredRisingPlatform, "restore must recreate the CNZ rising platform");
        assertNotSame(lightBulb, restoredLightBulb, "restore must recreate the CNZ light bulb");
        assertNotSame(barberPole, restoredBarberPole, "restore must recreate the CNZ barber pole");
        assertNotSame(replacementBalloon, restoredBalloon, "restore must drop the divergent CNZ balloon");
        assertNotSame(replacementRisingPlatform, restoredRisingPlatform,
                "restore must drop the divergent CNZ rising platform");
        assertNotSame(replacementLightBulb, restoredLightBulb,
                "restore must drop the divergent CNZ light bulb");
        assertNotSame(replacementBarberPole, restoredBarberPole,
                "restore must drop the divergent CNZ barber pole");

        assertEquals(BALLOON_SPAWN, restoredBalloon.getSpawn(), "CNZ balloon spawn must restore exactly");
        assertEquals(RISING_PLATFORM_SPAWN, restoredRisingPlatform.getSpawn(),
                "CNZ rising platform spawn must restore exactly");
        assertEquals(LIGHT_BULB_SPAWN, restoredLightBulb.getSpawn(),
                "CNZ light bulb spawn must restore exactly");
        assertEquals(BARBER_POLE_SPAWN, restoredBarberPole.getSpawn(),
                "CNZ barber pole spawn must restore exactly");

        assertEquals(BALLOON_SPAWN.subtype(), readIntField(restoredBalloon, "subtype"),
                "CNZ balloon subtype must rebuild from spawn");
        assertEquals(BALLOON_SPAWN.y(), readIntField(restoredBalloon, "baseY"),
                "CNZ balloon base Y must rebuild from spawn");
        assertEquals(0, restoredRisingPlatform.getRenderFrameForTest(),
                "CNZ rising platform initial frame must rebuild from spawn");
        assertEquals(0, restoredLightBulb.getRenderFrame(),
                "CNZ light bulb initial frame must rebuild from spawn");
        assertTrue(readBooleanField(restoredBarberPole, "mirrored"),
                "CNZ barber pole mirrored flag must rebuild from subtype");
    }

    @Test
    void cnzLocalMechanicsUseRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzBalloonInstance.class),
                "CnzBalloonInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzRisingPlatformInstance.class),
                "CnzRisingPlatformInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzLightBulbInstance.class),
                "CnzLightBulbInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(CnzBarberPoleObjectInstance.class),
                "CnzBarberPoleObjectInstance must restore through generic recreate");
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
                .sorted(Comparator.comparingInt(TestS3kCnzLocalMechanicsRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
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
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x500; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
