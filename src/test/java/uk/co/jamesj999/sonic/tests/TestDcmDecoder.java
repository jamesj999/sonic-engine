package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.tools.DcmDecoder;
import static org.junit.Assert.*;

public class TestDcmDecoder {
    @Test
    public void testDecode() {
        DcmDecoder decoder = new DcmDecoder();
        // Delta table index 1 is +1.
        // Start 0 (signed centre).
        // Input: 0x11 (High=1, Low=1).
        // High nibble processed first: 0 + 1 = 1. Output[0] = 1.
        // Low nibble processed next: 1 + 1 = 2. Output[1] = 2.

        byte[] input = { 0x11 };
        byte[] output = decoder.decode(input);

        assertEquals(2, output.length);
        assertEquals((byte)1, output[0]);
        assertEquals((byte)2, output[1]);
    }
}
