package com.openggf.game.rewind.snapshot;

import java.util.BitSet;
import java.util.Arrays;

/**
 * Snapshot of {@link com.openggf.level.rings.RingManager} per-frame dynamic state.
 *
 * <p>Covers:
 * <ul>
 *   <li>Ring placement: which rings have been collected, sparkle timers, and the
 *       camera-window cursor so the window can be restored without a full rescan.</li>
 *   <li>Lost-ring pool: active per-ring physics state and the shared spill
 *       animation counters.</li>
 *   <li>Attracted rings: active slots from the short-lived attractor array used
 *       when a shield draws rings toward the player.</li>
 * </ul>
 *
 * <p>Excluded: spawn list, sprite-sheet/pattern references, and any rendering-only
 * data — all are invariant after level load.
 */
public record RingSnapshot(
        // --- RingPlacement ---
        long[] collectedWords,
        SparkleEntry[] sparkleTimers,
        int placementCursorIndex,
        int placementLastCameraX,
        int[] activeSpawnIndices,

        // --- LostRingPool ---
        int lostRingActiveCount,
        int spillAnimCounter,
        int spillAnimAccum,
        int spillAnimFrame,
        int lostRingFrameCounter,
        LostRingEntry[] lostRings,

        // --- AttractedRings ---
        AttractedRingEntry[] attractedRings
) {
    public RingSnapshot {
        collectedWords = collectedWords == null ? new long[0] : collectedWords.clone();
        sparkleTimers = sparkleTimers == null ? new SparkleEntry[0] : sparkleTimers.clone();
        activeSpawnIndices = activeSpawnIndices == null ? new int[0] : Arrays.copyOf(activeSpawnIndices, activeSpawnIndices.length);
        lostRings = lostRings == null ? new LostRingEntry[0] : lostRings.clone();
        attractedRings = attractedRings == null ? new AttractedRingEntry[0] : attractedRings.clone();
    }

    public RingSnapshot(
            long[] collectedWords,
            SparkleEntry[] sparkleTimers,
            int placementCursorIndex,
            int placementLastCameraX,
            int lostRingActiveCount,
            int spillAnimCounter,
            int spillAnimAccum,
            int spillAnimFrame,
            int lostRingFrameCounter,
            LostRingEntry[] lostRings,
            AttractedRingEntry[] attractedRings
    ) {
        this(collectedWords, sparkleTimers, placementCursorIndex, placementLastCameraX,
                new int[0], lostRingActiveCount, spillAnimCounter, spillAnimAccum,
                spillAnimFrame, lostRingFrameCounter, lostRings, attractedRings);
    }

    public RingSnapshot(
            BitSet collected,
            SparkleEntry[] sparkleTimers,
            int placementCursorIndex,
            int placementLastCameraX,
            int lostRingActiveCount,
            int spillAnimCounter,
            int spillAnimAccum,
            int spillAnimFrame,
            int lostRingFrameCounter,
            LostRingEntry[] lostRings,
            AttractedRingEntry[] attractedRings
    ) {
        this(collected, sparkleTimers, placementCursorIndex, placementLastCameraX,
                new int[0], lostRingActiveCount, spillAnimCounter,
                spillAnimAccum, spillAnimFrame, lostRingFrameCounter,
                lostRings, attractedRings);
    }

    public RingSnapshot(
            BitSet collected,
            SparkleEntry[] sparkleTimers,
            int placementCursorIndex,
            int placementLastCameraX,
            int[] activeSpawnIndices,
            int lostRingActiveCount,
            int spillAnimCounter,
            int spillAnimAccum,
            int spillAnimFrame,
            int lostRingFrameCounter,
            LostRingEntry[] lostRings,
            AttractedRingEntry[] attractedRings
    ) {
        this(collected == null ? new long[0] : collected.toLongArray(), sparkleTimers,
                placementCursorIndex, placementLastCameraX, activeSpawnIndices,
                lostRingActiveCount, spillAnimCounter, spillAnimAccum, spillAnimFrame,
                lostRingFrameCounter, lostRings, attractedRings);
    }

    public RingSnapshot(
            BitSet collected,
            int[] sparkleStartFrames,
            int placementCursorIndex,
            int placementLastCameraX,
            int lostRingActiveCount,
            int spillAnimCounter,
            int spillAnimAccum,
            int spillAnimFrame,
            int lostRingFrameCounter,
            LostRingEntry[] lostRings,
            AttractedRingEntry[] attractedRings
    ) {
        this(collected, sparseSparkleTimers(sparkleStartFrames), placementCursorIndex,
                placementLastCameraX, new int[0], lostRingActiveCount,
                spillAnimCounter, spillAnimAccum, spillAnimFrame,
                lostRingFrameCounter, lostRings, attractedRings);
    }

    public BitSet collected() {
        return BitSet.valueOf(collectedWords);
    }

    private static SparkleEntry[] sparseSparkleTimers(int[] sparkleStartFrames) {
        int count = 0;
        for (int startFrame : sparkleStartFrames) {
            if (startFrame >= 0) {
                count++;
            }
        }
        SparkleEntry[] entries = new SparkleEntry[count];
        int out = 0;
        for (int i = 0; i < sparkleStartFrames.length; i++) {
            int startFrame = sparkleStartFrames[i];
            if (startFrame >= 0) {
                entries[out++] = new SparkleEntry(i, startFrame);
            }
        }
        return entries;
    }

    /**
     * Sparse snapshot of one active placed-ring sparkle timer.
     */
    public record SparkleEntry(
            int ringIndex,
            int startFrame
    ) {}

    /**
     * Snapshot of a single active {@link com.openggf.level.rings.LostRing} pool slot.
     */
    public record LostRingEntry(
            boolean active,
            int xSubpixel,
            int ySubpixel,
            int xVel,
            int yVel,
            int lifetime,
            boolean collected,
            int sparkleStartFrame,
            int phaseOffset,
            int slotIndex,
            int poolIndex
    ) {
        public LostRingEntry(
                boolean active,
                int xSubpixel,
                int ySubpixel,
                int xVel,
                int yVel,
                int lifetime,
                boolean collected,
                int sparkleStartFrame,
                int phaseOffset,
                int slotIndex
        ) {
            this(active, xSubpixel, ySubpixel, xVel, yVel, lifetime, collected,
                    sparkleStartFrame, phaseOffset, slotIndex, 0);
        }
    }

    /**
     * Snapshot of one active attracted-ring slot in the attractor array.
     */
    public record AttractedRingEntry(
            boolean active,
            int sourceIndex,
            int x,
            int y,
            int xSub,
            int ySub,
            int xVel,
            int yVel,
            int slotIndex,
            int objectSlotIndex,
            boolean collected,
            int sparkleStartFrame,
            int mappingFrame,
            int animationFrameTimer,
            int sparkleAnimationFrame
    ) {
        public AttractedRingEntry(
                boolean active,
                int sourceIndex,
                int x,
                int y,
                int xSub,
                int ySub,
                int xVel,
                int yVel,
                int slotIndex,
                int objectSlotIndex,
                boolean collected,
                int sparkleStartFrame
        ) {
            this(active, sourceIndex, x, y, xSub, ySub, xVel, yVel,
                    slotIndex, objectSlotIndex, collected, sparkleStartFrame, 0, 0, 0);
        }

        public AttractedRingEntry(
                boolean active,
                int sourceIndex,
                int x,
                int y,
                int xSub,
                int ySub,
                int xVel,
                int yVel,
                int slotIndex
        ) {
            this(active, sourceIndex, x, y, xSub, ySub, xVel, yVel,
                    slotIndex, -1, false, -1, 0, 0, 0);
        }

        public AttractedRingEntry(
                boolean active,
                int sourceIndex,
                int x,
                int y,
                int xSub,
                int ySub,
                int xVel,
                int yVel
        ) {
            this(active, sourceIndex, x, y, xSub, ySub, xVel, yVel,
                    0, -1, false, -1, 0, 0, 0);
        }
    }
}
