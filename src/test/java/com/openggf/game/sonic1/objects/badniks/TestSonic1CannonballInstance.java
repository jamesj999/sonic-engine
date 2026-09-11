package com.openggf.game.sonic1.objects.badniks;

import com.openggf.camera.Camera;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class TestSonic1CannonballInstance {

    @Test
    void slopeDirectionUsesObjFloorDistFlipAwareAngle() throws Exception {
        Camera camera = mock(Camera.class);
        when(camera.getMaxY()).thenReturn((short) 0x700);

        Sonic1CannonballInstance ball = new Sonic1CannonballInstance(0x1EF5, 0x04D8, 0x100, 6);
        ball.setServices(new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }
        });

        TerrainCheckResult rawAngle = new TerrainCheckResult(-1, (byte) 0x20, 0);
        TerrainCheckResult romTransformedAngle = new TerrainCheckResult(-1, (byte) 0xE0, 0);
        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x1EF6, 0x04D8, 7))
                    .thenReturn(rawAngle);
            terrain.when(() -> ObjectTerrainUtils.checkFloorDistWithFlipAwareAngle(0x1EF6, 0x04D8, 7))
                    .thenReturn(romTransformedAngle);

            ball.update(1, null);
        }

        assertEquals(-0x100, getPrivateInt(ball, "xVelocity"),
                "ObjFloorDist returns the chunk-flip-transformed ascending angle, so a right-moving ball reverses");
    }

    private static int getPrivateInt(Object target, String name) throws Exception {
        Field field = Sonic1CannonballInstance.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }
}
