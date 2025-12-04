package uk.co.jamesj999.sonic.tools;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Decompresses Saxman-compressed data.
 * Based on description and Z80 code.
 */
public class SaxmanDecompressor {

    public byte[] decompress(byte[] compressed) {
        // First 2 bytes are size of compressed data. Sonic docs show little-endian in many drivers.
        // Accept both: try little-endian first; if it underflows, fall back to big-endian.
        int sizeLe = (compressed[0] & 0xFF) | ((compressed[1] & 0xFF) << 8);
        int sizeBe = ((compressed[0] & 0xFF) << 8) | (compressed[1] & 0xFF);
        int compressedSize = sizeLe > 0 ? sizeLe : sizeBe;
        // Clamp to available data
        compressedSize = Math.min(compressedSize, compressed.length - 2);

        // Input pointer starts after header
        int inPos = 2;
        // Use a generous buffer; Saxman in Sonic 2 can exceed the 0x800 Z80 buffer.
        byte[] output = new byte[0x40000];
        int outPos = 0;

        int description = 0;
        int bitsLeft = 0;

        int inputEnd = 2 + compressedSize;

        while (inPos < compressed.length) { // Safety check, real check inside
            // Check if we need to reload description
            if (bitsLeft == 0) {
                if (inPos >= inputEnd) break;
                description = compressed[inPos++] & 0xFF;
                bitsLeft = 8;
            }

            // Read bit (LSB first)
            boolean isUncompressed = (description & 1) != 0;
            description >>= 1;
            bitsLeft--;

            if (isUncompressed) {
                if (inPos >= inputEnd) break;
                byte b = compressed[inPos++];
                output[outPos++] = b;
            } else {
                if (inPos + 1 >= inputEnd) break;
                int l = compressed[inPos++] & 0xFF;
                int h = compressed[inPos++] & 0xFF;

                // Format: [LLLL LLLL] [HHHH CCCC]
                int length = (h & 0x0F) + 3;
                int high = (h >> 4) & 0x0F;

                // Saxman re-basing as per Sega Retro spec
                int base = ((high << 8) | l);
                base = (base + 0x12) & 0x0FFF;
                int destination = outPos;
                int source = ((base - destination) & 0x0FFF) + destination - 0x1000;

                if (source < 0 || Integer.compareUnsigned(source, destination) > 0) {
                    // Zero fill if source would go past current destination
                    for (int i = 0; i < length; i++) {
                        output[outPos++] = 0;
                    }
                } else {
                    int src = source;
                    for (int i = 0; i < length; i++) {
                        output[outPos++] = output[src++];
                    }
                }
            }
        }

        return Arrays.copyOf(output, outPos);
    }

    /**
     * Convenience for streams that are missing the 2-byte size header (e.g. Z80 driver dump).
     * The payload is treated as-is and a synthetic header is prefixed for decoding.
     */
    public byte[] decompressRaw(byte[] payload, int compressedSize) {
        byte[] withHeader = new byte[payload.length + 2];
        withHeader[0] = (byte) (compressedSize & 0xFF);
        withHeader[1] = (byte) ((compressedSize >> 8) & 0xFF);
        System.arraycopy(payload, 0, withHeader, 2, payload.length);
        return decompress(withHeader);
    }
}
