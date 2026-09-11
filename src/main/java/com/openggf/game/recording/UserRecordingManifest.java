package com.openggf.game.recording;

import com.openggf.version.BuildIdentity;

import java.time.Instant;

public record UserRecordingManifest(
        int schemaVersion,
        String movieName,
        BuildIdentity engineIdentity,
        RecordingLaunchContext launchContext,
        UserRecordingSidecarMetadata sidecar,
        RecordingDeterminismMetadata determinism,
        String jumpActionButton,
        int frameCount,
        UserRecordingStopReason stopReason,
        Instant createdAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
