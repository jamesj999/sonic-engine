package uk.co.jamesj999.sonic.tools;

public class DcmDecoder {
    private static final int[] DELTA_TABLE = {
        0, 1, 2, 4, 8, 16, 32, 64,
        -128, -1, -2, -4, -8, -16, -32, -64
    };

    public byte[] decode(byte[] compressed) {
        byte[] output = new byte[compressed.length * 2];
        int outPos = 0;
        int accumulator = 0; // Signed center

        for (byte b : compressed) {
            // High nibble first?
            // "4-bit indices ... 50% reduction"
            // Usually high nibble is first sample.
            int high = (b >> 4) & 0xF;
            int low = b & 0xF;

            accumulator += DELTA_TABLE[high];
            if (accumulator < -128) accumulator = -128;
            if (accumulator > 127) accumulator = 127;
            output[outPos++] = (byte) accumulator;

            accumulator += DELTA_TABLE[low];
            if (accumulator < -128) accumulator = -128;
            if (accumulator > 127) accumulator = 127;
            output[outPos++] = (byte) accumulator;
        }
        return output;
    }
}
