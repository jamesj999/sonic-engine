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

class TestS3kControllerObjectRewind {

    private static final ObjectSpawn TWISTED_RAMP_SPAWN =
            new ObjectSpawn(0x1200, 0x0300, Sonic3kObjectIds.TWISTED_RAMP, 0x00, 0x01, false, 201);
    private static final ObjectSpawn UPDRAFT_SPAWN =
            new ObjectSpawn(0x1300, 0x0400, Sonic3kObjectIds.UPDRAFT, 0x85, 0x00, false, 202);
    private static final ObjectSpawn HCZ_TWISTING_LOOP_SPAWN =
            new ObjectSpawn(0x3440, 0x07F4, Sonic3kObjectIds.HCZ_TWISTING_LOOP, 0x83, 0x01, false, 203);
    private static final ObjectSpawn MGZ_TWISTING_LOOP_SPAWN =
            new ObjectSpawn(0x1800, 0x0500, Sonic3kObjectIds.MGZ_TWISTING_LOOP, 0x2A, 0x01, false, 204);
    private static final ObjectSpawn MHZ_TWISTED_VINE_SPAWN =
            new ObjectSpawn(0x1900, 0x0600, Sonic3kObjectIds.MHZ_TWISTED_VINE, 0x00, 0x01, false, 205);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x4000, 0x1000, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void s3kControllerObjectsRestoreFreshWithoutDropsAndPreserveSpawnState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Sonic3kTwistedRampObjectInstance twistedRamp = objectManager.createDynamicObject(
                () -> new Sonic3kTwistedRampObjectInstance(TWISTED_RAMP_SPAWN));
        UpdraftObjectInstance updraft = objectManager.createDynamicObject(
                () -> new UpdraftObjectInstance(UPDRAFT_SPAWN));
        HCZTwistingLoopObjectInstance hczLoop = objectManager.createDynamicObject(
                () -> new HCZTwistingLoopObjectInstance(HCZ_TWISTING_LOOP_SPAWN));
        MGZTwistingLoopObjectInstance mgzLoop = objectManager.createDynamicObject(
                () -> new MGZTwistingLoopObjectInstance(MGZ_TWISTING_LOOP_SPAWN));
        MhzTwistedVineObjectInstance mhzVine = objectManager.createDynamicObject(
                () -> new MhzTwistedVineObjectInstance(MHZ_TWISTED_VINE_SPAWN));

        ObjectRefId twistedRampId = objectId(objectManager, twistedRamp);
        ObjectRefId updraftId = objectId(objectManager, updraft);
        ObjectRefId hczLoopId = objectId(objectManager, hczLoop);
        ObjectRefId mgzLoopId = objectId(objectManager, mgzLoop);
        ObjectRefId mhzVineId = objectId(objectManager, mhzVine);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(twistedRamp);
        objectManager.removeDynamicObject(updraft);
        objectManager.removeDynamicObject(hczLoop);
        objectManager.removeDynamicObject(mgzLoop);
        objectManager.removeDynamicObject(mhzVine);
        Sonic3kTwistedRampObjectInstance replacementTwistedRamp = objectManager.createDynamicObject(
                () -> new Sonic3kTwistedRampObjectInstance(replacementSpawn(Sonic3kObjectIds.TWISTED_RAMP, 211)));
        UpdraftObjectInstance replacementUpdraft = objectManager.createDynamicObject(
                () -> new UpdraftObjectInstance(replacementSpawn(Sonic3kObjectIds.UPDRAFT, 212)));
        HCZTwistingLoopObjectInstance replacementHczLoop = objectManager.createDynamicObject(
                () -> new HCZTwistingLoopObjectInstance(replacementSpawn(Sonic3kObjectIds.HCZ_TWISTING_LOOP, 213)));
        MGZTwistingLoopObjectInstance replacementMgzLoop = objectManager.createDynamicObject(
                () -> new MGZTwistingLoopObjectInstance(replacementSpawn(Sonic3kObjectIds.MGZ_TWISTING_LOOP, 214)));
        MhzTwistedVineObjectInstance replacementMhzVine = objectManager.createDynamicObject(
                () -> new MhzTwistedVineObjectInstance(replacementSpawn(Sonic3kObjectIds.MHZ_TWISTED_VINE, 215)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, Sonic3kTwistedRampObjectInstance.class).size(),
                "restore must keep exactly the captured twisted ramp");
        assertEquals(1, liveObjects(objectManager, UpdraftObjectInstance.class).size(),
                "restore must keep exactly the captured updraft");
        assertEquals(1, liveObjects(objectManager, HCZTwistingLoopObjectInstance.class).size(),
                "restore must keep exactly the captured HCZ twisting loop");
        assertEquals(1, liveObjects(objectManager, MGZTwistingLoopObjectInstance.class).size(),
                "restore must keep exactly the captured MGZ twisting loop");
        assertEquals(1, liveObjects(objectManager, MhzTwistedVineObjectInstance.class).size(),
                "restore must keep exactly the captured MHZ twisted vine");

