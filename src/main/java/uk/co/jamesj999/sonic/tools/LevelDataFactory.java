package uk.co.jamesj999.sonic.tools;

import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.Level;
import uk.co.jamesj999.sonic.level.LevelConstants;
import uk.co.jamesj999.sonic.level.LevelManager;

public class LevelDataFactory {
    public static ChunkDesc[] chunksFromSegaByteArray(byte[] blockBuffer) {

        ChunkDesc[] chunkDescs = new ChunkDesc[LevelConstants.CHUNKS_PER_BLOCK];

        if (blockBuffer.length != LevelConstants.BLOCK_SIZE_IN_ROM) {
            throw new IllegalArgumentException("Buffer size does not match block size in ROM");
        }
        for (int i = 0; i < LevelConstants.CHUNKS_PER_BLOCK; i++) {
            int index = ((blockBuffer[i * 2] & 0xFF) << 8) | (blockBuffer[i * 2 + 1] & 0xFF); // Big-endian
            chunkDescs[i] = new ChunkDesc(index);
        }

        return chunkDescs;
    }
}
