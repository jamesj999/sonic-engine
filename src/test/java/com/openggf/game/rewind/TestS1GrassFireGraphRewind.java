package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1GrassFireObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LargeGrassyPlatformObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1GrassFireGraphRewind {
    private static final ObjectSpawn CAPTURED_PLATFORM_SPAWN =
            new ObjectSpawn(0x0180, 0x0200, Sonic1ObjectIds.MZ_LARGE_GRASSY_PLATFORM, 0x05, 0, false, 80);
    private static final ObjectSpawn WRONG_PLATFORM_SPAWN =
            new ObjectSpawn(0x0300, 0x0200, Sonic1ObjectIds.MZ_LARGE_GRASSY_PLATFORM, 0x05, 0, false, 81);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void grassFireGraphRestoresCapturedFiresWithIdentityRefsScalarsAndCleanup() {
        Harness harness = Harness.create(List.of(CAPTURED_PLATFORM_SPAWN, WRONG_PLATFORM_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        Sonic1LargeGrassyPlatformObjectInstance sourcePlatform =
                platformAt(objectManager, CAPTURED_PLATFORM_SPAWN);
        Sonic1LargeGrassyPlatformObjectInstance sourceWrongPlatform =
                platformAt(objectManager, WRONG_PLATFORM_SPAWN);
        FireGraph sourceGraph = createCapturedFireGraph(objectManager, sourcePlatform);
        List<Sonic1GrassFireObjectInstance> staleSourceFires = sourceGraph.allFires();

        ObjectRefId platformId = objectId(objectManager, sourcePlatform);
        ObjectRefId wrongPlatformId = objectId(objectManager, sourceWrongPlatform);
        ObjectRefId walkerId = objectId(objectManager, sourceGraph.walker());
        List<ObjectRefId> stationaryIds = sourceGraph.stationaryChildren().stream()
                .map(child -> objectId(objectManager, child))
                .toList();
        Map<ObjectRefId, Map<String, Object>> capturedScalars = scalarSnapshotById(objectManager, staleSourceFires);

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        for (Sonic1GrassFireObjectInstance fire : staleSourceFires) {
            objectManager.removeDynamicObject(fire);
        }
        FireGraph divergentGraph = createDivergentFireGraph(objectManager, sourceWrongPlatform);
        assertEquals(3, liveObjects(objectManager, Sonic1GrassFireObjectInstance.class).size(),
                "diverge step should leave only unrelated replacement fires");

        registry.restore(snapshot);

        List<Sonic1LargeGrassyPlatformObjectInstance> restoredPlatforms =
                liveObjects(objectManager, Sonic1LargeGrassyPlatformObjectInstance.class);
        List<Sonic1GrassFireObjectInstance> restoredFires =
                liveObjects(objectManager, Sonic1GrassFireObjectInstance.class);
        assertEquals(2, restoredPlatforms.size(), "restore must keep exactly the two captured platforms");
        assertEquals(3, restoredFires.size(), "restore must keep exactly the captured grass fires");

        Sonic1LargeGrassyPlatformObjectInstance restoredPlatform =
                objectById(objectManager, Sonic1LargeGrassyPlatformObjectInstance.class, platformId);
        Sonic1LargeGrassyPlatformObjectInstance restoredWrongPlatform =
                objectById(objectManager, Sonic1LargeGrassyPlatformObjectInstance.class, wrongPlatformId);
        Sonic1GrassFireObjectInstance restoredWalker =
                objectById(objectManager, Sonic1GrassFireObjectInstance.class, walkerId);
        List<Sonic1GrassFireObjectInstance> restoredStationary = stationaryIds.stream()
                .map(id -> objectById(objectManager, Sonic1GrassFireObjectInstance.class, id))
                .toList();

        assertNotSame(sourcePlatform, restoredPlatform, "restore must recreate the captured platform");
        assertNotSame(sourceWrongPlatform, restoredWrongPlatform, "restore must recreate the distractor platform");
        assertNotSame(sourceGraph.walker(), restoredWalker, "restore must recreate removed walker fire");
        assertNotSame(divergentGraph.walker(), restoredWalker, "restore must drop divergent walker fire");
        for (int i = 0; i < restoredStationary.size(); i++) {
            assertNotSame(sourceGraph.stationaryChildren().get(i), restoredStationary.get(i),
                    "restore must recreate removed stationary fire " + i);
        }

        for (Sonic1GrassFireObjectInstance fire : restoredFires) {
            assertSame(restoredPlatform, readObjectField(fire, "parentPlatform"),
                    "each restored fire must relink to the restored captured platform");
            assertNotSame(sourcePlatform, readObjectField(fire, "parentPlatform"),
                    "restored fire must not retain stale source platform refs");
            assertNotSame(restoredWrongPlatform, readObjectField(fire, "parentPlatform"),
                    "restored fire must not relink to the wrong live platform");
        }
        assertSame(restoredWalker, readObjectField(restoredPlatform, "walkerFire"),
                "platform walkerFire must point at the restored walker");
        assertEquals(join(restoredWalker, restoredStationary), readFireList(restoredPlatform, "fireChildren"),
                "platform fireChildren must contain only the restored captured fires");
        assertEquals(restoredStationary, readFireList(restoredWalker, "children"),
                "walker children must contain only the restored stationary fires");
        assertEquals(capturedScalars, scalarSnapshotById(objectManager, restoredFires),
                "compact restore must round-trip grass-fire scalar state");

        int childrenBeforeSpawn = readFireList(restoredWalker, "children").size();
        int platformChildrenBeforeSpawn = readFireList(restoredPlatform, "fireChildren").size();
        seedWalkerForNextChildSpawn(restoredWalker);
        restoredWalker.update(0, new TestablePlayableSprite("sonic", (short) 0, (short) 0));
        List<Sonic1GrassFireObjectInstance> walkerChildrenAfterSpawn =
                readFireList(restoredWalker, "children");
        List<Sonic1GrassFireObjectInstance> platformChildrenAfterSpawn =
                readFireList(restoredPlatform, "fireChildren");
        assertEquals(childrenBeforeSpawn + 1, walkerChildrenAfterSpawn.size(),
                "restored walker must append newly spawned stationary children");
        assertEquals(platformChildrenBeforeSpawn + 1, platformChildrenAfterSpawn.size(),
                "restored platform must track newly spawned stationary children");
        Sonic1GrassFireObjectInstance liveChild =
                walkerChildrenAfterSpawn.get(walkerChildrenAfterSpawn.size() - 1);
        assertSame(liveChild, platformChildrenAfterSpawn.get(platformChildrenAfterSpawn.size() - 1),
                "walker-side and platform-side lists must receive the same live child");
        assertSame(restoredPlatform, readObjectField(liveChild, "parentPlatform"),
                "newly spawned child must link to the restored platform");

        restoredPlatform.onUnload();
        for (Sonic1GrassFireObjectInstance fire : restoredFires) {
            assertTrue(fire.isDestroyed(), "cleanup must destroy restored captured fires");
        }
        assertTrue(liveChild.isDestroyed(), "cleanup must also destroy post-restore live children");
        for (Sonic1GrassFireObjectInstance stale : staleSourceFires) {
            assertFalse(stale.isDestroyed(), "cleanup must not destroy stale pre-restore fire refs");
        }
    }

    @Test
    void grassFireMissingParentRefsFailCaptureAndRestoreLoudly() {
        Harness captureHarness = Harness.create(List.of());
        Sonic1LargeGrassyPlatformObjectInstance unmanagedParent =
                new Sonic1LargeGrassyPlatformObjectInstance(CAPTURED_PLATFORM_SPAWN);
        Sonic1GrassFireObjectInstance unmanagedParentFire =
                captureHarness.objectManager().createDynamicObject(() -> new Sonic1GrassFireObjectInstance(
                        0x0120, 0x0205, 7, unmanagedParent.getSlopeData(), unmanagedParent, true));

        IllegalStateException captureFailure = assertThrows(
                IllegalStateException.class,
                () -> registryFor(captureHarness.objectManager()).capture());
        assertTrue(captureFailure.getMessage().contains("no registered id for object reference"),
                "non-null unmanaged grass-fire parent must fail required object-ref capture");

        Harness restoreHarness = Harness.create(List.of(CAPTURED_PLATFORM_SPAWN));
        Sonic1LargeGrassyPlatformObjectInstance managedParent =
                platformAt(restoreHarness.objectManager(), CAPTURED_PLATFORM_SPAWN);
        Sonic1GrassFireObjectInstance managedFire =
                restoreHarness.objectManager().createDynamicObject(() -> new Sonic1GrassFireObjectInstance(
                        0x0120, 0x0205, 7, managedParent.getSlopeData(), managedParent, true));
        PerObjectRewindSnapshot state =
                managedFire.captureRewindState(restoreHarness.objectManager().captureIdentityContext());

        Sonic1GrassFireObjectInstance target =
                new Sonic1GrassFireObjectInstance(0x0120, 0x0205, 0, null, null, true);
        target.setServices(restoreHarness.services());
        RewindIdentityTable missingParentTable = new RewindIdentityTable();
        missingParentTable.registerObject(target, objectId(restoreHarness.objectManager(), managedFire));

        IllegalStateException restoreFailure = assertThrows(
                IllegalStateException.class,
                () -> target.restoreRewindState(
                        state, RewindCaptureContext.withIdentityTable(missingParentTable)));
        assertTrue(restoreFailure.getMessage().contains("Missing required object reference"),
                "missing restore-table parent must fail required object-ref restore");
        assertNotNull(unmanagedParentFire, "precondition: unmanaged-parent fire was created");
    }

    @Test
    void grassFireUsesRewindRecreatableWithoutAnyS1DynamicCodec() {
        assertTrue(CompactFieldCapturer.supportsDefaultObjectSubclassScalars(Sonic1GrassFireObjectInstance.class),
                "Grass Fire compact capture must stay supported so required parent/list refs are captured");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1GrassFireObjectInstance.class),
                "Grass Fire must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(Sonic1GrassFireObjectInstance.class.getName()),
                "Grass Fire must not keep an explicit S1 dynamic rewind codec");
    }

    private static FireGraph createCapturedFireGraph(
            ObjectManager objectManager,
            Sonic1LargeGrassyPlatformObjectInstance platform) {
        Sonic1GrassFireObjectInstance walker = objectManager.createDynamicObject(
                () -> new Sonic1GrassFireObjectInstance(
                        platform.getX() - 0x40, platform.getY() + 5, 11,
                        platform.getSlopeData(), platform, true));
        Sonic1GrassFireObjectInstance stationaryA = objectManager.createDynamicObject(
                () -> new Sonic1GrassFireObjectInstance(
                        platform.getX() - 0x20, platform.getY() + 1, 11,
                        platform.getSlopeData(), platform, false));
        Sonic1GrassFireObjectInstance stationaryB = objectManager.createDynamicObject(
                () -> new Sonic1GrassFireObjectInstance(
                        platform.getX(), platform.getY() - 3, 11,
                        platform.getSlopeData(), platform, false));
        seedGrassFireScalars(walker, 0x0147, 0x0207, 13, 0x0214, 0x0140, 4, 2, true);
        seedGrassFireScalars(stationaryA, 0x0160, 0x0201, 14, 0x020F, 0x0160, 5, 3, false);
        seedGrassFireScalars(stationaryB, 0x0178, 0x01FD, 15, 0x020C, 0x0178, 1, 1, true);
        linkFireGraph(platform, walker, List.of(stationaryA, stationaryB));
        return new FireGraph(walker, List.of(stationaryA, stationaryB));
    }

    private static FireGraph createDivergentFireGraph(
            ObjectManager objectManager,
            Sonic1LargeGrassyPlatformObjectInstance wrongPlatform) {
        Sonic1GrassFireObjectInstance walker = objectManager.createDynamicObject(
                () -> new Sonic1GrassFireObjectInstance(
                        wrongPlatform.getX() - 0x40, wrongPlatform.getY() + 5, 0,
                        wrongPlatform.getSlopeData(), wrongPlatform, true));
        Sonic1GrassFireObjectInstance stationaryA = objectManager.createDynamicObject(
                () -> new Sonic1GrassFireObjectInstance(
                        wrongPlatform.getX() - 0x20, wrongPlatform.getY() + 5, 0,
                        wrongPlatform.getSlopeData(), wrongPlatform, false));
        Sonic1GrassFireObjectInstance stationaryB = objectManager.createDynamicObject(
                () -> new Sonic1GrassFireObjectInstance(
                        wrongPlatform.getX(), wrongPlatform.getY() + 5, 0,
                        wrongPlatform.getSlopeData(), wrongPlatform, false));
        linkFireGraph(wrongPlatform, walker, List.of(stationaryA, stationaryB));
        return new FireGraph(walker, List.of(stationaryA, stationaryB));
    }

    private static void linkFireGraph(
            Sonic1LargeGrassyPlatformObjectInstance platform,
            Sonic1GrassFireObjectInstance walker,
            List<Sonic1GrassFireObjectInstance> stationaryChildren) {
        writeObjectField(platform, "walkerFire", walker);
        writeBooleanField(platform, "fireSpawned", true);
        List<Sonic1GrassFireObjectInstance> platformChildren = readFireList(platform, "fireChildren");
        platformChildren.clear();
        platformChildren.add(walker);
        platformChildren.addAll(stationaryChildren);
        List<Sonic1GrassFireObjectInstance> walkerChildren = readFireList(walker, "children");
        walkerChildren.clear();
        walkerChildren.addAll(stationaryChildren);
    }

    private static void seedGrassFireScalars(
            Sonic1GrassFireObjectInstance fire,
            int currentX,
            int baseY,
            int sinkOffset,
            int currentY,
            int originX,
            int animTimer,
            int animIndex,
            boolean soundPlayed) {
        writeIntField(fire, "currentX", currentX);
        writeIntField(fire, "baseY", baseY);
        writeIntField(fire, "sinkOffset", sinkOffset);
        writeIntField(fire, "currentY", currentY);
        writeIntField(fire, "originX", originX);
        writeIntField(fire, "animTimer", animTimer);
        writeIntField(fire, "animIndex", animIndex);
        writeBooleanField(fire, "soundPlayed", soundPlayed);
    }

    private static void seedWalkerForNextChildSpawn(Sonic1GrassFireObjectInstance walker) {
        writeIntField(walker, "originX", 0x0100);
        writeIntField(walker, "currentX", 0x0107);
        writeIntField(walker, "baseY", 0x0200);
        writeIntField(walker, "currentY", 0x0200);
        writeIntField(walker, "sinkOffset", 0);
    }

    private static Map<ObjectRefId, Map<String, Object>> scalarSnapshotById(
            ObjectManager objectManager,
            List<Sonic1GrassFireObjectInstance> fires) {
        Map<ObjectRefId, Map<String, Object>> values = new LinkedHashMap<>();
        for (Sonic1GrassFireObjectInstance fire : fires.stream()
                .sorted(Comparator.comparing(f -> objectId(objectManager, f).toString()))
                .toList()) {
            values.put(objectId(objectManager, fire), scalarSnapshot(fire));
        }
        return values;
    }

    private static Map<String, Object> scalarSnapshot(Sonic1GrassFireObjectInstance fire) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("currentX", readObjectField(fire, "currentX"));
        values.put("baseY", readObjectField(fire, "baseY"));
        values.put("sinkOffset", readObjectField(fire, "sinkOffset"));
        values.put("currentY", readObjectField(fire, "currentY"));
        values.put("originX", readObjectField(fire, "originX"));
        values.put("animTimer", readObjectField(fire, "animTimer"));
        values.put("animIndex", readObjectField(fire, "animIndex"));
        values.put("soundPlayed", readObjectField(fire, "soundPlayed"));
        values.put("isWalker", readObjectField(fire, "isWalker"));
        return values;
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
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

    private static Sonic1LargeGrassyPlatformObjectInstance platformAt(
            ObjectManager objectManager,
            ObjectSpawn spawn) {
        return liveObjects(objectManager, Sonic1LargeGrassyPlatformObjectInstance.class).stream()
                .filter(platform -> platform.getSpawn().equals(spawn))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing platform for " + spawn));
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS1GrassFireGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof com.openggf.level.objects.AbstractObjectInstance aoi
                ? aoi.getSlotIndex()
                : -1;
    }

    private static List<Sonic1GrassFireObjectInstance> join(
            Sonic1GrassFireObjectInstance walker,
            List<Sonic1GrassFireObjectInstance> stationary) {
        List<Sonic1GrassFireObjectInstance> joined = new ArrayList<>();
        joined.add(walker);
        joined.addAll(stationary);
        return joined;
    }

    @SuppressWarnings("unchecked")
    private static List<Sonic1GrassFireObjectInstance> readFireList(Object target, String fieldName) {
        return (List<Sonic1GrassFireObjectInstance>) readObjectField(target, fieldName);
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void writeObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void writeIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void writeBooleanField(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Walk superclass chain.
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private record FireGraph(
            Sonic1GrassFireObjectInstance walker,
            List<Sonic1GrassFireObjectInstance> stationaryChildren) {
        List<Sonic1GrassFireObjectInstance> allFires() {
            return join(walker, stationaryChildren);
        }
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns, new Sonic1ObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager, services);
        }
    }

    private static Camera mockCameraAtOrigin() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x700; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
