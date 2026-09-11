package com.openggf.game.rewind.snapshot;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable snapshot of {@link com.openggf.game.AbstractLevelEventManager} state.
 *
 * <p>Captures the base-class scalars (zone/act, routine counters, frame counter,
 * timer, boss flag) together with the event-data arrays and an opaque
 * {@code extra} byte blob that each subclass encodes via its
 * {@code captureExtra}/{@code restoreExtra} hooks.
 *
 * <p>The {@code extra} field is null when the subclass has no additional state
 * to persist (i.e. when {@code captureExtra()} returns null).
 */
public record LevelEventSnapshot(
        int currentZone,
        int currentAct,
        int eventRoutineFg,
        int eventRoutineBg,
        int frameCounter,
        int timerFrames,
        boolean bossActive,
        short[] eventDataFg,
        byte[] eventDataBg,
        byte[] extra) {

    public LevelEventSnapshot {
        // Defensive copies so the record is truly immutable.
        eventDataFg = eventDataFg != null ? eventDataFg.clone() : null;
        eventDataBg = eventDataBg != null ? eventDataBg.clone() : null;
        extra       = extra       != null ? extra.clone()       : null;
    }

    /** Returns a defensive copy of the eventDataFg array (or null). */
    @Override
    public short[] eventDataFg() {
        return eventDataFg != null ? eventDataFg.clone() : null;
    }

    /** Returns a defensive copy of the eventDataBg array (or null). */
    @Override
    public byte[] eventDataBg() {
        return eventDataBg != null ? eventDataBg.clone() : null;
    }

    /** Returns a defensive copy of the extra blob (or null). */
    @Override
    public byte[] extra() {
        return extra != null ? extra.clone() : null;
    }
}
