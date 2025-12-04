package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.tools.DcmDecoder;
import uk.co.jamesj999.sonic.tools.SaxmanDecompressor;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class SmpsLoader {
    private static final Logger LOGGER = Logger.getLogger(SmpsLoader.class.getName());
    private final Rom rom;
    private final SaxmanDecompressor decompressor = new SaxmanDecompressor();
    private final DcmDecoder dcmDecoder = new DcmDecoder();
    private final Map<Integer, Integer> musicMap = new HashMap<>();
    private final Map<String, Integer> sfxMap = new HashMap<>();

    public SmpsLoader(Rom rom) {
        this.rom = rom;
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

    public SmpsData loadMusic(int musicId) {
        int offset = findMusicOffset(musicId);
        if (offset == -1) {
            LOGGER.fine("Music ID " + Integer.toHexString(musicId) + " not in map/flags.");
            return null;
        }
        // Sonic 2 music loaded at Z80 0x1380
        return loadSmps(offset, 0x1380);
    }

    /**
     * Returns the ROM offset for a given music ID using the hard map first, then the flag table.
     * Exposed for debug tools (sound test).
     */
    public int findMusicOffset(int musicId) {
        Integer mapped = musicMap.get(musicId);
        return mapped != null ? mapped : resolveMusicOffset(musicId);
    }

    public SmpsData loadSfx(String name) {
        Integer offset = sfxMap.get(name);
        if (offset != null) {
            // Sonic 2 SFX usually loaded at Z80 0x1C00 (Guess)
            return loadSmps(offset, 0x1C00);
        }
        return null;
    }

    /**
     * Sonic 2 final: music flags list at 0xECF36, pointer banks at 0xF0000/0xF8000.
     * Flag bits: 0-4 = pointer index, bit5 = uncompressed (1=uncompressed), bit7 = bank (0=0xF0000,1=0xF8000).
     */
    private int resolveMusicOffset(int musicId) {
        // Legacy map is more reliable; only use flags if map entry missing.
        Integer mapped = musicMap.get(musicId);
        if (mapped != null) return mapped;
        // Known music IDs start at 0x81; map ID to flag index by subtracting 0x81.
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
            // Fallback to legacy map if available
            Integer mapOffset = musicMap.get(musicId);
            if (mapOffset != null) return mapOffset;
            return -1;
        }
    }

    public SmpsData loadSmps(int offset) {
        // Default fallback: assume ROM mapping or unknown
        return loadSmps(offset, 0x8000 | (offset & 0x7FFF));
    }

    public SmpsData loadSmps(int offset, int z80Addr) {
         try {
            // Read Saxman header: first two bytes are compressed size, big-endian per spec
            int b1 = rom.readByte(offset) & 0xFF;
            int b2 = rom.readByte(offset + 1) & 0xFF;
            int compressedSize = (b1 << 8) | b2;

            // Read compressed block (header + payload)
            byte[] compressed = rom.readBytes(offset, compressedSize + 2);

            byte[] decompressed = decompressor.decompress(compressed);
            LOGGER.info("Decompressed SMPS at " + Integer.toHexString(offset) + ". Size: " + decompressed.length);
            return new SmpsData(decompressed, z80Addr);
        } catch (Exception e) {
            LOGGER.severe("Failed to load SMPS at " + Integer.toHexString(offset));
            e.printStackTrace();
            return null;
        }
    }

    public DacData loadDacData() {
        Map<Integer, byte[]> samples = new HashMap<>();
        Map<Integer, DacData.DacEntry> mapping = new HashMap<>();

        try {
            // Pointers at 0xECF7C
            int ptrTable = 0xECF7C;
            int bankStart = 0xE0000;

            // Load Samples 81-91
            for (int i = 0; i < 20; i++) {
                int entryAddr = ptrTable + (i * 4);
                // Little Endian
                int p1 = rom.readByte(entryAddr) & 0xFF;
                int p2 = rom.readByte(entryAddr + 1) & 0xFF;
                int ptr = p1 | (p2 << 8);

                int l1 = rom.readByte(entryAddr + 2) & 0xFF;
                int l2 = rom.readByte(entryAddr + 3) & 0xFF;
                int len = l1 | (l2 << 8);

                if (ptr == 0 || len == 0) continue;

                // Read compressed
                int romAddr = bankStart + ptr;
                byte[] compressed = rom.readBytes(romAddr, len);
                byte[] pcm = dcmDecoder.decode(compressed);

                samples.put(0x81 + i, pcm);
            }

            // Master List at 0xECF9C
            int mapAddr = 0xECF9C;
            for (int i = 0; i < 20; i++) {
                int noteId = 0x81 + i;
                int sampleId = rom.readByte(mapAddr + i * 2) & 0xFF;
                int rate = rom.readByte(mapAddr + i * 2 + 1) & 0xFF;

                if (sampleId == 0xFF) continue;

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
