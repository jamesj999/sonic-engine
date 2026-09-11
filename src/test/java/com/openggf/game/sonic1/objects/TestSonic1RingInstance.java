package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.rings.RingManager;
import com.openggf.level.rings.RingSpawn;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

class TestSonic1RingInstance {

    @Test
    void collectedChildRingDoesNotRespawnWhenGroupReloads() {
        RingManager ringManager = mock(RingManager.class);
        Set<RingSpawn> collected = new HashSet<>();
        when(ringManager.isCollected(any(RingSpawn.class))).thenAnswer(invocation ->
                collected.contains(invocation.getArgument(0)));
        doAnswer(invocation -> {
            collected.add(invocation.getArgument(0));
            return true;
        }).when(ringManager).collectPlacedRing(any(RingSpawn.class), any(), anyInt());

        ObjectManager manager = buildManager(ringManager);

        ObjectSpawn firstSpawn = new ObjectSpawn(0x0100, 0x0200, 0x25, 0x01, 0, false, 0);
        Sonic1RingInstance firstParent = buildTwoRingGroup(firstSpawn);
        firstParent.setRespawnStateIndex(5);
        manager.addDynamicObject(firstParent);
        manager.update(0, null, List.of(), 1);

        Sonic1RingInstance firstChild = findRingAt(manager, 0x0110, 0x0200);
        assertNotNull(firstChild, "Expected child ring to spawn on the first group load");

        TouchResponseResult touch = mock(TouchResponseResult.class);
        when(touch.category()).thenReturn(TouchCategory.SPECIAL);
        PlayableEntity player = new TestPlayableSprite();
        firstChild.onTouchResponse(player, touch, 10);

        manager.removeDynamicObject(firstChild);
        manager.removeDynamicObject(firstParent);

        ObjectSpawn secondSpawn = new ObjectSpawn(0x0100, 0x0200, 0x25, 0x01, 0, false, 0);
        Sonic1RingInstance secondParent = buildTwoRingGroup(secondSpawn);
        secondParent.setRespawnStateIndex(5);
        manager.addDynamicObject(secondParent);
        manager.update(0, null, List.of(), 2);

        Sonic1RingInstance respawnedChild = findRingAt(manager, 0x0110, 0x0200);
        assertNull(respawnedChild,
                "Collected child ring should stay absent when the ring group reloads");
    }

    @Test
    void sparkleRingIgnoresOutOfRangeUntilRingDelete() {
        RingManager ringManager = mock(RingManager.class);
        when(ringManager.collectPlacedRing(any(RingSpawn.class), any(), anyInt()))
                .thenReturn(true);

        RingSpawn ringSpawn = new RingSpawn(0x0110, 0x0200);
        Sonic1RingInstance ring = new Sonic1RingInstance(
                new ObjectSpawn(0x0110, 0x0200, 0x25, 0, 0, false, 0),
                ringSpawn,
                0x0100);
        ring.setServices(new StubObjectServices() {
            @Override
            public RingManager ringManager() {
                return ringManager;
            }
        });

        assertTrue(ring.usesCustomOutOfRangeCheck());
        assertTrue(ring.isCustomOutOfRange(0x0400),
                "Ring_Animate should still use the group-anchor out_of_range macro");

        TouchResponseResult touch = mock(TouchResponseResult.class);
        when(touch.category()).thenReturn(TouchCategory.SPECIAL);
        ring.onTouchResponse(new TestPlayableSprite(), touch, 10);

        assertFalse(ring.isCustomOutOfRange(0x0400),
                "Ring_Sparkle does not call out_of_range before Ring_Delete");
    }

    private static Sonic1RingInstance buildTwoRingGroup(ObjectSpawn spawn) {
        return new Sonic1RingInstance(spawn, List.of(
                new RingSpawn(0x0100, 0x0200),
                new RingSpawn(0x0110, 0x0200)));
    }

    private static ObjectManager buildManager(RingManager ringManager) {
        ObjectManager[] holder = new ObjectManager[1];
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public RingManager ringManager() {
                return ringManager;
            }
        };

        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);

        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null, null, camera, services);
        holder[0] = manager;
        return manager;
    }

    private static Sonic1RingInstance findRingAt(ObjectManager manager, int x, int y) {
        for (ObjectInstance instance : manager.getActiveObjects()) {
            if (instance instanceof Sonic1RingInstance ring
                    && ring.getX() == x
                    && ring.getY() == y
                    && !ring.isDestroyed()) {
                return ring;
            }
        }
        return null;
    }
}
