package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kMhzMinibossFlameGraphRewind {
    private static final ObjectSpawn DISTRACTOR_PARENT_SPAWN =
            new ObjectSpawn(0x0180, 0x0500, Sonic3kObjectIds.AIZ_END_BOSS, 0, 0, false, 70);
    private static final ObjectSpawn CAPTURED_PARENT_SPAWN =
            new ObjectSpawn(0x0340, 0x0500, Sonic3kObjectIds.AIZ_END_BOSS, 0, 0, false, 71);

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
    void flamesRestoreFreshWithExactCapturedParentAndScalarState() {
        Harness harness = Harness.create(List.of(DISTRACTOR_PARENT_SPAWN, CAPTURED_PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        List<MhzMinibossInstance> parents = liveObjects(objectManager, MhzMinibossInstance.class);
        assertEquals(2, parents.size(), "precondition: two MHZ miniboss parents must be live");
        MhzMinibossInstance parentDistractor = parents.get(0);
        MhzMinibossInstance parentCaptured = parents.get(1);

        MhzMinibossFlameInstance sourceFlameA = objectManager.createDynamicObject(
                () -> new MhzMinibossFlameInstance(parentCaptured, 0));
        MhzMinibossFlameInstance sourceFlameB = objectManager.createDynamicObject(
                () -> new MhzMinibossFlameInstance(parentCaptured, 1));
        setFlameState(sourceFlameA, 0, 0x0351, 0x0522, 0x16, 5);
        setFlameState(sourceFlameB, 1, 0x0338, 0x0512, 0x17, 3);

        ObjectRefId distractorParentId = objectId(objectManager, parentDistractor);
        ObjectRefId capturedParentId = objectId(objectManager, parentCaptured);
        ObjectRefId flameAId = objectId(objectManager, sourceFlameA);
        ObjectRefId flameBId = objectId(objectManager, sourceFlameB);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceFlameA);
        objectManager.removeDynamicObject(sourceFlameB);
        MhzMinibossInstance divergentParent = objectManager.createDynamicObject(
                () -> new MhzMinibossInstance(new ObjectSpawn(
                        0x0520, 0x0580, Sonic3kObjectIds.AIZ_END_BOSS, 0, 0, false, 72)));
        MhzMinibossFlameInstance divergentFlame = objectManager.createDynamicObject(
                () -> new MhzMinibossFlameInstance(divergentParent, 0));
        setFlameState(divergentFlame, 0, 0x0555, 0x0599, 0x16, 5);

        rewindRegistry.restore(snapshot);

        assertEquals(2, liveObjects(objectManager, MhzMinibossInstance.class).size(),
                "restore must keep exactly the captured MHZ miniboss parents");
        assertEquals(2, liveObjects(objectManager, MhzMinibossFlameInstance.class).size(),
                "restore must recreate exactly the captured two flame children");

        MhzMinibossInstance restoredDistractor =
                objectById(objectManager, MhzMinibossInstance.class, distractorParentId);
        MhzMinibossInstance restoredParent =
                objectById(objectManager, MhzMinibossInstance.class, capturedParentId);
        MhzMinibossFlameInstance restoredFlameA =
                objectById(objectManager, MhzMinibossFlameInstance.class, flameAId);
        MhzMinibossFlameInstance restoredFlameB =
                objectById(objectManager, MhzMinibossFlameInstance.class, flameBId);

        assertNotSame(parentDistractor, restoredDistractor, "restore must recreate the distractor parent");
        assertNotSame(parentCaptured, restoredParent, "restore must recreate the captured parent");
        assertNotSame(sourceFlameA, restoredFlameA, "restore must recreate flame A");
        assertNotSame(sourceFlameB, restoredFlameB, "restore must recreate flame B");
        assertNotSame(divergentFlame, restoredFlameA, "restore must drop the divergent flame");
        assertNotSame(divergentFlame, restoredFlameB, "restore must drop the divergent flame");

        assertSame(restoredParent, readObjectField(restoredFlameA, "parent"),
                "flame A parent must resolve to the restored captured parent by ObjectRefId");
        assertSame(restoredParent, readObjectField(restoredFlameB, "parent"),
                "flame B parent must resolve to the restored captured parent by ObjectRefId");
        assertNotSame(parentCaptured, readObjectField(restoredFlameA, "parent"),
                "flame A must not retain the stale source parent");
        assertNotSame(parentCaptured, readObjectField(restoredFlameB, "parent"),
                "flame B must not retain the stale source parent");
        assertNotSame(divergentParent, readObjectField(restoredFlameA, "parent"),
                "flame A must not relink to a divergent parent");
        assertNotSame(divergentParent, readObjectField(restoredFlameB, "parent"),
                "flame B must not relink to a divergent parent");

        assertFlameState(restoredFlameA, 0, 0x0351, 0x0522, 0x16, 5);
        assertFlameState(restoredFlameB, 1, 0x0338, 0x0512, 0x17, 3);
    }

    @Test
    void flameUsesRewindRecreatableWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(MhzMinibossFlameInstance.class),
                "MHZ miniboss flame must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        MhzMinibossFlameInstance.class.getName()),
                "MHZ miniboss flame must not keep an explicit S3K dynamic codec");
    }

    @Test
    void captureFailsForFlameWhoseRequiredParentHasNoRewindIdentity() {
        Harness harness = Harness.create(List.of());
        MhzMinibossInstance unmanagedParent =
                new MhzMinibossInstance(new ObjectSpawn(
                        0x0340, 0x0500, Sonic3kObjectIds.AIZ_END_BOSS, 0, 0, false, 73));
        unmanagedParent.setServices(harness.services());
        MhzMinibossFlameInstance flame = harness.objectManager().createDynamicObject(
                () -> new MhzMinibossFlameInstance(unmanagedParent, 0));
        assertSame(unmanagedParent, readObjectField(flame, "parent"),
                "precondition: flame parent is outside ObjectManager identity registration");

        RewindRegistry rewindRegistry = registryFor(harness.objectManager());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, rewindRegistry::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "missing required parent target must fail loudly");
    }

    private record Harness(ObjectManager objectManager, StubObjectServices services) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            int cameraX = initialCameraX(spawns);
            Camera camera = mockCamera(cameraX);
            StubObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public int romZoneId() { return Sonic3kZoneIds.ZONE_MHZ; }
                @Override public int featureZoneId() { return Sonic3kZoneIds.ZONE_MHZ; }
            };
            services.zoneRuntimeRegistry().install(new MhzZoneRuntimeState(0, PlayerCharacter.SONIC_AND_TAILS));
            ObjectManager objectManager = new ObjectManager(
                    spawns, new MhzTestRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(cameraX);
            return new Harness(objectManager, services);
        }
    }

    private static final class MhzTestRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_MHZ;
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
                .sorted(Comparator.comparingInt(TestS3kMhzMinibossFlameGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    private static void setFlameState(
            MhzMinibossFlameInstance flame, int childIndex, int x, int y, int mappingFrame, int priorityBucket) {
        setIntField(flame, "childIndex", childIndex);
        setIntField(flame, "x", x);
        setIntField(flame, "y", y);
        setIntField(flame, "mappingFrame", mappingFrame);
        setIntField(flame, "priorityBucket", priorityBucket);
    }

    private static void assertFlameState(
            MhzMinibossFlameInstance flame, int childIndex, int x, int y, int mappingFrame, int priorityBucket) {
        assertEquals(childIndex, readIntField(flame, "childIndex"), "childIndex must restore exactly");
        assertEquals(x, readIntField(flame, "x"), "x must restore exactly");
        assertEquals(y, readIntField(flame, "y"), "y must restore exactly");
        assertEquals(mappingFrame, readIntField(flame, "mappingFrame"), "mappingFrame must restore exactly");
        assertEquals(priorityBucket, readIntField(flame, "priorityBucket"),
                "priorityBucket must restore exactly");
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
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

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
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
