package com.openggf.tools.disasm;

import com.openggf.data.Rom;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.data.compression.NemesisReader;
import com.openggf.data.compression.SaxmanDecompressor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
                    return testKosinski(offset, chunk);
                case KOSINSKI_MODULED:
                    return testKosinskiModuled(offset, chunk);
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
     * Search the ROM for an exact raw byte match of the reference data.
     * This is useful for compressed assets where the disassembly file is the
     * exact compressed stream stored in the ROM.
     *
     * @return the ROM offset of the first match, or -1 if not found.
     */
    public long findRawMatch(byte[] referenceData, long startOffset, long endOffset) throws IOException {
        if (referenceData == null || referenceData.length == 0) {
            return -1;
        }

        byte[] data = getRomData();
        int refLen = referenceData.length;
        int start = (int) Math.max(0, startOffset);
        int maxEnd = endOffset > 0 ? (int) Math.min(endOffset, data.length) : data.length;
        int maxStart = maxEnd - refLen;
        if (maxStart < start) {
            return -1;
        }

        byte first = referenceData[0];
        byte last = referenceData[refLen - 1];

        for (int i = start; i <= maxStart; i++) {
            if (data[i] != first) {
                continue;
            }
            if (refLen > 1 && data[i + refLen - 1] != last) {
                continue;
            }
            if (matchesAt(data, referenceData, i)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Search the ROM for ALL exact byte matches of the pattern.
     *
     * @param pattern     Byte pattern to search for
     * @param startOffset Start of search range (inclusive)
     * @param endOffset   End of search range (exclusive), or Long.MAX_VALUE for entire ROM
     * @return list of ROM offsets where the pattern occurs
     */
    public List<Long> findAllRawMatches(byte[] pattern, long startOffset, long endOffset) throws IOException {
        List<Long> matches = new ArrayList<>();
        if (pattern == null || pattern.length == 0) {
            return matches;
        }

        long searchFrom = startOffset;
        while (true) {
            long offset = findRawMatch(pattern, searchFrom, endOffset);
            if (offset < 0) {
                break;
            }
            matches.add(offset);
            searchFrom = offset + 1;
        }
        return matches;
    }

    private boolean matchesAt(byte[] data, byte[] referenceData, int offset) {
        int refLen = referenceData.length;
        for (int j = 1; j < refLen - 1; j++) {
            if (data[offset + j] != referenceData[j]) {
                return false;
            }
        }
        return true;
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

    private CompressionTestResult testKosinski(long offset, byte[] chunk) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(chunk);
        ReadableByteChannel channel = Channels.newChannel(bais);

        byte[] decompressed = KosinskiReader.decompress(channel);
        int compressedSize = chunk.length - bais.available();

        return CompressionTestResult.success(CompressionType.KOSINSKI, offset,
                compressedSize, decompressed.length, decompressed);
    }

    private CompressionTestResult testKosinskiModuled(long offset, byte[] chunk) throws IOException {
        byte[] decompressed = KosinskiReader.decompressModuled(chunk, 0);

        return CompressionTestResult.success(CompressionType.KOSINSKI_MODULED, offset,
                -1, decompressed.length, decompressed);
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
                CompressionType.KOSINSKI_MODULED,
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
