package com.openggf.data;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRomLocationResolver {

    @TempDir
    Path configDirectory;

    @Test
    void retainsLiteralConfiguredValueAndResolvedPathWithoutOpeningTheFile() {
        RomLocation location = new RomLocation(
                RomGame.S1,
                "./missing/../sonic.gen",
                Path.of("/workspace/sonic.gen"),
                RomLocationSource.CONFIGURATION,
                RomFingerprintPolicy.NONE);

        assertEquals("./missing/../sonic.gen", location.configuredValue());
        assertEquals(Path.of("/workspace/sonic.gen"), location.resolvedPath());
    }

    @Test
    void rejectsNullRecordFields() {
        assertThrows(NullPointerException.class, () -> new RomLocation(
                null,
                "sonic.gen",
                Path.of("/workspace/sonic.gen"),
                RomLocationSource.CONFIGURATION,
                RomFingerprintPolicy.NONE));
        assertThrows(NullPointerException.class, () -> new RomLocation(
                RomGame.S1,
                null,
                Path.of("/workspace/sonic.gen"),
                RomLocationSource.CONFIGURATION,
                RomFingerprintPolicy.NONE));
        assertThrows(NullPointerException.class, () -> new RomLocation(
                RomGame.S1,
                "sonic.gen",
                null,
                RomLocationSource.CONFIGURATION,
                RomFingerprintPolicy.NONE));
        assertThrows(NullPointerException.class, () -> new RomLocation(
                RomGame.S1,
                "sonic.gen",
                Path.of("/workspace/sonic.gen"),
                null,
                RomFingerprintPolicy.NONE));
        assertThrows(NullPointerException.class, () -> new RomLocation(
                RomGame.S1,
                "sonic.gen",
                Path.of("/workspace/sonic.gen"),
                RomLocationSource.CONFIGURATION,
                null));
    }

    @Test
    void resolvesEachRomFamilyFromItsMatchingConfigurationKey() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_1_ROM, "sonic-one.gen");
        configuration.setConfigValue(SonicConfiguration.SONIC_2_ROM, "sonic-two.gen");
        configuration.setConfigValue(SonicConfiguration.SONIC_3K_ROM, "sonic-three-k.gen");
        Path workingDirectory = Path.of("/rom-work");
        RomLocationResolver resolver = new RomLocationResolver(configuration, workingDirectory);

        assertLocation(resolver.resolve(RomGame.S1).orElseThrow(), RomGame.S1,
                "sonic-one.gen", workingDirectory.resolve("sonic-one.gen"));
        assertLocation(resolver.resolve(RomGame.S2).orElseThrow(), RomGame.S2,
                "sonic-two.gen", workingDirectory.resolve("sonic-two.gen"));
        assertLocation(resolver.resolve(RomGame.S3K).orElseThrow(), RomGame.S3K,
                "sonic-three-k.gen", workingDirectory.resolve("sonic-three-k.gen"));
    }

    @Test
    void returnsEmptyForBlankConfigurationValues() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_1_ROM, "");
        configuration.setConfigValue(SonicConfiguration.SONIC_2_ROM, " \t");
        configuration.setConfigValue(SonicConfiguration.SONIC_3K_ROM, "\n");
        RomLocationResolver resolver = new RomLocationResolver(configuration, Path.of("/rom-work"));

        assertTrue(resolver.resolve(RomGame.S1).isEmpty());
        assertTrue(resolver.resolve(RomGame.S2).isEmpty());
        assertTrue(resolver.resolve(RomGame.S3K).isEmpty());
    }

    @Test
    void resolvesMissingRelativePathAgainstInjectedWorkingDirectory() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_1_ROM, "./missing/../sonic.gen");
        RomLocationResolver resolver = new RomLocationResolver(configuration, Path.of("/rom-work"));

        RomLocation location = resolver.resolve(RomGame.S1).orElseThrow();

        assertEquals("./missing/../sonic.gen", location.configuredValue());
        assertEquals(Path.of("/rom-work/sonic.gen"), location.resolvedPath());
    }

    @Test
    void normalizesAbsoluteConfiguredPathsWithoutRebasingThem() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_2_ROM, "/configured/../sonic.gen");
        RomLocationResolver resolver = new RomLocationResolver(configuration, Path.of("/rom-work"));

        RomLocation location = resolver.resolve(RomGame.S2).orElseThrow();

        assertEquals(Path.of("/sonic.gen"), location.resolvedPath());
    }

    @Test
    void normalizesRelativeInjectedWorkingDirectoryAtConstruction() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_3K_ROM, "sonic3k.gen");
        Path relativeWorkingDirectory = Path.of("resolver-work/../resolver-base");
        RomLocationResolver resolver = new RomLocationResolver(configuration, relativeWorkingDirectory);

        RomLocation location = resolver.resolve(RomGame.S3K).orElseThrow();

        assertEquals(relativeWorkingDirectory.toAbsolutePath().normalize().resolve("sonic3k.gen"),
                location.resolvedPath());
    }

    @Test
    void explicitLocationRetainsPathTextAndOverrideProvenance() {
        RomLocationResolver resolver = new RomLocationResolver(configuration(), Path.of("/rom-work"));
        Path explicitPath = Path.of("manual/../manual-sonic.gen");

        RomLocation location = resolver.explicit(RomGame.S2, explicitPath);

        assertEquals(explicitPath.toString(), location.configuredValue());
        assertEquals(Path.of("/rom-work/manual-sonic.gen"), location.resolvedPath());
        assertEquals(RomLocationSource.EXPLICIT_OVERRIDE, location.source());
        assertEquals(RomFingerprintPolicy.NONE, location.fingerprintPolicy());
    }

    @Test
    void rejectsNullResolverInputs() {
        SonicConfigurationService configuration = configuration();
        assertThrows(NullPointerException.class, () -> new RomLocationResolver(null, Path.of("/rom-work")));
        assertThrows(NullPointerException.class, () -> new RomLocationResolver(configuration, null));

        RomLocationResolver resolver = new RomLocationResolver(configuration, Path.of("/rom-work"));
        assertThrows(NullPointerException.class, () -> resolver.resolve(null));
        assertThrows(NullPointerException.class, () -> resolver.explicit(null, Path.of("manual.gen")));
        assertThrows(NullPointerException.class, () -> resolver.explicit(RomGame.S1, null));
    }

    @Test
    void factoryCapturesUserDirectoryAtConstruction() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_1_ROM, "sonic.gen");
        Path capturedWorkingDirectory = configDirectory.resolve("factory-working-directory").toAbsolutePath();
        String originalUserDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", capturedWorkingDirectory.toString());
            RomLocationResolver resolver = RomLocationResolver.forCurrentWorkingDirectory(configuration);
            System.setProperty("user.dir", configDirectory.resolve("later-working-directory").toString());

            RomLocation location = resolver.resolve(RomGame.S1).orElseThrow();

            assertEquals(capturedWorkingDirectory.resolve("sonic.gen"), location.resolvedPath());
        } finally {
            restoreUserDirectory(originalUserDirectory);
        }
    }

    @Test
    void factoryFallsBackToProcessWorkingDirectoryWhenUserDirectoryIsUnavailable() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_2_ROM, "sonic.gen");
        Path expectedWorkingDirectory = Path.of("").toAbsolutePath().normalize();
        String originalUserDirectory = System.getProperty("user.dir");
        try {
            System.clearProperty("user.dir");
            RomLocation location = RomLocationResolver.forCurrentWorkingDirectory(configuration)
                    .resolve(RomGame.S2)
                    .orElseThrow();

            assertEquals(expectedWorkingDirectory.resolve("sonic.gen"), location.resolvedPath());
        } finally {
            restoreUserDirectory(originalUserDirectory);
        }
    }

    @Test
    void factoryFallsBackToProcessWorkingDirectoryWhenUserDirectoryIsBlank() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_3K_ROM, "sonic.gen");
        Path expectedWorkingDirectory = Path.of("").toAbsolutePath().normalize();
        String originalUserDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", " \t");
            RomLocation location = RomLocationResolver.forCurrentWorkingDirectory(configuration)
                    .resolve(RomGame.S3K)
                    .orElseThrow();

            assertEquals(expectedWorkingDirectory.resolve("sonic.gen"), location.resolvedPath());
        } finally {
            restoreUserDirectory(originalUserDirectory);
        }
    }

    @Test
    void preservesCapturedWorkingDirectoryAfterUserDirectoryChanges() {
        SonicConfigurationService configuration = configuration();
        configuration.setConfigValue(SonicConfiguration.SONIC_1_ROM, "sonic.gen");
        Path capturedWorkingDirectory = Path.of("resolver-captured-work").toAbsolutePath().normalize();
        RomLocationResolver resolver = new RomLocationResolver(configuration, capturedWorkingDirectory);
        String originalUserDirectory = System.getProperty("user.dir");
        try {
            System.setProperty("user.dir", "/a-different-working-directory");

            RomLocation location = resolver.resolve(RomGame.S1).orElseThrow();

            assertEquals(capturedWorkingDirectory.resolve("sonic.gen"), location.resolvedPath());
        } finally {
            restoreUserDirectory(originalUserDirectory);
        }
    }

    private SonicConfigurationService configuration() {
        return SonicConfigurationService.createStandalone(configDirectory);
    }

    private static void restoreUserDirectory(String originalUserDirectory) {
        if (originalUserDirectory == null) {
            System.clearProperty("user.dir");
        } else {
            System.setProperty("user.dir", originalUserDirectory);
        }
    }

    private static void assertLocation(RomLocation location, RomGame game, String configuredValue,
                                       Path resolvedPath) {
        assertEquals(game, location.game());
        assertEquals(configuredValue, location.configuredValue());
        assertEquals(resolvedPath, location.resolvedPath());
        assertEquals(RomLocationSource.CONFIGURATION, location.source());
        assertEquals(RomFingerprintPolicy.NONE, location.fingerprintPolicy());
    }
}
