package com.openggf.game.sonic1.resources;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.HardwareWorkPreparation;
import com.openggf.game.timing.HardwareWorkPreparationSnapshot;
import com.openggf.game.timing.HardwareWorkSubmission;

import java.util.Objects;

/**
 * Submits and releases the Sonic 1 Nemesis PLC queue's arming edge as
 * ordinary hardware-timing work.
 *
 * <p>{@code RunPLC} (docs/s1disasm/sonic.asm:1379-1420) is the
 * {@code Level_MainLoop} tail routine that accepts the head of
 * {@code v_plc_buffer} for decompression. It arms iff, on entry, the buffer
 * is non-empty and {@code v_plc_patternsleft} is zero -- exactly the engine
 * predicate "the logical FIFO has a waiting descriptor and no active one".
 * How long the ROM's own iteration takes to reach that tail is a 68000 cost,
 * not a value any frame-granularity trace field carries, so the edge is the
 * one thing about S1 PLC service that recorded hardware timing may release.
 *
 * <p>This owner submits, and nothing else. It creates work only from the
 * engine's own queue state, carries no gameplay value and no art bytes, and
 * moves only <em>when</em> an arm the engine already decided to make becomes
 * visible. Under live admission the submission is released by the same
 * boundary service that prepared it, so live play arms in the frame it always
 * did.
 */
