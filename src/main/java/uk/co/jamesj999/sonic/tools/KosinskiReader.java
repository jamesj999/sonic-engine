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

        while (true) {
            if (getBit(channel) == 1) {
                buffer[pos++] = readByte(channel);

                if (pos >= bufferSize) {
                    return new Result(false, pos);
                }

                continue;
            }

            if (getBit(channel) == 1) {
                byte lo = readByte(channel);
                byte hi = readByte(channel);

                // Use 13-bit two's complement for offset
                offset = ((hi << 8) | (lo & 0xFF)) | 0xFF00;
                count = hi & 0x07;

                if (count == 0) {
                    count = readByte(channel) & 0xFF;

                    if (count == 0) {
                        break;
                    }

                    if (count <= 1) {
                        continue;
                    }
                } else {
                    count++;
                }
            } else {
                count = (getBit(channel) << 1) | getBit(channel);
                count++;

                offset = (readByte(channel) & 0xFF) | 0xFF00;
            }

            // Ensure we don't go out of bounds while copying
            while (count > 0 && (pos + offset >= 0) && (pos + offset < bufferSize)) {
                buffer[pos] = buffer[pos + offset];
                pos++;
                count--;

                if (pos >= bufferSize) {
                    return new Result(false, pos);
                }
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
