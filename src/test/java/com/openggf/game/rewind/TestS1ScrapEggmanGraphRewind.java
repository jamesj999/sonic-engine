package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.bosses.Sonic1ScrapEggmanInstance;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1ScrapEggmanGraphRewind {
    private static final ObjectSpawn SCRAP_EGGMAN_SPAWN =
            new ObjectSpawn(0x2050, 0x0510, Sonic1ObjectIds.SCRAP_EGGMAN, 0, 0, false, 0x34);
    private static final ObjectSpawn DIVERGENT_SCRAP_EGGMAN_SPAWN =
            new ObjectSpawn(0x2150, 0x0520, Sonic1ObjectIds.SCRAP_EGGMAN, 0, 0, false, 0x35);

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void scrapEggmanGraphRestoresWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        ScrapGraph source = ScrapGraph.spawn(objectManager, SCRAP_EGGMAN_SPAWN);
        seedSourceState(source);

        Map<Class<?>, Integer> sourceCounts = source.counts();
        Map<String, ObjectRefId> sourceIds = source.identityPreservedIds(objectManager);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        source.removeAll(objectManager);
        ScrapGraph divergent = ScrapGraph.spawn(objectManager, DIVERGENT_SCRAP_EGGMAN_SPAWN);
        assertEquals(sourceCounts, divergent.counts(), "divergent fixture should cover the same graph shape");

        registry.restore(snapshot);

        ScrapGraph restored = ScrapGraph.fromLiveObjects(objectManager);
        assertEquals(sourceCounts, restored.counts(),
                "restore must not drop or duplicate any Scrap Eggman graph object");
        assertEquals(sourceIds, restored.identityPreservedIds(objectManager),
                "restore must preserve captured Scrap Eggman dynamic identities");
        assertSame(restored.button(), readObjectField(restored.eggman(), "button"),
                "restored Eggman must point at the restored button");
        assertSame(restored.eggman(), readObjectField(restored.button(), "parent"),
                "restored button parent must point at the restored Eggman");
        assertSourceScalarsRestored(restored);

        restored.button().update(1, null);
        assertEquals(1, readIntField(restored.button(), "buttonFrame"),
                "restored button must keep its captured pressed state");
    }

    @Test
    void scrapEggmanFamilyUsesRewindRecreatableWithoutExplicitDynamicCodecs() {
        for (Class<?> type : List.of(
                Sonic1ScrapEggmanInstance.class,
                Sonic1ScrapEggmanInstance.ScrapEggmanButton.class)) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getSimpleName() + " must restore through generic recreate support");
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                    type.getSimpleName() + " must not rely on an explicit dynamic rewind codec");
        }
    }

    @Test
    void scrapEggmanButtonParentReferenceStillRequiresRewindIdentity() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        Sonic1ScrapEggmanInstance unmanagedParent = new Sonic1ScrapEggmanInstance(
                new ObjectSpawn(0x2050, 0x0510, 0, 0, 0, false, 0x36));
        objectManager.createDynamicObject(() -> new Sonic1ScrapEggmanInstance.ScrapEggmanButton(
                new ObjectSpawn(0x2130, 0x05BC, Sonic1ObjectIds.SCRAP_EGGMAN, 0, 0, false, 0x37),
                unmanagedParent));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> registryFor(objectManager).capture(),
                "capturing a required Scrap Eggman button parent without an identity must fail");
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "unexpected required-reference failure message: " + thrown.getMessage());
    }

    private static void seedSourceState(ScrapGraph graph) {
        writeIntField(graph.eggman(), "currentX", 0x2138);
        writeIntField(graph.eggman(), "currentY", 0x0594);
        writeIntField(graph.eggman(), "xFixed", 0x2138 << 8);
        writeIntField(graph.eggman(), "yFixed", 0x0594 << 8);
        writeIntField(graph.eggman(), "xVel", -0x60);
        writeIntField(graph.eggman(), "yVel", 0x24);
        writeIntField(graph.eggman(), "phase", 6);
        writeIntField(graph.eggman(), "timer", 0x11);
        writeIntField(graph.eggman(), "currentAnim", 1);
        writeIntField(graph.eggman(), "animFrameIndex", 1);
        writeIntField(graph.eggman(), "animTimer", 4);
        writeIntField(graph.eggman(), "mappingFrame", 2);
        writeBooleanField(graph.eggman(), "switchPressed", true);
        writeBooleanField(graph.eggman(), "floorSignalled", true);

        writeIntField(graph.button(), "buttonPhase", 2);
        writeIntField(graph.button(), "buttonFrame", 1);
    }

    private static void assertSourceScalarsRestored(ScrapGraph restored) {
        assertEquals(0x2138, restored.eggman().getX());
        assertEquals(0x0594, restored.eggman().getY());
        assertEquals(0x2138 << 8, readIntField(restored.eggman(), "xFixed"));
        assertEquals(0x0594 << 8, readIntField(restored.eggman(), "yFixed"));
        assertEquals(-0x60, readIntField(restored.eggman(), "xVel"));
        assertEquals(0x24, readIntField(restored.eggman(), "yVel"));
        assertEquals(6, readIntField(restored.eggman(), "phase"));
        assertEquals(0x11, readIntField(restored.eggman(), "timer"));
        assertEquals(1, readIntField(restored.eggman(), "currentAnim"));
        assertEquals(1, readIntField(restored.eggman(), "animFrameIndex"));
        assertEquals(4, readIntField(restored.eggman(), "animTimer"));
        assertEquals(2, readIntField(restored.eggman(), "mappingFrame"));
        assertEquals(true, readBooleanField(restored.eggman(), "switchPressed"));
        assertEquals(true, readBooleanField(restored.eggman(), "floorSignalled"));
        assertEquals(2, readIntField(restored.button(), "buttonPhase"));
        assertEquals(1, readIntField(restored.button(), "buttonFrame"));
        assertEquals(0x2130, restored.button().getX());
        assertEquals(0x05BC, restored.button().getY());
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private record ScrapGraph(
            Sonic1ScrapEggmanInstance eggman,
            Sonic1ScrapEggmanInstance.ScrapEggmanButton button) {

        static ScrapGraph spawn(ObjectManager objectManager, ObjectSpawn spawn) {
            Sonic1ScrapEggmanInstance eggman =
                    objectManager.createDynamicObject(() -> new Sonic1ScrapEggmanInstance(spawn));
            return fromEggman(eggman, objectManager);
        }

        static ScrapGraph fromLiveObjects(ObjectManager objectManager) {
            return fromEggman(only(objectManager, Sonic1ScrapEggmanInstance.class), objectManager);
        }

        private static ScrapGraph fromEggman(
                Sonic1ScrapEggmanInstance eggman, ObjectManager objectManager) {
            Sonic1ScrapEggmanInstance.ScrapEggmanButton button = only(objectManager,
                    Sonic1ScrapEggmanInstance.ScrapEggmanButton.class);
            assertSame(button, readObjectField(eggman, "button"),
                    "precondition: Eggman must point at the live button");
            return new ScrapGraph(eggman, button);
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
            ids.put("button", requireId(table, button));
            return ids;
        }

        void removeAll(ObjectManager objectManager) {
            for (ObjectInstance object : objects().reversed()) {
                objectManager.removeDynamicObject(object);
            }
        }

        List<ObjectInstance> objects() {
            return List.of(eggman, button);
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

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = new Camera() {
                @Override public short getX() { return 0x2000; }
                @Override public short getY() { return 0x0500; }
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
