package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestSonicConfigurationSessionOverrides {

    @TempDir
    Path tempDir;

    @Test
    void typedAccessorsReadSessionOverridesBeforePersistedValues() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        cfg.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
        cfg.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        cfg.setConfigValue(SonicConfiguration.FPS, 60);

        cfg.setSessionOverride(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        cfg.setSessionOverride(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        cfg.setSessionOverride(SonicConfiguration.FPS, 120);

        assertTrue(cfg.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED));
        assertEquals("knuckles", cfg.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals(120, cfg.getInt(SonicConfiguration.FPS));
    }

    @Test
    void sessionOverridesReadBeforeTransientResolvedValues() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        cfg.resolveDisplayAspect();
        assertEquals(400, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));

        cfg.setSessionOverride(SonicConfiguration.SCREEN_WIDTH_PIXELS, 528);

        assertEquals(528, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
    }

    @Test
    void sessionAspectOverridesPersistedAspectDuringResolution() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        cfg.resolveDisplayAspect();
        assertEquals(320, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));

        cfg.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        cfg.resolveDisplayAspect();

        assertEquals("WIDE_16_9", cfg.getString(SonicConfiguration.DISPLAY_ASPECT));
        assertEquals(400, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
    }

    @Test
    void setSessionOverrideClearsCachedInts() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        cfg.resolveDisplayAspect();
        assertEquals(320, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));

        cfg.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        cfg.resolveDisplayAspect();

        assertEquals(400, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
    }

    @Test
    void clearSessionOverridesRestoresPersistedValuesAndClearsCachedInts() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        cfg.setConfigValue(SonicConfiguration.FPS, 60);
        cfg.setSessionOverride(SonicConfiguration.FPS, 120);
        assertEquals(120, cfg.getInt(SonicConfiguration.FPS));

        cfg.clearSessionOverrides();

        assertFalse(cfg.hasSessionOverride(SonicConfiguration.FPS));
        assertEquals(60, cfg.getInt(SonicConfiguration.FPS));
    }

    @Test
    void clearSessionOverridesRestoresPersistedDisplayResolution() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        cfg.resolveDisplayAspect();
        cfg.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        cfg.resolveDisplayAspect();
        assertEquals(400, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));

        cfg.clearSessionOverrides();
        cfg.resolveDisplayAspect();

        assertEquals(320, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
    }

    @Test
    void saveConfigDoesNotPersistSessionOverrides() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        cfg.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
        cfg.setSessionOverride(SonicConfiguration.LIVE_REWIND_ENABLED, true);

        assertTrue(cfg.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED));
        cfg.saveConfig();

        SonicConfigurationService reloaded = SonicConfigurationService.createStandalone(tempDir);
        assertFalse(reloaded.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED));
    }

    @Test
    void savedYamlExcludesSessionOverrideOnlyValues() throws Exception {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        cfg.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        cfg.setSessionOverride(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");

        cfg.saveConfig();

        String yaml = Files.readString(tempDir.resolve("config.yaml"));
        assertTrue(yaml.contains("main: \"sonic\""), yaml);
        assertFalse(yaml.contains("main: \"knuckles\""), yaml);
    }

    @Test
    void resetToDefaultsClearsSessionOverrides() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        cfg.setSessionOverride(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        assertTrue(cfg.hasSessionOverride(SonicConfiguration.LIVE_REWIND_ENABLED));
        assertTrue(cfg.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED));

        cfg.resetToDefaults();

        assertFalse(cfg.hasSessionOverride(SonicConfiguration.LIVE_REWIND_ENABLED));
        assertFalse(cfg.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED));
    }
}
