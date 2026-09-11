package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.SpiralObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2SpiralGraphRewind {
    private static final ObjectSpawn SPIRAL_SPAWN =
            new ObjectSpawn(0x1200, 0x0400, Sonic2ObjectIds.SPIRAL, 0, 0, false, 0x86);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void spiralUsesRewindRecreatableWithoutExplicitDynamicCodec() {
        assertTrue(RewindRecreatable.class.isAssignableFrom(SpiralObjectInstance.class),
                "Spiral must restore through RewindRecreatable");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(SpiralObjectInstance.class.getName()),
                "Spiral must not rely on an explicit dynamic rewind codec");
    }

    @Test
    void spiralPlayerStateRestoresToCurrentLivePlayerReferences() {
        TestablePlayableSprite capturedMain = player("old-sonic");
        TestablePlayableSprite capturedSidekick = player("old-tails");
        Harness harness = Harness.create(capturedMain, List.of(capturedSidekick));
        ObjectManager objectManager = harness.objectManager();
        SpiralObjectInstance source = objectManager.createDynamicObject(
                () -> new SpiralObjectInstance(SPIRAL_SPAWN, "Spiral"));
        seedSpiral(source, capturedMain, capturedSidekick);
        ObjectRefId sourceId = objectId(objectManager, source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        TestablePlayableSprite restoredMain = player("new-sonic");
        TestablePlayableSprite restoredSidekick = player("new-tails");
        harness.setPlayers(restoredMain, List.of(restoredSidekick));

        registry.restore(snapshot);

        SpiralObjectInstance restored = only(objectManager, SpiralObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the spiral");
        assertEquals(sourceId, objectId(objectManager, restored),
                "spiral dynamic identity must be preserved");
        assertPlayerLinks(restored, restoredMain, restoredSidekick, capturedMain, capturedSidekick);
    }

    private static void seedSpiral(
            SpiralObjectInstance spiral,
            AbstractPlayableSprite main,
            AbstractPlayableSprite sidekick) {
        setAdd(spiral, "ridingPlayers", main);
        setAdd(spiral, "ridingPlayers", sidekick);
        mapPut(spiral, "cylinderAngles", main, 0x14);
        mapPut(spiral, "cylinderAngles", sidekick, 0x28);
    }

    private static void assertPlayerLinks(
            SpiralObjectInstance restored,
            AbstractPlayableSprite restoredMain,
            AbstractPlayableSprite restoredSidekick,
            AbstractPlayableSprite oldMain,
            AbstractPlayableSprite oldSidekick) {
        Set<?> riders = readSet(restored, "ridingPlayers");
        Map<?, ?> cylinderAngles = readMap(restored, "cylinderAngles");

        assertFalse(riders.contains(oldMain), "restored rider set must not retain stale main-player reference");
        assertFalse(riders.contains(oldSidekick), "restored rider set must not retain stale sidekick reference");
        assertTrue(riders.contains(restoredMain), "restored rider set must relink the live main player");
        assertTrue(riders.contains(restoredSidekick), "restored rider set must relink the live sidekick");

        assertFalse(cylinderAngles.containsKey(oldMain), "restored angle map must not retain stale main-player key");
        assertFalse(cylinderAngles.containsKey(oldSidekick), "restored angle map must not retain stale sidekick key");
        assertEquals(0x14, cylinderAngles.get(restoredMain));
        assertEquals(0x28, cylinderAngles.get(restoredSidekick));
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

    @SuppressWarnings("unchecked")
    private static Set<Object> readSet(Object target, String fieldName) {
        try {
            Object value = findField(target.getClass(), fieldName).get(target);
            return (Set<Object>) value;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> readMap(Object target, String fieldName) {
        try {
            Object value = findField(target.getClass(), fieldName).get(target);
            return (Map<Object, Object>) value;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setAdd(Object target, String fieldName, Object value) {
        readSet(target, fieldName).add(value);
    }

    private static void mapPut(Object target, String fieldName, Object key, Object value) {
        readMap(target, fieldName).put(key, value);
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
        private final TestCamera camera;
        private final MutableServices services;

        private Harness(ObjectManager objectManager, TestCamera camera, MutableServices services) {
            this.objectManager = objectManager;
            this.camera = camera;
            this.services = services;
        }

        static Harness create(AbstractPlayableSprite main, List<? extends PlayableEntity> sidekicks) {
            TestCamera camera = new TestCamera();
            camera.setFocusedSprite(main);
            MutableServices services = new MutableServices(camera, List.copyOf(sidekicks));
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
            return new Harness(objectManager, camera, services);
        }

        ObjectManager objectManager() {
            return objectManager;
        }

        void setPlayers(AbstractPlayableSprite main, List<? extends PlayableEntity> sidekicks) {
            camera.setFocusedSprite(main);
            services.sidekicks = List.copyOf(sidekicks);
        }
    }

    private static final class MutableServices extends StubObjectServices {
        private ObjectManager objectManager;
        private final Camera camera;
        private List<? extends PlayableEntity> sidekicks;

        private MutableServices(Camera camera, List<? extends PlayableEntity> sidekicks) {
            this.camera = camera;
            this.sidekicks = sidekicks;
        }

        @Override public ObjectManager objectManager() { return objectManager; }
        @Override public Camera camera() { return camera; }
        @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
        @Override public List<PlayableEntity> sidekicks() { return List.copyOf(sidekicks); }
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
