package com.openggf.game.sonic3k.audio.smps;

import static com.openggf.game.sonic3k.audio.Sonic3kMusic.S3_MUSIC_ID_BASE;

import com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.AbstractSmpsLoader;
import com.openggf.audio.smps.DacData;
import com.openggf.data.Rom;
import com.openggf.data.compression.KosinskiReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SMPS data loader for Sonic 3 &amp; Knuckles.
 *
 * <p>S3K uses a Z80 sound driver that is Kosinski-compressed in ROM. The driver
 * code lives at {@link Sonic3kSmpsConstants#Z80_DRIVER_ADDR}, and additional data
 * (pointer tables, PSG envelopes, instrument table) at
 * {@link Sonic3kSmpsConstants#Z80_ADDITIONAL_DATA_ADDR} (loaded to Z80 RAM 0x1300).
 *
 * <p>Key differences from Sonic 1/2 loaders:
 * <ul>
 *   <li>Music data is bank-switched raw data (not Saxman compressed like S2).</li>
 *   <li>A music bank list maps each music ID to a ROM bank address.</li>
 *   <li>A music pointer list gives Z80 offsets within each bank.</li>
 *   <li>SFX are in a single bank (like S2), pointed to by the SFX pointer list.</li>
 *   <li>A global instrument table is shared across songs.</li>
 *   <li>DAC samples use DPCM compression with bank-switching.</li>
 * </ul>
 */
public class Sonic3kSmpsLoader extends AbstractSmpsLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kSmpsLoader.class.getName());

    /** Maximum reasonable size for a single SMPS song/SFX blob. */
    private static final int MAX_BLOB_SIZE = 0x8000; // 32 KB (one Z80 bank)

    /** Voice table padding for SFX (25 bytes per voice, generous margin). */
    private static final int SFX_VOICE_TABLE_PADDING = 0x100;

    private final Map<Integer, byte[]> bankCache = new HashMap<>();

    // Decompressed Z80 driver data
    private byte[] z80Driver;
    private byte[] z80AdditionalData;

    // Parsed from S&K Z80 data
    private byte[] globalVoiceData;
    private Map<Integer, byte[]> modEnvelopes;
    private Map<Integer, byte[]> psgEnvelopes;
    private int[] musicBankAddresses;
    private int[] musicPointers;
    private int[] sfxPointers;

    // Parsed from S3 Z80 data (uncompressed driver at ROM 0x0E6000)
    private int[] s3MusicBankAddresses;
    private int[] s3MusicPointers;

    public Sonic3kSmpsLoader(Rom rom) {
        super(rom);
        decompressZ80Data();
        parseZ80Tables();
        parseS3MusicTables();
        loadModEnvelopes();
        loadPsgEnvelopes();
        loadGlobalInstrumentTable();
    }

    @Override
    public AbstractSmpsData loadMusic(int musicId) {
        // Route S3-specific IDs (0x100+) to the S3 driver tables
        if (musicId >= S3_MUSIC_ID_BASE) {
            return loadS3Music(musicId & 0xFF);
        }

        if (musicId <= 0 || musicBankAddresses == null || musicPointers == null) {
            LOGGER.fine("Cannot load S3K music ID 0x" + Integer.toHexString(musicId)
                    + ": driver data not loaded.");
            return null;
        }

        AbstractSmpsData cached = musicCache.get(musicId);
        if (cached != null) {
            return cached;
        }

        try {
            // Music tables are 0-indexed: index 0 = song ID 0x01, index 1 = song ID 0x02, etc.
            int index = musicId - 1;

            if (index < 0 || index >= musicBankAddresses.length) {
                LOGGER.warning("S3K music ID 0x" + Integer.toHexString(musicId)
                        + " exceeds bank list size (" + musicBankAddresses.length + ").");
                return null;
            }

            int bankAddr = musicBankAddresses[index];
            if (bankAddr == 0) {
                LOGGER.fine("S3K music ID 0x" + Integer.toHexString(musicId) + " has null bank.");
                return null;
            }

            // Music pointer list has one entry per music ID (2 bytes each, LE).
            // This is a Z80 address (0x8000-based) within the bank.
            if (index >= musicPointers.length) {
                LOGGER.warning("S3K music ID 0x" + Integer.toHexString(musicId)
                        + " exceeds pointer list size.");
                return null;
            }

            int z80Ptr = musicPointers[index];
            if (z80Ptr == 0) {
                LOGGER.fine("S3K music ID 0x" + Integer.toHexString(musicId)
                        + " has null pointer.");
                return null;
            }

            // Convert Z80 bank-relative pointer to ROM address
            int bankOffset = z80Ptr & Sonic3kSmpsConstants.Z80_BANK_MASK;
            int romAddr = bankAddr + bankOffset;

            if (romAddr <= 0 || romAddr >= rom.getSize()) {
                LOGGER.warning("Invalid S3K music ROM address for ID 0x"
                        + Integer.toHexString(musicId) + ": 0x" + Integer.toHexString(romAddr));
                return null;
            }

            // Calculate data size (bounded by bank end or next music entry)
            int dataSize = calculateMusicDataSize(index, romAddr, bankAddr);
            byte[] raw = rom.readBytes(romAddr, dataSize);

            LOGGER.info("Loaded S3K music ID 0x" + Integer.toHexString(musicId)
                    + " at ROM 0x" + Integer.toHexString(romAddr)
                    + " (bank 0x" + Integer.toHexString(bankAddr)
                    + ", z80Ptr=0x" + Integer.toHexString(z80Ptr)
                    + ", " + raw.length + " bytes)");
            LOGGER.info("  First 8 bytes: " + hexDump(raw, 0, 8));

            Sonic3kSmpsData data = new Sonic3kSmpsData(raw, z80Ptr);
            data.setModEnvelopes(modEnvelopes);
            data.setPsgEnvelopes(psgEnvelopes);
            data.setGlobalVoiceData(globalVoiceData);

            // Provide full bank data for shared voice table resolution.
            // Songs later in a bank need to reach the shared voice table
            // at the beginning of the bank (before their own start address).
            byte[] bank = loadBank(bankAddr);
            if (bank != null) {
                data.setBankData(bank, Sonic3kSmpsConstants.Z80_BANK_BASE);
            }

            data.setId(musicId);
            musicCache.put(musicId, data);
            return data;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load S3K music ID 0x" + Integer.toHexString(musicId), e);
            return null;
        }
    }

    @Override
    public AbstractSmpsData loadSfx(int sfxId) {
        if (sfxId < Sonic3kSfx.ID_BASE || sfxId > Sonic3kSfx.ID_MAX) {
            LOGGER.fine("S3K SFX ID 0x" + Integer.toHexString(sfxId) + " out of range.");
            return null;
        }

        AbstractSmpsData cached = sfxCache.get(sfxId);
        if (cached != null) {
            return cached;
        }

        try {
            int index = sfxId - Sonic3kSfx.ID_BASE;
            if (sfxPointers == null || index >= sfxPointers.length) {
                LOGGER.warning("S3K SFX ID 0x" + Integer.toHexString(sfxId)
                        + " exceeds pointer list size.");
                return null;
            }

            int z80Ptr = sfxPointers[index];
            if (z80Ptr == 0) {
                return null;
            }

            // SFX are in the bank at SFX_BANK_BASE
            int bankOffset = z80Ptr & Sonic3kSmpsConstants.Z80_BANK_MASK;
            int romOffset = Sonic3kSmpsConstants.SFX_BANK_BASE + bankOffset;
            int headerOffset = bankOffset;

            // Calculate SFX length
            int sfxLength = computeSfxLength(index, romOffset);

            // Extend buffer for voice table
            int voicePtr = readLE16FromRom(romOffset);
            int voiceOffset = (voicePtr == 0) ? -1 : (voicePtr & Sonic3kSmpsConstants.Z80_BANK_MASK);
            int voiceReach = (voiceOffset < 0) ? 0 : voiceOffset + SFX_VOICE_TABLE_PADDING;
            int readLength = Math.max(headerOffset + sfxLength, voiceReach);
            int bankLimit = Sonic3kSmpsConstants.Z80_BANK_BASE;
            if (readLength > bankLimit) {
                readLength = bankLimit;
            }

            byte[] raw = rom.readBytes(Sonic3kSmpsConstants.SFX_BANK_BASE, readLength);

            Sonic3kSfxData sfx = new Sonic3kSfxData(raw, Sonic3kSmpsConstants.Z80_BANK_BASE, 0, headerOffset);
            sfx.setModEnvelopes(modEnvelopes);
            sfx.setPsgEnvelopes(psgEnvelopes);
            sfx.setGlobalVoiceData(globalVoiceData);
            sfx.setId(sfxId);

            if (sfx.getTrackEntries().isEmpty()) {
                LOGGER.fine("S3K SFX ID 0x" + Integer.toHexString(sfxId) + " has no tracks.");
                return null;
            }

            sfxCache.put(sfxId, sfx);
            return sfx;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to load S3K SFX ID 0x" + Integer.toHexString(sfxId), e);
            return null;
        }
    }

    @Override
    protected boolean isValidSfxId(int id) {
        return id >= Sonic3kSfx.ID_BASE && id <= Sonic3kSfx.ID_MAX;
    }

    @Override
    public DacData loadDacData() {
        Map<Integer, byte[]> samples = new HashMap<>();
        Map<Integer, DacData.DacEntry> mapping = new HashMap<>();

        if (z80Driver == null) {
            LOGGER.warning("Z80 driver not loaded, cannot load DAC data.");
            return new DacData(samples, mapping, 297);
        }

        try {
            Sonic3kDpcmDecoder decoder = new Sonic3kDpcmDecoder();

            // DAC bank list at Z80_DAC_BANK_LIST (starts with entry for note 0x80).
            // Each entry is 1 BYTE (bank number); ROM bank = bankByte << 15.
            //
            // DAC drum pointer list at Z80_DAC_DRUM_PTR_LIST (0x8000) is a table of
            // 2-byte LE pointers. Each pointer points to a 5-byte descriptor within
            // the same bank:
            //   byte 0: rate/pitch
            //   bytes 1-2: sample length (LE)
            //   bytes 3-4: sample data pointer (LE)
            // Source: DACExtract.c case 0x19 (lines 2441-2508).

            int dacBankListOffset = Sonic3kSmpsConstants.Z80_DAC_BANK_LIST;
            int dacDrumPtrOffset = Sonic3kSmpsConstants.Z80_DAC_DRUM_PTR_LIST;

            // Vanilla S&K has DAC notes 0x81-0x9B plus 0xB2/0xB3 present
            for (int noteId = Sonic3kSmpsConstants.DAC_NOTE_BASE; noteId <= Sonic3kSmpsConstants.DAC_NOTE_MAX; noteId++) {
                // Bank list entry for this note (note 0x80 is index 0)
                int bankIndex = noteId - (Sonic3kSmpsConstants.DAC_NOTE_BASE - 1); // note 0x81 = index 1
                int bankEntryOffset = dacBankListOffset + bankIndex;

                if (bankEntryOffset >= z80Driver.length) {
                    break;
                }

                int bankByte = z80Driver[bankEntryOffset] & 0xFF;
                if (bankByte == 0) {
                    continue;
                }

                // Convert bank byte to ROM address: bankByte << 15
                int romBank = bankByte << 15;

                // Pointer table: 2-byte entries at Z80_DAC_DRUM_PTR_LIST (0x8000).
                // Note 0x81 = index 0.
                int drumIndex = noteId - Sonic3kSmpsConstants.DAC_NOTE_BASE;
                int ptrTableOffset = dacDrumPtrOffset + (drumIndex * 2);

                // Pointer table is in the bank-switched area (0x8000+)
                if (ptrTableOffset + 1 >= Sonic3kSmpsConstants.Z80_BANK_BASE) {
                    int romPtrAddr = romBank + (ptrTableOffset & Sonic3kSmpsConstants.Z80_BANK_MASK);
                    if (romPtrAddr + 1 >= rom.getSize()) {
                        continue;
                    }

                    // Read 2-byte LE pointer to the 5-byte descriptor
                    int descriptorZ80Addr = readLE16FromRom(romPtrAddr);
                    if (descriptorZ80Addr == 0) {
                        continue;
                    }

                    // Follow pointer to descriptor in same bank
                    int descriptorRomAddr = romBank + (descriptorZ80Addr & Sonic3kSmpsConstants.Z80_BANK_MASK);
                    if (descriptorRomAddr + 4 >= rom.getSize()) {
                        continue;
                    }

                    // Read 5-byte descriptor: rate(1), length(2 LE), samplePtr(2 LE)
                    int rate = rom.readByte(descriptorRomAddr) & 0xFF;
                    int sampleLen = readLE16FromRom(descriptorRomAddr + 1);
                    int samplePtr = readLE16FromRom(descriptorRomAddr + 3);

                    if (samplePtr == 0 || sampleLen == 0) {
                        continue;
                    }

                    int sampleRomAddr = romBank + (samplePtr & Sonic3kSmpsConstants.Z80_BANK_MASK);
                    if (sampleRomAddr + sampleLen > rom.getSize()) {
                        sampleLen = (int) (rom.getSize() - sampleRomAddr);
                        if (sampleLen <= 0) continue;
                    }

                    byte[] compressed = rom.readBytes(sampleRomAddr, sampleLen);
                    byte[] pcm = decoder.decode(compressed);

                    samples.put(noteId, pcm);
                    mapping.put(noteId, new DacData.DacEntry(noteId, rate));

                    LOGGER.fine("Loaded S3K DAC note 0x" + Integer.toHexString(noteId)
                            + ": " + sampleLen + " DPCM bytes -> " + pcm.length + " PCM bytes"
                            + " (bank 0x" + Integer.toHexString(romBank)
                            + ", rate=0x" + Integer.toHexString(rate) + ")");
                }
            }

            LOGGER.info("Loaded " + samples.size() + " S3K DAC samples.");
            return new DacData(samples, mapping, 297); // S3K baseCycles = 297
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to load S3K DAC data", e);
            return new DacData(samples, mapping, 297);
        }
    }

    /**
     * Returns the loaded PSG envelope data.
     */
    public Map<Integer, byte[]> getPsgEnvelopes() {
        return psgEnvelopes;
    }

    /**
     * Returns the loaded modulation envelope data.
     */
    public Map<Integer, byte[]> getModEnvelopes() {
        return modEnvelopes;
    }

    /**
     * Returns the global instrument (voice) table data.
     */
    public byte[] getGlobalVoiceData() {
        return globalVoiceData;
    }

    // -----------------------------------------------------------------------
    // Z80 driver decompression and table parsing
    // -----------------------------------------------------------------------

    @Override
    public int findMusicOffset(int musicId) {
        if (musicId >= S3_MUSIC_ID_BASE) {
            return findS3MusicOffset(musicId & 0xFF);
        }
        int index = musicId - 1;
        if (index < 0 || musicBankAddresses == null || musicPointers == null
                || index >= musicBankAddresses.length || index >= musicPointers.length) {
            return -1;
        }
        int bankAddr = musicBankAddresses[index];
        int z80Ptr = musicPointers[index];
        if (bankAddr == 0 || z80Ptr == 0) return -1;
        return bankAddr + (z80Ptr & Sonic3kSmpsConstants.Z80_BANK_MASK);
    }

    /**
     * Returns the ROM offset for a music ID using the S3 driver tables.
     * Returns -1 if the S3 tables are not loaded or the ID is out of range.
     */
    public int findS3MusicOffset(int musicId) {
        int index = musicId - 1;
        if (index < 0 || s3MusicBankAddresses == null || s3MusicPointers == null
                || index >= s3MusicBankAddresses.length || index >= s3MusicPointers.length) {
            return -1;
        }
        int bankAddr = s3MusicBankAddresses[index];
        int z80Ptr = s3MusicPointers[index];
        if (bankAddr == 0 || z80Ptr == 0) return -1;
        return bankAddr + (z80Ptr & Sonic3kSmpsConstants.Z80_BANK_MASK);
    }

    /**
     * Loads a music track using the S3 standalone driver tables instead of the
     * S&K driver tables. This is needed for tracks that differ between the two
     * drivers, most notably ID 0x2E (S3 miniboss vs S&K miniboss).
     *
     * <p>Uses the same SMPS parsing infrastructure as {@link #loadMusic(int)}
     * but resolves bank addresses and pointers from the S3 driver's tables.
     *
     * @param musicId the music ID (1-based, same numbering as S&K)
     * @return the parsed SMPS data, or null if unavailable
     */
    public AbstractSmpsData loadS3Music(int musicId) {
        if (musicId <= 0 || s3MusicBankAddresses == null || s3MusicPointers == null) {
            LOGGER.fine("Cannot load S3 music ID 0x" + Integer.toHexString(musicId)
                    + ": S3 driver tables not loaded.");
            return null;
        }

        // Use a distinct cache key to avoid colliding with S&K-loaded versions
        int cacheKey = 0x10000 | musicId;
        AbstractSmpsData cached = musicCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            int index = musicId - 1;
            if (index < 0 || index >= Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT) {
                LOGGER.warning("S3 music ID 0x" + Integer.toHexString(musicId)
                        + " exceeds S3 table size (" + Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT + ").");
                return null;
            }

            int bankAddr = s3MusicBankAddresses[index];
            if (bankAddr == 0) {
                LOGGER.fine("S3 music ID 0x" + Integer.toHexString(musicId) + " has null bank.");
                return null;
            }

            int z80Ptr = s3MusicPointers[index];
            if (z80Ptr == 0) {
                LOGGER.fine("S3 music ID 0x" + Integer.toHexString(musicId) + " has null pointer.");
                return null;
            }

            int bankOffset = z80Ptr & Sonic3kSmpsConstants.Z80_BANK_MASK;
            int romAddr = bankAddr + bankOffset;

            if (romAddr <= 0 || romAddr >= rom.getSize()) {
                LOGGER.warning("Invalid S3 music ROM address for ID 0x"
                        + Integer.toHexString(musicId) + ": 0x" + Integer.toHexString(romAddr));
                return null;
            }

            // Calculate data size using S3 tables
            int dataSize = calculateS3MusicDataSize(index, romAddr, bankAddr);
            byte[] raw = rom.readBytes(romAddr, dataSize);

            LOGGER.info("Loaded S3 music ID 0x" + Integer.toHexString(musicId)
                    + " at ROM 0x" + Integer.toHexString(romAddr)
                    + " (bank 0x" + Integer.toHexString(bankAddr)
                    + ", z80Ptr=0x" + Integer.toHexString(z80Ptr)
                    + ", " + raw.length + " bytes)");

            Sonic3kSmpsData data = new Sonic3kSmpsData(raw, z80Ptr);
            data.setModEnvelopes(modEnvelopes);
            data.setPsgEnvelopes(psgEnvelopes);
            data.setGlobalVoiceData(globalVoiceData);

            byte[] bank = loadBank(bankAddr);
            if (bank != null) {
                data.setBankData(bank, Sonic3kSmpsConstants.Z80_BANK_BASE);
            }

            data.setId(musicId);
            musicCache.put(cacheKey, data);
            return data;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load S3 music ID 0x" + Integer.toHexString(musicId), e);
            return null;
        }
    }

    private void decompressZ80Data() {
        try {
            // Decompress the main Z80 driver
            byte[] compressed = rom.readBytes(Sonic3kSmpsConstants.Z80_DRIVER_ADDR, MAX_BLOB_SIZE);
            z80Driver = KosinskiReader.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressed)), false);
            LOGGER.info("Decompressed S3K Z80 driver: " + z80Driver.length
                    + " bytes from ROM 0x" + Integer.toHexString(Sonic3kSmpsConstants.Z80_DRIVER_ADDR));
            LOGGER.info("  First 16 bytes: " + hexDump(z80Driver, 0, 16));

            // Decompress additional Z80 data (goes to Z80 RAM 0x1300)
            compressed = rom.readBytes(Sonic3kSmpsConstants.Z80_ADDITIONAL_DATA_ADDR, MAX_BLOB_SIZE);
            z80AdditionalData = KosinskiReader.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressed)), false);
            LOGGER.info("Decompressed S3K Z80 additional data: " + z80AdditionalData.length
                    + " bytes from ROM 0x" + Integer.toHexString(Sonic3kSmpsConstants.Z80_ADDITIONAL_DATA_ADDR));
            LOGGER.info("  First 16 bytes: " + hexDump(z80AdditionalData, 0, 16));
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to decompress S3K Z80 data", e);
        }
    }

    private void parseZ80Tables() {
        if (z80Driver == null || z80AdditionalData == null) {
            LOGGER.warning("Z80 data not available, cannot parse tables.");
            return;
        }

        // The additional data is loaded at Z80 RAM 0x1300.
        // Music bank list is at Z80 RAM offset Z80_MUSIC_BANK_LIST (0x0B65) - in the main driver.
        // Music pointer list is at Z80 RAM offset Z80_MUSIC_PTR_LIST (0x1618) - in the additional data.
        // SFX pointer list is at Z80 RAM offset Z80_SFX_PTR_LIST (0x167E) - in the additional data.

        // Parse music bank list from main driver data
        parseMusicBankList();

        // Parse music pointer list from additional data
        parseMusicPointerList();

        // Parse SFX pointer list from additional data
        parseSfxPointerList();
    }

    private void parseMusicBankList() {
        int offset = Sonic3kSmpsConstants.Z80_MUSIC_BANK_LIST;
        if (offset + 1 > z80Driver.length) {
            LOGGER.warning("Music bank list offset 0x" + Integer.toHexString(offset)
                    + " exceeds Z80 driver size.");
            return;
        }

        // Estimate count: read entries until we hit the next known table or end of reasonable range
        int maxEntries = 0x60; // generous upper bound
        musicBankAddresses = new int[maxEntries];

        for (int i = 0; i < maxEntries; i++) {
            // Bank list entries are 1 BYTE each (not 2-byte words).
            // Each byte is a Z80 bank number; the ROM address is bankByte << 15.
            int entryOffset = offset + i;
            if (entryOffset >= z80Driver.length) {
                break;
            }
            int bankByte = z80Driver[entryOffset] & 0xFF;
            musicBankAddresses[i] = bankByte << 15;
        }

        // Log first 5 entries for diagnostics
        StringBuilder sb = new StringBuilder("S3K music bank list (first 5): ");
        for (int i = 0; i < Math.min(5, maxEntries); i++) {
            if (i > 0) sb.append(", ");
            sb.append("[").append(i).append("]=0x").append(Integer.toHexString(musicBankAddresses[i]));
        }
        LOGGER.info(sb.toString());
        LOGGER.info("Parsed S3K music bank list: " + maxEntries + " entries from offset 0x"
                + Integer.toHexString(offset));
    }

    private void parseMusicPointerList() {
        // Additional data is loaded at Z80 RAM 0x1300
        // Music pointer list is at Z80 RAM 0x1618
        int additionalBase = Sonic3kSmpsConstants.Z80_GENERAL_PTR_LIST; // 0x1300
        int listZ80Addr = Sonic3kSmpsConstants.Z80_MUSIC_PTR_LIST;     // 0x1618
        int relOffset = listZ80Addr - additionalBase;

        if (relOffset < 0 || relOffset + 2 > z80AdditionalData.length) {
            LOGGER.warning("Music pointer list offset 0x" + Integer.toHexString(relOffset)
                    + " exceeds additional data size.");
            return;
        }

        int maxEntries = 0x60;
        musicPointers = new int[maxEntries];

        for (int i = 0; i < maxEntries; i++) {
            int entryOffset = relOffset + (i * 2);
            if (entryOffset + 1 >= z80AdditionalData.length) {
                break;
            }
            musicPointers[i] = readLE16(z80AdditionalData, entryOffset);
        }

        // Log first 5 entries for diagnostics
        StringBuilder sb = new StringBuilder("S3K music pointer list (first 5): ");
        for (int i = 0; i < Math.min(5, maxEntries); i++) {
            if (i > 0) sb.append(", ");
            sb.append("[").append(i).append("]=0x").append(Integer.toHexString(musicPointers[i]));
        }
        LOGGER.info(sb.toString());
        LOGGER.info("Parsed S3K music pointer list: " + maxEntries + " entries.");
    }

    private void parseSfxPointerList() {
        int additionalBase = Sonic3kSmpsConstants.Z80_GENERAL_PTR_LIST;
        int listZ80Addr = Sonic3kSmpsConstants.Z80_SFX_PTR_LIST;
        int relOffset = listZ80Addr - additionalBase;

        if (relOffset < 0 || relOffset + 2 > z80AdditionalData.length) {
            LOGGER.warning("SFX pointer list offset 0x" + Integer.toHexString(relOffset)
                    + " exceeds additional data size.");
            return;
        }

        int maxEntries = Sonic3kSfx.ID_MAX - Sonic3kSfx.ID_BASE + 1; // 0x60 entries
        sfxPointers = new int[maxEntries];

        for (int i = 0; i < maxEntries; i++) {
            int entryOffset = relOffset + (i * 2);
            if (entryOffset + 1 >= z80AdditionalData.length) {
                break;
            }
            sfxPointers[i] = readLE16(z80AdditionalData, entryOffset);
        }

        LOGGER.fine("Parsed S3K SFX pointer list: " + maxEntries + " entries.");
    }

    /**
     * Parses music bank and pointer tables from the S3 standalone Z80 driver.
     * The S3 driver is stored uncompressed at ROM {@code 0x2E6000} in the
     * combined S3&K ROM (S3 standalone offset 0x0E6000 + S3 ROM offset 0x200000).
     * Its tables use the same format as the S&K driver
     * but at different Z80 RAM offsets and with 50 entries instead of 51.
     */
    private void parseS3MusicTables() {
        int driverSize = Sonic3kSmpsConstants.S3_Z80_MUSIC_PTR_LIST + (Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT * 2) + 16;

        try {
            if (Sonic3kSmpsConstants.S3_Z80_DRIVER_ADDR + Sonic3kSmpsConstants.S3_ROM_OFFSET_IN_COMBINED + driverSize > rom.getSize()) {
                LOGGER.warning("S3 driver region exceeds ROM size, skipping S3 table parsing.");
                return;
            }
            byte[] s3Driver = rom.readBytes(Sonic3kSmpsConstants.S3_Z80_DRIVER_ADDR + Sonic3kSmpsConstants.S3_ROM_OFFSET_IN_COMBINED, driverSize);

            // Parse S3 music bank list at Z80 offset 0x0B48 (1-byte entries).
            // S3 uses bankswitchToMusicS3: only the low 4 bits of the bank byte
            // are used, and bit 4 of the 9-bit bank register is always set.
            // The resulting address is within the S3 standalone ROM (0x080000-0x0F8000).
            // In the combined S3&K ROM, S3 data is at offset 0x200000.
            s3MusicBankAddresses = new int[Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT];
            for (int i = 0; i < Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT; i++) {
                int entryOffset = Sonic3kSmpsConstants.S3_Z80_MUSIC_BANK_LIST + i;
                if (entryOffset >= s3Driver.length) break;
                int bankByte = s3Driver[entryOffset] & 0xFF;
                int s3Bank = ((bankByte & 0x0F) | 0x10) << 15; // bankswitchToMusicS3 formula
                s3MusicBankAddresses[i] = s3Bank + Sonic3kSmpsConstants.S3_ROM_OFFSET_IN_COMBINED;
            }

            // Parse S3 music pointer list at Z80 offset 0x161A (2-byte LE entries)
            s3MusicPointers = new int[Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT];
            for (int i = 0; i < Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT; i++) {
                int entryOffset = Sonic3kSmpsConstants.S3_Z80_MUSIC_PTR_LIST + (i * 2);
                if (entryOffset + 1 >= s3Driver.length) break;
                s3MusicPointers[i] = readLE16(s3Driver, entryOffset);
            }

            // Log differences between S3 and S&K tables
            logS3SkDifferences();

            LOGGER.info("Parsed S3 music tables: " + Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT + " entries from ROM 0x"
                    + Integer.toHexString(Sonic3kSmpsConstants.S3_Z80_DRIVER_ADDR + Sonic3kSmpsConstants.S3_ROM_OFFSET_IN_COMBINED));
        } catch (IOException e) {
            LOGGER.warning("Failed to read S3 driver data: " + e.getMessage());
        }
    }

    private void logS3SkDifferences() {
        if (s3MusicBankAddresses == null || s3MusicPointers == null
                || musicBankAddresses == null || musicPointers == null) {
            return;
        }

        int compareCount = Math.min(Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT, musicBankAddresses.length);
        compareCount = Math.min(compareCount, musicPointers.length);

        for (int i = 0; i < compareCount; i++) {
            int skBank = musicBankAddresses[i];
            int s3Bank = s3MusicBankAddresses[i];
            int skPtr = musicPointers[i];
            int s3Ptr = s3MusicPointers[i];

            if (skBank != s3Bank || skPtr != s3Ptr) {
                int musicId = i + 1;
                int skRom = (skBank != 0 && skPtr != 0) ? skBank + (skPtr & Sonic3kSmpsConstants.Z80_BANK_MASK) : 0;
                int s3Rom = (s3Bank != 0 && s3Ptr != 0) ? s3Bank + (s3Ptr & Sonic3kSmpsConstants.Z80_BANK_MASK) : 0;
                LOGGER.info("S3/S&K table difference at index " + i + " (music ID 0x"
                        + Integer.toHexString(musicId) + "): S&K ROM=0x"
                        + Integer.toHexString(skRom) + " S3 ROM=0x"
                        + Integer.toHexString(s3Rom));
            }
        }

        if (musicBankAddresses.length > Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT) {
            LOGGER.info("S&K driver has " + (musicBankAddresses.length - Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT)
                    + " extra entries beyond S3 table (IDs 0x"
                    + Integer.toHexString(Sonic3kSmpsConstants.S3_Z80_MUSIC_COUNT + 1) + "+).");
        }
    }

    private void loadPsgEnvelopes() {
        psgEnvelopes = new HashMap<>();

        if (z80AdditionalData == null) {
            return;
        }

        int additionalBase = Sonic3kSmpsConstants.Z80_GENERAL_PTR_LIST;
        int listZ80Addr = Sonic3kSmpsConstants.Z80_PSG_PTR_LIST;        // 0x1387
        int relOffset = listZ80Addr - additionalBase;

        if (relOffset < 0 || relOffset + 2 > z80AdditionalData.length) {
            LOGGER.warning("PSG pointer list not available.");
            return;
        }

        // Read PSG envelope pointers (2 bytes each, LE)
        int maxEnvelopes = 40; // generous upper bound
        for (int i = 1; i <= maxEnvelopes; i++) {
            int entryOffset = relOffset + ((i - 1) * 2);
            if (entryOffset + 1 >= z80AdditionalData.length) {
                break;
            }

            int ptr = readLE16(z80AdditionalData, entryOffset);
            if (ptr == 0) {
                continue;
            }

            // Resolve the pointer within the additional data
            int envRelOffset = ptr - additionalBase;
            if (envRelOffset < 0 || envRelOffset >= z80AdditionalData.length) {
                // Try as main driver offset
                if (ptr < z80Driver.length) {
                    envRelOffset = ptr;
                    loadEnvelopeFromData(i, z80Driver, envRelOffset, psgEnvelopes);
                    continue;
                }
                continue;
            }

            loadEnvelopeFromData(i, z80AdditionalData, envRelOffset, psgEnvelopes);
        }

        LOGGER.info("Loaded " + psgEnvelopes.size() + " S3K PSG envelopes.");
    }

    private void loadModEnvelopes() {
        modEnvelopes = new HashMap<>();

        if (z80AdditionalData == null) {
            return;
        }

        int additionalBase = Sonic3kSmpsConstants.Z80_GENERAL_PTR_LIST;
        int listZ80Addr = Sonic3kSmpsConstants.Z80_MOD_PTR_LIST;        // 0x130E
        int relOffset = listZ80Addr - additionalBase;

        if (relOffset < 0 || relOffset + 2 > z80AdditionalData.length) {
            LOGGER.warning("Modulation pointer list not available.");
            return;
        }

        // Pointers.txt: "Mod. Pointer List: 130E (W, 3C)" => 0x3C word entries.
        int maxEnvelopes = 0x3C;
        for (int i = 1; i <= maxEnvelopes; i++) {
            int entryOffset = relOffset + ((i - 1) * 2);
            if (entryOffset + 1 >= z80AdditionalData.length) {
                break;
            }

            int ptr = readLE16(z80AdditionalData, entryOffset);
            if (ptr == 0) {
                continue;
            }

            int envRelOffset = ptr - additionalBase;
            if (envRelOffset < 0 || envRelOffset >= z80AdditionalData.length) {
                // Fallback for pointers into the main driver blob.
                if (z80Driver != null && ptr < z80Driver.length) {
                    loadEnvelopeFromData(i, z80Driver, ptr, modEnvelopes);
                    continue;
                }
                continue;
            }

            loadEnvelopeFromData(i, z80AdditionalData, envRelOffset, modEnvelopes);
        }

        LOGGER.info("Loaded " + modEnvelopes.size() + " S3K modulation envelopes.");
    }

    private void loadEnvelopeFromData(int id, byte[] sourceData, int offset, Map<Integer, byte[]> target) {
        // Read envelope bytes until a terminator command (0x80-0x84)
        byte[] buffer = new byte[256];
        int len = 0;

        for (int j = 0; j < 256 && (offset + j) < sourceData.length; j++) {
            int b = sourceData[offset + j] & 0xFF;
            buffer[len++] = (byte) b;

            if (b == 0x80 || b == 0x81 || b == 0x83) {
                break; // RESET, HOLD, VOLSTOP_MODHOLD
            } else if (b == 0x82 || b == 0x84) {
                // LOOP (82) or CHG_MULT (84) - takes a parameter byte
                j++;
                if (offset + j < sourceData.length) {
                    buffer[len++] = sourceData[offset + j];
                }
                if (b == 0x82) break; // LOOP terminates
            }
        }

        if (len > 0) {
            byte[] env = new byte[len];
            System.arraycopy(buffer, 0, env, 0, len);
            target.put(id, env);
        }
    }

    private void loadGlobalInstrumentTable() {
        if (z80AdditionalData == null) {
            return;
        }

        int additionalBase = Sonic3kSmpsConstants.Z80_GENERAL_PTR_LIST;
        int tableZ80Addr = Sonic3kSmpsConstants.Z80_GLOBAL_INSTRUMENT_TABLE; // 0x17D8
        int relOffset = tableZ80Addr - additionalBase;

        if (relOffset < 0 || relOffset >= z80AdditionalData.length) {
            LOGGER.warning("Global instrument table not available at offset 0x"
                    + Integer.toHexString(relOffset));
            return;
        }

        // Read the remaining data from this point as the instrument table.
        // Voices are 25 bytes each; read as many complete voices as available.
        int available = z80AdditionalData.length - relOffset;
        int voiceCount = available / 25;
        int tableSize = voiceCount * 25;

        if (tableSize > 0) {
            globalVoiceData = new byte[tableSize];
            System.arraycopy(z80AdditionalData, relOffset, globalVoiceData, 0, tableSize);
            LOGGER.info("Loaded S3K global instrument table: " + voiceCount
                    + " voices (" + tableSize + " bytes).");
        }
    }

    // -----------------------------------------------------------------------
    // Bank loading
    // -----------------------------------------------------------------------

    private byte[] loadBank(int bankAddr) {
        if (bankCache.containsKey(bankAddr)) {
            return bankCache.get(bankAddr);
        }
        try {
            int size = (int) Math.min(Sonic3kSmpsConstants.Z80_BANK_BASE, rom.getSize() - bankAddr);
            if (size <= 0) {
                bankCache.put(bankAddr, null);
                return null;
            }
            byte[] bank = rom.readBytes(bankAddr, size);
            bankCache.put(bankAddr, bank);
            return bank;
        } catch (IOException e) {
            LOGGER.warning("Failed to load bank at 0x" + Integer.toHexString(bankAddr)
                    + ": " + e.getMessage());
            bankCache.put(bankAddr, null);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Size calculation helpers
    // -----------------------------------------------------------------------

    private int calculateMusicDataSize(int musicIndex, int romAddr, int bankAddr) throws IOException {
        int bankEnd = bankAddr + Sonic3kSmpsConstants.Z80_BANK_BASE; // End of 32KB bank

        // Try to find the next music entry in the same bank
        if (musicPointers != null && musicBankAddresses != null) {
            int bestNextAddr = bankEnd;
            for (int i = 0; i < musicPointers.length; i++) {
                if (i == musicIndex) continue;
                if (i < musicBankAddresses.length && musicBankAddresses[i] == bankAddr) {
                    int candidatePtr = musicPointers[i];
                    if (candidatePtr != 0) {
                        int candidateRom = bankAddr + (candidatePtr & Sonic3kSmpsConstants.Z80_BANK_MASK);
                        if (candidateRom > romAddr && candidateRom < bestNextAddr) {
                            bestNextAddr = candidateRom;
                        }
                    }
                }
            }

            int size = bestNextAddr - romAddr;
            if (size > 0 && size <= MAX_BLOB_SIZE) {
                return Math.min(size, (int) (rom.getSize() - romAddr));
            }
        }

        // Fallback: read to end of bank
        int size = bankEnd - romAddr;
        if (size <= 0 || size > MAX_BLOB_SIZE) {
            size = MAX_BLOB_SIZE;
        }
        return Math.min(size, (int) (rom.getSize() - romAddr));
    }

    private int calculateS3MusicDataSize(int musicIndex, int romAddr, int bankAddr) throws IOException {
        int bankEnd = bankAddr + Sonic3kSmpsConstants.Z80_BANK_BASE;

        if (s3MusicPointers != null && s3MusicBankAddresses != null) {
            int bestNextAddr = bankEnd;
            for (int i = 0; i < s3MusicPointers.length; i++) {
                if (i == musicIndex) continue;
                if (i < s3MusicBankAddresses.length && s3MusicBankAddresses[i] == bankAddr) {
                    int candidatePtr = s3MusicPointers[i];
                    if (candidatePtr != 0) {
                        int candidateRom = bankAddr + (candidatePtr & Sonic3kSmpsConstants.Z80_BANK_MASK);
                        if (candidateRom > romAddr && candidateRom < bestNextAddr) {
                            bestNextAddr = candidateRom;
                        }
                    }
                }
            }

            int size = bestNextAddr - romAddr;
            if (size > 0 && size <= MAX_BLOB_SIZE) {
                return Math.min(size, (int) (rom.getSize() - romAddr));
            }
        }

        int size = bankEnd - romAddr;
        if (size <= 0 || size > MAX_BLOB_SIZE) {
            size = MAX_BLOB_SIZE;
        }
        return Math.min(size, (int) (rom.getSize() - romAddr));
    }

    private int computeSfxLength(int tableIndex, int romOffset) {
        int bankEnd = Sonic3kSmpsConstants.SFX_BANK_BASE + Sonic3kSmpsConstants.Z80_BANK_BASE;

        // Find next SFX pointer
        if (sfxPointers != null) {
            for (int i = tableIndex + 1; i < sfxPointers.length; i++) {
                int ptr = sfxPointers[i];
                if (ptr != 0) {
                    int candidate = Sonic3kSmpsConstants.SFX_BANK_BASE + (ptr & Sonic3kSmpsConstants.Z80_BANK_MASK);
                    if (candidate > romOffset) {
                        int length = candidate - romOffset;
                        return Math.max(length, 16);
                    }
                }
            }
        }

        int length = bankEnd - romOffset;
        if (length <= 0 || length > (bankEnd - Sonic3kSmpsConstants.SFX_BANK_BASE)) {
            length = bankEnd - Sonic3kSmpsConstants.SFX_BANK_BASE;
        }
        return Math.max(length, 16);
    }

    // -----------------------------------------------------------------------
    // Byte reading helpers
    // -----------------------------------------------------------------------

    private static int readLE16(byte[] data, int offset) {
        if (offset + 1 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private int readLE16FromRom(int romAddr) throws IOException {
        int lo = rom.readByte(romAddr) & 0xFF;
        int hi = rom.readByte(romAddr + 1) & 0xFF;
        return lo | (hi << 8);
    }

    private static String hexDump(byte[] data, int offset, int length) {
        if (data == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        int end = Math.min(offset + length, data.length);
        for (int i = offset; i < end; i++) {
            if (i > offset) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
