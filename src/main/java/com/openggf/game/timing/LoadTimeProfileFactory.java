package com.openggf.game.timing;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Resolves optional readiness admission for work submitted through
 * {@link HardwareTimingService}. Game-owned PLC and dynamic-art lifecycle
 * services do not consume this profile.
 */
public final class LoadTimeProfileFactory {
    private LoadTimeProfileFactory() {
    }

    /** Resolves for a game without a FAST manifest: FAST warns and behaves as NONE. */
    public static LoadTimeProfile resolve(
            LoadTimeSimulationMode mode,
            LoadTimeProfile profiled,
            Consumer<String> warningSink) {
        return resolve(mode, profiled, null, warningSink);
    }

    /**
     * Resolves the normal-play profile. {@code fast} is the game's hand-tuned
     * FAST manifest, seeded from its measured PROFILED data; a game without one
     * passes {@code null}, and FAST then warns once per resolution and behaves
     * as NONE. REALISTIC stays reserved and uses the PROFILED data.
     */
    public static LoadTimeProfile resolve(
            LoadTimeSimulationMode mode,
            LoadTimeProfile profiled,
            LoadTimeProfile fast,
            Consumer<String> warningSink) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(profiled, "profiled");
        Objects.requireNonNull(warningSink, "warningSink");
        return switch (mode) {
            case NONE -> LoadTimeProfile.IMMEDIATE;
            case PROFILED -> profiled;
            case FAST -> {
                if (fast != null) {
                    yield fast;
                }
                warningSink.accept(
                        "FAST load-time simulation has no manifest for this game; using NONE");
                yield LoadTimeProfile.IMMEDIATE;
            }
            case REALISTIC -> {
                warningSink.accept(
                        "REALISTIC load-time simulation is reserved; no independent REALISTIC "
                                + "hardware-admission profile exists, using PROFILED");
                yield profiled;
            }
        };
    }
}
