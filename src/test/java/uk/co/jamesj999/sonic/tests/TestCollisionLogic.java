package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import org.junit.Assume;
import uk.co.jamesj999.sonic.tools.KosinskiReader;

import java.io.IOException;
import java.nio.ByteBuffer;
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
                collisionBuffer = KosinskiReader.decompress(fileChannel, true);
            }
        } else {
            // Fallback: Try to read from ROM
            Path romPath = Path.of("Sonic The Hedgehog 2 (W) (REV01) [!].gen");
            Assume.assumeTrue("Test data not available (neither .kos file nor ROM found)", romPath.toFile().exists());

            try (FileChannel romChannel = FileChannel.open(romPath, StandardOpenOption.READ)) {
                romChannel.position(0x44E50); // Offset for EHZ and HTZ primary from collisionindexes.txt
                collisionBuffer = KosinskiReader.decompress(romChannel, true);
            }
        }

        int[] collisionArray = new int[0x300];

        for (int i = 0; i < collisionBuffer.length; i++) {
            collisionArray[i] = Byte.toUnsignedInt(collisionBuffer[i]);
        }

    }

}
