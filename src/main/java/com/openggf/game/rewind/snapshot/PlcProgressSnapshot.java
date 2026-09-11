package com.openggf.game.rewind.snapshot;

import com.openggf.game.RuntimeArtAdmissionOwnerKind;

import java.util.List;

/**
 * Snapshot of PLC/object-art progress for a game's object art provider.
 *
 * <p>The engine loads all PLC-driven object art at zone-load time
 * ({@code loadArtForZone}). The optional runtime state records provider-owned
 * queued art work, such as S3K's direct {@code Queue_Kos_Module} requests and
 * the residual title-card owner lifetime that gates them.
 *
 * <p>Providers may restore the epoch and runtime state when they own dynamic
 * work; providers without runtime queues may continue using the epoch only.
 */
public record PlcProgressSnapshot(
        int loadEpoch,
        int runtimeState,
        List<PendingKosModule> pendingKosModules,
        List<Long> pendingKosOrdinals,
        boolean kosSubmissionArmed,
        /** Ticks the modelled title-card owner has run; -1 when not modelled. */
        int titleCardTeardownTicks,
        long runtimeArtAdmissionGeneration,
        long runtimeArtAdmissionNextLeaseId,
        long runtimeArtAdmissionLeaseId,
        long runtimeArtAdmissionBatchFingerprint,
        RuntimeArtAdmissionOwnerKind runtimeArtAdmissionOwnerKind,
        boolean runtimeArtAdmissionBound,
        boolean runtimeArtAdmissionConsumed,
        long titleCardTeardownLeaseId) {

    public PlcProgressSnapshot {
        pendingKosModules = List.copyOf(pendingKosModules);
        pendingKosOrdinals = List.copyOf(pendingKosOrdinals);
    }

    public PlcProgressSnapshot(int loadEpoch) {
        this(loadEpoch, 0, List.of(), List.of(), false, -1,
                0, 0, -1, 0, null, false, false, -1);
    }

    public PlcProgressSnapshot(int loadEpoch, int runtimeState) {
        this(loadEpoch, runtimeState, List.of(), List.of(), false, -1,
                0, 0, -1, 0, null, false, false, -1);
    }

    public record PendingKosModule(int sourceAddress, int destinationTile) {
    }
}
