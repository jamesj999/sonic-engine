package uk.co.jamesj999.sonic.debug;

import java.awt.event.KeyEvent;

public enum DebugOverlayToggle {
    OVERLAY("Overlay", KeyEvent.VK_F1, false),
    SHORTCUTS("Shortcuts", KeyEvent.VK_F2, false),
    PLAYER_PANEL("Player Panel", KeyEvent.VK_F3, true),
    SENSOR_LABELS("Sensor Labels", KeyEvent.VK_F4, true),
    OBJECT_LABELS("Object Labels", KeyEvent.VK_F5, true),
    CAMERA_BOUNDS("Camera Bounds", KeyEvent.VK_F6, true),
    PLAYER_BOUNDS("Player Bounds", KeyEvent.VK_F7, true),
    OBJECT_POINTS("Object Points", KeyEvent.VK_F8, true),
    RING_BOUNDS("Ring Bounds", KeyEvent.VK_F9, true),
    PLANE_SWITCHERS("Plane Switchers", KeyEvent.VK_F10, true),
    TOUCH_RESPONSE("Touch Response", KeyEvent.VK_F11, false),
    OBJECT_ART_VIEWER("Art Viewer", KeyEvent.VK_F12, false),
    COLLISION_VIEW("Collision View", KeyEvent.VK_BACK_QUOTE, false),
    PERFORMANCE("Performance", KeyEvent.VK_P, false);

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
        return KeyEvent.getKeyText(keyCode);
    }
}
