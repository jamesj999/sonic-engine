package com.openggf.graphics.color;

import java.util.Locale;

public enum DisplayColorProfile {
    RAW_RGB("Raw RGB"),
    MD_ANALOG("MD Analog"),
    NTSC_SOFT("NTSC Soft");

    private final String label;

    DisplayColorProfile(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public DisplayColorProfile next() {
        DisplayColorProfile[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static DisplayColorProfile parse(String value) {
        if (value == null) {
            return RAW_RGB;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DisplayColorProfile profile : values()) {
            if (profile.name().equals(normalized)) {
                return profile;
            }
        }
        return RAW_RGB;
    }
}
