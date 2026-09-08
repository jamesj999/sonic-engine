package com.openggf.data;

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
 * Implements AutoCloseable for proper resource management.
 */
@com.openggf.game.ModApi
public class Rom implements AutoCloseable {
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

    // Cached file size for bounds checking (set on open)
    private long fileSize = -1;

    // Immutable ROM assets are shared by all decoders for this open ROM.
    // Keep ownership here rather than in a static cache so closing/replacing
    // a ROM releases the cache and cannot mix assets from different games.
    private RomByteReader byteReader;

    public synchronized boolean open(String spath) {
        byteReader = null;
        try {
            Path path = Path.of(spath);
            // Resolve relative paths against user.dir. In GraalVM native images
            // launched from macOS Finder, Path.toAbsolutePath() may fail because
            // getcwd() is broken. Explicit resolution against user.dir is reliable.
            if (!path.isAbsolute()) {
                String userDir = System.getProperty("user.dir");
                if (userDir != null) {
                    path = Path.of(userDir).resolve(path);
                }
            }
            LOGGER.fine(path.toString());
            // Open read-only. macOS restricts write access to quarantined files
            // when launched from Finder. Write methods exist for ROM tools but
            // are not used during normal engine operation.
            fileChannel = FileChannel.open(path, StandardOpenOption.READ);
            fileSize = fileChannel.size();
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to open ROM: " + spath, e);
            return false;
        }
    }

    /**
     * Closes the ROM file channel and releases resources.
     */
    @Override
    public synchronized void close() {
        byteReader = null;
        if (fileChannel != null) {
            try {
                fileChannel.close();
                LOGGER.fine("ROM file channel closed");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing ROM file channel", e);
            }
            fileChannel = null;
        }
    }

    /**
     * Checks if the ROM file is currently open.
     */
    public boolean isOpen() {
        return fileChannel != null && fileChannel.isOpen();
    }

    synchronized RomByteReader byteReader() throws IOException {
        if (!isOpen()) {
            throw new IOException("Cannot buffer a closed ROM");
        }
        if (byteReader == null) {
            byteReader = new RomByteReader(readAllBytes());
        }
        return byteReader;
    }

    public FileChannel getFileChannel() {
        return fileChannel;
    }

    public long getSize() throws IOException {
        return fileSize >= 0 ? fileSize : fileChannel.size();
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
        long offset = 0;
        while (buffer.hasRemaining()) {
            int read = fileChannel.read(buffer, offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        if (offset < size) {
            throw new IOException("Unable to read entire ROM (read " + offset + " of " + size + " bytes)");
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

    /**
     * Sums the ROM the way the Mega Drive header checksum is defined.
     *
     * <p>Positional reads, for the same reason as {@link #readString}: this
     * walks the whole file, and a concurrent reader moving the shared channel
     * position would make it skip or repeat a block.
     */
    public int calculateChecksum() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(CHECKSUM_BUFFER_SIZE);
        long position = 512; // Skip the first 512 bytes
        int count = 0;

        int read;
        while ((read = fileChannel.read(buffer, position)) != -1) {
            position += read;
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
        if (offset < 0 || offset >= fileSize) {
            throw new IOException("ROM read out of bounds: offset=" + offset + " size=" + fileSize);
        }
        synchronized (this) {
            buffer1.clear();
            fileChannel.position(offset);
            fileChannel.read(buffer1);
            buffer1.flip();
            return buffer1.get();
        }
    }

    public byte[] readBytes(long offset, int count) throws IOException {
        if (offset < 0 || offset + count > fileSize) {
            throw new IOException("ROM read out of bounds: offset=0x" + Long.toHexString(offset) + " + " + count + " > size=" + fileSize);
        }
        synchronized (this) {
            ByteBuffer buffer = ByteBuffer.allocate(count);
            fileChannel.position(offset);
            int bytesRead = fileChannel.read(buffer);
            if (bytesRead < count) {
                throw new IOException("ROM short read at offset 0x" + Long.toHexString(offset) + ": expected " + count + " bytes, got " + bytesRead);
            }
            return Arrays.copyOf(buffer.array(), bytesRead);
        }
    }

    public int read16BitAddr(long offset) throws IOException {
        if (offset < 0 || offset + 2 > fileSize) {
            throw new IOException("ROM read out of bounds: offset=0x" + Long.toHexString(offset) + " + 2 > size=" + fileSize);
        }
        synchronized (this) {
            buffer2.clear();
            fileChannel.position(offset);
            fileChannel.read(buffer2);

            buffer2.flip();
            return (Byte.toUnsignedInt(buffer2.get()) << 8) | Byte.toUnsignedInt(buffer2.get());
        }
    }

    public int read32BitAddr(long offset) throws IOException {
        if (offset < 0 || offset + 4 > fileSize) {
            throw new IOException("ROM read out of bounds: offset=0x" + Long.toHexString(offset) + " + 4 > size=" + fileSize);
        }
        synchronized (this) {
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
    }

    public synchronized void write16BitAddr(int addr, long offset) throws IOException {
        byteReader = null;
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.put((byte) ((addr >> 8) & 0xFF));
        buffer.put((byte) (addr & 0xFF));
        buffer.flip();
        fileChannel.position(offset);
        fileChannel.write(buffer);
    }

    public synchronized void write32BitAddr(int addr, long offset) throws IOException {
        byteReader = null;
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.put((byte) ((addr >> 24) & 0xFF));
        buffer.put((byte) ((addr >> 16) & 0xFF));
        buffer.put((byte) ((addr >> 8) & 0xFF));
        buffer.put((byte) (addr & 0xFF));
        buffer.flip();
        fileChannel.position(offset);
        fileChannel.write(buffer);
    }

    /**
     * Reads a fixed-length header string.
     *
     * <p>Uses positional reads. Every other reader here brackets
     * {@code position()} plus {@code read()} in {@code synchronized (this)},
     * because a {@link FileChannel}'s position is shared mutable state; this
     * method did the same pair without the lock. The engine reads the ROM from
     * more than one thread -- {@code level-load-preparer} runs concurrently
     * with the caller -- so a read here could be repositioned between the two
     * calls and return the window starting a few bytes late.
     *
     * <p>That is not a cosmetic misread. The only callers are
     * {@link #readDomesticName()} and {@link #readInternationalName()}, which
     * feed ROM detection: a header returned as "C &amp; KNUCKLES ... SONI"
     * instead of "SONIC &amp; KNUCKLES" matches no detector, so
     * GameModuleRegistry falls back to its Sonic 2 default and the wrong game
     * module then reads the loaded ROM at the wrong offsets. Positional reads
     * neither use nor mutate the channel position, so this is now immune
     * whatever else holds the lock.
     */
    private String readString(long offset, int length) throws IOException {
        if (offset < 0 || offset + length > fileSize) {
            throw new IOException("ROM read out of bounds: offset=0x" + Long.toHexString(offset) + " + " + length + " > size=" + fileSize);
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = offset;
        while (buffer.hasRemaining()) {
            int read = fileChannel.read(buffer, position);
            if (read < 0) {
                break;
            }
            position += read;
        }
        return new String(buffer.array()).trim();
    }
}
