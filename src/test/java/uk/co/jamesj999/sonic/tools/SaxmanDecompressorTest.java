package uk.co.jamesj999.sonic.tools;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Saxman decompression tests built from the Sega Retro description:
 * - Size field precedes the bitstream (little-endian in many drivers).
 * - Description byte consumed LSB-first; bit=1 -> literal, bit=0 -> back-reference.
 * - Back-reference uses a 12-bit offset rebased by +0x12 and a length of (low nibble + 3).
 */
public class SaxmanDecompressorTest {

    private final SaxmanDecompressor decompressor = new SaxmanDecompressor();

    @Test
    public void decompressesAllLiterals() {
        // Header size = 4 (little-endian): 1 description + 3 literals
        byte[] compressed = new byte[] {
                0x04, 0x00,
                (byte) 0xFF, // first 3 bits = literals
                'A', 'B', 'C'
        };
        byte[] out = decompressor.decompress(compressed);
        assertArrayEquals(new byte[] {'A', 'B', 'C'}, out);
    }

    @Test
    public void decompressesLiteralThenBackref() {
        // Build "HELLOHELLO":
        // 5 literals "HELLO", then one backref of length 5 pointing to the previous 5 bytes.
        // Description bits (LSB first): 1 1 1 1 1 0 -> 0x1F
        // Backref encoding:
        //   desired source = dest - 5 when dest=5 => base = (dest + 0xFFB) & 0xFFF = 0x000
        //   stored offset = (base - 0x12) & 0xFFF = 0xFEE -> l=0xEE, h high-nibble=0x0F
        //   length = 5 => low nibble = (5 - 3) = 2, so h = 0xF2
        byte[] compressed = new byte[] {
                0x08, 0x00,       // size = 8 bytes (1 desc + 5 literals + 2 backref bytes)
                0x1F,             // description
                'H', 'E', 'L', 'L', 'O',
                (byte) 0xEE, (byte) 0xF2
        };
        byte[] out = decompressor.decompress(compressed);
        assertArrayEquals("HELLOHELLO".getBytes(), out);
    }

    @Test
    public void clampsDeclaredSizeToBuffer() {
        // Declared size is too large; decompressor should clamp to available data without error.
        byte[] compressed = new byte[] {
                (byte) 0xFF, 0x7F, // bogus large size (0x7FFF)
                (byte) 0xFF, 'X'    // description and one literal only available
        };
        byte[] out = decompressor.decompress(compressed);
        assertEquals(1, out.length);
        assertEquals('X', out[0]);
    }
}
