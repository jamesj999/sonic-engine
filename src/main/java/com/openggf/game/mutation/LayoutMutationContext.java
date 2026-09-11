package com.openggf.game.mutation;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Execution context for one layout mutation application pass.
 *
 * <p>The context exposes the target mutation surface and a sink for the
 * resulting {@link MutationEffects} so the caller can trigger redraws,
 * resyncs, and pattern uploads after each intent runs.
 */
public final class LayoutMutationContext {

    private final LevelMutationSurface surface;
    private final Consumer<MutationEffects> effectSink;

    /**
     * Creates a context that only publishes mutation side effects.
     *
     * <p>This is useful for tests or callers that do not need direct access to
     * the target level surface.
     */
    public LayoutMutationContext(Consumer<MutationEffects> effectSink) {
        this(null, effectSink);
    }

    /** Creates a context exposing both the mutation surface and the effect sink. */
    public LayoutMutationContext(LevelMutationSurface surface, Consumer<MutationEffects> effectSink) {
        this.surface = surface;
        this.effectSink = Objects.requireNonNull(effectSink, "effectSink");
    }

    /** Returns the writable surface for the active level mutation pass. */
    public LevelMutationSurface surface() {
        if (surface == null) {
            throw new IllegalStateException("This mutation context does not expose a LevelMutationSurface.");
        }
        return surface;
    }

    void publish(MutationEffects effects) {
        effectSink.accept(effects != null ? effects : MutationEffects.NONE);
    }
}
