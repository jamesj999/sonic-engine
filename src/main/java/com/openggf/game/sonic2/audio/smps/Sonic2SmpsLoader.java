package com.openggf.game.sonic2.audio.smps;

import static com.openggf.game.sonic2.audio.Sonic2SmpsConstants.*;

import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.AbstractSmpsLoader;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.LoadedSmpsMusic;
import com.openggf.data.Rom;
import com.openggf.data.compression.DcmDecoder;
import com.openggf.data.compression.SaxmanDecompressor;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Sonic2SmpsLoader extends AbstractSmpsLoader {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SmpsLoader.class.getName());
    /** Retail fixBugs=0 zWriteToDAC cost per byte/two decoded samples. */
    private static final int DPCM_BASE_CYCLES = 295;
    private final SaxmanDecompressor decompressor = new SaxmanDecompressor();
    private final DcmDecoder dcmDecoder = new DcmDecoder();
    private final Map<Integer, Integer> musicMap = new HashMap<>();
    private final Map<String, Integer> sfxMap = new HashMap<>();
    private final Map<Integer, String> sfxNames = new HashMap<>();

    public Sonic2SmpsLoader(Rom rom) {
        super(rom);
        populateSfxNames();
        cacheAllSfx();
        // Known Sonic 2 final music offsets (ROM addresses, Saxman compressed)
        // These ROM addresses were discovered empirically and are correct.
        // The IDs here are what the game uses when requesting music.
        musicMap.put(Sonic2Music.CONTINUE.id, 0x0F0002); // Continue
        musicMap.put(Sonic2Music.CASINO_NIGHT_2P.id, 0x0F84F6); // Casino Night 2P
        musicMap.put(Sonic2Music.EMERALD_HILL.id, 0x0F88C4); // Emerald Hill
        musicMap.put(Sonic2Music.METROPOLIS.id, 0x0F8DEE); // Metropolis
        musicMap.put(Sonic2Music.CASINO_NIGHT.id, 0x0F917B); // Casino Night
        musicMap.put(Sonic2Music.MYSTIC_CAVE.id, 0x0F9664); // Mystic Cave
        musicMap.put(Sonic2Music.MYSTIC_CAVE_2P.id, 0x0F9A3C); // Mystic Cave 2P
        musicMap.put(Sonic2Music.AQUATIC_RUIN.id, 0x0F9D69); // Aquatic Ruin
        musicMap.put(Sonic2Music.DEATH_EGG.id, 0x0FA36B); // Death Egg
        musicMap.put(Sonic2Music.SPECIAL_STAGE.id, 0x0FA6ED); // Special Stage
        musicMap.put(Sonic2Music.OPTIONS.id, 0x0FAAC4); // Options
        musicMap.put(Sonic2Music.ENDING.id, 0x0FAC3C); // Ending
        musicMap.put(Sonic2Music.FINAL_BOSS.id, 0x0FB124); // Final Battle
        musicMap.put(Sonic2Music.CHEMICAL_PLANT.id, 0x0FB3F7); // Chemical Plant
        musicMap.put(Sonic2Music.BOSS.id, 0x0FB81E); // Boss
        musicMap.put(Sonic2Music.SKY_CHASE.id, 0x0FBA6F); // Sky Chase
        musicMap.put(Sonic2Music.OIL_OCEAN.id, 0x0FBD8C); // Oil Ocean
        musicMap.put(Sonic2Music.WING_FORTRESS.id, 0x0FC146); // Wing Fortress
        musicMap.put(Sonic2Music.EMERALD_HILL_2P.id, 0x0FC480); // Emerald Hill 2P
        musicMap.put(Sonic2Music.RESULTS_2P.id, 0x0FC824); // 2P Results
        musicMap.put(Sonic2Music.SUPER_SONIC.id, 0x0FCBBC); // Super Sonic
        musicMap.put(Sonic2Music.HILL_TOP.id, 0x0FCE74); // Hill Top
        musicMap.put(Sonic2Music.TITLE.id, 0x0FD193); // Title
        musicMap.put(Sonic2Music.ACT_CLEAR.id, 0x0FD35E); // Stage Clear
        musicMap.put(Sonic2Music.INVINCIBILITY.id, 0x0F8359); // Invincibility
        musicMap.put(Sonic2Music.HIDDEN_PALACE.id, 0x0F803C); // Hidden Palace
        musicMap.put(Sonic2Music.EXTRA_LIFE.id, UNCOMPRESSED_EXTRA_LIFE_ADDR); // 1-Up
        musicMap.put(Sonic2Music.GAME_OVER.id, UNCOMPRESSED_GAME_OVER_ADDR); // Game Over
        musicMap.put(Sonic2Music.GOT_EMERALD.id, UNCOMPRESSED_GOT_EMERALD_ADDR); // Got an Emerald
        musicMap.put(Sonic2Music.CREDITS.id, UNCOMPRESSED_CREDITS_ADDR); // Credits
        musicMap.put(Sonic2Music.UNDERWATER.id, 0x0F823B); // Underwater Timing
        // SFX Map (Populate with discovered offsets)
        // Potential candidate for SFX: 0xFFEAD (FM=1)
        sfxMap.put("RING", 0xFFEAD);
    }

    private void populateSfxNames() {
        // Common Sonic 2 SFX (from SoundIndex in disassembly)
        sfxNames.put(Sonic2Sfx.ID_BASE, "Jump");
        sfxNames.put(0xA1, "Checkpoint");
        sfxNames.put(0xA2, "Spike Switch");
        sfxNames.put(0xA3, "Hurt");
        sfxNames.put(0xA4, "Skidding");
        sfxNames.put(0xA5, "Missile Dissolve (Unused)");
        sfxNames.put(0xA6, "Hurt By Spikes");
        sfxNames.put(0xA7, "Sparkle");
        sfxNames.put(0xA8, "Beep");
        sfxNames.put(0xA9, "Bwoop (Unused)");
        sfxNames.put(0xAA, "Splash");
        sfxNames.put(0xAB, "Swish");
        sfxNames.put(0xAC, "Boss Hit");
        sfxNames.put(0xAD, "Inhaling Bubble");
        sfxNames.put(0xAE, "Arrow Firing");
        sfxNames.put(0xAF, "Shield");
        sfxNames.put(0xB0, "Laser Beam");
        sfxNames.put(0xB1, "Zap (Unused)");
        sfxNames.put(0xB2, "Drown");
        sfxNames.put(0xB3, "Fire Burn");
        sfxNames.put(0xB4, "Bumper");
        sfxNames.put(0xB5, "Ring (Right)");
        sfxNames.put(0xB6, "Spikes Move");
        sfxNames.put(0xB7, "Rumbling");
        sfxNames.put(0xB8, "Unused");
        sfxNames.put(0xB9, "Smash");
        sfxNames.put(0xBA, "Ding (Unused)");
        sfxNames.put(0xBB, "Door Slam");
        sfxNames.put(0xBC, "Spindash Release");
        sfxNames.put(0xBD, "Hammer");
        sfxNames.put(0xBE, "Roll");
        sfxNames.put(0xBF, "Continue Jingle");
        sfxNames.put(0xC0, "Casino Bonus");
        sfxNames.put(0xC1, "Explosion");
        sfxNames.put(0xC2, "Water Warning");
        sfxNames.put(0xC3, "Enter Giant Ring");
        sfxNames.put(0xC4, "Boss Explosion");
        sfxNames.put(0xC5, "Tally End");
        sfxNames.put(0xC6, "Ring Spill");
        sfxNames.put(0xC7, "Chain Pull (Unused)");
        sfxNames.put(0xC8, "Flamethrower");
        sfxNames.put(0xC9, "Bonus");
        sfxNames.put(0xCA, "Special Stage Entry");
        sfxNames.put(0xCB, "Slow Smash");
        sfxNames.put(0xCC, "Spring");
        sfxNames.put(0xCD, "Blip");
        sfxNames.put(0xCE, "Ring (Left)");
        sfxNames.put(0xCF, "Signpost");
        sfxNames.put(0xD0, "CNZ Boss Zap");
        sfxNames.put(0xD1, "Unused");
        sfxNames.put(0xD2, "Unused");
        sfxNames.put(0xD3, "Signpost 2P");
        sfxNames.put(0xD4, "OOZ Lid Pop");
        sfxNames.put(0xD5, "Sliding Spike");
        sfxNames.put(0xD6, "CNZ Elevator");
        sfxNames.put(0xD7, "Platform Knock");
        sfxNames.put(0xD8, "Bonus Bumper");
        sfxNames.put(0xD9, "Large Bumper");
        sfxNames.put(0xDA, "Gloop");
        sfxNames.put(0xDB, "Pre-Arrow Firing");
        sfxNames.put(0xDC, "Fire");
        sfxNames.put(0xDD, "Arrow Stick");
        sfxNames.put(0xDE, "Wing Fortress");
        sfxNames.put(0xDF, "Super Transform");
        sfxNames.put(0xE0, "Spindash Charge");
        sfxNames.put(0xE1, "Rumbling 2");
        sfxNames.put(0xE2, "CNZ Launch");
        sfxNames.put(0xE3, "Flipper");
        sfxNames.put(0xE4, "HTZ Lift Click");
        sfxNames.put(0xE5, "Leaves");
        sfxNames.put(0xE6, "Mega Mack Drop");
        sfxNames.put(0xE7, "Drawbridge Move");
        sfxNames.put(0xE8, "Quick Door Slam");
        sfxNames.put(0xE9, "Drawbridge Down");
        sfxNames.put(0xEA, "Laser Burst");
        sfxNames.put(0xEB, "Laser Floor");
        sfxNames.put(0xEC, "Teleport");
        sfxNames.put(0xED, "Error");
        sfxNames.put(0xEE, "Mecha Sonic Buzz");
        sfxNames.put(Sonic2Sfx.ID_MAX, "Large Laser");
    }

    public void cacheAllSfx() {
        LOGGER.info("Caching all SFX...");
        LOGGER.fine("Scanning for SFX from ID_BASE to ID_MAX...");
        // Scan ID_BASE to ID_MAX
        for (int id = Sonic2Sfx.ID_BASE; id <= Sonic2Sfx.ID_MAX; id++) {
            AbstractSmpsData sfx = loadSfxInternal(id);
            if (sfx != null) {
                sfxCache.put(id, sfx);
                if (!sfxNames.containsKey(id)) {
                    sfxNames.put(id, "SFX " + Integer.toHexString(id).toUpperCase());
                }
            }
        }
        String msg = "Cached " + sfxCache.size() + " SFX.";
        LOGGER.fine(msg);
    }

    public Map<Integer, String> getSfxList() {
        return new HashMap<>(sfxNames);
    }

    public java.util.Set<Integer> getAvailableSfxIds() {
        return new java.util.HashSet<>(sfxCache.keySet());
    }

    public AbstractSmpsData loadMusic(int musicId) {
        LoadedSmpsMusic loaded = loadMusicWithReadiness(musicId);
        return loaded == null ? null : loaded.data();
    }

    @Override
    public LoadedSmpsMusic loadMusicWithReadiness(int musicId) {
        int offset = findMusicOffset(musicId);
        if (offset == -1) {
            LOGGER.fine("Music ID " + Integer.toHexString(musicId) + " not in map/flags.");
            return null;
        }

        // The music ID itself contains flags (per Sonic Retro documentation):
        // bit 5 (0x20): Compression - 0=Saxman compressed, 0x20=uncompressed
        boolean uncompressed = (musicId & 0x20) != 0
                || offset == UNCOMPRESSED_EXTRA_LIFE_ADDR
                || offset == UNCOMPRESSED_GAME_OVER_ADDR
                || offset == UNCOMPRESSED_GOT_EMERALD_ADDR
                || offset == UNCOMPRESSED_CREDITS_ADDR;

        AbstractSmpsData data;
        byte[] compressed = null;
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
            // Compressed music is decompressed and loaded at Z80 Z80_COMPRESSED_LOAD_ADDR
            compressed = readCompressedMusic(offset);
            data = loadSmps(compressed, offset, Z80_COMPRESSED_LOAD_ADDR);
        }

        if (data instanceof Sonic2SmpsData) {
            ((Sonic2SmpsData) data).setPsgEnvelopes(loadPsgEnvelopes());
        }
        if (data != null) {
            data.setId(musicId);
            // bit 6 (0x40): disable PAL music speed fix
            data.setPalSpeedupDisabled((musicId & 0x40) != 0);
        }
        if (data == null) {
            return null;
        }
        return new LoadedSmpsMusic(data, compressed == null
                ? com.openggf.audio.smps.SmpsLoadReadiness.immediatePlan()
                : new Sonic2SaxmanLoadReadiness(compressed,
                        data.getChannels(), data.getPsgChannels(),
                        (offset & 0x8000) != 0,
                        data.isPalSpeedupDisabled()));
    }

    /**
     * Returns the ROM offset for a given music ID using the hardcoded
     * REV01 {@link #musicMap}. Exposed for debug tools (sound test).
     * <p>
     * <b>Why ROM-driven resolution is not used here.</b> The S2 driver's
     * {@code zMasterPlaylist} flag table (and the per-bank pointer tables it
     * references) lives inside the <em>Saxman-compressed</em> Z80 driver
     * blob in 68K ROM — the structure only exists, in readable form, after
     * the driver has been decompressed into Z80 RAM at runtime. Reading
     * {@code zMasterPlaylist} bytes directly from 68K ROM (the previous
     * implementation's approach) parses compressed bytes and cannot yield
     * correct offsets. On top of that, the engine's {@code Sonic2Music} IDs
     * are systematically shifted relative to the disassembly's
     * {@code zMasterPlaylist} entry order (e.g. {@code EMERALD_HILL.id == 0x81}
     * loads the EHZ track, but {@code zMasterPlaylist[0]} (disasm id 0x81) is
     * {@code Mus_2PResult}), so even a properly Z80-decompressed lookup would
     * disagree with the engine's intended track for most IDs. Until both
     * problems are solved, the hardcoded REV01 map is authoritative.
     */
    public int findMusicOffset(int musicId) {
        Integer mapped = musicMap.get(musicId);
        if (mapped != null) {
            return mapped;
        }
        return -1;
    }

    @Override
    public AbstractSmpsData loadSfx(String name) {
        // Check named sfxMap first (e.g. "RING")
        Integer offset = sfxMap.get(name);
        if (offset != null) {
            return loadSmps(offset, Z80_UNCOMPRESSED_LOAD_ADDR);
        }
        // Fall back to base class hex-ID parsing
        return super.loadSfx(name);
    }

    @Override
    protected boolean isValidSfxId(int id) {
        return id >= Sonic2Sfx.ID_BASE && id <= Sonic2Sfx.ID_MAX + 1;
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
        // SFX Pointer Table at SFX_POINTER_TABLE_ADDR.
        // IDs start at Sonic2Sfx.ID_BASE.
        // Pointers are 2 bytes (LE), relative to bank start SFX_BANK_BASE (Z80
        // Z80_BANK_BASE).
        if (sfxId < Sonic2Sfx.ID_BASE)
            return null;

        try {
            int index = sfxId - Sonic2Sfx.ID_BASE;
            int tableAddr = SFX_POINTER_TABLE_ADDR;
            int entryAddr = tableAddr + (index * 2);

            int lo = rom.readByte(entryAddr) & 0xFF;
            int hi = rom.readByte(entryAddr + 1) & 0xFF;
            int ptr = lo | (hi << 8); // Z80 pointer (e.g. 0x8xxx)

            if (ptr == 0)
                return null;

            // Map Z80 Z80_BANK_BASE-0xFFFF to ROM SFX_BANK_BASE-0xFFFFF
            int romOffset = SFX_BANK_BASE + (ptr & Z80_BANK_MASK);

            // SFX are stored uncompressed in Sonic 2. Read raw bytes until next
            // pointer/bank end.
            int bankBase = SFX_BANK_BASE;
            int headerOffset = romOffset - bankBase;
            int sfxLength = computeRawSfxLength(index, romOffset);

            // Extend buffer if the voice table sits past the next pointer.
            int voicePtr = (rom.readByte(romOffset) & 0xFF) | ((rom.readByte(romOffset + 1) & 0xFF) << 8);
            int minLength = headerOffset + sfxLength;
            // Reserve up to SFX_VOICE_TABLE_PADDING bytes past the voice table start (25
            // bytes per voice,
            // rounded up)
            int voiceOffset = voicePtr == 0 ? -1 : (voicePtr & Z80_BANK_MASK);
            int voiceReach = voiceOffset < 0 ? 0 : voiceOffset + SFX_VOICE_TABLE_PADDING;
            int readLength = Math.max(minLength, voiceReach);
            int bankLimit = Z80_BANK_BASE; // 32 KB bank
            if (readLength > bankLimit) {
                readLength = bankLimit;
            }

            // Read from bankBase up to end of this SFX (bounded by next pointer/bank
            // end/voice table)
            byte[] raw = rom.readBytes(bankBase, readLength);

            Sonic2SfxData sfx = new Sonic2SfxData(raw, Z80_BANK_BASE, 0, headerOffset);
            sfx.setId(sfxId);  // Set the SFX ID for debugging/identification
            if (isValidSfx(sfx)) {
                return sfx;
            }

            LOGGER.severe("Failed to parse SFX ID " + Integer.toHexString(sfxId));
            return null;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to load SFX ID " + Integer.toHexString(sfxId), e);
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

    public AbstractSmpsData loadSmps(int offset) {
        // Default fallback: assume ROM mapping or unknown
        return loadSmps(offset, Z80_BANK_BASE | (offset & Z80_BANK_MASK));
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

            return loadSmps(compressed, offset, z80Addr);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to load SMPS at " + Integer.toHexString(offset), e);
            return null;
        }
    }

    private byte[] readCompressedMusic(int offset) {
        try {
            int size = (rom.readByte(offset) & 0xff)
                    | ((rom.readByte(offset + 1) & 0xff) << 8);
            int available = (int) Math.max(0L, rom.getSize() - offset - 2L);
            return readCompressed(offset, size, available);
        } catch (IOException failure) {
            LOGGER.log(Level.SEVERE, "Failed to read compressed music", failure);
            return null;
        }
    }

    private AbstractSmpsData loadSmps(
            byte[] compressed, int offset, int z80Addr) {
        if (compressed == null) {
            return null;
        }
        byte[] decompressed = decompressor.decompress(compressed, true);
        LOGGER.info("Decompressed SMPS at " + Integer.toHexString(offset)
                + ". Size: " + decompressed.length);
        return new Sonic2SmpsData(decompressed, z80Addr);
    }

    /**
     * Load uncompressed SMPS data directly from ROM.
     * Used for tracks with bit 5 set in their music ID (0x20 mask).
     * These include: 1-Up (0x98), Game Over (0xB8), Got an Emerald (0xBA), Credits
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
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to load uncompressed SMPS at " + Integer.toHexString(offset), e);
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
        // pointer table). These are the exact distances between consecutive
        // uncompressed song pointers; consolidated as named constants in
        // Sonic2SmpsConstants so musicMap and this switch share one source.
        switch (offset) {
            case UNCOMPRESSED_EXTRA_LIFE_ADDR:
                return UNCOMPRESSED_EXTRA_LIFE_SIZE;
            case UNCOMPRESSED_GAME_OVER_ADDR:
                return UNCOMPRESSED_GAME_OVER_SIZE;
            case UNCOMPRESSED_GOT_EMERALD_ADDR:
                return UNCOMPRESSED_GOT_EMERALD_SIZE;
            case UNCOMPRESSED_CREDITS_ADDR:
                return UNCOMPRESSED_CREDITS_SIZE;
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
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to load SMPS at " + Integer.toHexString(offset), e);
            return null;
        }
    }

    private int computeRawSfxLength(int tableIndex, int romOffset) throws IOException {
        int bankBase = SFX_BANK_BASE;
        int bankEnd = bankBase + Z80_BANK_BASE;

        // Bound by next SFX pointer in the table
        int nextOffset = bankEnd;
        int tableAddr = SFX_POINTER_TABLE_ADDR;
        for (int idx = tableIndex + 1; idx <= (Sonic2Sfx.ID_MAX - Sonic2Sfx.ID_BASE); idx++) {
            int entryAddr = tableAddr + (idx * 2);
            int lo = rom.readByte(entryAddr) & 0xFF;
            int hi = rom.readByte(entryAddr + 1) & 0xFF;
            int ptr = lo | (hi << 8);
            if (ptr != 0) {
                int candidate = bankBase + (ptr & Z80_BANK_MASK);
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
        // PSG Envelopes at PSG_ENVELOPE_TABLE_ADDR (Pointer Table)
        int tableAddr = PSG_ENVELOPE_TABLE_ADDR;
        int bankBase = PSG_ENVELOPE_BANK_BASE;

        try {
            // Read 16 entries
            for (int id = 1; id <= 16; id++) {
                int ptrAddr = tableAddr + (id - 1) * 2;
                int p1 = rom.readByte(ptrAddr) & 0xFF;
                int p2 = rom.readByte(ptrAddr + 1) & 0xFF;
                int ptr = p1 | (p2 << 8);

                if (ptr == 0)
                    continue;

                // Map Z80 Z80_BANK_BASE-0xFFFF to ROM
                int offset = ptr & Z80_BANK_MASK;
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
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load PSG Envelopes from ROM");
        }
        return envelopes;
    }

    public DacData loadDacData() {
        Map<Integer, byte[]> samples = new HashMap<>();
        Map<Integer, DacData.DacEntry> mapping = new HashMap<>();

        try {
            int bankStart = PCM_BANK_START;

            // 1. Load Samples from Pointer Table (81-87)
            // Pointers at PCM_SAMPLE_PTR_TABLE_ADDR. Format: 4 bytes (Ptr LE, Len LE). If
            // next byte is FF,
            // skip it.
            int ptrTable = PCM_SAMPLE_PTR_TABLE_ADDR;
            int offset = ptrTable;

            for (int i = 0; i < PCM_SAMPLE_COUNT; i++) {
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

                // Sample IDs correspond to PCM_SAMPLE_ID_BASE + i
                samples.put(PCM_SAMPLE_ID_BASE + i, pcm);
            }

            // 2. Load Mapping from Master List (81-91)
            // Starts at PCM_SAMPLE_MAP_ADDR. Format: 2 bytes (SampleID, Rate). If next byte
            // is FF, skip it.
            int mapAddr = PCM_SAMPLE_MAP_ADDR;
            offset = mapAddr;

            // Sonic 2 Master List covers 81-91 (17 entries)
            for (int i = 0; i < PCM_MAPPING_COUNT; i++) {
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

                int noteId = PCM_SAMPLE_ID_BASE + i;
                mapping.put(noteId, new DacData.DacEntry(sampleId, rate));
            }

            return new DacData(samples, mapping, DPCM_BASE_CYCLES);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed to load DAC Data", e);
            return new DacData(new HashMap<>(), new HashMap<>(), DPCM_BASE_CYCLES);
        }
    }
}
