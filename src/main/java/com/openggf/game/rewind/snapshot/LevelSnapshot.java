package com.openggf.game.rewind.snapshot;

import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.game.CheckpointState;
import com.openggf.game.InitialProcessSpritesLifecycle;

/**
 * Reference-only level snapshot. Block/Chunk arrays are shared while the
 * level is unchanged; gameplay mutation surfaces replace the live array and
 * edited entry before writing so captured snapshots keep the old structure.
 * mapData is the Map's underlying byte[]; CoW in Map.cowEnsureWritable clones
 * it on the first gameplay-path mutation per epoch, so snapshot keeps the old
 * ref.
 *
 * Pattern bytes are derived state and not snapshotted (animator counters
 * regenerate them on the next forward step).
 *
 * frameCounter is LevelManager.frameCounter: the per-frame counter passed
 * into ObjectManager.update / RingManager.collectStageRings /
 * OscillationManager.update. Without restoring it, vbla-mod-8 badnik AI
 * timing gates fire at different absolute frames after rewind, cascading
 * into divergent badnik / ring / oscillation state on the very first
 * post-restore step.
 */
public record LevelSnapshot(
        long epochAtCapture,
        Block[] blocks,
        Chunk[] chunks,
        byte[] mapData,
        int frameCounter,
        boolean hasLevelHudState,
        int levelRings,
        long levelTimerFrames,
        boolean levelTimerPaused,
        boolean respawnRequested,
        CheckpointState.RewindState checkpointState,
        InitialProcessSpritesLifecycle pendingInitialProcessSpritesLifecycle) {

    public LevelSnapshot(
            long epochAtCapture,
            Block[] blocks,
            Chunk[] chunks,
            byte[] mapData,
            int frameCounter) {
        this(epochAtCapture, blocks, chunks, mapData, frameCounter,
                false, 0, 0, false, false, null, InitialProcessSpritesLifecycle.NONE);
    }

    public LevelSnapshot(
            long epochAtCapture,
            Block[] blocks,
            Chunk[] chunks,
            byte[] mapData,
            int frameCounter,
            boolean respawnRequested) {
        this(epochAtCapture, blocks, chunks, mapData, frameCounter,
                false, 0, 0, false, respawnRequested, null, InitialProcessSpritesLifecycle.NONE);
    }
}
