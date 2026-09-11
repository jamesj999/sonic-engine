package com.openggf.game.recording;

public record UserRecordingVerificationResult(
        String status,
        boolean clean,
        int comparedFrames,
        int firstMismatchFrame,
        String firstMismatchField,
        String expectedValue,
        String actualValue
) {
    public static UserRecordingVerificationResult clean(int comparedFrames) {
        return new UserRecordingVerificationResult("clean", true, comparedFrames, -1, "", "", "");
    }

    public static UserRecordingVerificationResult missingSidecar(int comparedFrames) {
        return new UserRecordingVerificationResult("missing-sidecar", false, comparedFrames, -1, "", "", "");
    }

    public static UserRecordingVerificationResult schemaUnsupported(int comparedFrames) {
        return new UserRecordingVerificationResult("schema-unsupported", false, comparedFrames, -1, "", "", "");
    }

    public static UserRecordingVerificationResult truncatedSidecar(int comparedFrames) {
        return new UserRecordingVerificationResult("truncated-sidecar", false, comparedFrames, -1, "", "", "");
    }

    public static UserRecordingVerificationResult firstMismatch(int comparedFrames, int frame, String field,
            String expected, String actual) {
        return new UserRecordingVerificationResult(
                "first-mismatch(frame=" + frame
                        + ", field=" + field
                        + ", expected=" + expected
                        + ", actual=" + actual + ")",
                false,
                comparedFrames,
                frame,
                field,
                expected,
                actual);
    }
}
