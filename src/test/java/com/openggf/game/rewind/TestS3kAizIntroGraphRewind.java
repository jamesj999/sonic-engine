package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic3k.objects.AizIntroBoosterChild;
import com.openggf.game.sonic3k.objects.AizIntroEmeraldGlowChild;
import com.openggf.game.sonic3k.objects.AizIntroPlaneChild;
import com.openggf.game.sonic3k.objects.AizIntroWaveChild;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectRegistry;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kAizIntroGraphRewind {
    private static final int AIZ_INTRO_PARENT_TEST_ID = 0xF0;
    private static final ObjectSpawn PARENT_SPAWN =
            new ObjectSpawn(0x0060, 0x0030, AIZ_INTRO_PARENT_TEST_ID, 0, 0, false, 300);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AizPlaneIntroInstance.resetIntroPhaseState();
    }

    @AfterEach
    void tearDown() {
        AizPlaneIntroInstance.resetIntroPhaseState();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void aizIntroPlaneAndWaveChildrenRestoreFreshWithRestoredParentRefsAndScalars() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        AizIntroGraph before = AizIntroGraph.spawnInto(objectManager);
        Map<Class<?>, Integer> beforeCounts = familyCounts(objectManager);
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeDynamicChildren(objectManager);
        AizPlaneIntroInstance staleReplacementParent =
                new AizPlaneIntroInstance(new ObjectSpawn(0x0900, 0x0100, AIZ_INTRO_PARENT_TEST_ID, 0, 0, false, 301));
        AizIntroPlaneChild replacementPlane = objectManager.createDynamicObject(
                () -> new AizIntroPlaneChild(spawn(0x0900, 0x0140, 401), staleReplacementParent));
        AizIntroWaveChild replacementWave = objectManager.createDynamicObject(
                () -> new AizIntroWaveChild(spawn(0x0910, 0x0150, 402), staleReplacementParent));
        assertSame(staleReplacementParent, AizPlaneIntroInstance.getActiveIntroInstance(),
                "sanity: static active pointer must be stale before restore");

        rewindRegistry.restore(snapshot);

        AizIntroGraph restored = AizIntroGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, familyCounts(objectManager),
                "restore must not drop or duplicate AIZ intro parent/child objects");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured AIZ intro object identities");

        restored.assertChildrenAreFreshFrom(before);
        restored.assertChildrenAreFreshFrom(replacementPlane, replacementWave);
        restored.assertChildrenRelinkToRestoredParent();
        restored.assertScalarsEqual(before);
        assertSame(restored.parent(), AizPlaneIntroInstance.getActiveIntroInstance(),
                "static active intro pointer must point at the restored live parent after restore");
        assertNoHelperObjectsBecameManagerDynamics(objectManager);
    }

    @Test
    void aizIntroParentUsesGenericRecreateWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(AizPlaneIntroInstance.class),
                "AizPlaneIntroInstance must restore through RewindRecreatable graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        AizPlaneIntroInstance.class.getName()),
                "AizPlaneIntroInstance must not keep an explicit S3K dynamic rewind codec");
    }

    @Test
    void aizIntroEmeraldGlowGenericRecreateRelinksToLivePlaneIfCapturedDynamically() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        AizPlaneIntroInstance parent = only(objectManager, AizPlaneIntroInstance.class);
        AizIntroPlaneChild plane = objectManager.createDynamicObject(
                () -> new AizIntroPlaneChild(spawn(0x0080, 0x005C, 411), parent));

        ObjectInstance result = ObjectRewindDynamicCodecs.genericRecreate(
                dynamicEntry(AizIntroEmeraldGlowChild.class, spawn(0x0088, 0x0050, 412)),
                new DynamicObjectRecreateContext(objectManager));

        assertTrue(result instanceof AizIntroEmeraldGlowChild,
                "generic recreate should rebuild an AIZ intro glow child when a live plane is present");
        assertSame(plane, readObjectField(result, "parent"),
                "glow child must relink to the restore-time live plane");
        assertEquals(0x38, readObjectField(result, "xOffset"),
                "variant 0 must restore the first ChildObjDat_67A62 X offset");
        assertEquals(0x04, readObjectField(result, "yOffset"),
                "variant 0 must restore the first ChildObjDat_67A62 Y offset");
    }

    @Test
    void aizIntroEmeraldGlowSubtypeRecreatesImmutableOffsetsFromCapturedSpawn() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        AizPlaneIntroInstance parent = only(objectManager, AizPlaneIntroInstance.class);
        AizIntroPlaneChild originalPlane = objectManager.createDynamicObject(
                () -> new AizIntroPlaneChild(spawn(0x0080, 0x005C, 421), parent));
        ObjectSpawn glowSpawn = spawn(0x0098, 0x0074, 1, 422);
        AizIntroEmeraldGlowChild originalGlow = objectManager.createDynamicObject(
                () -> new AizIntroEmeraldGlowChild(glowSpawn, originalPlane, glowSpawn.subtype() & 1));

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();
        objectManager.removeDynamicObject(originalGlow);
        objectManager.removeDynamicObject(originalPlane);

        rewindRegistry.restore(snapshot);

        AizIntroPlaneChild restoredPlane = only(objectManager, AizIntroPlaneChild.class);
        AizIntroEmeraldGlowChild restoredGlow = only(objectManager, AizIntroEmeraldGlowChild.class);
        assertNotSame(originalGlow, restoredGlow, "rewind must recreate the glow child");
        assertSame(restoredPlane, readObjectField(restoredGlow, "parent"),
                "recreated glow must relink to the restore-time plane");
        assertEquals(1, readObjectField(restoredGlow, "variant"),
                "captured subtype 1 must recreate animation variant 1");
        assertEquals(0x18, readObjectField(restoredGlow, "xOffset"),
                "captured subtype 1 must recreate the second ChildObjDat_67A62 X offset");
        assertEquals(0x18, readObjectField(restoredGlow, "yOffset"),
                "captured subtype 1 must recreate the second ChildObjDat_67A62 Y offset");
    }

    @Test
    void aizIntroEmeraldGlowRejectsVariantThatDisagreesWithCapturedSpawnSubtype() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        AizPlaneIntroInstance parent = only(objectManager, AizPlaneIntroInstance.class);
        AizIntroPlaneChild plane = objectManager.createDynamicObject(
                () -> new AizIntroPlaneChild(spawn(0x0080, 0x005C, 431), parent));
        ObjectSpawn subtypeOneSpawn = spawn(0x0098, 0x0074, 1, 432);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new AizIntroEmeraldGlowChild(subtypeOneSpawn, plane, 0));

        assertEquals("AIZ intro glow variant 0 disagrees with captured spawn subtype variant 1",
                error.getMessage());
    }

    @Test
    void genericRecreateDropsAizIntroChildrenWhenActiveParentIsMissingOrStale() {
        Harness harness = Harness.createWithoutPlacedParent();
        ObjectManager objectManager = harness.objectManager();
        AizPlaneIntroInstance staleParent =
                new AizPlaneIntroInstance(new ObjectSpawn(0x0400, 0x0040, AIZ_INTRO_PARENT_TEST_ID, 0, 0, false, 302));

        ObjectInstance staleResult = ObjectRewindDynamicCodecs.genericRecreate(
                dynamicEntry(AizIntroPlaneChild.class, spawn(0x0420, 0x0060, 403)),
                new DynamicObjectRecreateContext(objectManager));
        assertNull(staleResult,
                "generic recreate must not relink an AIZ intro plane child to a stale unmanaged active parent");

        assertSame(staleParent, AizPlaneIntroInstance.getActiveIntroInstance(),
                "sanity: stale parent remains the static active pointer until reset");
        AizPlaneIntroInstance.resetIntroPhaseState();

        ObjectInstance missingResult = ObjectRewindDynamicCodecs.genericRecreate(
                dynamicEntry(AizIntroWaveChild.class, spawn(0x0430, 0x0070, 404)),
                new DynamicObjectRecreateContext(objectManager));
        assertNull(missingResult,
                "generic recreate must drop an AIZ intro wave child when no live intro parent exists");

        ObjectInstance missingGlowResult = ObjectRewindDynamicCodecs.genericRecreate(
                dynamicEntry(AizIntroEmeraldGlowChild.class, spawn(0x0440, 0x0080, 405)),
                new DynamicObjectRecreateContext(objectManager));
        assertNull(missingGlowResult,
                "generic recreate must drop an AIZ intro glow child when no live plane exists");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            return create(List.of(PARENT_SPAWN));
        }

        static Harness createWithoutPlacedParent() {
            return create(List.of());
        }

        private static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new AizIntroParentTestRegistry(),
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

    private record AizIntroGraph(
            AizPlaneIntroInstance parent,
            AizIntroPlaneChild planeA,
            AizIntroPlaneChild planeB,
            AizIntroWaveChild waveA,
            AizIntroWaveChild waveB) {

        static AizIntroGraph spawnInto(ObjectManager objectManager) {
            AizPlaneIntroInstance parent = only(objectManager, AizPlaneIntroInstance.class);
            AizIntroPlaneChild planeA = objectManager.createDynamicObject(
                    () -> new AizIntroPlaneChild(spawn(0x0080, 0x005C, 311), parent));
            AizIntroPlaneChild planeB = objectManager.createDynamicObject(
                    () -> new AizIntroPlaneChild(spawn(0x00A0, 0x006C, 312), parent));
            AizIntroWaveChild waveA = objectManager.createDynamicObject(
                    () -> new AizIntroWaveChild(spawn(0x00C0, 0x007C, 313), parent));
            AizIntroWaveChild waveB = objectManager.createDynamicObject(
                    () -> new AizIntroWaveChild(spawn(0x00D0, 0x008C, 314), parent));

            setPlaneState(planeA, 0x0180, 0x0090, 0x0120, true, 3, 0x22);
            setPlaneState(planeB, 0x01A0, 0x00A0, -0x0080, false, 4, 0x33);
            setWaveState(waveA, 0x01C0, 0x00B0, 4, 2, 1);
            setWaveState(waveB, 0x01E0, 0x00C0, 6, 3, 2);

            return new AizIntroGraph(parent, planeA, planeB, waveA, waveB);
        }

        static AizIntroGraph fromLiveObjects(ObjectManager objectManager) {
            List<AizIntroPlaneChild> planes = liveObjects(objectManager, AizIntroPlaneChild.class);
            List<AizIntroWaveChild> waves = liveObjects(objectManager, AizIntroWaveChild.class);
            assertEquals(2, planes.size(), "expected exactly two restored plane children");
            assertEquals(2, waves.size(), "expected exactly two restored wave children");
            return new AizIntroGraph(
                    only(objectManager, AizPlaneIntroInstance.class),
                    planes.get(0),
                    planes.get(1),
                    waves.get(0),
                    waves.get(1));
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            ids.put("parent", objectId(objectManager, parent));
            ids.put("planeA", objectId(objectManager, planeA));
            ids.put("planeB", objectId(objectManager, planeB));
            ids.put("waveA", objectId(objectManager, waveA));
            ids.put("waveB", objectId(objectManager, waveB));
            return ids;
        }

        void removeDynamicChildren(ObjectManager objectManager) {
            objectManager.removeDynamicObject(planeA);
            objectManager.removeDynamicObject(planeB);
            objectManager.removeDynamicObject(waveA);
            objectManager.removeDynamicObject(waveB);
        }

        void assertChildrenAreFreshFrom(AizIntroGraph before) {
            assertNotSame(before.planeA, planeA, "plane A must be recreated, not reused stale");
            assertNotSame(before.planeB, planeB, "plane B must be recreated, not reused stale");
            assertNotSame(before.waveA, waveA, "wave A must be recreated, not reused stale");
            assertNotSame(before.waveB, waveB, "wave B must be recreated, not reused stale");
        }

        void assertChildrenAreFreshFrom(AizIntroPlaneChild replacementPlane, AizIntroWaveChild replacementWave) {
            assertNotSame(replacementPlane, planeA, "restore must drop replacement plane child");
            assertNotSame(replacementPlane, planeB, "restore must drop replacement plane child");
            assertNotSame(replacementWave, waveA, "restore must drop replacement wave child");
            assertNotSame(replacementWave, waveB, "restore must drop replacement wave child");
        }

        void assertChildrenRelinkToRestoredParent() {
            assertSame(parent, readObjectField(planeA, "parent"), "plane A parent must be restored parent");
            assertSame(parent, readObjectField(planeB, "parent"), "plane B parent must be restored parent");
            assertSame(parent, readObjectField(waveA, "parent"), "wave A parent must be restored parent");
            assertSame(parent, readObjectField(waveB, "parent"), "wave B parent must be restored parent");
        }

        void assertScalarsEqual(AizIntroGraph before) {
            assertScalarFieldsEqual(before.planeA, planeA,
                    "currentX", "currentY", "swingVelocity", "swingDirectionDown", "mappingFrame", "ySub");
            assertScalarFieldsEqual(before.planeB, planeB,
                    "currentX", "currentY", "swingVelocity", "swingDirectionDown", "mappingFrame", "ySub");
            assertScalarFieldsEqual(before.waveA, waveA,
                    "currentX", "currentY", "animIndex", "animTimer", "mappingFrame");
            assertScalarFieldsEqual(before.waveB, waveB,
                    "currentX", "currentY", "animIndex", "animTimer", "mappingFrame");
        }
    }

    private static final class AizIntroParentTestRegistry extends Sonic3kObjectRegistry {
        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            if (spawn.objectId() == AIZ_INTRO_PARENT_TEST_ID) {
                return new AizPlaneIntroInstance(spawn);
            }
            return super.create(spawn);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectManagerSnapshot.DynamicObjectEntry dynamicEntry(
            Class<? extends AbstractObjectInstance> type,
            ObjectSpawn spawn) {
        return new ObjectManagerSnapshot.DynamicObjectEntry(
                type.getName(),
                spawn,
                0,
                emptyState());
    }

    private static PerObjectRewindSnapshot emptyState() {
        return new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
    }

    private static ObjectSpawn spawn(int x, int y, int layoutIndex) {
        return spawn(x, y, 0, layoutIndex);
    }

    private static ObjectSpawn spawn(int x, int y, int subtype, int layoutIndex) {
        return new ObjectSpawn(x, y, 0, subtype, 0, false, layoutIndex);
    }

    private static Map<Class<?>, Integer> familyCounts(ObjectManager objectManager) {
        Map<Class<?>, Integer> counts = new LinkedHashMap<>();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object.getClass() == AizPlaneIntroInstance.class
                    || object.getClass() == AizIntroPlaneChild.class
                    || object.getClass() == AizIntroWaveChild.class) {
                counts.merge(object.getClass(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static void assertNoHelperObjectsBecameManagerDynamics(ObjectManager objectManager) {
        assertFalse(objectManager.getActiveObjects().stream().anyMatch(AizIntroBoosterChild.class::isInstance),
                "booster helpers must not appear as ObjectManager dynamics");
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> live = liveObjects(objectManager, type);
        assertEquals(1, live.size(), "expected exactly one live " + type.getSimpleName());
        return live.getFirst();
    }

    private static <T> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
    }

    private static void assertScalarFieldsEqual(Object before, Object restored, String... fields) {
        for (String field : fields) {
            assertEquals(readObjectField(before, field), readObjectField(restored, field),
                    () -> before.getClass().getSimpleName() + "." + field + " must restore exactly");
        }
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setPlaneState(AizIntroPlaneChild plane, int currentX, int currentY,
            int swingVelocity, boolean swingDirectionDown, int mappingFrame, int ySub) {
        setIntField(plane, "currentX", currentX);
        setIntField(plane, "currentY", currentY);
        setIntField(plane, "swingVelocity", swingVelocity);
        setBooleanField(plane, "swingDirectionDown", swingDirectionDown);
        setIntField(plane, "mappingFrame", mappingFrame);
        setIntField(plane, "ySub", ySub);
    }

    private static void setWaveState(AizIntroWaveChild wave, int currentX, int currentY,
            int animIndex, int animTimer, int mappingFrame) {
        setIntField(wave, "currentX", currentX);
        setIntField(wave, "currentY", currentY);
        setIntField(wave, "animIndex", animIndex);
        setIntField(wave, "animTimer", animTimer);
        setIntField(wave, "mappingFrame", mappingFrame);
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
