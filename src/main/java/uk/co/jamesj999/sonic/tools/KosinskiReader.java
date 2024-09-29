package uk.co.jamesj999.sonic.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * RLE decompressor for level and art data.
 * <p>
 * See doc/kosinski.txt for details on the structure of compressed data.
 */
public class KosinskiReader {

    // Used to represent the result of the decompression process
    public record Result(boolean success, int byteCount) {}

    private short bitfield;
    private short bitcount;

    public KosinskiReader() {
        this.bitfield = 0;
        this.bitcount = 0;
    }

    /**
     * Decompress data from a ReadableByteChannel.
     *
     * @param channel The channel to read compressed data from.
     * @param buffer The buffer to write the decompressed data.
     * @param bufferSize The size of the buffer.
     * @return A Result object containing success status and bytes written.
     * @throws IOException If an error occurs during decompression.
     */
    public Result decompress(ReadableByteChannel channel, byte[] buffer, int bufferSize) throws IOException {
        if (buffer == null) {
            return new Result(false, 0);
        }

        int pos = 0;
        int count = 0;
        int offset = 0;

        loadBitfield(channel);

        while (pos < bufferSize) {

            // Uncompressed data mode. Copy directly from next byte.
            if (getBit(channel) == 1) {
                buffer[pos++] = readByte(channel);

                continue;
            }

            // Inline compression mode. Get Two's complement for repeat count and add 2.
            // [LLLL LLLL] [HHHH HCCC]
            // If CCC is 0, then we get the following two bytes and add 1.
            // [LLLL LLLL] [HHHH H000] [CCCC CCCC]
            if (getBit(channel) == 1) {
                int low = readByte(channel);
                int high = readByte(channel);

                offset = (0xFFFFFF00 | high) << 5;
                offset = (offset & 0xFFFFFF00) | low;


                count = high & 0x07;

                //
                if (count == 0) {
                    count = readByte(channel) & 0xFF;

                    if (count == 0) {
                        break;
                    }

                    if (count <= 1) {
                        continue;
                    }
                    count++;
                }
                else {
                    count +=2;
                }
            }
            else {
                count = (getBit(channel) << 1) | getBit(channel);
                count += 2;

                offset = (readByte(channel) & 0xFF) | 0xFFFFFF00;
            }

            // Ensure we don't go out of bounds while copying
            while (count > 0 && (pos + offset >= 0) && (pos + offset < bufferSize)) {
                buffer[pos] = buffer[pos + offset];
                pos++;
                count--;

            }
        }

        return new Result(true, pos);
    }

    private void loadBitfield(ReadableByteChannel channel) throws IOException {
        bitfield = (short) ((readByte(channel) & 0xFF) | ((readByte(channel) & 0xFF) << 8));
        bitcount = 16;
    }

    private byte getBit(ReadableByteChannel channel) throws IOException {
        byte bit = (byte) (bitfield & 1);
        bitfield >>= 1;
        bitcount--;

        if (bitcount == 0) {
            loadBitfield(channel);
        }

        return bit;
    }

    private byte readByte(ReadableByteChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        int bytesRead = channel.read(buffer);
        if (bytesRead == -1) {
            throw new IOException("Unexpected end of channel");
        }
        buffer.flip();
        return buffer.get();
    }
}
