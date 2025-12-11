package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.tools.DcmDecoder;
import uk.co.jamesj999.sonic.tools.SaxmanDecompressor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class Sonic2SmpsLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SmpsLoader.class.getName());
    private final Rom rom;
    private final SaxmanDecompressor decompressor = new SaxmanDecompressor();
    private final DcmDecoder dcmDecoder = new DcmDecoder();
    private final Map<Integer, Integer> musicMap = new HashMap<>();
    private final Map<String, Integer> sfxMap = new HashMap<>();
    private final Map<Integer, AbstractSmpsData> sfxCache = new HashMap<>();
    private final Map<Integer, String> sfxNames = new HashMap<>();

    public Sonic2SmpsLoader(Rom rom) {
        this.rom = rom;
        populateSfxNames();
        cacheAllSfx();
        // Known Sonic 2 final music offsets (ROM addresses, Saxman compressed)
        musicMap.put(0x00, 0x0F0002); // Continue
        musicMap.put(0x80, 0x0F84F6); // Casino Night 2P
        musicMap.put(0x81, 0x0F88C4); // Emerald Hill
        musicMap.put(0x82, 0x0F8DEE); // Metropolis
        musicMap.put(0x83, 0x0F917B); // Casino Night
        musicMap.put(0x84, 0x0F9664); // Mystic Cave
        musicMap.put(0x85, 0x0F9A3C); // Mystic Cave 2P
        musicMap.put(0x86, 0x0F9D69); // Aquatic Ruin
        musicMap.put(0x87, 0x0FA36B); // Death Egg
        musicMap.put(0x88, 0x0FA6ED); // Special Stage
        musicMap.put(0x89, 0x0FAAC4); // Options
        musicMap.put(0x8A, 0x0FAC3C); // Ending
        musicMap.put(0x8B, 0x0FB124); // Final battle
        musicMap.put(0x8C, 0x0FB3F7); // Chemical Plant
        musicMap.put(0x8D, 0x0FB81E); // Boss
        musicMap.put(0x8E, 0x0FBA6F); // Sky Chase
        musicMap.put(0x8F, 0x0FBD8C); // Oil Ocean
        musicMap.put(0x90, 0x0FC146); // Wing Fortress
        musicMap.put(0x91, 0x0FC480); // Emerald Hill 2P
        musicMap.put(0x92, 0x0FC824); // 2P Results
        musicMap.put(0x93, 0x0FCBBC); // Super Sonic
        musicMap.put(0x94, 0x0FCE74); // Hill Top
        musicMap.put(0x96, 0x0FD193); // Title
        musicMap.put(0x97, 0x0FD35E); // Stage Clear
        musicMap.put(0x99, 0x0F8359); // Invincibility
        musicMap.put(0x9B, 0x0F803C); // Hidden Palace
        musicMap.put(0xB5, 0x0FD48D); // 1-Up
        musicMap.put(0xB8, 0x0FD57A); // Game Over
        musicMap.put(0xBA, 0x0FD6C9); // Got an Emerald
        musicMap.put(0xBD, 0x0FD797); // Credits
        musicMap.put(0xDC, 0x0F823B); // Underwater Timing
        // SFX Map (Populate with discovered offsets)
        // Potential candidate for SFX: 0xFFEAD (FM=1)
        sfxMap.put("RING", 0xFFEAD);
    }

    private void populateSfxNames() {
        // Common Sonic 2 SFX
        sfxNames.put(0xA0, "Jump");
        sfxNames.put(0xA1, "Checkpoint");
        sfxNames.put(0xA2, "Spike Hit");
        sfxNames.put(0xA3, "Destroy Badnik");
        sfxNames.put(0xA4, "Skid");
        sfxNames.put(0xA5, "Slide");
        sfxNames.put(0xA6, "Spindash Rev");
        sfxNames.put(0xA7, "Roll");
        sfxNames.put(0xA8, "Splash");
        sfxNames.put(0xA9, "Spring");
        sfxNames.put(0xAA, "Teleport");
        sfxNames.put(0xAB, "Ring Loss");
        sfxNames.put(0xAC, "Shield");
        sfxNames.put(0xAD, "Bubble");
        sfxNames.put(0xAE, "Drown Warning");
        sfxNames.put(0xAF, "Drown");
        sfxNames.put(0xB0, "Death");
        sfxNames.put(0xB1, "Signpost");
        sfxNames.put(0xB2, "Fire");
        sfxNames.put(0xB3, "Crumble");
        sfxNames.put(0xB4, "Bumper");
        sfxNames.put(0xB5, "Ring");
        sfxNames.put(0xB6, "Spikes");
        sfxNames.put(0xB7, "Enter Special Stage");
        sfxNames.put(0xB8, "Register");
        sfxNames.put(0xB9, "Spring (Alt)");
        sfxNames.put(0xBA, "Switch");
        sfxNames.put(0xBB, "Break");
        sfxNames.put(0xBC, "Push");
        sfxNames.put(0xBD, "Air Bubble");
        sfxNames.put(0xBE, "Explosion");
        sfxNames.put(0xBF, "Score Add");
        sfxNames.put(0xC0, "Coin");
    }

    public void cacheAllSfx() {
        LOGGER.info("Caching all SFX...");
        System.out.println("Scanning for SFX from 0xA0 to 0xEF...");
        // Scan 0xA0 to 0xEF
        for (int id = 0xA0; id <= 0xEF; id++) {
            AbstractSmpsData sfx = loadSfxInternal(id);
            if (sfx != null) {
                sfxCache.put(id, sfx);
                if (!sfxNames.containsKey(id)) {
                    sfxNames.put(id, "SFX " + Integer.toHexString(id).toUpperCase());
                }
            }
        }
        String msg = "Cached " + sfxCache.size() + " SFX.";
        LOGGER.info(msg);
        System.out.println(msg);
    }

    public Map<Integer, String> getSfxList() {
        return new HashMap<>(sfxNames);
    }

    public java.util.Set<Integer> getAvailableSfxIds() {
        return new java.util.HashSet<>(sfxCache.keySet());
    }

    public AbstractSmpsData loadMusic(int musicId) {
        int offset = findMusicOffset(musicId);
        if (offset == -1) {
            LOGGER.fine("Music ID " + Integer.toHexString(musicId) + " not in map/flags.");
            return null;
        }
        // Sonic 2 music loaded at Z80 0x1380
        AbstractSmpsData data = loadSmps(offset, 0x1380);
        if (data instanceof Sonic2SmpsData) {
            ((Sonic2SmpsData) data).setPsgEnvelopes(loadPsgEnvelopes());
        }
        if (data != null) {
            data.setId(musicId);
            if (musicId >= 0x81) {
                try {
                    int flagsAddr = 0x0ECF36 + (musicId - 0x81);
                    int flags = rom.readByte(flagsAddr) & 0xFF;
                    data.setPalSpeedupDisabled((flags & 0x40) != 0);
                } catch (Exception e) {
                    LOGGER.fine("Failed to read flags for music ID " + Integer.toHexString(musicId));
                }
            }
        }
        return data;
    }

    /**
     * Returns the ROM offset for a given music ID using the hard map first, then the flag table.
     * Exposed for debug tools (sound test).
     */
    public int findMusicOffset(int musicId) {
        Integer mapped = musicMap.get(musicId);
        return mapped != null ? mapped : resolveMusicOffset(musicId);
    }

    public AbstractSmpsData loadSfx(String name) {
        Integer offset = sfxMap.get(name);
        if (offset != null) {
            return loadSmps(offset, 0x1C00);
        }
        // Try parsing as hex ID (e.g. "A0")
        try {
            int id = Integer.parseInt(name, 16);
            if (id >= 0xA0 && id <= 0xF0) {
                return loadSfx(id);
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    public AbstractSmpsData loadSfx(int sfxId) {
        if (sfxCache.containsKey(sfxId)) {
            return sfxCache.get(sfxId);
        }
        AbstractSmpsData data = loadSfxInternal(sfxId);
        if (data != null) {
            sfxCache.put(sfxId, data);
        }
        return data;
    }

    private AbstractSmpsData loadSfxInternal(int sfxId) {
        // SFX Pointer Table at 0xFEE91.
        // IDs start at 0xA0.
        // Pointers are 2 bytes (LE), relative to bank start 0xF8000 (Z80 0x8000).
        if (sfxId < 0xA0) return null;

        try {
            int index = sfxId - 0xA0;
            int tableAddr = 0xFEE91;
            int entryAddr = tableAddr + (index * 2);

            int lo = rom.readByte(entryAddr) & 0xFF;
            int hi = rom.readByte(entryAddr + 1) & 0xFF;
            int ptr = lo | (hi << 8); // Z80 pointer (e.g. 0x8xxx)

            if (ptr == 0) return null;

            // Map Z80 0x8000-0xFFFF to ROM 0xF8000-0xFFFFF
            int romOffset = 0xF8000 + (ptr & 0x7FFF);

            // SFX usually loaded at Z80 0x1C00 or dynamic
            // Try loading as UNCOMPRESSED first
            try {
                byte[] raw = rom.readBytes(romOffset, 2048);
                Sonic2SmpsData sfx = new Sonic2SmpsData(raw, 0x1C00);
                if (isValidSfx(sfx)) {
                    return sfx;
                }
            } catch (Exception e) {
                LOGGER.fine("Failed to load uncompressed SFX at " + Integer.toHexString(romOffset));
            }

            return loadSmps(romOffset, 0x1C00);
        } catch (Exception e) {
            LOGGER.severe("Failed to load SFX ID " + Integer.toHexString(sfxId));
            e.printStackTrace();
            return null;
        }
    }

    private boolean isValidSfx(AbstractSmpsData data) {
        // Basic validation: Channels should be within reasonable limits for Genesis
        // FM: 0-6, PSG: 0-4
        int fm = data.getChannels();
        int psg = data.getPsgChannels();
        if (fm > 7 || psg > 4) return false;
        // Also checks if header was parsed at all
        if (fm == 0 && psg == 0 && data.getVoicePtr() == 0) return false;

        // Check pointers
        int z80Start = data.getZ80StartAddress();
        int dataLen = data.getData().length;

        if (!isValidPointer(data.getVoicePtr(), z80Start, dataLen)) return false;
        if (data.getDacPointer() != 0 && !isValidPointer(data.getDacPointer(), z80Start, dataLen)) return false;

        if (data.getFmPointers() != null) {
            for (int ptr : data.getFmPointers()) {
                if (ptr != 0 && !isValidPointer(ptr, z80Start, dataLen)) return false;
            }
        }
        if (data.getPsgPointers() != null) {
            for (int ptr : data.getPsgPointers()) {
                if (ptr != 0 && !isValidPointer(ptr, z80Start, dataLen)) return false;
            }
        }

        return true;
    }

    private boolean isValidPointer(int ptr, int start, int len) {
        if (ptr == 0) return true;
        int offset = ptr;
        if (start > 0) {
            // If pointers are absolute Z80 addresses, they must map to our buffer
            if (ptr < start) return false;
            offset = ptr - start;
        }
        return offset >= 0 && offset < len;
    }

    /**
     * Sonic 2 final: music flags list at 0xECF36, pointer banks at 0xF0000/0xF8000.
     * Flag bits: 0-4 = pointer index, bit5 = uncompressed (1=uncompressed), bit7 = bank (0=0xF0000,1=0xF8000).
     */
    private int resolveMusicOffset(int musicId) {
        // Prefer deriving from ROM.
        int romOffset = resolveMusicOffsetFromRom(musicId);
        if (romOffset != -1) return romOffset;

        // Fallback to legacy map
        Integer mapped = musicMap.get(musicId);
        if (mapped != null) return mapped;
        return -1;
    }

    private int resolveMusicOffsetFromRom(int musicId) {
        // Known music IDs start at 0x81; map ID to flag index by subtracting 0x81.
        if (musicId < 0x81) return -1;
        int flagIndex = musicId - 0x81;
        int flagsAddr = 0x0ECF36 + flagIndex;
        try {
            int flags = rom.readByte(flagsAddr) & 0xFF;
            int ptrId = flags & 0x1F;
            boolean uncompressed = (flags & 0x20) != 0;
            int bankBase = ((flags & 0x80) != 0) ? 0x0F8000 : 0x0F0000;
            // Pointer-to-pointer table at 0x0EC810 (driver relocates this in RAM); treat as ROM address here.
            int ptrToPtrLo = rom.readByte(0x0EC810) & 0xFF;
            int ptrToPtrHi = rom.readByte(0x0EC810 + 1) & 0xFF;
            int ptrTableBase = (ptrToPtrLo << 8) | ptrToPtrHi;
            if (ptrTableBase == 0) return -1;
            int ptrTableEntry = ptrTableBase + (ptrId * 2);
            int lo = rom.readByte(ptrTableEntry) & 0xFF;
            int hi = rom.readByte(ptrTableEntry + 1) & 0xFF;
            int ptr = (lo << 8) | hi;
            int offset = bankBase + ptr;
            if (ptr == 0) return -1;
            if (uncompressed) {
                // We assume compressed; fallback to hard map if needed
                LOGGER.fine("Music " + Integer.toHexString(musicId) + " flagged uncompressed; using raw offset " + Integer.toHexString(offset));
            }
            return offset;
        } catch (Exception e) {
            return -1;
        }
    }

    public AbstractSmpsData loadSmps(int offset) {
        // Default fallback: assume ROM mapping or unknown
        return loadSmps(offset, 0x8000 | (offset & 0x7FFF));
    }

    public AbstractSmpsData loadSmps(int offset, int z80Addr) {
        try {
            // SMPS Z80 data uses little-endian Saxman size headers.
            int b1 = rom.readByte(offset) & 0xFF;
            int b2 = rom.readByte(offset + 1) & 0xFF;
            int sizeLe = (b1) | (b2 << 8);
            int sizeBe = (b1 << 8) | b2; // fallback only if LE is zero/invalid
            int maxAvail = (int) Math.max(0L, rom.getSize() - offset - 2L);

            int compressedSize = (sizeLe > 0 && sizeLe <= maxAvail)
                    ? sizeLe
                    : (sizeBe > 0 && sizeBe <= maxAvail ? sizeBe : maxAvail);

            byte[] compressed = readCompressed(offset, compressedSize, maxAvail);
            if (compressed == null) {
                LOGGER.severe("Failed to read SMPS at " + Integer.toHexString(offset));
                return null;
            }

            byte[] decompressed = decompressor.decompress(compressed, true);
            LOGGER.info("Decompressed SMPS at " + Integer.toHexString(offset) + ". Size: " + decompressed.length);
            return new Sonic2SmpsData(decompressed, z80Addr);
        } catch (Exception e) {
            LOGGER.severe("Failed to load SMPS at " + Integer.toHexString(offset));
            e.printStackTrace();
            return null;
        }
    }

    private byte[] readCompressed(int offset, int sizeHeader, int maxAvail) {
        if (sizeHeader <= 0) return null;
        int size = Math.min(sizeHeader, maxAvail);
        if (size <= 0) return null;
        try {
            return rom.readBytes(offset, size + 2);
        } catch (IOException e) {
            LOGGER.severe("Failed to read ROM bytes at " + Integer.toHexString(offset));
            return null;
        }
    }

    private Map<Integer, byte[]> loadPsgEnvelopes() {
        Map<Integer, byte[]> envelopes = new HashMap<>();
        // PSG Envelopes at 0x0F2E5C (Pointer Table)
        int tableAddr = 0x0F2E5C;
        int bankBase = 0x0F0000;

        try {
            // Read 16 entries
            for (int id = 1; id <= 16; id++) {
                int ptrAddr = tableAddr + (id - 1) * 2;
                int p1 = rom.readByte(ptrAddr) & 0xFF;
                int p2 = rom.readByte(ptrAddr + 1) & 0xFF;
                int ptr = p1 | (p2 << 8);

                if (ptr == 0) continue;

                // Map Z80 0x8000-0xFFFF to ROM
                int offset = ptr & 0x7FFF;
                int romAddr = bankBase + offset;

                byte[] buffer = new byte[256];
                int len = 0;
                for (int i = 0; i < 256; i++) {
                    int b = rom.readByte(romAddr + i) & 0xFF;
                    buffer[i] = (byte) b;
                    len++;
                    // 0x80 = Reset/End, 0x81 = Hold, 0x83 = Stop
                    if (b == 0x80 || b == 0x81 || b == 0x83) {
                        break;
                    } else if (b == 0x82 || b == 0x84) {
                        // Loop (82) or Multiplier (84) takes a parameter
                        i++;
                        b = rom.readByte(romAddr + i) & 0xFF;
                        buffer[i] = (byte) b;
                        len++;
                        if (buffer[i - 1] == (byte) 0x82) break;
                    }
                }

                byte[] env = new byte[len];
                System.arraycopy(buffer, 0, env, 0, len);
                envelopes.put(id, env);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to load PSG Envelopes from ROM");
        }
        return envelopes;
    }

    public DacData loadDacData() {
        Map<Integer, byte[]> samples = new HashMap<>();
        Map<Integer, DacData.DacEntry> mapping = new HashMap<>();

        try {
            int bankStart = 0xE0000;

            // 1. Load Samples from Pointer Table (81-87)
            // Pointers at 0xECF7C. Format: 4 bytes (Ptr LE, Len LE). If next byte is FF, skip it.
            int ptrTable = 0xECF7C;
            int offset = ptrTable;

            for (int i = 0; i < 7; i++) {
                // Read 4 bytes: Ptr(2), Len(2)
                int p1 = rom.readByte(offset) & 0xFF;
                int p2 = rom.readByte(offset + 1) & 0xFF;
                int ptr = p1 | (p2 << 8);

                int l1 = rom.readByte(offset + 2) & 0xFF;
                int l2 = rom.readByte(offset + 3) & 0xFF;
                int len = l1 | (l2 << 8);

                offset += 4;

                // Check for skip byte
                int nextByte = rom.readByte(offset) & 0xFF;
                if (nextByte == 0xFF) {
                    offset++;
                }

                if (ptr == 0 || len == 0) continue;

                // Read compressed
                int romAddr = bankStart + ptr;
                byte[] compressed = rom.readBytes(romAddr, len);
                byte[] pcm = dcmDecoder.decode(compressed);

                // Sample IDs correspond to 0x81 + i
                samples.put(0x81 + i, pcm);
            }

            // 2. Load Mapping from Master List (81-91)
            // Starts at 0xECF9C. Format: 2 bytes (SampleID, Rate). If next byte is FF, skip it.
            int mapAddr = 0xECF9C;
            offset = mapAddr;

            // Sonic 2 Master List covers 81-91 (17 entries)
            for (int i = 0; i < 17; i++) {
                int sampleId = rom.readByte(offset) & 0xFF;
                int rate = rom.readByte(offset + 1) & 0xFF;

                offset += 2;

                // Check for skip byte
                int nextByte = rom.readByte(offset) & 0xFF;
                if (nextByte == 0xFF) {
                    offset++;
                }

                if (sampleId == 0xFF) continue;

                int noteId = 0x81 + i;
                mapping.put(noteId, new DacData.DacEntry(sampleId, rate));
            }

            return new DacData(samples, mapping);
        } catch (Exception e) {
            LOGGER.severe("Failed to load DAC Data");
            e.printStackTrace();
            return new DacData(new HashMap<>(), new HashMap<>());
        }
    }
}
