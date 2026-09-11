package com.openggf.game.rewind.schema;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestRewindStateBufferReset {

    @Test
    void resetClearsSizeButKeepsBackingArrayUsable() {
        RewindStateBuffer buffer = new RewindStateBuffer();
        buffer.writeInt(0x12345678);
        buffer.writeLong(0x0123456789ABCDEFL);
        assertEquals(12, buffer.toByteArray().length);

        buffer.reset();
        assertEquals(0, buffer.toByteArray().length);

        buffer.writeInt(0x42);
        byte[] after = buffer.toByteArray();
        assertEquals(4, after.length);
        assertArrayEquals(new byte[] {0x42, 0, 0, 0}, after);
    }

    @Test
    void resetIsIdempotent() {
        RewindStateBuffer buffer = new RewindStateBuffer();
        buffer.reset();
        buffer.reset();
        assertEquals(0, buffer.toByteArray().length);
    }
}
