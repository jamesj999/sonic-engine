package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameRateResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void palResolvesToFiftyEvenWhenConfiguredFpsIsSixty() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.REGION, "PAL");
        config.setConfigValue(SonicConfiguration.FPS, 60);

        assertEquals(50, FrameRateResolver.effective(config));
    }

    @Test
    void ntscResolvesConfiguredFps() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.REGION, "NTSC");
        config.setConfigValue(SonicConfiguration.FPS, 120);

        assertEquals(120, FrameRateResolver.effective(config));
    }

    @Test
    void zeroLoadedFpsIsSanitizedBeforeResolution() throws Exception {
        Files.writeString(tempDir.resolve("config.yaml"), """
                display:
                  fps: 0
                audio:
                  region: NTSC
                """);
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);

        assertEquals(1, config.getInt(SonicConfiguration.FPS));
        assertEquals(1, FrameRateResolver.effective(config));
    }

    @Test
    void negativeLoadedFpsIsSanitizedBeforeResolution() throws Exception {
        Files.writeString(tempDir.resolve("config.yaml"), """
                display:
                  fps: -60
                audio:
                  region: NTSC
                """);
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);

        assertEquals(1, config.getInt(SonicConfiguration.FPS));
        assertEquals(1, FrameRateResolver.effective(config));
    }
}
