package com.openggf.physics;

import com.openggf.level.LevelManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guard for GroundSensor's per-scan service caching: the LevelManager is
 * resolved once per scan entry point (doScan/scanWorld) and threaded through every
 * tile-scan helper as a parameter, instead of re-resolving GameServices per tile
 * evaluation on the hot collision path. Behavior is intentionally unchanged;
 * functional coverage lives in TestGroundSensor and TestTerrainCollisionManager.
 */
class TestGroundSensorServiceResolution {

    private static final List<String> SCAN_HELPERS = List.of(
            "scanVertical",
            "scanTileVertical",
            "scanHorizontal",
            "scanHorizontalLayer",
            "evaluateWallTile",
            "scanWallTileSimple",
            "scanBackgroundCollision",
            "scanVerticalBg",
            "scanHorizontalBg",
            "getSolidTile");

    @Test
    void tileScanHelpersReceiveLevelManagerAsParameter() {
        for (String name : SCAN_HELPERS) {
            List<Method> methods = methodsNamed(name);
            assertFalse(methods.isEmpty(), "expected GroundSensor helper " + name);
            for (Method method : methods) {
                assertTrue(
                        Arrays.asList(method.getParameterTypes()).contains(LevelManager.class),
                        name + " must take LevelManager as a parameter instead of resolving services per tile");
            }
        }
    }

    private static List<Method> methodsNamed(String name) {
        return Arrays.stream(GroundSensor.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .toList();
    }
}
