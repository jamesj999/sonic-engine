package com.openggf.tools.audio.parity.s2;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import com.openggf.tools.audio.completerun.s2.S2NativeSoundResolver;
import com.openggf.tools.audio.completerun.s2.S2ProductionRequestProjector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only comparison seam for an explicit, unbound request-aware candidate.
 * It is deliberately absent from production bytecode and every profile/CLI.
 */
final class S2RequestAwareCandidateComparator {
    private static final Map<CompleteRunAudioTrace.RawAudioRequest,
            CompleteRunAudioTrace.NativeSoundIdentity> IDENTITIES =
            S2NativeSoundResolver.rev01().nativeRequestIdentities();

    private S2RequestAwareCandidateComparator() {
    }

    enum Kind { MATCH, DIVERGENCE, INVALID }

    record Report(Kind kind, int comparedTransfers, String detail) {
        String describe() {
            return "S2 unbound request candidate: " + kind + ": " + detail;
        }
    }

    record RequestObservation(CompleteRunAudioTrace.OwnerClass ownerClass,
            String contentKey, int nativeId, int physicalSlot, int row) {
    }

    /**
     * The same comparison for a published window other than the original one,
     * over an engine capture that spans several windows. Only the rows inside
     * the window take part; the engine ran exactly as it would have anyway.
     */
    static Report compareWindow(Path candidate,
            S2RequestAwareOracleSchema.Window window,
            S2ProductionRequestProjector projector, List<Integer> requestRows) {
        Objects.requireNonNull(window, "window");
        return compare(candidate, window, projector, requestRows);
    }

    static Report compare(Path candidate, S2ProductionRequestProjector projector,
            List<Integer> requestRows) {
        return compare(candidate, S2RequestAwareOracleSchema.CONTROL, projector,
                requestRows);
    }

    private static Report compare(Path candidate,
            S2RequestAwareOracleSchema.Window window,
            S2ProductionRequestProjector projector, List<Integer> requestRows) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(projector, "projector");
        Objects.requireNonNull(requestRows, "requestRows");
        S2RequestAwareOracleRawStream.Result reference;
        try {
            reference = S2RequestAwareOracleRawStream
                    .scanWindowSourceCandidateForTesting(candidate, window);
        } catch (IOException | RuntimeException failure) {
            return new Report(Kind.INVALID, 0,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }

        List<RequestObservation> expected = expected(reference);
        if (requestRows.size() != projector.requests().size()) {
            return new Report(Kind.INVALID, 0,
                    "production request rows=" + requestRows.size()
                            + " transfers=" + projector.requests().size());
        }
        List<RequestObservation> actual = new ArrayList<>();
        for (int index = 0; index < projector.requests().size(); index++) {
            int row = requestRows.get(index);
            if (row < window.firstRow() || row >= window.exclusiveEnd()) {
                continue;
            }
            CompleteRunAudioTrace.Request request = projector.requests().get(index);
            actual.add(new RequestObservation(request.ownerClass(),
                    request.contentKey(), request.nativeId(), request.queueSlot(),
                    row));
        }
        int shared = Math.min(expected.size(), actual.size());
        for (int index = 0; index < shared; index++) {
            if (!expected.get(index).equals(actual.get(index))) {
                return new Report(Kind.DIVERGENCE, index,
                        "transfer[" + index + "] expected=" + expected.get(index)
                                + " actual=" + actual.get(index));
            }
        }
        if (expected.size() != actual.size()) {
            return new Report(Kind.DIVERGENCE, shared,
                    "transfer count expected=" + expected.size()
                            + " actual=" + actual.size());
        }
        return new Report(Kind.MATCH, expected.size(),
                expected.size() + " production transfers agree");
    }

    private static List<RequestObservation> expected(
            S2RequestAwareOracleRawStream.Result reference) {
        List<RequestObservation> result = new ArrayList<>();
        for (S2RequestAwareOracleRawStream.Frame frame : reference.frames()) {
            for (S2RequestAwareOracleRawStream.RequestTransfer transfer
                    : frame.requestTransfers()) {
                CompleteRunAudioTrace.OwnerClass owner = owner(transfer.requestByte());
                CompleteRunAudioTrace.NativeSoundIdentity identity = IDENTITIES.get(
                        new CompleteRunAudioTrace.RawAudioRequest(owner,
                                transfer.requestByte(), "sound_queue",
                                transfer.physicalSlot()));
                // Slot 3 is the shipped VoiceTblPtr alias and F1-F7 are invalid;
                // neither is a canonical request in the production projector.
                if (identity != null) {
                    result.add(new RequestObservation(identity.ownerClass(),
                            identity.contentKey(), transfer.requestByte(),
                            transfer.physicalSlot(), transfer.sourceRow()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static CompleteRunAudioTrace.OwnerClass owner(int nativeId) {
        if (nativeId < 0xA0) {
            return CompleteRunAudioTrace.OwnerClass.MUSIC;
        }
        return nativeId >= 0xF8
                ? CompleteRunAudioTrace.OwnerClass.COMMAND
                : CompleteRunAudioTrace.OwnerClass.SFX;
    }
}

