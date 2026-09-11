package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestBlueBallsObjectInstance {

    @BeforeEach
    public void setUp() {
        GraphicsManager.getInstance().initHeadless();
        BlueBallsObjectInstance.resetGlobalState();
    }

    @AfterEach
    public void tearDown() {
        BlueBallsObjectInstance.resetGlobalState();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    public void siblingsAllocateAfterParentSlot() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = cameraAtOrigin();
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public Camera camera() {
                return camera;
            }
        };
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        ObjectSpawn spawn = new ObjectSpawn(0x80, 0x100, Sonic2ObjectIds.BLUE_BALLS,
                3, 0, false, 0);
        BlueBallsObjectInstance parent = new BlueBallsObjectInstance(spawn, "BlueBalls");
        manager.addDynamicObjectAtSlot(parent, 40);

        manager.update(0, null, List.of(), 1);

        List<BlueBallsObjectInstance> balls = manager.getActiveObjects().stream()
                .filter(BlueBallsObjectInstance.class::isInstance)
                .map(BlueBallsObjectInstance.class::cast)
                .toList();
        assertEquals(4, balls.size(), "parent plus three low-nibble siblings should be active");
        for (BlueBallsObjectInstance ball : balls) {
            if (ball != parent) {
                assertTrue(ball.getSlotIndex() > parent.getSlotIndex(), "Obj1D uses AllocateObjectAfterCurrent, so siblings must be after parent");
            }
        }
    }

    @Test
    public void placedParentWaitsOnePassAfterInitBeforeMoving() {
        ObjectManager[] holder = new ObjectManager[1];
        Camera camera = cameraAtOrigin();
        ObjectServices services = new StubObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }

            @Override
            public Camera camera() {
                return camera;
            }
        };
        ObjectManager manager = new ObjectManager(
                List.of(), null, 0, null, null,
                GraphicsManager.getInstance(), camera, services);
        holder[0] = manager;

        ObjectSpawn spawn = new ObjectSpawn(0x80, 0x100, Sonic2ObjectIds.BLUE_BALLS,
                0, 0, false, 0);
        BlueBallsObjectInstance parent = new BlueBallsObjectInstance(spawn, "BlueBalls");
        manager.addDynamicObjectAtSlot(parent, 40);

        manager.update(0, null, List.of(), 1);
        assertEquals(0x100, parent.getY(),
                "Obj1D_Init initializes and returns without running Obj1D_Wait");

        manager.update(0, null, List.of(), 2);
        assertEquals(0x100, parent.getY(),
                "Obj1D_Wait only arms routine 4; Obj1D_MoveArc starts on the following pass");
    }

    private static Camera cameraAtOrigin() {
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0);
        when(camera.getY()).thenReturn((short) 0);
        when(camera.getWidth()).thenReturn((short) 320);
        when(camera.getHeight()).thenReturn((short) 224);
        when(camera.isVerticalWrapEnabled()).thenReturn(false);
        return camera;
    }
}

