package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openggf.camera.DeadzoneGeometry;
import com.openggf.configuration.DeadzoneMode;
import com.openggf.configuration.DisplayWindowPolicy;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.configuration.WidescreenAspect;
import com.openggf.sprites.managers.RightBoundary;
import org.junit.jupiter.api.Test;

/** Pins that the default (NATIVE_4_3) path reproduces every historical constant. */
class TestWidescreenNativeRegression {

    @Test
    void nativeAspectResolvesToNativeDimensions() {
        // Pin the NATIVE_4_3 path explicitly — do NOT rely on the ambient
        // config.yaml, which a developer may have set to a widescreen preset.
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        cfg.setConfigValue(SonicConfiguration.SCREEN_WIDTH, 640);
        cfg.setConfigValue(SonicConfiguration.SCREEN_HEIGHT, 448);
        cfg.resolveDisplayAspect();
        assertEquals(320, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(224, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS));
        assertEquals(640, cfg.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(448, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT));
    }

    @Test
    void testModeForcesNativeEvenWithWidescreenConfig() {
        // The parity safeguard: TEST_MODE_ENABLED forces native regardless of a
        // widescreen DISPLAY_ASPECT in the ambient config — protecting trace tests.
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "ULTRA_21_9");
        cfg.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, true);
        cfg.resolveDisplayAspect();
        assertEquals(320, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
    }

    @Test
    void nativeCameraConstantsUnchanged() {
        for (DeadzoneMode mode : DeadzoneMode.values()) {
            assertEquals(144, DeadzoneGeometry.leftEdge(320, mode));
            // rightEdge(320) == 160 is both the scroll trigger and the focus-snap rest point.
            assertEquals(160, DeadzoneGeometry.rightEdge(320));
        }
    }

    @Test
    void nativeBoundaryConstantsUnchanged() {
        assertEquals(1000 + 0x128, RightBoundary.compute(1000, 320, 24, 64, true));
        assertEquals(1000 + 0x128 + 0x40, RightBoundary.compute(1000, 320, 24, 64, false));
    }

    @Test
    void nativeWindowPolicyUnchanged() {
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.NATIVE_4_3, true, 640, 448);
        assertEquals(320, r.pixelWidth());
        assertEquals(640, r.windowWidth());
        assertEquals(448, r.windowHeight());
    }
}
