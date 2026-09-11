package com.openggf.tools;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestTraceToolRomLocations {

    @TempDir
    Path configurationDirectory;

    @Test
    void resolvesEachStrictGameIdAgainstTheInjectedWorkingDirectory() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_1_ROM, "s1/../sonic-one.gen");
        configuration.setConfigValue(SonicConfiguration.SONIC_2_ROM, "s2/../sonic-two.gen");
        configuration.setConfigValue(SonicConfiguration.SONIC_3K_ROM, "s3k/../sonic-three-k.gen");
        Path workingDirectory = Path.of("/trace-tool-work");

        assertEquals(Path.of("/trace-tool-work/sonic-one.gen"),
                TraceToolRomLocations.resolve("s1", configuration, workingDirectory));
        assertEquals(Path.of("/trace-tool-work/sonic-two.gen"),
                TraceToolRomLocations.resolve("s2", configuration, workingDirectory));
        assertEquals(Path.of("/trace-tool-work/sonic-three-k.gen"),
                TraceToolRomLocations.resolve("s3k", configuration, workingDirectory));
    }

    @Test
    void returnsANonblankMissingConfiguredPathWithoutSelectingADefault() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_2_ROM,
                "missing/../configured-but-missing.gen");

        Path resolved = TraceToolRomLocations.resolve("s2", configuration,
                Path.of("/trace-tool-work"));

        assertEquals(Path.of("/trace-tool-work/configured-but-missing.gen"), resolved);
    }

    @Test
    void rejectsBlankConfigurationAtTheTraceToolBoundary() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_3K_ROM, " \t");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TraceToolRomLocations.resolve("s3k", configuration,
                        Path.of("/trace-tool-work")));

        assertEquals("No ROM configured for game: s3k", failure.getMessage());
    }

    @Test
    void preservesStrictUnknownAndNullGameIdFailures() {
        SonicConfigurationService configuration = configuration();

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> TraceToolRomLocations.resolve("unknown", configuration,
                        Path.of("/trace-tool-work")));
        IllegalArgumentException nullId = assertThrows(IllegalArgumentException.class,
                () -> TraceToolRomLocations.resolve(null, configuration,
                        Path.of("/trace-tool-work")));

        assertEquals("Unknown game: unknown", unknown.getMessage());
        assertEquals("Unknown game: null", nullId.getMessage());
    }

    private SonicConfigurationService configuration() {
        return SonicConfigurationService.createStandalone(configurationDirectory);
    }
}
