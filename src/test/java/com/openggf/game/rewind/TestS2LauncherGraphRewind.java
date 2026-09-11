package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.LauncherBallObjectInstance;
import com.openggf.game.sonic2.objects.LauncherSpringObjectInstance;
import com.openggf.game.sonic2.objects.OOZLauncherObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2LauncherGraphRewind {
    private static final ObjectSpawn LAUNCHER_BALL_SPAWN =
            new ObjectSpawn(0x1200, 0x0400, Sonic2ObjectIds.LAUNCHER_BALL, 0x81, 0, false, 0x48);
    private static final ObjectSpawn LAUNCHER_SPRING_SPAWN =
            new ObjectSpawn(0x1300, 0x0410, Sonic2ObjectIds.LAUNCHER_SPRING, 0x80, 0, false, 0x85);
    private static final ObjectSpawn OOZ_LAUNCHER_SPAWN =
            new ObjectSpawn(0x1400, 0x0420, Sonic2ObjectIds.OOZ_LAUNCHER, 1, 0, false, 0x3D);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
        LauncherBallObjectInstance.clearActiveCaptures();
    }

    @Test
    void launcherFamilyUsesRewindRecreatableWithoutExplicitDynamicCodecs() {
        for (Class<?> type : List.of(
                LauncherBallObjectInstance.class,
                LauncherSpringObjectInstance.class,
                OOZLauncherObjectInstance.class,
                OOZLauncherObjectInstance.LauncherFragmentInstance.class)) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getSimpleName() + " must restore through RewindRecreatable");
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                    type.getSimpleName() + " must not rely on an explicit dynamic rewind codec");
        }
    }

    @Test
    void launcherBallPlayerMapsRestoreToCurrentLivePlayerReferences() {
        TestablePlayableSprite capturedMain = player("old-sonic");
        TestablePlayableSprite capturedSidekick = player("old-tails");
        Harness harness = Harness.create(capturedMain, List.of(capturedSidekick));
        ObjectManager objectManager = harness.objectManager();
        LauncherBallObjectInstance source = objectManager.createDynamicObject(
                () -> new LauncherBallObjectInstance(LAUNCHER_BALL_SPAWN, "LauncherBall"));
        seedLauncherBall(source, capturedMain, capturedSidekick);
        ObjectRefId sourceId = objectId(objectManager, source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        TestablePlayableSprite restoredMain = player("new-sonic");
        TestablePlayableSprite restoredSidekick = player("new-tails");
        harness.setPlayers(restoredMain, List.of(restoredSidekick));

        registry.restore(snapshot);

        LauncherBallObjectInstance restored = only(objectManager, LauncherBallObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the launcher ball");
        assertEquals(sourceId, objectId(objectManager, restored),
                "launcher-ball dynamic identity must be preserved");
        assertLauncherBallState(restored, restoredMain, restoredSidekick, capturedMain, capturedSidekick);
    }

    @Test
    void launcherSpringPlayerStateMapRestoresToCurrentLivePlayerReferences() throws Exception {
        TestablePlayableSprite capturedMain = player("old-sonic");
        TestablePlayableSprite capturedSidekick = player("old-tails");
        Harness harness = Harness.create(capturedMain, List.of(capturedSidekick));
        ObjectManager objectManager = harness.objectManager();
        LauncherSpringObjectInstance source = objectManager.createDynamicObject(
                () -> new LauncherSpringObjectInstance(LAUNCHER_SPRING_SPAWN, "LauncherSpring"));
        seedLauncherSpring(source, capturedMain, capturedSidekick);
        ObjectRefId sourceId = objectId(objectManager, source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        TestablePlayableSprite restoredMain = player("new-sonic");
        TestablePlayableSprite restoredSidekick = player("new-tails");
        harness.setPlayers(restoredMain, List.of(restoredSidekick));

        registry.restore(snapshot);

        LauncherSpringObjectInstance restored = only(objectManager, LauncherSpringObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the launcher spring");
        assertEquals(sourceId, objectId(objectManager, restored),
                "launcher-spring dynamic identity must be preserved");
        assertEquals(9, readInt(restored, "compression"));
        assertEquals(2, readInt(restored, "compressionFrameCounter"));
        assertEquals(0x1304, readInt(restored, "currentSpriteX"));
        assertEquals(0x0418, readInt(restored, "currentSpriteY"));
        Map<?, ?> states = readMap(restored, "playerStates");
        assertFalse(states.containsKey(capturedMain), "restored map must not retain stale main-player key");
        assertFalse(states.containsKey(capturedSidekick), "restored map must not retain stale sidekick key");
        assertSpringState(states.get(restoredMain), 1, 7, true);
        assertSpringState(states.get(restoredSidekick), 2, 11, false);
    }

    @Test
    void oozLauncherPlayerStateMapRestoresToCurrentLivePlayerReferences() throws Exception {
        TestablePlayableSprite capturedMain = player("old-sonic");
        TestablePlayableSprite capturedSidekick = player("old-tails");
        Harness harness = Harness.create(capturedMain, List.of(capturedSidekick));
        ObjectManager objectManager = harness.objectManager();
        OOZLauncherObjectInstance source = objectManager.createDynamicObject(
                () -> new OOZLauncherObjectInstance(OOZ_LAUNCHER_SPAWN, "OOZLauncher"));
        seedOozLauncher(source, capturedMain, capturedSidekick);
        ObjectRefId sourceId = objectId(objectManager, source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        TestablePlayableSprite restoredMain = player("new-sonic");
        TestablePlayableSprite restoredSidekick = player("new-tails");
        harness.setPlayers(restoredMain, List.of(restoredSidekick));

        registry.restore(snapshot);

        OOZLauncherObjectInstance restored = only(objectManager, OOZLauncherObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the OOZ launcher");
        assertEquals(sourceId, objectId(objectManager, restored),
                "OOZ launcher dynamic identity must be preserved");
        assertTrue(readBoolean(restored, "broken"));
        assertTrue(readBoolean(restored, "launcherActive"));
        assertFalse(readBoolean(restored, "isVertical"));
        Map<?, ?> states = readMap(restored, "playerStates");
        assertFalse(states.containsKey(capturedMain), "restored map must not retain stale main-player key");
        assertFalse(states.containsKey(capturedSidekick), "restored map must not retain stale sidekick key");
        assertOozState(states.get(restoredMain), 2, 0x0A, -0x0120, true);
        assertOozState(states.get(restoredSidekick), 0, 0x0B, 0x0030, false);
    }

    @Test
    void oozLauncherFragmentRestoresFreshWithCapturedPhysicsAndIdentity() {
        Harness harness = Harness.create(player("sonic"), List.of());
        ObjectManager objectManager = harness.objectManager();
        OOZLauncherObjectInstance.LauncherFragmentInstance source = objectManager.createDynamicObject(
                () -> new OOZLauncherObjectInstance.LauncherFragmentInstance(
                        0x1400, 0x0420, -0x0400, -0x0200, null, null));
        seedFragment(source);
        ObjectRefId sourceId = objectId(objectManager, source);
        Map<String, Integer> sourceScalars = fragmentScalars(source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        OOZLauncherObjectInstance.LauncherFragmentInstance divergent = objectManager.createDynamicObject(
                () -> new OOZLauncherObjectInstance.LauncherFragmentInstance(
                        0x1500, 0x0520, 0x0100, -0x0100, null, null));
        assertFalse(sourceId.equals(objectId(objectManager, divergent)),
                "divergent fragment must not alias the captured identity");

        registry.restore(snapshot);

        OOZLauncherObjectInstance.LauncherFragmentInstance restored =
                only(objectManager, OOZLauncherObjectInstance.LauncherFragmentInstance.class);
        assertNotSame(source, restored, "restore must recreate the removed OOZ launcher fragment");
        assertNotSame(divergent, restored, "restore must drop unrelated post-snapshot fragments");
        assertEquals(sourceId, objectId(objectManager, restored),
                "OOZ launcher fragment dynamic identity must be preserved");
        assertEquals(sourceScalars, fragmentScalars(restored),
                "compact restore must replay captured OOZ fragment physics");
    }

    private static void seedLauncherBall(
            LauncherBallObjectInstance launcher,
            AbstractPlayableSprite main,
            AbstractPlayableSprite sidekick) {
        writeInt(launcher, "mappingFrame", 5);
        writeInt(launcher, "animFrameDuration", 3);
        mapPut(launcher, "playerStates", main, 2);
        mapPut(launcher, "playerStates", sidekick, 4);
        mapPut(launcher, "playerVelocities", main, new int[] {0x1000, 0});
        mapPut(launcher, "playerVelocities", sidekick, new int[] {0, -0x1000});
        mapPut(launcher, "playerCooldowns", sidekick, 6);
    }

    private static void assertLauncherBallState(
            LauncherBallObjectInstance restored,
            AbstractPlayableSprite restoredMain,
            AbstractPlayableSprite restoredSidekick,
            AbstractPlayableSprite oldMain,
            AbstractPlayableSprite oldSidekick) {
        assertEquals(5, readInt(restored, "mappingFrame"));
        assertEquals(3, readInt(restored, "animFrameDuration"));
        Map<?, ?> states = readMap(restored, "playerStates");
        Map<?, ?> velocities = readMap(restored, "playerVelocities");
        Map<?, ?> cooldowns = readMap(restored, "playerCooldowns");
        for (Map<?, ?> map : List.of(states, velocities, cooldowns)) {
            assertFalse(map.containsKey(oldMain), "restored map must not retain stale main-player key");
            assertFalse(map.containsKey(oldSidekick), "restored map must not retain stale sidekick key");
        }
        assertEquals(2, states.get(restoredMain));
        assertEquals(4, states.get(restoredSidekick));
        assertArrayEquals(new int[] {0x1000, 0}, (int[]) velocities.get(restoredMain));
        assertArrayEquals(new int[] {0, -0x1000}, (int[]) velocities.get(restoredSidekick));
        assertEquals(6, cooldowns.get(restoredSidekick));
    }

    private static void seedLauncherSpring(
            LauncherSpringObjectInstance spring,
            AbstractPlayableSprite main,
            AbstractPlayableSprite sidekick) throws Exception {
        writeInt(spring, "compression", 9);
        writeInt(spring, "compressionFrameCounter", 2);
        writeInt(spring, "currentSpriteX", 0x1304);
        writeInt(spring, "currentSpriteY", 0x0418);
        mapPut(spring, "playerStates", main, springState(1, 7, true));
        mapPut(spring, "playerStates", sidekick, springState(2, 11, false));
    }

    private static Object springState(int state, int cooldown, boolean pinballBeforeCapture) throws Exception {
        Class<?> type = Class.forName(LauncherSpringObjectInstance.class.getName() + "$PlayerState");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object value = ctor.newInstance();
        writeInt(value, "state", state);
        writeInt(value, "launchCooldown", cooldown);
        writeBoolean(value, "pinballBeforeCapture", pinballBeforeCapture);
        return value;
    }

    private static void assertSpringState(
            Object value,
            int state,
            int cooldown,
            boolean pinballBeforeCapture) {
        assertNotNull(value, "restored launcher-spring player state must exist");
        assertEquals(state, readInt(value, "state"));
        assertEquals(cooldown, readInt(value, "launchCooldown"));
        assertEquals(pinballBeforeCapture, readBoolean(value, "pinballBeforeCapture"));
    }

    private static void seedOozLauncher(
            OOZLauncherObjectInstance launcher,
            AbstractPlayableSprite main,
            AbstractPlayableSprite sidekick) throws Exception {
        writeBoolean(launcher, "broken", true);
        writeBoolean(launcher, "launcherActive", true);
        writeBoolean(launcher, "isVertical", false);
        mapPut(launcher, "playerStates", main, oozState(2, 0x0A, -0x0120, true));
        mapPut(launcher, "playerStates", sidekick, oozState(0, 0x0B, 0x0030, false));
    }

    private static Object oozState(int launcherState, int savedAnim, int savedYVel, boolean hasSavedState)
            throws Exception {
        Class<?> type = Class.forName(OOZLauncherObjectInstance.class.getName() + "$LauncherPlayerState");
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object value = ctor.newInstance();
        writeInt(value, "launcherState", launcherState);
        writeInt(value, "savedAnim", savedAnim);
        writeInt(value, "savedYVel", savedYVel);
        writeBoolean(value, "hasSavedState", hasSavedState);
        return value;
    }

    private static void assertOozState(Object value, int launcherState, int savedAnim, int savedYVel,
                                       boolean hasSavedState) {
        assertNotNull(value, "restored OOZ launcher player state must exist");
        assertEquals(launcherState, readInt(value, "launcherState"));
        assertEquals(savedAnim, readInt(value, "savedAnim"));
        assertEquals(savedYVel, readInt(value, "savedYVel"));
        assertEquals(hasSavedState, readBoolean(value, "hasSavedState"));
    }

    private static void seedFragment(OOZLauncherObjectInstance.LauncherFragmentInstance fragment) {
        writeInt(fragment, "currentX", 0x1410);
        writeInt(fragment, "currentY", 0x0418);
        writeInt(fragment, "subX", 0x1410 << 8);
        writeInt(fragment, "subY", 0x0418 << 8);
        writeInt(fragment, "velX", -0x0310);
        writeInt(fragment, "velY", -0x0180);
    }

    private static Map<String, Integer> fragmentScalars(
            OOZLauncherObjectInstance.LauncherFragmentInstance fragment) {
        Map<String, Integer> scalars = new LinkedHashMap<>();
        scalars.put("currentX", readInt(fragment, "currentX"));
        scalars.put("currentY", readInt(fragment, "currentY"));
        scalars.put("subX", readInt(fragment, "subX"));
        scalars.put("subY", readInt(fragment, "subY"));
        scalars.put("velX", readInt(fragment, "velX"));
        scalars.put("velY", readInt(fragment, "velY"));
        return scalars;
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

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> readMap(Object target, String fieldName) {
        try {
            Object value = findField(target.getClass(), fieldName).get(target);
            assertInstanceOf(Map.class, value, fieldName + " must be a map");
            return (Map<Object, Object>) value;
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
