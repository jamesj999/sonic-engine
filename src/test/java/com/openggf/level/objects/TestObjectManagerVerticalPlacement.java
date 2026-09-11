package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestObjectManagerVerticalPlacement {

    @Test
    void nonCounterPlacementDefersObjectUntilCameraYWindowIncludesSpawn() {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setY((short) 0x0923);

        ObjectSpawn spawn = new ObjectSpawn(0x17C0, 0x0860, 0x41, 0, 0, false, 0x0860);
        ObjectManager manager = new ObjectManager(
                List.of(spawn),
                new TestRegistry(),
                -1,
                null,
                null,
                null,
                camera,
                new StubObjectServices() {
                    @Override
                    public Camera camera() {
                        return camera;
                    }
                });

        manager.reset(0x186B);
        assertEquals(0, manager.getActiveObjects().size());

        camera.setY((short) 0x08F6);
        manager.update(0x181F, null, List.of(), 0, false);

        assertEquals(1, manager.getActiveObjects().size());
    }

    @Test
    void signedRawYWordBypassesVerticalFilterLikeRom() {
        ObjectSpawn alwaysLoad = new ObjectSpawn(0x17C0, 0x0860, 0x41, 0, 0, false, 0x8860);
        ObjectSpawn gated = new ObjectSpawn(0x17C0, 0x0860, 0x41, 0, 0, false, 0x0860);

        assertTrue(ObjectManager.isNonCounterSpawnVerticallyEligible(alwaysLoad, 0x0923, 0));
        assertFalse(ObjectManager.isNonCounterSpawnVerticallyEligible(gated, 0x0923, 0));
        assertTrue(ObjectManager.isNonCounterSpawnVerticallyEligible(gated, 0x08F6, 0));
    }

    @Test
    void negativeCameraMinYStillAppliesNonWrappingVerticalBand() {
        ObjectSpawn mgzBridge = new ObjectSpawn(0x04F0, 0x0834, 0x0F, 0x12, 0, false, 0x0834);

        assertFalse(ObjectManager.isNonCounterSpawnVerticallyEligible(mgzBridge, 0x045D, 0xFF00, 0x1000));
        assertFalse(ObjectManager.isNonCounterSpawnVerticallyEligible(mgzBridge, 0x0602, 0xFF00, 0x1000));
        assertTrue(ObjectManager.isNonCounterSpawnVerticallyEligible(mgzBridge, 0x06B0, 0xFF00, 0x1000));
    }

    @Test
    void negativeCameraMinYUsesSplitBandOnlyAcrossWrapBoundary() {
        ObjectSpawn nearTop = new ObjectSpawn(0x04F0, 0x0180, 0x0F, 0, 0, false, 0x0180);
        ObjectSpawn nearBottom = new ObjectSpawn(0x04F0, 0x0F90, 0x0F, 0, 0, false, 0x0F90);
        ObjectSpawn middle = new ObjectSpawn(0x04F0, 0x0834, 0x0F, 0, 0, false, 0x0834);

        assertTrue(ObjectManager.isNonCounterSpawnVerticallyEligible(nearTop, 0x0040, 0xFF00, 0x1000));
        assertTrue(ObjectManager.isNonCounterSpawnVerticallyEligible(nearBottom, 0x0040, 0xFF00, 0x1000));
        assertFalse(ObjectManager.isNonCounterSpawnVerticallyEligible(middle, 0x0040, 0xFF00, 0x1000));
    }

    @Test
    void s3kCursorLoadsNewXPassEntriesBeforeDeferredYPassEntries() {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setMinY((short) 0);
        camera.setY((short) 0);

        ObjectSpawn deferredLowX = new ObjectSpawn(0x0200, 0x0280, 0x41, 0, 0, false, 0x0280);
        ObjectSpawn newlyEnteredHighX = new ObjectSpawn(0x02C0, 0x0100, 0x42, 0, 0, false, 0x0100);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = new ObjectManager(
                List.of(deferredLowX, newlyEnteredHighX),
                registry,
                -1,
                null,
                null,
                null,
                camera,
                new StubObjectServices() {
                    @Override
                    public Camera camera() {
                        return camera;
                    }
                });
        manager.enableExecThenLoadPlacement();

        manager.reset(0);
        assertEquals(0, manager.getActiveObjects().size(),
                "Low-X spawn is horizontally cursor-passed but still outside the S3K vertical load band");

        camera.setY((short) 0x0100);
        manager.update(0x0080, null, List.of(), 1, false);

        DummyObject high = registry.instances.get(newlyEnteredHighX);
        DummyObject low = registry.instances.get(deferredLowX);
        assertEquals(4, high.getSlotIndex(),
                "S3K Load_Sprites runs the new X-pass allocation before the deferred Y-pass "
                        + "(docs/skdisasm/sonic3k.asm:37640-37762)");
        assertEquals(5, low.getSlotIndex(),
                "The earlier low-X entry is created by the later Y-camera pass, after X-pass slots are consumed");
    }

    @Test
    void s3kTwoAxisPendingLoadsSurviveRewindRestore() {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setMinY((short) 0);
        camera.setY((short) 0);

        ObjectSpawn deferredLowX = new ObjectSpawn(0x0200, 0x0280, 0x41, 0, 0, false, 0x0280);
        ObjectSpawn newlyEnteredHighX = new ObjectSpawn(0x02C0, 0x0100, 0x42, 0, 0, false, 0x0100);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = new ObjectManager(
                List.of(deferredLowX, newlyEnteredHighX),
                registry,
                -1,
                null,
                null,
                null,
                camera,
                new StubObjectServices() {
                    @Override
                    public Camera camera() {
                        return camera;
                    }
                });
        manager.enableExecThenLoadPlacement();

        manager.reset(0);
        var snapshot = manager.rewindSnapshottable().capture();

        camera.setY((short) 0x0100);
        manager.update(0x0080, null, List.of(), 1, false);
        assertEquals(4, registry.instances.get(newlyEnteredHighX).getSlotIndex());
        assertEquals(5, registry.instances.get(deferredLowX).getSlotIndex());

        registry.instances.clear();
        manager.rewindSnapshottable().restore(snapshot);
        manager.update(0x0080, null, List.of(), 1, false);

        assertEquals(4, registry.instances.get(newlyEnteredHighX).getSlotIndex(),
                "Pending X-pass load order must rewind with ObjectManager placement state");
        assertEquals(5, registry.instances.get(deferredLowX).getSlotIndex(),
                "Deferred Y-pass membership must rewind with ObjectManager placement state");
    }

    @Test
    void s3kTwoAxisCursorRetriesPendingEntryAfterFindFreeObjFailure() {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setMinY((short) 0);
        camera.setY((short) 0);

        ObjectSpawn late = new ObjectSpawn(0x02C0, 0x0100, 0x41, 0, 0, false, 0x0100);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = new ObjectManager(
                List.of(late),
                registry,
                -1,
                null,
                null,
                null,
                camera,
                new StubObjectServices() {
                    @Override
                    public Camera camera() {
                        return camera;
                    }
                });
        manager.enableExecThenLoadPlacement();
        manager.reset(0);

        DummyObject firstOccupant = null;
        for (int slot = ObjectSlotLayout.SONIC_3K.firstDynamicSlot();
                slot < ObjectSlotLayout.SONIC_3K.lastDynamicSlotExclusive(); slot++) {
            DummyObject occupant = new DummyObject(null);
            manager.addDynamicObjectAtSlot(occupant, slot);
            if (firstOccupant == null) {
                firstOccupant = occupant;
            }
        }

        manager.update(0x0080, null, List.of(), 1, false);
        assertFalse(registry.instances.containsKey(late),
                "the X cursor may cross the entry while FindFreeObj has no slot");
        assertArrayEquals(new int[] {0}, manager.rewindSnapshottable().capture()
                        .placement().pendingCursorLoadOrder(),
                "FindFreeObj failure must retain ordered cursor ownership");

        manager.removeDynamicObject(firstOccupant);
        manager.update(0x0080, null, List.of(), 2, false);

        assertTrue(registry.instances.containsKey(late),
                "cursor-owned work must retry after an SST slot becomes available");
        assertEquals(ObjectSlotLayout.SONIC_3K.firstDynamicSlot(),
                registry.instances.get(late).getSlotIndex());
    }

    @Test
    void s3kReleasedPlacementRespawnsOnLaterCameraYPassWhileTransformedObjectLives() {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setMinY((short) 0);
        camera.setY((short) 0x0180);

        ObjectSpawn bridgeSpawn = new ObjectSpawn(0x1900, 0x0340, 0x0F, 0x80, 0, false, 0x0340);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = new ObjectManager(
                List.of(bridgeSpawn),
                registry,
                -1,
                null,
                null,
                null,
                camera,
                new StubObjectServices() {
                    @Override
                    public Camera camera() {
                        return camera;
                    }
                });
        manager.enableExecThenLoadPlacement();

        manager.reset(0x182E);
        DummyObject transformed = registry.instances.get(bridgeSpawn);
        assertTrue(manager.getActiveObjects().contains(transformed));

        ObjectLifetimeOps.releaseSpawnForRespawn(manager, transformed, bridgeSpawn);
        camera.setY((short) 0x0100);
        manager.update(0x182E, null, List.of(), 1, false);
        camera.setY((short) 0x0180);
        manager.update(0x182E, null, List.of(), 2, false);

        DummyObject replacement = registry.instances.get(bridgeSpawn);
        assertNotSame(transformed, replacement,
                "S3K Camera_Y pass must recreate a placement whose respawn bit was cleared in-place");
        assertTrue(manager.getActiveObjects().contains(transformed),
                "The original transformed SST object must keep executing as a dynamic fragment");
        assertTrue(manager.getActiveObjects().contains(replacement),
                "The fresh placement instance must coexist with the transformed fragment");
    }

    @Test
    void s3kRespawnableSelfDeleteRemainsEligibleForLaterCameraYPass() {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setMinY((short) 0);
        camera.setY((short) 0x0180);

        ObjectSpawn balloonSpawn = new ObjectSpawn(0x1920, 0x0340, 0x41, 0, 0, false, 0x0340);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = new ObjectManager(
                List.of(balloonSpawn),
                registry,
                -1,
                null,
                null,
                null,
                camera,
                new StubObjectServices() {
                    @Override
                    public Camera camera() {
                        return camera;
                    }
                });
        manager.enableExecThenLoadPlacement();

        manager.reset(0x182E);
        DummyObject original = registry.instances.get(balloonSpawn);
        original.setDestroyedByOffscreen();
        manager.update(0x182E, null, List.of(), 1, false);
        assertFalse(manager.getActiveObjects().contains(original));

        camera.setY((short) 0x0100);
        manager.update(0x182E, null, List.of(), 2, false);
        camera.setY((short) 0x0180);
        manager.update(0x182E, null, List.of(), 3, false);

        DummyObject replacement = registry.instances.get(balloonSpawn);
        assertNotSame(original, replacement,
                "S3K loc_1B982 must rescan an X-cursor-passed entry after "
                        + "Sprite_CheckDeleteTouch3 clears its respawn bit");
        assertTrue(manager.getActiveObjects().contains(replacement));
    }

    @Test
    void s3kPostCameraCatchupDoesNotPreemptNextLoadSpritesPass() {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setMinY((short) 0);
        camera.setY((short) 0x0200);

        ObjectSpawn lowBarberPole = new ObjectSpawn(0x0280, 0x0300, 0x74, 0, 0, false, 0x0300);
        ObjectSpawn highBarberPole = new ObjectSpawn(0x02C0, 0x0300, 0x74, 0, 0, false, 0x0300);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = new ObjectManager(
                List.of(lowBarberPole, highBarberPole),
                registry,
                -1,
                null,
                null,
                null,
                camera,
                new StubObjectServices() {
                    @Override
                    public Camera camera() {
                        return camera;
                    }
                });
        manager.enableExecThenLoadPlacement();

        manager.reset(0);
        assertEquals(0, manager.getActiveObjects().size());

        manager.postCameraPlacementUpdate(0x0080);
        assertEquals(0, manager.getActiveObjects().size(),
                "S3K Load_Sprites runs before the later camera/deform step; post-camera catch-up must not "
                        + "create the next X-window objects early (docs/skdisasm/sonic3k.asm:7884-7895)");

        manager.update(0x0080, null, List.of(), 1, false);

        DummyObject low = registry.instances.get(lowBarberPole);
        DummyObject high = registry.instances.get(highBarberPole);
        assertEquals(4, low.getSlotIndex(),
                "The next S3K Load_Sprites X pass scans newly accepted entries in object-list order");
        assertEquals(5, high.getSlotIndex(),
                "The higher-X barber-pole entry must not preempt the lower-X entry via post-camera catch-up");
    }

    @Test
    void s3kCnzBarberPoleOrderUsesNarrowYPassAfterBroadXPass() {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setMinY((short) 0);
        camera.setY((short) 0x0580);

        ObjectSpawn blocker1 = new ObjectSpawn(0x0D00, 0x0600, 0x10, 0, 0, false, 0x0600);
        ObjectSpawn blocker2 = new ObjectSpawn(0x0D10, 0x0600, 0x11, 0, 0, false, 0x0600);
        ObjectSpawn blocker3 = new ObjectSpawn(0x0D20, 0x0600, 0x12, 0, 0, false, 0x0600);
        ObjectSpawn blocker4 = new ObjectSpawn(0x0D30, 0x0600, 0x13, 0, 0, false, 0x0600);
        ObjectSpawn lowBarberPole = new ObjectSpawn(0x0EF0, 0x0790, 0x4D, 0, 0, false, 0x0790);
        ObjectSpawn highBarberPole = new ObjectSpawn(0x0F70, 0x0810, 0x4D, 0, 0, false, 0x0810);
        TrackingRegistry registry = new TrackingRegistry();
        ObjectManager manager = new ObjectManager(
                List.of(blocker1, blocker2, blocker3, blocker4, lowBarberPole, highBarberPole),
                registry,
                -1,
                null,
                null,
                null,
                camera,
                new StubObjectServices() {
                    @Override
                    public Camera camera() {
                        return camera;
                    }
                });
        manager.enableExecThenLoadPlacement();

        manager.reset(0x0C80);
        assertEquals(4, registry.instances.get(blocker1).getSlotIndex());
        assertEquals(7, registry.instances.get(blocker4).getSlotIndex());
        assertFalse(registry.instances.containsKey(lowBarberPole),
                "The low CNZ pole is X-cursor-passed but just below the broad X-pass Y band");

        camera.setY((short) 0x0600);
        manager.update(0x0C80, null, List.of(), 1, false);

        DummyObject low = registry.instances.get(lowBarberPole);
        assertEquals(8, low.getSlotIndex(),
                "S3K Load_Sprites Y pass scans the newly exposed 0x0780..0x0800 strip in object-list order "
                        + "(docs/skdisasm/sonic3k.asm:37723-37762)");

        manager.update(0x0D00, null, List.of(), 2, false);
        assertFalse(registry.instances.containsKey(highBarberPole),
                "The high CNZ pole is horizontally accepted, but y=0x0810 is outside the current X-pass band");

        camera.setY((short) 0x0680);
        manager.update(0x0D00, null, List.of(), 3, false);

        DummyObject high = registry.instances.get(highBarberPole);
        assertEquals(9, high.getSlotIndex(),
                "The later high-X CNZ pole must allocate after the earlier low-X pole");
    }

    private static final class TestRegistry implements ObjectRegistry {
        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            return new DummyObject(spawn);
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "Dummy";
        }

        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_3K;
        }
    }

    private static final class TrackingRegistry implements ObjectRegistry {
        private final Map<ObjectSpawn, DummyObject> instances = new IdentityHashMap<>();

        @Override
        public ObjectInstance create(ObjectSpawn spawn) {
            DummyObject object = new DummyObject(spawn);
            instances.put(spawn, object);
            return object;
        }

        @Override
        public void reportCoverage(List<ObjectSpawn> spawns) {
        }

        @Override
        public String getPrimaryName(int objectId) {
            return "Dummy";
        }

        @Override
        public ObjectSlotLayout objectSlotLayout() {
            return ObjectSlotLayout.SONIC_3K;
        }
    }

    private static final class DummyObject extends AbstractObjectInstance {
        private DummyObject(ObjectSpawn spawn) {
            super(spawn, "Dummy");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }
    }
}
