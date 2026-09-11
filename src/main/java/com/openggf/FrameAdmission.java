package com.openggf;

import java.util.Objects;

/**
 * Result of classifying one outer level-loop iteration before gameplay-owned
 * timers, controls, recording, and cursor publication.
 */
public record FrameAdmission(LevelFrameResult result) {
    public FrameAdmission {
        Objects.requireNonNull(result, "result");
    }

    public boolean runsGameplay() {
        return result == LevelFrameResult.GAMEPLAY_FRAME;
    }

}
