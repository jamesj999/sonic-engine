package com.openggf.game.sonic3k.specialstage;

import java.util.ArrayList;
import java.util.List;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;

/**
 * Sphere-to-ring conversion algorithm for the S3K Blue Ball special stage.
 * <p>
 * When a blue sphere is collected, this algorithm detects if the surrounding
 * red spheres form a closed loop enclosing other blue spheres. If so, the
 * enclosed blue spheres (and adjacent red spheres) are converted to rings.
 * <p>
 * The algorithm has three phases:
 * <ol>
 *   <li><b>DFS loop detection</b> ({@code Find_Red_Sphere_Loop}): Walk red sphere
 *       paths using depth-first search to find closed loops. For each loop found,
 *       determine the "inside" direction and check for an enclosed blue sphere.</li>
 *   <li><b>BFS flood fill</b>: Starting from enclosed blue spheres (now rings),
 *       flood-fill to convert all connected blue spheres to rings.</li>
 *   <li><b>Red neighbor conversion</b>: Convert any red spheres adjacent to the
 *       newly created rings into rings as well.</li>
 * </ol>
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm
 * {@code Sphere_To_Rings} (line 12902), {@code Find_Red_Sphere_Loop} (line 12993)
 */
public class Sonic3kSpecialStageRingConverter {

    /** Result of the conversion attempt. */
    public static class ConversionResult {
        /** Whether any spheres were converted to rings. */
        public final boolean converted;
        /** Number of blue spheres that were converted (decremented from count). */
        public final int blueSpheresConverted;

        public ConversionResult(boolean converted, int blueSpheresConverted) {
            this.converted = converted;
            this.blueSpheresConverted = blueSpheresConverted;
        }
    }

    /**
     * Attempt sphere-to-ring conversion after a blue sphere is touched.
     * ROM: Sphere_To_Rings (sonic3k.asm:12902)
     *
     * @param grid the game grid
     * @param touchedIndex grid buffer index of the touched sphere (already marked 0x0A)
     * @return conversion result
     */
    /** Counter for blue spheres converted during DFS loop seeding (processLoop). */
    private int seedBlueConverted;

    public ConversionResult convert(Sonic3kSpecialStageGrid grid, int touchedIndex) {
        // Phase 1: Find loops and seed the ring queue
        seedBlueConverted = 0;
        List<Integer> ringQueue = findRedSphereLoops(grid, touchedIndex);

        if (ringQueue.isEmpty()) {
            return new ConversionResult(false, 0);
        }

        int blueConverted = seedBlueConverted; // Include DFS-seeded conversions

        // Phase 2: BFS flood fill - convert connected blue spheres to rings
        // ROM: Blue_To_Ring_Next_Ring_B (sonic3k.asm:12916)
        int queueIdx = 0;
        while (queueIdx < ringQueue.size()) {
            int ringPos = ringQueue.get(queueIdx);
            queueIdx++;

            // Check all 8 neighbors
            for (int dir : DIRECTIONS_8) {
                int neighbor = (ringPos + dir) & 0x3FF;
                if (grid.getCellByIndex(neighbor) == CELL_BLUE) {
                    grid.setCellByIndex(neighbor, CELL_RING);
                    ringQueue.add(neighbor);
                    blueConverted++;
                }
            }
        }

        // Phase 3: Convert red/touched sphere neighbors of rings to rings
        // ROM: Blue_To_Ring_Next_Ring_R (sonic3k.asm:12943)
        // Re-scan the entire queue (including newly added entries from phase 2)
        for (int ringPos : ringQueue) {
            for (int dir : DIRECTIONS_8) {
                int neighbor = (ringPos + dir) & 0x3FF;
                int neighborCell = grid.getCellByIndex(neighbor);
                // Convert RED (1) and TOUCHED (0x0A) neighbors to rings
                // TOUCHED cells are border spheres that haven't completed Phase 2 yet
                if (neighborCell == CELL_RED || neighborCell == CELL_TOUCHED
                        || (neighborCell & 0x7F) == CELL_RED
                        || (neighborCell & 0x7F) == CELL_TOUCHED) {
                    grid.setCellByIndex(neighbor, CELL_RING);
                }
            }
        }

        // sfx_RingLoss plays here
        return new ConversionResult(true, blueConverted);
    }

