package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1SpikedBallChainGraphRewind {
    private static final String CHAIN_CHILD_FQN =
            "com.openggf.game.sonic1.objects.Sonic1SpikedBallChainObjectInstance$ChainChild";
    private static final int ORIGIN_X = 0x0180;
    private static final int ORIGIN_Y = 0x0120;
    private static final int CHAIN_SUBTYPE = 0x03;

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void s1SpikedBallChainGraphRestoresChildrenWithoutDropsDoublesOrStaleSlots() {
        Class<?> childClass = loadClass(CHAIN_CHILD_FQN);
        Harness harness = Harness.create(new ObjectSpawn(
                ORIGIN_X, ORIGIN_Y, Sonic1ObjectIds.SPIKED_BALL_CHAIN, CHAIN_SUBTYPE,
                0, false, 0, 57));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        objectManager.update(0, null, null, 0, false);

        Sonic1SpikedBallChainObjectInstance beforeParent = liveParent(objectManager);
        List<ObjectInstance> beforeChildren = liveChildren(objectManager, childClass);
        assertEquals(3, beforeChildren.size(), "test fixture must spawn three chain children");
        Map<Integer, ObjectInstance> beforeByIndex = byChildIndex(beforeChildren);
        Map<Integer, ObjectRefId> childIds = beforeByIndex.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> objectId(objectManager, entry.getValue())));
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        beforeChildren.forEach(objectManager::removeDynamicObject);

        rewindRegistry.restore(snapshot);

        Sonic1SpikedBallChainObjectInstance restoredParent = liveParent(objectManager);
        List<ObjectInstance> restoredChildren = liveChildren(objectManager, childClass);
        assertNotSame(beforeParent, restoredParent, "restore must recreate the placed chain parent");
        assertEquals(3, restoredChildren.size(), "restore must keep exactly the captured chain children");

        Object[] parentSlots = readChildren(restoredParent);
        assertEquals(3, parentSlots.length, "restored parent must keep its three child slots");
        for (Map.Entry<Integer, ObjectRefId> entry : childIds.entrySet()) {
            int childIndex = entry.getKey();
            ObjectInstance restoredChild = objectWithId(objectManager, entry.getValue());
            assertTrue(childClass.isInstance(restoredChild), "rewind id must resolve to a chain child");
            assertNotSame(beforeByIndex.get(childIndex), restoredChild,
                    "restore must recreate child " + childIndex);
            assertSame(restoredChild, parentSlots[childIndex],
                    "restored parent slot must point to restored child " + childIndex);
            assertEquals(ORIGIN_X, restoredChild.getSpawn().rawYWord(),
                    "child spawn must preserve origin metadata for relink");
            assertEquals(childIndex, childIndex(restoredChild),
                    "child spawn must preserve child index metadata");
        }
    }

    @Test
    void s1SpikedBallChainChildUsesGenericRecreateWithoutExplicitDynamicCodec() {
        Class<?> childClass = loadClass(CHAIN_CHILD_FQN);
        assertTrue(RewindRecreatable.class.isAssignableFrom(childClass),
                "chain children must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(CHAIN_CHILD_FQN),
                "chain children must not use an explicit dynamic rewind codec");
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(ObjectSpawn spawn) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager objectManager = new ObjectManager(
                    List.of(spawn), new Sonic1ObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = objectManager;
            objectManager.reset(0);
            return new Harness(objectManager);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        ObjectRefId id = objectManager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static ObjectInstance objectWithId(ObjectManager objectManager, ObjectRefId id) {
        List<ObjectInstance> matches = objectManager.getActiveObjects().stream()
                .filter(object -> id.equals(objectManager.captureIdentityContext().requireIdentityTable().idFor(object)))
                .toList();
        assertEquals(1, matches.size(), "expected one live object for rewind id " + id);
        return matches.getFirst();
    }

    private static Sonic1SpikedBallChainObjectInstance liveParent(ObjectManager objectManager) {
        List<Sonic1SpikedBallChainObjectInstance> parents = objectManager.getActiveObjects().stream()
                .filter(Sonic1SpikedBallChainObjectInstance.class::isInstance)
                .map(Sonic1SpikedBallChainObjectInstance.class::cast)
                .filter(object -> !object.isDestroyed())
                .toList();
        assertEquals(1, parents.size(), "expected one live spiked-ball chain parent");
        return parents.getFirst();
    }

    private static List<ObjectInstance> liveChildren(ObjectManager objectManager, Class<?> childClass) {
        return objectManager.getActiveObjects().stream()
                .filter(childClass::isInstance)
                .filter(object -> !object.isDestroyed())
                .sorted(Comparator.comparingInt(TestSonic1SpikedBallChainGraphRewind::childIndex))
                .toList();
    }

    private static Map<Integer, ObjectInstance> byChildIndex(List<ObjectInstance> children) {
        return children.stream().collect(Collectors.toMap(
                TestSonic1SpikedBallChainGraphRewind::childIndex,
                child -> child));
    }

    private static int childIndex(ObjectInstance object) {
        return (object.getSpawn().subtype() >> 4) & 0x07;
    }

    private static Object[] readChildren(Sonic1SpikedBallChainObjectInstance parent) {
        try {
            Field field = Sonic1SpikedBallChainObjectInstance.class.getDeclaredField("children");
            field.setAccessible(true);
            return (Object[]) field.get(parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Class<?> loadClass(String fqn) {
        try {
            return Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    private static Camera mockCamera() {
        return new Camera() {
            @Override public short getX() { return 0; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 320; }
            @Override public short getHeight() { return 224; }
        };
    }
}
