package com.openggf.game.sonic3k.specialstage;

/**
 * Read-only per-frame snapshot of {@link Sonic3kSpecialStageManager} and its
 * {@link Sonic3kSpecialStagePlayer} state used by a trace replay harness to compare
 * engine state against a recorded ROM trace (multi-stage trace run spec addition #3).
 * <p>
 * Produced exclusively by {@link Sonic3kSpecialStageManager#captureComparisonState()}.
 * No mutators, no caching — every field is a pure read of existing manager/player
 * state at the moment of the call. Modeled on
 * {@link com.openggf.game.sonic2.specialstage.Sonic2SpecialStageComparisonState}.
 * <p>
 * {@code emeraldCollected} has no corresponding trace CSV column; it is included only
 * for snapshot completeness and is not itself a trace comparand.
 */
public record Sonic3kSpecialStageComparisonState(
        int playerX,
        int playerY,
        int angle,
        int velocity,
        int turning,
        int jumping,
        int fadeTimer,
        boolean started,
        int spheresLeft,
        int ringsCollected,
        int ringsLeft,
        int frameCounter,
        int clearRoutine,
        int clearTimer,
        boolean finished,
        boolean emeraldCollected) {
}
