package uk.co.jamesj999.sonic.level;

import java.util.Arrays;

/**
 * Represents a block of chunks in the Sonic 2/3 level editor.
 */
public class Block {
    public static final int BLOCK_HEIGHT = 128;
    public static final int BLOCK_WIDTH = 128;
    public static final int BYTES_PER_CHUNK = 2;
    public static final int CHUNKS_PER_BLOCK = 64;
    public static final int BLOCK_SIZE_IN_ROM = CHUNKS_PER_BLOCK * BYTES_PER_CHUNK;

    private final ChunkDesc[] chunkDescs;

    // Default constructor
    public Block() {
        this.chunkDescs = new ChunkDesc[CHUNKS_PER_BLOCK];
        Arrays.setAll(this.chunkDescs, i -> new ChunkDesc());  // Initialize array with new ChunkDesc instances
    }

    // Parses a block of data from Sega's format (big-endian 16-bit values)
    public void fromSegaFormat(byte[] buffer) {
        if (buffer.length != BLOCK_SIZE_IN_ROM) {
            throw new IllegalArgumentException("Buffer size does not match block size in ROM");
        }

        for (int i = 0; i < CHUNKS_PER_BLOCK; i++) {
            int index = ((buffer[i * 2] & 0xFF) << 8) | (buffer[i * 2 + 1] & 0xFF); // Big-endian
            chunkDescs[i].set(index);
        }
    }

    // Retrieves a chunk descriptor based on x and y coordinates (0-7 range)
    public ChunkDesc getChunkDesc(int x, int y) {
        if (x > 7 || y > 7) {
            throw new IllegalArgumentException("Invalid chunk index");
        }

        return chunkDescs[y * 8 + x];
    }
}
