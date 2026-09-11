package com.openggf.game.rewind.encounter;

import com.openggf.tests.rules.SonicGame;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Catalog entry for a focused rewind encounter validation.
 *
 * <p>Scenarios compare one engine forward-only run against the same engine
 * after a rewind+replay path. Trace files provide input only; ROM trace state
 * is not used as an oracle here.
 */
public record RewindEncounterScenario(
        String id,
        SonicGame game,
        String zone,
        int act,
        String objectFamily,
        String mechanic,
        Path traceDirectory,
        int zoneIndex,
        int actIndex,
        int rewindStartFrame,
        int compareFrame,
        List<String> snapshotKeys) {

    public RewindEncounterScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(objectFamily, "objectFamily");
        Objects.requireNonNull(mechanic, "mechanic");
        Objects.requireNonNull(traceDirectory, "traceDirectory");
        snapshotKeys = List.copyOf(Objects.requireNonNull(snapshotKeys, "snapshotKeys"));
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (act < 1) {
            throw new IllegalArgumentException("act must be 1-based");
        }
        if (zoneIndex < 0 || actIndex < 0) {
            throw new IllegalArgumentException("zoneIndex and actIndex must be zero-based and non-negative");
        }
        if (rewindStartFrame < 0) {
            throw new IllegalArgumentException("rewindStartFrame must be >= 0");
        }
        if (compareFrame <= rewindStartFrame) {
            throw new IllegalArgumentException("compareFrame must be after rewindStartFrame");
        }
        if (snapshotKeys.isEmpty()) {
            throw new IllegalArgumentException("snapshotKeys must not be empty");
        }
    }
}
