package com.openggf.game.recording;

public record UserRecordingSidecarMetadata(
        int desyncLiteSchemaVersion,
        String sampleMode,
        Integer sampleInterval
) {
    public static final int CURRENT_DESYNC_LITE_SCHEMA_VERSION = 1;

    public static UserRecordingSidecarMetadata everyFrame() {
        return new UserRecordingSidecarMetadata(CURRENT_DESYNC_LITE_SCHEMA_VERSION, "every-frame", null);
    }
}
