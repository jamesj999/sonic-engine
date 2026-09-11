package com.openggf.game.rewind;

import com.openggf.game.BonusStageProvider;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotBonusStageRuntime;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageController;
import com.openggf.game.sonic3k.objects.S3kSlotBonusCageObjectInstance;
import com.openggf.game.sonic3k.objects.S3kSlotRingRewardObjectInstance;
import com.openggf.game.sonic3k.objects.S3kSlotSpikeRewardObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotBonusGraphRewind {

    private static final ObjectSpawn CAGE_SPAWN =
            new ObjectSpawn(0x0460, 0x0430, 0, 0, 0, false, 11);
    private static final ObjectSpawn RING_SPAWN =
            new ObjectSpawn(0x0500, 0x0510, 0, 0, 0, false, 12);
    private static final ObjectSpawn SPIKE_SPAWN =
            new ObjectSpawn(0x03C0, 0x03D0, 0, 0, 0, false, 13);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void slotBonusObjectsRestoreThroughActiveSlotRuntimeWithoutDropsOrStaleControllers() {
        Harness harness = Harness.withSlotRuntime();
        ObjectManager objectManager = harness.objectManager();
        SlotGraph before = SlotGraph.create(objectManager, harness.controller());
        Map<Class<?>, Integer> beforeCounts = before.counts();
        Map<String, ObjectRefId> beforeIds = before.ids(objectManager);

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        before.removeDynamicObjects(objectManager);
        SlotGraph replacement = SlotGraph.createDivergentReplacement(objectManager, harness.controller());

        rewindRegistry.restore(snapshot);

        SlotGraph restored = SlotGraph.fromLiveObjects(objectManager);
        assertEquals(beforeCounts, restored.counts(),
                "restore must not drop or duplicate slot bonus objects");
        assertEquals(beforeIds, restored.ids(objectManager),
                "restore must preserve captured slot bonus object identities");
        assertRestoredObjectsAreFresh(before, replacement, restored);
        assertAllRestoredObjectsUseLiveController(restored, harness.controller());
        assertSeededScalarsRestored(restored);
    }

    @Test
    void slotBonusObjectsUseRewindRecreatableWithoutExplicitDynamicCodecs() {
        assertRewindRecreatableWithoutCodec(S3kSlotBonusCageObjectInstance.class);
        assertRewindRecreatableWithoutCodec(S3kSlotRingRewardObjectInstance.class);
        assertRewindRecreatableWithoutCodec(S3kSlotSpikeRewardObjectInstance.class);
    }

    @Test
    void genericRecreateReturnsNullWithoutActiveSlotRuntime() {
        Harness harness = Harness.withoutSlotRuntime();
        assertNull(genericRecreate(harness.objectManager(), S3kSlotBonusCageObjectInstance.class, CAGE_SPAWN));
        assertNull(genericRecreate(harness.objectManager(), S3kSlotRingRewardObjectInstance.class, RING_SPAWN));
        assertNull(genericRecreate(harness.objectManager(), S3kSlotSpikeRewardObjectInstance.class, SPIKE_SPAWN));
    }

    private static void assertRestoredObjectsAreFresh(SlotGraph before, SlotGraph replacement, SlotGraph restored) {
        assertNotSame(before.cage(), restored.cage());
        assertNotSame(before.ring(), restored.ring());
        assertNotSame(before.spike(), restored.spike());
        assertNotSame(replacement.cage(), restored.cage());
        assertNotSame(replacement.ring(), restored.ring());
        assertNotSame(replacement.spike(), restored.spike());
    }

    private static void assertAllRestoredObjectsUseLiveController(
            SlotGraph graph,
            S3kSlotStageController controller) {
        assertSame(controller, readObjectField(graph.cage(), "controller"));
        assertSame(controller, readObjectField(graph.ring(), "controller"));
        assertSame(controller, readObjectField(graph.spike(), "controller"));
    }

    private static void assertSeededScalarsRestored(SlotGraph graph) {
        assertEquals(1, graph.cage().cageStateForTest());
        assertEquals(0x33, readIntField(graph.cage(), "waitTimer"));
        assertEquals(0x44, readIntField(graph.cage(), "rewardAngle"));
        assertEquals(5, graph.cage().pendingRewardsForTest());
        assertEquals(true, graph.cage().spawnsRingsForTest());
        assertEquals(0x12, readIntField(graph.cage(), "sfxCounter"));
        assertEquals(true, readBooleanField(graph.cage(), "payoutInitialized"));
        assertEquals((short) 0x0461, graph.cage().getCurrentX());
        assertEquals((short) 0x0432, graph.cage().getCurrentY());
        assertEquals(3, graph.cage().getMappingFrame());

        assertTrue(graph.ring().isActive());
        assertFalse(graph.ring().isInSparkle());
        assertEquals(0x0500, graph.ring().getInterpolatedX());
        assertEquals(0x0510, graph.ring().getInterpolatedY());
        assertEquals(0x12, readIntField(graph.ring(), "framesRemaining"));
        assertEquals(0x0460, readIntField(graph.ring(), "targetX"));
        assertEquals(0x0430, readIntField(graph.ring(), "targetY"));
        assertEquals(9, readIntField(graph.ring(), "lastFrameCounter"));

        assertTrue(graph.spike().isActive());
        assertEquals(0x03C0, graph.spike().getInterpolatedX());
        assertEquals(0x03D0, graph.spike().getInterpolatedY());
        assertEquals(0x13, readIntField(graph.spike(), "framesRemaining"));
        assertEquals(0x0460, readIntField(graph.spike(), "targetX"));
        assertEquals(0x0430, readIntField(graph.spike(), "targetY"));
    }

    private record Harness(
            ObjectManager objectManager,
            Sonic3kBonusStageCoordinator coordinator,
            S3kSlotStageController controller) {

        static Harness withSlotRuntime() {
            Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
            S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
            runtime.bootstrap();
            installSlotRuntime(coordinator, runtime);
            return create(coordinator, runtime.stageController());
        }

        static Harness withoutSlotRuntime() {
            return create(new Sonic3kBonusStageCoordinator(), null);
        }

        private static Harness create(
                Sonic3kBonusStageCoordinator coordinator,
                S3kSlotStageController controller) {
            ObjectManager[] holder = new ObjectManager[1];
            ObjectServices services = new SlotRewindServices(coordinator) {
                @Override
                public ObjectManager objectManager() {
                    return holder[0];
                }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    new Sonic3kObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    null,
                    services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager, coordinator, controller);
        }
    }

    private record SlotGraph(
            S3kSlotBonusCageObjectInstance cage,
            S3kSlotRingRewardObjectInstance ring,
            S3kSlotSpikeRewardObjectInstance spike) {

        static SlotGraph create(ObjectManager objectManager, S3kSlotStageController controller) {
            S3kSlotBonusCageObjectInstance cage = objectManager.createDynamicObject(
                    () -> new S3kSlotBonusCageObjectInstance(CAGE_SPAWN, controller));
            S3kSlotRingRewardObjectInstance ring = objectManager.createDynamicObject(
                    () -> new S3kSlotRingRewardObjectInstance(RING_SPAWN, controller));
            S3kSlotSpikeRewardObjectInstance spike = objectManager.createDynamicObject(
                    () -> new S3kSlotSpikeRewardObjectInstance(SPIKE_SPAWN, controller));
            seedScalars(cage, ring, spike);
            return new SlotGraph(cage, ring, spike);
        }

        static SlotGraph createDivergentReplacement(
                ObjectManager objectManager,
                S3kSlotStageController controller) {
            S3kSlotBonusCageObjectInstance cage = objectManager.createDynamicObject(
                    () -> new S3kSlotBonusCageObjectInstance(CAGE_SPAWN, controller));
            S3kSlotRingRewardObjectInstance ring = objectManager.createDynamicObject(
                    () -> new S3kSlotRingRewardObjectInstance(RING_SPAWN, controller));
            S3kSlotSpikeRewardObjectInstance spike = objectManager.createDynamicObject(
                    () -> new S3kSlotSpikeRewardObjectInstance(SPIKE_SPAWN, controller));
            setIntField(cage, "cageState", 3);
            ring.activate(0x0550, 0x0560, 0x0460, 0x0430);
            spike.activate(0x0390, 0x0398, 0x0460, 0x0430);
            return new SlotGraph(cage, ring, spike);
        }

        static SlotGraph fromLiveObjects(ObjectManager objectManager) {
            return new SlotGraph(
                    only(objectManager, S3kSlotBonusCageObjectInstance.class),
                    only(objectManager, S3kSlotRingRewardObjectInstance.class),
                    only(objectManager, S3kSlotSpikeRewardObjectInstance.class));
        }

        Map<Class<?>, Integer> counts() {
            Map<Class<?>, Integer> counts = new LinkedHashMap<>();
            for (ObjectInstance object : objects()) {
                counts.merge(object.getClass(), 1, Integer::sum);
            }
            return counts;
        }

        Map<String, ObjectRefId> ids(ObjectManager objectManager) {
            var table = objectManager.captureIdentityContext().requireIdentityTable();
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            ids.put("cage", table.idFor(cage));
            ids.put("ring", table.idFor(ring));
            ids.put("spike", table.idFor(spike));
            return ids;
        }

        void removeDynamicObjects(ObjectManager objectManager) {
            objectManager.removeDynamicObject(cage);
            objectManager.removeDynamicObject(ring);
            objectManager.removeDynamicObject(spike);
        }

        private List<ObjectInstance> objects() {
            return List.of(cage, ring, spike);
        }
    }

    private static void seedScalars(
            S3kSlotBonusCageObjectInstance cage,
            S3kSlotRingRewardObjectInstance ring,
            S3kSlotSpikeRewardObjectInstance spike) {
        setIntField(cage, "cageState", 1);
        setIntField(cage, "waitTimer", 0x33);
        setIntField(cage, "rewardAngle", 0x44);
        setIntField(cage, "rewardsToSpawn", 5);
        setBooleanField(cage, "spawnRings", true);
        setIntField(cage, "sfxCounter", 0x12);
        setBooleanField(cage, "payoutInitialized", true);
        setShortField(cage, "currentX", (short) 0x0461);
        setShortField(cage, "currentY", (short) 0x0432);
        setIntField(cage, "mappingFrame", 3);
        cage.suppressObjectManagerUpdate();

        ring.activate(0x0500, 0x0510, 0x0460, 0x0430);
        setIntField(ring, "framesRemaining", 0x12);
        setIntField(ring, "lastFrameCounter", 9);
        ring.suppressObjectManagerUpdate();

        spike.activate(0x03C0, 0x03D0, 0x0460, 0x0430);
        setIntField(spike, "framesRemaining", 0x13);
        spike.suppressObjectManagerUpdate();
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static void assertRewindRecreatableWithoutCodec(Class<? extends AbstractObjectInstance> type) {
        assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                type.getSimpleName() + " must use RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                type.getSimpleName() + " must not keep an explicit S3K dynamic rewind codec");
    }

    private static ObjectInstance genericRecreate(
            ObjectManager objectManager,
            Class<?> targetClass,
            ObjectSpawn spawn) {
        ObjectManagerSnapshot.DynamicObjectEntry entry =
                new ObjectManagerSnapshot.DynamicObjectEntry(targetClass.getName(), spawn, -1, null);
        return ObjectRewindDynamicCodecs.genericRecreate(entry, new DynamicObjectRecreateContext(objectManager));
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> live = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
        assertEquals(1, live.size(), "expected exactly one live " + type.getSimpleName());
        return live.getFirst();
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return field(target, fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return field(target, fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            return field(target, fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            field(target, fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setShortField(Object target, String fieldName, short value) {
        try {
            field(target, fieldName).setShort(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            field(target, fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field field(Object target, String fieldName) throws NoSuchFieldException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void installSlotRuntime(
            Sonic3kBonusStageCoordinator coordinator,
            S3kSlotBonusStageRuntime runtime) {
        try {
            Field field = Sonic3kBonusStageCoordinator.class.getDeclaredField("slotRuntime");
            field.setAccessible(true);
            field.set(coordinator, runtime);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to install slot runtime", e);
        }
    }

    private static class SlotRewindServices extends TestObjectServices {
        private final BonusStageProvider provider;

        SlotRewindServices(BonusStageProvider provider) {
            this.provider = provider;
        }

        @Override
        public BonusStageProvider bonusStageProviderOrNull() {
            return provider;
        }
    }
}
