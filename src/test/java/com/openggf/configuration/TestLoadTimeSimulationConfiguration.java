package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestLoadTimeSimulationConfiguration {

    @Test
    void defaultsToFast(@TempDir Path emptyConfigDirectory) {
        // An empty directory isolates the built-in default from whatever
        // config.yaml the working directory happens to carry.
        SonicConfigurationService config =
                SonicConfigurationService.createStandalone(emptyConfigDirectory);

        assertEquals("FAST",
                config.getString(SonicConfiguration.LOAD_TIME_SIMULATION));
    }

    @Test
    void catalogUsesGameplayPathAndSupportedValues() {
        ConfigKeyMeta meta = ConfigCatalog.meta(
                SonicConfiguration.LOAD_TIME_SIMULATION);

        assertEquals("gameplay.loadTimeSimulation", meta.path());
        assertEquals(
                java.util.Set.of("NONE", "PROFILED", "FAST", "REALISTIC"),
                meta.allowedValues());
    }
}
