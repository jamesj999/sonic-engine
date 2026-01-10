package uk.co.jamesj999.sonic.tools;

import org.junit.Test;
import uk.co.jamesj999.sonic.data.Rom;

import java.io.File;
import java.io.IOException;

public class FindLivesArtAddr {

    @Test
    public void findAddress() throws IOException {
        String romPath = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";
        File f = new File(romPath);
        if (!f.exists()) {
            System.err.println("ROM NOT FOUND at " + f.getAbsolutePath());
            return;
        }

        Rom rom = new Rom();
        if (!rom.open(romPath)) {
            System.err.println("Failed to open ROM");
            return;
        }

        byte[] searchBytes = new byte[] { 0, 0, 0, 0, 0, 0, 0x66, 0x10, 0, 0x06, 0x66, 0x10, 0, 0x61, 0x66, 0x10 };
        byte[] romData = rom.readAllBytes();

        System.out.println("Searching " + romData.length + " bytes...");

        int foundAddr = -1;
        for (int i = 0; i < romData.length - searchBytes.length; i++) {
            boolean match = true;
            for (int j = 0; j < searchBytes.length; j++) {
                if (romData[i + j] != searchBytes[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                foundAddr = i;
                break;
            }
        }

        if (foundAddr != -1) {
            // foundAddr points to Digit 4 (offset 0x80 = 128). Start is -128.
            int startAddr = foundAddr - 128;
            System.out.println("FOUND LIVES ART ADDR: 0x" + Integer.toHexString(startAddr).toUpperCase());
            System.err.println("FOUND LIVES ART ADDR: 0x" + Integer.toHexString(startAddr).toUpperCase());
        } else {
            System.err.println("LIVES ART ADDR NOT FOUND");
        }
    }
}
