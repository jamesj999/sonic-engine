package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kIczSegmentColumnGraphRewind {
    private static final String SEGMENT_CLASS =
            "com.openggf.game.sonic3k.objects.IczSegmentColumnObjectInstance$Segment";
    private static final ObjectSpawn COLUMN_SPAWN =
            new ObjectSpawn(0x1200, 0x0580, Sonic3kObjectIds.ICZ_SEGMENT_COLUMN, 1, 0, false, 0x0580, 17);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractCameraBounds.set();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void segmentColumnRestoresSegmentChainWithRestoredRootAndPreviousLinks() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        objectManager.update(harness.camera().getX(), null, List.of(), 0);
        IczSegmentColumnObjectInstance sourceRoot = only(objectManager, IczSegmentColumnObjectInstance.class);
        List<ObjectInstance> sourceSegments = liveSegments(objectManager);
        assertEquals(4, sourceSegments.size(), "precondition: tall ICZ column must spawn four segments");
        ObjectInstance sourceFirst = sourceSegments.get(0);
        ObjectInstance sourceSecond = sourceSegments.get(1);
        ObjectInstance sourceThird = sourceSegments.get(2);
        ObjectInstance sourceTop = sourceSegments.get(3);

        writeIntField(sourceSecond, "timer", 12);
        writeBooleanField(sourceSecond, "cascadeActive", true);
        writeObjectField(sourceSecond, "phase", enumConstant(sourceSecond, "Phase", "WAITING_TO_FALL"));
        writeIntField(sourceThird, "timer", 4);
        writeObjectField(sourceThird, "phase", enumConstant(sourceThird, "Phase", "FALLING"));

        ObjectRefId rootId = objectId(objectManager, sourceRoot);
        ObjectRefId firstId = objectId(objectManager, sourceFirst);
        ObjectRefId secondId = objectId(objectManager, sourceSecond);
        ObjectRefId thirdId = objectId(objectManager, sourceThird);
        ObjectRefId topId = objectId(objectManager, sourceTop);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceFirst);
        objectManager.removeDynamicObject(sourceSecond);
        objectManager.removeDynamicObject(sourceThird);
        objectManager.removeDynamicObject(sourceTop);
        assertTrue(liveSegments(objectManager).isEmpty(),
                "diverge step should remove the captured segment graph before restore");

        rewindRegistry.restore(snapshot);

        IczSegmentColumnObjectInstance restoredRoot =
                objectById(objectManager, IczSegmentColumnObjectInstance.class, rootId);
        ObjectInstance restoredFirst = objectByIdRaw(objectManager, Class.forName(SEGMENT_CLASS), firstId);
        ObjectInstance restoredSecond = objectByIdRaw(objectManager, Class.forName(SEGMENT_CLASS), secondId);
        ObjectInstance restoredThird = objectByIdRaw(objectManager, Class.forName(SEGMENT_CLASS), thirdId);
        ObjectInstance restoredTop = objectByIdRaw(objectManager, Class.forName(SEGMENT_CLASS), topId);
        List<ObjectInstance> restoredSegments = liveSegments(objectManager);
        assertEquals(4, restoredSegments.size(),
                "restore must keep exactly the captured ICZ column segment chain");
        assertNotSame(sourceRoot, restoredRoot, "restore must recreate the ICZ column root");
        assertNotSame(sourceFirst, restoredFirst, "restore must recreate segment 0");
        assertNotSame(sourceSecond, restoredSecond, "restore must recreate segment 1");
        assertNotSame(sourceThird, restoredThird, "restore must recreate segment 2");
        assertNotSame(sourceTop, restoredTop, "restore must recreate segment 3");
        assertSame(restoredRoot, readObjectField(restoredFirst, "root"));
        assertSame(restoredRoot, readObjectField(restoredSecond, "root"));
        assertSame(restoredRoot, readObjectField(restoredThird, "root"));
        assertSame(restoredRoot, readObjectField(restoredTop, "root"));
        assertNull(readObjectField(restoredFirst, "previous"),
                "first segment must have no previous segment");
        assertSame(restoredFirst, readObjectField(restoredSecond, "previous"),
                "second segment must point at restored first segment");
        assertSame(restoredSecond, readObjectField(restoredThird, "previous"),
                "third segment must point at restored second segment");
        assertSame(restoredThird, readObjectField(restoredTop, "previous"),
                "top segment must point at restored third segment");
        assertEquals(12, readIntField(restoredSecond, "timer"),
                "second segment timer must restore exactly");
        assertTrue(readBooleanField(restoredSecond, "cascadeActive"),
                "second segment cascade flag must restore exactly");
        assertEquals("WAITING_TO_FALL", readObjectField(restoredSecond, "phase").toString(),
                "second segment phase must restore exactly");
        assertEquals(4, readIntField(restoredThird, "timer"),
                "third segment timer must restore exactly");
        assertEquals("FALLING", readObjectField(restoredThird, "phase").toString(),
                "third segment phase must restore exactly");
    }

    @Test
    void segmentColumnRestoresAfterPlayerBreaksBottomSegment() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        objectManager.update(harness.camera().getX(), null, List.of(), 0);
        List<ObjectInstance> segments = liveSegments(objectManager);
        assertEquals(4, segments.size(), "precondition: tall ICZ column must spawn four segments");
        ObjectInstance bottom = segments.get(0);
        ObjectInstance second = segments.get(1);
        assertEquals(0, readIntField(bottom, "subtype"), "precondition: bottom segment is subtype 0");
        assertEquals(2, readIntField(second, "subtype"), "precondition: second segment is subtype 2");

        // Player push-break (tryBreakFromContact): the bottom segment latches its
        // cascade flag and destroys itself; the segment above has already entered
        // its cascade wait by the end of the frame (ascending update order).
        writeBooleanField(bottom, "cascadeActive", true);
        ((com.openggf.level.objects.AbstractObjectInstance) bottom).setDestroyed(true);
        writeBooleanField(second, "cascadeActive", true);
        writeIntField(second, "timer", 10);
        writeObjectField(second, "phase", enumConstant(second, "Phase", "WAITING_TO_FALL"));

        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot withDestroyedPrevious = rewindRegistry.capture();
        objectManager.removeDynamicObject(bottom);
        CompositeSnapshot withSweptPrevious = rewindRegistry.capture();

        // Rewind back to the break frame: the subtype-2 segment's previous link
        // target is a DESTROYED (or absent) segment. Restore must not throw.
        rewindRegistry.restore(withDestroyedPrevious);
        List<ObjectInstance> restoredAfterBreak = liveSegments(objectManager);
        assertEquals(3, restoredAfterBreak.size(),
                "restore must bring back the three surviving segments");
        ObjectInstance restoredSecond = restoredAfterBreak.get(0);
        assertEquals(2, readIntField(restoredSecond, "subtype"),
                "surviving bottom-most segment must still be subtype 2");
        assertEquals("WAITING_TO_FALL", readObjectField(restoredSecond, "phase").toString(),
                "cascade phase must survive the restore");
        assertEquals(10, readIntField(restoredSecond, "timer"),
                "cascade timer must survive the restore");
        Object relinkedPrevious = readObjectField(restoredSecond, "previous");
        if (relinkedPrevious != null) {
            assertTrue(((ObjectInstance) relinkedPrevious).isDestroyed(),
                    "a relinked previous segment must be the restored destroyed break victim");
        }

        // Rewind to a frame where the broken segment was already swept: previous
        // is legitimately absent from the snapshot entirely.
        rewindRegistry.restore(withSweptPrevious);
        List<ObjectInstance> restoredAfterSweep = liveSegments(objectManager);
        assertEquals(3, restoredAfterSweep.size(),
                "restore must bring back the three surviving segments after sweep");
        ObjectInstance sweptSecond = restoredAfterSweep.get(0);
        assertEquals("WAITING_TO_FALL", readObjectField(sweptSecond, "phase").toString(),
                "cascade phase must survive the restore without a previous segment");
        assertNull(readObjectField(sweptSecond, "previous"),
                "an absent previous segment must relink as null, not crash the restore");
    }

    @Test
    void segmentColumnFamilyUsesGenericRecreateWithoutExplicitS3kCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczSegmentColumnObjectInstance.class),
                "ICZ segment column root must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(SEGMENT_CLASS)),
                "ICZ segment child must restore through generic graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        IczSegmentColumnObjectInstance.class.getName()),
                "ICZ segment column root must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(SEGMENT_CLASS),
                "ICZ segment child must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager, Camera camera, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtColumn();
            ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(() -> null, List::of);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public ObjectPlayerQuery playerQuery() { return playerQuery; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(COLUMN_SPAWN),
                    new Sonic3kObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(camera.getX());
            return new Harness(objectManager, camera, services);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager,
            Class<T> type,
            ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow();
    }

    private static ObjectInstance objectByIdRaw(
            ObjectManager objectManager,
            Class<?> type,
            ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .filter(object -> id.equals(objectManager.captureIdentityContext()
                        .requireIdentityTable()
                        .idFor(object)))
                .findFirst()
                .orElseThrow();
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> !object.isDestroyed())
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static List<ObjectInstance> liveSegments(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(SEGMENT_CLASS))
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(ObjectInstance::getY).reversed())
                .toList();
    }

    private static Camera mockCameraAtColumn() {
        return new Camera() {
            @Override public short getX() { return 0x1100; }
            @Override public short getY() { return 0x0500; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    private static Object enumConstant(Object target, String enumSimpleName, String constantName) {
        for (Class<?> nested : target.getClass().getDeclaredClasses()) {
            if (nested.getSimpleName().equals(enumSimpleName)) {
                for (Object constant : nested.getEnumConstants()) {
                    if (constant.toString().equals(constantName)) {
                        return constant;
                    }
                }
            }
        }
        throw new AssertionError("missing enum constant " + enumSimpleName + "." + constantName);
    }

    private static Object readObjectField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.get(target);
    }

    private static int readIntField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.getInt(target);
    }

    private static boolean readBooleanField(Object target, String name) throws Exception {
        Field field = field(target, name);
        return field.getBoolean(target);
    }

    private static void writeIntField(Object target, String name, int value) throws Exception {
        Field field = field(target, name);
        field.setInt(target, value);
    }

    private static void writeBooleanField(Object target, String name, boolean value) throws Exception {
        Field field = field(target, name);
        field.setBoolean(target, value);
    }

    private static void writeObjectField(Object target, String name, Object value) throws Exception {
        Field field = field(target, name);
        field.set(target, value);
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

    private static final class AbstractCameraBounds {
        static void set() {
            com.openggf.level.objects.AbstractObjectInstance.updateCameraBounds(
                    0x1100, 0x0500, 0x1100 + 320, 0x0500 + 224, 0);
        }
    }
}
