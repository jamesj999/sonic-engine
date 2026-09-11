package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.HCZCGZFanObjectInstance;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kHczCgzFanGraphRewind {
    private static final String PLATFORM_CLASS =
            "com.openggf.game.sonic3k.objects.HCZCGZFanObjectInstance$FanPlatformChild";
    private static final String BUBBLE_CLASS =
            "com.openggf.game.sonic3k.objects.HCZCGZFanObjectInstance$FanBubbleChild";
    private static final ObjectSpawn FAN_SPAWN =
            new ObjectSpawn(0x0580, 0x0520, Sonic3kObjectIds.HCZ_CGZ_FAN, 0xD0, 0, false, 0x0520, 11);
    private static final ObjectSpawn DIVERGENT_PLATFORM_SPAWN =
            new ObjectSpawn(0x05f0, 0x0560, Sonic3kObjectIds.HCZ_CGZ_FAN, 0, 0, false, 18);
    private static final ObjectSpawn DIVERGENT_BUBBLE_SPAWN =
            new ObjectSpawn(0x05f8, 0x04f0, Sonic3kObjectIds.HCZ_CGZ_FAN, 0, 0, false, 19);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void hczCgzFanPlatformAndBubbleRestoreFreshExactStateAndRelinkParent() throws Exception {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        HCZCGZFanObjectInstance sourceFan = only(objectManager, HCZCGZFanObjectInstance.class);
        ObjectInstance sourcePlatform = onlyPlatform(objectManager);
        sourcePlatform.update(0, null);
        ObjectInstance sourceBubble = onlyBubble(objectManager);
        ObjectRefId fanId = objectId(objectManager, sourceFan);
        ObjectRefId platformId = objectId(objectManager, sourcePlatform);
        ObjectRefId bubbleId = objectId(objectManager, sourceBubble);

        writeIntField(sourcePlatform, "x", 0x05a8);
        writeIntField(sourcePlatform, "slideOffset", 0x18);
        writeIntField(sourceBubble, "x", 0x0594);
        writeIntField(sourceBubble, "y", 0x04c8);
        writeIntField(sourceBubble, "lifetime", 7);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourcePlatform);
        objectManager.removeDynamicObject(sourceBubble);
        ObjectInstance divergentPlatform = objectManager.createDynamicObject(
                () -> constructPlatform(DIVERGENT_PLATFORM_SPAWN, sourceFan, 0x20, false));
        ObjectInstance divergentBubble = objectManager.createDynamicObject(
                () -> constructBubble(DIVERGENT_BUBBLE_SPAWN));
        assertEquals(1, livePlatforms(objectManager).size(),
                "diverge step should leave one unrelated HCZ/CGZ fan platform before restore");
        assertEquals(1, liveBubbles(objectManager).size(),
                "diverge step should leave one unrelated HCZ/CGZ fan bubble before restore");

        rewindRegistry.restore(snapshot);

        HCZCGZFanObjectInstance restoredFan =
                objectById(objectManager, HCZCGZFanObjectInstance.class, fanId);
        ObjectInstance restoredPlatform = objectByIdRaw(objectManager, Class.forName(PLATFORM_CLASS), platformId);
        ObjectInstance restoredBubble = objectByIdRaw(objectManager, Class.forName(BUBBLE_CLASS), bubbleId);
        assertEquals(1, livePlatforms(objectManager).size(),
                "restore must keep exactly the captured HCZ/CGZ fan platform");
        assertEquals(1, liveBubbles(objectManager).size(),
                "restore must keep exactly the captured HCZ/CGZ fan bubble");
        assertNotSame(sourceFan, restoredFan,
                "restore must recreate the HCZ/CGZ fan parent");
        assertNotSame(sourcePlatform, restoredPlatform,
                "restore must recreate the HCZ/CGZ fan platform");
        assertNotSame(sourceBubble, restoredBubble,
                "restore must recreate the HCZ/CGZ fan bubble");
        assertNotSame(divergentPlatform, restoredPlatform,
                "restore must drop the divergent HCZ/CGZ fan platform");
        assertNotSame(divergentBubble, restoredBubble,
                "restore must drop the divergent HCZ/CGZ fan bubble");
        assertEquals(0x05a8, readIntField(restoredPlatform, "x"),
                "restored platform must keep captured x state");
        assertEquals(0x18, readIntField(restoredPlatform, "slideOffset"),
                "restored platform must keep captured slide offset state");
        assertEquals(0x0594, readIntField(restoredBubble, "x"),
                "restored bubble must keep captured x state");
        assertEquals(0x04c8, readIntField(restoredBubble, "y"),
                "restored bubble must keep captured y state");
        assertEquals(7, readIntField(restoredBubble, "lifetime"),
                "restored bubble must keep captured lifetime state");
        assertSame(restoredFan, readObjectField(restoredPlatform, "fanParent"),
                "fan platform parent must resolve to the restored fan");
        assertSame(restoredPlatform, readObjectField(restoredFan, "platformChild"),
                "restored HCZ/CGZ fan must point at the restored platform child");
        assertNotSame(sourceFan, readObjectField(restoredPlatform, "fanParent"),
                "fan platform must not retain the stale pre-restore parent");
    }

    @Test
    void hczCgzFanFamilyUsesGenericRecreateWithoutExplicitS3kCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(HCZCGZFanObjectInstance.class),
                "HCZ/CGZ fan must restore through generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(PLATFORM_CLASS)),
                "HCZ/CGZ fan platform must restore through generic graph recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(BUBBLE_CLASS)),
                "HCZ/CGZ fan bubble must restore through generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        HCZCGZFanObjectInstance.class.getName()),
                "HCZ/CGZ fan must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(PLATFORM_CLASS),
                "HCZ/CGZ fan platform must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(BUBBLE_CLASS),
                "HCZ/CGZ fan bubble must not keep an explicit S3K dynamic codec");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtFan();
            ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(() -> null, List::of);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public ObjectPlayerQuery playerQuery() { return playerQuery; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(FAN_SPAWN),
                    new Sonic3kObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(camera.getX());
            return new Harness(objectManager, services);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectInstance constructPlatform(
            ObjectSpawn spawn,
            HCZCGZFanObjectInstance parent,
            int maxSlideDistance,
            boolean facingLeft) {
        try {
            Class<?> type = Class.forName(PLATFORM_CLASS);
            Constructor<?> ctor = type.getDeclaredConstructor(
                    ObjectSpawn.class, HCZCGZFanObjectInstance.class, int.class, boolean.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(spawn, parent, maxSlideDistance, facingLeft);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct HCZ/CGZ fan platform", e);
        }
    }

    private static ObjectInstance constructBubble(ObjectSpawn spawn) {
        try {
            Class<?> type = Class.forName(BUBBLE_CLASS);
            Constructor<?> ctor = type.getDeclaredConstructor(ObjectSpawn.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(spawn);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to construct HCZ/CGZ fan bubble", e);
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

    private static ObjectInstance onlyPlatform(ObjectManager objectManager) {
        List<ObjectInstance> platforms = livePlatforms(objectManager);
        assertEquals(1, platforms.size(), "expected exactly one live HCZ/CGZ fan platform");
        return platforms.getFirst();
    }

    private static ObjectInstance onlyBubble(ObjectManager objectManager) {
        List<ObjectInstance> bubbles = liveBubbles(objectManager);
        assertEquals(1, bubbles.size(), "expected exactly one live HCZ/CGZ fan bubble");
        return bubbles.getFirst();
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

    private static List<ObjectInstance> livePlatforms(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(PLATFORM_CLASS))
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static List<ObjectInstance> liveBubbles(ObjectManager objectManager) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(BUBBLE_CLASS))
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static Camera mockCameraAtFan() {
        return new Camera() {
            @Override public short getX() { return 0x0500; }
            @Override public short getY() { return 0x04c0; }
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

    private static void writeIntField(Object target, String name, int value) throws Exception {
        Field field = field(target, name);
        field.setInt(target, value);
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
