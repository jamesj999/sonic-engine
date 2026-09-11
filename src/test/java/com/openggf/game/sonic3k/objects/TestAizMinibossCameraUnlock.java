package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.level.Level;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAizMinibossCameraUnlock {
    @Test
    void levelEndUnlockDoesNotClampLaterWidenedCameraMax() throws Exception {
        Camera camera = new Camera(SonicConfigurationService.getInstance());
        camera.setMinX((short) 0x10E0);
        camera.setMaxX((short) 0x6000);

        AizMinibossInstance miniboss = new AizMinibossInstance(
                new ObjectSpawn(0x1200, 0x0200, 0x91, 0, 0, false, 0x0200));
        miniboss.setServices(new StubObjectServices() {
            @Override
            public Camera camera() {
                return camera;
            }

            @Override
            public Level currentLevel() {
                return levelWithMaxX(0x4640);
            }
        });

        Method unlock = AizMinibossInstance.class
                .getDeclaredMethod("updateLevelEndCameraUnlock", int.class);
        unlock.setAccessible(true);
        unlock.invoke(miniboss, 0x10E0);

        assertTrue(miniboss.isDestroyed(),
                "stale AIZ miniboss level-end unlock must delete instead of clamping a later camera owner");
        assertEquals(0x6000, camera.getMaxX() & 0xFFFF,
                "the miniboss handoff must not clamp a later AIZ camera owner");
    }

    private static Level levelWithMaxX(int maxX) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
            case "getMaxX" -> maxX;
            case "getMinX", "getMinY", "getMaxY", "getPaletteCount", "getPatternCount",
                    "getChunkCount", "getBlockCount", "getSolidTileCount", "getRomZoneId" -> 0;
            case "getObjects", "getRings" -> java.util.List.of();
            case "toString" -> "Level[maxX=" + maxX + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
        return (Level) Proxy.newProxyInstance(
                Level.class.getClassLoader(),
                new Class<?>[]{Level.class},
                handler);
    }
}
