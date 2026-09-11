package com.openggf.tests.trace.s3k;

public record S3kCheckpointProbe(
        int replayFrame,
        Integer actualZoneId,
        Integer actualAct,
        Integer apparentAct,
        Integer gameMode,
        int moveLock,
        boolean ctrlLocked,
        boolean objectControlled,
        boolean hidden,
        boolean eventsFg5,
        boolean fireTransitionActive,
        boolean hczTransitionActive,
        boolean signpostActive,
        boolean resultsActive,
        boolean levelStarted,
        boolean titleCardOverlayActive) {
}
