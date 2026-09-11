package com.openggf.trace;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable, defensive projection of engine state captured at frame 0 for
 * comparison against the recorded ROM frame-1 snapshots.
 *
 * <p>Fields here mirror the recorder schema:
 * <ul>
 *     <li>Player position-history rings (x, y, input, status) at 64 entries
 *         each, indexed by the engine's ring-buffer position.</li>
 *     <li>Sidekick CPU view ({@link SidekickCpuView}) - may be {@code null}
 *         when no sidekick is active.</li>
 *     <li>Per-slot object snapshots keyed by slot index ({@link ObjectSnapshot}).</li>
 * </ul>
 *
 * <p>All array references are defensively copied at construction time so the
 * snapshot never aliases live engine state. Callers may freely keep the
 * record across frames without worrying that subsequent engine writes will
 * mutate it.
 */
public record EngineSnapshot(
        short[] playerXHistory,
        short[] playerYHistory,
        short[] playerInputHistory,
        byte[] playerStatusHistory,
        int playerHistoryPos,
        SidekickCpuView tailsCpu,
        Map<Integer, ObjectSnapshot> slotStates,
        int levelStartX) {

    /**
     * Sentinel for {@link #levelStartX()} when the zone's ROM
     * {@code StartLocations} entry could not be resolved (no live level, or a
     * synthetic snapshot built by a comparator unit test). Comparators fall
     * back to their unconditional behaviour when they see it.
     */
    public static final int LEVEL_START_X_UNKNOWN = Integer.MIN_VALUE;

    /**
     * Convenience constructor for callers with no live level behind them.
     * Leaves {@link #levelStartX()} unknown.
     */
    public EngineSnapshot(short[] playerXHistory, short[] playerYHistory,
                          short[] playerInputHistory, byte[] playerStatusHistory,
                          int playerHistoryPos, SidekickCpuView tailsCpu,
                          Map<Integer, ObjectSnapshot> slotStates) {
        this(playerXHistory, playerYHistory, playerInputHistory, playerStatusHistory,
                playerHistoryPos, tailsCpu, slotStates, LEVEL_START_X_UNKNOWN);
    }

    /**
     * Compact constructor enforces defensive copies for the array members and
     * an unmodifiable map view for {@link #slotStates()}.
     */
    public EngineSnapshot {
        playerXHistory = copyOrEmpty(playerXHistory);
        playerYHistory = copyOrEmpty(playerYHistory);
        playerInputHistory = copyOrEmpty(playerInputHistory);
        playerStatusHistory = copyOrEmpty(playerStatusHistory);
        slotStates = slotStates == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new HashMap<>(slotStates));
    }

    /**
     * Snapshot of the engine's sidekick CPU controller for cross-reference
     * with the recorded {@code cpu_state_snapshot} event.
     */
    public record SidekickCpuView(
            int controlCounter,
            int respawnCounter,
            int cpuRoutine,
            short targetX,
            short targetY,
            int interactId,
            boolean jumping) {
    }

    /**
     * Minimal per-slot object projection. v1 covers the cardinal SST fields
     * (objectType + x_pos + y_pos + routine + status). Broader SST coverage
     * is deliberately deferred to follow-up work once these five fields are
     * stable across the S2 zone trace set.
     */
    public record ObjectSnapshot(
            int objectType,
            int xPos,
            int yPos,
            int routine,
            int status) {
    }

    /**
     * Factory that wraps prebuilt parts in an {@link EngineSnapshot}. Used by
     * {@code AbstractTraceReplayTest.captureEngineSnapshot} (production path,
     * pulls the parts from the live {@code HeadlessTestFixture}) and by
     * synthetic unit tests in {@code com.openggf.trace} that exercise the
     * comparator without dragging in fixture state. Sidekick CPU and per-slot
     * object snapshots are optional ({@code null} / empty map) — the
     * comparator emits WARNING entries for absent fields rather than failing.
     */
    public static EngineSnapshot capture(short[] xHistory, short[] yHistory,
                                         short[] inputHistory, byte[] statusHistory,
                                         int historyPos,
                                         SidekickCpuView tailsCpu,
                                         Map<Integer, ObjectSnapshot> slotStates) {
        return new EngineSnapshot(xHistory, yHistory, inputHistory, statusHistory,
                historyPos, tailsCpu, slotStates, LEVEL_START_X_UNKNOWN);
    }

    /**
     * As {@link #capture(short[], short[], short[], byte[], int, SidekickCpuView, Map)}
     * but carrying the loaded zone/act's ROM {@code StartLocations} X, which
     * lets the bootstrap comparator tell which {@code LevelSizeLoad} branch a
     * replay's level load corresponds to.
     */
    public static EngineSnapshot capture(short[] xHistory, short[] yHistory,
                                         short[] inputHistory, byte[] statusHistory,
                                         int historyPos,
                                         SidekickCpuView tailsCpu,
                                         Map<Integer, ObjectSnapshot> slotStates,
                                         int levelStartX) {
        return new EngineSnapshot(xHistory, yHistory, inputHistory, statusHistory,
                historyPos, tailsCpu, slotStates, levelStartX);
    }

    private static short[] copyOrEmpty(short[] src) {
        if (src == null) {
            return new short[0];
        }
        short[] dst = new short[src.length];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    private static byte[] copyOrEmpty(byte[] src) {
        if (src == null) {
            return new byte[0];
        }
        byte[] dst = new byte[src.length];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }
}
