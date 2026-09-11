package com.openggf.data.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;

/**
 * A thread-safe, statically callable Java implementation of the Kosinski decompression algorithm.
 */
public class KosinskiReader {

    // Size of the sliding window buffer (backsearch buffer)
    private static final int SLIDING_WINDOW_SIZE = 0x2000; // 8192 bytes

    // Maximum decompressed output size (1MB - well above any Mega Drive data)
    private static final int MAX_OUTPUT_SIZE = 0x100000;
    private static final int KOSM_MODULE_SIZE = 0x1000;

    /** Exact consumed and produced spans for one standard Kosinski stream. */
    public record StandardArchiveInfo(int compressedLength, int decompressedLength) {
    }

    /**
     * Inspects one standard Kosinski stream beginning at its descriptor field.
     * Unlike KosM, standard streams carry neither a size header nor alignment.
     */
    public static StandardArchiveInfo inspectStandard(byte[] data, int offset)
            throws IOException {
        ResumableKosinskiDecoder decoder = new ResumableKosinskiDecoder(data, offset);
        while (!decoder.complete()) {
            decoder.step(1);
        }
        return new StandardArchiveInfo(
                decoder.compressedBytesConsumed(), decoder.output().length);
    }

    /**
     * Decompresses data from the given ReadableByteChannel using the Kosinski algorithm.
     * This method is thread-safe and statically callable.
     *
     * @param inputChannel           The input channel to read compressed data from.
     * @param printDebugInformation  If true, debug information will be printed to standard error.
     * @return The decompressed data as a byte array.
     * @throws IOException If an I/O error occurs while reading from the input channel.
     */
    public static byte[] decompress(ReadableByteChannel inputChannel, boolean printDebugInformation) throws IOException {
        // Initialize state variables encapsulated in a final object to ensure thread safety
        final State state = new State();

        // Initialize the backsearch buffer
        final byte[] backsearchBuffer = new byte[SLIDING_WINDOW_SIZE];

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        final ByteBuffer readBuffer = ByteBuffer.allocate(1);

        // Function to read a single byte from the input channel
        ReadByteFunction readByte = () -> {
            readBuffer.clear();
            int bytesRead = inputChannel.read(readBuffer);
            if (bytesRead != 1) {
                throw new IOException("Unexpected end of input data");
            }
            readBuffer.flip();
            int value = readBuffer.get() & 0xFF; // Ensure unsigned byte
            state.readPosition++;
            return value;
        };

        // Function to write a byte to the output stream and update the backsearch buffer
        WriteByteFunction writeByte = (byte value) -> {
            if (state.writePosition >= MAX_OUTPUT_SIZE) {
                throw new IOException("Kosinski decompression exceeded maximum output size (" + MAX_OUTPUT_SIZE + " bytes)");
            }
            outputStream.write(value);
            backsearchBuffer[state.writePosition % SLIDING_WINDOW_SIZE] = value;
            state.writePosition++;
        };

        // Helper class for descriptor handling
        class DescriptorHelper {
            int descriptor;
            int bitsRemaining;

            void getDescriptor() throws IOException {
                int lowByte = readByte.read();
                int highByte = readByte.read();
                descriptor = ((highByte << 8) | lowByte) & 0xFFFF;
                bitsRemaining = 16;
            }

            boolean popDescriptor() throws IOException {
                boolean result = (descriptor & 1) != 0;
                descriptor >>>= 1;
                bitsRemaining--;
                if (bitsRemaining == 0) {
                    getDescriptor();
                }
                return result;
            }
        }

        DescriptorHelper descriptorHelper = new DescriptorHelper();
        descriptorHelper.getDescriptor();

        // Main decompression loop
        while (true) {
            if (descriptorHelper.popDescriptor()) {
                // Literal byte
                int position = state.readPosition;

                int value = readByte.read();

                if (printDebugInformation) {
                    System.err.printf("%X - Literal match: At %X, value %X%n", position, state.writePosition, value);
                }

                writeByte.write((byte) value);
            } else {
                // Compressed sequence
                int distance;
                int count;

                if (descriptorHelper.popDescriptor()) {
                    // Full match
                    int position = state.readPosition;

                    int lowByte = readByte.read();
                    int highByte = readByte.read();

                    distance = ((highByte & 0xF8) << 5) | lowByte;
                    distance = ((distance ^ 0x1FFF) + 1) & 0x1FFF; // Convert from negative two's complement to positive

                    count = highByte & 0x07;

                    if (count != 0) {
                        count += 2;

                        if (printDebugInformation) {
                            System.err.printf("%X - Full match: At %X, src %X, len %X%n",
                                    position, state.writePosition, state.writePosition - distance, count);
                        }
                    } else {
                        count = readByte.read() + 1;

                        if (count == 1) {
                            if (printDebugInformation) {
                                System.err.printf("%X - Terminator: At %X, src %X%n",
                                        position, state.writePosition, state.writePosition - distance);
                            }
                            break; // End of data
                        } else if (count == 2) {
                            if (printDebugInformation) {
                                System.err.printf("%X - 0xA000 boundary flag: At %X, src %X%n",
                                        position, state.writePosition, state.writePosition - distance);
                            }
                            continue; // Ignore and continue
                        } else {
                            if (printDebugInformation) {
                                System.err.printf("%X - Extended full match: At %X, src %X, len %X%n",
                                        position, state.writePosition, state.writePosition - distance, count);
                            }
                        }
                    }
                } else {
                    // Inline match
                    count = 2;

                    if (descriptorHelper.popDescriptor()) {
                        count += 2;
                    }
                    if (descriptorHelper.popDescriptor()) {
                        count += 1;
                    }

                    distance = (readByte.read() ^ 0xFF) + 1; // Convert from negative two's complement to positive
                    distance &= 0xFF; // Ensure byte range

                    if (printDebugInformation) {
                        System.err.printf("%X - Inline match: At %X, src %X, len %X%n",
                                state.readPosition - 1, state.writePosition, state.writePosition - distance, count);
                    }
                }

                // Copy the matched sequence from the backsearch buffer
                for (int i = 0; i < count; i++) {
                    byte value = backsearchBuffer[Math.floorMod(state.writePosition - distance, SLIDING_WINDOW_SIZE)];
                    writeByte.write(value);
                }
            }
        }

        // Return the decompressed data
        return outputStream.toByteArray();
    }

