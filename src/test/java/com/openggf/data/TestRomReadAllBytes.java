package com.openggf.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRomReadAllBytes {

    private static final int NAME_LEN = 48;
    private static final int DOMESTIC_NAME_OFFSET = 0x100 + 32;
    private static final int INTERNATIONAL_NAME_OFFSET = DOMESTIC_NAME_OFFSET + NAME_LEN;

    @TempDir
    Path tempDir;

    @Test
    void readAllBytes_doesNotMoveSharedChannelPosition() throws Exception {
        Path romPath = tempDir.resolve("tiny.gen");
        byte[] bytes = new byte[] {0x10, 0x20, 0x30, 0x40, 0x50};
        Files.write(romPath, bytes);

        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            rom.getFileChannel().position(2);

            byte[] actual = rom.readAllBytes();

            assertArrayEquals(bytes, actual);
            assertEquals(2, rom.getFileChannel().position(),
                    "whole-ROM buffering must not disturb positional readers using the same channel");
        }
    }

    /**
     * The header-name readers must not touch the shared channel position
     * either.
     *
     * <p>They did: {@code readString} set the position and then read, without
     * the {@code synchronized (this)} every other reader in {@link Rom} holds.
     * The engine reads the ROM from more than one thread, so the background
     * {@code level-load-preparer} could reposition the channel between those
     * two calls and hand back the window starting four bytes late -- observed
     * as "C &amp; KNUCKLES ... SONI" for "SONIC &amp; KNUCKLES". No detector
     * matches a rotated name, so GameModuleRegistry fell back to its Sonic 2
     * default and the Sonic 2 module then read the S3K ROM at Sonic 2 offsets,
     * dereferencing a garbage pointer.
     *
     * <p>Asserting on the position rather than staging a race keeps this
     * deterministic: leaving the position alone is what makes the interleaving
     * impossible, and it is the same invariant
     * {@link #readAllBytes_doesNotMoveSharedChannelPosition()} pins.
     */
    @Test
    void headerNameReadsDoNotMoveSharedChannelPosition() throws Exception {
        Path romPath = tempDir.resolve("named.gen");
        byte[] bytes = new byte[0x200];
        writeAscii(bytes, DOMESTIC_NAME_OFFSET, "SONIC & KNUCKLES");
        writeAscii(bytes, INTERNATIONAL_NAME_OFFSET, "SONIC & KNUCKLES");
        Files.write(romPath, bytes);

        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            rom.getFileChannel().position(7);

            assertEquals("SONIC & KNUCKLES", rom.readDomesticName());
            assertEquals(7, rom.getFileChannel().position(),
                    "reading the domestic name must not disturb a concurrent reader's position");

            assertEquals("SONIC & KNUCKLES", rom.readInternationalName());
            assertEquals(7, rom.getFileChannel().position(),
                    "reading the international name must not disturb a concurrent reader's position");
        }
    }

    /**
     * Same invariant for the whole-file checksum walk, which also paired
     * {@code position()} with an unlocked {@code read()}. A concurrent reader
     * moving the position mid-walk would make it skip or repeat a block and
     * return a checksum that is wrong rather than merely stale.
     */
    @Test
    void checksumWalkDoesNotMoveSharedChannelPosition() throws Exception {
        Path romPath = tempDir.resolve("checksum.gen");
        byte[] bytes = new byte[0x900];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        Files.write(romPath, bytes);

        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));

            rom.getFileChannel().position(0);
            int fromStart = rom.calculateChecksum();

            rom.getFileChannel().position(3);
            assertEquals(fromStart, rom.calculateChecksum(),
                    "the checksum must not depend on where another reader left the position");
            assertEquals(3, rom.getFileChannel().position(),
                    "the checksum walk must not disturb a concurrent reader's position");
        }
    }

    private static void writeAscii(byte[] target, int offset, String value) {
        byte[] ascii = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, target, offset, ascii.length);
        for (int i = offset + ascii.length; i < offset + NAME_LEN; i++) {
            target[i] = ' ';
        }
    }
}
