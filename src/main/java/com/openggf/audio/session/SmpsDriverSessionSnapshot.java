package com.openggf.audio.session;

import com.openggf.audio.rewind.SmpsSourceDescriptor;

import java.util.Objects;

public record SmpsDriverSessionSnapshot(
        boolean initialized,
        SmpsPendingGlobalCommand pendingGlobalCommand,
        SmpsSessionProfileFingerprint profile,
        SmpsSourceDescriptor selectedDacSource,
        boolean speedShoesEnabled,
        int speedMultiplier,
        boolean ringLeft,
        int musicFmDacTrackCount,
        SmpsSegaPcmTransportSnapshot segaPcmTransport,
        SmpsPhysicalDevice.Snapshot physical) {
    public SmpsDriverSessionSnapshot {
        Objects.requireNonNull(pendingGlobalCommand,
                "pendingGlobalCommand");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(physical, "physical");
        if (musicFmDacTrackCount < 0) {
            throw new IllegalArgumentException("musicFmDacTrackCount must be non-negative");
        }
        if (speedMultiplier < 1) {
            throw new IllegalArgumentException(
                    "speedMultiplier must be positive");
        }
    }
}
