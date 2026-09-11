package com.openggf.game.sonic3k.specialstage;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;

/**
 * The 32x32 toroidal game grid for S3K Blue Ball special stages.
 * <p>
 * The grid wraps in both X and Y axes (toroidal topology). Cell coordinates
 * use {@code & 0x1F} masking for automatic wrapping.
 * <p>
 * The layout buffer is 0x600 bytes total, with the actual 32x32 grid data
 * at offset 0x100 (0x400 bytes). 0x100 bytes of padding exist before and
 * after the grid for the DFS/BFS algorithms that use relative offsets.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm SStage_layout_buffer (line 10872)
 */
public class Sonic3kSpecialStageGrid {

    /**
     * Full layout buffer including padding. Grid data at offset 0x100.
     * Using int[] instead of byte[] for cleaner comparisons (no sign extension).
     */
    private final int[] buffer = new int[LAYOUT_BUFFER_SIZE];

    /**
     * Load grid data from a ROM layout (0x400 bytes of cell data plus
     * 4 trailing words: angle, xPos, yPos, spheresLeft).
     *
     * @param layoutData the raw layout data (at least 0x408 bytes)
     * @return the trailing parameters: [angle, xPos, yPos, spheresLeft]
     */
    public int[] loadFromLayoutData(byte[] layoutData) {
        // Clear entire buffer (including padding)
        java.util.Arrays.fill(buffer, 0);

        // Copy 0x400 bytes of grid data to offset 0x100
        for (int i = 0; i < LAYOUT_GRID_SIZE && i < layoutData.length; i++) {
            buffer[LAYOUT_GRID_OFFSET + i] = layoutData[i] & 0xFF;
        }

        // Read trailing parameters (4 words after grid data)
        int[] params = new int[4];
        int offset = LAYOUT_GRID_SIZE;
        for (int i = 0; i < 4; i++) {
            if (offset + 1 < layoutData.length) {
                params[i] = ((layoutData[offset] & 0xFF) << 8)
                          | (layoutData[offset + 1] & 0xFF);
            }
            offset += 2;
        }
        return params;
    }

    /**
     * Get the cell type at grid coordinates.
     * Coordinates wrap via {@code & 0x1F}.
     *
     * @param gridX X coordinate (0-31, wraps)
     * @param gridY Y coordinate (0-31, wraps)
     * @return cell type constant
     */
    public int getCell(int gridX, int gridY) {
        int index = ((gridY & GRID_MASK) << 5) | (gridX & GRID_MASK);
        return buffer[LAYOUT_GRID_OFFSET + index];
    }

    /**
     * Set the cell type at grid coordinates.
     *
     * @param gridX X coordinate (0-31, wraps)
     * @param gridY Y coordinate (0-31, wraps)
     * @param type cell type constant
     */
    public void setCell(int gridX, int gridY, int type) {
        int index = ((gridY & GRID_MASK) << 5) | (gridX & GRID_MASK);
        buffer[LAYOUT_GRID_OFFSET + index] = type;
    }

    /**
     * Get cell type at a buffer-relative index (used by collision and
     * sphere-to-ring algorithms that work with direct index offsets).
     *
     * @param bufferIndex index into the grid portion (0-0x3FF)
     * @return cell type constant
     */
    public int getCellByIndex(int bufferIndex) {
        return buffer[LAYOUT_GRID_OFFSET + (bufferIndex & 0x3FF)];
    }

    /**
     * Set cell type at a buffer-relative index.
     *
     * @param bufferIndex index into the grid portion (0-0x3FF)
     * @param type cell type constant
     */
    public void setCellByIndex(int bufferIndex, int type) {
        buffer[LAYOUT_GRID_OFFSET + (bufferIndex & 0x3FF)] = type;
    }

    /**
     * Convert player subpixel position to a grid buffer index.
     * ROM formula: ((Y+0x80)>>8 & 0x1F) * 0x20 + ((X+0x80)>>8 & 0x1F)
     * <p>
     * Reference: sub_972E (sonic3k.asm:12088)
     *
     * @param xPos player X position in subpixels
     * @param yPos player Y position in subpixels
     * @return grid buffer index (0-0x3FF)
     */
    public static int positionToIndex(int xPos, int yPos) {
        int gx = ((xPos + 0x80) >> 8) & GRID_MASK;
        int gy = ((yPos + 0x80) >> 8) & GRID_MASK;
        return (gy << 5) | gx;
    }

    /**
     * Get the cell type at the player's current position.
     *
     * @param xPos player X position in subpixels
     * @param yPos player Y position in subpixels
     * @return cell type constant
     */
    public int getCellAtPosition(int xPos, int yPos) {
        return getCellByIndex(positionToIndex(xPos, yPos));
    }

    /**
     * Count all blue spheres remaining in the grid.
     * ROM: sub_9EA0 (sonic3k.asm:12861)
     *
     * @return number of blue sphere cells
     */
    public int countBlueSpheres() {
        int count = 0;
        for (int i = 0; i < GRID_CELL_COUNT; i++) {
            if (buffer[LAYOUT_GRID_OFFSET + i] == CELL_BLUE) {
                count++;
            }
        }
        return count;
    }

    /**
     * Clear all grid cells to empty (used during stage clear sequence).
     * ROM: loc_9BB2 (sonic3k.asm:12555)
     */
    public void clearAll() {
        for (int i = 0; i < GRID_CELL_COUNT; i++) {
            buffer[LAYOUT_GRID_OFFSET + i] = CELL_EMPTY;
        }
    }

    /**
     * Get or-set operation on the buffer for DFS processing bit marking.
     * ROM uses ori.b #$80,(a2,d0.w) to mark cells as being processed.
     *
     * @param bufferIndex grid buffer index
     * @param mask bits to set
     */
    public void orCellByIndex(int bufferIndex, int mask) {
        buffer[LAYOUT_GRID_OFFSET + (bufferIndex & 0x3FF)] |= mask;
    }

    /**
     * And operation on the buffer for DFS processing bit clearing.
     * ROM uses andi.b #$7F,(a2,d0.w) to unmark cells.
     *
     * @param bufferIndex grid buffer index
     * @param mask bits to keep
     */
    public void andCellByIndex(int bufferIndex, int mask) {
        buffer[LAYOUT_GRID_OFFSET + (bufferIndex & 0x3FF)] &= mask;
    }

    Sonic3kSpecialStageSnapshot.GridSnapshot captureRewindSnapshot() {
        return new Sonic3kSpecialStageSnapshot.GridSnapshot(buffer);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.GridSnapshot snapshot) {
        Sonic3kSpecialStageSnapshot.copyInto(snapshot.buffer(), buffer);
    }
}
