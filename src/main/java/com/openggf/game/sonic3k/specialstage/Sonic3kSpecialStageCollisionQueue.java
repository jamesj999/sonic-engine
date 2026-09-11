package com.openggf.game.sonic3k.specialstage;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;

/**
 * Collision response animation queue for the S3K Blue Ball special stage.
 * <p>
 * When a blue sphere or ring is collected, an entry is added to this queue
 * that manages the collection animation (cell type transitions over time).
 * The queue is processed each frame to advance animations.
 * <p>
 * Queue structure: 32 entries, each 8 bytes.
 * Entry format: type (byte 0), timer (byte 2), frame (byte 3), gridIndex (bytes 4-7).
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm
 * {@code Touch_SSSprites} (line 12760), {@code SStage_collision_response_list}
 */
public class Sonic3kSpecialStageCollisionQueue {

    /** Callback for when a blue sphere animation completes. */
    public interface BlueSphereCallback {
        /**
         * Called when a blue sphere's collection animation finishes.
         *
         * @param gridIndex the grid buffer index of the sphere
         */
        void onBlueSphereAnimComplete(int gridIndex);
    }

    private final int[] types = new int[COLLISION_QUEUE_SIZE];
    private final int[] timers = new int[COLLISION_QUEUE_SIZE];
    private final int[] frames = new int[COLLISION_QUEUE_SIZE];
    private final int[] gridIndices = new int[COLLISION_QUEUE_SIZE];

    /**
     * Clear all entries.
     */
    public void clear() {
        for (int i = 0; i < COLLISION_QUEUE_SIZE; i++) {
            types[i] = 0;
            timers[i] = 0;
            frames[i] = 0;
            gridIndices[i] = 0;
        }
    }

    /**
     * Add a ring collection entry.
     * ROM: Find_SStageCollisionResponseSlot + move.b #1,(a2) (sonic3k.asm:12742)
     * <p>
     * Skips if this grid index already has a pending entry.
     *
     * @param gridIndex grid buffer index of the ring
     * @return true if added, false if queue is full or already pending
     */
    public boolean addRing(int gridIndex) {
        // Check for existing entry at this grid index
        for (int i = 0; i < COLLISION_QUEUE_SIZE; i++) {
            if (types[i] != 0 && gridIndices[i] == gridIndex) {
                return false; // Already pending
            }
        }
        int slot = findEmptySlot();
        if (slot < 0) return false;
        types[slot] = RESPONSE_RING;
        // ROM never writes the timer byte at creation (Find_SStageCollisionResponseSlot,
        // sonic3k.asm:12742-12753, only sets type + gridIndex pointer at 12178-12179);
        // a fresh slot inherits 0 from the clr.l at clearSlot()/entry release. Since
        // Process_Sprites (creates entries) runs before Touch_SSSprites (decrements
        // timers) within the same frame (sonic3k.asm:10744-10746), the timer's first
        // decrement (0 -> -1, subq.b/bpl.s at 12785-12786) fires the animation step in
        // the SAME frame the entry is created, not RING_ANIM_TIMER frames later.
        timers[slot] = 0;
        frames[slot] = 0;
        gridIndices[slot] = gridIndex;
        return true;
    }

    /**
     * Add a blue sphere collection entry.
     * ROM: Find_SStageCollisionResponseSlot + move.b #2,(a2) (sonic3k.asm:12136)
     * <p>
     * Deduplicated: only one entry per grid index to prevent queue saturation.
     * The ROM creates entries every frame but clears them fast enough to avoid
     * overflow. Our dedup achieves the same result — one entry per sphere.
     *
     * @param gridIndex grid buffer index of the sphere
     * @return true if added, false if queue is full or already pending
     */
    public boolean addBlueSphere(int gridIndex) {
        for (int i = 0; i < COLLISION_QUEUE_SIZE; i++) {
            if (types[i] != 0 && gridIndices[i] == gridIndex) {
                return false; // Already has an entry for this cell
            }
        }
        int slot = findEmptySlot();
        if (slot < 0) return false;
        types[slot] = RESPONSE_BLUE_SPHERE;
        // ROM never writes the timer byte at creation (Find_SStageCollisionResponseSlot,
        // sonic3k.asm:12742-12753, only sets type + gridIndex pointer at 12136-12137);
        // a fresh slot inherits 0 from the clr.l at clearSlot()/entry release. Since
        // Process_Sprites (creates entries) runs before Touch_SSSprites (decrements
        // timers) within the same frame (sonic3k.asm:10744-10746), the timer's first
        // decrement (0 -> -1, subq.b/bpl.s at 12807-12808) fires Phase 1 (sphere
        // collection, Decrement_BlueSphere_Count) in the SAME frame the entry is
        // created, not BLUE_SPHERE_ANIM_TIMER frames later.
        timers[slot] = 0;
        frames[slot] = 0;
        gridIndices[slot] = gridIndex;
        return true;
    }

