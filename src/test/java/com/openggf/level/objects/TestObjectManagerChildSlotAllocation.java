package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.camera.Camera;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestObjectManagerChildSlotAllocation {

    @BeforeEach
    public void setUp() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    public void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    public void childSpawnedDuringUpdate_allocatesAfterParentSlot() {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        ParentObject parent = new ParentObject(new ObjectSpawn(0, 0, 0, 0, 0, false, 0));
        manager.addDynamicObjectAtSlot(parent, 40);

        manager.update(0, null, null, 1);

        assertNotNull(parent.child);
        assertTrue(parent.child.getSlotIndex() > parent.getSlotIndex());
        assertFalse(parent.childWaitsForNextPass,
                "a child in a later SST slot remains executable in the current pass");
        assertEquals(1, parent.child.updateCount);
    }

    @Test
    public void childOptingOutOfSameFrameUpdate_waitsUntilNextFrameButSkipsTouch() {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        OptOutParentObject parent =
                new OptOutParentObject(new ObjectSpawn(0, 0, 0, 0, 0, false, 0));
        manager.addDynamicObjectAtSlot(parent, 40);

        manager.update(0, null, null, 1);

        assertNotNull(parent.child);
        assertTrue(parent.child.getSlotIndex() > parent.getSlotIndex());
        assertEquals(0, parent.child.updateCount);
        assertTrue(parent.child.isSkipTouchThisFrame());

        manager.update(0, null, null, 2);

        assertEquals(1, parent.child.updateCount);
    }

    @Test
    public void childSpawnedAfterLastSlot_isDiscardedLikeAllocateObjectAfterCurrentFailure() {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        ParentObject parent = new ParentObject(new ObjectSpawn(0, 0, 0, 0, 0, false, 0));
        manager.addDynamicObjectAtSlot(parent, 127);

        manager.update(0, null, null, 1);

        assertNotNull(parent.child, "the Java factory still constructs before manager insertion");
        assertEquals(-1, parent.child.getSlotIndex(),
                "AllocateObjectAfterCurrent has no slot after the final SST entry");
        assertTrue(parent.child.isDestroyed(),
                "ROM allocation failure leaves no live child object");
        assertEquals(0, parent.child.updateCount,
                "slotless overflow children must not execute through the fallback loop");
    }

    @Test
    public void childSpawnedWithFindFreeObj_usesLowestFreeSlotAndWaitsUntilNextFrame() {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        FindFreeParentObject parent =
                new FindFreeParentObject(new ObjectSpawn(0, 0, 0, 0, 0, false, 0));
        manager.addDynamicObjectAtSlot(parent, 40);

        manager.update(0, null, null, 1);

        assertNotNull(parent.child);
        assertEquals(32, parent.child.getSlotIndex());
        assertTrue(parent.child.getSlotIndex() < parent.getSlotIndex());
        assertTrue(parent.childWaitsForNextPass,
                "a first-free child behind the live SST cursor waits for the next pass");
        assertEquals(0, parent.child.updateCount,
                "FindFreeObj child in an earlier slot should not run again this frame");

        manager.update(0, null, null, 2);

        assertEquals(1, parent.child.updateCount);
    }

    @Test
    public void childSpawnedWithFindFreeObj_usesSonic2DynamicSlotBase() {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        ObjectManager manager = new ObjectManager(
                List.of(), new Sonic2ObjectRegistry(), 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        FindFreeParentObject parent =
                new FindFreeParentObject(new ObjectSpawn(0, 0, 0, 0, 0, false, 0));
        manager.addDynamicObjectAtSlot(parent, 40);

        manager.update(0, null, null, 1);

        assertNotNull(parent.child);
        assertEquals(16, parent.child.getSlotIndex(),
                "Sonic 2 FindFreeObj should start allocating at dynamic slot 0x10");
    }

    @Test
    public void reservedSlotChildrenPersistAfterParentInit() {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        ReservedParentObject parent =
                new ReservedParentObject(new ObjectSpawn(0x100, 0x100, 0x25, 0x12, 0, false, 0));
        manager.addDynamicObjectAtSlot(parent, 40);
        manager.allocateChildSlots(parent.getSpawn(), 2);

        manager.update(0, null, null, 1);

        assertEquals(List.of(32, 33), parent.spawnedChildSlots(),
                "reserved children should consume the next free reserved slots");
        assertFalse(manager.getActiveObjects().stream()
                        .filter(ReservedChildObject.class::isInstance)
                        .collect(Collectors.toList())
                        .isEmpty(),
                "reserved children should remain in the live object set after insertion");

        manager.update(0, null, null, 2);

        List<ReservedChildObject> children = manager.getActiveObjects().stream()
                .filter(ReservedChildObject.class::isInstance)
                .map(ReservedChildObject.class::cast)
                .collect(Collectors.toList());
        assertEquals(2, children.size(), "reserved children should still exist on the next frame");
        assertEquals(List.of(32, 33), children.stream()
                .map(ReservedChildObject::getSlotIndex)
                .sorted()
                .toList());
    }

    private static final class ParentObject extends AbstractObjectInstance {
        private ChildObject child;
        private boolean childWaitsForNextPass;

        private ParentObject(ObjectSpawn spawn) {
            super(spawn, "Parent");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (child == null) {
                child = spawnChild(() -> new ChildObject(buildSpawnAt(spawn.x(), spawn.y())));
                childWaitsForNextPass = services().objectManager()
                        .reservedSlotWaitsForNextObjectPass(child.getSlotIndex());
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class ChildObject extends AbstractObjectInstance {
        private int updateCount;

        private ChildObject(ObjectSpawn spawn) {
            super(spawn, "Child");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            updateCount++;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class OptOutParentObject extends AbstractObjectInstance {
        private OptOutChildObject child;

        private OptOutParentObject(ObjectSpawn spawn) {
            super(spawn, "OptOutParent");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (child == null) {
                child = spawnChild(() -> new OptOutChildObject(buildSpawnAt(spawn.x(), spawn.y())));
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class OptOutChildObject extends AbstractObjectInstance {
        private int updateCount;

        private OptOutChildObject(ObjectSpawn spawn) {
            super(spawn, "OptOutChild");
        }

        @Override
        protected boolean skipsSameFrameUpdateAfterSpawn() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            updateCount++;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class FindFreeParentObject extends AbstractObjectInstance {
        private FindFreeChildObject child;
        private boolean childWaitsForNextPass;

        private FindFreeParentObject(ObjectSpawn spawn) {
            super(spawn, "FindFreeParent");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (child == null) {
                child = spawnFreeChild(() -> new FindFreeChildObject(buildSpawnAt(spawn.x(), spawn.y())));
                childWaitsForNextPass = services().objectManager()
                        .reservedSlotWaitsForNextObjectPass(child.getSlotIndex());
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class FindFreeChildObject extends AbstractObjectInstance {
        private int updateCount;

        private FindFreeChildObject(ObjectSpawn spawn) {
            super(spawn, "FindFreeChild");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            updateCount++;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class ReservedParentObject extends AbstractObjectInstance {
        private final java.util.List<ReservedChildObject> children = new java.util.ArrayList<>();

        private ReservedParentObject(ObjectSpawn spawn) {
            super(spawn, "ReservedParent");
        }

        @Override
        public int getReservedChildSlotCount() {
            return 2;
        }

        @Override
        public boolean needsPreAllocatedChildSlots() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (!children.isEmpty()) {
                return;
            }
            ObjectManager manager = services().objectManager();
            for (int i = 0; i < 2; i++) {
                ReservedChildObject child =
                        new ReservedChildObject(buildSpawnAt(spawn.x() + ((i + 1) * 0x10), spawn.y()));
                manager.addDynamicObjectToReservedSlot(child, spawn, i);
                children.add(child);
            }
        }

        private java.util.List<Integer> spawnedChildSlots() {
            return children.stream().map(ReservedChildObject::getSlotIndex).sorted().toList();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }

    private static final class ReservedChildObject extends AbstractObjectInstance {
        private ReservedChildObject(ObjectSpawn spawn) {
            super(spawn, "ReservedChild");
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }
    }
}
