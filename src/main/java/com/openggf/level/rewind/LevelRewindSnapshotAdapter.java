package com.openggf.level.rewind;

import com.openggf.game.LevelState;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.LevelSnapshot;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;

/**
 * Rewind adapter for mutable level geometry plus level-scoped HUD and checkpoint state.
 */
public final class LevelRewindSnapshotAdapter implements RewindSnapshottable<LevelSnapshot> {
    private final LevelManager manager;

    private LevelRewindSnapshotAdapter(LevelManager manager) {
        this.manager = manager;
    }

    public static RewindSnapshottable<LevelSnapshot> create(LevelManager manager) {
        return new LevelRewindSnapshotAdapter(manager);
    }

    @Override
    public String key() {
        return "level";
    }

    @Override
    public LevelSnapshot capture() {
        AbstractLevel level = currentAbstractLevel();
        LevelState levelState = manager.getLevelGamestate();

        // Capture establishes a new COW boundary: future production map writes
        // must clone away from the byte array referenced by this keyframe.
        level.bumpEpoch();

        return new LevelSnapshot(
                level.currentEpoch(),
                level.blocksReference(),
                level.chunksReference(),
                level.getMap().getData(),
                manager.getFrameCounter(),
                levelState != null,
                levelState != null ? levelState.getRings() : 0,
                levelState != null ? levelState.getRingExtraLifeFlags() : 0,
                levelState != null ? levelState.getTimerFrames() : 0,
                levelState != null && levelState.isTimerPaused(),
                manager.isRespawnRequestedForRewind(),
                manager.captureCheckpointStateForRewind(),
                manager.isTransitionRingInitializationPendingForRewind(),
                manager.capturePendingInitialProcessSpritesLifecycleForRewind()
        );
    }

    @Override
    public void restore(LevelSnapshot snapshot) {
        manager.discardPreparedLevelLoad();
        AbstractLevel level = currentAbstractLevel();
        boolean geometryReferencesChanged =
                level.blocksReference() != snapshot.blocks()
                        || level.chunksReference() != snapshot.chunks()
                        || level.getMap().getData() != snapshot.mapData();

        level.replaceBlocks(snapshot.blocks());
        level.replaceChunks(snapshot.chunks());
        level.getMap().restoreData(snapshot.mapData());
        level.bumpEpoch();
        if (geometryReferencesChanged) {
            // Rewind can restore prior tile arrays by reference; rebuild manager-owned caches only on swaps.
            manager.invalidateAllTilemaps();
        }
        manager.setFrameCounter(snapshot.frameCounter());
        restoreLevelHudState(snapshot);
        manager.restoreRespawnRequestedForRewind(snapshot.respawnRequested());
        manager.restoreCheckpointStateForRewind(snapshot.checkpointState());
        manager.restoreTransitionRingInitializationPendingForRewind(
                snapshot.transitionRingInitializationPending());
        manager.restorePendingInitialProcessSpritesLifecycleForRewind(
                snapshot.pendingInitialProcessSpritesLifecycle());
    }

    private void restoreLevelHudState(LevelSnapshot snapshot) {
        LevelState levelState = manager.getLevelGamestate();
        if (!snapshot.hasLevelHudState() || levelState == null) {
            return;
        }

        levelState.setRings(snapshot.levelRings());
        levelState.setRingExtraLifeFlags(snapshot.levelRingExtraLifeFlags());
        levelState.setTimerFrames(snapshot.levelTimerFrames());
        if (snapshot.levelTimerPaused()) {
            levelState.pauseTimer();
        } else {
            levelState.resumeTimer();
        }
    }

    private AbstractLevel currentAbstractLevel() {
        Level currentLevel = manager.getCurrentLevel();
        if (!(currentLevel instanceof AbstractLevel level)) {
            throw new IllegalStateException(
                    "Current level is not an AbstractLevel: " + currentLevel.getClass().getName());
        }
        return level;
    }
}
