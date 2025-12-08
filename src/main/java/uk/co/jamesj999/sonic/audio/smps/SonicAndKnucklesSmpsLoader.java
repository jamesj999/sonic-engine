package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.tools.KosinskiReader;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class SonicAndKnucklesSmpsLoader implements SmpsLoader {
    private static final Logger LOGGER = Logger.getLogger(SonicAndKnucklesSmpsLoader.class.getName());
    private final Rom rom;

    // S&K (W) Offsets
    private static final int Z80_DRIVER_OFFSET = 0x0F6960;
    // Offsets within the DECOMPRESSED driver (based on Z80 RAM map 0000-1FFF)
    // S&K Z80 RAM map: Driver likely starts at 0000 or has specific offsets.
    // Pointers.txt: "Music Pointer List: 1618 (W)". This is Z80 RAM.
    // Z80 Driver is loaded where? Usually 0x0000.
    // So we access decompressed_driver[0x1618].

    private byte[] decompressedDriver = null;

    public SonicAndKnucklesSmpsLoader(Rom rom) {
        this.rom = rom;
        initDriver();
    }

    private void initDriver() {
        try {
            // Decompress Z80 driver from 0x0F6960
            // Size unknown, read a chunk?
            // Kosinski usually has header/markers.
            // Let's read 16KB to be safe (Z80 RAM is 8KB but compressed could be smaller, decompression larger? No, 8KB max).
            // Actually, S&K driver might be split or larger.
            // But fits in Z80 RAM (8KB).

            byte[] compressed = rom.readBytes(Z80_DRIVER_OFFSET, 0x4000); // 16KB guess
            ReadableByteChannel channel = Channels.newChannel(new ByteArrayInputStream(compressed));
            decompressedDriver = KosinskiReader.decompress(channel);
            LOGGER.info("Decompressed S&K Z80 Driver. Size: " + decompressedDriver.length);
        } catch (Exception e) {
            LOGGER.severe("Failed to decompress S&K Z80 driver");
            e.printStackTrace();
        }
    }

    @Override
    public AbstractSmpsData loadMusic(int musicId) {
        int offset = findMusicOffset(musicId);
        if (offset == -1) return null;

        // Load SMPS from ROM offset.
        // S&K music format is S3-like (Z80 LE).
        // Check for compression?
        // Usually stored uncompressed in banks.
        return new Sonic2SmpsLoader(rom).loadSmps(offset, 0x8000 | (offset & 0x7FFF));
    }

    @Override
    public AbstractSmpsData loadSfx(String name) {
        return null;
    }

    @Override
    public int findMusicOffset(int musicId) {
        if (decompressedDriver == null) return -1;
        if (musicId <= 0) return -1;

        // Pointers.txt: Music Pointer List: 1618 (W). 2 bytes (Ptr).
        // Music Bank List: 0B65 (W). 1 byte (Bank).

        // ID mapping? Same as S3 (1-based index).
        // Let's assume musicId matches index for now (1..N).
        int index = musicId;

        try {
            int ptrListAddr = 0x1618;
            int bankListAddr = 0x0B65;

            if (ptrListAddr + (index * 2) + 2 > decompressedDriver.length) return -1;
            if (bankListAddr + index + 1 > decompressedDriver.length) return -1;

            // Read Ptr (LE) from driver
            int p1 = decompressedDriver[ptrListAddr + (index * 2)] & 0xFF;
            int p2 = decompressedDriver[ptrListAddr + (index * 2) + 1] & 0xFF;
            int ptr = p1 | (p2 << 8); // Z80 Window Address

            // Read Bank
            int bank = decompressedDriver[bankListAddr + index] & 0x0F; // Low 4 bits

            // Calculate ROM Offset
            // S3/S&K Bank Base 0x080000?
            // "resulting ROM bank is 080000..0FFFFF" (from S3 Pointers.txt).
            // Assuming S&K uses same mapping for music banks.
            // S&K is a larger ROM (lock-on).
            // But "W" (Stand-alone S&K) music is in 080000+ range?
            // Actually S&K (W) is 2MB or 4MB? 2MB.
            // Music is likely in the S&K ROM.
            // 0x080000 is in the ROM.

            int bankOffset = 0x080000 + (bank * 0x8000);
            int finalOffset = bankOffset + (ptr & 0x7FFF);

            return finalOffset;
        } catch (Exception e) {
            LOGGER.severe("Error calculating S&K offset for " + Integer.toHexString(musicId));
            return -1;
        }
    }

    @Override
    public DacData loadDacData() {
        // Stub for now, similar issues to S3 (need to locate table).
        // Pointers.txt: DAC Bank List: 00D6 (W).
        // DAC Drum Pointer List: 8000 (W).
        return new DacData(new HashMap<>(), new HashMap<>());
    }
}
