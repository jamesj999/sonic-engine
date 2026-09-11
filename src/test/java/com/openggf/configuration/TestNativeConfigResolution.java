package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestNativeConfigResolution {

    @TempDir
    Path tempDir;

    @Test
    void macosAppBundleUsesEditableSiblingConfig() throws Exception {
        Path macOsDir = Files.createDirectories(
                tempDir.resolve("OpenGGF.app").resolve("Contents").resolve("MacOS"));
        Path executable = Files.createFile(macOsDir.resolve("OpenGGF.bin"));
        Path editableConfig = Files.createFile(tempDir.resolve("config.yaml"));
        Files.createFile(macOsDir.resolve("config.yaml"));

        assertEquals(editableConfig.toFile(),
                SonicConfigurationService.resolveNativeConfigForExecutable(executable.toFile()));
    }

    @Test
    void nonBundleNativeExecutableUsesExecutableAdjacentConfig() throws Exception {
        Path binDir = Files.createDirectories(tempDir.resolve("bin"));
        Path executable = Files.createFile(binDir.resolve("OpenGGF"));

        assertEquals(binDir.resolve("config.yaml").toFile(),
                SonicConfigurationService.resolveNativeConfigForExecutable(executable.toFile()));
    }
}
