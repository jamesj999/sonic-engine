package uk.co.jamesj999.sonic.tools;

import uk.co.jamesj999.sonic.game.GameServices;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;

/**
 * Dumps palette data from ROM.
 */
public class PaletteDumper {
    public static void main(String[] args) throws Exception {
        RomManager romManager = GameServices.rom();
        Rom rom = romManager.getRom();
        
        // Read SonicAndTails palette (line 0)
        int addr = Sonic2Constants.SONIC_TAILS_PALETTE_ADDR;
        byte[] palette = rom.readBytes(addr, 32); // 16 colors * 2 bytes
        
        System.out.println("SonicAndTails palette at 0x" + Integer.toHexString(addr) + ":");
        System.out.println("Raw bytes:");
        for (int i = 0; i < palette.length; i += 2) {
            System.out.printf("%02X%02X ", palette[i] & 0xFF, palette[i+1] & 0xFF);
        }
        System.out.println("\n");
        
        System.out.println("Decoded colors:");
        for (int i = 0; i < 16; i++) {
            int word = ((palette[i*2] & 0xFF) << 8) | (palette[i*2+1] & 0xFF);
            // Mega Drive format: 0000BBB0GGG0RRR0
            int b = (word >> 9) & 7;
            int g = (word >> 5) & 7;
            int r = (word >> 1) & 7;
            
            // Convert to 8-bit RGB
            int r8 = r * 255 / 7;
            int g8 = g * 255 / 7;
            int b8 = b * 255 / 7;
            
            System.out.printf("Index %X: $%04X -> B=%d G=%d R=%d -> RGB(%3d, %3d, %3d)\n", 
                i, word, b, g, r, r8, g8, b8);
        }
    }
}

