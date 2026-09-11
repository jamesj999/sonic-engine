package com.openggf.game.resources;

import java.util.Objects;

/** One queue-local native service boundary observed during a logical frame. */
public record QueueServiceObservation(String boundary, int budget) {
    public QueueServiceObservation {
        if (Objects.requireNonNull(boundary, "boundary").isBlank()) {
            throw new IllegalArgumentException("boundary must not be blank");
        }
        if (budget < -1) {
            throw new IllegalArgumentException("budget must be -1 or nonnegative");
        }
    }
}
