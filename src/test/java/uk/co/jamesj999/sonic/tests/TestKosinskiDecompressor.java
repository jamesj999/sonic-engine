package uk.co.jamesj999.sonic.tests;


import org.junit.Test;
import uk.co.jamesj999.sonic.tools.KosinskiDecompressor;

import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;


public class TestKosinskiDecompressor {
    @Test
    public void testDecompression() throws IOException {
        // Input compressed data
        byte[] compressedData = new byte[]{
                (byte) 0xFF, (byte) 0x3F, (byte) 0x54, (byte) 0x3b, (byte) 0xc4, (byte) 0x44, (byte) 0x54, (byte) 0x33,
                (byte) 0x33, (byte) 0x5b, (byte) 0x2d, (byte) 0x5c, (byte) 0x44, (byte) 0x5c, (byte) 0xc4, (byte) 0xc5,
                (byte) 0xFc, (byte) 0x15, (byte) 0xfe, (byte) 0xc3, (byte) 0x44, (byte) 0x78, (byte) 0x88, (byte) 0x98,
                (byte) 0x44, (byte) 0x30, (byte) 0xff, (byte) 0xff, (byte) 0x00, (byte) 0xf8, (byte) 0x00
        };

        // Expected decompressed output
        byte[] expectedOutput = new byte[]{
                (byte) 0x54, (byte) 0x3B, (byte) 0xC4, (byte) 0x44, (byte) 0x54, (byte) 0x33, (byte) 0x33, (byte) 0x5B,
                (byte) 0x2D, (byte) 0x5C, (byte) 0x44, (byte) 0x5C, (byte) 0xC4, (byte) 0xC5, (byte) 0xC3, (byte) 0x44,
                (byte) 0x78, (byte) 0x88, (byte) 0x98, (byte) 0x44, (byte) 0x30
        };

        // Instantiate the decompressor
        KosinskiDecompressor decompressor = new KosinskiDecompressor(compressedData);

        // Perform decompression
        byte[] decompressedData = decompressor.decompress();

        // Assert the decompressed data matches the expected output
        assertArrayEquals(expectedOutput, decompressedData);
    }
}
