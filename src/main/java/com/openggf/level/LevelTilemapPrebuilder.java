package com.openggf.level;

import com.openggf.game.GameStateManager;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.graphics.GraphicsManager;

/**
 * Builds foreground/background tilemap bytes for the newly installed target
 * using a throwaway {@link LevelTilemapManager} over the level's
 * own geometry and the same block lookup rules the live manager uses.
 *
 * <p>Runs on the frame thread after the target is installed: wrapping predicates
 * consult the live zone state and must not run on the ROM preparation worker.
 * Callers must invalidate these bytes if subsequent mutation or feature
 * initialization changes the layout or wrapping policy.
 * The live manager adopts the result through
 * {@link LevelTilemapManager#adoptPrebuiltTilemaps} and swaps it in with
 * {@link LevelTilemapManager#swapToPrebuiltTilemaps()}, which is also what
 * verifies the bytes are identical to a from-scratch build (see
 * {@code TestS3kAiz1TilemapRebuildHitches}).
 */
public final class LevelTilemapPrebuilder {
    private LevelTilemapPrebuilder() {
    }

    public static PrebuiltTilemaps build(Level level,
                                         GraphicsManager graphicsManager,
                                         GameStateManager gameState,
                                         ZoneFeatureProvider zoneFeatureProvider,
                                         int currentZone,
                                         ParallaxManager parallaxManager,
                                         boolean verticalWrapEnabled) {
        if (level == null || level.getMap() == null) {
            return null;
        }
        LevelGeometry geometry = LevelGeometry.forLevel(level);
        LevelTilemapManager scratch = new LevelTilemapManager(geometry, graphicsManager, gameState);
        LevelTilemapManager.BlockLookup lookup = (layer, x, y) ->
                LevelLayoutLookup.blockAt(level, geometry, verticalWrapEnabled, layer, x, y);
        scratch.prebuildTransitionTilemaps(lookup, zoneFeatureProvider, currentZone,
                parallaxManager, verticalWrapEnabled);
        return scratch.takePrebuiltTilemaps();
    }
}
