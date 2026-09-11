package com.openggf.audio.driver;

import java.util.Objects;

/**
 * Game-profile-owned whole-request policy evaluated before sequencer
 * construction, continuous-SFX mutation, or per-role arbitration.
 */
@FunctionalInterface
public interface SmpsRequestAdmissionPolicy {
    int NO_PRIORITY = -1;

    SmpsRequestAdmissionPolicy PERMISSIVE = context ->
            new AdmissionResult(true, RejectionReason.NONE,
                    context.priorityBefore(), context.requestedPriority(),
                    context.resolvedSoundId());

    AdmissionResult evaluate(SmpsAdmissionContext context);

    enum RejectionReason {
        NONE,
        BLOCKED,
        PRIORITY,
        DUPLICATE,
        QUEUE_FULL,
        CACHE_MISS,
        OTHER
    }

    record SmpsAdmissionContext(
            int requestedSoundId,
            int resolvedSoundId,
            int requestedPriority,
            int priorityBefore,
            boolean specialSfx,
            boolean continuousRetrigger) {
    }

    record AdmissionResult(
            boolean accepted,
            RejectionReason reason,
            int priorityBefore,
            int priorityAfter,
            int resolvedSoundId) {
        public AdmissionResult {
            Objects.requireNonNull(reason, "reason");
            if (accepted && reason != RejectionReason.NONE) {
                throw new IllegalArgumentException(
                        "accepted admission must use reason NONE");
            }
            if (!accepted && reason == RejectionReason.NONE) {
                throw new IllegalArgumentException(
                        "rejected admission must provide a reason");
            }
        }
    }
}
