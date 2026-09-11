package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.data.compression.NemesisReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_2)
public class NemesisReaderTest {

    @Test
    void testRingArtDecompressionLengthAndHash() throws Exception {
        byte[] result;
        FileChannel channel = GameServices.rom().getRom().getFileChannel();
        channel.position(0x7945C);
        result = NemesisReader.decompress(channel);

        assertEquals(14 * 0x20, result.length, "Ring art should be 14 patterns");
        assertEquals("3167aa6aa97faabadff19b28953ad122", md5Hex(result), "Ring art checksum mismatch");
    }

    @Test
    void testInlineRunsNormalMode() throws Exception {
        byte[] payload = buildInlineNemesisStream(false, 1);
        byte[] result = NemesisReader.decompress(Channels.newChannel(new ByteArrayInputStream(payload)));

        assertEquals(0x20, result.length);
        for (int i = 0; i < result.length; i++) {
            int expected = (i < 4) ? 0x11 : 0x00;
            assertEquals(expected, result[i] & 0xFF, "Unexpected byte at " + i);
        }
    }

    @Test
    void testInlineRunsXorMode() throws Exception {
        byte[] payload = buildInlineNemesisStream(true, 1);
        byte[] result = NemesisReader.decompress(Channels.newChannel(new ByteArrayInputStream(payload)));

        assertEquals(0x20, result.length);
        for (int i = 0; i < result.length; i++) {
            assertEquals(0x11, result[i] & 0xFF, "Unexpected byte at " + i);
        }
    }

    private static byte[] buildInlineNemesisStream(boolean xorMode, int patternCount) throws IOException {
        ByteArrayBuilder out = new ByteArrayBuilder();
        int header = (xorMode ? 0x8000 : 0x0000) | (patternCount & 0x7FFF);
        out.write((header >> 8) & 0xFF);
        out.write(header & 0xFF);

        out.write(0xFF);

        BitPacker packer = new BitPacker(out);
        int rows = patternCount * 8;
        for (int i = 0; i < rows; i++) {
            packer.writeBits(0x3F, 6);
            int inline = (i == 0) ? 0x71 : 0x70;
            packer.writeBits(inline, 7);
        }
        packer.flush();

        return out.toByteArray();
    }

    private static String md5Hex(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] digest = md5.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static final class ByteArrayBuilder {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        void write(int value) {
            out.write(value);
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    private static final class BitPacker {
        private final ByteArrayBuilder out;
        private int buffer;
        private int bitCount;

        private BitPacker(ByteArrayBuilder out) {
            this.out = out;
        }

        void writeBits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                int bit = (value >> i) & 1;
                buffer = (buffer << 1) | bit;
                bitCount++;
                if (bitCount == 8) {
                    out.write(buffer & 0xFF);
                    buffer = 0;
                    bitCount = 0;
                }
            }
        }

        void flush() {
            if (bitCount > 0) {
                buffer <<= (8 - bitCount);
                out.write(buffer & 0xFF);
                buffer = 0;
                bitCount = 0;
            }
        }
    }
}


