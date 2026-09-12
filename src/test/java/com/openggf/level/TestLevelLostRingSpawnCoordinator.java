package com.openggf.level;

import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.rings.RingManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLevelLostRingSpawnCoordinator {
    private LevelManager manager;
    private RingManager rings;
    private ObjectManager objects;
    private LevelLostRingSpawnCoordinator coordinator;
    private AbstractPlayableSprite player;

    @BeforeEach
    void setUp() {
        manager = mock(LevelManager.class);
        rings = mock(RingManager.class);
        ObjectRegistry registry = mock(ObjectRegistry.class);
        when(registry.objectSlotLayout()).thenReturn(ObjectSlotLayout.SONIC_3K);
        objects = new ObjectManager(List.of(), registry, 0, null, null);
        manager.ringManager = rings;
        manager.objectManager = objects;
        player = mock(AbstractPlayableSprite.class);
        when(player.getCode()).thenReturn("p1");
        when(player.getRingCount()).thenReturn(3);
        when(player.getCentreX()).thenReturn((short) 0x120);
        when(player.getCentreY()).thenReturn((short) 0x90);
        manager.spriteManager = new com.openggf.sprites.managers.SpriteManager();
        manager.spriteManager.addSprite(player);
        coordinator = new LevelLostRingSpawnCoordinator(manager);
    }

    @Test
    void deferredQueueWaitsUntilTheFollowingFrameAndUsesItsReservedSlots() {
        coordinator.queue(player, 40, true);
        assertEquals(3, objects.getAllocatedSlotCount());

        manager.frameCounter = 40;
        coordinator.processPending();
        verify(rings, never()).spawnLostRingsWithInitialObjectStep(
                any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), anyBoolean(), anyBoolean());

        manager.frameCounter = 41;
        coordinator.processPending();
        verify(rings).spawnLostRingsWithInitialObjectStep(
                player, 3, 41, 0x120, 0x90, new int[] {4, 5, 6}, true, true);
    }

    @Test
    void cancelledDeferredQueueReleasesEveryReservation() {
        coordinator.queue(player, 40, false);
        assertEquals(3, objects.getAllocatedSlotCount());
        when(player.getRingCount()).thenReturn(0);

        manager.frameCounter = 41;
        coordinator.processPending();

        assertEquals(0, objects.getAllocatedSlotCount());
        verify(rings, never()).spawnLostRingsWithInitialObjectStep(
                any(), anyInt(), anyInt(), anyInt(), anyInt(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void resetReleasesPendingReservations() {
        coordinator.queue(player, 40, false);
        assertEquals(3, objects.getAllocatedSlotCount());

        coordinator.reset();

        assertEquals(0, objects.getAllocatedSlotCount());
    }

    @Test
    void rewindRestoreUsesTheCurrentPlayerForTheCapturedPendingQueue() {
        coordinator.queue(player, 40, true);
        LevelLostRingSpawnCoordinator.Snapshot snapshot = coordinator.capture();
        coordinator.reset();

        AbstractPlayableSprite restoredPlayer = mock(AbstractPlayableSprite.class);
        when(restoredPlayer.getCode()).thenReturn("p1");
        when(restoredPlayer.getRingCount()).thenReturn(3);
        manager.spriteManager.addSprite(restoredPlayer);
        coordinator.restore(snapshot);

        manager.frameCounter = 41;
        coordinator.processPending();

        verify(rings).spawnLostRingsWithInitialObjectStep(
                restoredPlayer, 3, 41, 0x120, 0x90, new int[] {4, 5, 6}, true, true);
    }
}
