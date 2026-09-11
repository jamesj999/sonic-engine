package com.openggf.audio.session;

import java.util.Objects;

/** Immutable host configuration for one persistent SMPS session. */
public record SmpsDriverSessionConfiguration(
        SmpsStatefulCommandPolicy statefulCommandPolicy) {
    public static final SmpsDriverSessionConfiguration DEFAULT =
            new SmpsDriverSessionConfiguration(SmpsStatefulCommandPolicy.NONE);

    public SmpsDriverSessionConfiguration {
        Objects.requireNonNull(statefulCommandPolicy,
                "statefulCommandPolicy");
    }
}
