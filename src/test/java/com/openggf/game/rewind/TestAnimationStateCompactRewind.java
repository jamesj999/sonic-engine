package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.PlayerRefId;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.sonic2.objects.SpringboardObjectInstance;
import com.openggf.game.sonic3k.objects.CorkFloorObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip coverage for the compact-schema reachability of non-final
 * {@link ObjectAnimationState} fields (SpringboardObjectInstance) and derived
 * lookup-table fields (CorkFloorObjectInstance). Both classes formerly fell back
 * to the generic scalar path, silently dropping their CAPTURED player references
 * (Springboard {@code launchPlayer}, cork floor {@code rollingBreakPlayer}). This
 * asserts the classes are now compact-schema capturable and that those references
 * survive a capture -> mutate -> restore cycle.
 */
class TestAnimationStateCompactRewind {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void springboardAndCorkFloorAreCompactSchemaCapturable() {
        assertCompactSupported(SpringboardObjectInstance.class);
        assertCompactSupported(CorkFloorObjectInstance.class);
    }

    @Test
    void springboardLaunchPlayerAndLazyAnimationStateSurviveRewind() {
        TestablePlayableSprite main = player("sonic");
        Harness harness = Harness.create(main);
        ObjectManager objectManager = harness.objectManager();

        ObjectSpawn spawn = new ObjectSpawn(0x1800, 0x0500, 0x0F, 0, 1, false, 0, 51);
        SpringboardObjectInstance source = objectManager.createDynamicObject(
                () -> new SpringboardObjectInstance(spawn, "Springboard"));

        // The animation state is lazily created (null after construction); populate it
        // so the non-final fresh-target-allocation path is exercised, not just the null path.
        ObjectAnimationState seeded = new ObjectAnimationState(null, 1, 5);
        writeField(source, "animationState", seeded);
        writeInt(seeded, "frameIndex", 1);
        writeInt(seeded, "frameTick", 2);
        writeInt(seeded, "lastAnimId", 1);
        writeBoolean(source, "launchSequenceActive", true);
        writeField(source, "launchPlayer", main);

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        SpringboardObjectInstance divergent = objectManager.createDynamicObject(
                () -> new SpringboardObjectInstance(new ObjectSpawn(
                        0x1900, 0x0600, 0x0F, 0, 0, false, 0, 52), "Springboard"));
        writeField(divergent, "launchPlayer", null);

        registry.restore(snapshot);

        SpringboardObjectInstance restored = only(objectManager, SpringboardObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the removed springboard");
        assertNotSame(divergent, restored, "restore must drop the unrelated post-snapshot springboard");
        assertSame(main, readObject(restored, "launchPlayer", AbstractPlayableSprite.class),
                "compact restore must replay the CAPTURED launchPlayer reference");
        assertTrue((Boolean) readValue(restored, "launchSequenceActive"),
                "compact restore must replay launchSequenceActive");
        ObjectAnimationState restoredAnim =
                readObject(restored, "animationState", ObjectAnimationState.class);
        assertEquals(seeded, restoredAnim,
                "non-final animation state must be rebuilt with the same frame counters");
    }

    @Test
    void corkFloorRollingBreakPlayerSurvivesRewind() {
        TestablePlayableSprite main = player("sonic");
        Harness harness = Harness.create(main);
        ObjectManager objectManager = harness.objectManager();

        ObjectSpawn spawn = new ObjectSpawn(0x1600, 0x0480, 0x00, 0, 3, false, 0, 61);
        CorkFloorObjectInstance source = objectManager.createDynamicObject(
                () -> new CorkFloorObjectInstance(spawn));
        writeField(source, "rollingBreakPlayer", main);
        writeBoolean(source, "playerStanding", true);
        writeInt(source, "savedPreContactYSpeed", 0x180);

        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        CorkFloorObjectInstance divergent = objectManager.createDynamicObject(
                () -> new CorkFloorObjectInstance(new ObjectSpawn(
                        0x1700, 0x0580, 0x00, 0, 0, false, 0, 62)));
        writeField(divergent, "rollingBreakPlayer", null);

        registry.restore(snapshot);

        CorkFloorObjectInstance restored = only(objectManager, CorkFloorObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the removed cork floor");
        assertNotSame(divergent, restored, "restore must drop the unrelated post-snapshot cork floor");
        assertSame(main, readObject(restored, "rollingBreakPlayer", AbstractPlayableSprite.class),
                "compact restore must replay the CAPTURED rollingBreakPlayer reference");
        assertTrue((Boolean) readValue(restored, "playerStanding"),
                "compact restore must replay playerStanding");
        assertEquals(0x180, (int) (Integer) readValue(restored, "savedPreContactYSpeed"),
                "compact restore must replay savedPreContactYSpeed");
    }

    private static void assertCompactSupported(Class<? extends ObjectInstance> type) {
        assertTrue(GenericRewindEligibility.usesDefaultObjectSubclassCapture(type),
                type.getSimpleName() + " must use the default object-subclass capture path");
        assertTrue(CompactFieldCapturer.supportsDefaultObjectSubclassScalars(type),
                type.getSimpleName() + " must be compact-schema capturable so its CAPTURED references ride keyframes");
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> matches = objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one live " + type.getSimpleName());
        return matches.getFirst();
    }

    private static TestablePlayableSprite player(String code) {
        return new TestablePlayableSprite(code, (short) 0x1200, (short) 0x0400);
    }

    private static Object readValue(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static <T> T readObject(Object target, String fieldName, Class<T> fieldType) {
        return fieldType.cast(readValue(target, fieldName));
    }

    private static void writeField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
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

    private static final class Harness {
        private final ObjectManager objectManager;

        private Harness(ObjectManager objectManager) {
            this.objectManager = objectManager;
        }

        static Harness create(AbstractPlayableSprite main) {
            TestCamera camera = new TestCamera();
            camera.setFocusedSprite(main);
            MutableServices services = new MutableServices(camera);
            ObjectManager objectManager = new ObjectManager(
                    List.of(),
                    null,
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            services.objectManager = objectManager;
            objectManager.reset(camera.getX());
            objectManager.setRewindInPlaceRestoreEnabledForTest(false);
            return new Harness(objectManager);
        }

        ObjectManager objectManager() {
            return objectManager;
        }
    }

    private static final class MutableServices extends StubObjectServices {
        private ObjectManager objectManager;
        private final Camera camera;

        private MutableServices(Camera camera) {
            this.camera = camera;
        }

        @Override public ObjectManager objectManager() { return objectManager; }
        @Override public Camera camera() { return camera; }
        @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
    }

    private static final class TestCamera extends Camera {
        private AbstractPlayableSprite focusedSprite;

        @Override public void setFocusedSprite(AbstractPlayableSprite sprite) { focusedSprite = sprite; }
        @Override public AbstractPlayableSprite getFocusedSprite() { return focusedSprite; }
        @Override public short getX() { return 0x1000; }
        @Override public short getY() { return 0x0300; }
        @Override public short getWidth() { return 320; }
        @Override public short getHeight() { return 224; }
        @Override public boolean isVerticalWrapEnabled() { return false; }
    }
}
