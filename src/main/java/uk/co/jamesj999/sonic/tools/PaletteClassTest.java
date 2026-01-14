package uk.co.jamesj999.sonic.tools;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.level.Palette;

/**
 * Tests the Palette class color conversion.
 */
public class PaletteClassTest {
    public static void main(String[] args) throws Exception {
        RomManager romManager = RomManager.getInstance();
        Rom rom = romManager.getRom();
        
        // Read SonicAndTails palette using Palette class
        byte[] paletteData = rom.readBytes(Sonic2Constants.SONIC_TAILS_PALETTE_ADDR, 32);
        Palette palette = new Palette();
        palette.fromSegaFormat(paletteData);
        
        System.out.println("Palette colors using Palette class:");
        for (int i = 0; i < 16; i++) {
            Palette.Color c = palette.getColor(i);
            System.out.printf("Index %X: RGB(%3d, %3d, %3d)\n", 
                i, Byte.toUnsignedInt(c.r), Byte.toUnsignedInt(c.g), Byte.toUnsignedInt(c.b));
        }
        
        // Specifically check index C (pure red)
        Palette.Color red = palette.getColor(0xC);
        System.out.println("\nIndex C (should be pure red 255,0,0):");
        System.out.printf("  R=%d, G=%d, B=%d\n", 
            Byte.toUnsignedInt(red.r), Byte.toUnsignedInt(red.g), Byte.toUnsignedInt(red.b));
    }
}
