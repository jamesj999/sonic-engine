package uk.co.jamesj999.sonic.tools;

import uk.co.jamesj999.sonic.game.GameServices;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.level.Pattern;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.util.Arrays;

/**
 * Dumps tile data from ArtNem_TitleCard to see color indices used.
 */
public class TitleCardTileDumper {
    public static void main(String[] args) throws Exception {
        RomManager romManager = GameServices.rom();
        Rom rom = romManager.getRom();
        
        // Load ArtNem_TitleCard (Nemesis compressed)
        byte[] compressed = rom.readBytes(Sonic2Constants.ART_NEM_TITLE_CARD_ADDR, 8192);
        byte[] decompressed;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
             var channel = Channels.newChannel(bais)) {
            decompressed = NemesisReader.decompress(channel);
        }
        
        int patternCount = decompressed.length / Pattern.PATTERN_SIZE_IN_ROM;
        System.out.println("Total patterns in ArtNem_TitleCard: " + patternCount);
        
        // Tile $5D4 is at offset 0x54 from VRAM base 0x580
        // 0x5D4 - 0x580 = 0x54 = 84 decimal
        int targetTile = 0x54;
        
        if (targetTile < patternCount) {
            int offset = targetTile * Pattern.PATTERN_SIZE_IN_ROM;
            byte[] tileData = Arrays.copyOfRange(decompressed, offset, offset + Pattern.PATTERN_SIZE_IN_ROM);
            
            System.out.println("\nTile $5D4 (index " + targetTile + ") raw bytes:");
            for (int i = 0; i < tileData.length; i++) {
                System.out.printf("%02X ", tileData[i] & 0xFF);
                if ((i + 1) % 4 == 0) System.out.println();
            }
            
            // Decode the pattern (Mega Drive stores 2 pixels per byte, high nibble first)
            System.out.println("\nDecoded color indices (8x8 grid):");
            Pattern p = new Pattern();
            p.fromSegaFormat(tileData);
            
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int colorIndex = p.getPixel(x, y) & 0xF;
                    System.out.printf("%X ", colorIndex);
                }
                System.out.println();
            }
            
            // Count unique color indices
            int[] colorCounts = new int[16];
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    colorCounts[p.getPixel(x, y) & 0xF]++;
                }
            }
            System.out.println("\nColor index usage:");
            for (int i = 0; i < 16; i++) {
                if (colorCounts[i] > 0) {
                    System.out.printf("  Index %X: %d pixels\n", i, colorCounts[i]);
                }
            }
        } else {
            System.out.println("Tile index out of range!");
        }
    }
}

