package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.tools.LevelDataFactory;

import java.util.Arrays;

/**
 * Represents a block of chunks in the Sonic 2/3 level editor.
 */
public class Block {


    private ChunkDesc[] chunkDescs;

    // Default constructor
    public Block() {
        this.chunkDescs = new ChunkDesc[LevelConstants.CHUNKS_PER_BLOCK];
        // Initialize array with references to empty ChunkDesc instance (to save on pointlessly making objects)
        Arrays.setAll(this.chunkDescs, i -> ChunkDesc.EMPTY);
    }

    // Parses a block of data from Sega's format (big-endian 16-bit values)
    public void fromSegaFormat(byte[] blockBuffer) {
        this.chunkDescs = LevelDataFactory.chunksFromSegaByteArray(blockBuffer);
    }

    // Retrieves a chunk descriptor based on x and y coordinates (0-7 range)
    public ChunkDesc getChunkDesc(int x, int y) {
        if (x > 7 || y > 7) {
            throw new IllegalArgumentException("Invalid chunk index");
        }

        return chunkDescs[y * 8 + x];
    }
}
