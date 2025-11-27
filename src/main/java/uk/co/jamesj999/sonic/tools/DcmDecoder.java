package uk.co.jamesj999.sonic.tools;

public class DcmDecoder {
    private static final int[] DELTA_TABLE = {
        0, 1, 2, 4, 8, 16, 32, 64,
        -128, -1, -2, -4, -8, -16, -32, -64
    };

    public byte[] decode(byte[] compressed) {
        byte[] output = new byte[compressed.length * 2];
        int outPos = 0;
        int accumulator = 0x80; // Start at center? Or 0?
        // "inherent loss of precision compared to the 8-bit unsigned LPCM"
        // Usually DPCM starts at 0x80 for unsigned 8-bit output.

        for (byte b : compressed) {
            // High nibble first?
            // "4-bit indices ... 50% reduction"
            // Usually high nibble is first sample.
            int high = (b >> 4) & 0xF;
            int low = b & 0xF;

            accumulator += DELTA_TABLE[high];
            if (accumulator < 0) accumulator = 0;
            if (accumulator > 255) accumulator = 255;
            output[outPos++] = (byte)accumulator;

            accumulator += DELTA_TABLE[low];
            if (accumulator < 0) accumulator = 0;
            if (accumulator > 255) accumulator = 255;
            output[outPos++] = (byte)accumulator;
        }
        return output;
    }
}
