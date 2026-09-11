package com.openggf.game;

/**
 * ROM-level offset used when positioning sidekicks relative to the main
 * player during level startup or trace/bootstrap repositioning.
 */
public record SidekickSpawnOffset(int xOffset, int yOffset) {
    public static final SidekickSpawnOffset S2_STYLE = new SidekickSpawnOffset(-40, 0);
}
