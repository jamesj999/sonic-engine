package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.boss.AbstractBossChild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2DeathEggRobotGraphRewind {
    private static final ObjectSpawn ROBOT_SPAWN =
            new ObjectSpawn(0x0840, 0x0120, Sonic2ObjectIds.DEATH_EGG_ROBOT, 0, 0, false, 0x40);
    private static final ObjectSpawn DIVERGENT_ROBOT_SPAWN =
            new ObjectSpawn(0x0940, 0x0130, Sonic2ObjectIds.DEATH_EGG_ROBOT, 0, 0, false, 0x41);

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void deathEggRobotGraphRestoresWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        RobotGraph source = RobotGraph.spawn(objectManager, ROBOT_SPAWN, 0x0868, 0x0108);
        seedSourceState(source);

        Map<Class<?>, Integer> sourceCounts = source.counts();
        Map<String, ObjectRefId> sourceIds = source.identityPreservedIds(objectManager);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        source.removeAll(objectManager);
        RobotGraph divergent = RobotGraph.spawn(objectManager, DIVERGENT_ROBOT_SPAWN, 0x0968, 0x0118);
        assertEquals(sourceCounts, divergent.counts(), "divergent fixture should cover the same graph shape");

        registry.restore(snapshot);

        RobotGraph restored = RobotGraph.fromLiveObjects(objectManager);
        assertEquals(sourceCounts, restored.counts(),
                "restore must not drop or duplicate any Death Egg Robot graph object");
        assertEquals(sourceIds, restored.identityPreservedIds(objectManager),
                "restore must preserve captured Death Egg Robot runtime dynamic identities");
        assertAllReferencesPointAtRestoredGraph(restored);
        assertRestoredObjectsAreFresh(source, divergent, restored);
        assertSourceScalarsRestored(restored);
    }

    /**
     * EHZ-style scenario (see {@code TestEHZBossWheelOrphanAfterSiblingDestroy
     * #survivingWheelsAreExactAfterASiblingIsDestroyedBeforeCapture}) applied to
     * DEZ's 6 {@code ArticulatedChild} siblings (shoulder, frontLowerLeg, upperArm,
     * frontThigh, backLowerLeg, backThigh -- all the SAME runtime class, distinguished
     * only by construction-time name/frame/priority args and captured mutable state):
     * destroy + prune one BEFORE capture, restore, and assert the 5 survivors are
     * exactly right (present once each in childComponents and getActiveObjects(),
     * captured mutable state intact).
     *
     * <p>Root cause (see {@code ObjectManager.restore()}'s Phase-1 dynamic-object loop):
     * DEZ's boss is a captured {@code DynamicObjectEntry} processed inside the SAME
     * loop as its own 10 construction-spawned children -- and those children were
     * inserted into {@code dynamicObjects} DURING the boss's constructor, before the
     * boss itself was appended, so their captured entries sort BEFORE the boss's own
     * entry. A restore attempt for a child entry therefore used to run before the boss
     * had been reconstructed at all: the reconstruction-child pool was empty (the boss
     * hadn't run its constructor yet to populate it) and the boss itself wasn't live for
     * any relink fallback either -- every one of the 10 children's captured mutable
     * state was silently discarded, and the destroyed sibling's slot was never pruned.
     * Fixed by keeping the reconstruction-child pool active across a dynamically-spawned
     * parent's OWN Phase-1 reconstruction and retrying parked child entries once after
     * the full first pass (see {@code ObjectManager.restore()}).
     */
    @Test
    void survivingArticulatedChildrenAreExactAfterASiblingIsDestroyedBeforeCapture() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        RobotGraph source = RobotGraph.spawn(objectManager, ROBOT_SPAWN, 0x0868, 0x0108);

        // Seed distinguishing mutable state on two DIFFERENT surviving siblings so a
        // wrong ordinal-adoption pairing would show up as swapped falling/velocity
        // state between them, not just a frame/priority value that construction-time
        // wiring alone could coincidentally still get right.
        source.frontThigh().startFalling(0x111, -0x222);
        source.frontThigh().update(2, null);
        source.backLowerLeg().startFalling(0x333, -0x444);
        source.backLowerLeg().update(3, null);

        List<Sonic2DeathEggRobotInstance.ArticulatedChild> survivingSource =
                exactArticulatedChildrenOf(source.boss()).stream()
                        .filter(child -> child != source.frontLowerLeg())
                        .toList();
        assertEquals(5, survivingSource.size(), "precondition: 5 siblings other than the destroyed one");
        Set<ArticulatedChildSnapshot> expectedSurvivors = snapshotAll(survivingSource);

        // Destroy + prune ONE of the 6 indistinguishable-by-runtime-class
        // ArticulatedChild siblings before capture (matching
        // Sonic2EHZBossInstance's per-frame wheel-bounce-off-screen cleanup shape).
        source.frontLowerLeg().setDestroyed(true);
        invokePrivate(objectManager, "cleanupDestroyedDynamicObjects");
        // cleanupDestroyedDynamicObjects prunes ObjectManager.dynamicObjects (matching
        // EHZ's own precondition check), but does NOT itself touch childComponents --
        // that pruning is a rewind-restore-only reconciliation step (see
        // AbstractBossChild#onDroppedAsUnmatchedRewindReconstructionChild()), so the
        // destroyed sibling still shows up in childComponents (marked isDestroyed())
        // until then.
        assertEquals(5, objectManager.getActiveObjects().stream()
                        .filter(o -> o.getClass() == Sonic2DeathEggRobotInstance.ArticulatedChild.class
                                && !o.isDestroyed())
                        .count(),
                "precondition: exactly 5 ArticulatedChild-exact-class siblings should remain "
                        + "tracked after the destroy+prune");

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();
        registry.restore(snapshot);

        Sonic2DeathEggRobotInstance restoredBoss = only(objectManager, Sonic2DeathEggRobotInstance.class);
        List<Sonic2DeathEggRobotInstance.ArticulatedChild> restoredSurvivors =
                exactArticulatedChildrenOf(restoredBoss);

        assertEquals(5, restoredSurvivors.size(),
                "childComponents must contain EXACTLY the 5 surviving ArticulatedChild siblings "
                        + "after restore -- no orphan left by the unmatched fresh reconstruction "
                        + "candidate, and no duplicate");
        assertEquals(5, new HashSet<>(restoredSurvivors).size(),
                "no duplicate object references among the surviving siblings");

        List<ObjectInstance> active = new ArrayList<>(objectManager.getActiveObjects());
        for (Sonic2DeathEggRobotInstance.ArticulatedChild child : restoredSurvivors) {
            assertTrue(active.contains(child),
                    "every childComponents ArticulatedChild entry must also be tracked by the "
                            + "manager (no untracked live orphan)");
        }

        assertEquals(expectedSurvivors, snapshotAll(restoredSurvivors),
                "each surviving sibling's captured name/frame/priority/falling/velocity state must "
                        + "match its OWN original self, not another survivor's -- a wrong "
                        + "ordinal-based adoption pairing would silently swap this "
                        + "generically-restored state between positions, exactly like the EHZ "
                        + "wheel bug's shape");
    }

    @Test
    void deathEggRobotFamilyUsesRewindRecreatableWithoutExplicitDynamicCodecs() {
        for (Class<?> type : List.of(
                Sonic2DeathEggRobotInstance.class,
                Sonic2DeathEggRobotInstance.ArticulatedChild.class,
                Sonic2DeathEggRobotInstance.ForearmChild.class,
                Sonic2DeathEggRobotInstance.HeadChild.class,
                Sonic2DeathEggRobotInstance.JetChild.class,
                Sonic2DeathEggRobotInstance.SensorChild.class)) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getSimpleName() + " must restore through generic recreate support");
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                    type.getSimpleName() + " must not rely on an explicit dynamic rewind codec");
        }
    }

    private static void seedSourceState(RobotGraph graph) {
        writeIntField(graph.boss(), "actionTimer", 0x123);
        writeIntField(graph.boss(), "attackIndex", 3);
        writeBooleanField(graph.boss(), "facingLeft", true);

        graph.shoulder().startFalling(0x400, -0x200);
        graph.shoulder().update(5, null);
        graph.frontForearm().startFalling(0x300, -0x180);
        graph.frontForearm().update(6, null);

        writeIntField(graph.sensor(), "sensorRoutine", 4);
        writeIntField(graph.sensor(), "countdown", 0x21);
        writeBooleanField(graph.sensor(), "lockOnActive", true);
        writeBooleanField(graph.sensor(), "lockOnPaletteFlip", true);
        writeIntField(graph.sensor(), "currentX", 0x0870);
        writeIntField(graph.sensor(), "currentY", 0x00F8);
    }

    private static void assertAllReferencesPointAtRestoredGraph(RobotGraph graph) {
        assertSame(graph.shoulder(), readObjectField(graph.boss(), "shoulder"));
        assertSame(graph.frontLowerLeg(), readObjectField(graph.boss(), "frontLowerLeg"));
        assertSame(graph.frontForearm(), readObjectField(graph.boss(), "frontForearm"));
        assertSame(graph.upperArm(), readObjectField(graph.boss(), "upperArm"));
        assertSame(graph.frontThigh(), readObjectField(graph.boss(), "frontThigh"));
        assertSame(graph.head(), readObjectField(graph.boss(), "head"));
        assertSame(graph.jet(), readObjectField(graph.boss(), "jet"));
        assertSame(graph.backLowerLeg(), readObjectField(graph.boss(), "backLowerLeg"));
        assertSame(graph.backForearm(), readObjectField(graph.boss(), "backForearm"));
        assertSame(graph.backThigh(), readObjectField(graph.boss(), "backThigh"));
        assertSame(graph.sensor(), readObjectField(graph.boss(), "sensorChild"));

        for (ObjectInstance child : graph.children()) {
            assertSame(graph.boss(), readObjectField(child, "parent"),
                    child.getClass().getSimpleName() + " parent must point at restored boss");
        }
        assertEquals(10, graph.boss().getChildComponents().size(),
                "constructor-owned body children should remain the boss child component set");
        for (ObjectInstance child : graph.constructorChildren()) {
            assertTrue(graph.boss().getChildComponents().contains(child),
                    child.getClass().getSimpleName() + " must be in childComponents");
        }
    }

    private static void assertRestoredObjectsAreFresh(
            RobotGraph source, RobotGraph divergent, RobotGraph restored) {
        for (ObjectInstance object : restored.objects()) {
            assertFalse(source.objects().contains(object), "restore must recreate removed source objects");
            assertFalse(divergent.objects().contains(object), "restore must discard divergent live objects");
        }
    }

    private static void assertSourceScalarsRestored(RobotGraph restored) {
        assertEquals(0x123, readIntField(restored.boss(), "actionTimer"));
        assertEquals(3, readIntField(restored.boss(), "attackIndex"));
        assertEquals(true, readBooleanField(restored.boss(), "facingLeft"));
        assertEquals(true, readBooleanField(restored.frontForearm(), "isFront"));
        assertEquals(false, readBooleanField(restored.backForearm(), "isFront"));
        assertEquals(4, readIntField(restored.sensor(), "sensorRoutine"));
        assertEquals(0x21, readIntField(restored.sensor(), "countdown"));
        assertEquals(true, readBooleanField(restored.sensor(), "lockOnActive"));
        assertEquals(true, readBooleanField(restored.sensor(), "lockOnPaletteFlip"));
        assertEquals(0x0870, restored.sensor().getX());
        assertEquals(0x00F8, restored.sensor().getY());
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private record RobotGraph(
            Sonic2DeathEggRobotInstance boss,
            Sonic2DeathEggRobotInstance.ArticulatedChild shoulder,
            Sonic2DeathEggRobotInstance.ArticulatedChild frontLowerLeg,
            Sonic2DeathEggRobotInstance.ForearmChild frontForearm,
            Sonic2DeathEggRobotInstance.ArticulatedChild upperArm,
            Sonic2DeathEggRobotInstance.ArticulatedChild frontThigh,
            Sonic2DeathEggRobotInstance.HeadChild head,
            Sonic2DeathEggRobotInstance.JetChild jet,
            Sonic2DeathEggRobotInstance.ArticulatedChild backLowerLeg,
            Sonic2DeathEggRobotInstance.ForearmChild backForearm,
            Sonic2DeathEggRobotInstance.ArticulatedChild backThigh,
            Sonic2DeathEggRobotInstance.SensorChild sensor) {

        static RobotGraph spawn(ObjectManager objectManager, ObjectSpawn bossSpawn, int sensorX, int sensorY) {
            Sonic2DeathEggRobotInstance boss =
                    objectManager.createDynamicObject(() -> new Sonic2DeathEggRobotInstance(bossSpawn));
            Sonic2DeathEggRobotInstance.SensorChild sensor = objectManager.createDynamicObject(
                    () -> new Sonic2DeathEggRobotInstance.SensorChild(boss, sensorX, sensorY));
            writeObjectField(boss, "sensorChild", sensor);
            return fromBossAndSensor(boss, sensor);
        }

        static RobotGraph fromLiveObjects(ObjectManager objectManager) {
            return fromBossAndSensor(
                    only(objectManager, Sonic2DeathEggRobotInstance.class),
                    only(objectManager, Sonic2DeathEggRobotInstance.SensorChild.class));
        }

        private static RobotGraph fromBossAndSensor(
                Sonic2DeathEggRobotInstance boss,
                Sonic2DeathEggRobotInstance.SensorChild sensor) {
            return new RobotGraph(
                    boss,
                    readField(boss, "shoulder", Sonic2DeathEggRobotInstance.ArticulatedChild.class),
                    readField(boss, "frontLowerLeg", Sonic2DeathEggRobotInstance.ArticulatedChild.class),
                    readField(boss, "frontForearm", Sonic2DeathEggRobotInstance.ForearmChild.class),
                    readField(boss, "upperArm", Sonic2DeathEggRobotInstance.ArticulatedChild.class),
                    readField(boss, "frontThigh", Sonic2DeathEggRobotInstance.ArticulatedChild.class),
                    readField(boss, "head", Sonic2DeathEggRobotInstance.HeadChild.class),
                    readField(boss, "jet", Sonic2DeathEggRobotInstance.JetChild.class),
                    readField(boss, "backLowerLeg", Sonic2DeathEggRobotInstance.ArticulatedChild.class),
                    readField(boss, "backForearm", Sonic2DeathEggRobotInstance.ForearmChild.class),
                    readField(boss, "backThigh", Sonic2DeathEggRobotInstance.ArticulatedChild.class),
                    sensor);
        }

        Map<Class<?>, Integer> counts() {
            Map<Class<?>, Integer> counts = new LinkedHashMap<>();
            for (ObjectInstance object : objects()) {
                counts.merge(object.getClass(), 1, Integer::sum);
            }
            return counts;
        }

        Map<String, ObjectRefId> identityPreservedIds(ObjectManager objectManager) {
            RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
            Map<String, ObjectRefId> ids = new LinkedHashMap<>();
            ids.put("boss", requireId(table, boss));
            ids.put("sensor", requireId(table, sensor));
            return ids;
        }

        void removeAll(ObjectManager objectManager) {
            for (ObjectInstance object : objects().reversed()) {
                objectManager.removeDynamicObject(object);
            }
        }

        List<ObjectInstance> objects() {
            return List.of(
                    boss, shoulder, frontLowerLeg, frontForearm, upperArm, frontThigh,
                    head, jet, backLowerLeg, backForearm, backThigh, sensor);
        }

        List<ObjectInstance> children() {
            return List.of(
                    shoulder, frontLowerLeg, frontForearm, upperArm, frontThigh,
                    head, jet, backLowerLeg, backForearm, backThigh, sensor);
        }

        List<ObjectInstance> constructorChildren() {
            return List.of(
                    shoulder, frontLowerLeg, frontForearm, upperArm, frontThigh,
                    head, jet, backLowerLeg, backForearm, backThigh);
        }

    }

    /**
     * Value snapshot of an {@code ArticulatedChild}'s identity-and-mutable-state
     * signature, used to detect a wrong ordinal-adoption pairing after restore
     * (see {@code survivingArticulatedChildrenAreExactAfterASiblingIsDestroyedBeforeCapture}).
     * {@code name} is read via the (same-package-accessible) field directly rather
     * than a probing getter, matching this file's existing reflection-light style.
     */
    private record ArticulatedChildSnapshot(
            String name, int frame, int priority, boolean falling, int xVel, int yVel) {
    }

    private static ArticulatedChildSnapshot snapshotOf(Sonic2DeathEggRobotInstance.ArticulatedChild child) {
        return new ArticulatedChildSnapshot(
                child.getName(), child.frame, child.getPriority(), child.falling,
                readIntField(child, "xVel"), readIntField(child, "yVel"));
    }

    private static Set<ArticulatedChildSnapshot> snapshotAll(
            List<Sonic2DeathEggRobotInstance.ArticulatedChild> children) {
        Set<ArticulatedChildSnapshot> snapshots = new HashSet<>();
        for (Sonic2DeathEggRobotInstance.ArticulatedChild child : children) {
            snapshots.add(snapshotOf(child));
        }
        return snapshots;
    }

    /**
     * {@code childComponents} filtered to EXACT-class {@code ArticulatedChild}
     * instances (excluding the {@code ForearmChild} subclass, which shares the
     * same base fields but is a distinct runtime class for adoption purposes).
     */
    private static List<Sonic2DeathEggRobotInstance.ArticulatedChild> exactArticulatedChildrenOf(
            Sonic2DeathEggRobotInstance boss) {
        List<Sonic2DeathEggRobotInstance.ArticulatedChild> result = new ArrayList<>();
        for (var child : boss.getChildComponents()) {
            if (child.getClass() == Sonic2DeathEggRobotInstance.ArticulatedChild.class) {
                result.add((Sonic2DeathEggRobotInstance.ArticulatedChild) child);
            }
        }
        return result;
    }

    private static void invokePrivate(Object target, String methodName) throws Exception {
        java.lang.reflect.Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        m.invoke(target);
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static ObjectRefId requireId(RewindIdentityTable table, ObjectInstance object) {
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass().getName());
        return id;
    }

    private static Object readObjectField(Object target, String name) {
        try {
            return field(target, name).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int readIntField(Object target, String name) {
        try {
            return field(target, name).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean readBooleanField(Object target, String name) {
        try {
            return field(target, name).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeObjectField(Object target, String name, Object value) {
        try {
            field(target, name).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeIntField(Object target, String name, int value) {
        try {
            field(target, name).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void writeBooleanField(Object target, String name, boolean value) {
        try {
            field(target, name).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static <T> T readField(Object target, String name, Class<T> type) {
        return type.cast(readObjectField(target, name));
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = new Camera() {
                @Override public short getX() { return 0x0800; }
                @Override public short getY() { return 0x0000; }
                @Override public short getWidth() { return 320; }
                @Override public short getHeight() { return 224; }
                @Override public boolean isVerticalWrapEnabled() { return false; }
            };
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    null,
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(camera.getX());
            objectManager.setRewindInPlaceRestoreEnabledForTest(false);
            return new Harness(objectManager);
        }
    }
}