    /**
     * Process all queue entries for one frame.
     * ROM: Touch_SSSprites (sonic3k.asm:12760)
     *
     * @param grid the game grid
     * @param callback callback for blue sphere animation completion
     * @param playerXPos player X position (for alignment check in Phase 2)
     * @param playerYPos player Y position (for alignment check in Phase 2)
     */
    public void update(Sonic3kSpecialStageGrid grid, BlueSphereCallback callback,
                       int playerXPos, int playerYPos) {
        for (int i = 0; i < COLLISION_QUEUE_SIZE; i++) {
            if (types[i] == 0) continue;

            switch (types[i]) {
                case RESPONSE_RING:
                    updateRing(i, grid);
                    break;
                case RESPONSE_BLUE_SPHERE:
                    updateBlueSphere(i, grid, callback, playerXPos, playerYPos);
                    break;
            }
        }
    }

    /**
     * Update a ring collection animation entry.
     * ROM: Touch_SSSprites_Ring (sonic3k.asm:12784)
     * Ring cell cycles through: 6 -> 7 -> 8 -> 9 -> 0 (disappear)
     */
    private void updateRing(int slot, Sonic3kSpecialStageGrid grid) {
        timers[slot]--;
        if (timers[slot] >= 0) return;

        timers[slot] = RING_ANIM_TIMER;
        int frame = frames[slot];
        int newCell = RING_ANIM_CELLS[frame];
        grid.setCellByIndex(gridIndices[slot], newCell);

        if (newCell == 0) {
            // Animation complete - clear entry
            clearSlot(slot);
        } else {
            frames[slot] = frame + 1;
        }
    }

    /**
     * Update a blue sphere collection animation entry.
     * ROM: Touch_SSSprites_BlueSphere (sonic3k.asm:12806)
     * <p>
     * Two-phase process:
     * <ol>
     *   <li>First timer expiry: cell is still blue (2) → mark as touched (0x0A),
     *       decrement count, run sphere-to-ring conversion. If conversion happened,
     *       cell becomes ring (4) and entry is cleared. Otherwise entry stays alive.</li>
     *   <li>Second timer expiry: cell is no longer blue → if cell is touched (0x0A),
     *       change to red (1). Clear entry.</li>
     * </ol>
     */
    private void updateBlueSphere(int slot, Sonic3kSpecialStageGrid grid,
                                   BlueSphereCallback callback,
                                   int playerXPos, int playerYPos) {
        timers[slot]--;
        if (timers[slot] >= 0) return;

        timers[slot] = BLUE_SPHERE_ANIM_TIMER;
        int gridIndex = gridIndices[slot];
        int cellType = grid.getCellByIndex(gridIndex);

        if (cellType == CELL_BLUE) {
            // Phase 1: cell is still blue
            // ROM: Decrement_BlueSphere_Count, move.b #$A,(a1), bsr Sphere_To_Rings
            grid.setCellByIndex(gridIndex, CELL_TOUCHED);
            if (callback != null) {
                callback.onBlueSphereAnimComplete(gridIndex);
            }
            // The ring converter's DFS may leave bit 7 set on the cell (0x8A).
            // Clear it so Phase 2 can properly detect TOUCHED (0x0A).
            int afterCell = grid.getCellByIndex(gridIndex);
            if ((afterCell & 0x80) != 0) {
                grid.andCellByIndex(gridIndex, 0x7F);
                afterCell = grid.getCellByIndex(gridIndex);
            }
            if (afterCell == CELL_RING) {
                clearSlot(slot);
            }
            // Otherwise entry stays alive for phase 2
        } else {
            // Phase 2: cell is no longer blue.
            // ROM: loc_9E62 — reset timer to 0, then check player alignment.
            // If player IS aligned (on cell center), DON'T convert yet — the player
            // might still be on this cell. Wait until they've moved away.
            timers[slot] = 0;
            int posCheck = (playerXPos | playerYPos) & 0xE0;
            if (posCheck == 0) {
                // Player is aligned (on a cell center) — don't convert yet, keep entry alive
                return;
            }
            if (cellType == CELL_TOUCHED) {
                grid.setCellByIndex(gridIndex, CELL_RED);
            }
            clearSlot(slot);
        }
    }

    private int findEmptySlot() {
        for (int i = 0; i < COLLISION_QUEUE_SIZE; i++) {
            if (types[i] == 0) return i;
        }
        return -1;
    }

    private void clearSlot(int slot) {
        types[slot] = 0;
        timers[slot] = 0;
        frames[slot] = 0;
        gridIndices[slot] = 0;
    }

    Sonic3kSpecialStageSnapshot.CollisionQueueSnapshot captureRewindSnapshot() {
        return new Sonic3kSpecialStageSnapshot.CollisionQueueSnapshot(
                types, timers, frames, gridIndices);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.CollisionQueueSnapshot snapshot) {
        Sonic3kSpecialStageSnapshot.copyInto(snapshot.types(), types);
        Sonic3kSpecialStageSnapshot.copyInto(snapshot.timers(), timers);
        Sonic3kSpecialStageSnapshot.copyInto(snapshot.frames(), frames);
        Sonic3kSpecialStageSnapshot.copyInto(snapshot.gridIndices(), gridIndices);
    }
}
