package com.openggf.trace.timing;

import com.openggf.game.timing.HardwareServiceBoundary;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable rewind state for the bounded hardware-timing replay ledger. */
public record HardwareTimingReplaySnapshot(
        HardwareTimingSchedule schedule,
        int edgeCursor,
        Set<String> consumedIdentities,
        Integer rawFrameLatch,
        HardwareServiceBoundary lastAppliedBoundary,
        boolean installed,
        boolean runComplete,
        List<String> unmatchedCompletions,
        List<String> pendingSubmissionsAtClose) {

    public HardwareTimingReplaySnapshot {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(consumedIdentities, "consumedIdentities");
        if (edgeCursor < 0 || edgeCursor > schedule.edges().size()) {
            throw new IllegalArgumentException(
                    "edgeCursor is outside the installed schedule: " + edgeCursor);
        }
        Objects.requireNonNull(unmatchedCompletions, "unmatchedCompletions");
        Objects.requireNonNull(
                pendingSubmissionsAtClose, "pendingSubmissionsAtClose");
        consumedIdentities = Set.copyOf(consumedIdentities);
        unmatchedCompletions = List.copyOf(unmatchedCompletions);
        pendingSubmissionsAtClose = List.copyOf(pendingSubmissionsAtClose);
    }
}
