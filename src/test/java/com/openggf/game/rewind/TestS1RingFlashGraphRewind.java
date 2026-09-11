package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1GiantRingObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.game.sonic1.objects.Sonic1RingFlashObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1RingFlashGraphRewind {

    private static final ObjectSpawn GIANT_RING_SPAWN =
            new ObjectSpawn(0x0180, 0x0120, Sonic1ObjectIds.GIANT_RING, 0, 0, false, 10);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void ringFlashGraphRestoreRecreatesFreshAndRelinksLiveGiantRing() {
        Harness harness = Harness.create(List.of(GIANT_RING_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Sonic1GiantRingObjectInstance parent = only(objectManager, Sonic1GiantRingObjectInstance.class);
        Sonic1RingFlashObjectInstance before = objectManager.createDynamicObject(
                () -> new Sonic1RingFlashObjectInstance(parent, GIANT_RING_SPAWN.x(), GIANT_RING_SPAWN.y(), false));
        seedRingFlashScalars(before);

        ObjectRefId beforeId = objectId(objectManager, before);
        Map<String, Object> beforeScalars = scalarSnapshot(before);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(before);
        Sonic1RingFlashObjectInstance replacement = objectManager.createDynamicObject(
                () -> new Sonic1RingFlashObjectInstance(parent, 0x0300, 0x0200, false));
        assertNotEquals(beforeId, objectId(objectManager, replacement),
                "post-snapshot replacement flash must not alias the captured flash identity");

        registry.restore(snapshot);

        Sonic1RingFlashObjectInstance restored = only(objectManager, Sonic1RingFlashObjectInstance.class);
        Sonic1GiantRingObjectInstance restoredParent = only(objectManager, Sonic1GiantRingObjectInstance.class);
        assertEquals(beforeId, objectId(objectManager, restored),
                "captured ring flash rewind identity must be preserved");
        assertNotSame(before, restored, "restore must recreate the removed ring flash");
        assertNotSame(replacement, restored, "restore must drop unrelated post-snapshot ring flashes");
        assertSame(restoredParent, readObjectField(restored, "parent"),
                "restored ring flash must point at the restored live giant ring");
        assertNotSame(parent, readObjectField(restored, "parent"),
                "restored ring flash must not retain the stale pre-restore giant ring");
        assertEquals(beforeScalars, scalarSnapshot(restored),
                "compact restore must round-trip meaningful ring flash scalar state");
    }

    @Test
    void genericRecreatePreservesMissingParentRingFlashBehavior() {
        Harness harness = Harness.create(List.of());
        ObjectManager objectManager = harness.objectManager();
        ObjectSpawn flashSpawn = new ObjectSpawn(0x0200, 0x0140, 0x7C, 0, 0, false, 0);

        ObjectInstance recreated = ObjectRewindDynamicCodecs.genericRecreate(
                new ObjectManagerSnapshot.DynamicObjectEntry(
                        Sonic1RingFlashObjectInstance.class.getName(), flashSpawn, 0, emptyState()),
                new DynamicObjectRecreateContext(objectManager));

        Sonic1RingFlashObjectInstance flash =
                assertInstanceOf(Sonic1RingFlashObjectInstance.class, recreated);
        assertNull(readObjectField(flash, "parent"),
                "generic recreate must preserve current RingFlash behavior when no live giant ring exists");
    }

    @Test
    void ringFlashUsesRewindRecreatableWithoutExplicitS1DynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1RingFlashObjectInstance.class),
                "S1 ring flash must restore through RewindRecreatable generic recreate");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1RingFlashObjectInstance.class.getName()),
                "S1 ring flash must not keep an explicit S1 dynamic rewind codec");
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static PerObjectRewindSnapshot emptyState() {
        return new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0,
                false, false, 0, -1, null, null, null);
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static void seedRingFlashScalars(Sonic1RingFlashObjectInstance flash) {
        setBooleanField(flash, "hFlip", true);
        setIntField(flash, "frameTimer", 5);
        setIntField(flash, "animFrame", 4);
        setBooleanField(flash, "triggerFired", true);
        setBooleanField(flash, "finished", true);
    }

    private static Map<String, Object> scalarSnapshot(Sonic1RingFlashObjectInstance flash) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("hFlip", readObjectField(flash, "hFlip"));
        values.put("frameTimer", readObjectField(flash, "frameTimer"));
        values.put("animFrame", readObjectField(flash, "animFrame"));
        values.put("triggerFired", readObjectField(flash, "triggerFired"));
        values.put("finished", readObjectField(flash, "finished"));
        return values;
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
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

    private record Harness(ObjectManager objectManager) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new Sonic1ObjectRegistry(),
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

    private static Camera mockCameraAtOrigin() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
