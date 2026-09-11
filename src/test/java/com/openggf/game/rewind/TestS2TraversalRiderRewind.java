package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic2.objects.CPZSpinTubeObjectInstance;
import com.openggf.game.sonic2.objects.FlipperObjectInstance;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.game.sonic2.objects.SpeedLauncherObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prove-first regression coverage for the S2 CPZ-traversal rewind disposition
 * audit. Each object holds per-player traversal/lock state in an identity-keyed
 * collection that is NOT re-derived from live contact while the object is
 * driving the player (spin-tube object control, speed-launcher catapult/launch,
 * flipper movement lock). Before the CAPTURED policy entries these collections
 * were silently dropped on the generic scalar fallback, so a rewind restore
 * recreated the object with an empty map and stranded/mis-handled the player.
 *
 * <p>Each test seeds the collection with the identity-table-registered player,
 * captures through the real rewind registry, forces the recreate path, and
 * asserts the recreated object still holds the per-player state. Without the
 * policy the restored collection is empty and these fail.
 */
class TestS2TraversalRiderRewind {

    private TestablePlayableSprite player;

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x800, 0x500, 0);
        player = new TestablePlayableSprite("sonic", (short) 0x0300, (short) 0x0200);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void cpzSpinTubeCharacterStateSurvivesRewind() throws Exception {
        ObjectManager objectManager = harness();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        CPZSpinTubeObjectInstance source = objectManager.createDynamicObject(
                () -> new CPZSpinTubeObjectInstance(new ObjectSpawn(
                        0x0300, 0x0200, 0x1E, 0, 0, false, 20), "CPZSpinTube"));

        java.lang.reflect.Constructor<?> stateCtor = Class.forName(
                        "com.openggf.game.sonic2.objects.CPZSpinTubeObjectInstance$CharacterState")
                .getDeclaredConstructor();
        stateCtor.setAccessible(true);
        Object characterState = stateCtor.newInstance();
        setIntField(characterState, "state", 4);
        setIntField(characterState, "pathIndex", 3);
        setIntField(characterState, "duration", 10);
        mapPut(source, "characterStates", player, characterState);

        ObjectRefId id = objectId(objectManager, source);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();
        recreatePath(objectManager, source, () -> new CPZSpinTubeObjectInstance(new ObjectSpawn(
                0x0100, 0x0100, 0x1E, 0, 0, false, 21), "CPZSpinTube"), snapshot);

        CPZSpinTubeObjectInstance restored =
                objectById(objectManager, CPZSpinTubeObjectInstance.class, id);
        assertNotSame(source, restored, "restore must recreate the spin tube");
        Object restoredState = mapGet(restored, "characterStates", player);
        assertNotNull(restoredState,
                "restored spin tube must still hold the player's traversal slot (else it strands mid-tube)");
        assertEquals(4, readIntField(restoredState, "state"), "spin-tube traversal state must survive rewind");
        assertEquals(3, readIntField(restoredState, "pathIndex"), "spin-tube path index must survive rewind");
        assertEquals(10, readIntField(restoredState, "duration"), "spin-tube segment duration must survive rewind");
    }

    @Test
    void speedLauncherRidersSurviveRewind() {
        ObjectManager objectManager = harness();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        SpeedLauncherObjectInstance source = objectManager.createDynamicObject(
                () -> new SpeedLauncherObjectInstance(new ObjectSpawn(
                        0x0300, 0x0200, 0x40, 0, 0, false, 22), "SpeedLauncher"));
        addToCollection(source, "standingPlayers", player);
        addToCollection(source, "accelerationRiders", player);

        ObjectRefId id = objectId(objectManager, source);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();
        recreatePath(objectManager, source, () -> new SpeedLauncherObjectInstance(new ObjectSpawn(
                0x0100, 0x0100, 0x40, 0, 0, false, 23), "SpeedLauncher"), snapshot);

        SpeedLauncherObjectInstance restored =
                objectById(objectManager, SpeedLauncherObjectInstance.class, id);
        assertNotSame(source, restored, "restore must recreate the speed launcher");
        assertTrue(collectionContains(restored, "standingPlayers", player),
                "restored speed launcher must keep its standing rider so the launch still fires");
        assertTrue(collectionContains(restored, "accelerationRiders", player),
                "restored speed launcher must keep its acceleration rider (not re-derived from contact)");
    }

    @Test
    void flipperLockedPinballModeSurvivesRewind() {
        ObjectManager objectManager = harness();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        FlipperObjectInstance source = objectManager.createDynamicObject(
                () -> new FlipperObjectInstance(new ObjectSpawn(
                        0x0300, 0x0200, 0x1F, 0, 0, false, 24), "Flipper"));
        mapPut(source, "lockedPlayerPrevSuppressed", player, Boolean.TRUE);
        mapPut(source, "lockedPlayerPrevPinballMode", player, Boolean.TRUE);
        mapPut(source, "playerFlipperState", player, 1);

        ObjectRefId id = objectId(objectManager, source);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();
        recreatePath(objectManager, source, () -> new FlipperObjectInstance(new ObjectSpawn(
                0x0100, 0x0100, 0x1F, 0, 0, false, 25), "Flipper"), snapshot);

        FlipperObjectInstance restored = objectById(objectManager, FlipperObjectInstance.class, id);
        assertNotSame(source, restored, "restore must recreate the flipper");
        assertEquals(Boolean.TRUE, mapGet(restored, "lockedPlayerPrevPinballMode", player),
                "restored flipper must remember the locked player's pre-lock pinball mode (else release clears it)");
        assertEquals(Boolean.TRUE, mapGet(restored, "lockedPlayerPrevSuppressed", player),
                "restored flipper must remember the locked player's suppression flag");
        assertEquals(1, mapGet(restored, "playerFlipperState", player),
                "restored flipper must remember the locked player's flipper state");
    }

    // --- harness ---------------------------------------------------------

    private ObjectManager harness() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = playerCamera();
        ObjectServices services = new StubObjectServices() {
            @Override public ObjectManager objectManager() { return holder[0]; }
            @Override public Camera camera() { return camera; }
            @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
        };
        ObjectManager objectManager = new ObjectManager(
                List.of(), new Sonic2ObjectRegistry(), 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = objectManager;
        objectManager.reset(0);
        return objectManager;
    }

    private <T extends ObjectInstance> void recreatePath(
            ObjectManager objectManager, T source, java.util.function.Supplier<T> divergent,
            CompositeSnapshot snapshot) {
        objectManager.removeDynamicObject(source);
        objectManager.createDynamicObject(divergent);
        registryFor(objectManager).restore(snapshot);
    }

    private Camera playerCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x500; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
            @Override public AbstractPlayableSprite getFocusedSprite() { return player; }
        };
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(o -> ((AbstractObjectInstance) o).getSlotIndex()))
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    @SuppressWarnings("unchecked")
    private static void addToCollection(Object target, String fieldName, PlayableEntity value) {
        ((Collection<PlayableEntity>) readField(target, fieldName)).add(value);
    }

    @SuppressWarnings("unchecked")
    private static boolean collectionContains(Object target, String fieldName, PlayableEntity value) {
        return ((Collection<PlayableEntity>) readField(target, fieldName)).contains(value);
    }

    @SuppressWarnings("unchecked")
    private static void mapPut(Object target, String fieldName, Object key, Object value) {
        ((Map<Object, Object>) readField(target, fieldName)).put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static Object mapGet(Object target, String fieldName, Object key) {
        return ((Map<Object, Object>) readField(target, fieldName)).get(key);
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return field(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            field(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Object readField(Object target, String fieldName) {
        try {
            return field(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Field field(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
