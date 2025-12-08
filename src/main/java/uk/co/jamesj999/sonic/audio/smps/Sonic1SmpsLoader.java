package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.tools.DcmDecoder;
import uk.co.jamesj999.sonic.tools.KosinskiReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class Sonic1SmpsLoader implements SmpsLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic1SmpsLoader.class.getName());
    private final Rom rom;
    private final DcmDecoder dcmDecoder = new DcmDecoder();

    // S1 Rev 00/01 offsets
    private static final int MUSIC_POINTER_LIST = 0x071A9C;
    private static final int Z80_DRIVER_OFFSET = 0x072E7C;
    private static final int DAC_TABLE_OFFSET_IN_DRIVER = 0x00D6; // From Pointers.txt (Z80 Driver: 00D6)

    public Sonic1SmpsLoader(Rom rom) {
        this.rom = rom;
    }

    @Override
    public AbstractSmpsData loadMusic(int musicId) {
        int offset = findMusicOffset(musicId);
        if (offset == -1) {
            return null;
        }
        // S1 music is uncompressed.
        // It runs on Z80 but is stored in 68k ROM and accessed via pointers?
        // Actually, S1 driver (on Z80) reads from ROM banks or via 68k.
        // But for our emulator, we just need the SMPS data.
        // S1 SMPS headers are standard.
        // Z80 Address: S1 usually loads music at a specific Z80 RAM location or streams it.
        // But the SmpsData class needs an address to resolve relative jumps.
        // For S1, let's check `Sonic1SmpsData`.

        // We will read a chunk of data. Since it's uncompressed, we don't know the exact size.
        // We can guess or read until a large enough buffer.
        // SMPS headers don't strictly define size unless we parse it.
        // Let's read 4KB or so, or until the next pointer?
        // For now, let's read a safe amount, e.g., 4KB.

        try {
            byte[] data = rom.readBytes(offset, 0x2000); // 8KB
            // Z80 start address for S1?
            // In S1, the music pointer is a ROM pointer. The Z80 driver reads from ROM window.
            // The "Z80 Start Address" in SmpsData is used to adjust pointers if they are Z80-relative.
            // If S1 music uses ROM pointers for jumps (F6 xx xx), we need to know the base.
            // S1 SMPS usually uses relative jumps or absolute Z80 RAM pointers?
            // Actually S1 pointers in SMPS are usually relative to the track start or absolute ROM (if 68k driver).
            // But this is the Z80 driver.
            // Let's assume the data is relocatable or uses relative jumps,
            // OR we treat the Z80 Address as the ROM offset if the driver reads directly from ROM space.
            // Sonic 1 Z80 driver reads from ROM bank window (0x8000+).
            // So we might need to map it to 0x8000 + (offset & 0x7FFF).

            // However, `Sonic1SmpsData` constructor might handle this.
            // Let's check `Sonic1SmpsData` source after this.

            return new Sonic1SmpsData(data, 0x8000 | (offset & 0x7FFF)); // Mimic bank window
        } catch (IOException e) {
            LOGGER.severe("Failed to read music at " + Integer.toHexString(offset));
            return null;
        }
    }

    @Override
    public AbstractSmpsData loadSfx(String name) {
        // SFX logic for S1 to be implemented if needed.
        return null;
    }

    @Override
    public int findMusicOffset(int musicId) {
        if (musicId < 0x81) return -1;
        int index = musicId - 0x81;
        int ptrAddr = MUSIC_POINTER_LIST + (index * 4);
        try {
            // S1 pointers are 4 bytes (ROM address)
            return rom.read32BitAddr(ptrAddr) & 0xFFFFFF;
        } catch (Exception e) {
            LOGGER.severe("Error reading pointer for music " + Integer.toHexString(musicId));
            return -1;
        }
    }

    @Override
    public DacData loadDacData() {
        Map<Integer, byte[]> samples = new HashMap<>();
        Map<Integer, DacData.DacEntry> mapping = new HashMap<>();

        try {
            // 1. Decompress Z80 driver
            byte[] compressedDriver = rom.readBytes(Z80_DRIVER_OFFSET, 0x2000); // Read enough
            ReadableByteChannel channel = Channels.newChannel(new ByteArrayInputStream(compressedDriver));
            byte[] z80Driver = KosinskiReader.decompress(channel);

            // 2. Read DAC Table from decompressed driver
            int tableOffset = DAC_TABLE_OFFSET_IN_DRIVER;

            // S1 DAC Table: Offset(2), Len(2), Pitch(1), Unused(3) -> 8 bytes
            // The table has entries for 81, 82, etc.
            // How many? Until we hit end or invalid?
            // S1 has Kick(81), Snare(82), Timpani(83)?
            // Pointers.txt says "DAC Pitch Modifier: ... (DAC sound 83)".
            // Let's read a few.

            for (int i = 0; i < 6; i++) { // S1 has few samples
                int entryAddr = tableOffset + (i * 8);
                if (entryAddr + 8 > z80Driver.length) break;

                int ptr = (z80Driver[entryAddr] & 0xFF) | ((z80Driver[entryAddr+1] & 0xFF) << 8);
                int len = (z80Driver[entryAddr+2] & 0xFF) | ((z80Driver[entryAddr+3] & 0xFF) << 8);
                // Pitch is at +4
                int pitch = z80Driver[entryAddr+4] & 0xFF;

                if (ptr == 0 || len == 0) continue;

                // ptr is Z80 RAM offset. The driver is loaded at Z80 0x0000 (usually).
                // So ptr is an index into the z80Driver array.
                if (ptr + len <= z80Driver.length) {
                    byte[] dpcData = new byte[len];
                    System.arraycopy(z80Driver, ptr, dpcData, 0, len);
                    byte[] pcm = dcmDecoder.decode(dpcData);
                    samples.put(0x81 + i, pcm);

                    // Mapping: ID -> SampleID, Rate/Pitch
                    // S1 uses pitch byte.
                    // DacData expects (SampleID, Rate).
                    // We can store Pitch as Rate for now, or convert?
                    // S2/S3 use a rate table index or actual rate value?
                    // S2 uses a rate value. S1 pitch byte is likely a timer reload value or similar.
                    // We'll pass it as is and let the Backend handle it if possible, or mapping.
                    // For now, map 0x81+i -> Sample 0x81+i, Pitch
                    mapping.put(0x81 + i, new DacData.DacEntry(0x81 + i, pitch));
                }
            }

            return new DacData(samples, mapping);

        } catch (Exception e) {
            LOGGER.severe("Failed to load S1 DAC Data");
            e.printStackTrace();
            return new DacData(new HashMap<>(), new HashMap<>());
        }
    }
}
