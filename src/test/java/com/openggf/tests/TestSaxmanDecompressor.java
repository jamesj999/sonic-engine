package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.data.compression.SaxmanDecompressor;
import static org.junit.jupiter.api.Assertions.*;

public class TestSaxmanDecompressor {

    @Test
    public void testZeroFill() {
        // Example from documentation:
        // "The following Saxman/z80-compressed stream: 03 00 00 00 FF produces... 18 zeroes"
        // 03 00: Size (3 bytes) - Wait, documentation says "The first two bytes are the compressed data length... compressed data is 3 bytes"
        // "03 00 00 00 FF" -> 5 bytes.
        // "Size" is size of the *compressed stream following header*?
        // "compressed data is prefixed with a small 2-byte header, listing the size of the compressed data"
        // If data is "00 00 FF", size is 3.

        byte[] compressed = { 0x03, 0x00, 0x00, 0x00, (byte)0xFF };

        SaxmanDecompressor decompressor = new SaxmanDecompressor();
        byte[] output = decompressor.decompress(compressed);

        assertEquals(18, output.length, "Should produce 18 bytes");
        for (byte b : output) {
            assertEquals(0, b, "Should be zero");
        }
    }
}


