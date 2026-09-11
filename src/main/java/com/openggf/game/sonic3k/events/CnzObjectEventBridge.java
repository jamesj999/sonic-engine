package com.openggf.game.sonic3k.events;

/**
 * Narrow write surface for CNZ object code that needs to mutate level-event state.
 *
 * <p>CNZ relies on object-owned routines to poke a small set of event variables
 * that the ROM stores in the shared {@code Events_fg_*} / {@code Events_bg_*}
 * work area. Keeping that surface explicit avoids scattering hidden event-state
 * mutations through object code and preserves a traceable mapping back to
 * {@code Obj_CNZMinibossScrollControl}, the water helpers, and the Knuckles
 * teleporter route.
 *
 * <p>The arena-destruction hook is intentionally a single pending request in
 * Slice 0. The name reflects that this bridge does not yet provide FIFO queue
 * semantics.
 */
public interface CnzObjectEventBridge {
    void setPendingArenaChunkDestruction(int chunkWorldX, int chunkWorldY);
    void setBossScrollState(int offsetY, int velocityY);
    void signalMinibossDefeatedForScrollControl();
    boolean consumeMinibossDefeatSignalForScrollControl();
    void advanceMinibossBackgroundRoutineAfterScrollSnap();
    void setBossFlag(boolean value);
    void setEventsFg5(boolean value);
    void setWallGrabSuppressed(boolean value);
    void setWaterButtonArmed(boolean value);
    boolean isWaterButtonArmed();
    void setWaterTargetY(int targetY);
    /** ROM: {@code move.w #...,(Mean_water_level).w} — seed the surface height now. */
    void setWaterMeanLevel(int meanY);
    /** ROM: {@code move.w #frames,(Screen_shake_flag).w} — start a timed shake. */
    void triggerScreenShake(int frames);
    default void requestSidekickBoundsPublishAfterCameraEasing() {
    }
    void beginKnucklesTeleporterRoute();
    void endKnucklesTeleporterRoute();
    void markTeleporterBeamSpawned();
}
