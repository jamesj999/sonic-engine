package com.openggf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestEngineNativeLibDiscovery {

    @TempDir
    Path tempDir;

    @Test
    void nativeLibsAreResolvedFromExecutableDirectory() throws IOException {
        Path packageDir = Files.createDirectory(tempDir.resolve("package"));
        Path executable = Files.createFile(packageDir.resolve("OpenGGF"));
        Files.createFile(packageDir.resolve("lwjgl.dll"));

        assertEquals(
                packageDir.toFile().getCanonicalPath(),
                Engine.findNativeLibsDirForTesting(null, executable.toString()));
    }

    @Test
    void macosLauncherEnvHintMustMatchExecutableDirectory() throws IOException {
        Path appMacOsDir = Files.createDirectories(tempDir.resolve("OpenGGF.app").resolve("Contents").resolve("MacOS"));
        Path executable = Files.createFile(appMacOsDir.resolve("OpenGGF.bin"));
        Files.createFile(appMacOsDir.resolve("liblwjgl.dylib"));

        assertEquals(
                appMacOsDir.toFile().getCanonicalPath(),
                Engine.findNativeLibsDirForTesting(appMacOsDir.toString(), executable.toString()));
    }

    @Test
    void envHintOutsideExecutableDirectoryIsRejected() throws IOException {
        Path packageDir = Files.createDirectory(tempDir.resolve("package"));
        Path executable = Files.createFile(packageDir.resolve("OpenGGF"));
        Path untrustedDir = Files.createDirectory(tempDir.resolve("untrusted"));
        Files.createFile(untrustedDir.resolve("lwjgl.dll"));

        assertNull(Engine.findNativeLibsDirForTesting(untrustedDir.toString(), executable.toString()));
    }

    @Test
    void cwdStyleNativeLibDirectoryIsRejectedWhenExecutableDirectoryHasNoLibraries() throws IOException {
        Path packageDir = Files.createDirectory(tempDir.resolve("package"));
        Path executable = Files.createFile(packageDir.resolve("OpenGGF"));
        Path cwdTargetLibs = Files.createDirectories(tempDir.resolve("target").resolve("native-libs"));
        Files.createFile(cwdTargetLibs.resolve("lwjgl.dll"));

        assertNull(Engine.findNativeLibsDirForTesting(cwdTargetLibs.toString(), executable.toString()));
    }
}
