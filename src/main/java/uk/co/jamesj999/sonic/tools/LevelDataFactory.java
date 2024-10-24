package uk.co.jamesj999.sonic.tools;

import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.Level;
import uk.co.jamesj999.sonic.level.LevelConstants;
import uk.co.jamesj999.sonic.level.LevelManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class LevelDataFactory {
    public static ChunkDesc[] chunksFromSegaByteArray(byte[] blockBuffer) {

        int chunkIndexSize = ChunkDesc.getIndexSize(); // Assuming this method exists
        int chunksPerBlock = LevelConstants.CHUNKS_PER_BLOCK;
        int expectedBlockSize = chunksPerBlock * chunkIndexSize;

        ChunkDesc[] chunkDescs = new ChunkDesc[chunksPerBlock];

        if (blockBuffer.length != expectedBlockSize) {
            throw new IllegalArgumentException("Buffer size does not match expected block size");
        }

        ByteBuffer bb = ByteBuffer.wrap(blockBuffer);
        bb.order(ByteOrder.BIG_ENDIAN);

        for (int i = 0; i < chunksPerBlock; i++) {
            // Read as unsigned 16-bit integer
            int index = bb.getShort() & 0xFFFF; // Convert to unsigned by masking with 0xFFFF
            chunkDescs[i] = new ChunkDesc(index);
        }

        return chunkDescs;
    }
}
