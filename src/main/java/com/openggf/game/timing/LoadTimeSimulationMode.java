package com.openggf.game.timing;

import java.util.Locale;
import java.util.Objects;

public enum LoadTimeSimulationMode {
    NONE,
    PROFILED,
    FAST,
    REALISTIC;

    public static LoadTimeSimulationMode parse(String value) {
        try {
            return valueOf(Objects.requireNonNull(value, "value")
                    .trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "loadTimeSimulation must be NONE, PROFILED, FAST, or REALISTIC",
                    exception);
        }
    }
}
