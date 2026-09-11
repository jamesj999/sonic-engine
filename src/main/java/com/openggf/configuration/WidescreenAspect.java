package com.openggf.configuration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Display aspect presets. Height is always 224; only the native pixel width
 * varies. Every width is a multiple of 16 (per-16px vertical-scroll column
 * alignment). NATIVE_4_3 reproduces the original 320x224 behaviour.
 */
public enum WidescreenAspect {
    NATIVE_4_3(320),
    WIDE_16_10(352),
    WIDE_16_9(400),
    ULTRA_21_9(528),
    SUPER_32_9(800);

    private static final Logger LOGGER = Logger.getLogger(WidescreenAspect.class.getName());

    private final int pixelWidth;

    WidescreenAspect(int pixelWidth) {
        this.pixelWidth = pixelWidth;
    }

    public int pixelWidth() {
        return pixelWidth;
    }

    /** Parses a preset name (case-insensitive). Unknown/invalid values warn and fall back to NATIVE_4_3. */
    public static WidescreenAspect parse(String value) {
        if (value == null) {
            return NATIVE_4_3;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (WidescreenAspect aspect : values()) {
            if (aspect.name().equals(normalized)) {
                return aspect;
            }
        }
        LOGGER.warning("Unknown/blank DISPLAY_ASPECT '" + value + "', falling back to NATIVE_4_3");
        return NATIVE_4_3;
    }
}
