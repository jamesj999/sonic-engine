package com.openggf.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestDisplayAspectResolution {

    @Test
    void nativeDefaultLeavesScreenWidthPixels320() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        cfg.resolveDisplayAspect();
        assertEquals(320, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(224, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS));
        assertEquals(640, cfg.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(448, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT));
    }

    @Test
    void wide169WithAutosizeWidensPixelsAndWindow() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        cfg.resolveDisplayAspect();
        assertEquals(400, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(800, cfg.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(448, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT));
    }

    @Test
    void autosizeOffPreservesWindow() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        cfg.setConfigValue(SonicConfiguration.DISPLAY_WINDOW_AUTOSIZE, false);
        cfg.setConfigValue(SonicConfiguration.SCREEN_WIDTH, 1024);
        cfg.setConfigValue(SonicConfiguration.SCREEN_HEIGHT, 768);
        cfg.resolveDisplayAspect();
        assertEquals(400, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(1024, cfg.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(768, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT));
    }

    @Test
    void derivedPixelWidthIsNotPersistedToDefaults() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        cfg.resolveDisplayAspect();
        assertEquals(400, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(320, ((Number) cfg.getDefaultValue(SonicConfiguration.SCREEN_WIDTH_PIXELS)).intValue());
    }

    @Test
    void testModeForcesNativeRegardlessOfAspect() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "ULTRA_21_9");
        cfg.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, true);
        cfg.resolveDisplayAspect();
        assertEquals(320, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
    }

    @Test
    void switchingFromWidescreenAutosizeToPreserveClearsDerivedWindow() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.SCREEN_WIDTH, 1024);
        cfg.setConfigValue(SonicConfiguration.SCREEN_HEIGHT, 768);
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        cfg.setConfigValue(SonicConfiguration.DISPLAY_WINDOW_AUTOSIZE, true);
        cfg.resolveDisplayAspect();
        assertEquals(800, cfg.getInt(SonicConfiguration.SCREEN_WIDTH)); // derived overlay
        // Turn autosize off and re-resolve: derived window cleared, persisted 1024 surfaces.
        cfg.setConfigValue(SonicConfiguration.DISPLAY_WINDOW_AUTOSIZE, false);
        cfg.resolveDisplayAspect();
        assertEquals(1024, cfg.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(768, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT));
        assertEquals(400, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS)); // pixel still derived
    }
}
