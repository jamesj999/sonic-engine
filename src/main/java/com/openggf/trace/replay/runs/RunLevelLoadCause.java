package com.openggf.trace.replay.runs;

/**
 * Structural reason for a level load that production code has already chosen.
 * The value observes lifecycle; it never requests or alters a load.
 */
public enum RunLevelLoadCause {
    ORDINARY,
    LEVEL_ADVANCE,
    DEATH_RESTART,
    INTERIOR_RETURN
}
