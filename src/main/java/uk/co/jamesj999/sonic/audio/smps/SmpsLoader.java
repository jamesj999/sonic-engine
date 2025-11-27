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
        // Map Music ID (from Playlist) to ROM Offset
        musicMap.put(0x82, 0xF88C4); // EHZ
        musicMap.put(0x8B, 0xF9664); // MCZ
        musicMap.put(0x84, 0xFBD8C); // OOZ
        musicMap.put(0x85, 0xF8DEE); // MTZ
        musicMap.put(0x86, 0xFCE74); // HTZ
        musicMap.put(0x87, 0xF9D69); // ARZ
        musicMap.put(0x89, 0xF917B); // CNZ
        musicMap.put(0x8A, 0xFA36B); // DEZ
        musicMap.put(0x8D, 0xFBA6F); // SCZ
        musicMap.put(0x8E, 0xFB3F7); // CPZ
        musicMap.put(0x8F, 0xFC146); // WFZ

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
            // Read compressed size (Little Endian)
            int b1 = rom.readByte(offset) & 0xFF;
            int b2 = rom.readByte(offset + 1) & 0xFF;
            int compressedSize = b1 | (b2 << 8);

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
