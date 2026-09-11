package com.openggf.testmode;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;

/**
 * Resolves trace render-visibility decisions from config as three independent
 * master gates. Honored by both live Trace Test Mode and the headless capture
 * recorder (Plan 2 wires the gates at the render sites).
 *
 * <p>{@code showDebugHud()} is a master gate only — it does NOT mutate
 * {@code DebugOverlayManager}. When true, the render site renders the debug HUD
 * and the existing per-element {@code DebugOverlayToggle} states decide which
 * panels show; when false, the debug HUD is skipped without touching any toggle
 * state. (Driving per-panel selection from capture config in headless mode is a
 * Plan 2 concern.)
 */
public final class TraceRenderVisibility {

    private static final TraceRenderVisibility[] FLYWEIGHTS = createFlyweights();

    private final boolean showGhosts;
    private final boolean showGameHud;
    private final boolean showDebugHud;

    private TraceRenderVisibility(boolean showGhosts, boolean showGameHud, boolean showDebugHud) {
        this.showGhosts = showGhosts;
        this.showGameHud = showGameHud;
        this.showDebugHud = showDebugHud;
    }

    public static TraceRenderVisibility fromConfig(SonicConfigurationService config) {
        return of(
                config.getBoolean(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS),
                config.getBoolean(SonicConfiguration.TRACE_SHOW_GAME_HUD),
                config.getBoolean(SonicConfiguration.TRACE_SHOW_DEBUG_HUD));
    }

    public static TraceRenderVisibility of(boolean showGhosts, boolean showGameHud, boolean showDebugHud) {
        int bits = showGhosts ? 1 : 0;
        if (showGameHud) bits |= 2;
        if (showDebugHud) bits |= 4;
        return FLYWEIGHTS[bits];
    }

    /**
     * The config-default gates (ghosts on, game HUD on, debug HUD off), for use
     * as a non-null placeholder before a real per-frame value is resolved.
     */
    public static TraceRenderVisibility defaults() {
        return FLYWEIGHTS[3];
    }

    private static TraceRenderVisibility[] createFlyweights() {
        TraceRenderVisibility[] values = new TraceRenderVisibility[8];
        for (int bits = 0; bits < values.length; bits++) {
            values[bits] = new TraceRenderVisibility(
                    (bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0);
        }
        return values;
    }

    public boolean showGhosts() { return showGhosts; }

    public boolean showGameHud() { return showGameHud; }

    public boolean showDebugHud() { return showDebugHud; }
}
