package com.openggf.game.rewind.schema;

import java.util.Arrays;
import java.util.Objects;

public final class RewindStateBuffer {
    private static final int DEFAULT_CAPACITY = 64;

    private byte[] data = new byte[DEFAULT_CAPACITY];
    private int size;

    public void writeByte(int value) {
        ensureCapacity(1);
        data[size++] = (byte) value;
    }

    public void writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
    }

    public void writeShort(int value) {
        ensureCapacity(Short.BYTES);
        data[size++] = (byte) value;
        data[size++] = (byte) (value >>> 8);
    }

    public void writeInt(int value) {
        ensureCapacity(Integer.BYTES);
        data[size++] = (byte) value;
        data[size++] = (byte) (value >>> 8);
        data[size++] = (byte) (value >>> 16);
        data[size++] = (byte) (value >>> 24);
    }

    public void writeLong(long value) {
        ensureCapacity(Long.BYTES);
        data[size++] = (byte) value;
        data[size++] = (byte) (value >>> 8);
        data[size++] = (byte) (value >>> 16);
        data[size++] = (byte) (value >>> 24);
        data[size++] = (byte) (value >>> 32);
        data[size++] = (byte) (value >>> 40);
        data[size++] = (byte) (value >>> 48);
        data[size++] = (byte) (value >>> 56);
    }

    public void writeFloat(float value) {
        writeInt(Float.floatToRawIntBits(value));
    }

    public void writeDouble(double value) {
        writeLong(Double.doubleToRawLongBits(value));
    }

    public void writeBytes(byte[] values) {
        Objects.requireNonNull(values, "values");
        ensureCapacity(values.length);
        System.arraycopy(values, 0, data, size, values.length);
        size += values.length;
    }

    public void reset() {
        size = 0;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(data, size);
    }

    public Reader reader() {
        // toByteArray() already yields a fresh array; skip the Reader's
        // defensive copy.
        return new Reader(toByteArray(), true);
    }

    public static Reader reader(byte[] data) {
        return new Reader(data);
    }

    /**
     * Reader over {@code data} with NO defensive copy. Restore-path callers
     * own immutable snapshot bytes and must not mutate {@code data} while the
     * reader is in use.
     */
    public static Reader sharedReader(byte[] data) {
        return new Reader(Objects.requireNonNull(data, "data"), true);
    }

    private void ensureCapacity(int bytesToWrite) {
        int required = size + bytesToWrite;
        if (required <= data.length) {
            return;
        }

        int newCapacity = data.length;
        while (newCapacity < required) {
            newCapacity = Math.multiplyExact(newCapacity, 2);
        }
        data = Arrays.copyOf(data, newCapacity);
    }

    public static final class Reader {
        private final byte[] data;
        private int position;

        private Reader(byte[] data) {
            this.data = Arrays.copyOf(Objects.requireNonNull(data, "data"), data.length);
        }

        private Reader(byte[] data, boolean transferOwnership) {
            this.data = data;
        }

        public byte readByte() {
            requireAvailable(1);
            return data[position++];
        }

        public boolean readBoolean() {
            return readByte() != 0;
        }

        public short readShort() {
            requireAvailable(Short.BYTES);
            int value = (data[position] & 0xFF)
                    | ((data[position + 1] & 0xFF) << 8);
            position += Short.BYTES;
            return (short) value;
        }

        public int readInt() {
            requireAvailable(Integer.BYTES);
            int value = (data[position] & 0xFF)
                    | ((data[position + 1] & 0xFF) << 8)
                    | ((data[position + 2] & 0xFF) << 16)
                    | ((data[position + 3] & 0xFF) << 24);
            position += Integer.BYTES;
            return value;
        }

        public long readLong() {
            requireAvailable(Long.BYTES);
            long value = (long) data[position] & 0xFFL;
            value |= ((long) data[position + 1] & 0xFFL) << 8;
            value |= ((long) data[position + 2] & 0xFFL) << 16;
            value |= ((long) data[position + 3] & 0xFFL) << 24;
            value |= ((long) data[position + 4] & 0xFFL) << 32;
            value |= ((long) data[position + 5] & 0xFFL) << 40;
            value |= ((long) data[position + 6] & 0xFFL) << 48;
            value |= ((long) data[position + 7] & 0xFFL) << 56;
            position += Long.BYTES;
            return value;
        }

        public float readFloat() {
            return Float.intBitsToFloat(readInt());
        }

        public double readDouble() {
            return Double.longBitsToDouble(readLong());
        }

        public byte[] readBytes(int length) {
            if (length < 0) {
                throw new IllegalArgumentException("length must be non-negative: " + length);
            }
            requireAvailable(length);
            byte[] values = Arrays.copyOfRange(data, position, position + length);
            position += length;
            return values;
        }

        public int remaining() {
            return data.length - position;
        }

        private void requireAvailable(int bytes) {
            if (position + bytes <= data.length) {
                return;
            }
            throw new IllegalStateException("Attempted to read past end of rewind state buffer: requested "
                    + bytes + " bytes at offset " + position + ", available " + (data.length - position) + ".");
        }
    }
}
