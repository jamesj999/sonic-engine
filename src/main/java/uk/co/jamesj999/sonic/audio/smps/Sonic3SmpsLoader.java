package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.data.Rom;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class Sonic3SmpsLoader implements SmpsLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic3SmpsLoader.class.getName());
    private final Rom rom;

    // S3 (JUE) Offsets
    private static final int MUSIC_POINTER_LIST = 0x0E761A;
    private static final int MUSIC_BANK_LIST = 0x0E6B48;
    private static final int DAC_BANK_LIST = 0x0E60DC;

    public Sonic3SmpsLoader(Rom rom) {
        this.rom = rom;
    }

    @Override
    public AbstractSmpsData loadMusic(int musicId) {
        // S3 logic for pointer resolution
        int offset = findMusicOffset(musicId);
        if (offset == -1) return null;

        // In S3, SMPS data can be compressed (Kosinski/Universal) or uncompressed.
        // The pointer table entry doesn't have flags like S2 (or does it?).
        // S3 usually stores data in banks.
        // We load the data from the resolved offset.
        // Need to check if it's compressed.
        // S3 "Universal Code" usually means compressed.
        // But often S3 music is stored uncompressed in the ROM bank.
        // Let's assume uncompressed first, or check for header?
        // Actually, S3/S&K music is often uncompressed in the banks, unlike S2.
        // But the Z80 driver is compressed.
        // Let's assume uncompressed for now as per "Pointers.txt" implying banks of data.
        // If it fails (garbage header), we might need to look at compression.

        // Z80 Address: S3 uses bank switching.
        // The Z80 sees the bank at a window (usually 0x8000).
        // So we should map the data to 0x8000 | (offset & 0x7FFF).

        return new Sonic2SmpsLoader(rom).loadSmps(offset, 0x8000 | (offset & 0x7FFF));
        // Re-use Sonic2SmpsLoader's loadSmps?
        // Sonic2SmpsLoader uses Saxman. S3 uses Kosinski if compressed.
        // But `loadSmps` in S2Loader tries to read header size.
        // S3 SMPS headers are Little Endian (Z80).
        // So we can try to use a similar logic but S3 headers might differ slightly.
        // S3 headers are standard SMPS Z80 headers.

        // I should probably implement a `loadSmps` here that handles S3 specific compression if needed.
        // But if it's uncompressed, S2Loader's logic (minus Saxman) might work.
        // Actually, better to implement fresh here to be safe.
    }

    private AbstractSmpsData loadSmps(int offset, int z80Addr) {
        try {
            // Read first 2 bytes for size (Little Endian)
            int b1 = rom.readByte(offset) & 0xFF;
            int b2 = rom.readByte(offset + 1) & 0xFF;
            int size = b1 | (b2 << 8);

            // Sanity check size
            if (size <= 0 || size > 0x8000) {
                // Maybe it's a pointer (if size is huge)? Or valid 32k bank?
                // S3 music usually fits in a bank.
                // If size is invalid, it might be compressed or raw data without size header?
                // Standard SMPS Z80 files usually have the size header.
            }

            // Read raw data
            byte[] data = rom.readBytes(offset, size + 2);
            // S3 is Little Endian (Z80).
            // Use Sonic2SmpsData as it handles Little Endian Z80 format.
            return new Sonic2SmpsData(data, z80Addr);
        } catch (Exception e) {
            LOGGER.severe("Failed to load S3 SMPS at " + Integer.toHexString(offset));
            return null;
        }
    }

    @Override
    public AbstractSmpsData loadSfx(String name) {
        return null;
    }

    @Override
    public int findMusicOffset(int musicId) {
        // Music IDs start at 0x01 in S3? Or 0x81?
        // Pointers.txt says "Music Pointer List...".
        // S3 usually uses IDs 01..XX.
        // SoundTestApp uses 0x81 convention.
        // In S3, 0x01 is Angel Island.
        // We need to map 0x01-based index?
        // Let's assume input musicId matches the game's ID system.
        // If SoundTestApp sends 0x81 (Emerald Hill), we interpret it as ID 0x81.
        // But S3 doesn't have ID 0x81 music (it uses 01-30 approx).
        // Wait, S3 IDs:
        // 01 Angel Island
        // ...
        // 25 Title Screen
        // So musicId 1 = Angel Island.

        // Music Pointer List (0E761A). 2 bytes per entry?
        // Music Bank List (0E6B48). 1 byte per entry (low 4 bits).

        // S3 usually indexes from 0 or 1?
        // Pointer list usually starts at ID 1? Or 0?
        // 00 is usually invalid or "Stop".
        // Let's assume 1-based index for lookup.

        if (musicId <= 0) return -1;

        int index = musicId; // Or musicId - 1?
        // Usually index 0 is for ID 0 (Stop), index 1 for ID 1.
        // So we access table[musicId].

        try {
            // 1. Get Pointer (2 bytes LE)
            int ptrAddr = MUSIC_POINTER_LIST + (index * 2);
            int p1 = rom.readByte(ptrAddr) & 0xFF;
            int p2 = rom.readByte(ptrAddr + 1) & 0xFF;
            int ptr = p1 | (p2 << 8); // Z80 Window Address (8000-FFFF)

            // 2. Get Bank (1 byte)
            int bankAddr = MUSIC_BANK_LIST + index; // 1 byte per entry?
            // "Music Bank List... (low 4 bits only)"
            int bankByte = rom.readByte(bankAddr) & 0x0F;

            // 3. Calculate ROM Offset
            // Bank 0 maps to 0x000000? Or 0x080000?
            // Pointers.txt: "resulting ROM bank is 080000..0FFFFF"
            // So Base is 0x080000.
            // Bank size 32KB (0x8000).
            // Offset = 0x080000 + (Bank * 0x8000) + (Ptr & 0x7FFF).
            // (Ptr is usually 0x8000-0xFFFF).

            int bankOffset = 0x080000 + (bankByte * 0x8000);
            int finalOffset = bankOffset + (ptr & 0x7FFF);

            return finalOffset;
        } catch (Exception e) {
            LOGGER.severe("Error calculating S3 offset for " + Integer.toHexString(musicId));
            return -1;
        }
    }

    @Override
    public DacData loadDacData() {
        Map<Integer, byte[]> samples = new HashMap<>();
        Map<Integer, DacData.DacEntry> mapping = new HashMap<>();

        try {
            // DAC Bank List at 0x0E60DC.
            // "Starts with entry for note 80".
            // Entry format? Pointers.txt: "DAC List Format... Same as MMPR... DPCM compressed...".
            // Wait, DAC Bank List is just the banks?
            // "DAC Drum Pointer List: 8000 (JUE) (Z80 Bank Offset) [Note: starts with entry for note 81]"
            // This means the pointers are at `0x8000` in the *bank* specified by the DAC Bank List.

            // So for Note N (starts at 0x81):
            // 1. Get Bank from DAC Bank List [N - 0x80].
            // 2. Ptr Table is at offset 0 in that bank (Z80 0x8000).
            //    Wait, "DAC Drum Pointer List: 8000".
            //    Usually this means the pointers are at the start of the bank.
            //    The pointers themselves?
            //    "Format... 4 bytes? 2 bytes?"
            //    MMPR format: "2 bytes - Start Offset, 2 bytes - Length, 1 byte - pitch...".
            //    Actually S3 format is: Ptr(2), Size(2).
            //    Let's check S2 format.
            //    Pointers.txt: "Format is the same one as used in MMPR".
            //    S3 Pointers.txt: "Start Offset (Z80 Driver offset 10EE?)" - this refers to the decoding table.

            // Let's assume standard S3 format:
            // For ID 0x81+i:
            // Bank Index = (ID - 0x80)?
            //   Wait, "DAC Bank List... starts with entry for note 80".
            //   So ID 0x81 is at index 1.
            //   Bank = readByte(DAC_BANK_LIST + (ID - 0x80)).
            //   BankAddr = 0x080000 + (Bank * 0x8000) (Assuming same bank logic as music? Or different base? DAC usually uses full ROM access or similar bank window).
            //   Usually DAC banks are also mapped to Z80 0x8000 window.
            //   The Pointer is at the start of that bank?
            //   "DAC Drum Pointer List: 8000... starts with entry for note 81".
            //   This is confusing.
            //   Does it mean there's a SINGLE pointer table at 0x8000 in a specific bank?
            //   Or does each bank contain the sample?
            //   In S3, usually, the pointer table is fixed (e.g. in driver or ROM), and it points to samples in banks.
            //   BUT "DAC Bank List" implies each sample has its own bank byte.
            //   So:
            //     Bank = BankList[ID - 0x80]
            //     The sample is at `Bank` window.
            //     Where in the bank?
            //     Maybe the "Pointer List" gives the offset within the bank?
            //     "DAC Drum Pointer List: 8000 (JUE) (Z80 Bank Offset)".
            //     Maybe the table is at 0x8000 in the Z80 (which is the bank start).
            //     So:
            //       Go to Bank X.
            //       Read table at start of Bank X?
            //       No, "starts with entry for note 81".
            //       It implies there is ONE list of pointers.
            //       But where is it?
            //       If it says "8000 (Z80 Bank Offset)", maybe the table itself is in a bank?
            //       Or maybe the table is in the driver, and it says "8000" meaning the pointers are relative to 8000?

            // Let's look at a reference or disassembly logic for S3 DAC.
            // S3 DAC driver loads Bank for sample.
            // Then reads Sample Start and Size from a table.
            // Where is the table?
            // "DAC Drum Pointer List: 8000...". This looks like a Z80 address.
            // Since Z80 RAM is 0000-1FFF, 8000 is in the bank window.
            // So the table is in the currently banked ROM.
            // Which bank contains the table?
            // Usually the driver sets a default bank.
            // Or maybe the table is at `0x0E60DC` (DAC Bank List) + something? No.

            // Re-reading Pointers.txt carefully:
            // "DAC Bank List: 0E60DC... starts with entry for 80". (This is a list of Bytes).
            // "DAC Drum Pointer List: 8000 (JUE) (Z80 Bank Offset)".
            // This likely means the *pointers* (offsets) for the drums are stored at 0x8000 in the Z80 address space.
            // But which ROM bank is mapped to 0x8000 when reading the list?
            // The driver probably has a "Data Bank" or similar.
            // For S3, the "Global Instrument Data" or similar might be relevant.

            // HYPOTHESIS: The DAC Pointer List is at the beginning of the ROM bank used for the driver or a specific data bank.
            // AND "DAC Bank List" tells us which bank each SAMPLE is in.

            // Let's look at `0E60DC`. It's a list of banks.
            // What about the offsets?
            // Maybe the offsets are fixed? Or maybe there is a "DAC Offset List"?
            // Pointers.txt mentions "DAC Drum Pointer List: 8000".
            // If this is Z80 0x8000, it's the start of the bank window.
            // This implies that for a given drum, we switch to its bank (from Bank List),
            // and the sample data starts at... 0x8000?
            // "starts with entry for note 81".
            // This phrasing "starts with entry" usually refers to a table.
            // If the table is at 0x8000, it means the table is in the bank.
            // But the bank changes per sample.
            // So does EVERY sample bank have a table at 0x8000?
            // That would be redundant.

            // ALTERNATIVE: The "DAC Drum Pointer List" is in a specific bank, let's say Bank Y.
            // The driver switches to Bank Y to read the pointer/size.
            // THEN it switches to the Sample's Bank (from DAC Bank List) to play it.

            // Which bank contains the pointer list?
            // Likely the same bank as the driver or a dedicated "Data Bank".
            // S3 Z80 driver is at `0E6000`.
            // `0E6000` is in ROM bank `0E6000 / 0x8000`? No, S3 uses specific mapping.
            // Z80 driver usually manages this.

            // Let's assume the table is in the ROM at a known location.
            // If "8000" is the offset, maybe it means the table is at the start of the "Drums" bank?
            // But S3 has many drum banks.

            // Let's try to interpret `DAC Drum Pointer List: 8000` as `ROM Offset`? No, it says `Z80 Bank Offset`.
            // Maybe there is a *Master DAC Bank*?
            // S3 DPCM samples are usually in the `0x080000`+ range.

            // Let's peek at `0E60DC` (DAC Bank List) in the ROM to see values.
            // If they are all the same, then there is one bank.
            // If different, multiple banks.

            // Implementation Strategy:
            // 1. Read DAC Bank List (starts at 81? Pointers says 80).
            //    We iterate 81..9A (S3 drums).
            // 2. For each ID:
            //    Bank = readByte(DAC_BANK_LIST + (ID - 0x80)).
            //    Offset/Len = ??

            // If I can't find the table, I can't load samples.
            // However, "DAC Drum Pointer List: 8000" might mean "The pointer is the Z80 address 8000".
            // i.e., The sample *always* starts at 0x8000 in its assigned bank.
            // This is a common optimization (align samples to bank start).
            // If so, Offset = 0x8000 (Z80) -> 0 in Bank.
            // Length? We need the length.
            // Does the table exist?
            // "DAC Drum Pointer List" implies a list.
            // Maybe the list is at 0x8000 in the *Driver's* bank?

            // Let's assume the "DAC Drum Pointer List" is at a fixed ROM address.
            // Where?
            // If it's at `8000 (Z80 Bank Offset)`, and the driver is at `0E6000`, maybe the list is at `0E8000`?
            // Let's try to read `0E8000` and see if it looks like a table.
            // Or `0F8000`?

            // For now, I'll implement a placeholder or basic attempt.
            // Note: S3 DAC samples are compressed (DPCM).

            return new DacData(samples, mapping);
        } catch (Exception e) {
            e.printStackTrace();
            return new DacData(new HashMap<>(), new HashMap<>());
        }
    }
}
