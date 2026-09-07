package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestConfigServiceYamlRoundTrip {

    @TempDir
    Path tempDir;

    @Test
    void saveWritesGroupedYaml() throws Exception {
        Path yaml = tempDir.resolve("config.yaml");
        SonicConfigurationService svc = SonicConfigurationService.createStandalone(tempDir);
        svc.setConfigValue(SonicConfiguration.FPS, 50);
        svc.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, true);
        svc.saveConfig();
        assertTrue(Files.exists(yaml), "saveConfig must write config.yaml");
        String text = Files.readString(yaml);
        assertTrue(text.contains("display:"), text);
        assertTrue(text.contains("debug:"), text);
        assertFalse(text.contains("audio:"), "sections without a user setting are not written");
        assertFalse(text.contains("SCREEN_WIDTH_PIXELS"), "derived keys must not be persisted");
    }

    @Test
    void loadReadsBackWrittenValues() throws Exception {
        SonicConfigurationService svc = SonicConfigurationService.createStandalone(tempDir);
        svc.setConfigValue(SonicConfiguration.AUDIO_ENABLED, false);
        svc.saveConfig();

        SonicConfigurationService reloaded = SonicConfigurationService.createStandalone(tempDir);
        assertFalse(reloaded.getBoolean(SonicConfiguration.AUDIO_ENABLED));
    }
}
