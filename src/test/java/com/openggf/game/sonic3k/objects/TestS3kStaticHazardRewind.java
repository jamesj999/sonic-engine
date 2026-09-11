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

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kStaticHazardRewind {

    private static final ObjectSpawn STILL_SPRITE_SPAWN =
            new ObjectSpawn(0x02A0, 0x0128, Sonic3kObjectIds.STILL_SPRITE, 5, 0, false, 51);
    private static final ObjectSpawn SPIKE_SPAWN =
            new ObjectSpawn(0x0310, 0x0180, Sonic3kObjectIds.SPIKES, 0x13, 0, false, 52);

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
    void staticHazardsRestoreFreshWithoutDropsAndPreserveState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        StillSpriteInstance stillSprite = objectManager.createDynamicObject(
                () -> new StillSpriteInstance(STILL_SPRITE_SPAWN));
        Sonic3kSpikeObjectInstance spike = objectManager.createDynamicObject(
                () -> new Sonic3kSpikeObjectInstance(SPIKE_SPAWN));
        seedSpikeState(spike);

        ObjectRefId stillSpriteId = objectId(objectManager, stillSprite);
        ObjectRefId spikeId = objectId(objectManager, spike);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(stillSprite);
        objectManager.removeDynamicObject(spike);
        StillSpriteInstance replacementStillSprite = objectManager.createDynamicObject(
                () -> new StillSpriteInstance(
                        new ObjectSpawn(0x0100, 0x0100,
                                Sonic3kObjectIds.STILL_SPRITE, 0, 0, false, 53)));
        Sonic3kSpikeObjectInstance replacementSpike = objectManager.createDynamicObject(
                () -> new Sonic3kSpikeObjectInstance(
                        new ObjectSpawn(0x0140, 0x0100,
                                Sonic3kObjectIds.SPIKES, 0, 0, false, 54)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, StillSpriteInstance.class).size(),
                "restore must keep exactly the captured still sprite");
        assertEquals(1, liveObjects(objectManager, Sonic3kSpikeObjectInstance.class).size(),
                "restore must keep exactly the captured spike object");

        StillSpriteInstance restoredStillSprite =
                objectById(objectManager, StillSpriteInstance.class, stillSpriteId);
        Sonic3kSpikeObjectInstance restoredSpike =
                objectById(objectManager, Sonic3kSpikeObjectInstance.class, spikeId);

        assertNotSame(stillSprite, restoredStillSprite, "restore must recreate the still sprite");
        assertNotSame(spike, restoredSpike, "restore must recreate the spike object");
        assertNotSame(replacementStillSprite, restoredStillSprite,
                "restore must drop the divergent still sprite");
        assertNotSame(replacementSpike, restoredSpike,
                "restore must drop the divergent spike object");

        assertEquals(STILL_SPRITE_SPAWN, restoredStillSprite.getSpawn(),
                "still sprite spawn must restore exactly");
        assertEquals(16, restoredStillSprite.getOnScreenHalfWidth(),
                "still sprite subtype dimensions must rebuild from spawn");
        assertEquals(16, restoredStillSprite.getOnScreenHalfHeight(),
                "still sprite subtype dimensions must rebuild from spawn");
        assertEquals(6, restoredStillSprite.getPriorityBucket(),
                "still sprite priority must rebuild from spawn");
        assertTrue(restoredStillSprite.isHighPriority(),
                "still sprite high-priority flag must rebuild from spawn");

        assertEquals(SPIKE_SPAWN, restoredSpike.getSpawn(),
                "spike spawn must restore exactly");
        assertSeededSpikeState(restoredSpike);
    }

    @Test
    void staticHazardsUseRewindRecreatableWithoutExplicitCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(StillSpriteInstance.class),
                "StillSpriteInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic3kSpikeObjectInstance.class),
                "Sonic3kSpikeObjectInstance must restore through generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        StillSpriteInstance.class.getName()),
                "StillSpriteInstance must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic3kSpikeObjectInstance.class.getName()),
                "Sonic3kSpikeObjectInstance must not keep an explicit S3K dynamic codec");
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

    private static void seedSpikeState(Sonic3kSpikeObjectInstance spike) {
        setIntField(spike, "baseX", 0x0310);
        setIntField(spike, "baseY", 0x0180);
        setIntField(spike, "currentX", 0x0319);
        setIntField(spike, "currentY", 0x0188);
        setIntField(spike, "retractOffset", 0x1800);
        setIntField(spike, "retractState", 1);
        setIntField(spike, "retractTimer", 17);
        setBooleanField(spike, "contactPushingActive", true);
        setIntField(spike, "pushRateTimer", 7);
        setIntField(spike, "pushDistanceRemaining", 11);
        setBooleanField(spike, "mainRoutineReached", true);
        setBooleanField(spike, "suppressSolidThisFrame", false);
    }

    private static void assertSeededSpikeState(Sonic3kSpikeObjectInstance spike) {
        assertEquals(0x0310, readIntField(spike, "baseX"),
                "spike base X must restore exactly");
        assertEquals(0x0180, readIntField(spike, "baseY"),
                "spike base Y must restore exactly");
        assertEquals(0x0319, readIntField(spike, "currentX"),
                "spike current X must restore exactly");
        assertEquals(0x0188, readIntField(spike, "currentY"),
                "spike current Y must restore exactly");
        assertEquals(0x1800, readIntField(spike, "retractOffset"),
                "spike retract offset must restore exactly");
        assertEquals(1, readIntField(spike, "retractState"),
                "spike retract state must restore exactly");
        assertEquals(17, readIntField(spike, "retractTimer"),
                "spike retract timer must restore exactly");
        assertTrue(readBooleanField(spike, "contactPushingActive"),
                "spike push contact flag must restore exactly");
        assertEquals(7, readIntField(spike, "pushRateTimer"),
                "spike push rate timer must restore exactly");
        assertEquals(11, readIntField(spike, "pushDistanceRemaining"),
                "spike remaining push distance must restore exactly");
        assertTrue(readBooleanField(spike, "mainRoutineReached"),
                "spike main-routine latch must restore exactly");
        assertFalse(readBooleanField(spike, "suppressSolidThisFrame"),
                "spike solid suppression latch must restore exactly");
        assertEquals(0x0319, spike.getX(), "spike live X accessor must use restored current X");
        assertEquals(0x0188, spike.getY(), "spike live Y accessor must use restored current Y");
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
                .sorted(Comparator.comparingInt(TestS3kStaticHazardRewind::slotIndex))
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

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
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
