package com.openggf.game.sonic1.audio.smps;

import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.audio.Sonic1SmpsConstants;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.AbstractSmpsLoader;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.Sonic1SmpsData;
import com.openggf.data.Rom;
import com.openggf.data.compression.DcmDecoder;
import com.openggf.data.compression.KosinskiReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SMPS data loader for Sonic 1.
 *
 * <p>Key differences from Sonic 2:
 * <ul>
 *   <li>Pointer tables use 32-bit big-endian absolute ROM addresses (4 bytes per entry).
 *       Sonic 2 uses 16-bit little-endian Z80-relative pointers.</li>
 *   <li>Music and SFX data is stored uncompressed in ROM. Sonic 2 uses Saxman compression
 *       for music.</li>
 *   <li>Within a song, voice and channel pointers are 16-bit big-endian offsets relative
 *       to the song start address. Sonic 2 uses absolute Z80 addresses.</li>
 *   <li>PSG envelope data is loaded from a 9-entry pointer table in ROM.</li>
 * </ul>
 */
public class Sonic1SmpsLoader extends AbstractSmpsLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic1SmpsLoader.class.getName());

    /** Maximum reasonable size for a single SMPS song/SFX blob. */
    private static final int MAX_BLOB_SIZE = 0x4000; // 16 KB safety limit

    private byte[][] psgEnvelopes;
    private byte[] zeroAddressVoiceBank;

    public Sonic1SmpsLoader(Rom rom) {
        super(rom);
        try {
            zeroAddressVoiceBank = rom.readBytes(0, 0x100);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "cannot read the S1 ROM vector area", error);
        }
        loadPsgEnvelopes();
    }

    @Override
    protected boolean isValidSfxId(int id) {
        return (id >= Sonic1Sfx.ID_BASE && id <= Sonic1Sfx.NORMAL_ID_MAX)
                || (id >= Sonic1SmpsConstants.SPECIAL_SFX_ID_BASE
                    && id < Sonic1SmpsConstants.SPECIAL_SFX_ID_BASE + Sonic1SmpsConstants.SPECIAL_SFX_COUNT);
    }

    @Override
    public AbstractSmpsData loadMusic(int musicId) {
        if (musicId < Sonic1Music.ID_BASE || musicId > Sonic1Music.ID_MAX) {
            LOGGER.fine("Music ID 0x" + Integer.toHexString(musicId) + " out of range.");
            return null;
        }

        AbstractSmpsData cached = musicCache.get(musicId);
        if (cached != null) {
            return cached;
        }

        try {
            int index = musicId - Sonic1Music.ID_BASE;
            int romAddr = rom.read32BitAddr(Sonic1SmpsConstants.MUSIC_PTR_TABLE_ADDR + index * 4);
            if (romAddr <= 0 || romAddr >= rom.getSize()) {
                LOGGER.warning("Invalid music pointer for ID 0x" + Integer.toHexString(musicId)
                        + ": 0x" + Integer.toHexString(romAddr));
                return null;
            }

            int dataSize = calculateMusicDataSize(index, romAddr);
            byte[] raw = rom.readBytes(romAddr, dataSize);

            LOGGER.info("Loaded S1 music ID 0x" + Integer.toHexString(musicId)
                    + " at ROM 0x" + Integer.toHexString(romAddr)
                    + " (" + raw.length + " bytes)");

            Sonic1SmpsData data = new Sonic1SmpsData(raw, 0);
            data.setPsgEnvelopes(psgEnvelopes);
            data.setId(musicId);
            musicCache.put(musicId, data);
            return data;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load S1 music ID 0x" + Integer.toHexString(musicId), e);
            return null;
        }
    }

    @Override
    public AbstractSmpsData loadSfx(int sfxId) {
        // Normal-table upper bound is Sonic1Sfx.NORMAL_ID_MAX (0xCF), NOT
        // ID_MAX (0xD0): ROM's PlaySoundID dispatches the normal and special
        // SFX pointer tables as disjoint ranges (see NORMAL_ID_MAX's javadoc).
        // Using ID_MAX here previously let 0xD0 fall through to the normal
        // table -- it only "worked" because this ROM's SFX_PTR_TABLE_ADDR +
        // SFX_COUNT*4 happens to equal SPECIAL_SFX_PTR_TABLE_ADDR by
        // coincidence, reading the same pointer via a less-precise fallback
        // blob-size calculation than the special path uses.
        if (sfxId < Sonic1Sfx.ID_BASE || sfxId > Sonic1Sfx.NORMAL_ID_MAX) {
            // Check special SFX range
            if (sfxId >= Sonic1SmpsConstants.SPECIAL_SFX_ID_BASE
                    && sfxId < Sonic1SmpsConstants.SPECIAL_SFX_ID_BASE + Sonic1SmpsConstants.SPECIAL_SFX_COUNT) {
                return loadSpecialSfx(sfxId);
            }
            LOGGER.fine("SFX ID 0x" + Integer.toHexString(sfxId) + " out of range.");
            return null;
        }

        AbstractSmpsData cached = sfxCache.get(sfxId);
        if (cached != null) {
            return cached;
        }

        try {
            int index = sfxId - Sonic1Sfx.ID_BASE;
            int romAddr = rom.read32BitAddr(Sonic1SmpsConstants.SFX_PTR_TABLE_ADDR + index * 4);
            if (romAddr <= 0 || romAddr >= rom.getSize()) {
                LOGGER.warning("Invalid SFX pointer for ID 0x" + Integer.toHexString(sfxId)
                        + ": 0x" + Integer.toHexString(romAddr));
                return null;
            }

            int dataSize = calculateSfxDataSize(index, romAddr);
            byte[] raw = rom.readBytes(romAddr, dataSize);

            LOGGER.info("Loaded S1 SFX ID 0x" + Integer.toHexString(sfxId)
                    + " at ROM 0x" + Integer.toHexString(romAddr)
                    + " (" + raw.length + " bytes)");

            Sonic1SfxData data = new Sonic1SfxData(raw, 0);
            data.setPsgEnvelopes(psgEnvelopes);
            data.setZeroAddressVoiceBank(zeroAddressVoiceBank);
            data.setId(sfxId);
            sfxCache.put(sfxId, data);
            return data;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load S1 SFX ID 0x" + Integer.toHexString(sfxId), e);
            return null;
        }
    }

    @Override
    public DacData loadDacData() {
        try {
            // 1. Decompress the Kosinski-compressed Z80 DAC driver from ROM
            byte[] compressed = rom.readBytes(Sonic1SmpsConstants.DAC_DRIVER_ADDR, MAX_BLOB_SIZE);
            byte[] z80Binary = KosinskiReader.decompress(
                    Channels.newChannel(new ByteArrayInputStream(compressed)), false);

            LOGGER.info("Decompressed S1 Z80 DAC driver: " + z80Binary.length
                    + " bytes from ROM 0x" + Integer.toHexString(Sonic1SmpsConstants.DAC_DRIVER_ADDR));

            // 2. Parse zPCM_Table at Z80 offset 0x00D6: 3 entries x 8 bytes each
            //    Format per entry: startAddr(LE word), length(LE word), pitch(LE word), pad(2)
            Map<Integer, byte[]> samples = new HashMap<>();
            Map<Integer, DacData.DacEntry> mapping = new HashMap<>();
            DcmDecoder decoder = new DcmDecoder();

            int tableOffset = Sonic1SmpsConstants.DAC_PTR_TABLE_Z80_OFFSET;
            int[] sampleRates = new int[Sonic1SmpsConstants.DAC_SAMPLE_COUNT];

            for (int i = 0; i < Sonic1SmpsConstants.DAC_SAMPLE_COUNT; i++) {
                int entryBase = tableOffset + i * 8;
                if (entryBase + 6 > z80Binary.length) {
                    LOGGER.warning("Z80 binary too short for DAC entry " + i);
                    break;
                }

                // Little-endian 16-bit reads from decompressed Z80 data
                int startAddr = (z80Binary[entryBase] & 0xFF)
                        | ((z80Binary[entryBase + 1] & 0xFF) << 8);
                int sampleLen = (z80Binary[entryBase + 2] & 0xFF)
                        | ((z80Binary[entryBase + 3] & 0xFF) << 8);
                int pitch = (z80Binary[entryBase + 4] & 0xFF)
                        | ((z80Binary[entryBase + 5] & 0xFF) << 8);

                sampleRates[i] = pitch;

                if (startAddr == 0 || sampleLen == 0) {
                    LOGGER.fine("DAC sample " + i + " has zero addr/len, skipping");
                    continue;
                }
                if (startAddr + sampleLen > z80Binary.length) {
                    LOGGER.warning("DAC sample " + i + " extends beyond Z80 binary"
                            + " (addr=0x" + Integer.toHexString(startAddr)
                            + ", len=" + sampleLen
                            + ", z80size=" + z80Binary.length + ")");
                    // Clamp to available data
                    sampleLen = z80Binary.length - startAddr;
                    if (sampleLen <= 0) continue;
                }

                // Extract DPCM bytes and decode
                byte[] dpcmData = new byte[sampleLen];
                System.arraycopy(z80Binary, startAddr, dpcmData, 0, sampleLen);
                byte[] pcm = decoder.decode(dpcmData);

                int sampleId = Sonic1SmpsConstants.DAC_SAMPLE_ID_BASE + i;
                samples.put(sampleId, pcm);

                LOGGER.info("Loaded S1 DAC sample 0x" + Integer.toHexString(sampleId)
                        + ": " + sampleLen + " DPCM bytes -> " + pcm.length
                        + " PCM bytes, pitch=" + pitch);
            }

            // 3. Build note-to-sample mapping
            // 0x81 = Kick, 0x82 = Snare, 0x83 = Timpani (direct samples)
            for (int i = 0; i < Sonic1SmpsConstants.DAC_SAMPLE_COUNT; i++) {
                int noteId = Sonic1SmpsConstants.DAC_SAMPLE_ID_BASE + i;
                if (samples.containsKey(noteId)) {
                    mapping.put(noteId, new DacData.DacEntry(noteId, sampleRates[i]));
                }
            }

            // 4. Timpani pitch variants 0x88-0x8B
            //    Read 4 pitch modifier bytes from ROM at DAC_SAMPLE_RATE_TABLE_ADDR
            int timpaniSampleId = Sonic1SmpsConstants.DAC_SAMPLE_ID_BASE + 2; // 0x83
            for (int i = 0; i < 4; i++) {
                int pitchByte = rom.readByte(Sonic1SmpsConstants.DAC_SAMPLE_RATE_TABLE_ADDR + i) & 0xFF;
                if (pitchByte == 0xFF) continue; // invalid entry
                int noteId = 0x88 + i;
                mapping.put(noteId, new DacData.DacEntry(timpaniSampleId, pitchByte));

                LOGGER.fine("Timpani variant 0x" + Integer.toHexString(noteId)
                        + " -> sample 0x" + Integer.toHexString(timpaniSampleId)
                        + ", rate=" + pitchByte);
            }

            return new DacData(samples, mapping, 301); // S1 baseCycles = 301
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to load S1 DAC data", e);
            return null;
        }
    }

    /**
     * Returns the loaded PSG envelope data for external use.
     */
    public byte[][] getPsgEnvelopes() {
        return psgEnvelopes;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private AbstractSmpsData loadSpecialSfx(int sfxId) {
        AbstractSmpsData cached = sfxCache.get(sfxId);
        if (cached != null) {
            return cached;
        }

        try {
            int index = sfxId - Sonic1SmpsConstants.SPECIAL_SFX_ID_BASE;
            int romAddr = rom.read32BitAddr(Sonic1SmpsConstants.SPECIAL_SFX_PTR_TABLE_ADDR + index * 4);
            if (romAddr <= 0 || romAddr >= rom.getSize()) {
                LOGGER.warning("Invalid special SFX pointer for ID 0x"
                        + Integer.toHexString(sfxId));
                return null;
            }

            // Special SFX: use a generous read since there's only one entry
            int available = (int) Math.min(MAX_BLOB_SIZE, rom.getSize() - romAddr);
            byte[] raw = rom.readBytes(romAddr, available);

            Sonic1SfxData data = new Sonic1SfxData(raw, 0);
            data.setPsgEnvelopes(psgEnvelopes);
            data.setZeroAddressVoiceBank(zeroAddressVoiceBank);
            data.setId(sfxId);
            sfxCache.put(sfxId, data);
            return data;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load special SFX ID 0x" + Integer.toHexString(sfxId), e);
            return null;
        }
    }

    /**
     * Calculate music data size by reading the next entry's pointer.
     * For the last entry, uses the known SFX pointer table start as boundary.
     */
    private int calculateMusicDataSize(int index, int romAddr) throws IOException {
        int nextAddr;
        if (index < Sonic1SmpsConstants.MUSIC_COUNT - 1) {
            // Read the next music entry's pointer
            nextAddr = rom.read32BitAddr(Sonic1SmpsConstants.MUSIC_PTR_TABLE_ADDR + (index + 1) * 4);
            if (nextAddr > romAddr && nextAddr < romAddr + MAX_BLOB_SIZE) {
                return nextAddr - romAddr;
            }
        }

        // For the last entry or if next pointer is not usable, scan forward
        // through subsequent pointers to find a reasonable boundary.
        // As a fallback, use a generous read size.
        int bestBound = romAddr + MAX_BLOB_SIZE;
        for (int i = index + 1; i < Sonic1SmpsConstants.MUSIC_COUNT; i++) {
            int candidate = rom.read32BitAddr(Sonic1SmpsConstants.MUSIC_PTR_TABLE_ADDR + i * 4);
            if (candidate > romAddr && candidate < bestBound) {
                bestBound = candidate;
                break;
            }
        }

        int size = bestBound - romAddr;
        int available = (int) Math.min(size, rom.getSize() - romAddr);
        return Math.min(available, MAX_BLOB_SIZE);
    }

    /**
     * Calculate SFX data size by reading the next entry's pointer.
     * For the last entry, uses a generous read.
     */
    private int calculateSfxDataSize(int index, int romAddr) throws IOException {
        // Try next SFX pointer
        for (int i = index + 1; i < Sonic1SmpsConstants.SFX_COUNT; i++) {
            int candidate = rom.read32BitAddr(Sonic1SmpsConstants.SFX_PTR_TABLE_ADDR + i * 4);
            if (candidate > romAddr && candidate < romAddr + MAX_BLOB_SIZE) {
                return candidate - romAddr;
            }
        }

        // If no next SFX pointer found, try special SFX table
        if (Sonic1SmpsConstants.SPECIAL_SFX_COUNT > 0) {
            int candidate = rom.read32BitAddr(Sonic1SmpsConstants.SPECIAL_SFX_PTR_TABLE_ADDR);
            if (candidate > romAddr && candidate < romAddr + MAX_BLOB_SIZE) {
                return candidate - romAddr;
            }
        }

        // Fallback: read a reasonable chunk
        int available = (int) Math.min(MAX_BLOB_SIZE, rom.getSize() - romAddr);
        return Math.min(available, 0x800); // 2 KB for SFX
    }

    /**
     * Load PSG envelope data from ROM.
     *
     * <p>The PSG envelope pointer table has 9 entries of 4 bytes each (32-bit BE pointers).
     * Each envelope is a sequence of attenuation bytes terminated by 0x80 (HOLD).
     */
    private void loadPsgEnvelopes() {
        psgEnvelopes = new byte[9][];
        for (int i = 0; i < 9; i++) {
            try {
                int ptrAddr = Sonic1SmpsConstants.PSG_ENV_PTR_TABLE_ADDR + i * 4;
                int envAddr = rom.read32BitAddr(ptrAddr);
                if (envAddr <= 0 || envAddr >= rom.getSize()) {
                    LOGGER.fine("Invalid PSG envelope pointer " + i
                            + " at 0x" + Integer.toHexString(ptrAddr));
                    psgEnvelopes[i] = new byte[] { (byte) 0x80 }; // empty hold
                    continue;
                }

                List<Byte> bytes = new ArrayList<>();
                int offset = 0;
                while (offset < 256) { // safety limit
                    int b = rom.readByte(envAddr + offset) & 0xFF;
                    bytes.add((byte) b);
                    if (b == 0x80) {
                        break; // HOLD terminator
                    }
                    offset++;
                }

                psgEnvelopes[i] = new byte[bytes.size()];
                for (int j = 0; j < bytes.size(); j++) {
                    psgEnvelopes[i][j] = bytes.get(j);
                }

                LOGGER.fine("Loaded PSG envelope " + i + " from 0x"
                        + Integer.toHexString(envAddr) + " (" + bytes.size() + " bytes)");
            } catch (IOException e) {
                LOGGER.warning("Failed to load PSG envelope " + i);
                psgEnvelopes[i] = new byte[] { (byte) 0x80 }; // fallback
            }
        }
    }
}
