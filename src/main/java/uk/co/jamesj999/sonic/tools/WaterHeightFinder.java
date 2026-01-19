package uk.co.jamesj999.sonic.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool to find water height data in Sonic 2 ROM.
 * 
 * Known water heights (from SonLVL/s2disasm):
 * - ARZ Act 1: 0x410 (1040 decimal)
 * - ARZ Act 2: 0x510 (1296 decimal)
 * - CPZ Act 2: Unknown initial value (710 was display value, but starts lower)
 */
public class WaterHeightFinder {

    public static void main(String[] args) throws IOException {
        Path romPath = Paths.get("Sonic The Hedgehog 2 (W) (REV01) [!].gen");
        if (!Files.exists(romPath)) {
            System.out.println("ROM not found.");
            return;
        }

        byte[] romData = Files.readAllBytes(romPath);
        System.out.println("ROM loaded: " + romData.length + " bytes\n");

        // Search for ARZ1 (0x0410) and ARZ2 (0x0510) appearing together
        // These are 16-bit big-endian values
        System.out.println("=== Searching for ARZ water height sequence (0x0410 followed by 0x0510) ===\n");

        for (int i = 0; i < romData.length - 4; i++) {
            int word1 = ((romData[i] & 0xFF) << 8) | (romData[i + 1] & 0xFF);
            int word2 = ((romData[i + 2] & 0xFF) << 8) | (romData[i + 3] & 0xFF);

            if (word1 == 0x0410 && word2 == 0x0510) {
                System.out.printf("FOUND at offset 0x%X!%n", i);
                System.out.printf("  0x%X: 0x%04X (ARZ1 = %d)%n", i, word1, word1);
                System.out.printf("  0x%X: 0x%04X (ARZ2 = %d)%n", i + 2, word2, word2);

                // Look at more extended context - 64 bytes before for full table structure
                System.out.println("\nExtended Context (32 words before to 8 words after):");
                int start = Math.max(0, i - 64);
                int end = Math.min(romData.length, i + 16);
                int idx = 0;
                for (int j = start; j < end; j += 2) {
                    int w = ((romData[j] & 0xFF) << 8) | (romData[j + 1] & 0xFF);
                    String label = "";
                    if (j == i)
                        label = " <-- ARZ1";
                    else if (j == i + 2)
                        label = " <-- ARZ2";
                    else if (w >= 0x700 && w <= 0x900)
                        label = " <-- Possible CPZ2 candidate";
                    System.out.printf("  [%2d] 0x%05X: 0x%04X (%5d)%s%n", idx++, j, w, w, label);
                }
                System.out.println();
            }
        }

        // Also search for CPZ2 patterns (values around 0x720-0x740) near 0x0410
        System.out.println("=== Searching for CPZ2 candidates (0x0700-0x0740) followed by 0x0410 (ARZ1) ===\n");

        for (int i = 0; i < romData.length - 6; i++) {
            int word1 = ((romData[i] & 0xFF) << 8) | (romData[i + 1] & 0xFF);
            int word2 = ((romData[i + 2] & 0xFF) << 8) | (romData[i + 3] & 0xFF);
            int word3 = ((romData[i + 4] & 0xFF) << 8) | (romData[i + 5] & 0xFF);

            // Look for pattern: CPZ2 (0x700-0x740), ARZ1 (0x410), ARZ2 (0x510)
            if (word1 >= 0x700 && word1 <= 0x740 && word2 == 0x0410 && word3 == 0x0510) {
                System.out.printf("FOUND WATER TABLE at offset 0x%X!%n", i);
                System.out.printf("  CPZ2: 0x%04X (%d) at 0x%X%n", word1, word1, i);
                System.out.printf("  ARZ1: 0x%04X (%d) at 0x%X%n", word2, word2, i + 2);
                System.out.printf("  ARZ2: 0x%04X (%d) at 0x%X%n", word3, word3, i + 4);
                System.out.println();
            }
        }

        // Search for higher values (If 0x710 is wrong, try 0x740-0x780 range)
        // Based on visual comparison, water should be about 48 pixels lower
        System.out.println("=== Searching for CPZ2 candidates (0x0740-0x0780) near ARZ entries ===\n");

        for (int i = 0; i < romData.length - 30; i++) {
            int word = ((romData[i] & 0xFF) << 8) | (romData[i + 1] & 0xFF);

            // Check for values in the target range
            if (word >= 0x0740 && word <= 0x0780) {
                // Check if ARZ1/ARZ2 appear within 30 bytes
                for (int k = 2; k <= 30; k += 2) {
                    if (i + k + 4 >= romData.length)
                        break;
                    int wordArz1 = ((romData[i + k] & 0xFF) << 8) | (romData[i + k + 1] & 0xFF);
                    int wordArz2 = ((romData[i + k + 2] & 0xFF) << 8) | (romData[i + k + 3] & 0xFF);

                    if (wordArz1 == 0x0410 && wordArz2 == 0x0510) {
                        System.out.printf("FOUND at 0x%05X: 0x%04X (%d) - ARZ at offset +%d%n", i, word, word, k);
                    }
                }
            }
        }

        // Also dump all values between 0x0700 and 0x0800 found in the first 0x10000
        // bytes
        System.out.println("\n=== All values 0x0700-0x0800 in first 0x10000 bytes ===");
        for (int i = 0; i < Math.min(romData.length, 0x10000) - 2; i += 2) {
            int word = ((romData[i] & 0xFF) << 8) | (romData[i + 1] & 0xFF);
            if (word >= 0x0700 && word <= 0x0800) {
                System.out.printf("  0x%05X: 0x%04X (%d)%n", i, word, word);
            }
        }

        System.out.println("\nSearch complete.");
    }
}
