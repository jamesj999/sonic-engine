package com.openggf.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestRomByteReaderCache {
    @TempDir Path directory;

    @Test
    void repeatedConsumersShareOneImmutableReaderWithoutMovingChannel() throws Exception {
        Path file = directory.resolve("rom.gen");
        Files.write(file, new byte[] {1, 2, 3, 4});
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(file.toString()));
            rom.getFileChannel().position(2);
            RomByteReader first = RomByteReader.fromRom(rom);
            assertSame(first, RomByteReader.fromRom(rom));
            assertEquals(2, rom.getFileChannel().position());
            first.slice(0, 4)[0] = 99;
            rom.readAllBytes()[1] = 99;
            assertEquals(0x01020304, RomByteReader.fromRom(rom).readU32BE(0));
        }
    }

    @Test
    void reopenReplacesCacheButPreviouslyIssuedReaderRemainsImmutable() throws Exception {
        Path firstFile = directory.resolve("first.gen");
        Path secondFile = directory.resolve("second.gen");
        Files.write(firstFile, new byte[] {1});
        Files.write(secondFile, new byte[] {2});
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(firstFile.toString()));
            RomByteReader first = RomByteReader.fromRom(rom);
            rom.close();
            assertThrows(java.io.IOException.class, () -> RomByteReader.fromRom(rom));
            assertTrue(rom.open(secondFile.toString()));
            RomByteReader second = RomByteReader.fromRom(rom);
            assertNotSame(first, second);
            assertEquals(1, first.readU8(0));
            assertEquals(2, second.readU8(0));
        }
    }

    @Test
    void arrayConstructorStillDefensivelyCopiesCallerStorage() {
        byte[] source = {1, 2};
        RomByteReader reader = new RomByteReader(source);
        source[0] = 99;
        assertEquals(1, reader.readU8(0));
    }
}
