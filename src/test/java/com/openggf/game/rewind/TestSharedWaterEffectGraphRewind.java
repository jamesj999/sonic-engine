package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.level.objects.BreathingBubbleInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SplashObjectInstance;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSharedWaterEffectGraphRewind {

    private static final int[] S2_COUNTDOWN_FRAMES = {11, 10, 9, 8, 7, 6};

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void sharedWaterEffectsUseGenericRecreateWithoutExplicitCodecs() {
        for (Class<?> type : List.of(BreathingBubbleInstance.class, SplashObjectInstance.class)) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getSimpleName() + " must restore through RewindRecreatable");
        }
    }

    @Test
    void breathingBubbleRestoresFreshWithCapturedStateAndVisualConfig() {
        TestablePlayableSprite owner = player("sonic");
        Harness harness = Harness.create(owner);
        ObjectManager objectManager = harness.objectManager();
        BreathingBubbleInstance source = objectManager.createDynamicObject(
                () -> new BreathingBubbleInstance(
                        0x1600, 0x0520, true, 4, ObjectArtKeys.BUBBLES,
                        S2_COUNTDOWN_FRAMES, 3, -0x88, true, owner));
        seedBreathingBubble(source);
        ObjectRefId sourceId = objectId(objectManager, source);
        Map<String, Object> sourceState = breathingBubbleState(source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        BreathingBubbleInstance divergent = objectManager.createDynamicObject(
                () -> new BreathingBubbleInstance(
                        0x1700, 0x0620, false, -1, ObjectArtKeys.LZ_BUBBLES,
                        new int[] {13, 18, 17, 16, 15, 14}, 5, -0x80));
        assertFalse(sourceId.equals(objectId(objectManager, divergent)),
                "divergent breathing bubble must not alias the captured identity");

        registry.restore(snapshot);

        BreathingBubbleInstance restored = only(objectManager, BreathingBubbleInstance.class);
        assertNotSame(source, restored, "restore must recreate the removed breathing bubble");
        assertNotSame(divergent, restored, "restore must drop unrelated post-snapshot breathing bubbles");
        org.junit.jupiter.api.Assertions.assertSame(owner, readObject(restored, "owner"),
                "the countdown must retain its player binding after recreation");
        assertEquals(sourceId, objectId(objectManager, restored),
                "breathing-bubble dynamic identity must be preserved");
        assertEquals(sourceState, breathingBubbleState(restored),
                "compact restore must replay captured breathing-bubble scalar state and config");
        assertArrayEquals(S2_COUNTDOWN_FRAMES, (int[]) readObject(restored, "countdownFrameMap"),
                "spawn recreate must rebuild countdown frame mapping for rendered number bubbles");
    }

    @Test
    void splashRestoresFreshWithCapturedAnimationStateAndIdentity() {
        Harness harness = Harness.create(player("sonic"));
        ObjectManager objectManager = harness.objectManager();
        SplashObjectInstance source = objectManager.createDynamicObject(
                () -> new SplashObjectInstance(0x1800, 0x0440, null, true));
        seedSplash(source);
        ObjectRefId sourceId = objectId(objectManager, source);
        Map<String, Object> sourceState = splashState(source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        SplashObjectInstance divergent = objectManager.createDynamicObject(
                () -> new SplashObjectInstance(0x1900, 0x0500, null, false));
        assertFalse(sourceId.equals(objectId(objectManager, divergent)),
                "divergent splash must not alias the captured identity");

        registry.restore(snapshot);

        SplashObjectInstance restored = only(objectManager, SplashObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the removed splash");
        assertNotSame(divergent, restored, "restore must drop unrelated post-snapshot splash effects");
        assertEquals(sourceId, objectId(objectManager, restored),
                "splash dynamic identity must be preserved");
        assertEquals(sourceState, splashState(restored),
                "compact restore must replay captured splash animation state");
    }

    private static void seedBreathingBubble(BreathingBubbleInstance bubble) {
        writeInt(bubble, "currentX", 0x1608);
        writeInt(bubble, "currentY", 0x0508);
        writeInt(bubble, "baseX", 0x1604);
        writeInt(bubble, "yPos16", 0x0508 << 16);
        writeInt(bubble, "riseVelocity", -0x90);
        writeInt(bubble, "wobbleAngle", 0x45);
        writeInt(bubble, "countdownNumber", 4);
        writeInt(bubble, "countdownFrame", 8);
        writeBoolean(bubble, "numberFormed", false);
        writeInt(bubble, "lockedScreenX", 0x20);
        writeInt(bubble, "lockedScreenY", 0x30);
        writeInt(bubble, "lifetime", 12);
        writeInt(bubble, "surfacePopUpdatesRemaining", 0);
        writeBoolean(bubble, "romRenderOnScreen", true);
        writeInt(bubble, "maxBubbleFrame", 3);
    }

    private static Map<String, Object> breathingBubbleState(BreathingBubbleInstance bubble) {
        Map<String, Object> values = scalars(bubble,
                "currentX", "currentY", "baseX", "yPos16", "riseVelocity",
                "wobbleAngle", "countdownNumber", "countdownFrame", "lockedScreenX",
                "lockedScreenY", "lifetime", "surfacePopUpdatesRemaining", "maxBubbleFrame");
        values.put("numberFormed", readBoolean(bubble, "numberFormed"));
        values.put("romRenderOnScreen", readBoolean(bubble, "romRenderOnScreen"));
        values.put("artKey", readObject(bubble, "artKey"));
        values.put("countdownFrameMap", Arrays.toString((int[]) readObject(bubble, "countdownFrameMap")));
        return values;
    }

    private static void seedSplash(SplashObjectInstance splash) {
        writeInt(splash, "animTimer", 1);
        writeInt(splash, "frameIndex", 6);
        writeBoolean(splash, "facingLeft", true);
    }

    private static Map<String, Object> splashState(SplashObjectInstance splash) {
        Map<String, Object> values = scalars(splash, "animTimer", "frameIndex");
        values.put("facingLeft", readBoolean(splash, "facingLeft"));
        return values;
    }

    private static Map<String, Object> scalars(Object target, String... fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : fields) {
            values.put(field, readInt(target, field));
        }
        return values;
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager identity table must register " + object.getClass().getName());
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

    private static TestablePlayableSprite player(String code) {
        return new TestablePlayableSprite(code, (short) 0x1200, (short) 0x0400);
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

    private static Object readObject(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
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