        Sonic3kTwistedRampObjectInstance restoredTwistedRamp =
                objectById(objectManager, Sonic3kTwistedRampObjectInstance.class, twistedRampId);
        UpdraftObjectInstance restoredUpdraft =
                objectById(objectManager, UpdraftObjectInstance.class, updraftId);
        HCZTwistingLoopObjectInstance restoredHczLoop =
                objectById(objectManager, HCZTwistingLoopObjectInstance.class, hczLoopId);
        MGZTwistingLoopObjectInstance restoredMgzLoop =
                objectById(objectManager, MGZTwistingLoopObjectInstance.class, mgzLoopId);
        MhzTwistedVineObjectInstance restoredMhzVine =
                objectById(objectManager, MhzTwistedVineObjectInstance.class, mhzVineId);

        assertFresh(twistedRamp, replacementTwistedRamp, restoredTwistedRamp);
        assertFresh(updraft, replacementUpdraft, restoredUpdraft);
        assertFresh(hczLoop, replacementHczLoop, restoredHczLoop);
        assertFresh(mgzLoop, replacementMgzLoop, restoredMgzLoop);
        assertFresh(mhzVine, replacementMhzVine, restoredMhzVine);

        assertEquals(TWISTED_RAMP_SPAWN, restoredTwistedRamp.getSpawn());
        assertEquals(UPDRAFT_SPAWN, restoredUpdraft.getSpawn());
        assertEquals(HCZ_TWISTING_LOOP_SPAWN, restoredHczLoop.getSpawn());
        assertEquals(MGZ_TWISTING_LOOP_SPAWN, restoredMgzLoop.getSpawn());
        assertEquals(MHZ_TWISTED_VINE_SPAWN, restoredMhzVine.getSpawn());

        assertTrue(readBooleanField(restoredTwistedRamp, "facingLeft"),
                "twisted ramp direction must rebuild from render flags");
        assertEquals(0x28, readIntField(restoredUpdraft, "innerRange"),
                "updraft inner range must rebuild from subtype");
        assertEquals(0x38, readIntField(restoredUpdraft, "outerRange"),
                "updraft outer range must rebuild from subtype");
        assertTrue(readBooleanField(restoredUpdraft, "negativeSubtype"),
                "updraft negative-subtype flag must rebuild from subtype");
        assertEquals(0x83, readIntField(restoredHczLoop, "subtype"),
                "HCZ loop subtype must rebuild from spawn");
        assertTrue(readBooleanField(restoredHczLoop, "reverseEntry"),
                "HCZ loop reverse-entry flag must rebuild from subtype");
        assertTrue(readBooleanField(restoredHczLoop, "objectFlippedX"),
                "HCZ loop flip flag must rebuild from render flags");
        assertEquals(MGZ_TWISTING_LOOP_SPAWN.x(), readIntField(restoredMgzLoop, "centerX"),
                "MGZ loop center X must rebuild from spawn");
        assertEquals(MGZ_TWISTING_LOOP_SPAWN.y(), readIntField(restoredMgzLoop, "centerY"),
                "MGZ loop center Y must rebuild from spawn");
        assertEquals(0x2A0, readIntField(restoredMgzLoop, "captureThreshold"),
                "MGZ loop capture threshold must rebuild from subtype");
        assertTrue(readBooleanField(restoredMgzLoop, "flipped"),
                "MGZ loop flip flag must rebuild from render flags");
        assertTrue(readBooleanField(restoredMhzVine, "upperVariant"),
                "MHZ twisted vine variant must rebuild from render flags");
    }

    @Test
    void s3kControllerObjectsUseRewindRecreatable() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic3kTwistedRampObjectInstance.class),
                "Sonic3kTwistedRampObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(UpdraftObjectInstance.class),
                "UpdraftObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(HCZTwistingLoopObjectInstance.class),
                "HCZTwistingLoopObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(MGZTwistingLoopObjectInstance.class),
                "MGZTwistingLoopObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(MhzTwistedVineObjectInstance.class),
                "MhzTwistedVineObjectInstance must restore through generic recreate");
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
                .sorted(Comparator.comparingInt(TestS3kControllerObjectRewind::slotIndex))
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
            @Override public short getWidth() { return 0x4000; }
            @Override public short getHeight() { return 0x1000; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
