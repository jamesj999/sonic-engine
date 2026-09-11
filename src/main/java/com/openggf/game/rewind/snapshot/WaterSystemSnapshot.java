package com.openggf.game.rewind.snapshot;

import java.util.Map;

/**
 * Snapshot of {@link com.openggf.level.WaterSystem} per-frame dynamic state.
 * Covers the water-entered counter and the per-zone dynamic water level
 * oscillation state. Static configuration (waterConfigs, underwater palettes)
 * is loaded at zone init and excluded.
 *
 * <p>Dynamic handler references are not captured — they are stateless lambdas
 * set at zone load and do not change during gameplay.
 */
public record WaterSystemSnapshot(
        int waterEnteredCounter,
        Map<String, DynamicWaterEntry> dynamicStates
) {
    public WaterSystemSnapshot {
        dynamicStates = Map.copyOf(dynamicStates);
    }

    /**
     * Numeric fields of one {@link com.openggf.level.WaterSystem.DynamicWaterState}
     * entry. The {@code handler} reference is excluded (stateless, set at zone load).
     */
    public record DynamicWaterEntry(
            int currentLevel,
            int targetLevel,
            int meanLevel,
            boolean rising,
            int speed,
            boolean enabled,
            boolean locked,
            int shakeTimer
    ) {}
}
