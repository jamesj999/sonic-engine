package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.objects.RisingPillarObjectInstance;
import com.openggf.game.sonic2.objects.SmashableGroundObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2DebrisFragmentGraphRewind {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void s2DebrisFragmentsUseGenericRecreateWithoutExplicitCodecs() {
        for (Class<?> type : List.of(
                RisingPillarObjectInstance.RisingPillarDebrisInstance.class,
                SmashableGroundObjectInstance.SmashableGroundFragmentInstance.class)) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getSimpleName() + " must restore through RewindRecreatable");
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                    type.getSimpleName() + " must not rely on an explicit dynamic rewind codec");
        }
    }

    @Test
    void risingPillarDebrisRestoresFreshWithCapturedPhysicsAndIdentity() {
        Harness harness = Harness.create(player("sonic"));
        ObjectManager objectManager = harness.objectManager();
        RisingPillarObjectInstance.RisingPillarDebrisInstance source = objectManager.createDynamicObject(
                () -> new RisingPillarObjectInstance.RisingPillarDebrisInstance(
                        0x1800, 0x0500, -0x0100, -0x0200, null, 4));
        seedRisingPillarDebris(source);
        ObjectRefId sourceId = objectId(objectManager, source);
        Map<String, Integer> sourceScalars = risingPillarDebrisScalars(source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        RisingPillarObjectInstance.RisingPillarDebrisInstance divergent = objectManager.createDynamicObject(
                () -> new RisingPillarObjectInstance.RisingPillarDebrisInstance(
                        0x1900, 0x0600, 0x0100, -0x0100, null, 0));
        assertFalse(sourceId.equals(objectId(objectManager, divergent)),
                "divergent rising-pillar fragment must not alias the captured identity");

        registry.restore(snapshot);

        RisingPillarObjectInstance.RisingPillarDebrisInstance restored =
                only(objectManager, RisingPillarObjectInstance.RisingPillarDebrisInstance.class);
        assertNotSame(source, restored, "restore must recreate the removed rising-pillar debris");
        assertNotSame(divergent, restored, "restore must drop unrelated post-snapshot rising-pillar debris");
        assertEquals(sourceId, objectId(objectManager, restored),
                "rising-pillar fragment dynamic identity must be preserved");
        assertEquals(sourceScalars, risingPillarDebrisScalars(restored),
                "compact restore must replay captured rising-pillar debris physics and visual selector");
    }

    @Test
    void smashableGroundFragmentRestoresFreshWithCapturedPhysicsAndIdentity() {
        Harness harness = Harness.create(player("sonic"));
        ObjectManager objectManager = harness.objectManager();
        SmashableGroundObjectInstance.SmashableGroundFragmentInstance source = objectManager.createDynamicObject(
                () -> new SmashableGroundObjectInstance.SmashableGroundFragmentInstance(
                        0x1A00, 0x0580, -0x0100, -0x0800, null, null));
        seedSmashableGroundFragment(source);
        ObjectRefId sourceId = objectId(objectManager, source);
        Map<String, Integer> sourceScalars = smashableGroundScalars(source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        SmashableGroundObjectInstance.SmashableGroundFragmentInstance divergent = objectManager.createDynamicObject(
                () -> new SmashableGroundObjectInstance.SmashableGroundFragmentInstance(
                        0x1B00, 0x0680, 0x0100, -0x0400, null, null));
        assertFalse(sourceId.equals(objectId(objectManager, divergent)),
                "divergent smashable-ground fragment must not alias the captured identity");

        registry.restore(snapshot);

        SmashableGroundObjectInstance.SmashableGroundFragmentInstance restored =
                only(objectManager, SmashableGroundObjectInstance.SmashableGroundFragmentInstance.class);
        assertNotSame(source, restored, "restore must recreate the removed smashable-ground fragment");
        assertNotSame(divergent, restored, "restore must drop unrelated post-snapshot smashable-ground fragments");
        assertEquals(sourceId, objectId(objectManager, restored),
                "smashable-ground fragment dynamic identity must be preserved");
        assertEquals(sourceScalars, smashableGroundScalars(restored),
                "compact restore must replay captured smashable-ground fragment physics and visual selector");
    }

    private static void seedRisingPillarDebris(
            RisingPillarObjectInstance.RisingPillarDebrisInstance fragment) {
        writeInt(fragment, "currentX", 0x1810);
        writeInt(fragment, "currentY", 0x0518);
        writeInt(fragment, "subX", 0x1810 << 8);
        writeInt(fragment, "subY", 0x0518 << 8);
        writeInt(fragment, "velX", -0x0120);
        writeInt(fragment, "velY", -0x0180);
        writeInt(fragment, "delay", 3);
        writeInt(fragment, "mappingFrame", 9);
        writeInt(fragment, "pieceIndex", 4);
    }

    private static Map<String, Integer> risingPillarDebrisScalars(
            RisingPillarObjectInstance.RisingPillarDebrisInstance fragment) {
        return scalars(fragment,
                "currentX", "currentY", "subX", "subY", "velX", "velY", "delay",
                "mappingFrame", "pieceIndex");
    }

    private static void seedSmashableGroundFragment(
            SmashableGroundObjectInstance.SmashableGroundFragmentInstance fragment) {
        writeInt(fragment, "currentX", 0x1A14);
        writeInt(fragment, "currentY", 0x0590);
        writeInt(fragment, "subX", 0x1A14 << 8);
        writeInt(fragment, "subY", 0x0590 << 8);
        writeInt(fragment, "velX", 0x00E0);
        writeInt(fragment, "velY", -0x0700);
        writeInt(fragment, "frameIndex", 2);
        writeInt(fragment, "pieceIndex", 1);
    }

    private static Map<String, Integer> smashableGroundScalars(
            SmashableGroundObjectInstance.SmashableGroundFragmentInstance fragment) {
        return scalars(fragment,
                "currentX", "currentY", "subX", "subY", "velX", "velY",
                "frameIndex", "pieceIndex");
    }

    private static Map<String, Integer> scalars(Object target, String... fields) {
        Map<String, Integer> values = new LinkedHashMap<>();
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

    private static void writeInt(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
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
