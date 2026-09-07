package com.openggf.util;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.level.Pattern;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.data.compression.NemesisReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Converts raw tile bytes into {@link Pattern} arrays.
 *
 * <p>Centralises the 7-line loop that was previously copy-pasted in every
 * game-specific art loader (S1, S2, S3K).
 *
 * <p>Convenience methods handle ROM channel setup and decompression for the
 * three compression formats used across the Sonic series: Nemesis, Kosinski,
 * and Kosinski Moduled. Each seeks the shared {@link java.nio.channels.FileChannel}
 * and then streams from it, so each holds the {@code Rom} monitor for the whole
 * seek-and-stream, matching the lock every reader inside {@link Rom} takes.
 *
 * <p>These used to require the caller to synchronize instead. The Sonic 1 and
 * Sonic 2 art loaders did; every Sonic 3 &amp; Knuckles caller did not, and the
 * background {@code level-load-preparer} thread repositions the same channel
 * during a level load. The observed result was a Nemesis stream that ended
 * early ("Unexpected end of input data") because the decoder's sequential reads
 * had been seeked out from under it. Locking here makes every caller safe by
 * construction; the monitor is reentrant, so the callers that already
 * synchronize are unaffected.
 */
public final class PatternDecompressor {

    private static final Logger LOGGER = Logger.getLogger(PatternDecompressor.class.getName());

    private PatternDecompressor() {}

    /**
     * Converts raw tile bytes into an array of {@link Pattern} objects.
     *
     * @param data raw tile data (32 bytes per pattern)
     * @return pattern array, or empty array if data is null/empty
     */
    public static Pattern[] fromBytes(byte[] data) {
        if (data == null || data.length == 0) {
            return new Pattern[0];
        }
        int count = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        return buildPatterns(data, count);
    }

    /**
     * Converts raw tile bytes into an array of {@link Pattern} objects,
     * capped at {@code maxCount} patterns.
     *
     * @param data     raw tile data (32 bytes per pattern)
     * @param maxCount maximum number of patterns to produce
     * @return pattern array, or empty array if data is null/empty
     */
    public static Pattern[] fromBytes(byte[] data, int maxCount) {
        if (data == null || data.length == 0) {
            return new Pattern[0];
        }
        int available = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        int count = Math.min(available, maxCount);
        return buildPatterns(data, count);
    }

    /**
     * Decompresses Nemesis-compressed art from ROM and converts to patterns.
     *
     * @param rom     the ROM to read from
     * @param address ROM address of the Nemesis-compressed data
     * @return pattern array
     * @throws IOException on decompression failure
     */
    public static Pattern[] nemesis(Rom rom, int address) throws IOException {
        synchronized (rom) {
            var channel = rom.getFileChannel();
            channel.position(address);
            byte[] data = NemesisReader.decompress(channel);
            return fromBytes(data);
        }
    }

    /**
     * Decompresses Nemesis-compressed art from ROM and converts to patterns.
     * Uses {@code rom.readBytes()} with a default read size of 8192 bytes.
     * Returns an empty array on failure instead of throwing.
     *
     * @param rom     the ROM to read from
     * @param address ROM address of the Nemesis-compressed data
     * @param name    descriptive name for logging on failure
     * @return pattern array, or empty array on decompression failure
     */
    public static Pattern[] nemesis(Rom rom, int address, String name) {
        return nemesis(rom, address, 8192, name);
    }

    /**
     * Decompresses Nemesis-compressed art from ROM and converts to patterns.
     * Uses {@code rom.readBytes()} with the specified read size.
     * Returns an empty array on failure instead of throwing.
     *
     * @param rom      the ROM to read from
     * @param address  ROM address of the Nemesis-compressed data
     * @param readSize number of bytes to read from ROM (must cover compressed data)
     * @param name     descriptive name for logging on failure
     * @return pattern array, or empty array on decompression failure
     */
    public static Pattern[] nemesis(Rom rom, int address, int readSize, String name) {
        try {
            byte[] compressed = rom.readBytes(address, readSize);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = NemesisReader.decompress(channel);
                return fromBytes(decompressed);
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load " + name + " patterns: " + e.getMessage());
            return new Pattern[0];
        }
    }

    /**
     * Decompresses Kosinski-compressed art from ROM and converts to patterns.
     *
     * @param rom     the ROM to read from
     * @param address ROM address of the Kosinski-compressed data
     * @return pattern array
     * @throws IOException on decompression failure
     */
    public static Pattern[] kosinski(Rom rom, int address) throws IOException {
        synchronized (rom) {
            var channel = rom.getFileChannel();
            channel.position(address);
            byte[] data = KosinskiReader.decompress(channel);
            return fromBytes(data);
        }
    }

    /**
     * Decompresses Kosinski Moduled art from ROM and converts to patterns.
     *
     * @param rom     the ROM to read from
     * @param address ROM address of the Kosinski Moduled data
     * @return pattern array
     * @throws IOException on decompression failure
     */
    public static Pattern[] kosinskiModuled(Rom rom, int address) throws IOException {
        synchronized (rom) {
            var channel = rom.getFileChannel();
            channel.position(address);
            byte[] data = KosinskiReader.decompressModuled(channel);
            return fromBytes(data);
        }
    }

    /**
     * Reads uncompressed art tiles directly from ROM via a {@link RomByteReader}.
     *
     * @param reader ROM byte reader
     * @param address ROM address of the uncompressed art data
     * @param size    total size in bytes (must be a multiple of {@link Pattern#PATTERN_SIZE_IN_ROM})
     * @return pattern array, or empty array if size &lt;= 0
     * @throws IOException if the data size is not a multiple of 32
     */
    public static Pattern[] uncompressed(RomByteReader reader, int address, int size) throws IOException {
        if (size <= 0) return new Pattern[0];
        if (size % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Uncompressed art size is not a multiple of " + Pattern.PATTERN_SIZE_IN_ROM);
        }
        int count = size / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[count];
        for (int i = 0; i < count; i++) {
            patterns[i] = new Pattern();
            patterns[i].fromSegaFormat(reader.slice(address + i * Pattern.PATTERN_SIZE_IN_ROM, Pattern.PATTERN_SIZE_IN_ROM));
        }
        return patterns;
    }

    private static Pattern[] buildPatterns(byte[] data, int count) {
        Pattern[] patterns = new Pattern[count];
        for (int i = 0; i < count; i++) {
            patterns[i] = new Pattern();
            byte[] tile = Arrays.copyOfRange(
                    data,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(tile);
        }
        return patterns;
    }
}
