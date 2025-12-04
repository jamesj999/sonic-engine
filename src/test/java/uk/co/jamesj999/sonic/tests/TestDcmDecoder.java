package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.tools.DcmDecoder;
import static org.junit.Assert.*;

public class TestDcmDecoder {
    @Test
    public void testDecode() {
        DcmDecoder decoder = new DcmDecoder();
        // Delta table index 1 is +1.
        // Start 0x80 (unsigned centre).
        // Input: 0x11 (High=1, Low=1). Low nibble is processed first.
        // Low nibble: 0x80 + 1 = 0x81. Output[0] = 0x81.
        // High nibble: 0x81 + 1 = 0x82. Output[1] = 0x82.

        byte[] input = { 0x11 };
        byte[] output = decoder.decode(input);

        assertEquals(2, output.length);
        assertEquals((byte)0x81, output[0]);
        assertEquals((byte)0x82, output[1]);
    }
}
