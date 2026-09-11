package com.openggf.game;

/**
 * Says who paces a special stage's ROM entry sequence. Every provider steps
 * the sequence's V-int waits itself frame by frame (the entry fade-to-white
 * is visible over the level and the fade-from-white is the reveal, so none of
 * it is hidden startup); only the ROM's masked-interrupt load span differs.
 */
public enum SpecialStageStartupPolicy {
    /** Ordinary play: no row driver exists and the load span is skipped. */
    FAST,
    /**
     * A BK2-driven session paces the entry: recorded lag rows are admitted by
     * the timing port, so the provider must add no span of its own.
     */
    TRACE_ACCURATE
}
