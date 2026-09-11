package com.openggf.game.rewind.snapshot;

/**
 * Immutable snapshot of the history-dependent persistent Plane B nametable.
 */
public record LevelTilemapSnapshot(
        byte[] descriptors,
        int originXTiles,
        int originYTiles,
        int alignedBgX,
        int alignedBgY,
        boolean baselineValid) {

    public LevelTilemapSnapshot {
        descriptors = descriptors == null ? new byte[0] : descriptors.clone();
    }

    @Override
    public byte[] descriptors() {
        return descriptors.clone();
    }

    public static LevelTilemapSnapshot invalid() {
        return new LevelTilemapSnapshot(new byte[0], 0, 0, 0, 0, false);
    }
}
