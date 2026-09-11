package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.rewind.schema.RewindCaptureContext;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2MechaSonicGraphRewind {
    private static final ObjectSpawn MECHA_SPAWN =
            new ObjectSpawn(0x0240, 0x0120, Sonic2ObjectIds.MECHA_SONIC, 0, 0, false, 0x20);
    private static final ObjectSpawn DIVERGENT_MECHA_SPAWN =
            new ObjectSpawn(0x0340, 0x0140, Sonic2ObjectIds.MECHA_SONIC, 0, 0, false, 0x21);

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void mechaSonicGraphRestoresWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        MechaGraph source = MechaGraph.spawn(objectManager, MECHA_SPAWN, 0x0260, 0x0108);
        seedSourceState(source);

        Map<Class<?>, Integer> sourceCounts = source.counts();
        Map<String, ObjectRefId> sourceIds = source.identityPreservedIds(objectManager);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        source.removeAll(objectManager);
        MechaGraph divergent = MechaGraph.spawn(objectManager, DIVERGENT_MECHA_SPAWN, 0x0380, 0x0160);
        assertEquals(5, divergent.objects().size(), "divergent fixture should cover the whole graph");

        registry.restore(snapshot);

        MechaGraph restored = MechaGraph.fromLiveObjects(objectManager);
        assertEquals(sourceCounts, restored.counts(),
                "restore must not drop or duplicate any Mecha Sonic graph object");
        assertEquals(sourceIds, restored.identityPreservedIds(objectManager),
                "restore must preserve captured Mecha Sonic runtime dynamic identities");
        assertAllReferencesPointAtRestoredGraph(restored);
        assertRestoredObjectsAreFresh(source, divergent, restored);
        assertSourceScalarsRestored(restored);
    }

    @Test
    void mechaSonicFamilyUsesRewindRecreatableWithoutExplicitDynamicCodecs() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2MechaSonicInstance.class));
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2MechaSonicInstance.MechaSonicDEZWindow.class));
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2MechaSonicInstance.MechaSonicTargetingSensor.class));
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2MechaSonicInstance.MechaSonicLEDWindow.class));
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic2MechaSonicInstance.MechaSonicSpikeball.class));

        for (Class<?> type : List.of(
                Sonic2MechaSonicInstance.class,
                Sonic2MechaSonicInstance.MechaSonicDEZWindow.class,
                Sonic2MechaSonicInstance.MechaSonicTargetingSensor.class,
                Sonic2MechaSonicInstance.MechaSonicLEDWindow.class,
                Sonic2MechaSonicInstance.MechaSonicSpikeball.class)) {
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                    type.getSimpleName() + " must not rely on an explicit dynamic rewind codec");
        }
    }

    @Test
    void missingRequiredObjectReferencesStillFailWhenTargetHasNoRewindIdentity() {
        Harness harness = Harness.create();
        MechaGraph source = MechaGraph.spawn(harness.objectManager(), MECHA_SPAWN, 0x0260, 0x0108);
        Sonic2MechaSonicInstance.MechaSonicTargetingSensor unmanaged =
                new Sonic2MechaSonicInstance.MechaSonicTargetingSensor(source.boss());
        RequiredReferenceFixture fixture = new RequiredReferenceFixture(unmanaged);
        RewindCaptureContext context =
                RewindCaptureContext.withIdentityTable(new RewindIdentityTable());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture, context));
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "non-null required object references must still require registered rewind identities");
    }

    private static void seedSourceState(MechaGraph graph) {
        writeIntField(graph.boss(), "actionTimer", 0x123);
        writeIntField(graph.boss(), "attackIndex", 7);
        writeBooleanField(graph.boss(), "facingLeft", true);
        writeIntField(graph.spikeball(), "xVel", 0x234);
        writeIntField(graph.spikeball(), "yVel", -0x345);
        writeIntField(graph.spikeball(), "mappingFrame", 0x15);
        writeIntField(graph.spikeball(), "xFixed", graph.spikeball().getX() << 16);
        writeIntField(graph.spikeball(), "yFixed", graph.spikeball().getY() << 16);
    }

    private static void assertAllReferencesPointAtRestoredGraph(MechaGraph graph) {
        assertSame(graph.ledWindow(), readObjectField(graph.boss(), "ledWindow"),
                "boss ledWindow field must point at the restored LED child");
        assertSame(graph.sensor(), readObjectField(graph.boss(), "targetingSensor"),
                "boss targetingSensor field must point at the restored sensor child");
        assertSame(graph.dezWindow(), readObjectField(graph.boss(), "dezWindow"),
                "boss dezWindow field must point at the restored DEZ window child");
        assertSame(graph.boss(), readObjectField(graph.ledWindow(), "parent"),
                "LED child parent must point at the restored boss");
        assertSame(graph.boss(), readObjectField(graph.sensor(), "parent"),
                "sensor child parent must point at the restored boss");
        assertSame(graph.boss(), readObjectField(graph.dezWindow(), "parent"),
                "DEZ window child parent must point at the restored boss");
        assertSame(graph.boss(), readObjectField(graph.spikeball(), "parent"),
                "spikeball parent must point at the restored boss");
    }

    private static void assertRestoredObjectsAreFresh(
            MechaGraph source, MechaGraph divergent, MechaGraph restored) {
        for (ObjectInstance object : restored.objects()) {
            assertFalse(source.objects().contains(object), "restore must recreate removed source objects");
            assertFalse(divergent.objects().contains(object), "restore must discard divergent live objects");
        }
    }

    private static void assertSourceScalarsRestored(MechaGraph restored) {
        assertEquals(0x123, readIntField(restored.boss(), "actionTimer"));
        assertEquals(7, readIntField(restored.boss(), "attackIndex"));
        assertEquals(true, readBooleanField(restored.boss(), "facingLeft"));
        assertEquals(0x234, readIntField(restored.spikeball(), "xVel"));
        assertEquals(-0x345, readIntField(restored.spikeball(), "yVel"));
        assertEquals(0x15, readIntField(restored.spikeball(), "mappingFrame"));
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static final class RequiredReferenceFixture {
        ObjectInstance object;

        private RequiredReferenceFixture(ObjectInstance object) {
            this.object = object;
        }
    }

    private record MechaGraph(
            Sonic2MechaSonicInstance boss,
            Sonic2MechaSonicInstance.MechaSonicLEDWindow ledWindow,
            Sonic2MechaSonicInstance.MechaSonicTargetingSensor sensor,
            Sonic2MechaSonicInstance.MechaSonicDEZWindow dezWindow,
            Sonic2MechaSonicInstance.MechaSonicSpikeball spikeball) {

        static MechaGraph spawn(ObjectManager objectManager, ObjectSpawn bossSpawn, int spikeX, int spikeY) {
            Sonic2MechaSonicInstance boss = objectManager.createDynamicObject(
                    () -> new Sonic2MechaSonicInstance(bossSpawn));
            Sonic2MechaSonicInstance.MechaSonicSpikeball spikeball = objectManager.createDynamicObject(
                    () -> new Sonic2MechaSonicInstance.MechaSonicSpikeball(
                            boss, spikeX, spikeY, 0x100, -0x200, 0x0F));
            return new MechaGraph(
                    boss,
                    only(objectManager, Sonic2MechaSonicInstance.MechaSonicLEDWindow.class),
                    only(objectManager, Sonic2MechaSonicInstance.MechaSonicTargetingSensor.class),
                    only(objectManager, Sonic2MechaSonicInstance.MechaSonicDEZWindow.class),
                    spikeball);
        }

        static MechaGraph fromLiveObjects(ObjectManager objectManager) {
            return new MechaGraph(
                    only(objectManager, Sonic2MechaSonicInstance.class),
                    only(objectManager, Sonic2MechaSonicInstance.MechaSonicLEDWindow.class),
                    only(objectManager, Sonic2MechaSonicInstance.MechaSonicTargetingSensor.class),
                    only(objectManager, Sonic2MechaSonicInstance.MechaSonicDEZWindow.class),
                    only(objectManager, Sonic2MechaSonicInstance.MechaSonicSpikeball.class));
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
            ids.put("spikeball", requireId(table, spikeball));
            return ids;
        }

        void removeAll(ObjectManager objectManager) {
            objectManager.removeDynamicObject(spikeball);
            objectManager.removeDynamicObject(ledWindow);
            objectManager.removeDynamicObject(sensor);
            objectManager.removeDynamicObject(dezWindow);
            objectManager.removeDynamicObject(boss);
        }

        List<ObjectInstance> objects() {
            return List.of(boss, ledWindow, sensor, dezWindow, spikeball);
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
                @Override public short getX() { return 0x0200; }
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
