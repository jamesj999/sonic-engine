package uk.co.jamesj999.sonic.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * A thread-safe, statically callable Java implementation of the Kosinski decompression algorithm.
 */
public class KosinskiReader {

    // Size of the sliding window buffer (backsearch buffer)
    private static final int SLIDING_WINDOW_SIZE = 0x2000; // 8192 bytes

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
                    byte value = backsearchBuffer[(state.writePosition - distance) % SLIDING_WINDOW_SIZE];
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
