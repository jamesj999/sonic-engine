package uk.co.jamesj999.sonic.tools;

import uk.co.jamesj999.sonic.data.Rom;
import org.junit.Test;

import java.io.IOException;

public class RomScannerTest {

    @Test
    public void scanForObjectsLayout() throws IOException {
        String romPath = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";
        Rom rom = new Rom();
        try {
            rom.open(romPath);
        } catch (Exception e) {
            System.out.println("Could not open ROM: " + e.getMessage());
            return;
        }

        // Scan the entire ROM for the pattern
        // Pattern: E2 18 E0 48 41 F9
        byte[] buffer = rom.readBytes(0, 0x100000); // Read 1MB (Full ROM)

        System.out.println("Scanning ROM for ObjectsManager_Init pattern...");
        boolean found = false;
        for (int i = 0; i < buffer.length - 10; i++) {
            if (buffer[i] == (byte)0xE2 && buffer[i+1] == (byte)0x18 &&
                buffer[i+2] == (byte)0xE0 && buffer[i+3] == (byte)0x48 &&
                buffer[i+4] == (byte)0x41 && buffer[i+5] == (byte)0xF9) {

                int addr = ((buffer[i+6] & 0xFF) << 24) |
                           ((buffer[i+7] & 0xFF) << 16) |
                           ((buffer[i+8] & 0xFF) << 8) |
                           (buffer[i+9] & 0xFF);
                System.out.printf("Objects_Layout Address found at offset 0x%X: 0x%08X%n", i, addr);
                found = true;
            }
        }
        if (!found) {
            System.out.println("Pattern not found.");
        }
    }
}
