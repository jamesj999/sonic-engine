package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.tools.NemesisReader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NemesisReaderTest {

    @Test
    public void testRingArtDecompressionLengthAndHash() throws Exception {
        File romFile = RomTestUtils.ensureRomAvailable();
        byte[] result;
        try (FileChannel channel = FileChannel.open(romFile.toPath(), StandardOpenOption.READ)) {
            channel.position(0x7945C);
            result = NemesisReader.decompress(channel);
        }

        assertEquals("Ring art should be 14 patterns", 14 * 0x20, result.length);
        assertEquals("Ring art checksum mismatch", "5e598183b7b54dca50c2e227b610f2b4", md5Hex(result));
    }

    @Test
    public void testInlineRunsNormalMode() throws Exception {
        byte[] payload = buildInlineNemesisStream(false, 1);
        byte[] result = NemesisReader.decompress(Channels.newChannel(new ByteArrayInputStream(payload)));

        assertEquals(0x20, result.length);
        for (int i = 0; i < result.length; i++) {
            int expected = (i < 4) ? 0x11 : 0x00;
            assertEquals("Unexpected byte at " + i, expected, result[i] & 0xFF);
        }
    }

    @Test
    public void testInlineRunsXorMode() throws Exception {
        byte[] payload = buildInlineNemesisStream(true, 1);
        byte[] result = NemesisReader.decompress(Channels.newChannel(new ByteArrayInputStream(payload)));

        assertEquals(0x20, result.length);
        for (int i = 0; i < result.length; i++) {
            assertEquals("Unexpected byte at " + i, 0x11, result[i] & 0xFF);
        }
    }

    private static byte[] buildInlineNemesisStream(boolean xorMode, int patternCount) throws IOException {
        ByteArrayBuilder out = new ByteArrayBuilder();
        int header = (xorMode ? 0x8000 : 0x0000) | (patternCount & 0x7FFF);
        out.write((header >> 8) & 0xFF);
        out.write(header & 0xFF);

        out.write(0x00); // palette index (ignored for inline-only data)
        out.write(0xFF); // end of code table

        BitPacker packer = new BitPacker(out);
        int rows = patternCount * 8;
        for (int i = 0; i < rows; i++) {
            packer.writeBits(0x3F, 6); // inline prefix 111111
            int inline = (i == 0) ? 0x71 : 0x70; // first row pal=1, rest pal=0
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
