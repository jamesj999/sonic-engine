package com.openggf.audio.session;

import java.util.Objects;

/** Host-driver side effects accompanying a music fade command. */
public record SmpsFadeOutEffects(
        boolean driverOwnedCounters,
        boolean stopSfx,
        boolean clearSpeedShoes,
        SmpsWriteProgram psgSilence) {
    public static final SmpsFadeOutEffects NONE = new SmpsFadeOutEffects(
            false, false, false, SmpsWriteProgram.EMPTY);

    public SmpsFadeOutEffects {
        Objects.requireNonNull(psgSilence, "psgSilence");
        if (psgSilence.writes().stream().anyMatch(w -> !(w instanceof SmpsChipWrite.Psg))) {
            throw new IllegalArgumentException("fade silence accepts only PSG writes");
        }
    }
}
