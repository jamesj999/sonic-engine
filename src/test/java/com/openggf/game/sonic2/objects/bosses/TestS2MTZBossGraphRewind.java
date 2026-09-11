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
import com.openggf.level.objects.boss.BossChildComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2MTZBossGraphRewind {
    private static final ObjectSpawn BOSS_SPAWN =
            new ObjectSpawn(0x2B50, 0x0380, Sonic2ObjectIds.MTZ_BOSS, 0, 0, false, 0x50);
    private static final ObjectSpawn DIVERGENT_BOSS_SPAWN =
            new ObjectSpawn(0x2C50, 0x0390, Sonic2ObjectIds.MTZ_BOSS, 0, 0, false, 0x51);
    private static final List<Integer> EXPECTED_TILT_FLAGS = List.of(0, 1, 1, 0, 1, 1, 0);

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void mtzBossGraphRestoresWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        MtzGraph source = MtzGraph.spawn(objectManager, BOSS_SPAWN);
        seedSourceState(source);

        Map<Class<?>, Integer> sourceCounts = source.counts();
        Map<String, ObjectRefId> sourceIds = source.identityPreservedIds(objectManager);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        source.removeAll(objectManager);
        MtzGraph divergent = MtzGraph.spawn(objectManager, DIVERGENT_BOSS_SPAWN);
        assertEquals(sourceCounts, divergent.counts(), "divergent fixture should cover the same graph shape");

        registry.restore(snapshot);

        MtzGraph restored = MtzGraph.fromLiveObjects(objectManager);
        assertEquals(sourceCounts, restored.counts(),
                "restore must not drop or duplicate any MTZ boss graph object");
        assertEquals(sourceIds, restored.identityPreservedIds(objectManager),
                "restore must preserve the captured MTZ boss dynamic identity");
        assertAllReferencesPointAtRestoredGraph(restored);
        assertRestoredObjectsAreFresh(source, divergent, restored);
        assertSourceScalarsRestored(restored);
        assertOrbConstructorStateRestored(restored);
    }

    @Test
    void mtzBossFamilyUsesRewindRecreatableWithoutExplicitDynamicCodecs() {
        for (Class<?> type : List.of(
                Sonic2MTZBossInstance.class,
                Sonic2MTZBossInstance.MTZBossOrb.class,
                Sonic2MTZBossInstance.MTZLaserShooter.class)) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getSimpleName() + " must restore through generic recreate support");
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                    type.getSimpleName() + " must not rely on an explicit dynamic rewind codec");
        }
    }

    private static void seedSourceState(MtzGraph graph) {
        writeIntField(graph.boss(), "bossXFixed", 0x2B60 << 16);
        writeIntField(graph.boss(), "bossYFixed", 0x0400 << 16);
        writeIntField(graph.boss(), "bossCountdown", 0x2A);
        writeIntField(graph.boss(), "outerOrbRadius", 0x55);
        writeIntField(graph.boss(), "innerOrbParam", 0x22);
        writeIntField(graph.boss(), "orbBreakState", -1);
        writeIntField(graph.boss(), "faceFrame", 0x0D);
        writeBooleanField(graph.boss(), "pendingOrbBreak", true);
    }

    private static void assertAllReferencesPointAtRestoredGraph(MtzGraph graph) {
        assertSame(graph.shooter(), readObjectField(graph.boss(), "laserShooter"),
                "boss laserShooter field must point at the restored shooter child");
        assertSame(graph.boss(), readObjectField(graph.shooter(), "parent"),
                "laser shooter parent must point at the restored boss");
        for (Sonic2MTZBossInstance.MTZBossOrb orb : graph.orbs()) {
            assertSame(graph.boss(), readObjectField(orb, "parent"),
                    "orb parent must point at the restored boss");
        }

        List<BossChildComponent> components = graph.boss().getChildComponents();
        assertEquals(8, components.size(), "MTZ boss should own one shooter and seven orbs");
        assertEquals(1, countIdentity(components, graph.shooter()));
        for (Sonic2MTZBossInstance.MTZBossOrb orb : graph.orbs()) {
            assertEquals(1, countIdentity(components, orb),
                    "each restored orb must appear exactly once in childComponents");
        }
    }

    private static void assertRestoredObjectsAreFresh(
            MtzGraph source, MtzGraph divergent, MtzGraph restored) {
        for (ObjectInstance object : restored.objects()) {
            assertFalse(source.objects().contains(object), "restore must recreate removed source objects");
            assertFalse(divergent.objects().contains(object), "restore must discard divergent live objects");
        }
    }

    private static void assertSourceScalarsRestored(MtzGraph restored) {
        assertEquals(0x2B60 << 16, readIntField(restored.boss(), "bossXFixed"));
        assertEquals(0x0400 << 16, readIntField(restored.boss(), "bossYFixed"));
        assertEquals(0x2A, readIntField(restored.boss(), "bossCountdown"));
        assertEquals(0x55, readIntField(restored.boss(), "outerOrbRadius"));
        assertEquals(0x22, readIntField(restored.boss(), "innerOrbParam"));
        assertEquals(-1, readIntField(restored.boss(), "orbBreakState"));
        assertEquals(0x0D, readIntField(restored.boss(), "faceFrame"));
        assertEquals(true, readBooleanField(restored.boss(), "pendingOrbBreak"));
    }

    private static void assertOrbConstructorStateRestored(MtzGraph restored) {
        assertEquals(7, restored.orbs().size());
        for (int i = 0; i < restored.orbs().size(); i++) {
            Sonic2MTZBossInstance.MTZBossOrb orb = restored.orbs().get(i);
            assertEquals(i, readIntField(orb, "orbIndex"), "orb index should match constructor order");
            assertEquals(EXPECTED_TILT_FLAGS.get(i), readIntField(orb, "tiltFlag"),
                    "orb tilt flag should match the MTZ boss orbit table");
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private record MtzGraph(
            Sonic2MTZBossInstance boss,
            Sonic2MTZBossInstance.MTZLaserShooter shooter,
            List<Sonic2MTZBossInstance.MTZBossOrb> orbs) {

        static MtzGraph spawn(ObjectManager objectManager, ObjectSpawn bossSpawn) {
            Sonic2MTZBossInstance boss =
                    objectManager.createDynamicObject(() -> new Sonic2MTZBossInstance(bossSpawn));
            return fromBoss(boss, objectManager);
        }

        static MtzGraph fromLiveObjects(ObjectManager objectManager) {
            return fromBoss(only(objectManager, Sonic2MTZBossInstance.class), objectManager);
        }

        private static MtzGraph fromBoss(Sonic2MTZBossInstance boss, ObjectManager objectManager) {
            return new MtzGraph(
                    boss,
                    only(objectManager, Sonic2MTZBossInstance.MTZLaserShooter.class),
                    liveObjects(objectManager, Sonic2MTZBossInstance.MTZBossOrb.class).stream()
                            .sorted(Comparator.comparingInt(orb -> readIntField(orb, "orbIndex")))
                            .toList());
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
            return ids;
        }

        void removeAll(ObjectManager objectManager) {
            for (ObjectInstance object : objects().reversed()) {
                objectManager.removeDynamicObject(object);
            }
        }

        List<ObjectInstance> objects() {
            return StreamUtils.concat(List.of(boss, shooter), orbs);
        }
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = liveObjects(objectManager, type);
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
    }

    private static ObjectRefId requireId(RewindIdentityTable table, ObjectInstance object) {
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass().getName());
        return id;
    }

    private static int countIdentity(List<BossChildComponent> components, BossChildComponent target) {
        int count = 0;
        for (BossChildComponent component : components) {
            if (component == target) {
                count++;
            }
        }
        return count;
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

    private static final class StreamUtils {
        private static List<ObjectInstance> concat(
                List<? extends ObjectInstance> first,
                List<? extends ObjectInstance> second) {
            return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
        }
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = new Camera() {
                @Override public short getX() { return 0x2A00; }
                @Override public short getY() { return 0x0300; }
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
