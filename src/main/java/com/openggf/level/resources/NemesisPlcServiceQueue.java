package com.openggf.level.resources;

import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.resources.QueueServiceObservation;
import com.openggf.level.resources.PlcParser.PlcDefinition;
import com.openggf.level.resources.PlcParser.PlcEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Logical FIFO for S1/S2 Nemesis PLC workload. A caller explicitly prepares the next entry;
 * servicing never implicitly advances the queue.
 */
public final class NemesisPlcServiceQueue {
    private NemesisPlcQueueSnapshot.Entry activeEntry;
    private final ArrayDeque<NemesisPlcQueueSnapshot.Entry> queuedEntries = new ArrayDeque<>();

    /** Replaces the waiting queue, but never interrupts an entry already being serviced. */
    public void replaceQueued(PlcDefinition definition, List<Integer> patternCounts) {
        List<NemesisPlcQueueSnapshot.Entry> replacement = submission(definition, patternCounts);
        requireIdleDecoder();
        queuedEntries.clear();
        queuedEntries.addAll(replacement);
    }

    /** Adds all entries in a PLC definition at the tail of the logical FIFO. */
    public void append(PlcDefinition definition, List<Integer> patternCounts) {
        queuedEntries.addAll(submission(definition, patternCounts));
    }

    /** Clears all waiting entries, but never interrupts an entry already being serviced. */
    public void clearQueued() {
        requireIdleDecoder();
        queuedEntries.clear();
    }

    /** Moves the current FIFO head into the prepared service slot without consuming any work. */
    public void prepareHead() {
        if (activeEntry == null) {
            activeEntry = queuedEntries.pollFirst();
        }
    }

    /**
     * Services up to {@code patternBudget} patterns from the prepared head.
     * Finishing an entry leaves the next entry queued and unprepared.
     */
    public void servicePatterns(int patternBudget) {
        if (patternBudget <= 0) {
            throw new IllegalArgumentException("patternBudget must be positive");
        }
        if (activeEntry == null) {
            return;
        }
        int remaining = activeEntry.remainingPatterns() - Math.min(
                patternBudget, activeEntry.remainingPatterns());
        if (remaining == 0) {
            activeEntry = null;
        } else {
            activeEntry = new NemesisPlcQueueSnapshot.Entry(
                    activeEntry.sourceAddress(), activeEntry.destinationTile(),
                    activeEntry.totalPatterns(), remaining);
        }
    }

    /** Returns whether the active slot or any unprepared FIFO entry remains. */
    public boolean isBusy() {
        return activeEntry != null || !queuedEntries.isEmpty();
    }

    /** Returns the number of descriptors waiting behind the separate active service slot. */
    public int queuedEntryCount() {
        return queuedEntries.size();
    }

    /** Captures immutable active and FIFO state for rewind. */
    public NemesisPlcQueueSnapshot capture() {
        return new NemesisPlcQueueSnapshot(activeEntry, List.copyOf(queuedEntries));
    }

    public QueueDiagnosticSnapshot captureDiagnostics(
            QueueDiagnosticSnapshot.Kind kind,
            List<QueueServiceObservation> observations) {
        observations = List.of();
        List<String> waiting = queuedEntries.stream()
                .map(entry -> QueueDiagnosticSnapshot.fingerprint(
                        kind, entry.sourceAddress(), entry.destinationTile(),
                        entry.totalPatterns()))
                .toList();
        if (activeEntry == null) {
            if (waiting.isEmpty()) {
                return QueueDiagnosticSnapshot.idle(kind, observations);
            }
            return new QueueDiagnosticSnapshot(
                    kind, true, false, -1, -1, -1, -1,
                    waiting, observations);
        }
        return new QueueDiagnosticSnapshot(
                kind, true, true,
                -1, -1, -1, activeEntry.remainingPatterns(),
                waiting, observations);
    }

    /** Restores a validated immutable snapshot without partially mutating live queue state. */
    public void restore(NemesisPlcQueueSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        NemesisPlcQueueSnapshot.Entry restoredActive = snapshot.activeEntry();
        if (restoredActive != null) {
            validateActiveEntry(restoredActive);
        }
        List<NemesisPlcQueueSnapshot.Entry> restoredQueued = new ArrayList<>(snapshot.queuedEntries().size());
        for (NemesisPlcQueueSnapshot.Entry entry : snapshot.queuedEntries()) {
            validateQueuedEntry(entry);
            restoredQueued.add(entry);
        }

        activeEntry = restoredActive;
        queuedEntries.clear();
        queuedEntries.addAll(restoredQueued);
    }

    private static List<NemesisPlcQueueSnapshot.Entry> submission(
            PlcDefinition definition, List<Integer> patternCounts) {
        Objects.requireNonNull(definition, "definition");
        List<PlcEntry> entries = Objects.requireNonNull(definition.entries(), "definition.entries");
        Objects.requireNonNull(patternCounts, "patternCounts");
        if (entries.size() != patternCounts.size()) {
            throw new IllegalArgumentException("patternCounts must match PLC entry count");
        }

        List<NemesisPlcQueueSnapshot.Entry> submitted = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            PlcEntry entry = Objects.requireNonNull(entries.get(index), "PLC entry");
            Integer patternCount = Objects.requireNonNull(patternCounts.get(index), "pattern count");
            if (entry.romAddr() < 0 || entry.tileIndex() < 0 || patternCount <= 0) {
                throw new IllegalArgumentException("PLC entries require nonnegative addresses and positive patterns");
            }
            submitted.add(new NemesisPlcQueueSnapshot.Entry(
                    entry.romAddr(), entry.tileIndex(), patternCount, patternCount));
        }
        return submitted;
    }

    private void requireIdleDecoder() {
        if (activeEntry != null) {
            throw new IllegalStateException("cannot mutate queued PLC entries while the decoder is active");
        }
    }

    private static void validateActiveEntry(NemesisPlcQueueSnapshot.Entry entry) {
        validateEntryIdentityAndTotal(entry);
        if (entry.remainingPatterns() <= 0 || entry.remainingPatterns() > entry.totalPatterns()) {
            throw new IllegalArgumentException("active PLC snapshot has impossible remaining patterns");
        }
    }

    private static void validateQueuedEntry(NemesisPlcQueueSnapshot.Entry entry) {
        validateEntryIdentityAndTotal(entry);
        if (entry.remainingPatterns() != entry.totalPatterns()) {
            throw new IllegalArgumentException("unprepared PLC snapshot entry must have full remaining patterns");
        }
    }

    private static void validateEntryIdentityAndTotal(NemesisPlcQueueSnapshot.Entry entry) {
        Objects.requireNonNull(entry, "PLC snapshot entry");
        if (entry.sourceAddress() < 0 || entry.destinationTile() < 0 || entry.totalPatterns() <= 0) {
            throw new IllegalArgumentException("PLC snapshot entry is invalid");
        }
    }
}
