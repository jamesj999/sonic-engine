package com.openggf.level;

/**
 * Foreground and background tilemap bytes built ahead of the frame that
 * installs them, in the layout {@link LevelTilemapManager} uploads directly.
 *
 * <p>Produced by {@link LevelTilemapManager#takePrebuiltTilemaps()} on a
 * throwaway manager (see {@link LevelTilemapPrebuilder}) and adopted by the
 * live manager through {@link LevelTilemapManager#adoptPrebuiltTilemaps}.
 */
public record PrebuiltTilemaps(
        byte[] foreground, int foregroundWidthTiles, int foregroundHeightTiles,
        byte[] background, int backgroundWidthTiles, int backgroundHeightTiles) {

    public PrebuiltTilemaps {
        if (foreground == null || background == null) {
            throw new IllegalArgumentException("prebuilt tilemap data");
        }
    }
}
