package com.openggf.audio.session;

import java.util.Objects;

public record SmpsSessionProfileFingerprint(
        String baseGameId,
        long sourceGeneration,
        SmpsPhysicalPolicy.Identity physicalPolicyId,
        SmpsPhysicalDevice.Settings settings,
        SmpsStatefulCommandPolicy.Identity statefulCommandPolicyId) {
    public SmpsSessionProfileFingerprint(
            String baseGameId,
            long sourceGeneration,
            SmpsPhysicalPolicy.Identity physicalPolicyId,
            SmpsPhysicalDevice.Settings settings) {
        this(baseGameId, sourceGeneration, physicalPolicyId, settings,
                SmpsStatefulCommandPolicy.Identity.NONE);
    }

    public SmpsSessionProfileFingerprint {
        Objects.requireNonNull(baseGameId, "baseGameId");
        if (sourceGeneration < 0) {
            throw new IllegalArgumentException(
                    "sourceGeneration must be non-negative");
        }
        Objects.requireNonNull(physicalPolicyId, "physicalPolicyId");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(statefulCommandPolicyId,
                "statefulCommandPolicyId");
    }
}