public final class Sonic1PlcArmTiming
        implements RewindSnapshottable<Sonic1PlcArmTiming.Snapshot> {
    public static final String REWIND_KEY = "sonic1-plc-arm-timing";

    /**
     * The recorder's canonical descriptor encoding for this kind
     * (tools/tracechaser/bizhawk-headless/src/Recording/S1PlcHardwareTimingObserver.cs).
     * A Nemesis PLC descriptor has no compressed length in ROM and no
     * modules, and its destination is the tile index the ROM's own
     * {@code v_plc_buffer} slot holds.
     */
    private static final String COMPRESSION_VARIANT = "nemesis";
    private static final int NO_COMPRESSED_LENGTH = 0;
    private static final int NO_MODULES = 0;

    private final HardwareTimingService timing;
    private HardwareWorkHandle outstanding;

    /**
     * Whether this session's arm readiness is decided by a recorded stream.
     * Under live admission the arm is released by the boundary that prepared
     * it, so nothing about its visibility has moved and the replay row-shape
     * hold still owns whatever it owned before.
     */
    public boolean isRecordedAuthority() {
        return timing.admissionPolicyFor(HardwareWorkKind.NEMESIS_PLC_QUEUE)
                == HardwareReadinessAdmissionPolicy.RECORDED;
    }

    public Sonic1PlcArmTiming(HardwareTimingService timing) {
        this.timing = Objects.requireNonNull(timing, "timing");
    }

    /** Immutable rewind identity; the timing ledger owns the job itself. */
    public record Snapshot(long outstandingOrdinal) {
    }

    /**
     * Submits the arm the engine's own FIFO state has made available, at the
     * loop-tail boundary where {@code RunPLC} runs.
     */
    public void submitArmableHead(NemesisPlcQueueSnapshot queue) {
        Objects.requireNonNull(queue, "queue");
        if (outstanding != null || queue.activeEntry() != null
                || queue.queuedEntries().isEmpty()) {
            return;
        }
        NemesisPlcQueueSnapshot.Entry head = queue.queuedEntries().get(0);
        outstanding = timing.submit(new HardwareWorkSubmission(
                HardwareWorkKind.NEMESIS_PLC_QUEUE,
                head.sourceAddress(),
                NO_COMPRESSED_LENGTH,
                head.destinationTile(),
                head.totalPatterns(),
                COMPRESSION_VARIANT,
                NO_MODULES,
                false,
                new ArmPreparation()));
    }

    /**
     * Returns whether the queue's arm may become visible now.
     *
     * <p>An arm with no submitted job behind it is one the loop tail never
     * modelled -- a native blocking fade's own {@code RunPLC} iteration, for
     * instance -- and is released unchanged. A submitted job is released only
     * once its readiness has been admitted, which under recorded admission
     * means a matching recorded edge arrived at this boundary.
     */
    public boolean releaseArm() {
        if (outstanding == null) {
            return true;
        }
        if (!timing.isReady(outstanding)) {
            if (timing.recordedAuthorityRepresentsRow()) {
                return false;
            }
            // Row authority is deactivated, and HardwareTimingReplayPort
            // .enterUnrepresentedGap's contract is that "production hardware
            // work may continue, but no recorded completion edge may be applied
            // until the next beginRawFrame" (HardwareTimingReplayPort:120-126).
            // The recorder discards anything observed before a segment's first
            // row, so a level load's own RunPLC arming reaches no trace file
            // (S1PlcHardwareTimingObserver.cs:80-83) and no edge for this arm
            // can ever exist. Holding it against recorded readiness deadlocks
            // the ROM title-card wait, which loops until the PLC buffer empties
            // (docs/s1disasm/sonic.asm:2840-2841). Fall back to native
            // readiness for the span the stream never described.
            timing.admitUnrepresentedReadiness(outstanding);
            timing.claim(outstanding);
            // The recorder never counted this arm, so it must not hold a place
            // in the shared numbering: the next arm the stream does describe
            // has to be allocated the ordinal the recording gives it
            // (S1PlcHardwareTimingObserver.cs:80-83, cited above). Returning
            // the identity after the claim keeps the allocator invariant --
            // nothing unclaimed is left numbered on the old axis.
            timing.releaseUnrepresentedIdentity(outstanding);
            outstanding = null;
            return true;
        }
        boolean unrepresented = timing.wasSubmittedUnrepresented(outstanding);
        timing.claim(outstanding);
        if (unrepresented) {
            // Readiness may have been admitted by the ledger's own native pass
            // for work submitted outside the stream's rows rather than by the
            // gap branch above, but the ordinal invariant is the same either
            // way: the recorder never counted this arm, so it must not hold a
            // place in the shared numbering.
            timing.releaseUnrepresentedIdentity(outstanding);
        }
        outstanding = null;
        return true;
    }

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public Snapshot capture() {
        return new Snapshot(outstanding == null ? -1L : outstanding.ordinal());
    }

    @Override
    public void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        outstanding = timing.pendingHandle(
                HardwareWorkKind.NEMESIS_PLC_QUEUE, snapshot.outstandingOrdinal())
                .orElse(null);
    }

    @Override
    public void resetForMissingSnapshot() {
        outstanding = null;
    }

    /**
     * An arm carries no decompressed bytes: the production PLC pipeline still
     * owns every pattern. The preparation exists so the ledger has a real
     * prepared job for a recorded edge to release, and completes at the same
     * loop-tail boundary the ROM arms on.
     */
    private static final class ArmPreparation implements HardwareWorkPreparation {
        private static final byte[] NO_PAYLOAD = new byte[0];

        private boolean prepared;

        private ArmPreparation() {
        }

        private ArmPreparation(boolean prepared) {
            this.prepared = prepared;
        }

        @Override
        public boolean stepOneWorkUnit() {
            if (prepared) {
                return false;
            }
            prepared = true;
            return true;
        }

        @Override
        public boolean isPrepared() {
            return prepared;
        }

        @Override
        public byte[] preparedPayload() {
            if (!prepared) {
                throw new IllegalStateException("PLC arm is not prepared");
            }
            return NO_PAYLOAD;
        }

        @Override
        public HardwareWorkPreparationSnapshot snapshot() {
            return new ArmSnapshot(prepared);
        }

        @Override
        public void restore(HardwareWorkPreparationSnapshot snapshot) {
            throw new UnsupportedOperationException(
                    "hardware timing restores by recreating preparations");
        }

        @Override
        public boolean isBoundaryDriven() {
            return true;
        }

        @Override
        public boolean serviceBoundary(HardwareServiceBoundary boundary) {
            if (boundary != HardwareServiceBoundary.PRE_MAIN_LOOP || prepared) {
                return false;
            }
            prepared = true;
            return true;
        }
    }

    private record ArmSnapshot(boolean prepared)
            implements HardwareWorkPreparationSnapshot {
        @Override
        public HardwareWorkPreparation recreatePreparation() {
            return new ArmPreparation(prepared);
        }
    }
}
