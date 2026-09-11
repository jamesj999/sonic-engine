package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.TornadoObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2TornadoGraphRewind {
    private static final int SUBTYPE_WFZ_START = 0x52;
    private static final int SUBTYPE_THRUSTER = 0x5C;
    private static final ObjectSpawn TORNADO_SPAWN =
            new ObjectSpawn(0x3000, 0x0300, Sonic2ObjectIds.TORNADO, SUBTYPE_WFZ_START, 0, false, 0x30);
    private static final ObjectSpawn DIVERGENT_TORNADO_SPAWN =
            new ObjectSpawn(0x3100, 0x0340, Sonic2ObjectIds.TORNADO, SUBTYPE_WFZ_START, 0, false, 0x31);

    @BeforeEach
    void initHeadlessGraphics() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void resetGraphics() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void tornadoThrusterGraphRestoresWithoutDropsDoublesOrStaleReferences() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        TornadoObjectInstance sourceTornado = objectManager.createDynamicObject(
                () -> new TornadoObjectInstance(TORNADO_SPAWN));
        TornadoObjectInstance sourceThruster =
                spawnTornadoChild(sourceTornado, SUBTYPE_THRUSTER, 0x3008, 0x0318);
        writeObjectField(sourceTornado, "thrusterFollowerChild", sourceThruster);
        writeIntField(sourceTornado, "scriptTimer", 0x123);
        writeIntField(sourceTornado, "routineSecondary", 4);
        writeIntField(sourceThruster, "routineSecondary", 2);
        writeIntField(sourceThruster, "mappingFrame", 1);

        RewindIdentityTable captureTable = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId tornadoId = requireId(captureTable, sourceTornado);
        ObjectRefId thrusterId = requireId(captureTable, sourceThruster);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(sourceThruster);
        objectManager.removeDynamicObject(sourceTornado);
        TornadoObjectInstance divergentTornado = objectManager.createDynamicObject(
                () -> new TornadoObjectInstance(DIVERGENT_TORNADO_SPAWN));
        TornadoObjectInstance divergentThruster =
                spawnTornadoChild(divergentTornado, SUBTYPE_THRUSTER, 0x3108, 0x0358);
        writeObjectField(divergentTornado, "thrusterFollowerChild", divergentThruster);

        registry.restore(snapshot);

        assertEquals(1, countLiveBySubtype(objectManager, SUBTYPE_WFZ_START),
                "restore must leave exactly one live Tornado parent");
        assertEquals(1, countLiveBySubtype(objectManager, SUBTYPE_THRUSTER),
                "restore must leave exactly one live Tornado thruster child");
        TornadoObjectInstance restoredTornado = objectById(objectManager, tornadoId);
        TornadoObjectInstance restoredThruster = objectById(objectManager, thrusterId);

        assertNotSame(sourceTornado, restoredTornado, "restore must recreate the removed Tornado parent");
        assertNotSame(sourceThruster, restoredThruster, "restore must recreate the removed thruster child");
        assertNotSame(divergentTornado, restoredTornado, "restore must drop the divergent parent");
        assertNotSame(divergentThruster, restoredThruster, "restore must drop the divergent child");
        assertSame(restoredThruster, readObjectField(restoredTornado, "thrusterFollowerChild"),
                "parent thrusterFollowerChild must relink to the restored child");
        assertSame(restoredTornado, readObjectField(restoredThruster, "parent"),
                "thruster child parent must point to the restored parent, not a stale pre-restore parent");
        assertEquals(0x123, readIntField(restoredTornado, "scriptTimer"),
                "parent scalar state must restore exactly");
        assertEquals(4, readIntField(restoredTornado, "routineSecondary"),
                "parent secondary routine must restore exactly");
        assertEquals(2, readIntField(restoredThruster, "routineSecondary"),
                "thruster secondary routine must restore exactly");
        assertEquals(1, readIntField(restoredThruster, "mappingFrame"),
                "thruster animation frame must restore exactly");
    }

    @Test
    void tornadoGraphUsesRewindRecreatableWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(TornadoObjectInstance.class),
                "Tornado parent and child subtypes must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(TornadoObjectInstance.class.getName()),
                "Tornado must not rely on an explicit dynamic rewind codec");
    }

    @Test
    void capturedTornadoThrusterReferenceFailsLoudlyWhenTargetHasNoRewindIdentity() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        TornadoObjectInstance tornado = objectManager.createDynamicObject(
                () -> new TornadoObjectInstance(TORNADO_SPAWN));
        TornadoObjectInstance unmanagedThruster =
                newTornadoChildForTesting(tornado, SUBTYPE_THRUSTER, 0x3008, 0x0318);
        writeObjectField(tornado, "thrusterFollowerChild", unmanagedThruster);

        RewindRegistry registry = registryFor(objectManager);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, registry::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "capturing an unmanaged Tornado child reference must fail loudly");
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static TornadoObjectInstance spawnTornadoChild(
            TornadoObjectInstance parent, int subtype, int x, int y) {
        try {
            Method method = TornadoObjectInstance.class.getDeclaredMethod(
                    "spawnTornadoChild", int.class, int.class, int.class);
            method.setAccessible(true);
            return (TornadoObjectInstance) method.invoke(parent, subtype, x, y);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to spawn Tornado child through production helper", e);
        }
    }

    private static TornadoObjectInstance newTornadoChildForTesting(
            TornadoObjectInstance parent, int subtype, int x, int y) {
        try {
            ObjectSpawn spawn = new ObjectSpawn(
                    x, y, Sonic2ObjectIds.TORNADO, subtype, 0, false, 0x30);
            Constructor<TornadoObjectInstance> ctor =
                    TornadoObjectInstance.class.getDeclaredConstructor(ObjectSpawn.class, TornadoObjectInstance.class);
            ctor.setAccessible(true);
            return ctor.newInstance(spawn, parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct unmanaged Tornado child", e);
        }
    }

    private static int countLiveBySubtype(ObjectManager objectManager, int subtype) {
        return (int) objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == TornadoObjectInstance.class && !object.isDestroyed())
                .filter(object -> readIntField(object, "subtype") == subtype)
                .count();
    }

    private static TornadoObjectInstance objectById(ObjectManager objectManager, ObjectRefId id) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object.getClass() == TornadoObjectInstance.class && id.equals(table.idFor(object))) {
                return (TornadoObjectInstance) object;
            }
        }
        throw new AssertionError("No live Tornado object with id " + id);
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
                @Override public short getX() { return 0x2F00; }
                @Override public short getY() { return 0x0200; }
                @Override public short getWidth() { return 320; }
                @Override public short getHeight() { return 224; }
                @Override public boolean isVerticalWrapEnabled() { return false; }
            };
            StubObjectServices services = new StubObjectServices() {
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
