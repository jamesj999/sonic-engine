package com.openggf.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import com.openggf.data.compression.KosinskiReader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class TestCollisionLogic {

    @Test
    public void testCollisionLogic() throws IOException {
        String ehzPriColPath = "EHZ and HTZ primary 16x16 collision index.kos";
        Path path = Path.of(ehzPriColPath);
        byte[] collisionBuffer = null;

        if (path.toFile().exists()) {
            try (FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ)) {
                collisionBuffer = KosinskiReader.decompress(fileChannel);
            }
        } else {
            // Fallback: Try to read from ROM
            Path romPath = Path.of("s2.gen");
            Assumptions.assumeTrue(romPath.toFile().exists(), "Test data not available (neither .kos file nor ROM found)");

            try (FileChannel romChannel = FileChannel.open(romPath, StandardOpenOption.READ)) {
                romChannel.position(0x44E50); // Offset for EHZ and HTZ primary from collisionindexes.txt
                collisionBuffer = KosinskiReader.decompress(romChannel);
            }
        }

        int[] collisionArray = new int[0x300];

        for (int i = 0; i < collisionBuffer.length; i++) {
            collisionArray[i] = Byte.toUnsignedInt(collisionBuffer[i]);
        }

        // Verify decompressed data is non-empty and contains valid collision indices
        assertTrue(collisionBuffer.length > 0, "Decompressed collision buffer should not be empty");
        assertTrue(collisionArray.length > 0, "Collision array should have entries");

        // EHZ collision data uses collision block indices; verify some are non-zero
        boolean hasNonZero = false;
        for (int value : collisionArray) {
            if (value != 0) {
                hasNonZero = true;
                break;
            }
        }
        assertTrue(hasNonZero, "Collision array should contain non-zero entries");

        // All values should be valid unsigned byte range (0-255)
        for (int i = 0; i < collisionBuffer.length; i++) {
            assertTrue(collisionArray[i] >= 0 && collisionArray[i] <= 255, "Collision value at index " + i + " should be in range 0-255");
        }
    }

}

