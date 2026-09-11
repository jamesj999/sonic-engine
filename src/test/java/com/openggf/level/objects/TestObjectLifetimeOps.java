package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestObjectLifetimeOps {
    private ObjectManager objectManager;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        GraphicsManager.getInstance().initHeadless();
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        };
        objectManager = new ObjectManager(List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
        SessionManager.clear();
    }

    @Test
    void namedDestroyOperationsPreserveCurrentFlags() {
        TestObject latched = testObject();
        ObjectLifetimeOps.destroyLatched(latched);
        assertTrue(latched.isDestroyed());
        assertFalse(latched.isDestroyedRespawnable());

        TestObject noRespawn = testObject();
        ObjectLifetimeOps.deleteNoRespawn(noRespawn);
        assertTrue(noRespawn.isDestroyed());
        assertFalse(noRespawn.isDestroyedRespawnable());

        TestObject dynamic = testObject();
        ObjectLifetimeOps.expireDynamic(dynamic);
        assertTrue(dynamic.isDestroyed());
        assertFalse(dynamic.isDestroyedRespawnable());
    }

    @Test
    void respawnableOffscreenDestroySetsRespawnableFlag() {
        TestObject object = testObject();

        ObjectLifetimeOps.destroyRespawnableOffscreen(object);

        assertTrue(object.isDestroyed());
        assertTrue(object.isDestroyedRespawnable());
    }

    @Test
    void deleteNoRespawnClearsPriorRespawnableDestroyFlag() {
        TestObject object = testObject();
        ObjectLifetimeOps.destroyRespawnableOffscreen(object);

        ObjectLifetimeOps.deleteNoRespawn(object);

        assertTrue(object.isDestroyed());
        assertFalse(object.isDestroyedRespawnable(),
                "deleteNoRespawn is a permanent delete and must not leave a stale off-screen respawn flag");
    }

    @Test
    void slotTransferDetachesSourceAndAddsReplacementAtSameSlot() {
        TestObject source = testObject();
        TestObject replacement = testObject();
        objectManager.addDynamicObjectAtSlot(source, 40);

        int slot = ObjectLifetimeOps.detachSlotForTransfer(source);
        ObjectLifetimeOps.addReplacementAtTransferredSlot(objectManager, replacement, slot);

        assertEquals(40, slot);
        assertEquals(-1, source.getSlotIndex());
        assertEquals(40, replacement.getSlotIndex());
        assertTrue(objectManager.getActiveObjects().contains(replacement));
    }

    @Test
    void clearPreviousManagerSlotDropsStaleSlotBeforeReRegistration() {
        TestObject object = testObject();
        objectManager.addDynamicObjectAtSlot(object, 41);

        ObjectLifetimeOps.clearPreviousManagerSlot(object);

        assertEquals(-1, object.getSlotIndex());
    }

    @Test
    void reservedSlotAdditionUsesRequestedDynamicSlot() {
        TestObject object = testObject();

        ObjectLifetimeOps.addDynamicAtReservedSlot(objectManager, object, 42);

        assertEquals(42, object.getSlotIndex());
        assertTrue(objectManager.getActiveObjects().contains(object));
    }

    @Test
    void assignFindNextFreeChildSlotAllocatesAfterPredecessorAndAssignsChildSlot() {
        TestObject predecessor = testObject();
        TestObject child = testObject();
        objectManager.addDynamicObjectAtSlot(predecessor, 40);

        int slot = ObjectLifetimeOps.assignFindNextFreeChildSlot(
                objectManager, child, predecessor.getSlotIndex());

        assertEquals(41, slot);
        assertEquals(41, child.getSlotIndex());
    }

    @Test
    void assignFindNextFreeChildSlotWithMissingPredecessorLeavesChildSlotUnchanged() {
        TestObject child = testObject();
        child.setSlotIndex(42);

        int slot = ObjectLifetimeOps.assignFindNextFreeChildSlot(objectManager, child, -1);

        assertEquals(-1, slot);
        assertEquals(42, child.getSlotIndex());
    }

    @Test
    void transferredReplacementWithoutSlotUsesNormalDynamicAllocation() {
        TestObject replacement = testObject();

        ObjectLifetimeOps.addReplacementAtTransferredSlot(objectManager, replacement, -1);

        assertTrue(replacement.getSlotIndex() >= 0);
        assertTrue(objectManager.getActiveObjects().contains(replacement));
    }

    @Test
    void rememberAndRemoveSpawnDelegateToPlacementState() {
        ObjectSpawn remembered = new ObjectSpawn(0, 0, 1, 0, 0, true, 0x8000);
        ObjectSpawn removed = new ObjectSpawn(8, 0, 2, 0, 0, false, 0);
        ObjectManager manager = new ObjectManager(List.of(remembered, removed), null, 0, null, null,
                GraphicsManager.getInstance(), GameServices.camera(), new StubObjectServices());
        manager.update(0, null, List.of(), 1);

        ObjectLifetimeOps.markSpawnRemembered(manager, remembered);
        ObjectLifetimeOps.removeSpawnFromActive(manager, removed);

        assertTrue(manager.isRemembered(remembered));
        assertFalse(manager.getActiveSpawns().contains(removed));
    }

    private static TestObject testObject() {
        return new TestObject(new ObjectSpawn(0, 0, 0, 0, 0, false, 0));
    }

    private static final class TestObject extends AbstractObjectInstance {
        private TestObject(ObjectSpawn spawn) {
            super(spawn, "Test");
        }

        @Override
        public int getX() {
            return getSpawn().x();
        }

        @Override
        public int getY() {
            return getSpawn().y();
        }

        @Override
        public void appendRenderCommands(List<com.openggf.graphics.GLCommand> commands) {
        }
    }
}
