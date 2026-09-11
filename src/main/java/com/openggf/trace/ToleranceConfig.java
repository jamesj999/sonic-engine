package com.openggf.trace;

/**
 * Per-field tolerance thresholds for trace comparison.
 * Warn and error thresholds define the boundaries between MATCH, WARNING, and ERROR.
 * Boolean/enum fields (air, rolling, ground_mode) always ERROR on any mismatch.
 */
public record ToleranceConfig(
    int positionWarn,
    int positionError,
    int speedWarn,
    int speedError,
    boolean speedSignChangeIsError,
    int angleWarn,
    int angleError,
    int cameraWarn,
    int cameraError,
    RingCountMode ringCountMode
) {
    public enum RingCountMode {
        DISABLED,
        WARN_ONLY,
        FORCE_ERROR
    }

    /** Backward-compatible 7-arg constructor (no camera, ring DISABLED). */
    public ToleranceConfig(int positionWarn,
                           int positionError,
                           int speedWarn,
                           int speedError,
                           boolean speedSignChangeIsError,
                           int angleWarn,
                           int angleError) {
        this(positionWarn, positionError, speedWarn, speedError,
                speedSignChangeIsError, angleWarn, angleError,
                1, 1, RingCountMode.DISABLED);
    }

    /** Backward-compatible 8-arg constructor (no camera). */
    public ToleranceConfig(int positionWarn,
                           int positionError,
                           int speedWarn,
                           int speedError,
                           boolean speedSignChangeIsError,
                           int angleWarn,
                           int angleError,
                           RingCountMode ringCountMode) {
        this(positionWarn, positionError, speedWarn, speedError,
                speedSignChangeIsError, angleWarn, angleError,
                1, 1, ringCountMode);
    }

    /**
     * Default replay policy is exact: any numeric mismatch is an error.
     * Non-zero tolerance configs must be opt-in and justified by the caller.
     * - Flags: any mismatch = error (hardcoded, not configurable)
     * - Ring counts: any mismatch = error. Callers triaging known parity gaps
     *   can opt into {@link #withRingCountMode(RingCountMode)} explicitly.
     * - Camera: any mismatch = error when both ROM trace and engine recorded
     *   camera coordinates. Skipped automatically when either side is absent.
     */
    public static final ToleranceConfig DEFAULT = new ToleranceConfig(
        1, 1,         // position
        1, 1, true,   // speed
        1, 1,         // angle
        1, 1,         // camera
        RingCountMode.FORCE_ERROR
    );

    /** Returns a copy of this config with the specified ring count mode. */
    public ToleranceConfig withRingCountMode(RingCountMode mode) {
        return new ToleranceConfig(positionWarn, positionError,
                speedWarn, speedError, speedSignChangeIsError,
                angleWarn, angleError, cameraWarn, cameraError, mode);
    }

    /** Returns a copy of this config with new camera tolerances. */
    public ToleranceConfig withCameraTolerances(int warn, int error) {
        return new ToleranceConfig(positionWarn, positionError,
                speedWarn, speedError, speedSignChangeIsError,
                angleWarn, angleError, warn, error, ringCountMode);
    }

    /** Classify a numeric difference against warn/error thresholds. */
    public Severity classify(int absDelta, int warn, int error) {
        if (absDelta == 0) return Severity.MATCH;
        if (absDelta >= error) return Severity.ERROR;
        if (absDelta >= warn) return Severity.WARNING;
        return Severity.MATCH;
    }
}


