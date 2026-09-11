package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSonic1SwingingPlatformChildSlots {

    @BeforeEach
    void setUp() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void swingingPlatformStopsBuildingWhenObjectRamIsFullAndRemainsRewindCapturable() {
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
                List.of(), new Sonic1ObjectRegistry(), 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        Sonic1SwingingPlatformObjectInstance platform =
                new Sonic1SwingingPlatformObjectInstance(
                        new ObjectSpawn(0x0100, 0x0060, 0x15, 0x07, 0, false, 0, 202));
        manager.addDynamicObjectAtSlot(platform, 110);
        manager.reserveAllButNFreeSlots(4);

        manager.update(0, null, null, 1);

        long liveLinks = manager.getActiveObjects().stream()
                .filter(Sonic1SwingingPlatformObjectInstance.SwingChainLinkChild.class::isInstance)
                .count();
        assertEquals(4, liveLinks,
                "Swing_CreateLinks must stop at the first failed FindFreeObj allocation");

        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(manager.rewindSnapshottable());
        assertDoesNotThrow(rewindRegistry::capture,
                "the parent must retain references only to managed, identity-bearing links");

        manager.removeDynamicObject(platform);
        long remainingLinks = manager.getActiveObjects().stream()
                .filter(Sonic1SwingingPlatformObjectInstance.SwingChainLinkChild.class::isInstance)
                .count();
        assertEquals(0, remainingLinks,
                "the parent must retain and unload every successfully managed link");
    }
}
