package com.openggf.configuration;

import java.util.Locale;

/** Camera horizontal deadzone behaviour on wide screens. */
public enum DeadzoneMode {
    /** Native 16px band, re-centered. */
    CENTER_SCALED,
    /** Band width scales with width/320. Default. */
    PROPORTIONAL;

    public static DeadzoneMode parse(String value) {
        if (value == null) {
            return PROPORTIONAL;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DeadzoneMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return PROPORTIONAL;
    }
}
