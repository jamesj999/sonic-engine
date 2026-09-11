package com.openggf.game.rewind.snapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable capture of TimerManager state for rewind snapshots.
 * Captures the map of registered timers' code-to-state mappings.
 * Each timer's state includes its code and remaining tick count.
 */
public record TimerManagerSnapshot(
        Map<String, TimerState> timerStates) {

    public record TimerState(String code, int ticks) {
    }

    public TimerManagerSnapshot {
        Objects.requireNonNull(timerStates, "timerStates");
        // Defensive copy so the record is truly immutable
        timerStates = new HashMap<>(timerStates);
    }

    @Override
    public Map<String, TimerState> timerStates() {
        return new HashMap<>(timerStates);
    }
}
