package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.objects.MGZTopLauncherObjectInstance;
import com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance;
import com.openggf.game.sonic3k.objects.PachinkoItemOrbObjectInstance;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kMgzMechanismGraphRewind {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void mgzMechanismClassesUseGenericRecreateWithoutExplicitCodecs() {
        for (Class<?> type : List.of(
                MGZTopLauncherObjectInstance.class,
                MGZTopPlatformObjectInstance.class,
                PachinkoItemOrbObjectInstance.class)) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getSimpleName() + " must restore through RewindRecreatable");
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                    type.getSimpleName() + " must not rely on an explicit dynamic rewind codec");
        }
    }

    @Test
    void mgzTopLauncherRestoresChildGraphWithoutDropsDoublesOrStaleReference() {
        Harness harness = Harness.create(player("sonic"));
        ObjectManager objectManager = harness.objectManager();
        ObjectSpawn spawn = new ObjectSpawn(0x1800, 0x0500, 0x5C, 0, 1, false, 0, 42);
        MGZTopLauncherObjectInstance source = objectManager.createDynamicObject(
                () -> new MGZTopLauncherObjectInstance(spawn));
        source.update(0, harness.player());
        MGZTopPlatformObjectInstance sourceChild =
                readObject(source, "child", MGZTopPlatformObjectInstance.class);
        assertNotNull(sourceChild, "launcher update must spawn its passive platform child");
        seedLauncher(source);
        seedPlatform(sourceChild);
        ObjectRefId sourceId = objectId(objectManager, source);
        ObjectRefId sourceChildId = objectId(objectManager, sourceChild);
        Map<String, Object> sourceScalars = launcherScalars(source);
        Map<String, Object> sourceChildScalars = platformScalars(sourceChild);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        objectManager.removeDynamicObject(sourceChild);
        MGZTopLauncherObjectInstance divergent = objectManager.createDynamicObject(
                () -> new MGZTopLauncherObjectInstance(new ObjectSpawn(
                        0x1900, 0x0600, 0x5C, 0, 0, false, 0, 43)));
        divergent.update(1, harness.player());
        MGZTopPlatformObjectInstance divergentChild =
                readObject(divergent, "child", MGZTopPlatformObjectInstance.class);
        assertFalse(sourceId.equals(objectId(objectManager, divergent)),
                "divergent launcher must not alias captured identity");

        registry.restore(snapshot);

        MGZTopLauncherObjectInstance restoredLauncher =
                only(objectManager, MGZTopLauncherObjectInstance.class);
        MGZTopPlatformObjectInstance restoredPlatform =
                only(objectManager, MGZTopPlatformObjectInstance.class);
        assertNotSame(source, restoredLauncher, "restore must recreate the removed launcher");
        assertNotSame(sourceChild, restoredPlatform, "restore must recreate the removed platform child");
        assertNotSame(divergent, restoredLauncher, "restore must drop unrelated post-snapshot launcher");
        assertNotSame(divergentChild, restoredPlatform, "restore must drop unrelated post-snapshot platform");
        assertEquals(sourceId, objectId(objectManager, restoredLauncher),
                "launcher dynamic identity must be preserved");
        assertEquals(sourceChildId, objectId(objectManager, restoredPlatform),
                "platform child dynamic identity must be preserved");
        assertSame(restoredPlatform, readObject(restoredLauncher, "child", MGZTopPlatformObjectInstance.class),
                "launcher child field must relink to the restored platform, not a stale pre-restore instance");
        assertEquals(sourceScalars, launcherScalars(restoredLauncher),
                "compact restore must replay captured launcher state");
        assertEquals(sourceChildScalars, platformScalars(restoredPlatform),
                "compact restore must replay captured platform state");
    }

    @Test
    void pachinkoItemOrbRestoresFreshBeforeRewardConversion() {
        Harness harness = Harness.create(player("sonic"));
        ObjectManager objectManager = harness.objectManager();
        ObjectSpawn spawn = new ObjectSpawn(0x1600, 0x0483, 0xED, 0, 3, false, 0, 71);
        PachinkoItemOrbObjectInstance source = objectManager.createDynamicObject(
                () -> new PachinkoItemOrbObjectInstance(spawn));
        seedPachinkoOrb(source);
        ObjectRefId sourceId = objectId(objectManager, source);
        Map<String, Object> sourceScalars = pachinkoScalars(source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        PachinkoItemOrbObjectInstance divergent = objectManager.createDynamicObject(
                () -> new PachinkoItemOrbObjectInstance(new ObjectSpawn(
                        0x1700, 0x0580, 0xED, 0, 0, false, 0, 72)));
        assertFalse(sourceId.equals(objectId(objectManager, divergent)),
                "divergent orb must not alias captured identity");

        registry.restore(snapshot);

        PachinkoItemOrbObjectInstance restored = only(objectManager, PachinkoItemOrbObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the removed pachinko orb");
        assertNotSame(divergent, restored, "restore must drop unrelated post-snapshot orb");
        assertEquals(sourceId, objectId(objectManager, restored),
                "pachinko orb dynamic identity must be preserved");
        assertEquals(sourceScalars, pachinkoScalars(restored),
                "compact restore must replay captured pachinko orb state");
    }

    private static void seedLauncher(MGZTopLauncherObjectInstance launcher) {
        writeBoolean(launcher, "hFlip", true);
        writeInt(launcher, "posX", 0x1810);
        writeInt(launcher, "posY", 0x0520);
        writeInt(launcher, "remainingDrop", 9);
        writeInt(launcher, "launchVelocity", -0x0C00);
        writeBoolean(launcher, "childSpawned", true);
        writeBoolean(launcher, "descending", true);
    }

    private static void seedPlatform(MGZTopPlatformObjectInstance platform) {
        writeInt(platform, "currentSubtype", 1);
        writeBoolean(platform, "bodyDriven", false);
        writeInt(platform, "posX", 0x1810);
        writeInt(platform, "posY", 0x0520);
        writeInt(platform, "homeX", 0x1810);
        writeInt(platform, "homeY", 0x0520);
        writeInt(platform, "timer", 0x24);
        writeBoolean(platform, "airborne", false);
        writeBoolean(platform, "releasedFlight", false);
    }

    private static void seedPachinkoOrb(PachinkoItemOrbObjectInstance orb) {
        writeInt(orb, "animationVIntRunCount", 5);
        writeBoolean(orb, "touchedLastResolvedFrame", false);
        writeBoolean(orb, "armed", true);
    }

    private static Map<String, Object> launcherScalars(MGZTopLauncherObjectInstance launcher) {
        return scalars(launcher,
                "hFlip", "posX", "posY", "remainingDrop", "launchVelocity",
                "childSpawned", "descending");
    }

    private static Map<String, Object> platformScalars(MGZTopPlatformObjectInstance platform) {
        return scalars(platform,
                "currentSubtype", "bodyDriven", "posX", "posY", "homeX", "homeY",
                "timer", "airborne", "releasedFlight");
    }

    private static Map<String, Object> pachinkoScalars(PachinkoItemOrbObjectInstance orb) {
        return scalars(orb, "animationVIntRunCount", "touchedLastResolvedFrame", "armed");
    }

    private static Map<String, Object> scalars(Object target, String... fields) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String field : fields) {
            values.put(field, readValue(target, field));
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

    private static Object readValue(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static <T> T readObject(Object target, String fieldName, Class<T> fieldType) {
        Object value = readValue(target, fieldName);
        return fieldType.cast(value);
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
        private final AbstractPlayableSprite player;

        private Harness(ObjectManager objectManager, AbstractPlayableSprite player) {
            this.objectManager = objectManager;
            this.player = player;
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
            return new Harness(objectManager, main);
        }

        ObjectManager objectManager() {
            return objectManager;
        }

        AbstractPlayableSprite player() {
            return player;
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
