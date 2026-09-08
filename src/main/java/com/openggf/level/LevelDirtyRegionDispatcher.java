package com.openggf.level;

import com.openggf.game.mutation.MutationEffects;

import java.util.BitSet;

/**
 * Dispatches MutableLevel dirty sets and mutation effects to rendering,
 * tilemap, object, and ring subsystems.
 */
final class LevelDirtyRegionDispatcher {
    private final LevelManager levelManager;

    LevelDirtyRegionDispatcher(LevelManager levelManager) {
        this.levelManager = levelManager;
    }

    void processDirtyRegions() {
        if (!(levelManager.level instanceof MutableLevel ml)) {
            return;
        }

        BitSet dirtyPatterns = ml.consumeDirtyPatterns();
        if (!dirtyPatterns.isEmpty()) {
            reuploadDirtyPatterns(dirtyPatterns);
        }

        BitSet dirtyBlocks = ml.consumeDirtyBlocks();
        BitSet dirtyMapCells = ml.consumeDirtyMapCells();
        if ((!dirtyBlocks.isEmpty() || !dirtyMapCells.isEmpty()) && levelManager.tilemapManager != null) {
            levelManager.tilemapManager.rebuildDirtyRegions(dirtyBlocks, dirtyMapCells, ml);
        }

        BitSet dirtySolidTiles = ml.consumeDirtySolidTiles();
        if (!dirtySolidTiles.isEmpty()) {
            // Terrain sensors read SolidTile data directly from the current Level.
            // The consume keeps MutableLevel's frame-visible dirty state in sync.
        }

        boolean objectsDirty = ml.consumeObjectsDirty();
        boolean ringsDirty = ml.consumeRingsDirty();
        resyncDirtySpawnLists(objectsDirty, ringsDirty,
                levelManager::resyncRingSpawnListFromLevel,
                levelManager::resyncObjectSpawnListFromLevel);
    }

    static void resyncDirtySpawnLists(boolean objectsDirty, boolean ringsDirty,
                                      Runnable ringResync, Runnable objectResync) {
        if (ringsDirty) ringResync.run();
        if (objectsDirty) objectResync.run();
    }

    void reuploadDirtyPatterns(BitSet dirtyPatterns) {
        if (dirtyPatterns == null || dirtyPatterns.isEmpty() || levelManager.level == null) {
            return;
        }
        levelManager.graphicsManager.reuploadDirtyPatterns(dirtyPatterns, levelManager.level);
    }

    void applyMutationEffects(MutationEffects effects) {
        if (effects == null || effects.isEmpty()) {
            return;
        }
        if (effects.hasDirtyPatterns()) {
            reuploadDirtyPatterns(effects.dirtyPatterns());
        }
        if (effects.dirtyRegionProcessingRequired()) {
            processDirtyRegions();
        }
        if (effects.allTilemapsRedrawRequired()) {
            levelManager.invalidateAllTilemaps();
        } else if (effects.foregroundRedrawRequired()) {
            levelManager.invalidateForegroundTilemap();
        }
        if (effects.patternLookupRefreshRequired() && !effects.allTilemapsRedrawRequired()) {
            levelManager.invalidatePatternLookup();
        }
        if (effects.objectResyncRequired()) {
            levelManager.resyncObjectSpawnListFromLevel();
        }
        if (effects.ringResyncRequired()) {
            levelManager.resyncRingSpawnListFromLevel();
        }
    }
}
