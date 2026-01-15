package uk.co.jamesj999.sonic.tools;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import static org.junit.Assert.*;

/**
 * Tests for Enigma decompression.
 * Test cases based on the Sega Retro documentation example.
 */
public class EnigmaReaderTest {
    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int currentByte = 0;
        private int bitCount = 0;

        void writeBits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                int bit = (value >> i) & 1;
                currentByte = (currentByte << 1) | bit;
                bitCount++;
                if (bitCount == 8) {
                    out.write(currentByte);
                    currentByte = 0;
                    bitCount = 0;
                }
            }
        }

        byte[] toByteArray() {
            if (bitCount > 0) {
                currentByte <<= (8 - bitCount);
                out.write(currentByte);
                currentByte = 0;
                bitCount = 0;
            }
            return out.toByteArray();
        }
    }

    private static byte[] buildEnigma(byte packetLength, byte flagMask, int incrementingValue,
                                      int commonValue, BitWriter bitWriter) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(packetLength & 0xFF);
        out.write(flagMask & 0xFF);
        out.write((incrementingValue >> 8) & 0xFF);
        out.write(incrementingValue & 0xFF);
        out.write((commonValue >> 8) & 0xFF);
        out.write(commonValue & 0xFF);
        byte[] bits = bitWriter.toByteArray();
        out.write(bits, 0, bits.length);
        return out.toByteArray();
    }

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

    @Test
    public void testIncrementingAndCommonRuns() throws IOException {
        BitWriter bits = new BitWriter();
        // 00: incrementing run, count=3 (count bits=2)
        bits.writeBits(0b0, 1);
        bits.writeBits(0b0, 1);
        bits.writeBits(0b0010, 4);
        // 01: common run, count=2 (count bits=1)
        bits.writeBits(0b0, 1);
        bits.writeBits(0b1, 1);
        bits.writeBits(0b0001, 4);
        // 111: terminator
        bits.writeBits(0b1, 1);
        bits.writeBits(0b11, 2);
        bits.writeBits(0b1111, 4);

        byte[] compressed = buildEnigma((byte) 1, (byte) 0x00, 0x0010, 0x0020, bits);
        byte[] decompressed = decompress(compressed, 0);

        byte[] expected = new byte[] {
            0x00, 0x10,
            0x00, 0x11,
            0x00, 0x12,
            0x00, 0x20,
            0x00, 0x20
        };
        assertArrayEquals("Incrementing/common runs should match expected output", expected, decompressed);
    }

    @Test
    public void testInlineModesAndDeltas() throws IOException {
        BitWriter bits = new BitWriter();
        // 100: inline no delta, count=2, value=0x03
        bits.writeBits(0b1, 1);
        bits.writeBits(0b00, 2);
        bits.writeBits(0b0001, 4);
        bits.writeBits(0b00011, 5);
        // 101: inline increment, count=3, value=0x08
        bits.writeBits(0b1, 1);
        bits.writeBits(0b01, 2);
        bits.writeBits(0b0010, 4);
        bits.writeBits(0b01000, 5);
        // 110: inline decrement, count=3, value=0x05
        bits.writeBits(0b1, 1);
        bits.writeBits(0b10, 2);
        bits.writeBits(0b0010, 4);
        bits.writeBits(0b00101, 5);
        // 111: terminator
        bits.writeBits(0b1, 1);
        bits.writeBits(0b11, 2);
        bits.writeBits(0b1111, 4);

        byte[] compressed = buildEnigma((byte) 5, (byte) 0x00, 0x0000, 0x0000, bits);
        byte[] decompressed = decompress(compressed, 0);

        byte[] expected = new byte[] {
            0x00, 0x03,
            0x00, 0x03,
            0x00, 0x08,
            0x00, 0x09,
            0x00, 0x0A,
            0x00, 0x05,
            0x00, 0x04,
            0x00, 0x03
        };
        assertArrayEquals("Inline modes should follow delta rules", expected, decompressed);
    }

    @Test
    public void testMode111InlineList() throws IOException {
        BitWriter bits = new BitWriter();
        // 111: inline list count=3 (count bits=2)
        bits.writeBits(0b1, 1);
        bits.writeBits(0b11, 2);
        bits.writeBits(0b0010, 4);
        // Inline values (3 bits each): 1, 5, 7
        bits.writeBits(0b001, 3);
        bits.writeBits(0b101, 3);
        bits.writeBits(0b111, 3);
        // Terminator
        bits.writeBits(0b1, 1);
        bits.writeBits(0b11, 2);
        bits.writeBits(0b1111, 4);

        byte[] compressed = buildEnigma((byte) 3, (byte) 0x00, 0x0000, 0x0000, bits);
        byte[] decompressed = decompress(compressed, 0);

        byte[] expected = new byte[] {
            0x00, 0x01,
            0x00, 0x05,
            0x00, 0x07
        };
        assertArrayEquals("Mode 111 inline list should decode correctly", expected, decompressed);
    }

    @Test
    public void testFlagBitOrderAllFlags() throws IOException {
        BitWriter bits = new BitWriter();
        // 100: inline no delta, count=1, flags=10000 (priority only), value=0
        bits.writeBits(0b1, 1);
        bits.writeBits(0b00, 2);
        bits.writeBits(0b0000, 4);
        bits.writeBits(0b10000, 5);
        bits.writeBits(0b0, 1);
        // Terminator
        bits.writeBits(0b1, 1);
        bits.writeBits(0b11, 2);
        bits.writeBits(0b1111, 4);

        byte[] compressed = buildEnigma((byte) 1, (byte) 0x1F, 0x0000, 0x0000, bits);
        byte[] decompressed = decompress(compressed, 0);

        int word = ((decompressed[0] & 0xFF) << 8) | (decompressed[1] & 0xFF);
        assertEquals("Priority flag should map to bit 15", 0x8000, word);
    }

    @Test
    public void testFlagBitOrderNonContiguousMask() throws IOException {
        BitWriter bits = new BitWriter();
        // Mask bits: P, V, H (0x13). Flags bits: 1 0 1 -> P and H set.
        bits.writeBits(0b1, 1);
        bits.writeBits(0b00, 2);
        bits.writeBits(0b0000, 4);
        bits.writeBits(0b101, 3);
        bits.writeBits(0b0, 1);
        // Terminator
        bits.writeBits(0b1, 1);
        bits.writeBits(0b11, 2);
        bits.writeBits(0b1111, 4);

        byte[] compressed = buildEnigma((byte) 1, (byte) 0x13, 0x0000, 0x0000, bits);
        byte[] decompressed = decompress(compressed, 0);

        int word = ((decompressed[0] & 0xFF) << 8) | (decompressed[1] & 0xFF);
        assertEquals("Flag order should be P, V, H", 0x8800, word);
    }

    @Test
    public void testStartingArtTileAddedToInlineValues() throws IOException {
        BitWriter bits = new BitWriter();
        // 100: inline no delta, count=1, value=0x3
        bits.writeBits(0b1, 1);
        bits.writeBits(0b00, 2);
        bits.writeBits(0b0000, 4);
        bits.writeBits(0b011, 3);
        // Terminator
        bits.writeBits(0b1, 1);
        bits.writeBits(0b11, 2);
        bits.writeBits(0b1111, 4);

        byte[] compressed = buildEnigma((byte) 3, (byte) 0x00, 0x0000, 0x0000, bits);
        byte[] decompressed = decompress(compressed, 0x1000);

        int word = ((decompressed[0] & 0xFF) << 8) | (decompressed[1] & 0xFF);
        assertEquals("Starting art tile should be added to inline values", 0x1003, word);
    }

    @Test
    public void testPacketLength11BitsCrossesByteBoundary() throws IOException {
        BitWriter bits = new BitWriter();
        // 100: inline no delta, count=1, value=0x5AA (11 bits)
        bits.writeBits(0b1, 1);
        bits.writeBits(0b00, 2);
        bits.writeBits(0b0000, 4);
        bits.writeBits(0b10110101010, 11);
        // Terminator
        bits.writeBits(0b1, 1);
        bits.writeBits(0b11, 2);
        bits.writeBits(0b1111, 4);

        byte[] compressed = buildEnigma((byte) 11, (byte) 0x00, 0x0000, 0x0000, bits);
        byte[] decompressed = decompress(compressed, 0);

        int word = ((decompressed[0] & 0xFF) << 8) | (decompressed[1] & 0xFF);
        assertEquals("11-bit inline values should decode correctly", 0x05AA, word);
    }

    private byte[] decompress(byte[] compressed, int startingArtTile) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             ReadableByteChannel channel = Channels.newChannel(bais)) {
            return EnigmaReader.decompress(channel, startingArtTile);
        }
    }
}
