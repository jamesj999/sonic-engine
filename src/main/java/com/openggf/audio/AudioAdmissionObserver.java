package com.openggf.audio;

import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.AdmissionResult;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.SmpsAdmissionContext;
import java.util.Objects;

/**
 * Disabled-by-default diagnostic view of completed request-level admission
 * decisions. Implementations observe an outcome after its policy boundary and
 * cannot choose or alter playback behavior.
 */
@FunctionalInterface
public interface AudioAdmissionObserver {
    AudioAdmissionObserver NONE = decision -> { };

    void onDecision(AudioAdmissionDecision decision);

    record AudioAdmissionDecision(
            SmpsAdmissionContext context, AdmissionResult result) {
        public AudioAdmissionDecision {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(result, "result");
        }
    }
}
