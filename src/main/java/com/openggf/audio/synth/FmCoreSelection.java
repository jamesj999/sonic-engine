package com.openggf.audio.synth;

import java.util.Locale;

/** Which FM synthesis core a {@link VirtualSynthesizer} drives. */
public enum FmCoreSelection {
    /** Cycle-exact Nuked-OPN2 port ({@link Ym2612Chip}); the parity oracle. */
    ACCURATE("accurate"),
    /** Register-level clean-room core behind {@link FastYm2612Chip}. */
    FAST("fast");

    private final String configValue;

    FmCoreSelection(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    /** Parses the {@code audio.fmCore} value; unknown values select the accurate core. */
    public static FmCoreSelection fromConfig(String value) {
        if (value == null) {
            return ACCURATE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (FmCoreSelection selection : values()) {
            if (selection.configValue.equals(normalized)) {
                return selection;
            }
        }
        return ACCURATE;
    }
}
