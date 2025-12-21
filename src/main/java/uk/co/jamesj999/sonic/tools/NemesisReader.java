package uk.co.jamesj999.sonic.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * A thread-safe, statically callable Java implementation of the Nemesis decompression algorithm.
 * Based on Sega's standard Nemesis routine (as documented by Sega Retro).
 */
public class NemesisReader {

    private static final int PATTERN_SIZE = 0x20; // 32 bytes per 8x8 pattern
    private static final int INLINE_PREFIX_MASK = 0xFC; // 11111100
    private static final int INLINE_PREFIX_VALUE = 0xFC; // top 6 bits set

    /**
     * Decompresses Nemesis data from a ReadableByteChannel.
     *
     * @param inputChannel The input channel to read compressed data from.
     * @return The decompressed data as a byte array.
     * @throws IOException If an I/O error occurs while reading from the input channel.
     */
    public static byte[] decompress(ReadableByteChannel inputChannel) throws IOException {
        return decompress(inputChannel, false);
    }

    /**
     * Decompresses Nemesis data from a ReadableByteChannel.
     *
     * @param inputChannel The input channel to read compressed data from.
     * @param printDebugInformation If true, debug information will be printed to standard error.
     * @return The decompressed data as a byte array.
     * @throws IOException If an I/O error occurs while reading from the input channel.
     */
    public static byte[] decompress(ReadableByteChannel inputChannel, boolean printDebugInformation) throws IOException {
        final ByteReader reader = new ByteReader(inputChannel);

        int header = (reader.read() << 8) | reader.read();
        boolean xorMode = (header & 0x8000) != 0;
        int patternCount = header & 0x7FFF;

        if (patternCount == 0) {
            return new byte[0];
        }

        int[] codeLengths = new int[256];
        int[] codeEntries = new int[256];

        buildCodeTable(reader, codeLengths, codeEntries);

        BitReader bitReader = new BitReader(reader);
        int totalRows = patternCount * 8;
        byte[] output = new byte[patternCount * PATTERN_SIZE];
        byte[] prevRow = new byte[4];

        int outPos = 0;
        for (int row = 0; row < totalRows; row++) {
            byte[] rowBytes = decodeRow(bitReader, codeLengths, codeEntries);
            if (xorMode) {
                for (int i = 0; i < 4; i++) {
                    rowBytes[i] = (byte) (rowBytes[i] ^ prevRow[i]);
                    prevRow[i] = rowBytes[i];
                }
            }
            System.arraycopy(rowBytes, 0, output, outPos, rowBytes.length);
            outPos += rowBytes.length;
        }

        if (printDebugInformation) {
            System.err.printf("Nemesis: patterns=%d xor=%s bytes=%d%n", patternCount, xorMode, output.length);
        }

        return output;
    }

    private static void buildCodeTable(ByteReader reader, int[] codeLengths, int[] codeEntries) throws IOException {
        int paletteIndex = reader.read() & 0x0F;

        while (true) {
            int control = reader.read();
            if (control == 0xFF) {
                return;
            }
            if ((control & 0x80) != 0) {
                paletteIndex = control & 0x0F;
                continue;
            }

            int repeatCount = (control >> 4) & 0x7;
            int codeLength = control & 0x0F;
            int code = reader.read();

            if (codeLength == 0) {
                continue;
            }

            int entry = (repeatCount << 4) | paletteIndex;
            if (codeLength == 8) {
                codeLengths[code] = codeLength;
                codeEntries[code] = entry;
            } else {
                int shift = 8 - codeLength;
                int base = (code << shift) & 0xFF;
                int count = 1 << shift;
                for (int i = 0; i < count; i++) {
                    int index = (base + i) & 0xFF;
                    codeLengths[index] = codeLength;
                    codeEntries[index] = entry;
                }
            }
        }
    }

    private static byte[] decodeRow(BitReader bitReader, int[] codeLengths, int[] codeEntries) throws IOException {
        byte[] row = new byte[4];
        int nibbleIndex = 0;

        while (nibbleIndex < 8) {
            int prefix = bitReader.peekBits(8);
            int palette;
            int repeat;

            if ((prefix & INLINE_PREFIX_MASK) == INLINE_PREFIX_VALUE) {
                bitReader.readBits(6);
                int inline = bitReader.readBits(7);
                palette = inline & 0xF;
                repeat = (inline >> 4) & 0x7;
            } else {
                int codeLength = codeLengths[prefix];
                if (codeLength == 0) {
                    throw new IOException(String.format("Nemesis decode error: invalid code length at prefix 0x%02X", prefix));
                }
                int entry = codeEntries[prefix];
                bitReader.readBits(codeLength);
                palette = entry & 0xF;
                repeat = (entry >> 4) & 0xF;
            }

            int run = repeat + 1;
            for (int i = 0; i < run && nibbleIndex < 8; i++) {
                int byteIndex = nibbleIndex / 2;
                if ((nibbleIndex & 1) == 0) {
                    row[byteIndex] = (byte) (palette << 4);
                } else {
                    row[byteIndex] |= (byte) palette;
                }
                nibbleIndex++;
            }
        }

        return row;
    }

    private static final class ByteReader {
        private final ReadableByteChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocate(1);

        private ByteReader(ReadableByteChannel channel) {
            this.channel = channel;
        }

        int read() throws IOException {
            buffer.clear();
            int bytesRead = channel.read(buffer);
            if (bytesRead != 1) {
                throw new IOException("Unexpected end of input data");
            }
            buffer.flip();
            return buffer.get() & 0xFF;
        }
    }

    private static final class BitReader {
        private final ByteReader reader;
        private int buffer;
        private int bitCount;

        private BitReader(ByteReader reader) {
            this.reader = reader;
        }

        int peekBits(int count) throws IOException {
            ensure(count);
            return (buffer >> (bitCount - count)) & ((1 << count) - 1);
        }

        int readBits(int count) throws IOException {
            int value = peekBits(count);
            bitCount -= count;
            if (bitCount == 0) {
                buffer = 0;
            } else {
                buffer &= (1 << bitCount) - 1;
            }
            return value;
        }

        private void ensure(int count) throws IOException {
            while (bitCount < count) {
                buffer = (buffer << 8) | reader.read();
                bitCount += 8;
            }
        }
    }
}
