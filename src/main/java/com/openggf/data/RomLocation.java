package com.openggf.data;

import java.nio.file.Path;
import java.util.Objects;

public record RomLocation(
        RomGame game,
        String configuredValue,
        Path resolvedPath,
        RomLocationSource source,
        RomFingerprintPolicy fingerprintPolicy) {

    public RomLocation {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(configuredValue, "configuredValue");
        Objects.requireNonNull(resolvedPath, "resolvedPath");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fingerprintPolicy, "fingerprintPolicy");
    }
}
