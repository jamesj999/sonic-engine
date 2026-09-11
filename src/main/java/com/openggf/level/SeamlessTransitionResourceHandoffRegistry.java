package com.openggf.level;

import com.openggf.game.rewind.RewindSnapshottable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Gameplay-session owner for single-use seamless resource handoffs.
 */
public final class SeamlessTransitionResourceHandoffRegistry
        implements RewindSnapshottable<
                SeamlessTransitionResourceHandoffRegistry.Snapshot> {
    private long nextId;
    private final Map<SeamlessTransitionResourceHandoffId,
            SeamlessTransitionResourceHandoff> pending =
            new LinkedHashMap<>();
    private final Map<SeamlessTransitionResourceHandoffId,
            SeamlessTransitionResourceHandoff> failedTransfers =
            new LinkedHashMap<>();

    public SeamlessTransitionResourceHandoffId register(
            SeamlessTransitionResourceHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        SeamlessTransitionResourceHandoffId id =
                new SeamlessTransitionResourceHandoffId(nextId++);
        pending.put(id, handoff);
        return id;
    }

    public SeamlessTransitionResourceHandoff peek(
            SeamlessTransitionResourceHandoffId id) {
        rejectFailedTransfer(id);
        SeamlessTransitionResourceHandoff handoff = pending.get(id);
        if (handoff == null) {
            throw new IllegalStateException(
                    "missing or already-consumed seamless resource handoff "
                            + id);
        }
        return handoff;
    }

    public SeamlessTransitionResourceHandoff claim(
            SeamlessTransitionResourceHandoffId id) {
        rejectFailedTransfer(id);
        SeamlessTransitionResourceHandoff handoff = pending.remove(id);
        if (handoff == null) {
            throw new IllegalStateException(
                    "missing or already-consumed seamless resource handoff "
                            + id);
        }
        return handoff;
    }

    public void recordFailedTransfer(
            SeamlessTransitionResourceHandoffId id,
            SeamlessTransitionResourceHandoff handoff) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handoff, "handoff");
        pending.remove(id);
        if (failedTransfers.putIfAbsent(id, handoff) != null) {
            throw new IllegalStateException(
                    "seamless resource handoff transfer already failed " + id);
        }
    }

    public boolean hasFailedTransfer(SeamlessTransitionResourceHandoffId id) {
        return failedTransfers.containsKey(id);
    }

    public SeamlessTransitionResourceHandoff failedTransfer(
            SeamlessTransitionResourceHandoffId id) {
        SeamlessTransitionResourceHandoff handoff = failedTransfers.get(id);
        if (handoff == null) {
            throw new IllegalStateException(
                    "seamless resource handoff has no failed transfer " + id);
        }
        return handoff;
    }

    private void rejectFailedTransfer(SeamlessTransitionResourceHandoffId id) {
        if (failedTransfers.containsKey(id)) {
            throw new IllegalStateException(
                    "seamless resource handoff transfer failed terminally " + id);
        }
    }

    @Override
    public String key() {
        return "seamless_transition_resource_handoffs";
    }

    @Override
    public Snapshot capture() {
        return new Snapshot(
                nextId, Map.copyOf(pending), Map.copyOf(failedTransfers));
    }

    @Override
    public void restore(Snapshot snapshot) {
        nextId = snapshot.nextId();
        pending.clear();
        pending.putAll(snapshot.pending());
        failedTransfers.clear();
        failedTransfers.putAll(snapshot.failedTransfers());
    }

    @Override
    public void resetForMissingSnapshot() {
        nextId = 0;
        pending.clear();
        failedTransfers.clear();
    }

    public record Snapshot(
            long nextId,
            Map<SeamlessTransitionResourceHandoffId,
                    SeamlessTransitionResourceHandoff> pending,
            Map<SeamlessTransitionResourceHandoffId,
                    SeamlessTransitionResourceHandoff> failedTransfers) {
        public Snapshot {
            pending = Map.copyOf(pending);
            failedTransfers = Map.copyOf(failedTransfers);
        }
    }
}
