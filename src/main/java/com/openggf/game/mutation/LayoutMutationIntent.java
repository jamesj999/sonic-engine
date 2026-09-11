package com.openggf.game.mutation;

/** One atomic layout mutation step that can be queued or applied immediately. */
@FunctionalInterface
public interface LayoutMutationIntent {
    /** Applies the mutation and returns the side effects required to reflect it. */
    MutationEffects apply(LayoutMutationContext context);
}
