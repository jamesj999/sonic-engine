package com.openggf.game.render;

/**
 * Runtime-owned special render effect.
 *
 * <p>Effects are registered by zone feature providers and dispatched by stage
 * from {@link com.openggf.level.LevelManager}.
 */
public interface SpecialRenderEffect {

    /**
     * Returns the stage this effect should run in.
     */
    SpecialRenderEffectStage stage();

    /**
     * Human-readable name for logging/debugging.
     */
    default String debugName() {
        return getClass().getSimpleName();
    }

    /**
     * Executes the effect for the current frame.
     */
    void render(SpecialRenderEffectContext context);
}
