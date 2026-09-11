package com.openggf.game;

import com.openggf.level.WaterSystem;

/**
 * Per-frame dynamic water level handler. Each zone with dynamic water
 * (rising/falling based on camera position) provides an implementation.
 * Called once per frame from {@link WaterSystem#update()}.
 */
public interface DynamicWaterHandler {
    /**
     * Update water target/mean based on current camera position.
     *
     * @param state   mutable water state (current level, target, speed)
     * @param cameraX camera X position in world pixels
     * @param cameraY camera Y position in world pixels
     */
    void update(WaterSystem.DynamicWaterState state, int cameraX, int cameraY);

    /**
     * Whether this handler's positive shake countdown is owned by a native SST
     * timer object rather than only by global water state.
     */
    default boolean shakeTimerOccupiesObjectSlot() {
        return false;
    }
}
