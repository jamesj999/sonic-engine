package uk.co.jamesj999.sonic.debug;

import uk.co.jamesj999.sonic.Control.InputHandler;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

public class DebugOverlayManager {
    private static DebugOverlayManager debugOverlayManager;

    private final EnumMap<DebugOverlayToggle, Boolean> states = new EnumMap<>(DebugOverlayToggle.class);

    private DebugOverlayManager() {
        for (DebugOverlayToggle toggle : DebugOverlayToggle.values()) {
            states.put(toggle, toggle.defaultEnabled());
        }
    }

    public static synchronized DebugOverlayManager getInstance() {
        if (debugOverlayManager == null) {
            debugOverlayManager = new DebugOverlayManager();
        }
        return debugOverlayManager;
    }

    public void updateInput(InputHandler handler) {
        if (handler == null) {
            return;
        }
        for (DebugOverlayToggle toggle : DebugOverlayToggle.values()) {
            if (handler.isKeyPressed(toggle.keyCode())) {
                setEnabled(toggle, !isEnabled(toggle));
            }
        }
    }

    public boolean isEnabled(DebugOverlayToggle toggle) {
        return states.getOrDefault(toggle, Boolean.TRUE);
    }

    public void setEnabled(DebugOverlayToggle toggle, boolean enabled) {
        states.put(toggle, enabled);
    }

    public List<String> buildShortcutLines() {
        List<String> lines = new ArrayList<>();
        for (DebugOverlayToggle toggle : DebugOverlayToggle.values()) {
            String state = isEnabled(toggle) ? "On" : "Off";
            lines.add(toggle.shortcutLabel() + " " + toggle.label() + ": " + state);
        }
        return lines;
    }
}
