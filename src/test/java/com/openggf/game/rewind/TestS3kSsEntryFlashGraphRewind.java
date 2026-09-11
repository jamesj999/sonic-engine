package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.objects.Sonic3kSSEntryFlashObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kSSEntryRingObjectInstance;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSsEntryFlashGraphRewind {
    private static final ObjectSpawn DISTRACTOR_RING_SPAWN =
            new ObjectSpawn(0x0180, 0x0120, Sonic3kObjectIds.SS_ENTRY_RING, 1, 0, false, 40);
    private static final ObjectSpawn CAPTURED_RING_SPAWN =
            new ObjectSpawn(0x0320, 0x0180, Sonic3kObjectIds.SS_ENTRY_RING, 2, 0, false, 41);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x800, 0x700, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void flashRestoresFreshWithExactCapturedLayoutRingAndScalarState() {
        Harness harness = Harness.create(List.of(DISTRACTOR_RING_SPAWN, CAPTURED_RING_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        List<Sonic3kSSEntryRingObjectInstance> rings =
                liveObjects(objectManager, Sonic3kSSEntryRingObjectInstance.class);
        assertEquals(2, rings.size(), "precondition: two placement SS-entry rings must be live");
        Sonic3kSSEntryRingObjectInstance distractorRing = rings.get(0);
        Sonic3kSSEntryRingObjectInstance capturedRing = rings.get(1);

        Sonic3kSSEntryFlashObjectInstance sourceFlash = objectManager.createDynamicObject(
                () -> new Sonic3kSSEntryFlashObjectInstance(
                        capturedRing, CAPTURED_RING_SPAWN.x(), CAPTURED_RING_SPAWN.y()));
        seedFlashScalars(sourceFlash);

        ObjectRefId distractorRingId = objectId(objectManager, distractorRing);
        ObjectRefId capturedRingId = objectId(objectManager, capturedRing);
        ObjectRefId flashId = objectId(objectManager, sourceFlash);
        Map<String, Object> sourceScalars = scalarSnapshot(sourceFlash);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceFlash);
        Sonic3kSSEntryFlashObjectInstance divergentFlash = objectManager.createDynamicObject(
                () -> new Sonic3kSSEntryFlashObjectInstance(
                        distractorRing, DISTRACTOR_RING_SPAWN.x(), DISTRACTOR_RING_SPAWN.y()));
        setIntField(divergentFlash, "animIndex", 1);

        rewindRegistry.restore(snapshot);

        assertEquals(2, liveObjects(objectManager, Sonic3kSSEntryRingObjectInstance.class).size(),
                "restore must keep exactly the captured two placement rings");
        assertEquals(1, liveObjects(objectManager, Sonic3kSSEntryFlashObjectInstance.class).size(),
                "restore must recreate exactly one captured SS-entry flash");

        Sonic3kSSEntryRingObjectInstance restoredDistractor =
                objectById(objectManager, Sonic3kSSEntryRingObjectInstance.class, distractorRingId);
        Sonic3kSSEntryRingObjectInstance restoredRing =
                objectById(objectManager, Sonic3kSSEntryRingObjectInstance.class, capturedRingId);
        Sonic3kSSEntryFlashObjectInstance restoredFlash =
                objectById(objectManager, Sonic3kSSEntryFlashObjectInstance.class, flashId);

        assertNotSame(distractorRing, restoredDistractor, "restore must recreate the distractor ring");
        assertNotSame(capturedRing, restoredRing, "restore must recreate the captured ring");
        assertNotSame(sourceFlash, restoredFlash, "restore must recreate the removed flash");
        assertNotSame(divergentFlash, restoredFlash, "restore must drop the divergent flash");

        assertSame(restoredRing, readObjectField(restoredFlash, "parentRing"),
                "flash parentRing must resolve to the restored captured layout ring by ObjectRefId");
        assertNotSame(restoredDistractor, readObjectField(restoredFlash, "parentRing"),
                "flash parentRing must not relink to a distractor ring");
        assertNotSame(capturedRing, readObjectField(restoredFlash, "parentRing"),
                "flash parentRing must not retain the stale pre-restore ring");
        assertEquals(sourceScalars, scalarSnapshot(restoredFlash),
                "compact restore must round-trip SS-entry flash scalar state");
    }

    @Test
    void flashUsesRewindRecreatableWithoutExplicitS3kDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic3kSSEntryFlashObjectInstance.class),
                "S3K SS-entry flash must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic3kSSEntryFlashObjectInstance.class.getName()),
                "S3K SS-entry flash must not keep an explicit S3K dynamic codec");
    }

    @Test
    void captureIgnoresFlashWhoseParentRingHasNoRewindIdentity() {
        Harness harness = Harness.create(List.of());
        Sonic3kSSEntryRingObjectInstance unmanagedRing =
                new Sonic3kSSEntryRingObjectInstance(CAPTURED_RING_SPAWN);
        unmanagedRing.setServices(harness.services());
        Sonic3kSSEntryFlashObjectInstance flash = harness.objectManager().createDynamicObject(
                () -> new Sonic3kSSEntryFlashObjectInstance(
                        unmanagedRing, CAPTURED_RING_SPAWN.x(), CAPTURED_RING_SPAWN.y()));
        assertSame(unmanagedRing, readObjectField(flash, "parentRing"),
                "precondition: flash parent ring is outside ObjectManager identity registration");

        assertDoesNotThrow(() -> registryFor(harness.objectManager()).capture(),
                "capture must tolerate stale SS-entry flash parent-ring references");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            int cameraX = initialCameraX(spawns);
            Camera camera = mockCamera(cameraX);
            GameStateManager gameState = new GameStateManager();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public GameStateManager gameState() { return gameState; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns, new Sonic3kObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(cameraX);
            return new Harness(objectManager, services);
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

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS3kSsEntryFlashGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    private static void seedFlashScalars(Sonic3kSSEntryFlashObjectInstance flash) {
        setObjectField(flash, "state", enumConstant("State", "WAITING", Sonic3kSSEntryFlashObjectInstance.class));
        setIntField(flash, "animIndex", 6);
        setIntField(flash, "waitTimer", 17);
        setBooleanField(flash, "ringDeleteTriggered", true);
    }

    private static Map<String, Object> scalarSnapshot(Sonic3kSSEntryFlashObjectInstance flash) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("state", readObjectField(flash, "state"));
        values.put("animIndex", readObjectField(flash, "animIndex"));
        values.put("waitTimer", readObjectField(flash, "waitTimer"));
        values.put("ringDeleteTriggered", readObjectField(flash, "ringDeleteTriggered"));
        return values;
    }

    private static Object enumConstant(String simpleName, String constantName, Class<?> enclosingType) {
        for (Class<?> nested : enclosingType.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName) && nested.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object value = Enum.valueOf((Class<? extends Enum>) nested, constantName);
                return value;
            }
        }
        throw new AssertionError("Missing enum " + simpleName + " on " + enclosingType);
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

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
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

    private static int initialCameraX(List<ObjectSpawn> spawns) {
        return spawns.stream().mapToInt(ObjectSpawn::x).min().orElse(0) & 0xFF80;
    }

    private static Camera mockCamera(int cameraX) {
        return new Camera() {
            @Override public short getX() { return (short) cameraX; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x700; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
