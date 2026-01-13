package uk.co.jamesj999.sonic.tools.disasm;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.tools.EnigmaReader;
import uk.co.jamesj999.sonic.tools.KosinskiReader;
import uk.co.jamesj999.sonic.tools.NemesisReader;
import uk.co.jamesj999.sonic.tools.SaxmanDecompressor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Tool for testing decompression of data at various ROM offsets.
 * Can test Nemesis, Kosinski, Enigma, and Saxman compression.
 */
public class CompressionTestTool {

    private static final int MAX_READ_SIZE = 0x10000;

    private final Rom rom;
    private byte[] romData;

    public CompressionTestTool(Rom rom) {
        this.rom = rom;
    }

    public CompressionTestTool(String romPath) throws IOException {
        this.rom = new Rom();
        if (!rom.open(romPath)) {
            throw new IOException("Failed to open ROM: " + romPath);
        }
    }

    private byte[] getRomData() throws IOException {
        if (romData == null) {
            romData = rom.readAllBytes();
        }
        return romData;
    }

    /**
     * Test decompression at a specific offset with the given compression type.
     */
    public CompressionTestResult testDecompression(long offset, CompressionType type) throws IOException {
        byte[] data = getRomData();

        if (offset < 0 || offset >= data.length) {
            return CompressionTestResult.failure(type, offset, "Offset out of bounds");
        }

        int maxLen = (int) Math.min(MAX_READ_SIZE, data.length - offset);
        byte[] chunk = Arrays.copyOfRange(data, (int) offset, (int) offset + maxLen);

        try {
            switch (type) {
                case NEMESIS:
                    return testNemesis(offset, chunk);
                case KOSINSKI:
                case KOSINSKI_MODULED:
                    return testKosinski(offset, chunk, type);
                case ENIGMA:
                    return testEnigma(offset, chunk);
                case SAXMAN:
                    return testSaxman(offset, chunk);
                case UNCOMPRESSED:
                    return CompressionTestResult.success(type, offset, chunk.length, chunk.length, chunk);
                default:
                    return CompressionTestResult.failure(type, offset, "Unknown compression type");
            }
        } catch (Exception e) {
            return CompressionTestResult.failure(type, offset, e.getMessage());
        }
    }

    /**
     * Test if decompressed data matches reference data (from disassembly file).
     */
    public CompressionTestResult testDecompressionWithReference(long offset, CompressionType type,
                                                                  byte[] referenceData) throws IOException {
        CompressionTestResult result = testDecompression(offset, type);

        if (!result.isSuccess()) {
            return result;
        }

        byte[] decompressed = result.getDecompressedData();
        if (!Arrays.equals(decompressed, referenceData)) {
            return CompressionTestResult.failure(type, offset,
                    String.format("Data mismatch: expected %d bytes, got %d bytes",
                            referenceData.length, decompressed.length));
        }

        return result;
    }

    /**
     * Search for a matching decompression starting from a given offset range.
     * Tries to find where in the ROM the compressed version of referenceData exists.
     */
    public CompressionTestResult searchForMatch(CompressionType type, byte[] referenceData,
                                                  long startOffset, long endOffset, int step) throws IOException {
        byte[] data = getRomData();
        long maxOffset = Math.min(endOffset, data.length);

        for (long offset = startOffset; offset < maxOffset; offset += step) {
            try {
                CompressionTestResult result = testDecompression(offset, type);
                if (result.isSuccess()) {
                    if (Arrays.equals(result.getDecompressedData(), referenceData)) {
                        return result;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return CompressionTestResult.failure(type, startOffset, "No matching offset found");
    }

    /**
     * Search the entire ROM for a matching compressed version of the reference data.
     */
    public CompressionTestResult searchEntireRom(CompressionType type, byte[] referenceData) throws IOException {
        return searchForMatch(type, referenceData, 0, getRomData().length, 1);
    }

    private CompressionTestResult testNemesis(long offset, byte[] chunk) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(chunk);
        ReadableByteChannel channel = Channels.newChannel(bais);

        byte[] decompressed = NemesisReader.decompress(channel);
        int compressedSize = chunk.length - bais.available();

        return CompressionTestResult.success(CompressionType.NEMESIS, offset,
                compressedSize, decompressed.length, decompressed);
    }

    private CompressionTestResult testKosinski(long offset, byte[] chunk, CompressionType type) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(chunk);
        ReadableByteChannel channel = Channels.newChannel(bais);

        byte[] decompressed = KosinskiReader.decompress(channel);
        int compressedSize = chunk.length - bais.available();

        return CompressionTestResult.success(type, offset,
                compressedSize, decompressed.length, decompressed);
    }

    private CompressionTestResult testEnigma(long offset, byte[] chunk) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(chunk);
        ReadableByteChannel channel = Channels.newChannel(bais);

        byte[] decompressed = EnigmaReader.decompress(channel);
        int compressedSize = chunk.length - bais.available();

        return CompressionTestResult.success(CompressionType.ENIGMA, offset,
                compressedSize, decompressed.length, decompressed);
    }

    private CompressionTestResult testSaxman(long offset, byte[] chunk) {
        SaxmanDecompressor decompressor = new SaxmanDecompressor();
        byte[] decompressed = decompressor.decompress(chunk);

        int sizeFromHeader = (chunk[0] & 0xFF) | ((chunk[1] & 0xFF) << 8);
        int compressedSize = Math.min(sizeFromHeader + 2, chunk.length);

        return CompressionTestResult.success(CompressionType.SAXMAN, offset,
                compressedSize, decompressed.length, decompressed);
    }

    /**
     * Try all compression types at a given offset and return the first successful one.
     */
    public CompressionTestResult autoDetect(long offset) throws IOException {
        CompressionType[] types = {
                CompressionType.NEMESIS,
                CompressionType.KOSINSKI,
                CompressionType.ENIGMA,
                CompressionType.SAXMAN
        };

        for (CompressionType type : types) {
            try {
                CompressionTestResult result = testDecompression(offset, type);
                if (result.isSuccess() && result.getDecompressedSize() > 0) {
                    return result;
                }
            } catch (Exception ignored) {
            }
        }

        return CompressionTestResult.failure(CompressionType.UNKNOWN, offset,
                "No valid compression detected");
    }

    /**
     * Read raw bytes from ROM at a specific offset.
     *
     * @param offset Start offset
     * @param length Number of bytes to read
     * @return Byte array, or null if offset is out of bounds
     */
    public byte[] readRomBytes(long offset, int length) throws IOException {
        byte[] data = getRomData();
        if (offset < 0 || offset + length > data.length) {
            return null;
        }
        return Arrays.copyOfRange(data, (int) offset, (int) offset + length);
    }

    public void close() {
        if (rom != null) {
            rom.close();
        }
    }
}
