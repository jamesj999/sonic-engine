package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic1.objects.badniks.Sonic1BombShrapnelInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1BuzzBomberMissileDissolveInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1BuzzBomberMissileInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1CannonballInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1CrabmeatProjectileInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1NewtronMissileInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindFixS1Batch8Codecs {

    private static final SonicConfigurationService DEFAULT_CONFIGURATION =
            createDefaultConfiguration();
    private static final ObjectRenderManager INERT_RENDER_MANAGER =
            new ObjectRenderManager(new InertObjectArtProvider());

    private record Candidate(
            Class<? extends AbstractObjectInstance> type,
            Supplier<? extends AbstractObjectInstance> factory,
            List<String> scalarPaths) {
    }

    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate(
                    Sonic1BombShrapnelInstance.class,
                    () -> new Sonic1BombShrapnelInstance(0x180, 0x120, 0x140, -0x280),
                    List.of("currentX", "currentY", "xVelocity", "yVelocity",
                            "animTickCounter", "motionState.xVel", "motionState.yVel")),
            new Candidate(
                    Sonic1CannonballInstance.class,
                    () -> new Sonic1CannonballInstance(0x1A0, 0x130, -0x100, 2),
                    List.of("currentX", "currentY", "xVelocity", "yVelocity",
                            "explosionTimer", "animTimer", "currentFrame", "motion.xVel")),
            new Candidate(
                    Sonic1NewtronMissileInstance.class,
                    () -> new Sonic1NewtronMissileInstance(0x1C0, 0x140, 0x200, true),
                    List.of("currentX", "currentY", "facingLeft", "animTimer", "animFrame",
                            "motionState.xVel")),
            new Candidate(
                    Sonic1BuzzBomberMissileInstance.class,
                    () -> new Sonic1BuzzBomberMissileInstance(
                            0x1E0, 0x150, -0x200, 0x200, true, 7),
                    List.of("currentX", "currentY", "xVelocity", "yVelocity",
                            "motionState.xVel", "motionState.yVel", "facingLeft",
                            "parentSlotIndex", "phase", "flareTimer", "animTimer", "animFrame",
                            "renderedFrame", "collisionEnabled")),
            new Candidate(
                    Sonic1BuzzBomberMissileDissolveInstance.class,
                    () -> new Sonic1BuzzBomberMissileDissolveInstance(0x200, 0x160),
                    List.of("currentX", "currentY", "animFrame", "frameTimer")),
            new Candidate(
                    Sonic1CrabmeatProjectileInstance.class,
                    () -> new Sonic1CrabmeatProjectileInstance(
                            0x220, 0x170, 0x100, -0x400, null),
                    List.of("currentX", "currentY", "motionState.xVel", "motionState.yVel",
                            "animTimer", "renderedFrame", "launchYVelocity", "initialized",
                            "touchCollisionActive", "deferSameFrameUpdateAfterSpawn")));

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void batch8S1ProjectileDynamicsImplementRewindRecreatable() {
        for (Candidate candidate : CANDIDATES) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(candidate.type()),
                    candidate.type().getName()
                            + " must implement RewindRecreatable before its explicit codec is removed");
        }
    }

    @Test
    void batch8S1ProjectileDynamicsHaveNoExplicitCodec() {
        for (Candidate candidate : CANDIDATES) {
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(candidate.type().getName()),
                    candidate.type().getName()
                            + " must restore through genericRecreate, not an explicit S1 dynamic codec");
        }
    }

    @Test
    void sessionRestoreKeepsOneLiveProjectileAndRestoresScalarState() {
        for (Candidate candidate : CANDIDATES) {
            ObjectManager[] holder = new ObjectManager[1];
            ObjectServices services = services(holder);
            ObjectManager objectManager = new ObjectManager(
                    List.of(), new Sonic1ObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), mockCamera(), services);
            holder[0] = objectManager;
            objectManager.reset(0);

            AbstractObjectInstance source =
                    ObjectConstructionContext.construct(services, candidate.factory());
            objectManager.addDynamicObject(source);
            Map<String, Object> expected = readScalars(source, candidate.scalarPaths());

            RewindRegistry rewindRegistry = new RewindRegistry();
            rewindRegistry.register(objectManager.rewindSnapshottable());
            CompositeSnapshot snapshot = rewindRegistry.capture();

            source.setDestroyed(true);
            writeInt(source, "currentX", -1);
            writeInt(source, "currentY", -1);

            rewindRegistry.restore(snapshot);

            List<AbstractObjectInstance> restored = liveObjectsOfType(objectManager, candidate.type());
            assertEquals(1, restored.size(),
                    candidate.type().getSimpleName()
                            + " restore must keep exactly one live dynamic object");
            assertEquals(expected, readScalars(restored.get(0), candidate.scalarPaths()),
                    candidate.type().getSimpleName()
                            + " restore must reapply captured scalar/helper state");
        }
    }

    private static List<AbstractObjectInstance> liveObjectsOfType(
            ObjectManager objectManager,
            Class<? extends AbstractObjectInstance> type) {
        return objectManager.getActiveObjects().stream()
                .filter(type::isInstance)
                .filter(object -> !object.isDestroyed())
                .map(object -> (AbstractObjectInstance) object)
                .toList();
    }

    private static Map<String, Object> readScalars(Object target, List<String> paths) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String path : paths) {
            values.put(path, readPath(target, path));
        }
        return values;
    }

    private static Object readPath(Object target, String path) {
        Object current = target;
        for (String segment : path.split("\\.")) {
            current = readField(current, segment);
        }
        return current;
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from "
                    + target.getClass().getName(), e);
        }
    }

    private static void writeInt(Object target, String fieldName, int value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on "
                    + target.getClass().getName(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                return cursor.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static ObjectServices services(ObjectManager[] holder) {
        Camera camera = mockCamera();
        return new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public SonicConfigurationService configuration() {
                return DEFAULT_CONFIGURATION;
            }

            @Override
            public ObjectRenderManager renderManager() {
                return INERT_RENDER_MANAGER;
            }
        };
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }

    private static SonicConfigurationService createDefaultConfiguration() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(
                TestSessionOutputPaths.diagnostics("rewind").resolve("rewind-s1-batch8-config"));
        config.resetToDefaults();
        return config;
    }

    private static final class InertObjectArtProvider implements ObjectArtProvider {
        @Override public void loadArtForZone(int zoneIndex) {}
        @Override public PatternSpriteRenderer getRenderer(String key) { return null; }
        @Override public ObjectSpriteSheet getSheet(String key) { return null; }
        @Override public SpriteAnimationSet getAnimations(String key) { return null; }
        @Override public int getZoneData(String key, int zoneIndex) { return -1; }
        @Override public Pattern[] getHudDigitPatterns() { return new Pattern[0]; }
        @Override public Pattern[] getHudTextPatterns() { return new Pattern[0]; }
        @Override public Pattern[] getHudLivesPatterns() { return new Pattern[0]; }
        @Override public Pattern[] getHudLivesNumbers() { return new Pattern[0]; }
        @Override public List<String> getRendererKeys() { return List.of(); }
        @Override public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }
        @Override public boolean isReady() { return true; }
    }
}
