package com.openggf.game.recording;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.PlaybackDebugManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

public final class UserRecordingVerifier {
    private final Map<Integer, DesyncLiteFrame> expectedFramesById;
    private final IntFunction<DesyncLiteFrame> snapshotter;
    private final PlaybackDebugManager.PlaybackFrameObserver observer = new Observer();
    private final VerificationAvailability availability;
    private final UserRecordingSidecarMetadata sidecarMetadata;

    private int comparedFrames;
    private UserRecordingVerificationResult firstMismatch;
    private boolean truncatedSidecar;

    public UserRecordingVerifier(List<DesyncLiteFrame> expectedFrames) {
        this(expectedFrames, UserRecordingSidecarMetadata.everyFrame(), DesyncLiteSnapshotter::capture,
                VerificationAvailability.AVAILABLE);
    }

    public UserRecordingVerifier(List<DesyncLiteFrame> expectedFrames,
            UserRecordingSidecarMetadata sidecarMetadata) {
        this(expectedFrames, sidecarMetadata, DesyncLiteSnapshotter::capture,
                VerificationAvailability.AVAILABLE);
    }

    private UserRecordingVerifier(List<DesyncLiteFrame> expectedFrames,
                                  UserRecordingSidecarMetadata sidecarMetadata,
                                  IntFunction<DesyncLiteFrame> snapshotter,
                                  VerificationAvailability availability) {
        Objects.requireNonNull(expectedFrames, "expectedFrames");
        this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
        this.sidecarMetadata = sidecarMetadata == null ? UserRecordingSidecarMetadata.everyFrame() : sidecarMetadata;
        this.availability = Objects.requireNonNull(availability, "availability");
        this.expectedFramesById = new HashMap<>();
        for (DesyncLiteFrame frame : expectedFrames) {
            DesyncLiteFrame expected = Objects.requireNonNull(frame, "expectedFrames element");
            expectedFramesById.put(expected.frame(), expected);
        }
    }

    static UserRecordingVerifier forTesting(List<DesyncLiteFrame> expectedFrames,
                                            DesyncLiteFrame... actualFrames) {
        return forTesting(expectedFrames, UserRecordingSidecarMetadata.everyFrame(), actualFrames);
    }

    static UserRecordingVerifier forTesting(List<DesyncLiteFrame> expectedFrames,
                                            UserRecordingSidecarMetadata sidecarMetadata,
                                            DesyncLiteFrame... actualFrames) {
        Objects.requireNonNull(actualFrames, "actualFrames");
        return new UserRecordingVerifier(expectedFrames, sidecarMetadata, new IntFunction<>() {
            private int index;

            @Override
            public DesyncLiteFrame apply(int frame) {
                if (index >= actualFrames.length) {
                    throw new IllegalStateException("No test snapshot for frame " + frame);
                }
                return actualFrames[index++];
            }
        }, VerificationAvailability.AVAILABLE);
    }

    public static UserRecordingVerifier missingSidecar() {
        return new UserRecordingVerifier(List.of(), UserRecordingSidecarMetadata.everyFrame(),
                DesyncLiteSnapshotter::capture, VerificationAvailability.MISSING_SIDECAR);
    }

    public static UserRecordingVerifier unsupportedSchema(int schemaVersion) {
        return new UserRecordingVerifier(List.of(),
                new UserRecordingSidecarMetadata(schemaVersion, "every-frame", null),
                DesyncLiteSnapshotter::capture,
                VerificationAvailability.SCHEMA_UNSUPPORTED);
    }

    public PlaybackDebugManager.PlaybackFrameObserver observer() {
        return observer;
    }

    public UserRecordingVerificationResult result() {
        if (firstMismatch != null) {
            return UserRecordingVerificationResult.firstMismatch(
                    comparedFrames,
                    firstMismatch.firstMismatchFrame(),
                    firstMismatch.firstMismatchField(),
                    firstMismatch.expectedValue(),
                    firstMismatch.actualValue());
        }
        if (availability == VerificationAvailability.MISSING_SIDECAR) {
            return UserRecordingVerificationResult.missingSidecar(comparedFrames);
        }
        if (availability == VerificationAvailability.SCHEMA_UNSUPPORTED) {
            return UserRecordingVerificationResult.schemaUnsupported(comparedFrames);
        }
        if (truncatedSidecar) {
            return UserRecordingVerificationResult.truncatedSidecar(comparedFrames);
        }
        return UserRecordingVerificationResult.clean(comparedFrames);
    }

    public boolean hasMismatch() {
        return firstMismatch != null;
    }

    private void compareFrame(Bk2FrameInput frame) {
        if (availability != VerificationAvailability.AVAILABLE) {
            return;
        }
        DesyncLiteFrame expected = expectedFramesById.get(frame.frameIndex());
        if (expected == null) {
            if (isEveryFrameSidecar()) {
                truncatedSidecar = true;
            }
            return;
        }

        DesyncLiteFrame actual = snapshotter.apply(frame.frameIndex());
        comparedFrames++;
        if (firstMismatch == null) {
            firstMismatch = compare(expected, actual, comparedFrames);
        }
    }

    private static UserRecordingVerificationResult compare(DesyncLiteFrame expected,
                                                           DesyncLiteFrame actual,
                                                           int comparedFrames) {
        UserRecordingVerificationResult mismatch;
        mismatch = compareField("frame", expected.frame(), actual.frame(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("p1CentreX", expected.p1CentreX(), actual.p1CentreX(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("p1CentreY", expected.p1CentreY(), actual.p1CentreY(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("p1XSpeed", expected.p1XSpeed(), actual.p1XSpeed(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("p1YSpeed", expected.p1YSpeed(), actual.p1YSpeed(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("p1Inertia", expected.p1Inertia(), actual.p1Inertia(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("p1Status", expected.p1Status(), actual.p1Status(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("p1Animation", expected.p1Animation(), actual.p1Animation(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("cameraX", expected.cameraX(), actual.cameraX(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("cameraY", expected.cameraY(), actual.cameraY(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("timerFrames", expected.timerFrames(), actual.timerFrames(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("timerSeconds", expected.timerSeconds(), actual.timerSeconds(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("timerMinutes", expected.timerMinutes(), actual.timerMinutes(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        mismatch = compareField("ringCount", expected.ringCount(), actual.ringCount(), comparedFrames, expected.frame());
        if (mismatch != null) return mismatch;
        return compareField("score", expected.score(), actual.score(), comparedFrames, expected.frame());
    }

    private static UserRecordingVerificationResult compareField(String fieldName, int expected, int actual,
                                                                int comparedFrames, int frame) {
        if (expected == actual) {
            return null;
        }
        return UserRecordingVerificationResult.firstMismatch(
                comparedFrames,
                frame,
                fieldName,
                Integer.toString(expected),
                Integer.toString(actual));
    }

    private boolean isEveryFrameSidecar() {
        return "every-frame".equalsIgnoreCase(sidecarMetadata.sampleMode());
    }

    private enum VerificationAvailability {
        AVAILABLE,
        MISSING_SIDECAR,
        SCHEMA_UNSUPPORTED
    }

    private final class Observer implements PlaybackDebugManager.PlaybackFrameObserver {
        @Override
        public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
            return false;
        }

        @Override
        public void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped) {
            compareFrame(frame);
        }
    }
}
