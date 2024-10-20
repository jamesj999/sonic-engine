package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
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
        System.out.println(path.toAbsolutePath().toString());
        FileChannel fileChannel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);

        int[] collisionArray = new int[0x300];

        byte[] collisionBuffer = KosinskiReader.decompress(fileChannel, true);

        for (int i=0; i< collisionBuffer.length; i++) {
            collisionArray[i] = Byte.toUnsignedInt(collisionBuffer[i]);
        }

    }


}
