package com.openggf.data;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class RomLocationResolver {
    private final SonicConfigurationService configuration;
    private final Path workingDirectory;

    public RomLocationResolver(SonicConfigurationService configuration, Path workingDirectory) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
    }

    public static RomLocationResolver forCurrentWorkingDirectory(
            SonicConfigurationService configuration) {
        String userDirectory = System.getProperty("user.dir");
        Path workingDirectory = userDirectory == null || userDirectory.isBlank()
                ? Path.of("").toAbsolutePath()
                : Path.of(userDirectory);
        return new RomLocationResolver(configuration, workingDirectory);
    }

    public Optional<RomLocation> resolve(RomGame game) {
        Objects.requireNonNull(game, "game");
        String configuredValue = configuration.getString(configurationKey(game));
        if (configuredValue == null || configuredValue.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new RomLocation(
                game,
                configuredValue,
                resolvePath(Path.of(configuredValue)),
                RomLocationSource.CONFIGURATION,
                RomFingerprintPolicy.NONE));
    }

    public RomLocation explicit(RomGame game, Path path) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(path, "path");
        return new RomLocation(
                game,
                path.toString(),
                resolvePath(path),
                RomLocationSource.EXPLICIT_OVERRIDE,
                RomFingerprintPolicy.NONE);
    }

    private SonicConfiguration configurationKey(RomGame game) {
        return switch (game) {
            case S1 -> SonicConfiguration.SONIC_1_ROM;
            case S2 -> SonicConfiguration.SONIC_2_ROM;
            case S3K -> SonicConfiguration.SONIC_3K_ROM;
        };
    }

    private Path resolvePath(Path path) {
        return (path.isAbsolute() ? path : workingDirectory.resolve(path)).normalize();
    }
}
