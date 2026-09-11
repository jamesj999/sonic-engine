package com.openggf.game.recording;

import java.nio.file.Path;
import java.time.Instant;

public record UserRecordingEntry(
        Path path,
        String displayName,
        UserRecordingManifest manifest,
        int frameCount,
        Instant modifiedAt,
        RecordingVersionWarning versionWarning,
        String loadError
) {
    public boolean isLoadable() {
        return loadError == null || loadError.isBlank();
    }
}
