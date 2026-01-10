package uk.co.jamesj999.sonic.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * A thread-safe, statically callable Java implementation of the Enigma decompression algorithm.
 * Enigma is a run-length encoding variant used for plane mappings in Sega Genesis games.
 *
 * Based on the format documented at Sega Retro and the reference implementation in s2ssedit.
 */
public class EnigmaReader {

    /**
     * Decompresses Enigma data from a ReadableByteChannel.
     *
     * @param inputChannel The input channel to read compressed data from.
     * @return The decompressed data as a byte array (big-endian 16-bit words).
     * @throws IOException If an I/O error occurs while reading from the input channel.
     */
    public static byte[] decompress(ReadableByteChannel inputChannel) throws IOException {
        return decompress(inputChannel, 0, false);
    }

    /**
     * Decompresses Enigma data from a ReadableByteChannel.
     *
     * @param inputChannel The input channel to read compressed data from.
     * @param startingArtTile The starting art tile value to add to each decompressed word.
     * @return The decompressed data as a byte array (big-endian 16-bit words).
     * @throws IOException If an I/O error occurs while reading from the input channel.
     */
    public static byte[] decompress(ReadableByteChannel inputChannel, int startingArtTile) throws IOException {
        return decompress(inputChannel, startingArtTile, false);
    }

    /**
     * Decompresses Enigma data from a ReadableByteChannel.
     *
     * @param inputChannel The input channel to read compressed data from.
     * @param startingArtTile The starting art tile value to add to each decompressed word.
     * @param printDebugInformation If true, debug information will be printed to standard error.
     * @return The decompressed data as a byte array (big-endian 16-bit words).
     * @throws IOException If an I/O error occurs while reading from the input channel.
     */
    public static byte[] decompress(ReadableByteChannel inputChannel, int startingArtTile, boolean printDebugInformation) throws IOException {
        final ByteReader reader = new ByteReader(inputChannel);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        int packetLength = reader.read();
        int flagMask = reader.read();
        int incrementingValue = (reader.read() << 8) | reader.read();
        int commonValue = (reader.read() << 8) | reader.read();

        int flagBitCount = Integer.bitCount(flagMask & 0x1F);

        if (printDebugInformation) {
            System.err.printf("Enigma: packetLength=%d flagMask=0x%02X flagBits=%d incr=0x%04X common=0x%04X%n",
                    packetLength, flagMask, flagBitCount, incrementingValue, commonValue);
        }

        BitReader bitReader = new BitReader(reader);

        while (true) {
            int firstBit = bitReader.readBits(1);

            if (firstBit == 1) {
                int mode = bitReader.readBits(2);
                int count = bitReader.readBits(4) + 1;

                switch (mode) {
                    case 0: // 100 - copy inline value count times
                    case 1: // 101 - copy inline value count times, increment after each
                    case 2: // 110 - copy inline value count times, decrement after each
                        int delta = (mode == 0) ? 0 : (mode == 1) ? 1 : -1;
                        int flags = readFlags(bitReader, flagMask, flagBitCount);
                        int value = bitReader.readBits(packetLength) | flags;

                        for (int i = 0; i < count; i++) {
                            writeWord(output, (value + startingArtTile) & 0xFFFF);
                            value += delta;
                        }

                        if (printDebugInformation) {
                            System.err.printf("  Mode %d: count=%d delta=%d%n", mode, count, delta);
                        }
                        break;

                    case 3: // 111 - terminator or inline copy
                        if (count - 1 == 0x0F) {
                            if (printDebugInformation) {
                                System.err.println("  Terminator");
                            }
                            return output.toByteArray();
                        }

                        for (int i = 0; i < count; i++) {
                            int inlineFlags = readFlags(bitReader, flagMask, flagBitCount);
                            int inlineValue = bitReader.readBits(packetLength) | inlineFlags;
                            writeWord(output, (inlineValue + startingArtTile) & 0xFFFF);
                        }

                        if (printDebugInformation) {
                            System.err.printf("  Mode 3 inline: count=%d%n", count);
                        }
                        break;
                }
            } else {
                int secondBit = bitReader.readBits(1);
                int count = bitReader.readBits(4) + 1;

                if (secondBit == 0) {
                    // 00 - copy incrementing value count times, increment after each
                    for (int i = 0; i < count; i++) {
                        writeWord(output, (incrementingValue + startingArtTile) & 0xFFFF);
                        incrementingValue++;
                    }

                    if (printDebugInformation) {
                        System.err.printf("  Incrementing: count=%d%n", count);
                    }
                } else {
                    // 01 - copy common value count times
                    for (int i = 0; i < count; i++) {
                        writeWord(output, (commonValue + startingArtTile) & 0xFFFF);
                    }

                    if (printDebugInformation) {
                        System.err.printf("  Common: count=%d%n", count);
                    }
                }
            }
        }
    }

    private static int readFlags(BitReader bitReader, int flagMask, int flagBitCount) throws IOException {
        if (flagBitCount == 0) {
            return 0;
        }

        int flagBits = bitReader.readBits(flagBitCount);
        int flags = 0;
        int bitIndex = 0;

        for (int i = 4; i >= 0; i--) {
            if ((flagMask & (1 << i)) != 0) {
                if ((flagBits & (1 << (flagBitCount - 1 - bitIndex))) != 0) {
                    flags |= (1 << (11 + i));
                }
                bitIndex++;
            }
        }

        return flags;
    }

    private static void writeWord(ByteArrayOutputStream output, int value) {
        output.write((value >> 8) & 0xFF);
        output.write(value & 0xFF);
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

        int readBits(int count) throws IOException {
            while (bitCount < count) {
                buffer = (buffer << 8) | reader.read();
                bitCount += 8;
            }
            int value = (buffer >> (bitCount - count)) & ((1 << count) - 1);
            bitCount -= count;
            if (bitCount == 0) {
                buffer = 0;
            } else {
                buffer &= (1 << bitCount) - 1;
            }
            return value;
        }
    }
}
