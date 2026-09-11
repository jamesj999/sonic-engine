package com.openggf.game;

/**
 * Identifies whether a load is assembling a fresh playable runtime or only
 * decoding/restoring level state.
 */
public enum LevelAssemblyKind {
    DECODE_ONLY,
    FRESH_LEVEL_ASSEMBLY,
    STATE_RESTORATION
}
