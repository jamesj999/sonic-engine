package com.openggf.control;

import java.util.Locale;

/** Names describe GLFW's standardized physical button positions for unknown pads. */
public record ControllerPromptStyle(String confirm, String back, String details) {
    private static final ControllerPromptStyle PLAYSTATION = new ControllerPromptStyle("Cross", "Circle", "Triangle");
    private static final ControllerPromptStyle XBOX = new ControllerPromptStyle("A", "B", "Y");
    private static final ControllerPromptStyle GENERIC = new ControllerPromptStyle("South", "East", "North");

    public static ControllerPromptStyle forName(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (normalized.contains("playstation") || normalized.contains("dualshock")
                || normalized.contains("dualsense") || normalized.contains("ps4") || normalized.contains("ps5")) {
            return PLAYSTATION;
        }
        if (normalized.contains("xbox") || normalized.contains("xinput")) {
            return XBOX;
        }
        return GENERIC;
    }
}
