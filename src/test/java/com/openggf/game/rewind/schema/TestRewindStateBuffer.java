package com.openggf.game.rewind.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindStateBuffer {
    @Test
    void writesAndReadsLittleEndianPrimitives() {
        RewindStateBuffer buffer = new RewindStateBuffer();
        buffer.writeByte(0x12);
        buffer.writeBoolean(true);
        buffer.writeShort(0x3456);
        buffer.writeInt(0x789ABCDE);
        buffer.writeLong(0x0102030405060708L);
        buffer.writeFloat(Float.intBitsToFloat(0x3F800000));
        buffer.writeDouble(Double.longBitsToDouble(0x3FF0000000000000L));

        RewindStateBuffer.Reader reader = buffer.reader();
        assertEquals(0x12, reader.readByte() & 0xFF);
        assertTrue(reader.readBoolean());
        assertEquals(0x3456, reader.readShort() & 0xFFFF);
        assertEquals(0x789ABCDE, reader.readInt());
        assertEquals(0x0102030405060708L, reader.readLong());
        assertEquals(1.0f, reader.readFloat());
        assertEquals(1.0d, reader.readDouble());
    }

    @Test
    void writesAndReadsByteArrays() {
        RewindStateBuffer buffer = new RewindStateBuffer();
        buffer.writeBytes(new byte[]{1, 2, 3, 4});

        assertArrayEquals(new byte[]{1, 2, 3, 4}, buffer.reader().readBytes(4));
    }

    @Test
    void toByteArrayReturnsDefensiveCopy() {
        RewindStateBuffer buffer = new RewindStateBuffer();
        buffer.writeInt(0x01020304);

        byte[] first = buffer.toByteArray();
        first[0] = 0x7F;

        assertArrayEquals(new byte[]{4, 3, 2, 1}, buffer.toByteArray());
    }

    @Test
    void staticReaderDefensivelyCopiesInput() {
        byte[] source = new byte[]{10, 20};
        RewindStateBuffer.Reader reader = RewindStateBuffer.reader(source);

        source[0] = 99;

        assertEquals(10, reader.readByte());
        assertEquals(20, reader.readByte());
    }

    @Test
    void sharedReaderDoesNotCopyInput() {
        // Pins sharedReader's NO-copy ownership contract so it cannot be
        // silently merged with the defensively copying reader(byte[]) factory.
        byte[] source = new byte[]{10, 20};
        RewindStateBuffer.Reader reader = RewindStateBuffer.sharedReader(source);

        source[0] = 99;

        assertEquals(99, reader.readByte());
        assertEquals(20, reader.readByte());
    }

    @Test
    void readerFailsClearlyWhenReadingPastEnd() {
        RewindStateBuffer.Reader reader = RewindStateBuffer.reader(new byte[]{1, 2});

        IllegalStateException error = assertThrows(IllegalStateException.class, reader::readInt);

        assertTrue(error.getMessage().contains("past end"));
        assertTrue(error.getMessage().contains("requested 4 bytes"));
    }
}
