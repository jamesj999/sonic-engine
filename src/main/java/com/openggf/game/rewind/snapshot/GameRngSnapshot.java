package com.openggf.game.rewind.snapshot;

import com.openggf.game.GameRng;

/**
 * Immutable capture of GameRng state for rewind snapshots.
 * Captures the 32-bit seed and the RNG flavour (S1_S2 vs S3K).
 */
public record GameRngSnapshot(
        long seed,
        GameRng.Flavour flavour) {
}
