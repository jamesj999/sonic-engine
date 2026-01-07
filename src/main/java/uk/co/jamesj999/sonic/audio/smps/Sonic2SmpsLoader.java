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
        // These ROM addresses were discovered empirically and are correct.
        // The IDs here are what the game uses when requesting music.
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
        musicMap.put(0x8B, 0x0FB124); // Final Battle
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
        musicMap.put(0xB5, 0x0FD48D); // 1-Up (MUS_EXTRA_LIFE)
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
        sfxNames.put(0xA2, "Spike Pop");
        sfxNames.put(0xA3, "Death");
        sfxNames.put(0xA4, "Skid");
        sfxNames.put(0xA5, "Fire");
        sfxNames.put(0xA6, "Spike Hit");
        sfxNames.put(0xA7, "Sparkle");
        sfxNames.put(0xA8, "?");
        sfxNames.put(0xA9, "Reverse");
        sfxNames.put(0xAA, "Water Splash");
        sfxNames.put(0xAB, "Rustling");
        sfxNames.put(0xAC, "Boss Hit");
        sfxNames.put(0xAD, "Bubble");
        sfxNames.put(0xAE, "Projectile Launch");
        sfxNames.put(0xAF, "Shield");
        sfxNames.put(0xB0, "Mecha Sonic Buzz");
        sfxNames.put(0xB1, "SBZ Electric");
        sfxNames.put(0xB2, "Drowned");
        sfxNames.put(0xB3, "Jumping Fire");
        sfxNames.put(0xB4, "Bumper");
        sfxNames.put(0xB5, "Ring (Right Channel)");
        sfxNames.put(0xB6, "Fire Sound?");
        sfxNames.put(0xB7, "Rumble");
        sfxNames.put(0xB8, "Fire Pop?");
        sfxNames.put(0xB9, "Big Crumble");
        sfxNames.put(0xBA, "Special Stage Brick Hit (S1)");
        sfxNames.put(0xBB, "MCZ Bridge Move");
        sfxNames.put(0xBC, "Spindash Release");
        sfxNames.put(0xBD, "Projectile Pop");
        sfxNames.put(0xBE, "Spin");
        sfxNames.put(0xBF, "Continue");
        sfxNames.put(0xC0, "Slot Machine");
        sfxNames.put(0xC1, "Badnik Dead");
        sfxNames.put(0xC2, "Breath Chime");
        sfxNames.put(0xC3, "Enter Giant Ring");
        sfxNames.put(0xC4, "Projectile Pop");
        sfxNames.put(0xC5, "Score Tally End");
        sfxNames.put(0xC6, "Muh Rings!");
        sfxNames.put(0xC7, "MZ Chain Pull");
        sfxNames.put(0xC8, "MZ Fire");
        sfxNames.put(0xC9, "S1 Ending Points");
        sfxNames.put(0xCA, "Special Stage Load");
        sfxNames.put(0xCB, "Big Crumble");
        sfxNames.put(0xCC, "Spring");
        sfxNames.put(0xCD, "Switch Toggle");
        sfxNames.put(0xCE, "Ring (Left Channel)");
        sfxNames.put(0xCF, "Goal Post");
        sfxNames.put(0xD0, "Buzz");
        sfxNames.put(0xD1, "Open/Close Thing");
        sfxNames.put(0xD2, "Robot Fart");
        sfxNames.put(0xD3, "2P Goal");
        sfxNames.put(0xD4, "OOZ Gas-Burner Platform");
        sfxNames.put(0xD5, "Big Spikes");
        sfxNames.put(0xD6, "CNZ Elevator");
        sfxNames.put(0xD7, "MCZ Swinging Platform Collision");
        sfxNames.put(0xD8, "CNZ Pinball Target Hit");
        sfxNames.put(0xD9, "CNZ Triangle Bumper");
        sfxNames.put(0xDA, "Droplet");
        sfxNames.put(0xDB, "Scritch");
        sfxNames.put(0xDC, "Tink");
        sfxNames.put(0xDE, "Rotor Blade");
        sfxNames.put(0xDF, "Super Sonic Transform");
        sfxNames.put(0xE0, "Spindash Charge");
        sfxNames.put(0xE1, "HTZ Rumble");
        sfxNames.put(0xE2, "Shoot?");
        sfxNames.put(0xE3, "Pinball Flipper");
        sfxNames.put(0xE4, "?");
        sfxNames.put(0xE5, "?");
        sfxNames.put(0xE6, "?");
        sfxNames.put(0xE7, "?");
        sfxNames.put(0xE8, "MCZ Bridge Stop (Short)");
        sfxNames.put(0xE9, "MCZ Bridge Stop (Long)");
        sfxNames.put(0xEA, "Lazer Buzz");
        sfxNames.put(0xEB, "?");
        sfxNames.put(0xEC, "2P Teleport");
        sfxNames.put(0xED, "2P Zone Already Played");
        sfxNames.put(0xEE, "?");
        sfxNames.put(0xEF, "?");
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

        // The music ID itself contains flags (per Sonic Retro documentation):
        // bit 5 (0x20): Compression - 0=Saxman compressed, 0x20=uncompressed
        boolean uncompressed = (musicId & 0x20) != 0;

        AbstractSmpsData data;
        if (uncompressed) {
            // For uncompressed data, the Z80 address is the low 16 bits of the ROM offset.
            // Per Sonic Retro: Z80 pointers in uncompressed data are bank-relative.
            // Example: 1-Up at ROM 0xFD48D has Z80 pointer 0xD48D
            int z80Addr = offset & 0xFFFF;
            LOGGER.info("Loading uncompressed SMPS at " + Integer.toHexString(offset)
                    + " for music ID " + Integer.toHexString(musicId)
                    + " (Z80 base: " + Integer.toHexString(z80Addr) + ")");
            data = loadSmpsUncompressed(offset, z80Addr);
        } else {
            // Compressed music is decompressed and loaded at Z80 0x1380
            data = loadSmps(offset, 0x1380);
        }

        if (data instanceof Sonic2SmpsData) {
            ((Sonic2SmpsData) data).setPsgEnvelopes(loadPsgEnvelopes());
        }
        if (data != null) {
            data.setId(musicId);
            // bit 6 (0x40): disable PAL music speed fix
            data.setPalSpeedupDisabled((musicId & 0x40) != 0);
        }
        return data;
    }

    /**
     * Returns the ROM offset for a given music ID using the hard map first, then
     * the flag table.
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
        } catch (NumberFormatException ignored) {
        }
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
        if (sfxId < 0xA0)
            return null;

        try {
            int index = sfxId - 0xA0;
            int tableAddr = 0xFEE91;
            int entryAddr = tableAddr + (index * 2);

            int lo = rom.readByte(entryAddr) & 0xFF;
            int hi = rom.readByte(entryAddr + 1) & 0xFF;
            int ptr = lo | (hi << 8); // Z80 pointer (e.g. 0x8xxx)

            if (ptr == 0)
                return null;

            // Map Z80 0x8000-0xFFFF to ROM 0xF8000-0xFFFFF
            int romOffset = 0xF8000 + (ptr & 0x7FFF);

            // SFX are stored uncompressed in Sonic 2. Read raw bytes until next
            // pointer/bank end.
            int bankBase = 0xF8000;
            int headerOffset = romOffset - bankBase;
            int sfxLength = computeRawSfxLength(index, romOffset);

            // Extend buffer if the voice table sits past the next pointer.
            int voicePtr = (rom.readByte(romOffset) & 0xFF) | ((rom.readByte(romOffset + 1) & 0xFF) << 8);
            int minLength = headerOffset + sfxLength;
            // Reserve up to 0x100 bytes past the voice table start (25 bytes per voice,
            // rounded up)
            int voiceOffset = voicePtr == 0 ? -1 : (voicePtr & 0x7FFF);
            int voiceReach = voiceOffset < 0 ? 0 : voiceOffset + 0x100;
            int readLength = Math.max(minLength, voiceReach);
            int bankLimit = 0x8000; // 32 KB bank
            if (readLength > bankLimit) {
                readLength = bankLimit;
            }

            // Read from bankBase up to end of this SFX (bounded by next pointer/bank
            // end/voice table)
            byte[] raw = rom.readBytes(bankBase, readLength);

            Sonic2SfxData sfx = new Sonic2SfxData(raw, 0x8000, 0, headerOffset);
            if (isValidSfx(sfx)) {
                return sfx;
            }

            LOGGER.severe("Failed to parse SFX ID " + Integer.toHexString(sfxId));
            return null;
        } catch (Exception e) {
            LOGGER.severe("Failed to load SFX ID " + Integer.toHexString(sfxId));
            e.printStackTrace();
            return null;
        }
    }

    private boolean isValidSfx(AbstractSmpsData data) {
        if (data instanceof Sonic2SfxData sfxData) {
            return !sfxData.getTrackEntries().isEmpty();
        }
        // Basic validation: Channels should be within reasonable limits for Genesis
        // FM: 0-6, PSG: 0-4
        int fm = data.getChannels();
        int psg = data.getPsgChannels();
        if (fm > 7 || psg > 4)
            return false;
        // Also checks if header was parsed at all
        if (fm == 0 && psg == 0 && data.getVoicePtr() == 0)
            return false;

        // Check pointers
        int z80Start = data.getZ80StartAddress();
        int dataLen = data.getData().length;

        if (!isValidPointer(data.getVoicePtr(), z80Start, dataLen))
            return false;
        if (data.getDacPointer() != 0 && !isValidPointer(data.getDacPointer(), z80Start, dataLen))
            return false;

        if (data.getFmPointers() != null) {
            for (int ptr : data.getFmPointers()) {
                if (ptr != 0 && !isValidPointer(ptr, z80Start, dataLen))
                    return false;
            }
        }
        if (data.getPsgPointers() != null) {
            for (int ptr : data.getPsgPointers()) {
                if (ptr != 0 && !isValidPointer(ptr, z80Start, dataLen))
                    return false;
            }
        }

        return true;
    }

    private boolean isValidPointer(int ptr, int start, int len) {
        if (ptr == 0)
            return true;
        int offset = ptr;
        if (start > 0) {
            // If pointers are absolute Z80 addresses, they must map to our buffer
            if (ptr < start)
                return false;
            offset = ptr - start;
        }
        return offset >= 0 && offset < len;
    }

    /**
     * Sonic 2 final: music flags list at 0xECF36, pointer banks at 0xF0000/0xF8000.
     * Flag bits: 0-4 = pointer index, bit5 = uncompressed (1=uncompressed), bit7 =
     * bank (0=0xF0000,1=0xF8000).
     */
    private int resolveMusicOffset(int musicId) {
        // Prefer deriving from ROM.
        int romOffset = resolveMusicOffsetFromRom(musicId);
        if (romOffset != -1)
            return romOffset;

        // Fallback to legacy map
        Integer mapped = musicMap.get(musicId);
        if (mapped != null)
            return mapped;
        return -1;
    }

    private int resolveMusicOffsetFromRom(int musicId) {
        // Known music IDs start at 0x81; map ID to flag index by subtracting 0x81.
        if (musicId < 0x81)
            return -1;
        int flagIndex = musicId - 0x81;
        int flagsAddr = 0x0ECF36 + flagIndex;
        try {
            int flags = rom.readByte(flagsAddr) & 0xFF;
            int ptrId = flags & 0x1F;
            boolean uncompressed = (flags & 0x20) != 0;
            int bankBase = ((flags & 0x80) != 0) ? 0x0F8000 : 0x0F0000;
            // Pointer-to-pointer table at 0x0EC810 (driver relocates this in RAM); treat as
            // ROM address here.
            int ptrToPtrLo = rom.readByte(0x0EC810) & 0xFF;
            int ptrToPtrHi = rom.readByte(0x0EC810 + 1) & 0xFF;
            int ptrTableBase = (ptrToPtrLo << 8) | ptrToPtrHi;
            if (ptrTableBase == 0)
                return -1;
            int ptrTableEntry = ptrTableBase + (ptrId * 2);
            int lo = rom.readByte(ptrTableEntry) & 0xFF;
            int hi = rom.readByte(ptrTableEntry + 1) & 0xFF;
            int ptr = (lo << 8) | hi;
            int offset = bankBase + ptr;
            if (ptr == 0)
                return -1;
            if (uncompressed) {
                // We assume compressed; fallback to hard map if needed
                LOGGER.fine("Music " + Integer.toHexString(musicId) + " flagged uncompressed; using raw offset "
                        + Integer.toHexString(offset));
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

    /**
     * Load uncompressed SMPS data directly from ROM.
     * Used for tracks with bit 5 set in their music ID (0x20 mask).
     * These include: 1-Up (0xB5), Game Over (0xB8), Got an Emerald (0xBA), Credits
     * (0xBD).
     */
    private AbstractSmpsData loadSmpsUncompressed(int offset, int z80Addr) {
        try {
            // Calculate size by finding the next music pointer after this offset
            int size = calculateUncompressedSize(offset);
            int available = (int) Math.max(0L, rom.getSize() - offset);
            int readLen = Math.min(size, available);

            if (readLen <= 0) {
                LOGGER.severe("No data available at offset " + Integer.toHexString(offset));
                return null;
            }

            byte[] raw = rom.readBytes(offset, readLen);
            LOGGER.info("Loaded uncompressed SMPS at " + Integer.toHexString(offset)
                    + ". Size: " + raw.length + " bytes (0x" + Integer.toHexString(raw.length) + ")");
            return new Sonic2SmpsData(raw, z80Addr);
        } catch (Exception e) {
            LOGGER.severe("Failed to load uncompressed SMPS at " + Integer.toHexString(offset));
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Calculate the size of uncompressed music data.
     * Uses explicit sizes for known uncompressed tracks based on ROM analysis.
     * Per Sonic Retro, the uncompressed tracks are: 1-Up, Game Over, Emerald,
     * Credits.
     */
    private int calculateUncompressedSize(int offset) {
        // Explicit sizes for uncompressed tracks (calculated from Sonic Retro ROM
        // pointer table)
        // These are the exact distances between consecutive uncompressed song pointers
        switch (offset) {
            case 0x0FD48D: // 1-Up → Game Over (0xFD57A - 0xFD48D)
                return 0xED; // 237 bytes
            case 0x0FD57A: // Game Over → Emerald (0xFD6C9 - 0xFD57A)
                return 0x14F; // 335 bytes
            case 0x0FD6C9: // Emerald → Credits (0xFD797 - 0xFD6C9)
                return 0xCE; // 206 bytes
            case 0x0FD797: // Credits (massive medley song, ~37 voices + 9 tracks)
                // Estimated actual size: ~5-6KB based on 925 bytes of voices + ~4-5KB of track
                // data
                return 0x2000; // 8KB buffer - safe upper bound
            default:
                // Fallback: find next offset or use reasonable max
                int nextOffset = Integer.MAX_VALUE;
                for (int romOffset : musicMap.values()) {
                    if (romOffset > offset && romOffset < nextOffset) {
                        nextOffset = romOffset;
                    }
                }
                if (nextOffset != Integer.MAX_VALUE) {
                    int size = nextOffset - offset;
                    LOGGER.fine("Calculated uncompressed size: " + size + " bytes (next offset: "
                            + Integer.toHexString(nextOffset) + ")");
                    return size;
                }
                return 0x200; // 512 bytes fallback
        }
    }

    private AbstractSmpsData loadSfxSmps(int offset, int z80Addr) {
        try {
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
            LOGGER.info("Decompressed SFX SMPS at " + Integer.toHexString(offset) + ". Size: " + decompressed.length);
            return new Sonic2SfxData(decompressed, z80Addr, 0, 0);
        } catch (Exception e) {
            LOGGER.severe("Failed to load SMPS at " + Integer.toHexString(offset));
            e.printStackTrace();
            return null;
        }
    }

    private int computeRawSfxLength(int tableIndex, int romOffset) throws IOException {
        int bankBase = 0xF8000;
        int bankEnd = bankBase + 0x8000;

        // Bound by next SFX pointer in the table
        int nextOffset = bankEnd;
        int tableAddr = 0xFEE91;
        for (int idx = tableIndex + 1; idx <= (0xEF - 0xA0); idx++) {
            int entryAddr = tableAddr + (idx * 2);
            int lo = rom.readByte(entryAddr) & 0xFF;
            int hi = rom.readByte(entryAddr + 1) & 0xFF;
            int ptr = lo | (hi << 8);
            if (ptr != 0) {
                int candidate = bankBase + (ptr & 0x7FFF);
                if (candidate > romOffset) {
                    nextOffset = candidate;
                    break;
                }
            }
        }

        int length = nextOffset - romOffset;
        if (length <= 0 || length > (bankEnd - romOffset)) {
            length = bankEnd - romOffset;
        }
        // safety floor
        if (length < 16)
            length = 16;
        return length;
    }

    private byte[] readCompressed(int offset, int sizeHeader, int maxAvail) {
        if (sizeHeader <= 0)
            return null;
        int size = Math.min(sizeHeader, maxAvail);
        if (size <= 0)
            return null;
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

                if (ptr == 0)
                    continue;

                // Map Z80 0x8000-0xFFFF to ROM
                int offset = ptr & 0x7FFF;
                int romAddr = bankBase + offset;

                byte[] buffer = new byte[256];
                int len = 0;
                for (int i = 0; i < 256; i++) {
                    int b = rom.readByte(romAddr + i) & 0xFF;
                    buffer[i] = (byte) b;
                    len++;
                    // 0x80 = Hold (S2 definition), 0x81 = Hold, 0x83 = Stop
                    if (b == 0x80 || b == 0x81 || b == 0x83) {
                        break;
                    } else if (b == 0x82 || b == 0x84) {
                        // Loop (82) or Multiplier (84) takes a parameter
                        i++;
                        b = rom.readByte(romAddr + i) & 0xFF;
                        buffer[i] = (byte) b;
                        len++;
                        if (buffer[i - 1] == (byte) 0x82)
                            break;
                    }
                }

                byte[] env = new byte[len];
                System.arraycopy(buffer, 0, env, 0, len);
                // Validate envelope bytes against expected Sonic 2 PSG semantics before
                // accepting.
                boolean valid = true;
                for (byte v : env) {
                    int val = v & 0xFF;
                    if (val < 0x80) {
                        // Data byte (attenuation). Sonic 2 uses 0-0x0F, but keep it lenient to preserve
                        // raw ROM data.
                        if (val > 0x7F) {
                            valid = false;
                            break;
                        }
                    } else {
                        if (val != 0x80 && val != 0x81 && val != 0x82 && val != 0x83 && val != 0x84) {
                            valid = false;
                            break;
                        }
                    }
                }
                if (valid) {
                    envelopes.put(id, env);
                } else {
                    LOGGER.fine("Skipped invalid PSG envelope " + id + " at " + Integer.toHexString(romAddr));
                }
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
            // Pointers at 0xECF7C. Format: 4 bytes (Ptr LE, Len LE). If next byte is FF,
            // skip it.
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

                if (ptr == 0 || len == 0)
                    continue;

                // Read compressed
                int romAddr = bankStart + ptr;
                byte[] compressed = rom.readBytes(romAddr, len);
                byte[] pcm = dcmDecoder.decode(compressed);

                // Sample IDs correspond to 0x81 + i
                samples.put(0x81 + i, pcm);
            }

            // 2. Load Mapping from Master List (81-91)
            // Starts at 0xECF9C. Format: 2 bytes (SampleID, Rate). If next byte is FF, skip
            // it.
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

                if (sampleId == 0xFF)
                    continue;

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
