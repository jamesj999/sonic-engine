package com.openggf.data;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Read-only byte/word access helper for Sega ROM data.
 * All multi-byte reads are big-endian and unsigned unless stated otherwise.
 */
@com.openggf.game.ModApi
public class RomByteReader {

    private final byte[] data;

    public RomByteReader(byte[] data) {
        this.data = Arrays.copyOf(data, data.length);
    }

    private RomByteReader(byte[] data, boolean owned) {
        this.data = data;
    }

    /**
     * Returns the immutable reader shared for the current open ROM. Closing,
     * reopening, or writing through Rom invalidates its cached reader; already
     * issued readers remain immutable snapshots of their original bytes.
     */
    public static RomByteReader fromRom(Rom rom) throws IOException {
        return rom.byteReader();
    }

    /** Builds a reader over the given bytes (used for logical-ROM windows and tests). */
    public static RomByteReader fromBytes(byte[] data) {
        return new RomByteReader(data);
    }

    /** Builds a reader over a defensively copied byte range. */
    public static RomByteReader fromBytes(byte[] data, int from, int to) {
        Objects.checkFromToIndex(from, to, data.length);
        return new RomByteReader(Arrays.copyOfRange(data, from, to), true);
    }

    public int size() {
        return data.length;
    }

    public int readU8(int addr) {
        boundsCheck(addr, 1);
        return Byte.toUnsignedInt(data[addr]);
    }

    public int readU16BE(int addr) {
        boundsCheck(addr, 2);
        return (Byte.toUnsignedInt(data[addr]) << 8) | Byte.toUnsignedInt(data[addr + 1]);
    }

    public int readS16BE(int addr) {
        boundsCheck(addr, 2);
        return (short) readU16BE(addr);
    }

    public int readU32BE(int addr) {
        boundsCheck(addr, 4);
        return (Byte.toUnsignedInt(data[addr]) << 24)
             | (Byte.toUnsignedInt(data[addr + 1]) << 16)
             | (Byte.toUnsignedInt(data[addr + 2]) << 8)
             | Byte.toUnsignedInt(data[addr + 3]);
    }

    public byte[] slice(int addr, int len) {
        boundsCheck(addr, len);
        return Arrays.copyOfRange(data, addr, addr + len);
    }

    /**
     * Read a 16-bit offset from a word table and return the absolute address.
     * The table stores word offsets relative to {@code baseAddr}.
     */
    public int readPointer16(int baseAddr, int index) {
        int offset = readU16BE(baseAddr + index * 2);
        return baseAddr + offset;
    }

    private void boundsCheck(int addr, int len) {
        if (addr < 0 || addr + len > data.length) {
            throw new IndexOutOfBoundsException(
                    String.format("Read out of bounds: addr=0x%X len=%d size=0x%X", addr, len, data.length));
        }
    }
}
