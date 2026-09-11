package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
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
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kUtilityObjectRewind {

    private static final ObjectSpawn HIDDEN_MONITOR_SPAWN =
            new ObjectSpawn(0x0280, 0x0140, Sonic3kObjectIds.HIDDEN_MONITOR, 0x0A, 0, false, 71);
    private static final ObjectSpawn SINKING_MUD_SPAWN =
            new ObjectSpawn(0x0340, 0x0180, Sonic3kObjectIds.SINKING_MUD, 0x06, 0, false, 72);
    private static final ObjectSpawn SS_ENTRY_RING_SPAWN =
            new ObjectSpawn(0x03C0, 0x0120, Sonic3kObjectIds.SS_ENTRY_RING, 0x83, 0, false, 73);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x800, 0x500, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void utilityObjectsRestoreFreshWithoutDropsAndPreserveConstructorState() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        S3kHiddenMonitorInstance hiddenMonitor = objectManager.createDynamicObject(
                () -> new S3kHiddenMonitorInstance(HIDDEN_MONITOR_SPAWN));
        SinkingMudObjectInstance sinkingMud = objectManager.createDynamicObject(
                () -> new SinkingMudObjectInstance(SINKING_MUD_SPAWN));
        Sonic3kSSEntryRingObjectInstance ssEntryRing = objectManager.createDynamicObject(
                () -> new Sonic3kSSEntryRingObjectInstance(SS_ENTRY_RING_SPAWN));
        seedState(hiddenMonitor, ssEntryRing);

        ObjectRefId hiddenMonitorId = objectId(objectManager, hiddenMonitor);
        ObjectRefId sinkingMudId = objectId(objectManager, sinkingMud);
        ObjectRefId ssEntryRingId = objectId(objectManager, ssEntryRing);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(hiddenMonitor);
        objectManager.removeDynamicObject(sinkingMud);
        objectManager.removeDynamicObject(ssEntryRing);
        S3kHiddenMonitorInstance replacementHiddenMonitor = objectManager.createDynamicObject(
                () -> new S3kHiddenMonitorInstance(new ObjectSpawn(
                        0x0100, 0x0100, Sonic3kObjectIds.HIDDEN_MONITOR, 0x00, 0, false, 74)));
        SinkingMudObjectInstance replacementSinkingMud = objectManager.createDynamicObject(
                () -> new SinkingMudObjectInstance(new ObjectSpawn(
                        0x0140, 0x0100, Sonic3kObjectIds.SINKING_MUD, 0x01, 0, false, 75)));
        Sonic3kSSEntryRingObjectInstance replacementSsEntryRing = objectManager.createDynamicObject(
                () -> new Sonic3kSSEntryRingObjectInstance(new ObjectSpawn(
                        0x0180, 0x0100, Sonic3kObjectIds.SS_ENTRY_RING, 0x00, 0, false, 76)));

        registryFor(objectManager).restore(snapshot);

        assertEquals(1, liveObjects(objectManager, S3kHiddenMonitorInstance.class).size(),
                "restore must keep exactly the captured hidden monitor");
        assertEquals(1, liveObjects(objectManager, SinkingMudObjectInstance.class).size(),
                "restore must keep exactly the captured sinking mud object");
        assertEquals(1, liveObjects(objectManager, Sonic3kSSEntryRingObjectInstance.class).size(),
                "restore must keep exactly the captured SS-entry ring");

        S3kHiddenMonitorInstance restoredHiddenMonitor =
                objectById(objectManager, S3kHiddenMonitorInstance.class, hiddenMonitorId);
        SinkingMudObjectInstance restoredSinkingMud =
                objectById(objectManager, SinkingMudObjectInstance.class, sinkingMudId);
        Sonic3kSSEntryRingObjectInstance restoredSsEntryRing =
                objectById(objectManager, Sonic3kSSEntryRingObjectInstance.class, ssEntryRingId);

        assertNotSame(hiddenMonitor, restoredHiddenMonitor, "restore must recreate the hidden monitor");
        assertNotSame(sinkingMud, restoredSinkingMud, "restore must recreate the sinking mud object");
        assertNotSame(ssEntryRing, restoredSsEntryRing, "restore must recreate the SS-entry ring");
        assertNotSame(replacementHiddenMonitor, restoredHiddenMonitor,
                "restore must drop the divergent hidden monitor");
        assertNotSame(replacementSinkingMud, restoredSinkingMud,
                "restore must drop the divergent sinking mud object");
        assertNotSame(replacementSsEntryRing, restoredSsEntryRing,
                "restore must drop the divergent SS-entry ring");

        assertEquals(HIDDEN_MONITOR_SPAWN, restoredHiddenMonitor.getSpawn(),
                "hidden monitor spawn must restore exactly");
        assertEquals(SINKING_MUD_SPAWN, restoredSinkingMud.getSpawn(),
                "sinking mud spawn must restore exactly");
        assertEquals(SS_ENTRY_RING_SPAWN, restoredSsEntryRing.getSpawn(),
                "SS-entry ring spawn must restore exactly");

        assertHiddenMonitorState(restoredHiddenMonitor);
        assertSinkingMudState(restoredSinkingMud);
        assertSsEntryRingState(restoredSsEntryRing);
    }

    @Test
    void utilityObjectsUseRewindRecreatableWithoutExplicitCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(S3kHiddenMonitorInstance.class),
                "S3kHiddenMonitorInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(SinkingMudObjectInstance.class),
                "SinkingMudObjectInstance must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic3kSSEntryRingObjectInstance.class),
                "Sonic3kSSEntryRingObjectInstance must restore through generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        S3kHiddenMonitorInstance.class.getName()),
                "S3kHiddenMonitorInstance must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        SinkingMudObjectInstance.class.getName()),
                "SinkingMudObjectInstance must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        Sonic3kSSEntryRingObjectInstance.class.getName()),
                "Sonic3kSSEntryRingObjectInstance must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    new Sonic3kObjectRegistry(),
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

    private static void seedState(
            S3kHiddenMonitorInstance hiddenMonitor,
            Sonic3kSSEntryRingObjectInstance ssEntryRing) {
        setBooleanField(hiddenMonitor, "resolved", true);
        setObjectField(ssEntryRing, "state", enumConstant("State", "ENTERED",
                Sonic3kSSEntryRingObjectInstance.class));
        setBooleanField(ssEntryRing, "initialized", true);
        setIntField(ssEntryRing, "animTimer", 5);
        setIntField(ssEntryRing, "animIndex", 2);
        setIntField(ssEntryRing, "mappingFrame", 10);
        setBooleanField(ssEntryRing, "inIdleAnim", true);
    }

    private static void assertHiddenMonitorState(S3kHiddenMonitorInstance hiddenMonitor) {
        assertEquals(HIDDEN_MONITOR_SPAWN.x(), readIntField(hiddenMonitor, "monitorX"),
                "hidden monitor X must rebuild from spawn");
        assertEquals(HIDDEN_MONITOR_SPAWN.y(), readIntField(hiddenMonitor, "monitorY"),
                "hidden monitor Y must rebuild from spawn");
        assertEquals(HIDDEN_MONITOR_SPAWN.subtype(), readIntField(hiddenMonitor, "monitorSubtype"),
                "hidden monitor subtype must rebuild from spawn");
        assertTrue(readBooleanField(hiddenMonitor, "resolved"),
                "hidden monitor resolved state must restore exactly");
    }

    private static void assertSinkingMudState(SinkingMudObjectInstance sinkingMud) {
        assertEquals((SINKING_MUD_SPAWN.subtype() & 0xFF) << 3, readIntField(sinkingMud, "halfWidth"),
                "sinking mud half-width must rebuild from subtype");
    }

    private static void assertSsEntryRingState(Sonic3kSSEntryRingObjectInstance ring) {
        assertEquals(SS_ENTRY_RING_SPAWN.subtype() & 0x1F, readIntField(ring, "bitIndex"),
                "SS-entry ring bit index must rebuild from subtype");
        assertTrue(readBooleanField(ring, "hiddenPalaceRoute"),
                "SS-entry ring hidden-palace route flag must rebuild from subtype bit 7");
        assertTrue(readBooleanField(ring, "initialized"),
                "SS-entry ring initialized flag must restore exactly");
        assertEquals(5, readIntField(ring, "animTimer"),
                "SS-entry ring animation timer must restore exactly");
        assertEquals(2, readIntField(ring, "animIndex"),
                "SS-entry ring animation index must restore exactly");
        assertEquals(10, ring.getMappingFrame(),
                "SS-entry ring mapping frame must restore exactly");
        assertTrue(readBooleanField(ring, "inIdleAnim"),
                "SS-entry ring idle animation flag must restore exactly");
        assertFalse(ring.isMainState(),
                "SS-entry ring state must restore exactly");
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

    private static <T extends ObjectInstance> List<T> liveObjects(
            ObjectManager objectManager,
            Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS3kUtilityObjectRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    private static Object enumConstant(String simpleName, String constantName, Class<?> enclosingType) {
        for (Class<?> nested : enclosingType.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(simpleName) && nested.isEnum()) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object value = Enum.valueOf((Class<? extends Enum>) nested, constantName);
                return value;
            }
        }
        throw new AssertionError("Missing enum " + simpleName + " on " + enclosingType);
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
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

    private static void setObjectField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
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

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x500; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
