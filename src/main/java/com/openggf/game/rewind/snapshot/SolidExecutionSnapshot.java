package com.openggf.game.rewind.snapshot;

import com.openggf.game.solid.ContactKind;

import java.util.List;

/**
 * Snapshot record for the solid execution registry.
 *
 * <p>{@code DefaultSolidExecutionRegistry} keeps previous-frame standing state
 * keyed by live object/player references. Rewind serializes only the stable
 * identities needed to rebuild that map after {@code object-manager} has
 * re-instantiated placement-backed objects: object spawn index plus playable
 * sprite code.
 */
public record SolidExecutionSnapshot(List<PreviousStandingEntry> previousStanding) {
    public SolidExecutionSnapshot {
        previousStanding = List.copyOf(previousStanding);
    }

    public record PreviousStandingEntry(
            int spawnIndex,
            String playerCode,
            ContactKind kind,
            boolean standing,
            boolean pushing) {}
}
