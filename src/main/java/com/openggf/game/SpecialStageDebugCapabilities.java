package com.openggf.game;

/**
 * Optional developer controls exposed by a special-stage implementation.
 *
 * <p>The shared game loop owns the key bindings, but the stage owns whether a
 * binding has a meaningful implementation. Keeping that answer explicit
 * prevents an unsupported game from consuming a key while silently leaving a
 * provider's default no-op method untouched.</p>
 *
 * @param gameplayMovement whether the direct player-movement debug mode is available
 * @param stageSelection whether the next-stage shortcut is available
 * @param layoutSelection whether the alternate layout-set shortcut is available
 * @param spriteViewer whether the sprite debug viewer is available
 * @param planeVisibility whether the plane visibility cycle is available
 * @param alignment whether alignment-test mode and its overlay are available
 * @param lagCompensation whether the lag-compensation diagnostic display is available
 */
public record SpecialStageDebugCapabilities(
        boolean gameplayMovement,
        boolean stageSelection,
        boolean layoutSelection,
        boolean spriteViewer,
        boolean planeVisibility,
        boolean alignment,
        boolean lagCompensation) {

    /** No optional special-stage developer controls are available. */
    public static final SpecialStageDebugCapabilities NONE =
            new SpecialStageDebugCapabilities(false, false, false, false, false, false, false);

    /**
     * Explicit all-controls profile for test doubles or an implementation
     * that genuinely supports every optional control. Built-in providers use
     * narrower profiles rather than relying on this convenience constant.
     */
    public static final SpecialStageDebugCapabilities LEGACY =
            new SpecialStageDebugCapabilities(true, true, true, true, true, true, true);

    /**
     * Normalizes an optional provider response to the fail-closed profile.
     * Mockito-backed or older providers may return {@code null} for a newly
     * added interface method; that must mean that no optional control is
     * available, rather than causing the game loop to fail.
     */
    public static SpecialStageDebugCapabilities orNone(SpecialStageDebugCapabilities capabilities) {
        return capabilities == null ? NONE : capabilities;
    }
}
