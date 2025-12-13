package uk.co.jamesj999.sonic.tests;


import org.junit.Test;
import uk.co.jamesj999.sonic.tools.KosinskiReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Logger;

import static org.junit.Assert.assertArrayEquals;


public class TestKosinskiDecompressor {

    private static final Logger LOG = Logger.getLogger(TestKosinskiDecompressor.class.getName());

    @Test
    public void testDecompression() throws IOException {

        byte[] uncompressedKosinskiData = new byte[] {
                (byte) 0xFF, (byte) 0x5F, (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04,
                (byte) 0x05, (byte) 0x06, (byte) 0x07, (byte) 0x08, (byte) 0x09, (byte) 0x0A, (byte) 0x0B,
                (byte) 0x0C, (byte) 0x00, (byte) 0xF0, (byte) 0x00
        };

        byte[] uncompressedData = new byte[] {
                (byte) 0x00, (byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04,
                (byte) 0x05, (byte) 0x06, (byte) 0x07, (byte) 0x08, (byte) 0x09, (byte) 0x0A, (byte) 0x0B,
                (byte) 0x0C
        };

        byte[] kosinskiInlineCompressedData = new byte[] {
                (byte) 0x51, (byte) 0x00, (byte) 0x25, (byte) 0xFF, (byte) 0x00, (byte) 0xF0, (byte) 0x00
        };

        byte[] kosinskiInlineDecompressedData = new byte[] {
                (byte) 0x25, 0x25, 0x25, 0x25
        };

        byte[] kosinskiTest2CompressedData = new byte[] {
                (byte) 0xFF, (byte) 0x3F, (byte) 0x54, (byte) 0x3B, (byte) 0xC4, (byte) 0x44, (byte) 0x54,
                (byte) 0x33, (byte) 0x33, (byte) 0x5B, (byte) 0x2D, (byte) 0x5C, (byte) 0x44, (byte) 0x5C,
                (byte) 0xC4, (byte) 0xC5, (byte) 0xFC, (byte) 0x15, (byte) 0xFE, (byte) 0xC3, (byte) 0x44,
                (byte) 0x78, (byte) 0x88, (byte) 0x98, (byte) 0x44, (byte) 0x30, (byte) 0xFF, (byte) 0xFF,
                (byte) 0x00, (byte) 0xF8, (byte) 0x00
        };

        byte[] kosinskiTest2DecompressedData = new byte[] {
                (byte) 0x54, (byte) 0x3B, (byte) 0xC4, (byte) 0x44, (byte) 0x54, (byte) 0x33, (byte) 0x33,
                (byte) 0x5B, (byte) 0x2D, (byte) 0x5C, (byte) 0x44, (byte) 0x5C, (byte) 0xC4, (byte) 0xC5,
                (byte) 0xC4, (byte) 0xC5, (byte) 0xC3, (byte) 0x44, (byte) 0x78, (byte) 0x88, (byte) 0x98,
                (byte) 0x44, (byte) 0x30, (byte) 0x30, (byte) 0x30, (byte) 0x30, (byte) 0x30, (byte) 0x30,
                (byte) 0x30, (byte) 0x30, (byte) 0x30, (byte) 0x30
        };

        byte[] kosEHZ16 = Files.readAllBytes(Paths.get("src/test/resources/EHZ-16x16.kos"));
        byte[] rawEHZ16 = Files.readAllBytes(Paths.get("src/test/resources/EHZ-16x16.raw"));

        test(kosEHZ16, rawEHZ16);

       // LOG.info("Uncompressed Kosinski Data");
        test(uncompressedKosinskiData, uncompressedData);

        LOG.info("Inline Compressed Kosinski Data");
        test(kosinskiInlineCompressedData, kosinskiInlineDecompressedData);

        LOG.info("Test from https://segaretro.org/Kosinski_compression");
        test(kosinskiTest2CompressedData, kosinskiTest2DecompressedData);
    }

    private static void test(byte[] input, byte[] expected) throws IOException {
        LOG.info("Input: " + bytesToHexString(input));
        LOG.info("Expected Output " + bytesToHexString(expected));

        LOG.info("Old Reader");
        long start = System.currentTimeMillis();
        byte[] buffer = KosinskiReader.decompress(Channels.newChannel(new ByteArrayInputStream(input)),true);
        long end = System.currentTimeMillis();
        LOG.info("(" + (end-start) + ") Our Output: " + bytesToHexString(buffer));

        assertArrayEquals(expected, buffer);

    }

    public static String bytesToHexString(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            hexString.append(String.format("0x%02X", bytes[i]));
            if (i < bytes.length - 1) {
                hexString.append(", ");
            }
        }
        return hexString.toString();
    }
}
