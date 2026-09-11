package com.openggf.level;

import com.openggf.tools.LevelDataFactory;

import java.util.Arrays;

/**
 * Represents a block of chunks in a level.
 * Sonic 2 uses 128x128 blocks (8x8 grid of 16x16 chunks).
 * Sonic 1 uses 256x256 blocks (16x16 grid of 16x16 chunks).
 *
 * Copy-on-write: when gameplay code mutates this block, it calls
 * cowEnsureWritable(currentEpoch) to clone the internal chunkDescs[] array
 * if needed. This protects snapshot-captured block references from
 * being clobbered by subsequent mutations.
 */
public class Block {

    private final int gridSide;
    private ChunkDesc[] chunkDescs;

    // Epoch at which chunkDescs was last cloned. If currentEpoch has moved
    // forward, the next mutation will clone the array.
    private long lastTouchedEpoch = 0L;

    // Default constructor (8x8 grid for Sonic 2 backward compatibility)
    public Block() {
        this(8);
    }

    public Block(int gridSide) {
        this.gridSide = gridSide;
        this.chunkDescs = new ChunkDesc[gridSide * gridSide];
        // Initialize array with references to empty ChunkDesc instance (to save on pointlessly making objects)
        Arrays.setAll(this.chunkDescs, i -> ChunkDesc.EMPTY);
    }

    // Parses a block of data from Sega's format (big-endian 16-bit values)
    public void fromSegaFormat(byte[] blockBuffer) {
        this.chunkDescs = LevelDataFactory.chunksFromSegaByteArray(blockBuffer);
    }

    public void fromSegaFormat(byte[] blockBuffer, int chunksPerBlock) {
        this.chunkDescs = LevelDataFactory.chunksFromSegaByteArray(blockBuffer, chunksPerBlock);
    }

    // Retrieves a chunk descriptor based on x and y coordinates
    public ChunkDesc getChunkDesc(int x, int y) {
        if (x >= gridSide || y >= gridSide) {
            throw new IllegalArgumentException("Invalid chunk index: (" + x + ", " + y + ") for gridSide " + gridSide);
        }

        return chunkDescs[y * gridSide + x];
    }

    /** Sets a chunk descriptor at the given grid position. */
    public void setChunkDesc(int x, int y, ChunkDesc desc) {
        if (x >= gridSide || y >= gridSide) {
            throw new IllegalArgumentException("Invalid chunk index: (" + x + ", " + y + ") for gridSide " + gridSide);
        }
        chunkDescs[y * gridSide + x] = desc;
    }

    /**
     * Ensures this block's chunkDescs array is writable for the current epoch.
     * On first write per epoch, clones the array. Subsequent writes within the
     * same epoch reuse the cloned array.
     *
     * Called before any gameplay mutation to protect snapshot references.
     *
     * @param currentEpoch the current snapshot epoch from the level
     */
    public void cowEnsureWritable(long currentEpoch) {
        if (lastTouchedEpoch < currentEpoch) {
            chunkDescs = chunkDescs.clone();
            lastTouchedEpoch = currentEpoch;
        }
    }

    public int getGridSide() {
        return gridSide;
    }

    /**
     * Saves the block state (chunk descriptors) as a compact int array.
     * Used for snapshot/restore during level editing.
     */
    public int[] saveState() {
        int[] state = new int[chunkDescs.length];
        for (int i = 0; i < chunkDescs.length; i++) {
            state[i] = chunkDescs[i].get();
        }
        return state;
    }

    /**
     * Restores block state from a previously saved snapshot.
     */
    public void restoreState(int[] state) {
        for (int i = 0; i < state.length; i++) {
            chunkDescs[i] = new ChunkDesc(state[i]);
        }
    }

    /**
     * Package-private accessor for the live chunkDescs array (testing only).
     * Marked as @VisibleForTesting: use only in test assertions.
     *
     * @return the internal ChunkDesc array reference
     */
    ChunkDesc[] chunkDescsArrayForTest() {
        return chunkDescs;
    }
}
