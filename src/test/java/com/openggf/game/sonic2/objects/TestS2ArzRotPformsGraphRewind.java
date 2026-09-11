package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestS2ArzRotPformsGraphRewind {
    private static final String SLOT_CHILD_CLASS =
            "com.openggf.game.sonic2.objects.ARZRotPformsObjectInstance$Obj83SlotChild";
    private static final ObjectSpawn PARENT_SPAWN = new ObjectSpawn(
            0x260, 0x150, Sonic2ObjectIds.ARZ_ROT_PFORMS, 0, 0, false, 0, 124);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void obj83GraphRestoresThreeRoleChildrenWithFreshParentLinks() {
        Harness harness = Harness.create(List.of(PARENT_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        objectManager.update(0, null, null, 0, false);

        ARZRotPformsObjectInstance beforeParent = onlyParent(objectManager);
        List<ObjectInstance> beforeChildren = liveChildren(objectManager);
        assertEquals(3, beforeChildren.size(), "Obj83 must allocate its three ROM child slots");
        beforeChildren.forEach(child -> assertSame(beforeParent, readField(child, "parent")));

        ObjectRefId parentId = objectId(objectManager, beforeParent);
        List<ObjectRefId> childIds = beforeChildren.stream()
                .map(child -> objectId(objectManager, child))
                .toList();
        Map<String, ChildIdentity> beforeIdentityByRole = identityByRole(objectManager, beforeChildren);
        RewindRegistry registry = new RewindRegistry();
        registry.register(objectManager.rewindSnapshottable());
        CompositeSnapshot snapshot = registry.capture();

        beforeChildren.forEach(objectManager::removeDynamicObject);
        registry.restore(snapshot);

        ARZRotPformsObjectInstance restoredParent = onlyParent(objectManager);
        List<ObjectInstance> restoredChildren = liveChildren(objectManager);
        assertNotSame(beforeParent, restoredParent);
        assertEquals(parentId, objectId(objectManager, restoredParent));
        assertEquals(3, restoredChildren.size());
        assertEquals(3, new HashSet<>(restoredChildren).size());
        assertEquals(new HashSet<>(childIds), restoredChildren.stream()
                .map(child -> objectId(objectManager, child))
                .collect(Collectors.toSet()));
        assertEquals(beforeIdentityByRole, identityByRole(objectManager, restoredChildren),
                "each Obj83 role must retain its captured identity and slot");
        restoredChildren.forEach(child -> {
            assertSame(restoredParent, readField(child, "parent"));
            assertNotSame(beforeParent, readField(child, "parent"));
            assertNotNull(readField(child, "kind"));
        });
    }

    private record Harness(ObjectManager objectManager) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mockCamera();
            LevelManager levelManager = mock(LevelManager.class);
            when(levelManager.getFrameCounter()).thenReturn(0);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
                @Override public LevelManager levelManager() { return levelManager; }
            };
            ObjectManager manager = new ObjectManager(
                    spawns,
                    new Sonic2ObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = manager;
            manager.reset(0);
            return new Harness(manager);
        }
    }

    private static ARZRotPformsObjectInstance onlyParent(ObjectManager manager) {
        List<ARZRotPformsObjectInstance> parents = manager.getActiveObjects().stream()
                .filter(ARZRotPformsObjectInstance.class::isInstance)
                .map(ARZRotPformsObjectInstance.class::cast)
                .filter(parent -> !parent.isDestroyed())
                .toList();
        assertEquals(1, parents.size());
        return parents.getFirst();
    }

    private static List<ObjectInstance> liveChildren(ObjectManager manager) {
        return manager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(SLOT_CHILD_CLASS))
                .filter(object -> !object.isDestroyed())
                .toList();
    }

    private static ObjectRefId objectId(ObjectManager manager, ObjectInstance object) {
        ObjectRefId id = manager.captureIdentityContext().requireIdentityTable().idFor(object);
        assertNotNull(id);
        return id;
    }

    private static Map<String, ChildIdentity> identityByRole(
            ObjectManager manager, List<ObjectInstance> children) {
        return children.stream().collect(Collectors.toMap(
                child -> readField(child, "kind").toString(),
                child -> new ChildIdentity(
                        objectId(manager, child),
                        ((AbstractObjectInstance) child).getSlotIndex())));
    }

    private record ChildIdentity(ObjectRefId objectId, int slotIndex) {
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
}
