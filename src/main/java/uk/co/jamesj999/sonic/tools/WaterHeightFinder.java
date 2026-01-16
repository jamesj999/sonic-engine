package uk.co.jamesj999.sonic.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class WaterHeightFinder {

    public static void main(String[] args) throws IOException {
        Path romPath = Paths.get("Sonic The Hedgehog 2 (W) (REV01) [!].gen");
        if (!Files.exists(romPath)) {
            System.out.println("ROM not found.");
            return;
        }

        byte[] romData = Files.readAllBytes(romPath);

        // Search for 0x0510 (ARZ2 target) and 0x0710 (CPZ2 target, maybe?)
        // Or maybe CPZ2 is 0x0728 (old hardcode 710 is 0x2C6... user said "Same is true
        // for CPZ2")
        // "CPZ Act 2: ~0x2C6 = 710" -> "710 in hex" = 0x710.

        System.out.println("Searching for 0x0510 (ARZ2)...");
        List<Integer> arz2Matches = findSequence(romData, new byte[] { 0x05, 0x10 });
        for (int addr : arz2Matches) {
            System.out.printf("  0x%X\n", addr);
        }

        System.out.println("Searching for 0x0710 (CPZ2?)...");
        List<Integer> cpz2Matches = findSequence(romData, new byte[] { 0x07, 0x10 });
        for (int addr : cpz2Matches) {
            System.out.printf("  0x%X\n", addr);
        }

        // Also look for values that might be ARZ1.
        // User said old hardcode 1069 (0x42D) was "really close".
        // "it's too low" -> correct value is HIGHER (smaller Y).
        // Maybe 1069 hex? 0x1069 (Too big).
        // Maybe "1069 is close to hex value"? 0x42D.
        // If 510 (dec) -> 0x510 (hex) was the pattern.
        // Then 1069 (dec) -> 0x1069 (hex)? No.
        // Wait, 1069 is 0x42D.
        // Maybe the value is around 1069?
        // Let's look around the matches.

        for (int addr : arz2Matches) {
            dumpRegion(romData, addr - 32, addr + 32);
        }
    }

    private static List<Integer> findSequence(byte[] data, byte[] sequence) {
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < data.length - sequence.length; i++) {
            boolean match = true;
            for (int j = 0; j < sequence.length; j++) {
                if (data[i + j] != sequence[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                matches.add(i);
            }
        }
        return matches;
    }

    private static void dumpRegion(byte[] data, int start, int end) {
        System.out.println("Dump " + Integer.toHexString(start) + " - " + Integer.toHexString(end));
        for (int i = start; i < end; i++) {
            if (i % 16 == 0)
                System.out.printf("\n%05X: ", i);
            if (i >= 0 && i < data.length) {
                System.out.printf("%02X ", data[i]);
            }
        }
        System.out.println("\n");
    }
}
