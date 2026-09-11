package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.AizCollapsingLogBridgeObjectInstance;
import com.openggf.game.sonic3k.objects.CollapsingBridgeObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prove-first regression coverage for the AIZ collapse-rider rewind disposition
 * audit. Both bridges hold per-player rider state in identity-keyed collections
 * that stop being re-derivable once the collapse sequence has begun (the bridge
 * is no longer a solid surface, so live contact never re-adds a rider). Before
 * the CAPTURED policy entries these collections were silently dropped on the
 * generic scalar fallback, so a mid-collapse rewind restore recreated the bridge
 * with an empty rider set and stranded standing players.
 *
 * <p>Each test seeds the rider collection with the identity-table-registered
 * player, captures through the real rewind registry, forces the recreate path,
 * and asserts the recreated bridge still holds the rider. Without the policy the
 * restored collection is empty and these fail.
 */
class TestS3kAizBridgeRiderRewind {

    private TestablePlayableSprite player;

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x800, 0x500, 0);
        player = new TestablePlayableSprite("sonic", (short) 0x0300, (short) 0x0200);
    }

    @AfterEach
    void tearDown() {
        AizCollapsingLogBridgeObjectInstance.setDrawBridgeBurnActive(false);
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void collapsingLogBridgeStandingAndEjectedRidersSurviveRewind() {
        ObjectManager objectManager = harness();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        AizCollapsingLogBridgeObjectInstance source = objectManager.createDynamicObject(
                () -> new AizCollapsingLogBridgeObjectInstance(new ObjectSpawn(
                        0x0300, 0x0200, Sonic3kObjectIds.AIZ_COLLAPSING_LOG_BRIDGE, 0x85, 1, true, 17)));
        addToCollection(source, "standingPlayers", player);
        addToCollection(source, "ejectedPlayers", player);

        ObjectRefId id = objectId(objectManager, source);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(source);
        objectManager.createDynamicObject(() -> new AizCollapsingLogBridgeObjectInstance(new ObjectSpawn(
                0x0100, 0x0100, Sonic3kObjectIds.AIZ_COLLAPSING_LOG_BRIDGE, 0, 0, false, 18)));
        registryFor(objectManager).restore(snapshot);

        AizCollapsingLogBridgeObjectInstance restored =
                objectById(objectManager, AizCollapsingLogBridgeObjectInstance.class, id);
        assertNotSame(source, restored, "restore must recreate the collapsing log bridge");
        assertTrue(collectionContains(restored, "standingPlayers", player),
                "restored collapsing log bridge must still hold the standing rider (else it never gets knocked off)");
        assertTrue(collectionContains(restored, "ejectedPlayers", player),
                "restored collapsing log bridge must remember the ejected rider (else it can wrongly re-stand)");
    }

    @Test
    void collapsingBridgeWaveRidersSurviveRewind() {
        ObjectManager objectManager = harness();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);

        CollapsingBridgeObjectInstance source = objectManager.createDynamicObject(
                () -> new CollapsingBridgeObjectInstance(new ObjectSpawn(
                        0x0300, 0x0200, Sonic3kObjectIds.COLLAPSING_BRIDGE, 0x20, 1, false, 40)));
        addToCollection(source, "collapseWaveRiders", player);

        ObjectRefId id = objectId(objectManager, source);
        CompositeSnapshot snapshot = registryFor(objectManager).capture();

        objectManager.removeDynamicObject(source);
        objectManager.createDynamicObject(() -> new CollapsingBridgeObjectInstance(new ObjectSpawn(
                0x0100, 0x0100, Sonic3kObjectIds.COLLAPSING_BRIDGE, 0x20, 1, false, 41)));
        registryFor(objectManager).restore(snapshot);

        CollapsingBridgeObjectInstance restored =
                objectById(objectManager, CollapsingBridgeObjectInstance.class, id);
        assertNotSame(source, restored, "restore must recreate the collapsing bridge");
        assertTrue(collectionContains(restored, "collapseWaveRiders", player),
                "restored collapsing bridge must still carry the seeded wave rider (else it drops early)");
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
                List.of(), new Sonic3kObjectRegistry(), 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = objectManager;
        objectManager.reset(0);
        return objectManager;
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

    private static Object readField(Object target, String fieldName) {
        try {
            Class<?> current = target.getClass();
            while (current != null) {
                try {
                    Field field = current.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                }
            }
            throw new NoSuchFieldException(fieldName);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }
}
