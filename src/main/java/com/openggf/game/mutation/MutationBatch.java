package com.openggf.game.mutation;

import java.util.List;
import java.util.Objects;

/** Immutable snapshot of the intents being applied in one pipeline flush. */
public record MutationBatch(List<LayoutMutationIntent> intents) {

    public MutationBatch {
        intents = List.copyOf(Objects.requireNonNull(intents, "intents"));
    }

    /** Returns an empty mutation batch. */
    public static MutationBatch empty() {
        return new MutationBatch(List.of());
    }

    /** Returns {@code true} when the batch contains no intents. */
    public boolean isEmpty() {
        return intents.isEmpty();
    }

    /** Returns the unapplied suffix starting at {@code startIndex}. */
    public List<LayoutMutationIntent> remainingFrom(int startIndex) {
        if (startIndex <= 0) {
            return intents;
        }
        if (startIndex >= intents.size()) {
            return List.of();
        }
        return intents.subList(startIndex, intents.size());
    }
}
