import org.junit.Test;
import uk.co.jamesj999.sonic.data.RomByteReader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class RomByteReaderTest {

    @Test
    public void readsUnsignedBigEndianWords() {
        byte[] data = {0x01, (byte) 0xFF, 0x10, 0x20, 0x30};
        RomByteReader reader = new RomByteReader(data);

        assertEquals(0x01, reader.readU8(0));
        assertEquals(0xFF, reader.readU8(1));
        assertEquals(0x01FF, reader.readU16BE(0));
        assertEquals(0xFF10, reader.readU16BE(1));
        assertEquals(-240, reader.readS16BE(1));
    }

    @Test
    public void slicesDefensively() {
        byte[] data = {0x00, 0x11, 0x22, 0x33};
        RomByteReader reader = new RomByteReader(data);
        assertArrayEquals(new byte[]{0x11, 0x22}, reader.slice(1, 2));
    }

    @Test
    public void readPointer16AddsBase() {
        byte[] data = {
                0x00, 0x04, // pointer 0 -> base+4
                0x00, 0x08, // pointer 1 -> base+8
                0x55, 0x66, 0x77, 0x00
        };
        RomByteReader reader = new RomByteReader(data);
        int base = 0;
        assertEquals(4, reader.readPointer16(base, 0));
        assertEquals(8, reader.readPointer16(base, 1));
    }
}
