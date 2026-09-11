package com.openggf.audio.smps;

import java.util.Objects;

/** A decoded ROM music program together with its hardware load-readiness work. */
public record LoadedSmpsMusic(
        AbstractSmpsData data, SmpsLoadReadiness readiness) {
    public LoadedSmpsMusic {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(readiness, "readiness");
    }

    public static LoadedSmpsMusic immediate(AbstractSmpsData data) {
        return data == null ? null : new LoadedSmpsMusic(
                data, SmpsLoadReadiness.immediatePlan());
    }
}
