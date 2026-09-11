package com.openggf.audio.synth;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TestFastFmConfiguration {
    @TempDir Path directory;

    @Test
    void newConfigurationSelectsFastAndExplicitAccurateChoiceSurvivesReload() throws Exception {
        SonicConfigurationService fresh = SonicConfigurationService.createStandalone(directory);
        assertEquals("fast", fresh.getString(SonicConfiguration.AUDIO_FM_CORE));
        fresh.setConfigValue(SonicConfiguration.AUDIO_FM_CORE, "accurate");
        fresh.saveConfig();
        SonicConfigurationService reloaded = SonicConfigurationService.createStandalone(directory);
        assertEquals("accurate", reloaded.getString(SonicConfiguration.AUDIO_FM_CORE));
        assertTrue(Files.exists(directory.resolve("config.yaml")));
    }

}
