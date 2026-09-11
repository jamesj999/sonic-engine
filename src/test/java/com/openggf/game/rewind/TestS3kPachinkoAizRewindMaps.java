package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.schema.CompactFieldCapturer;
import com.openggf.game.sonic3k.objects.AizTransitionFloorObjectInstance;
import com.openggf.game.sonic3k.objects.PachinkoMagnetOrbObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for two identity-keyed maps that silently rode the generic
 * scalar fallback (which cannot capture identity-keyed collections):
 *
 * <ul>
 *   <li>{@link PachinkoMagnetOrbObjectInstance} {@code playerStates} — per-player
 *       capture/orbit state. A mid-orbit rewind restore must bring the captured
 *       player state back, otherwise the orb thinks the player is free while the
 *       player's own restored state keeps it object-controlled/frozen.</li>
 *   <li>{@link AizTransitionFloorObjectInstance} {@code zeroDistanceRejects} —
 *       the per-player fire-refresh reject counter that gates the accepted
 *       first landing; it accumulates across frames, so a rewind restore must
 *       reproduce the count.</li>
 * </ul>
 */
class TestS3kPachinkoAizRewindMaps {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void bothClassesAreCompactSchemaReachableSoTheirIdentityMapsRideKeyframes() {
        assertTrue(GenericRewindEligibility.usesDefaultObjectSubclassCapture(
                        PachinkoMagnetOrbObjectInstance.class),
                "pachinko magnet orb must stay on the default capture path");
        assertTrue(CompactFieldCapturer.supportsDefaultObjectSubclassScalars(
                        PachinkoMagnetOrbObjectInstance.class),
                "pachinko magnet orb must be compact-schema capturable so playerStates rides keyframes");
        assertTrue(GenericRewindEligibility.usesDefaultObjectSubclassCapture(
                        AizTransitionFloorObjectInstance.class),
                "AIZ transition floor must stay on the default capture path");
        assertTrue(CompactFieldCapturer.supportsDefaultObjectSubclassScalars(
                        AizTransitionFloorObjectInstance.class),
                "AIZ transition floor must be compact-schema capturable so zeroDistanceRejects rides keyframes");
    }

