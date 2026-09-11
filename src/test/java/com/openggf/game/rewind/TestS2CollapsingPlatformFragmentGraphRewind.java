package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.objects.CollapsingPlatformObjectInstance.CollapsingPlatformFragmentInstance;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2CollapsingPlatformFragmentGraphRewind {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void collapsingPlatformFragmentRestoresFreshWithCapturedMetadataAndIdentity() {
        Harness harness = Harness.create();
        ObjectManager objectManager = harness.objectManager();

        CollapsingPlatformFragmentInstance before = objectManager.createDynamicObject(
                () -> newFragment(0x0140, 0x0180, 0x12, 2, true, false));
        seedFragmentMetadata(before, 3, false, true);
        ObjectRefId beforeId = objectId(objectManager, before);
        Map<String, Object> beforeScalars = scalarSnapshot(before);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(before);
        CollapsingPlatformFragmentInstance divergent = objectManager.createDynamicObject(
                () -> newFragment(0x0180, 0x01A0, 0x02, 1, false, false));
        assertNotEquals(beforeId, objectId(objectManager, divergent),
                "post-snapshot divergent fragment must not alias captured identity");

        registry.restore(snapshot);

        CollapsingPlatformFragmentInstance restored = only(objectManager, CollapsingPlatformFragmentInstance.class);
        assertNotSame(before, restored, "restore must recreate the removed collapsing-platform fragment");
        assertNotSame(divergent, restored, "restore must drop unrelated post-snapshot fragments");
        assertEquals(beforeId, objectId(objectManager, restored),
                "collapsing-platform fragment dynamic identity must be preserved");
        assertEquals(beforeScalars, scalarSnapshot(restored),
                "compact restore must replay captured fragment metadata and visual offsets");
    }

    @Test
    void collapsingPlatformFragmentUsesRewindRecreatableWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(CollapsingPlatformFragmentInstance.class),
                "collapsing-platform fragments must restore through RewindRecreatable");

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        CollapsingPlatformFragmentInstance.class.getName()),
                "collapsing-platform fragments must not rely on an explicit dynamic rewind codec");
    }

    private static CollapsingPlatformFragmentInstance newFragment(
            int parentX,
            int parentY,
            int delay,
            int fragmentIndex,
            boolean hFlip,
            boolean vFlip) {
        try {
            Constructor<?> constructor = CollapsingPlatformFragmentInstance.class.getConstructor(
                    int.class, int.class, int.class, int.class,
                    Class.forName("com.openggf.game.sonic2.objects.CollapsingPlatformObjectInstance$ZoneConfig"),
                    com.openggf.level.objects.ObjectRenderManager.class,
                    boolean.class, boolean.class);
            return (CollapsingPlatformFragmentInstance) constructor.newInstance(
                    parentX, parentY, delay, fragmentIndex, null, null, hFlip, vFlip);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to create collapsing-platform fragment fixture", e);
        }
    }

    private static void seedFragmentMetadata(
            CollapsingPlatformFragmentInstance fragment,
            int fragmentIndex,
            boolean hFlip,
            boolean vFlip) {
        writeInt(fragment, "fragmentIndex", fragmentIndex);
        writeBoolean(fragment, "hFlip", hFlip);
        writeBoolean(fragment, "vFlip", vFlip);
    }

    private static Map<String, Object> scalarSnapshot(CollapsingPlatformFragmentInstance fragment) {
        Map<String, Object> scalars = new LinkedHashMap<>();
        scalars.put("fragmentIndex", readInt(fragment, "fragmentIndex"));
        scalars.put("hFlip", readBoolean(fragment, "hFlip"));
        scalars.put("vFlip", readBoolean(fragment, "vFlip"));
        return scalars;
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static int readInt(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBoolean(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void writeInt(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void writeBoolean(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Walk superclass chain.
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create() {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCameraAtOrigin();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
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
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private static Camera mockCameraAtOrigin() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
