package com.openggf.trace;

import java.util.Locale;

/** Selects which independently classified trace fields block a replay gate. */
public enum TraceVerificationScope {
    ALL,
    PHYSICS,
    ANIMATION;

    public static final String SYSTEM_PROPERTY = "trace.verification";

    public static TraceVerificationScope fromSystemProperty() {
        return parse(System.getProperty(SYSTEM_PROPERTY, "all"));
    }

    public static TraceVerificationScope parse(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "all", "both" -> ALL;
            case "physics", "non-animation", "non_animation" -> PHYSICS;
            case "animation", "animations", "anim" -> ANIMATION;
            default -> throw new IllegalArgumentException(
                    "Unsupported -D" + SYSTEM_PROPERTY + "=" + value
                            + " (expected all, physics, or animation)");
        };
    }

    public boolean includes(VerificationGroup group) {
        return this == ALL
                || (this == PHYSICS && group == VerificationGroup.PHYSICS)
                || (this == ANIMATION && group == VerificationGroup.ANIMATION);
    }
}
