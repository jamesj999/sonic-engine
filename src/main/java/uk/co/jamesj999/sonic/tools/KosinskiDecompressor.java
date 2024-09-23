package uk.co.jamesj999.sonic.tools;

import java.nio.ByteBuffer;
import java.io.IOException;

/**
 *
 * Kosinski Decompression Routine.
 *
 *
 * @author farrell
 */
public class KosinskiDecompressor {

    private int bitfield = 0;
    private int bitCount = 0; // Track how many bits are left

    private final ByteBuffer dataBuffer;
    private int position = 0;

    public KosinskiDecompressor(byte[] data) {
        this.dataBuffer = ByteBuffer.wrap(data);
    }


    // Helper to read the next byte
    private int readNextByte() throws IOException {
        if (dataBuffer.remaining() == 0) {
            throw new IOException("Unexpected end of data.");
        }
        return Byte.toUnsignedInt(dataBuffer.get());
    }

    // Refills the bitfield when needed
    private void loadBitfield() throws IOException {
        bitfield = readNextByte() | (readNextByte() << 8);
        bitCount = 16; // Reset bit count after loading 16 bits
    }

    // Shift the bitfield and return the next bit
    private int getBit() throws IOException {
        if (bitCount == 0) {
            loadBitfield(); // Refill the bitfield when needed
        }

        int bit = bitfield & 1;
        bitfield >>= 1;
        bitCount--;
        return bit;
    }

    public byte[] decompress() throws IOException {
        ByteBuffer decompressedBuffer = ByteBuffer.allocate(1024); // Adjust size based on expected output

        // Main loop to process input data
        while (dataBuffer.hasRemaining()) {
            if (getBit() == 1) {
                // Bit is 1, read the next byte and append it to the decompressed buffer
                decompressedBuffer.put((byte) readNextByte());
                position++;

            } else {
                // Bit is 0, check next bit
                if (getBit() == 1) {
                    // Two-byte offset and count case
                    int lo = readNextByte();
                    int hi = readNextByte();

                    int offset = ((hi << 8) | lo) | 0xFF00; // 13-bit two's complement offset
                    int count = hi & 0x07;

                    if (count == 0) {
                        // If count is 0, read the next byte for the real count
                        count = readNextByte();

                        if (count == 0) {
                            break; // Termination case
                        }

                        if (count == 1) {
                            continue; // Restart the main loop
                        }
                    } else {
                        count++;
                    }

                    copyBytes(decompressedBuffer, offset, count);
                } else {
                    // Handle the multi-bit case where count is a two-bit value
                    int count = (getBit() << 1) | getBit();
                    count++;  // Increment count

                    int offset = (readNextByte() | 0xFF00); // Read the offset byte

                    copyBytes(decompressedBuffer, offset, count);
                }
            }
        }

        // Extract the decompressed bytes and return them
        byte[] result = new byte[position];
        decompressedBuffer.flip();
        decompressedBuffer.get(result, 0, position);
        return result;
    }

    // Copies `count` bytes from `offset` in the decompressed buffer
    private void copyBytes(ByteBuffer decompressedBuffer, int offset, int count) {
        int startPos = position - (offset & 0xFFF); // Use two's complement offset for buffer access
        for (int i = 0; i < count; i++) {
            // Ensure we don’t go out of bounds
            if (startPos + i < 0 || startPos + i >= decompressedBuffer.limit()) {
                break;
            }
            byte copiedByte = decompressedBuffer.get(startPos + i);
            decompressedBuffer.put(copiedByte);
            position++;
        }
    }

}