package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.tools.SaxmanDecompressor;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class SmpsLoader {
    private static final Logger LOGGER = Logger.getLogger(SmpsLoader.class.getName());
    private final Rom rom;
    private final SaxmanDecompressor decompressor = new SaxmanDecompressor();
    private final Map<Integer, Integer> musicMap = new HashMap<>();

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
    }

    public SmpsData loadMusic(int musicId) {
        Integer offset = musicMap.get(musicId);
        if (offset == null) {
            LOGGER.fine("Music ID " + Integer.toHexString(musicId) + " not in map.");
            return null;
        }

        try {
            // Read compressed size (Little Endian)
            int b1 = rom.readByte(offset) & 0xFF;
            int b2 = rom.readByte(offset + 1) & 0xFF;
            int compressedSize = b1 | (b2 << 8);

            // Read compressed block
            byte[] compressed = rom.readBytes(offset, compressedSize + 2);

            byte[] decompressed = decompressor.decompress(compressed);
            LOGGER.info("Decompressed music " + Integer.toHexString(musicId) + ". Size: " + decompressed.length);
            return new SmpsData(decompressed);
        } catch (Exception e) {
            LOGGER.severe("Failed to load music " + Integer.toHexString(musicId));
            e.printStackTrace();
            return null;
        }
    }
}
