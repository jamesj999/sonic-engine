package uk.co.jamesj999.sonic.tools;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import static org.junit.Assert.*;

/**
 * Tests for Enigma decompression.
 * Test cases based on the Sega Retro documentation example.
 */
public class EnigmaReaderTest {

    @Test
    public void testDecompressSegaRetroExample() throws IOException {
        byte[] compressed = new byte[] {
            0x07,       // packet length = 7 bits
            0x0C,       // flag mask = 0x0C (palette line bits CC)
            0x00, 0x00, // incrementing value = 0x0000
            0x00, 0x10, // common value = 0x0010
            0x05,       // bitstream start
            0x3D, 0x11, (byte) 0x8F, (byte) 0xE0
        };

        byte[] decompressed = decompress(compressed, 0);

        byte[] expected = new byte[] {
            0x00, 0x00,  // incrementing: 0x0000
            0x00, 0x01,  // incrementing: 0x0001
            0x00, 0x10,  // common: 0x0010
            0x00, 0x10,  // common: 0x0010
            0x00, 0x10,  // common: 0x0010
            0x00, 0x10,  // common: 0x0010
            0x40, 0x18,  // inline with palette: 0x4018
            0x40, 0x17,  // decrement: 0x4017
            0x40, 0x16,  // decrement: 0x4016
            0x40, 0x15,  // decrement: 0x4015
            0x40, 0x14,  // decrement: 0x4014
            0x40, 0x13,  // decrement: 0x4013
            0x40, 0x12,  // decrement: 0x4012
            0x40, 0x11,  // decrement: 0x4011
            0x40, 0x10   // decrement: 0x4010
        };

        assertArrayEquals("Decompressed data should match expected", expected, decompressed);
    }

    @Test
    public void testDecompressWithStartingArtTile() throws IOException {
        byte[] compressed = new byte[] {
            0x07,       // packet length = 7 bits
            0x0C,       // flag mask = 0x0C (palette line bits CC)
            0x00, 0x00, // incrementing value = 0x0000
            0x00, 0x10, // common value = 0x0010
            0x05,       // bitstream start
            0x3D, 0x11, (byte) 0x8F, (byte) 0xE0
        };

        byte[] decompressed = decompress(compressed, 0x1000);

        assertEquals("First word should have starting tile added",
                0x10, (decompressed[0] & 0xFF));
        assertEquals("First word low byte",
                0x00, (decompressed[1] & 0xFF));
    }

    @Test
    public void testDecompressSegaRetroExampleWithArtTile() throws IOException {
        byte[] compressed = new byte[] {
            0x07,       // packet length = 7 bits
            0x0C,       // flag mask = 0x0C (palette line bits CC)
            0x00, 0x00, // incrementing value = 0x0000
            0x00, 0x10, // common value = 0x0010
            0x05,       // bitstream start
            0x3D, 0x11, (byte) 0x8F, (byte) 0xE0
        };

        byte[] decompressed = decompress(compressed, 0x1000);

        assertTrue("Should produce output", decompressed.length > 0);
        assertEquals("Output should be word-aligned", 0, decompressed.length % 2);

        int firstWord = ((decompressed[0] & 0xFF) << 8) | (decompressed[1] & 0xFF);
        assertEquals("First word should have starting tile added", 0x1000, firstWord);
    }

    @Test
    public void testOutputIsWordAligned() throws IOException {
        byte[] compressed = new byte[] {
            0x07,       // packet length = 7 bits
            0x0C,       // flag mask = 0x0C (palette line bits CC)
            0x00, 0x00, // incrementing value = 0x0000
            0x00, 0x10, // common value = 0x0010
            0x05,       // bitstream start
            0x3D, 0x11, (byte) 0x8F, (byte) 0xE0
        };

        byte[] decompressed = decompress(compressed, 0);

        assertEquals("Output should be word-aligned", 0, decompressed.length % 2);
    }

    private byte[] decompress(byte[] compressed, int startingArtTile) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            return EnigmaReader.decompress(channel, startingArtTile);
        }
    }
}
