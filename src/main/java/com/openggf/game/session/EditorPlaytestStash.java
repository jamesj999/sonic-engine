package com.openggf.game.session;

/**
 * Immutable snapshot of gameplay state preserved while the editor is active.
 * <p>
 * This stays intentionally narrow for the first editor slice: just the player-centric
 * data needed to resume play-test from the editor cursor without a full rebuild.
 */
public record EditorPlaytestStash(
        int playerX,
        int playerY,
        int xVelocity,
        int yVelocity,
        boolean facingRight,
        int rings,
        int shieldState
) {
}
