package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.ClamerObjectInstance;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kClamerGraphRewind {
    private static final String SPRING_CLASS =
            "com.openggf.game.sonic3k.objects.ClamerObjectInstance$ClamerSpringChild";
    private static final ObjectSpawn CLAMER_SPAWN =
            new ObjectSpawn(0x0578, 0x0690, Sonic3kObjectIds.CLAMER, 0, 0, false, 0x0690, 11);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void clamerSpringRestoresFreshExactStateAndRelinksParent() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        objectManager.update(harness.camera().getX(), null, List.of(), 0);
        objectManager.update(harness.camera().getX(), null, List.of(), 1);
        ClamerObjectInstance sourceClamer = only(objectManager, ClamerObjectInstance.class);
        ObjectInstance sourceSpring = onlySpring(objectManager);
        ObjectRefId clamerId = objectId(objectManager, sourceClamer);
        ObjectRefId springId = objectId(objectManager, sourceSpring);

        writeIntField(sourceClamer, "routine", 0x04);
        writeIntField(sourceClamer, "mappingFrame", 3);
        writeIntField(sourceClamer, "springCprop", 2);
        writeBooleanField(sourceClamer, "springInListLastFrame", false);
        writeIntField(sourceClamer, "closeTimer", 5);
        writeIntField(sourceClamer, "autoCloseAnimIndex", 4);
        writeIntField(sourceClamer, "autoCloseAnimTimer", 7);
        writeBooleanField(sourceClamer, "springFiredFlag", true);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceSpring);
        ObjectInstance divergentSpring = objectManager.createDynamicObject(() -> constructSpring(sourceClamer));
        assertEquals(1, liveSprings(objectManager).size(),
                "diverge step should leave one unrelated Clamer spring before restore");

        rewindRegistry.restore(snapshot);

        ClamerObjectInstance restoredClamer =
                objectById(objectManager, ClamerObjectInstance.class, clamerId);
        ObjectInstance restoredSpring = objectByIdRaw(objectManager, Class.forName(SPRING_CLASS), springId);
        assertEquals(1, liveSprings(objectManager).size(),
                "restore must keep exactly the captured Clamer spring");
        assertNotSame(sourceClamer, restoredClamer,
                "restore must recreate the Clamer parent");
        assertNotSame(sourceSpring, restoredSpring,
                "restore must recreate the Clamer spring child");
        assertNotSame(divergentSpring, restoredSpring,
                "restore must drop the divergent Clamer spring child");
        assertEquals(0x04, readIntField(restoredClamer, "routine"),
                "restored Clamer must keep captured routine state");
        assertEquals(3, readIntField(restoredClamer, "mappingFrame"),
                "restored Clamer must keep captured mapping frame state");
        assertEquals(2, readIntField(restoredClamer, "springCprop"),
                "restored Clamer must keep captured spring cprop state");
        assertFalse(readBooleanField(restoredClamer, "springInListLastFrame"),
                "restored Clamer must keep captured spring list state");
        assertEquals(5, readIntField(restoredClamer, "closeTimer"),
                "restored Clamer must keep captured close timer state");
        assertEquals(4, readIntField(restoredClamer, "autoCloseAnimIndex"),
                "restored Clamer must keep captured auto-close animation index");
        assertEquals(7, readIntField(restoredClamer, "autoCloseAnimTimer"),
                "restored Clamer must keep captured auto-close animation timer");
        assertTrue(readBooleanField(restoredClamer, "springFiredFlag"),
                "restored Clamer must keep captured spring-fired flag");
        assertSame(restoredClamer, readObjectField(restoredSpring, "parent"),
                "Clamer spring parent must resolve to the restored Clamer");
        assertSame(restoredSpring, readObjectField(restoredClamer, "springChildSlot"),
                "restored Clamer must point at the restored spring child");
        assertNotSame(sourceClamer, readObjectField(restoredSpring, "parent"),
                "Clamer spring must not retain the stale pre-restore parent");
        assertEquals(restoredClamer.getX(), restoredSpring.getX(),
                "restored spring should read x through the restored parent");
        assertEquals(restoredClamer.getY() - 8, restoredSpring.getY(),
                "restored spring should read y through the restored parent");
    }

    @Test
    void clamerFamilyUsesGenericRecreateWithoutExplicitS3kCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(ClamerObjectInstance.class),
                "Clamer must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(SPRING_CLASS)),
                "Clamer spring must restore through generic graph recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        ClamerObjectInstance.class.getName()),
                "Clamer must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(SPRING_CLASS),
                "Clamer spring must not keep an explicit S3K dynamic codec");
    }

    @Test
    void clamerSpringSlotFailsLoudlyWhenTargetHasNoRewindIdentity() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        objectManager.update(harness.camera().getX(), null, List.of(), 0);
        ClamerObjectInstance sourceClamer = only(objectManager, ClamerObjectInstance.class);
        ObjectInstance unmanagedSpring = constructSpring(sourceClamer);
        writeObjectField(sourceClamer, "springChildSlot", unmanagedSpring);

        RewindRegistry rewindRegistry = registryFor(objectManager);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, rewindRegistry::capture);
        assertTrue(thrown.getMessage().contains("no registered id for object reference"),
                "capturing an unmanaged Clamer springChildSlot reference must fail loudly");
    }

    private record Harness(ObjectManager objectManager, Camera camera, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtClamer();
            ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(() -> null, List::of);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public ObjectPlayerQuery playerQuery() { return playerQuery; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(CLAMER_SPAWN),
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

    private static ObjectInstance constructSpring(ClamerObjectInstance parent) {
        try {
            Class<?> type = Class.forName(SPRING_CLASS);
            Constructor<?> ctor = type.getDeclaredConstructor(ClamerObjectInstance.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct Clamer spring", e);
        }
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

    private static ObjectInstance onlySpring(ObjectManager objectManager) {
        List<ObjectInstance> springs = liveSprings(objectManager);
        assertEquals(1, springs.size(), "expected exactly one live Clamer spring");
        return springs.getFirst();
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

    private static List<ObjectInstance> liveSprings(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(SPRING_CLASS))
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static Camera mockCameraAtClamer() {
        return new Camera() {
            @Override public short getX() { return 0x0500; }
            @Override public short getY() { return 0x0620; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
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

    private static void writeObjectField(Object target, String name, Object value) {
        try {
            Field field = field(target, name);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + name + " on " + target.getClass(), e);
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
}
