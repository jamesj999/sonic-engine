package com.openggf.trace;

public enum TraceExecutionPhase {
    FULL_LEVEL_FRAME,
    FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD,
    PLAYABLE_ANIMATION_ONLY,
    ADVANCE_ONLY,
    VBLANK_ONLY
}
