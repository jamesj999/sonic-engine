package com.openggf.capture;

/** What a {@link FrameSink} does when its bounded queue is full. */
public enum BackpressurePolicy {
    /** Block the producer until space frees up. Never drops; paces the producer. */
    BLOCK,
    /** Discard the oldest queued frame to make room for the new one. */
    DROP_OLDEST,
    /** Throw {@link CaptureException} from {@code submit}. */
    FAIL
}
