package com.openggf.game.resources;

/** Semantic ROM loop that owns one represented PLC VBlank. */
public enum PlcLifecyclePhase {
    LAG,
    TITLE_SCREEN,
    CONTINUE_SCREEN,
    LEVEL_SELECT,
    LEVEL_TITLE_CARD,
    ORDINARY_LEVEL,
    PALETTE_FADE,
    SPECIAL_STAGE,
    SPECIAL_STAGE_RESULTS,
    TWO_PLAYER_RESULTS,
    CREDITS_TEXT,
    CREDITS_DEMO,
    CREDITS_DEMO_FADE,
    ENDING,
    POST_CREDITS,
    NORMAL_PAUSE,
    SPECIAL_STAGE_PAUSE;

    public static PlcLifecyclePhase orElse(
            PlcLifecyclePhase phase, PlcLifecyclePhase fallback) {
        return phase == null ? fallback : phase;
    }
}
