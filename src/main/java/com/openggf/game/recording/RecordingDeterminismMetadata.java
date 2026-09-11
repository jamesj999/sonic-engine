package com.openggf.game.recording;

public record RecordingDeterminismMetadata(
        Integer initialLevelFrameCounter,
        Long initialRngSeed
) {
}
