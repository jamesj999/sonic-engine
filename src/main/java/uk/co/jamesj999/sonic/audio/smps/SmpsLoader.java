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
        // Map Music ID (from playlist) to absolute ROM offsets (Sonic 2 final)
        musicMap.put(0x81, 0x0F88C4); // EHZ
        musicMap.put(0x82, 0x0F8DEE); // MTZ
        musicMap.put(0x83, 0x0F917B); // CNZ
        musicMap.put(0x84, 0x0F9664); // MCZ
        musicMap.put(0x85, 0x0F9A3C); // MCZ 2P
        musicMap.put(0x86, 0x0FCE74); // HTZ
        musicMap.put(0x87, 0x0F9D69); // ARZ
        musicMap.put(0x88, 0x0FA6ED); // Special Stage
        musicMap.put(0x89, 0x0FAAC4); // Options
        musicMap.put(0x8A, 0x0FAC3C); // Ending
        musicMap.put(0x8B, 0x0FB124); // Final battle
        musicMap.put(0x8C, 0x0FB3F7); // CPZ
        musicMap.put(0x8D, 0x0FB81E); // Boss
        musicMap.put(0x8E, 0x0FBA6F); // Sky Chase
        musicMap.put(0x8F, 0x0FC146); // WFZ
        musicMap.put(0x90, 0x0FC146); // WFZ alias
        musicMap.put(0x91, 0x0FC480); // EHZ 2P
        musicMap.put(0x93, 0x0FCBBC); // Super Sonic
        musicMap.put(0x94, 0x0FCE74); // HTZ alias
        musicMap.put(0x96, 0x0FD193); // Title
        musicMap.put(0x97, 0x0FD35E); // Stage Clear
        musicMap.put(0x99, 0x0F8359); // Invincibility
        musicMap.put(0x9B, 0x0F803C); // Hidden Palace
        musicMap.put(0xB5, 0x0FD48D); // 1-Up
        musicMap.put(0xB8, 0x0FD57A); // Game Over
        musicMap.put(0xBA, 0x0FD6C9); // Got an Emerald
        musicMap.put(0xBD, 0x0FD797); // Credits
        musicMap.put(0x00, 0x0F0002); // Continue

        // SFX Map (Populate with discovered offsets)
        // Potential candidate for SFX: 0xFFEAD (FM=1)
        // Without precise IDs, we leave this empty for now or add placeholders.
        // Adding infrastructure so it can be enabled easily.
        // sfxMap.put("RING", 0xFFEAD);
    }

    public SmpsData loadMusic(int musicId) {
        Integer offset = musicMap.get(musicId);
        if (offset == null) {
            LOGGER.fine("Music ID " + Integer.toHexString(musicId) + " not in map.");
            return null;
        }
        return loadSmps(offset);
    }

    public SmpsData loadSfx(String name) {
        Integer offset = sfxMap.get(name);
        if (offset != null) {
            return loadSmps(offset);
        }
        return null;
    }

    public SmpsData loadSmps(int offset) {
         try {
            // Read compressed size (Saxman stores little-endian size)
            int b1 = rom.readByte(offset) & 0xFF;
            int b2 = rom.readByte(offset + 1) & 0xFF;
            int compressedSize = (b2 << 8) | b1;

            // Read compressed block
            byte[] compressed = rom.readBytes(offset, compressedSize + 2);

            byte[] decompressed = decompressor.decompress(compressed);
            LOGGER.info("Decompressed SMPS at " + Integer.toHexString(offset) + ". Size: " + decompressed.length);
            return new SmpsData(decompressed);
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
