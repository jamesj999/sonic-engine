package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindFixS1Batch9Codecs {

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
                    Sonic1ResultsScreenObjectInstance.class,
                    () -> {
                        Sonic1ResultsScreenObjectInstance results =
                                new Sonic1ResultsScreenObjectInstance(30, 7, 2);
                        results.setSpecialStageAfter(true);
                        writeInt(results, "state", 2);
                        writeInt(results, "stateTimer", 11);
                        writeInt(results, "totalFrames", 23);
                        return results;
                    },
                    List.of("timeBonus", "ringBonus", "elapsedTimeSeconds", "ringCount",
                            "actNumber", "specialStageAfter", "state", "stateTimer",
                            "totalFrames")),
            new Candidate(
                    Sonic1StomperDoorObjectInstance.class,
                    () -> {
                        Sonic1StomperDoorObjectInstance door =
                                new Sonic1StomperDoorObjectInstance(
                                        new ObjectSpawn(0x400, 0x180, 0x6B, 0x10, 0, false, 0),
                                        Sonic1Constants.ZONE_SBZ);
                        writeInt(door, "x", 0x3E0);
                        writeInt(door, "y", 0x190);
                        writeInt(door, "origX", 0x410);
                        writeInt(door, "moveType", 4);
                        writeInt(door, "currentOffset", 0x20);
                        writeInt(door, "timer", 17);
                        writeBoolean(door, "active", true);
                        writeBoolean(door, "firstUpdate", false);
                        return door;
                    },
                    List.of("x", "y", "origX", "moveType", "currentOffset",
                            "timer", "active", "firstUpdate")));

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
        Sonic1StomperDoorObjectInstance.resetSbz3Flag();
    }

    @Test
    void batch9S1SessionDynamicsImplementRewindRecreatable() {
        for (Candidate candidate : CANDIDATES) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(candidate.type()),
                    candidate.type().getName()
                            + " must implement RewindRecreatable before its explicit codec is removed");
        }
    }

    @Test
    void batch9S1SessionDynamicsHaveNoExplicitCodec() {
        for (Candidate candidate : CANDIDATES) {
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(candidate.type().getName()),
                    candidate.type().getName()
                            + " must restore through genericRecreate, not an explicit S1 dynamic codec");
        }
    }

    @Test
    void sessionRestoreKeepsOneLiveObjectAndRestoresScalarState() {
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
            corruptScalars(source, candidate.scalarPaths());

            rewindRegistry.restore(snapshot);

            List<AbstractObjectInstance> restored = liveObjectsOfType(objectManager, candidate.type());
            assertEquals(1, restored.size(),
                    candidate.type().getSimpleName()
                            + " restore must keep exactly one live dynamic object");
            assertEquals(expected, readScalars(restored.get(0), candidate.scalarPaths()),
                    candidate.type().getSimpleName()
                            + " restore must reapply captured scalar state");
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

    private static void corruptScalars(Object target, List<String> paths) {
        for (String path : paths) {
            if (path.contains(".")) {
                continue;
            }
            Field field = findFieldUnchecked(target.getClass(), path);
            field.setAccessible(true);
            try {
                if (field.getType() == boolean.class) {
                    field.setBoolean(target, !field.getBoolean(target));
                } else if (field.getType() == int.class) {
                    field.setInt(target, -0x1234);
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Unable to corrupt " + path + " on "
                        + target.getClass().getName(), e);
            }
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

    private static void writeBoolean(Object target, String fieldName, boolean value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on "
                    + target.getClass().getName(), e);
        }
    }

    private static Field findFieldUnchecked(Class<?> type, String fieldName) {
        try {
            return findField(type, fieldName);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("Unable to find " + fieldName + " on " + type.getName(), e);
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

            @Override
            public int romZoneId() {
                return Sonic1Constants.ZONE_SBZ;
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
                TestSessionOutputPaths.diagnostics("rewind").resolve("rewind-s1-batch9-config"));
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
