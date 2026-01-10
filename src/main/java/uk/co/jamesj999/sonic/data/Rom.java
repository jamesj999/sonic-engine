package uk.co.jamesj999.sonic.data;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents a ROM file for reading and writing.
 */
public class Rom {
    private static final Logger LOGGER = Logger.getLogger(Rom.class.getName());

    private FileChannel fileChannel;
    private final static int CHECKSUM_OFFSET = 0x018E;
    private final static int CHECKSUM_BUFFER_SIZE = 0x8000; // 32kB
    private final static int ROM_HEADER_OFFSET = 0x100;
    private final static int ROM_LENGTH_OFFSET = 0x01A4;
    private final static int DOMESTIC_NAME_LEN = 48;
    private final static int DOMESTIC_NAME_OFFSET = ROM_HEADER_OFFSET + 32;
    private final static int INTERNATIONAL_NAME_LEN = 48;
    private final static int INTERNATIONAL_NAME_OFFSET = DOMESTIC_NAME_OFFSET + DOMESTIC_NAME_LEN;

    // Pre-allocated buffers for small reads (avoid per-call allocations)
    private final ByteBuffer buffer1 = ByteBuffer.allocate(1);
    private final ByteBuffer buffer2 = ByteBuffer.allocate(2);
    private final ByteBuffer buffer4 = ByteBuffer.allocate(4);

    public boolean open(String spath) {
        try {
            Path path = Path.of(spath);
            LOGGER.fine(path.toAbsolutePath().toString());
            fileChannel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to open ROM: " + spath, e);
            return false;
        }
    }

    public FileChannel getFileChannel() {
        return fileChannel;
    }

    public long getSize() throws IOException {
        return fileChannel.size();
    }

    /**
     * Read the whole ROM into memory.
     */
    public byte[] readAllBytes() throws IOException {
        long size = getSize();
        if (size > Integer.MAX_VALUE) {
            throw new IOException("ROM too large to buffer in memory: " + size + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) size);
        fileChannel.position(0);
        int read = fileChannel.read(buffer);
        if (read < size) {
            throw new IOException("Unable to read entire ROM (read " + read + " of " + size + " bytes)");
        }
        return buffer.array();
    }

    public int readAddrRange() throws IOException {
        return read32BitAddr(ROM_LENGTH_OFFSET);
    }

    public void writeSize(int size) throws IOException {
        write32BitAddr(size, ROM_LENGTH_OFFSET);
        fileChannel.force(true); // Ensure the changes are written to the file
    }

    public int calculateChecksum() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(CHECKSUM_BUFFER_SIZE);
        fileChannel.position(512); // Skip the first 512 bytes
        int count = 0;

        while (fileChannel.read(buffer) != -1) {
            buffer.flip();
            for (int i = 0; i < buffer.limit(); i += 2) {
                int num = Byte.toUnsignedInt(buffer.get(i)) << 8;
                if (i + 1 < buffer.limit()) {
                    num |= Byte.toUnsignedInt(buffer.get(i + 1));
                }
                count = (count + num) & 0xFFFF;
            }
            buffer.clear();
        }

        return count;
    }

    public int readChecksum() throws IOException {
        return read16BitAddr(CHECKSUM_OFFSET);
    }

    public void writeChecksum(int checksum) throws IOException {
        write16BitAddr(checksum, CHECKSUM_OFFSET);
        fileChannel.force(true); // Ensure the changes are written to the file
    }

    public String readDomesticName() throws IOException {
        return readString(DOMESTIC_NAME_OFFSET, DOMESTIC_NAME_LEN);
    }

    public String readInternationalName() throws IOException {
        return readString(INTERNATIONAL_NAME_OFFSET, INTERNATIONAL_NAME_LEN);
    }

    public byte readByte(long offset) throws IOException {
        buffer1.clear();
        fileChannel.position(offset);
        fileChannel.read(buffer1);
        buffer1.flip();
        return buffer1.get();
    }

    public byte[] readBytes(long offset, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        fileChannel.position(offset);
        int bytesRead = fileChannel.read(buffer);
        return Arrays.copyOf(buffer.array(), bytesRead);
    }

    public int read16BitAddr(long offset) throws IOException {
        buffer2.clear();
        long fileSize = fileChannel.size();
        if (offset > fileSize) {
            LOGGER.fine("offset " + offset + " is longer than current fileSize " + fileSize);
        }
        fileChannel.position(offset);
        fileChannel.read(buffer2);

        buffer2.flip();
        return (Byte.toUnsignedInt(buffer2.get()) << 8) | Byte.toUnsignedInt(buffer2.get());
    }

    public int read32BitAddr(long offset) throws IOException {
        buffer4.clear();
        fileChannel.position(offset);
        fileChannel.read(buffer4);

        buffer4.flip();

        int result = (Byte.toUnsignedInt(buffer4.get()) << 24) |
                (Byte.toUnsignedInt(buffer4.get()) << 16) |
                (Byte.toUnsignedInt(buffer4.get()) << 8) |
                Byte.toUnsignedInt(buffer4.get());

        return result;
    }

    public void write16BitAddr(int addr, long offset) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.put((byte) ((addr >> 8) & 0xFF));
        buffer.put((byte) (addr & 0xFF));
        buffer.flip();
        fileChannel.position(offset);
        fileChannel.write(buffer);
    }

    public void write32BitAddr(int addr, long offset) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.put((byte) ((addr >> 24) & 0xFF));
        buffer.put((byte) ((addr >> 16) & 0xFF));
        buffer.put((byte) ((addr >> 8) & 0xFF));
        buffer.put((byte) (addr & 0xFF));
        buffer.flip();
        fileChannel.position(offset);
        fileChannel.write(buffer);
    }

    private String readString(long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        fileChannel.position(offset);
        fileChannel.read(buffer);
        return new String(buffer.array()).trim();
    }
}
