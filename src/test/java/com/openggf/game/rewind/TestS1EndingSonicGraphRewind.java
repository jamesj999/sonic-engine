package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.rewind.schema.RewindCaptureContext;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1EndingEmeraldsObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1EndingSonicObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1EndingSonicGraphRewind {
    private static final ObjectSpawn ENDING_SONIC_SPAWN =
            new ObjectSpawn(0x0200, 0x0180, Sonic1ObjectIds.END_SONIC, 0, 0, false, 70);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void endingSonicRestoresFreshWithEmeraldGraphIdentityAndScalarState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        Sonic1EndingSonicObjectInstance sourceSonic = objectManager.createDynamicObject(
                () -> new Sonic1EndingSonicObjectInstance(ENDING_SONIC_SPAWN.x(), ENDING_SONIC_SPAWN.y()));
        seedSonicScalars(sourceSonic);
        List<Sonic1EndingEmeraldsObjectInstance> sourceEmeralds =
                createAndLinkEmeralds(objectManager, sourceSonic, 0x0200, 0x0180);
        seedEmeraldScalars(sourceEmeralds);
        List<Sonic1EndingEmeraldsObjectInstance> staleSourceEmeralds = List.copyOf(sourceEmeralds);

        ObjectRefId sonicId = objectId(objectManager, sourceSonic);
        List<ObjectRefId> emeraldIds = sourceEmeralds.stream()
                .map(emerald -> objectId(objectManager, emerald))
                .toList();
        Map<String, Object> sourceScalars = sonicScalarSnapshot(sourceSonic);

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(sourceSonic);
        for (Sonic1EndingEmeraldsObjectInstance emerald : staleSourceEmeralds) {
            objectManager.removeDynamicObject(emerald);
            emerald.setDestroyed(false);
        }
        Sonic1EndingSonicObjectInstance divergentSonic = objectManager.createDynamicObject(
                () -> new Sonic1EndingSonicObjectInstance(0x0040, 0x0048));
        createAndLinkEmeralds(objectManager, divergentSonic, 0x0040, 0x0048);

        registry.restore(snapshot);

        assertEquals(1, liveObjects(objectManager, Sonic1EndingSonicObjectInstance.class).size(),
                "restore must recreate exactly one Ending Sonic");
        assertEquals(6, liveObjects(objectManager, Sonic1EndingEmeraldsObjectInstance.class).size(),
                "restore must recreate exactly six ending emeralds");

        Sonic1EndingSonicObjectInstance restoredSonic =
                objectById(objectManager, Sonic1EndingSonicObjectInstance.class, sonicId);
        List<Sonic1EndingEmeraldsObjectInstance> restoredEmeralds = emeraldIds.stream()
                .map(id -> objectById(objectManager, Sonic1EndingEmeraldsObjectInstance.class, id))
                .toList();

        assertNotSame(sourceSonic, restoredSonic, "restore must recreate removed Ending Sonic");
        assertNotSame(divergentSonic, restoredSonic, "restore must drop divergent replacement Sonic");
        for (int i = 0; i < restoredEmeralds.size(); i++) {
            assertNotSame(staleSourceEmeralds.get(i), restoredEmeralds.get(i),
                    "restore must recreate removed emerald " + i);
        }
        assertEquals(sourceScalars, sonicScalarSnapshot(restoredSonic),
                "compact restore must round-trip Ending Sonic scalar state");
        assertSame(restoredEmeralds.getFirst(), readObjectField(restoredSonic, "emeraldMaster"),
                "emeraldMaster must resolve to the restored emerald instance");
        assertNotSame(staleSourceEmeralds.getFirst(), readObjectField(restoredSonic, "emeraldMaster"),
                "emeraldMaster must not retain the stale source emerald");
        assertEquals(restoredEmeralds, readEmeraldList(restoredSonic),
                "emeralds list must contain restored emerald instances only");
    }

    @Test
    void captureFailsWhenEndingSonicEmeraldReferenceHasNoRewindIdentity() {
        Harness harness = Harness.create();
        Sonic1EndingSonicObjectInstance sonic = harness.objectManager().createDynamicObject(
                () -> new Sonic1EndingSonicObjectInstance(0x0200, 0x0180));
        Sonic1EndingEmeraldsObjectInstance unmanaged =
                new Sonic1EndingEmeraldsObjectInstance(0x0200, 0x0180, 0, 1);
        unmanaged.setServices(harness.services());
        writeObjectField(sonic, "emeraldMaster", unmanaged);
        writeObjectField(sonic, "emeralds", new ArrayList<>(List.of(unmanaged)));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, registryFor(harness.objectManager())::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "unmanaged emerald reference must fail required object-ref capture");
    }

    @Test
    void restoreFailsWhenCapturedEmeraldReferenceIsMissingFromIdentityTable() {
        Harness harness = Harness.create();
        Sonic1EndingSonicObjectInstance sonic = harness.objectManager().createDynamicObject(
                () -> new Sonic1EndingSonicObjectInstance(0x0200, 0x0180));
        List<Sonic1EndingEmeraldsObjectInstance> emeralds =
                createAndLinkEmeralds(harness.objectManager(), sonic, 0x0200, 0x0180);
        PerObjectRewindSnapshot state =
                sonic.captureRewindState(harness.objectManager().captureIdentityContext());

        Sonic1EndingSonicObjectInstance target = new Sonic1EndingSonicObjectInstance(0, 0);
        target.setServices(harness.services());
        RewindIdentityTable missingTable = new RewindIdentityTable();
        missingTable.registerObject(target, objectId(harness.objectManager(), sonic));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> target.restoreRewindState(state, RewindCaptureContext.withIdentityTable(missingTable)));
        assertTrue(thrown.getMessage().contains("Missing required object reference"),
                "restore must fail loudly when captured emerald target is absent");
        assertEquals(6, emeralds.size(), "precondition: captured graph includes six emerald refs");
    }

    @Test
    void clearEmeraldsDestroysRestoredEmeraldsNotStaleSourceEmeralds() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        Sonic1EndingSonicObjectInstance sourceSonic = objectManager.createDynamicObject(
                () -> new Sonic1EndingSonicObjectInstance(0x0200, 0x0180));
        List<Sonic1EndingEmeraldsObjectInstance> sourceEmeralds =
                createAndLinkEmeralds(objectManager, sourceSonic, 0x0200, 0x0180);
        List<Sonic1EndingEmeraldsObjectInstance> staleSourceEmeralds = List.copyOf(sourceEmeralds);
        List<ObjectRefId> emeraldIds = sourceEmeralds.stream()
                .map(emerald -> objectId(objectManager, emerald))
                .toList();
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(sourceSonic);
        for (Sonic1EndingEmeraldsObjectInstance emerald : staleSourceEmeralds) {
            objectManager.removeDynamicObject(emerald);
            emerald.setDestroyed(false);
        }

        registry.restore(snapshot);
        Sonic1EndingSonicObjectInstance restoredSonic =
                liveObjects(objectManager, Sonic1EndingSonicObjectInstance.class).getFirst();
        List<Sonic1EndingEmeraldsObjectInstance> restoredEmeralds = emeraldIds.stream()
                .map(id -> objectById(objectManager, Sonic1EndingEmeraldsObjectInstance.class, id))
                .toList();

        invokeClearEmeralds(restoredSonic);

        for (Sonic1EndingEmeraldsObjectInstance emerald : restoredEmeralds) {
            assertTrue(emerald.isDestroyed(),
                    "clearEmeralds must destroy restored emerald instances");
        }
        for (Sonic1EndingEmeraldsObjectInstance stale : staleSourceEmeralds) {
            assertFalse(stale.isDestroyed(),
                    "clearEmeralds must not act on stale pre-restore emerald refs");
        }
    }

    @Test
    void endingSonicUsesRewindRecreatableWithoutExplicitS1DynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1EndingSonicObjectInstance.class),
                "Ending Sonic must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic1EndingSonicObjectInstance.class.getName()),
                "Ending Sonic must not keep an explicit S1 dynamic rewind codec");

        ObjectInstance recreated = ObjectRewindDynamicCodecs.genericRecreate(
                new com.openggf.game.rewind.snapshot.ObjectManagerSnapshot.DynamicObjectEntry(
                        Sonic1EndingSonicObjectInstance.class.getName(),
                        ENDING_SONIC_SPAWN,
                        0,
                        new PerObjectRewindSnapshot(
                                false, false, false, 0, 0, 0, 0, false, 0,
                                false, false, 0, -1, null, null, null)),
                new DynamicObjectRecreateContext(Harness.create().objectManager()));
        Sonic1EndingSonicObjectInstance sonic =
                assertInstanceOf(Sonic1EndingSonicObjectInstance.class, recreated);
        assertEquals(new ObjectSpawn(
                        ENDING_SONIC_SPAWN.x(),
                        ENDING_SONIC_SPAWN.y(),
                        Sonic1ObjectIds.END_SONIC,
                        0,
                        0,
                        false,
                        0),
                sonic.getSpawn(),
                "generic recreate must preserve the captured Ending Sonic spawn");
    }

    private static List<Sonic1EndingEmeraldsObjectInstance> createAndLinkEmeralds(
            ObjectManager objectManager,
            Sonic1EndingSonicObjectInstance sonic,
            int x,
            int y) {
        List<Sonic1EndingEmeraldsObjectInstance> emeralds = new ArrayList<>();
        int angleStep = 0x100 / 6;
        for (int i = 0; i < 6; i++) {
            int angleOffset = (angleStep * i) & 0xFF;
            int frame = i + 1;
            Sonic1EndingEmeraldsObjectInstance emerald = objectManager.createDynamicObject(
                    () -> new Sonic1EndingEmeraldsObjectInstance(x, y, angleOffset, frame));
            emeralds.add(emerald);
        }
        writeObjectField(sonic, "emeraldMaster", emeralds.getFirst());
        writeObjectField(sonic, "emeralds", emeralds);
        return emeralds;
    }

    private static void seedSonicScalars(Sonic1EndingSonicObjectInstance sonic) {
        writeIntField(sonic, "currentX", 0x0224);
        writeIntField(sonic, "currentY", 0x0198);
        writeIntField(sonic, "routine", 0x08);
        writeIntField(sonic, "timer", 37);
        writeIntField(sonic, "currentFrame", 2);
        writeIntField(sonic, "animId", 1);
        writeIntField(sonic, "animFrameIndex", 4);
        writeIntField(sonic, "animTimer", 5);
        writeBooleanField(sonic, "emeraldsSpawned", true);
        writeBooleanField(sonic, "sthSpawned", true);
        writeBooleanField(sonic, "emeraldsCleared", false);
    }

    private static void seedEmeraldScalars(List<Sonic1EndingEmeraldsObjectInstance> emeralds) {
        for (int i = 0; i < emeralds.size(); i++) {
            Sonic1EndingEmeraldsObjectInstance emerald = emeralds.get(i);
            writeIntField(emerald, "origX", 0x0200 + i);
            writeIntField(emerald, "origY", 0x0180 - i);
            writeIntField(emerald, "radius", 0x0800 + i);
            writeIntField(emerald, "rotationSpeed", 0x0100 + i);
            writeIntField(emerald, "personalAngle", 0x2000 + i);
            writeIntField(emerald, "currentX", 0x0210 + i);
            writeIntField(emerald, "currentY", 0x0170 + i);
        }
    }

    private static Map<String, Object> sonicScalarSnapshot(Sonic1EndingSonicObjectInstance sonic) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("currentX", readObjectField(sonic, "currentX"));
        values.put("currentY", readObjectField(sonic, "currentY"));
        values.put("routine", readObjectField(sonic, "routine"));
        values.put("timer", readObjectField(sonic, "timer"));
        values.put("currentFrame", readObjectField(sonic, "currentFrame"));
        values.put("animId", readObjectField(sonic, "animId"));
        values.put("animFrameIndex", readObjectField(sonic, "animFrameIndex"));
        values.put("animTimer", readObjectField(sonic, "animTimer"));
        values.put("emeraldsSpawned", readObjectField(sonic, "emeraldsSpawned"));
        values.put("sthSpawned", readObjectField(sonic, "sthSpawned"));
        values.put("emeraldsCleared", readObjectField(sonic, "emeraldsCleared"));
        return values;
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
                .sorted(Comparator.comparingInt(TestS1EndingSonicGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    @SuppressWarnings("unchecked")
    private static List<Sonic1EndingEmeraldsObjectInstance> readEmeraldList(
            Sonic1EndingSonicObjectInstance sonic) {
        return (List<Sonic1EndingEmeraldsObjectInstance>) readObjectField(sonic, "emeralds");
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
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

    private static void writeObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void invokeClearEmeralds(Sonic1EndingSonicObjectInstance sonic) {
        try {
            Method method = Sonic1EndingSonicObjectInstance.class.getDeclaredMethod("clearEmeralds");
            method.setAccessible(true);
            method.invoke(sonic);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke clearEmeralds", e);
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

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            GameStateManager gameState = new GameStateManager();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public GameStateManager gameState() { return gameState; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(), new Sonic1ObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager, services);
        }
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x700; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
