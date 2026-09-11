package com.openggf.game.sonic2;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.level.Pattern;
import com.openggf.level.WaterSystem;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2WaterSurfaceManager {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void shouldRenderWaterSurfaceOnlyForCpzAct2AndArzActs() {
        TestEnvironment.activeGameplayMode();
        WaterSystem waterSystem = GameServices.water();
        setWaterConfig(waterSystem, 0x0D, 0, true);
        setWaterConfig(waterSystem, 0x0D, 1, true);
        setWaterConfig(waterSystem, 0x0F, 0, true);
        setWaterConfig(waterSystem, 0x0F, 1, true);

        WaterSurfaceManager cpzAct1 = new WaterSurfaceManager(0x0D, 0, patterns(24), patterns(16));
        WaterSurfaceManager cpzAct2 = new WaterSurfaceManager(0x0D, 1, patterns(24), patterns(16));
        WaterSurfaceManager arzAct1 = new WaterSurfaceManager(0x0F, 0, patterns(24), patterns(16));
        WaterSurfaceManager arzAct2 = new WaterSurfaceManager(0x0F, 1, patterns(24), patterns(16));

        assertFalse(cpzAct1.shouldRenderWaterSurface());
        assertTrue(cpzAct2.shouldRenderWaterSurface());
        assertTrue(arzAct1.shouldRenderWaterSurface());
        assertTrue(arzAct2.shouldRenderWaterSurface());
    }

    @Test
    void animationFrameUsesPingPongCadence() {
        WaterSurfaceManager manager = new WaterSurfaceManager(0x0D, 1, patterns(24), patterns(16));

        assertEquals(0, manager.animationFrameForCounter(0));
        assertEquals(1, manager.animationFrameForCounter(16));
        assertEquals(2, manager.animationFrameForCounter(32));
        assertEquals(1, manager.animationFrameForCounter(48));
    }

    @Test
    void segmentAlternationSwitchesEvenAndOddSegmentsEachFrame() {
        WaterSurfaceManager manager = new WaterSurfaceManager(0x0D, 1, patterns(24), patterns(16));

        assertTrue(manager.shouldDrawSegment(0, 0));
        assertFalse(manager.shouldDrawSegment(0, 1));
        assertFalse(manager.shouldDrawSegment(1, 0));
        assertTrue(manager.shouldDrawSegment(1, 1));
    }

    private static Pattern[] patterns(int count) {
        Pattern[] patterns = new Pattern[count];
        for (int i = 0; i < count; i++) {
            patterns[i] = new Pattern();
        }
        return patterns;
    }

    @SuppressWarnings("unchecked")
    private static void setWaterConfig(WaterSystem waterSystem, int zoneId, int actId, boolean hasWater) {
        try {
            Field field = WaterSystem.class.getDeclaredField("waterConfigs");
            field.setAccessible(true);
            Map<String, WaterSystem.WaterConfig> configs =
                    (Map<String, WaterSystem.WaterConfig>) field.get(waterSystem);
            configs.put(zoneId + "_" + actId, new WaterSystem.WaterConfig(hasWater, 0x500, null));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to inject WaterSystem config", e);
        }
    }
}