    /**
     * Find closed loops of red spheres using DFS, and seed the ring queue
     * with enclosed blue spheres converted to rings.
     * ROM: Find_Red_Sphere_Loop (sonic3k.asm:12993)
     *
     * @param grid the game grid
     * @param touchedIndex index of the touched sphere
     * @return list of grid indices that have been converted to rings
     */
    private List<Integer> findRedSphereLoops(Sonic3kSpecialStageGrid grid, int touchedIndex) {
        List<Integer> ringQueue = new ArrayList<>();

        // Step 1: Check neighbors - mark touched (0x0A) neighbors as red,
        // count blue neighbors
        // ROM: Red_Loop_Check_Neighbors (sonic3k.asm:12999)
        int blueNeighborCount = 0;
        for (int dir : DIRECTIONS_8) {
            int neighbor = (touchedIndex + dir) & 0x3FF;
            int cellType = grid.getCellByIndex(neighbor);
            if (cellType == CELL_TOUCHED) {
                grid.setCellByIndex(neighbor, CELL_RED);
            }
            if (cellType == CELL_BLUE) {
                blueNeighborCount++;
            }
        }

        if (blueNeighborCount == 0) {
            return ringQueue; // No blue neighbors - nothing to convert
        }

        // Step 2: Span check - horizontal and vertical spans must each be >= 3
        // ROM: Red_Loop_Count_Horizontal_Left (sonic3k.asm:13020)
        int horizontalSpan = countSpan(grid, touchedIndex, -1, 1)
                           + countSpan(grid, touchedIndex, 1, 1);
        if (horizontalSpan < 4) { // < 4 because touched position is double-counted
            return ringQueue;
        }

        int verticalSpan = countSpan(grid, touchedIndex, -0x20, 1)
                         + countSpan(grid, touchedIndex, 0x20, 1);
        if (verticalSpan < 4) {
            return ringQueue;
        }

        // Step 3: DFS walk to find loops
        // ROM: Red_Loop_Find_Next (sonic3k.asm:13074)
        dfsWalk(grid, touchedIndex, ringQueue);

        return ringQueue;
    }