    @Test
    void pachinkoMagnetOrbRestoresCapturedPlayerStateAcrossRewind() throws Exception {
        Harness harness = Harness.create(player("sonic"));
        ObjectManager objectManager = harness.objectManager();
        AbstractPlayableSprite player = harness.player();

        ObjectSpawn spawn = new ObjectSpawn(0x1600, 0x0483, 0xEC, 0, 3, false, 0, 81);
        PachinkoMagnetOrbObjectInstance source = objectManager.createDynamicObject(
                () -> new PachinkoMagnetOrbObjectInstance(spawn));

        // Seed a mid-orbit captured player state directly into the identity map.
        Object seededState = newPlayerState(true, 12, -0x10, 0x37);
        seedPlayerState(source, player, seededState);
        ObjectRefId sourceId = objectId(objectManager, source);
        Map<String, Object> expected = playerStateScalars(seededState);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        // Diverge: remove the captured orb and spawn an unrelated one.
        objectManager.removeDynamicObject(source);
        PachinkoMagnetOrbObjectInstance divergent = objectManager.createDynamicObject(
                () -> new PachinkoMagnetOrbObjectInstance(new ObjectSpawn(
                        0x1700, 0x0580, 0xEC, 0, 0, false, 0, 82)));
        assertFalse(sourceId.equals(objectId(objectManager, divergent)),
                "divergent orb must not alias captured identity");

        registry.restore(snapshot);

        PachinkoMagnetOrbObjectInstance restored = only(objectManager, PachinkoMagnetOrbObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the removed orb");
        assertNotSame(divergent, restored, "restore must drop unrelated post-snapshot orb");
        assertEquals(sourceId, objectId(objectManager, restored),
                "orb dynamic identity must be preserved");

        Map<AbstractPlayableSprite, Object> restoredStates = playerStatesMap(restored);
        assertEquals(1, restoredStates.size(), "restored orb must carry exactly the captured player state");
        Object restoredState = restoredStates.get(player);
        assertSame(restoredState, restoredStates.get(player),
                "identity-keyed player state must relink to the live player instance");
        assertTrue(restoredState != null, "captured player's orbit state must survive the rewind restore");
        assertEquals(expected, playerStateScalars(restoredState),
                "restored orbit state must replay every captured field (captured/cooldown/angleA/angleB)");
    }

    @Test
    void aizTransitionFloorRestoresZeroDistanceRejectCounterAcrossRewind() throws Exception {
        Harness harness = Harness.create(player("sonic"));
        ObjectManager objectManager = harness.objectManager();
        AbstractPlayableSprite player = harness.player();

        AizTransitionFloorObjectInstance source = objectManager.createDynamicObject(
                AizTransitionFloorObjectInstance::new);

        // Mid fire-refresh handoff: several rejects have accumulated for this player.
        rejectsMap(source).put(player, 7);
        ObjectRefId sourceId = objectId(objectManager, source);
        RewindRegistry registry = registryFor(objectManager);
        CompositeSnapshot snapshot = registry.capture();

        objectManager.removeDynamicObject(source);
        AizTransitionFloorObjectInstance divergent = objectManager.createDynamicObject(
                AizTransitionFloorObjectInstance::new);
        rejectsMap(divergent).put(player, 19);
        assertFalse(sourceId.equals(objectId(objectManager, divergent)),
                "divergent floor must not alias captured identity");

        registry.restore(snapshot);

        AizTransitionFloorObjectInstance restored = only(objectManager, AizTransitionFloorObjectInstance.class);
        assertNotSame(source, restored, "restore must recreate the removed floor");
        assertNotSame(divergent, restored, "restore must drop unrelated post-snapshot floor");
        assertEquals(sourceId, objectId(objectManager, restored),
                "floor dynamic identity must be preserved");

        Map<AbstractPlayableSprite, Integer> restoredRejects = rejectsMap(restored);
        assertEquals(1, restoredRejects.size(), "restored floor must carry exactly the captured reject entry");
        assertEquals(7, restoredRejects.get(player),
                "restored reject counter must replay the captured cross-frame count, not reset to zero");
    }

    private static Object newPlayerState(boolean captured, int cooldownFrames, int angleA, int angleB)
            throws Exception {
        Class<?> stateType = Class.forName(
                "com.openggf.game.sonic3k.objects.PachinkoMagnetOrbObjectInstance$PlayerState");
        Constructor<?> constructor = stateType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();
        setField(state, "captured", captured);
        setField(state, "cooldownFrames", cooldownFrames);
        setField(state, "angleA", angleA);
        setField(state, "angleB", angleB);
        return state;
    }

    private static Map<String, Object> playerStateScalars(Object state) {
        return Map.of(
                "captured", readValue(state, "captured"),
                "cooldownFrames", readValue(state, "cooldownFrames"),
                "angleA", readValue(state, "angleA"),
                "angleB", readValue(state, "angleB"));
    }

    @SuppressWarnings("unchecked")
    private static void seedPlayerState(PachinkoMagnetOrbObjectInstance orb,
                                        AbstractPlayableSprite player, Object state) {
        ((Map<AbstractPlayableSprite, Object>) readValue(orb, "playerStates")).put(player, state);
    }

    @SuppressWarnings("unchecked")
    private static Map<AbstractPlayableSprite, Object> playerStatesMap(PachinkoMagnetOrbObjectInstance orb) {
        return (Map<AbstractPlayableSprite, Object>) readValue(orb, "playerStates");
    }

    @SuppressWarnings("unchecked")
    private static Map<AbstractPlayableSprite, Integer> rejectsMap(AizTransitionFloorObjectInstance floor) {
        return (Map<AbstractPlayableSprite, Integer>) readValue(floor, "zeroDistanceRejects");
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        return registry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertTrue(id != null, "ObjectManager identity table must register " + object.getClass().getName());
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

    private static void setField(Object target, String fieldName, Object value) {
        try {
            findField(target.getClass(), fieldName).set(target, value);
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
