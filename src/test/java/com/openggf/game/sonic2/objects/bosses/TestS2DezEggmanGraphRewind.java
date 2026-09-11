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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2DezEggmanGraphRewind {
    private static final ObjectSpawn EGGMAN_SPAWN =
            new ObjectSpawn(0x0440, 0x0168, Sonic2ObjectIds.DEZ_EGGMAN, 0xA6, 0, false, 0x62);
    private static final ObjectSpawn DIVERGENT_EGGMAN_SPAWN =
            new ObjectSpawn(0x0540, 0x0178, Sonic2ObjectIds.DEZ_EGGMAN, 0xA6, 0, false, 0x63);

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void dezEggmanGraphRestoresWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        DezEggmanGraph source = DezEggmanGraph.spawn(objectManager, EGGMAN_SPAWN);
        seedSourceState(source);

        Map<Class<?>, Integer> sourceCounts = source.counts();
        Map<String, ObjectRefId> sourceIds = source.identityPreservedIds(objectManager);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        source.removeAll(objectManager);
        DezEggmanGraph divergent = DezEggmanGraph.spawn(objectManager, DIVERGENT_EGGMAN_SPAWN);
        assertEquals(sourceCounts, divergent.counts(), "divergent fixture should cover the same graph shape");

        registry.restore(snapshot);

        DezEggmanGraph restored = DezEggmanGraph.fromLiveObjects(objectManager);
        assertEquals(sourceCounts, restored.counts(),
                "restore must not drop or duplicate any DEZ Eggman graph object");
        assertEquals(sourceIds, restored.identityPreservedIds(objectManager),
                "restore must preserve captured DEZ Eggman dynamic identities");
        assertSame(restored.wall(), readObjectField(restored.eggman(), "barrierWall"),
                "restored Eggman must point at the restored barrier wall");
        assertRestoredObjectsAreFresh(source, divergent, restored);
        assertSourceScalarsRestored(restored);

        writeIntField(restored.eggman(), "timer", 0);
        restored.eggman().update(1, null);
        assertEquals(true, readBooleanField(restored.wall(), "eggmanRunning"),
                "restored parent run transition must signal the restored wall");
    }

    @Test
    void dezEggmanFamilyUsesRewindRecreatableWithoutExplicitDynamicCodecs() {
        for (Class<?> type : List.of(
                Sonic2DEZEggmanInstance.class,
                Sonic2DEZEggmanInstance.ExhaustPuff.class,
                Sonic2DEZEggmanInstance.BarrierWall.class)) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getSimpleName() + " must restore through generic recreate support");
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                    type.getSimpleName() + " must not rely on an explicit dynamic rewind codec");
        }
    }

    private static void seedSourceState(DezEggmanGraph graph) {
        writeIntField(graph.eggman(), "routineSecondary", 4);
        writeIntField(graph.eggman(), "currentX", 0x0480);
        writeIntField(graph.eggman(), "currentY", 0x0158);
        writeIntField(graph.eggman(), "xFixed", 0x0480 << 16);
        writeIntField(graph.eggman(), "yFixed", 0x0158 << 16);
        writeIntField(graph.eggman(), "xVel", 0x180);
        writeIntField(graph.eggman(), "yVel", -0x40);
        writeIntField(graph.eggman(), "timer", 0x12);
        writeIntField(graph.eggman(), "currentFrame", 1);
        writeIntField(graph.eggman(), "animFrameIndex", 2);
        writeIntField(graph.eggman(), "animTimer", 3);
        writeIntField(graph.eggman(), "puffTimer", 7);
    }

    private static void assertRestoredObjectsAreFresh(
            DezEggmanGraph source, DezEggmanGraph divergent, DezEggmanGraph restored) {
        for (ObjectInstance object : restored.objects()) {
            assertFalse(source.objects().contains(object), "restore must recreate removed source objects");
            assertFalse(divergent.objects().contains(object), "restore must discard divergent live objects");
        }
    }

    private static void assertSourceScalarsRestored(DezEggmanGraph restored) {
        assertEquals(4, readIntField(restored.eggman(), "routineSecondary"));
        assertEquals(0x0480, restored.eggman().getX());
        assertEquals(0x0158, restored.eggman().getY());
        assertEquals(0x0480 << 16, readIntField(restored.eggman(), "xFixed"));
        assertEquals(0x0158 << 16, readIntField(restored.eggman(), "yFixed"));
        assertEquals(0x180, readIntField(restored.eggman(), "xVel"));
        assertEquals(-0x40, readIntField(restored.eggman(), "yVel"));
        assertEquals(0x12, readIntField(restored.eggman(), "timer"));
        assertEquals(1, readIntField(restored.eggman(), "currentFrame"));
        assertEquals(2, readIntField(restored.eggman(), "animFrameIndex"));
        assertEquals(3, readIntField(restored.eggman(), "animTimer"));
        assertEquals(7, readIntField(restored.eggman(), "puffTimer"));
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private record DezEggmanGraph(
            Sonic2DEZEggmanInstance eggman,
            Sonic2DEZEggmanInstance.BarrierWall wall) {

        static DezEggmanGraph spawn(ObjectManager objectManager, ObjectSpawn spawn) {
            Sonic2DEZEggmanInstance eggman =
                    objectManager.createDynamicObject(() -> new Sonic2DEZEggmanInstance(spawn));
            eggman.update(0, null);
            return fromEggman(eggman, objectManager);
        }

        static DezEggmanGraph fromLiveObjects(ObjectManager objectManager) {
            return fromEggman(only(objectManager, Sonic2DEZEggmanInstance.class), objectManager);
        }

        private static DezEggmanGraph fromEggman(
                Sonic2DEZEggmanInstance eggman, ObjectManager objectManager) {
            Sonic2DEZEggmanInstance.BarrierWall wall = only(objectManager,
                    Sonic2DEZEggmanInstance.BarrierWall.class);
            assertSame(wall, readObjectField(eggman, "barrierWall"),
                    "precondition: Eggman must point at the live barrier wall");
            return new DezEggmanGraph(eggman, wall);
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
            ids.put("eggman", requireId(table, eggman));
            ids.put("wall", requireId(table, wall));
            return ids;
        }

        void removeAll(ObjectManager objectManager) {
            for (ObjectInstance object : objects().reversed()) {
                objectManager.removeDynamicObject(object);
            }
        }

        List<ObjectInstance> objects() {
            return List.of(eggman, wall);
        }
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

    private static void writeIntField(Object target, String name, int value) {
        try {
            field(target, name).setInt(target, value);
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

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = new Camera() {
                @Override public short getX() { return 0x0400; }
                @Override public short getY() { return 0x0100; }
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
