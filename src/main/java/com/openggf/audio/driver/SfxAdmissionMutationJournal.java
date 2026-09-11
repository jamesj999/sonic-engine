package com.openggf.audio.driver;

import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;

import java.util.Objects;

/**
 * Rollback state retained across the complete observed SFX-admission boundary.
 *
 * <p>The journal is created only when diagnostic callbacks can throw.  It also
 * owns the prepared admission's claim so a failed queued command can retry it.
 */
public final class SfxAdmissionMutationJournal {
    private final SmpsDriver driver;
    private final PreparedSfxAdmission admission;
    private final SmpsDriver.SfxAdmissionMutationState driverState;
    private final SmpsCoordFlagRuntimeState coordStateOwner;
    private final SmpsCoordFlagRuntimeState.Snapshot coordState;
    private boolean restored;

    private SfxAdmissionMutationJournal(
            SmpsDriver driver,
            PreparedSfxAdmission admission,
            SmpsCoordFlagRuntimeState coordStateOwner,
            SmpsCoordFlagRuntimeState.Snapshot coordState) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.admission = Objects.requireNonNull(admission, "admission");
        this.driverState = driver.captureSfxAdmissionMutation(admission);
        this.coordStateOwner = coordStateOwner;
        this.coordState = coordState;
    }

    public static SfxAdmissionMutationJournal capture(
            SmpsDriver driver,
            PreparedSfxAdmission admission,
            SmpsCoordFlagRuntimeState coordStateOwner,
            SmpsCoordFlagRuntimeState.Snapshot coordState) {
        return new SfxAdmissionMutationJournal(
                driver, admission, coordStateOwner, coordState);
    }

    public void restore() {
        if (restored) {
            return;
        }
        restored = true;
        RuntimeException failure = null;
        try {
            driver.restoreSfxAdmissionMutation(driverState);
        } catch (RuntimeException driverFailure) {
            failure = driverFailure;
        }
        try {
            if (coordStateOwner != null && coordState != null) {
                coordStateOwner.restore(coordState);
            }
        } catch (RuntimeException coordFailure) {
            if (failure == null) {
                failure = coordFailure;
            } else {
                failure.addSuppressed(coordFailure);
            }
        } finally {
            admission.releaseCommit();
        }
        if (failure != null) {
            throw failure;
        }
    }
}
