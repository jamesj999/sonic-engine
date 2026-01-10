package uk.co.jamesj999.sonic.debug;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArt;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.level.objects.ObjectArtData;
import uk.co.jamesj999.sonic.level.Pattern;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class DebugArtViewer {

    public static void main(String[] args) {
        try {
            File romFile = new File("Sonic The Hedgehog 2 (W) (REV01) [!].gen");
            if (!romFile.exists()) {
                System.err.println("ROM not found");
                return;
            }
            Rom rom = new Rom();
            rom.open(romFile.getAbsolutePath());
            RomByteReader reader = RomByteReader.fromRom(rom);

            Sonic2ObjectArt artLoader = new Sonic2ObjectArt(rom, reader);
            ObjectArtData artData = artLoader.load(); // This triggers the build logic

            // Access restricted, maybe we can expose a getter or just use reflection?
            // Better to add a temporary public method to Sonic2ObjectArt or just modify it
            // to dump.
            // Actually, we can just edit Sonic2ObjectArt to dump images during load() for
            // this session.

            System.out.println("Use the modified Sonic2ObjectArt to dump images.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void dumpPatterns(Pattern[] patterns, String filename) {
        if (patterns == null || patterns.length == 0)
            return;

        int tilesPerRow = 32;
        int rows = (patterns.length + tilesPerRow - 1) / tilesPerRow;

        BufferedImage img = new BufferedImage(tilesPerRow * 8, rows * 8, BufferedImage.TYPE_INT_ARGB);

        for (int i = 0; i < patterns.length; i++) {
            Pattern p = patterns[i];
            int tileX = (i % tilesPerRow) * 8;
            int tileY = (i / tilesPerRow) * 8;

            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int colorIndex = p.getPixel(x, y);
                    int rgb = 0;
                    // Simple grayscale for debug
                    if (colorIndex > 0) {
                        int c = colorIndex * 16;
                        rgb = 0xFF000000 | (c << 16) | (c << 8) | c;
                    }
                    img.setRGB(tileX + x, tileY + y, rgb);
                }
            }
        }

        try {
            ImageIO.write(img, "png", new File(filename));
            System.out.println("Dumped " + filename);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
