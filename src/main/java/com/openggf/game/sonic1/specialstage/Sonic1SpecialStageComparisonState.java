package com.openggf.game.sonic1.specialstage;

/**
 * Read-only per-frame snapshot of {@link Sonic1SpecialStageManager} state used
 * by a trace replay harness to compare engine state against a recorded ROM
 * trace (multi-stage trace run spec addition #2).
 *
 * <p>Produced exclusively by {@link Sonic1SpecialStageManager#captureComparisonState()}.
 * No mutators, no caching — every field is a pure read of existing manager
 * state at the moment of the call. Modeled on
 * {@link com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageComparisonState}.
 *
 * <p>{@code sonicPosX}/{@code sonicPosY} keep the manager's full 16.16
 * fixed-point layout (top 16 bits = pixel), matching the trace's raw
 * {@code obX}/{@code obY} longword reads.
 */
public record Sonic1SpecialStageComparisonState(
        long sonicPosX,
        long sonicPosY,
        int sonicVelX,
        int sonicVelY,
        int sonicInertia,
        boolean sonicAirborne,
        boolean sonicFacingLeft,
        int ssAngle,
        int ssRotate,
        int bgAnimState,
        int ringsCollected,
        boolean emeraldCollected,
        boolean exitTriggered,
        boolean finished,
        int currentStage) {
}
