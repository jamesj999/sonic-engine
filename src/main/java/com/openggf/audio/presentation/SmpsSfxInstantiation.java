package com.openggf.audio.presentation;

import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.AdmissionResult;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.RejectionReason;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.SmpsAdmissionContext;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.session.PreparedSmpsSfxProgram;
import java.util.Objects;

public interface SmpsSfxInstantiation {
    /** Signals that a source resolved earlier is no longer cached at admission. */
    final class CacheMissException extends IllegalStateException {
        public CacheMissException(SmpsAssetKey assetKey) {
            super("SMPS SFX asset cache miss: "
                    + Objects.requireNonNull(assetKey, "assetKey"));
        }
    }

    record Admission(
            SmpsAdmissionContext context, AdmissionResult result) {
        public Admission {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(result, "result");
        }
    }

    SmpsSequencer instantiateCached(ResolvedSmpsSfxSource source,
                                    SmpsDriver currentOwner);

    /** Prepares a write-free session admission from the immutable cache. */
    default PreparedSmpsSfxProgram prepareCached(
            ResolvedSmpsSfxSource source) {
        throw new IllegalStateException(
                "no session SMPS SFX preparation for "
                        + Objects.requireNonNull(source, "source").assetKey());
    }

    /** Evaluates one whole request before any driver or continuous-SFX mutation. */
    default Admission evaluateAdmission(
            ResolvedSmpsSfxSource source, SmpsDriver currentOwner) {
        SmpsAdmissionContext context = admissionContext(source);
        return new Admission(context,
                SmpsRequestAdmissionPolicy.PERMISSIVE.evaluate(context));
    }

    /** Builds an engine-owned rejection which bypasses the game policy. */
    default Admission rejectedAdmission(
            ResolvedSmpsSfxSource source, RejectionReason reason) {
        SmpsAdmissionContext context = admissionContext(source);
        return new Admission(context, new AdmissionResult(false, reason,
                context.priorityBefore(), context.priorityBefore(),
                context.resolvedSoundId()));
    }

    /** Reclassifies an already evaluated request at a later engine gate. */
    default Admission rejectedAdmission(
            Admission evaluated, RejectionReason reason) {
        Objects.requireNonNull(evaluated, "evaluated");
        Objects.requireNonNull(reason, "reason");
        SmpsAdmissionContext context = evaluated.context();
        return new Admission(context, new AdmissionResult(false, reason,
                evaluated.result().priorityBefore(),
                evaluated.result().priorityBefore(),
                evaluated.result().resolvedSoundId()));
    }

    /** Reports a completed decision; the observer has no mutation authority. */
    default void observeAdmission(Admission admission) { }

    /** Reports a completed registry lifecycle mutation. */
    default void observeLifecycle(
            SmpsDriverServiceObserver.LifecycleEvent event) { }

    /** Whether this admission path can invoke a throwing diagnostic callback. */
    default boolean hasPotentiallyThrowingObserver() {
        return false;
    }

    private static SmpsAdmissionContext admissionContext(
            ResolvedSmpsSfxSource source) {
        Objects.requireNonNull(source, "source");
        return new SmpsAdmissionContext(source.assetKey().sfxId(),
                source.resolvedSoundId(), source.priority(),
                SmpsRequestAdmissionPolicy.NO_PRIORITY,
                source.specialSfx(), false);
    }
}
