package com.openggf.debug;

import static org.lwjgl.glfw.GLFW.*;

public enum DebugOverlayToggle {
    OVERLAY("Overlay", GLFW_KEY_F1, false),
    SHORTCUTS("Shortcuts", GLFW_KEY_F2, false),
    PLAYER_PANEL("Player Panel", GLFW_KEY_F3, true),
    SENSOR_LABELS("Sensor Labels", GLFW_KEY_F4, true),
    OBJECT_LABELS("Object Labels", GLFW_KEY_F5, true),
    CAMERA_BOUNDS("Camera Bounds", GLFW_KEY_F6, true),
    PLAYER_BOUNDS("Player Bounds", GLFW_KEY_F7, true),
    OBJECT_POINTS("Object Points", GLFW_KEY_F8, true),
    RING_BOUNDS("Ring Bounds", GLFW_KEY_K, true),
    PLANE_SWITCHERS("Plane Switchers", GLFW_KEY_F10, true),
    TOUCH_RESPONSE("Touch Response", GLFW_KEY_F11, false),
    OBJECT_ART_VIEWER("Art Viewer", GLFW_KEY_F12, false),
    COLLISION_VIEW("Collision View", GLFW_KEY_GRAVE_ACCENT, false),
    TILE_PRIORITY_VIEW("Tile Priority", GLFW_KEY_EQUAL, false),
    PERFORMANCE("Performance", GLFW_KEY_P, false),
    OBJECT_DEBUG("Object Debug", GLFW_KEY_O, false);

    private final String label;
    private final int keyCode;
    private final boolean defaultEnabled;

    DebugOverlayToggle(String label, int keyCode, boolean defaultEnabled) {
        this.label = label;
        this.keyCode = keyCode;
        this.defaultEnabled = defaultEnabled;
    }

    public String label() {
        return label;
    }

    public int keyCode() {
        return keyCode;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public String shortcutLabel() {
        return glfwKeyToString(keyCode);
    }

    private static String glfwKeyToString(int key) {
        return switch (key) {
            case GLFW_KEY_F1 -> "F1";
            case GLFW_KEY_F2 -> "F2";
            case GLFW_KEY_F3 -> "F3";
            case GLFW_KEY_F4 -> "F4";
            case GLFW_KEY_F5 -> "F5";
            case GLFW_KEY_F6 -> "F6";
            case GLFW_KEY_F7 -> "F7";
            case GLFW_KEY_F8 -> "F8";
            case GLFW_KEY_F9 -> "F9";
            case GLFW_KEY_F10 -> "F10";
            case GLFW_KEY_F11 -> "F11";
            case GLFW_KEY_F12 -> "F12";
            case GLFW_KEY_GRAVE_ACCENT -> "`";
            case GLFW_KEY_EQUAL -> "=";
            case GLFW_KEY_K -> "K";
            case GLFW_KEY_P -> "P";
            case GLFW_KEY_O -> "O";
            default -> "?";
        };
    }
}