    /**
     * Count the span of non-empty cells in one direction from the touched sphere.
     *
     * @param grid the game grid
     * @param startIndex starting grid index
     * @param direction direction offset (-1, +1, -0x20, +0x20)
     * @param initialCount initial count (1 for the starting cell itself)
     * @return total span count
     */
    private int countSpan(Sonic3kSpecialStageGrid grid, int startIndex,
                          int direction, int initialCount) {
        int count = initialCount;
        int pos = startIndex;
        for (int i = 0; i < 16; i++) {
            pos = (pos + direction) & 0x3FF;
            if (grid.getCellByIndex(pos) == CELL_EMPTY) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * DFS walk along red spheres to find closed loops.
     * When a loop is found, processes it to find enclosed blue spheres.
     * ROM: Red_Loop_Find_Next through Red_Loop_Pop_Stack (sonic3k.asm:13074-13142)
     */
    private void dfsWalk(Sonic3kSpecialStageGrid grid, int touchedIndex,
                         List<Integer> ringQueue) {
        // DFS stack: each entry is (lowerBound, dirIndex, position)
        int[] stackLower = new int[256];
        int[] stackDir = new int[256];
        int[] stackPos = new int[256];
        int stackSize = 0;

        int lowerBound = 0;     // Direction index lower bound
        int dirIndex = 6;       // Current direction index (starts at 6 = lowerBound + 6)
        dirIndex += lowerBound;
        int currentPos = touchedIndex;

        while (true) {
            // Try next direction
            if (dirIndex < lowerBound) {
                // All directions exhausted at this position - pop stack
                if (stackSize == 0) {
                    // Unmark the last position before returning
                    grid.andCellByIndex(currentPos, 0x7F);
                    return; // No more loops to find
                }
                stackSize--;
                // Unmark current position
                grid.andCellByIndex(currentPos, 0x7F);
                currentPos = stackPos[stackSize];
                dirIndex = stackDir[stackSize];
                lowerBound = stackLower[stackSize];
                // Decrement and try next direction
                dirIndex -= 2;
                if (dirIndex >= lowerBound) {
                    continue;
                }
                // This position exhausted too - keep popping
                continue;
            }

            // Get candidate position
            int nextDir = DIRECTIONS_4[dirIndex / 2]; // Directions are word-indexed
            int candidate = (currentPos + nextDir) & 0x3FF;
            int candidateCell = grid.getCellByIndex(candidate);

            // Check for loop completion
            if (candidateCell == (CELL_TOUCHED | 0x80)) { // 0x8A = processed + touched
                // Loop found! Process it
                processLoop(grid, touchedIndex, currentPos, candidate,
                        stackPos, stackSize, ringQueue);
                dirIndex -= 2;
                continue;
            }

            // Check if candidate is a red sphere
            if (candidateCell != CELL_RED) {
                dirIndex -= 2;
                continue;
            }

            // Anti-backtrack: if stack has 2+ entries, reject if candidate
            // is one step away from the grandparent position (stack[-2]).
            // This prevents the DFS from making a 180-degree turn.
            if (stackSize >= 2) {
                int prevPrevPos = stackPos[stackSize - 2]; // grandparent position
                int diff = (candidate - prevPrevPos) & 0xFFFF;
                // Sign-extend for comparison
                if (diff > 0x7FFF) diff -= 0x10000;
                if (diff == -1 || diff == 1 || diff == 0x20 || diff == -0x20) {
                    dirIndex -= 2;
                    continue;
                }
            }

            // Push current state and advance to candidate
            grid.orCellByIndex(currentPos, 0x80); // Mark as being processed
            stackLower[stackSize] = lowerBound;
            stackDir[stackSize] = dirIndex;
            stackPos[stackSize] = currentPos;
            stackSize++;

            // Calculate new bounds (prevent 180-degree turns)
            // New lower = (dirIndex - 2) & 6
            // New dirIndex = 4 + newLower
            lowerBound = (dirIndex - 2) & 6;
            dirIndex = 4 + lowerBound;
            currentPos = candidate;
        }
    }

    /**
     * Process a detected loop to find enclosed blue spheres.
     * ROM: Red_Loop_Processes_Loop (sonic3k.asm:13168)
     *
     * @param grid the game grid
     * @param touchedIndex the originally touched sphere position
     * @param lastWalkPos the last position in the walk before loop closure
     * @param loopClosePos the position that closed the loop
     * @param stackPos the DFS stack positions
     * @param stackSize the DFS stack size
     * @param ringQueue output queue for converted rings
     */
    private void processLoop(Sonic3kSpecialStageGrid grid, int touchedIndex,
                             int lastWalkPos, int loopClosePos,
                             int[] stackPos, int stackSize,
                             List<Integer> ringQueue) {
        // Direction from start to last walk position
        int startToLast = (lastWalkPos - touchedIndex) & 0x3FF;
        if (startToLast > 0x1FF) startToLast -= 0x400;

        // Final direction (last walk -> start, i.e. closing the loop)
        int finalDir = -startToLast;

        // Get second position in the walk path.
        // ROM: lea (SStage_red_sphere_dfs_walk_stack+6).w,a4 / move.w (a4),d2
        // This reads stackPos[1] — the position stored when the DFS took its
        // SECOND step (the first step stores touchedIndex in stackPos[0]).
        int secondPos;
        if (stackSize > 1) {
            secondPos = stackPos[1];
        } else if (stackSize > 0) {
            secondPos = stackPos[0];
        } else {
            secondPos = lastWalkPos;
        }

        // Initial direction (touched -> second position)
        int initialDir = (secondPos - touchedIndex) & 0x3FF;
        if (initialDir > 0x1FF) initialDir -= 0x400;

        // Re-walk the path to find where direction changes.
        // ROM: starts reading from stackPos[1] onward (a4 was set to entry 1).
        int walkHead = touchedIndex;
        int newDir = initialDir;
        int walkIdx = 1; // Start from entry 1 (entry 0 = touchedIndex itself)

        // Find first direction change
        while (walkIdx < stackSize) {
            int nextPos = stackPos[walkIdx];
            int stepDir = (nextPos - walkHead) & 0x3FF;
            if (stepDir > 0x1FF) stepDir -= 0x400;

            if (stepDir != initialDir) {
                newDir = stepDir;
                break;
            }
            walkHead = nextPos;
            walkIdx++;
        }

        // Determine the "inside" offset
        int insideOffset;

        // Corner check: if new direction != final direction and initial != final
        if (newDir != finalDir && initialDir != finalDir) {
            insideOffset = initialDir + newDir;
        } else {
            insideOffset = newDir;
        }

        // Check the cell at touched + insideOffset for a blue sphere
        int checkPos = (touchedIndex + insideOffset) & 0x3FF;
        int checkCell = grid.getCellByIndex(checkPos);

        if (checkCell == CELL_BLUE) {
            // Convert to ring, decrement sphere count, and add to queue
            // ROM: Red_Loop_Blue_To_Ring calls Decrement_BlueSphere_Count
            grid.setCellByIndex(checkPos, CELL_RING);
            ringQueue.add(checkPos);
            seedBlueConverted++;
            // blueConverted is tracked by the caller
        }
        // If cell is already a ring (0x04), skip silently
    }

    Sonic3kSpecialStageSnapshot.RingConverterSnapshot captureRewindSnapshot() {
        return new Sonic3kSpecialStageSnapshot.RingConverterSnapshot(seedBlueConverted);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.RingConverterSnapshot snapshot) {
        seedBlueConverted = snapshot.seedBlueConverted();
    }
}