    /**
     * Decompresses data from the given ReadableByteChannel using the Kosinski algorithm.
     * This method is thread-safe and statically callable.
     *
     * @param inputChannel The input channel to read compressed data from.
     * @return The decompressed data as a byte array.
     * @throws IOException If an I/O error occurs while reading from the input channel.
     */
    public static byte[] decompress(ReadableByteChannel inputChannel) throws IOException {
        return decompress(inputChannel, false);
    }

    /**
     * Decompresses Kosinski Moduled data from a byte array at the given offset.
     * <p>
     * Kosinski Moduled format:
     * <ol>
     *   <li>2-byte big-endian header: total uncompressed size</li>
     *   <li>Module 1: standard Kosinski compressed data (up to 0x1000 bytes decompressed)</li>
     *   <li>Zero-byte padding to align the next module to a 16-byte boundary
     *       (relative to 2 bytes after the header start)</li>
     *   <li>Module 2, Module 3, ... until total decompressed bytes >= header size</li>
     * </ol>
     *
     * @param data   The byte array containing the compressed data.
     * @param offset The offset within the array where the moduled data begins.
     * @return The decompressed data as a byte array.
     * @throws IOException If an I/O error occurs during decompression.
     */
    public static byte[] decompressModuled(byte[] data, int offset) throws IOException {
        if (offset + 2 > data.length) {
            throw new IOException("Not enough data for Kosinski Moduled header");
        }

        // Read 2-byte big-endian header: total uncompressed size
        int fullSize = decodeModuledSize(data, offset);
        if (fullSize == 0) {
            return new byte[0];
        }
        if (fullSize > MAX_OUTPUT_SIZE) {
            throw new IOException("Kosinski Moduled header declares " + fullSize +
                    " bytes, exceeding maximum output size (" + MAX_OUTPUT_SIZE + " bytes)");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(fullSize);
        int pos = offset + 2; // current position in the data array

        while (output.size() < fullSize) {
            if (pos >= data.length) {
                throw new IOException("Unexpected end of data in Kosinski Moduled stream");
            }

            // Decompress one standard Kosinski module
            int remaining = data.length - pos;
            ByteArrayInputStream bais = new ByteArrayInputStream(data, pos, remaining);
            ReadableByteChannel channel = Channels.newChannel(bais);
            byte[] moduleData = decompress(channel);
            int compressedBytesConsumed = remaining - bais.available();

            output.write(moduleData);
            pos += compressedBytesConsumed;

            if (output.size() >= fullSize) {
                break;
            }

            // Pad position to next 16-byte boundary relative to (offset + 2)
            // Formula from reference: ((pos - 2 + 0xF) & ~0xF) + 2
            // where positions are relative to the start of the data, so we adjust for our offset
            int relativePos = pos - offset;
            int paddedRelative = (((relativePos - 2) + 0xF) & ~0xF) + 2;
            pos = offset + paddedRelative;
        }

        // Truncate to exactly fullSize bytes
        byte[] result = output.toByteArray();
        if (result.length > fullSize) {
            result = Arrays.copyOf(result, fullSize);
        }
        return result;
    }

    /**
     * Parses a complete KosM archive without guessing its compressed span.
     *
     * <p>The returned source length includes the two-byte archive header and
     * any inter-module alignment, but excludes bytes after the final module
     * terminator. This is the canonical span used by hardware submission
     * fingerprints.
     */
    public static ModuledArchiveInfo inspectModuled(byte[] data, int offset)
            throws IOException {
        int fullSize = decodeModuledSize(data, offset);
        if (fullSize == 0) {
            return new ModuledArchiveInfo(2, 0, 0);
        }
        int moduleCount = (fullSize + KOSM_MODULE_SIZE - 1) / KOSM_MODULE_SIZE;
        int pos = offset + 2;
        for (int module = 0; module < moduleCount; module++) {
            ResumableKosinskiDecoder decoder =
                    new ResumableKosinskiDecoder(data, pos);
            while (!decoder.complete()) {
                decoder.step(256);
            }
            pos += decoder.compressedBytesConsumed();
            if (module + 1 < moduleCount) {
                int relative = pos - (offset + 2);
                pos = offset + 2 + ((relative + 0xF) & ~0xF);
                if (pos > data.length) {
                    throw new IOException(
                            "Unexpected end of Kosinski Moduled alignment");
                }
            }
        }
        return new ModuledArchiveInfo(
                pos - offset,
                fullSize,
                moduleCount);
    }

    private static int decodeModuledSize(byte[] data, int offset)
            throws IOException {
        if (offset < 0 || offset + 2 > data.length) {
            throw new IOException("Not enough data for Kosinski Moduled header");
        }
        int encoded = ((data[offset] & 0xFF) << 8)
                | (data[offset + 1] & 0xFF);
        // The original Queue_Kos_Module treats the wrapped $A000 header as
        // $8000 bytes before deriving the module count.
        return encoded == 0xA000 ? 0x8000 : encoded;
    }

    public record ModuledArchiveInfo(
            int compressedLength,
            int decompressedLength,
            int moduleCount) {
    }

    /**
     * Decompresses Kosinski Moduled data from a ReadableByteChannel.
     * This reads all available data from the channel into a byte array first.
     *
     * @param inputChannel The input channel to read compressed data from.
     * @return The decompressed data as a byte array.
     * @throws IOException If an I/O error occurs during decompression.
     */
    public static byte[] decompressModuled(ReadableByteChannel inputChannel) throws IOException {
        // Read all data from the channel
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ByteBuffer readBuf = ByteBuffer.allocate(4096);
        while (inputChannel.read(readBuf) != -1) {
            readBuf.flip();
            buffer.write(readBuf.array(), 0, readBuf.limit());
            readBuf.clear();
        }
        return decompressModuled(buffer.toByteArray(), 0);
    }

    // Helper class to encapsulate mutable state
    private static class State {
        int readPosition = 0;
        int writePosition = 0;
    }

    // Functional interfaces for read and write operations
    @FunctionalInterface
    private interface ReadByteFunction {
        int read() throws IOException;
    }

    @FunctionalInterface
    private interface WriteByteFunction {
        void write(byte value) throws IOException;
    }
}
