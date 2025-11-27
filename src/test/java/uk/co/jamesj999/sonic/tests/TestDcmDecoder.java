package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.tools.DcmDecoder;
import static org.junit.Assert.*;

public class TestDcmDecoder {
    @Test
    public void testDecode() {
        DcmDecoder decoder = new DcmDecoder();
        // Delta table index 1 is +1.
        // Start 128.
        // Input: 0x11 (High=1, Low=1).
        // High nibble processed first: 128 + 1 = 129. Output[0] = 129.
        // Low nibble processed next: 129 + 1 = 130. Output[1] = 130.

        byte[] input = { 0x11 };
        byte[] output = decoder.decode(input);

        assertEquals(2, output.length);
        assertEquals((byte)129, output[0]);
        assertEquals((byte)130, output[1]);
    }
}
