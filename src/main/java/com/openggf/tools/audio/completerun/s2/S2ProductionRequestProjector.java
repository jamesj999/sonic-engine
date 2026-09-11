package com.openggf.tools.audio.completerun.s2;

import com.openggf.game.sonic2.audio.Sonic2SoundRequestPipeline;
import com.openggf.game.sonic2.audio.Sonic2SoundRequestService;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Output-only projection of committed OpenGGF Sonic 2 request transitions.
 *
 * <p>The projector retains only facts published by the production request service. Native
 * identities come from the REV01 playlist/queue inventory cited by {@link S2NativeSoundResolver};
 * no chip output, fixture row, or reference producer participates.</p>
 */
public final class S2ProductionRequestProjector
        implements Consumer<Sonic2SoundRequestService.Event> {
    private static final List<CompleteRunAudioTrace.HardwareRole> HARDWARE_ROLES =
            List.of(CompleteRunAudioTrace.HardwareRole.values());
    private static final Map<CompleteRunAudioTrace.RawAudioRequest,
            CompleteRunAudioTrace.NativeSoundIdentity> IDENTITIES =
            S2NativeSoundResolver.rev01().nativeRequestIdentities();

    private final List<CompleteRunAudioTrace.Request> requests = new ArrayList<>();
    private final List<MusicSubmission> musicSubmissions = new ArrayList<>();
    private final List<CompleteRunAudioTrace.Decision> decisions = new ArrayList<>();
    private final Map<Sonic2SoundRequestPipeline.QueueSlot, ObservedRequest> queued =
            new EnumMap<>(Sonic2SoundRequestPipeline.QueueSlot.class);
    private PendingDecision pendingDispatch;

    @Override
    public void accept(Sonic2SoundRequestService.Event event) {
        if (event instanceof Sonic2SoundRequestService.Submission submission) {
            observeSubmission(submission);
        } else if (event instanceof Sonic2SoundRequestService.Transfer transfer) {
            observeTransfer(transfer);
        } else if (event instanceof Sonic2SoundRequestService.Decision decision) {
            observeDecision(decision);
        } else if (event instanceof Sonic2SoundRequestService.Dispatch dispatch) {
            observeDispatch(dispatch);
        }
    }

    public List<CompleteRunAudioTrace.Request> requests() {
        return List.copyOf(requests);
    }

    /**
     * Every request the production service submitted to a music mailbox, in
     * submission order and carrying the native request byte.
     *
     * <p>The ROM has two request stores. {@code PlaySound} writes a sound
     * effect into the three-entry queue with
     * {@code move.b d0,$09(a1,d1.w)}, while {@code PlayMusic} writes the music
     * mailbox with {@code move.b d0,8(a1)} (docs/s2disasm/s2.asm:1302-1304).
     * The queue projection above models the first store; this list models the
     * second, so a request the ROM sends through {@code PlayMusic} is visible
     * even though it never enters the queue.
     */
    public List<MusicSubmission> musicSubmissions() {
        return List.copyOf(musicSubmissions);
    }

    /** One production submission to a music mailbox. */
    public record MusicSubmission(Sonic2SoundRequestPipeline.SourceSlot sourceMailbox,
            int nativeRequestId) {
    }

    public List<CompleteRunAudioTrace.Decision> decisions() {
        return List.copyOf(decisions);
    }

    private void observeSubmission(Sonic2SoundRequestService.Submission submission) {
        if (submission.sourceMailbox() != Sonic2SoundRequestPipeline.SourceSlot.MUSIC0
                && submission.sourceMailbox() != Sonic2SoundRequestPipeline.SourceSlot.MUSIC1) {
            return;
        }
        musicSubmissions.add(new MusicSubmission(submission.sourceMailbox(),
                submission.rawRequestId()));
    }

    private void observeTransfer(Sonic2SoundRequestService.Transfer transfer) {
        if (transfer.slot3Alias()) {
            return;
        }
        Sonic2SoundRequestPipeline.QueueSlot slot =
                Sonic2SoundRequestPipeline.QueueSlot.values()[transfer.physicalSlot()];
        CompleteRunAudioTrace.NativeSoundIdentity identity = identity(
                transfer.rawRequestId(), transfer.physicalSlot());
        if (identity == null) {
            queued.remove(slot);
            return;
        }
        CompleteRunAudioTrace.Request request = new CompleteRunAudioTrace.Request(
                transfer.ordinal(), identity.ownerClass(), identity.contentKey(),
                transfer.rawRequestId(), "sound_queue", transfer.physicalSlot());
        requests.add(request);
        queued.put(slot, new ObservedRequest(request, identity));
    }

    private void observeDecision(Sonic2SoundRequestService.Decision decision) {
        ObservedRequest request = queued.remove(decision.queueSlot());
        if (request == null
                || decision.reason() == Sonic2SoundRequestService.DecisionReason.INVALID_DISCARD) {
            return;
        }
        if (decision.reason()
                == Sonic2SoundRequestService.DecisionReason.REJECTED_PRIORITY) {
            decisions.add(canonicalDecision(request.request().ordinal(), request.identity(),
                    false, "rejected_priority", decision.priorityBefore(),
                    decision.priorityAfter()));
            return;
        }
        pendingDispatch = new PendingDecision(request, decision.priorityBefore(),
                decision.priorityAfter());
    }

    private void observeDispatch(Sonic2SoundRequestService.Dispatch dispatch) {
        PendingDecision pending = pendingDispatch;
        pendingDispatch = null;
        if (pending == null) {
            return;
        }
        CompleteRunAudioTrace.NativeSoundIdentity resolved = identity(
                pending.request().identity().ownerClass(), dispatch.selectedRequestId(),
                pending.request().request().queueSlot());
        if (resolved == null) {
            return;
        }
        boolean accepted = dispatch.kind()
                == Sonic2SoundRequestPipeline.DispatchKind.NOT_YET_DISPATCHED;
        decisions.add(canonicalDecision(pending.request().request().ordinal(), resolved,
                accepted, accepted ? "accepted" : "suppressed",
                pending.priorityBefore(), pending.priorityAfter()));
    }

    private static CompleteRunAudioTrace.Decision canonicalDecision(
            long ordinal, CompleteRunAudioTrace.NativeSoundIdentity identity,
            boolean accepted, String reason, int priorityBefore,
            int priorityAfter) {
        return new CompleteRunAudioTrace.Decision(ordinal, identity.nativeId(),
                identity.contentKey(), accepted, reason, priorityBefore,
                priorityAfter, HARDWARE_ROLES, null);
    }

    private static CompleteRunAudioTrace.NativeSoundIdentity identity(
            int rawId, int slot) {
        CompleteRunAudioTrace.OwnerClass owner = rawId < 0xA0
                ? CompleteRunAudioTrace.OwnerClass.MUSIC
                : rawId >= 0xF8
                        ? CompleteRunAudioTrace.OwnerClass.COMMAND
                        : CompleteRunAudioTrace.OwnerClass.SFX;
        return identity(owner, rawId, slot);
    }

    private static CompleteRunAudioTrace.NativeSoundIdentity identity(
            CompleteRunAudioTrace.OwnerClass owner, int rawId, Integer slot) {
        return IDENTITIES.get(new CompleteRunAudioTrace.RawAudioRequest(
                owner, rawId, "sound_queue", slot));
    }

    private record ObservedRequest(CompleteRunAudioTrace.Request request,
                                   CompleteRunAudioTrace.NativeSoundIdentity identity) { }

    private record PendingDecision(ObservedRequest request, int priorityBefore,
                                   int priorityAfter) { }
}
