package uk.co.jamesj999.sonic.tools;

public class DcmDecoder {
    private static final int[] DELTA_TABLE = {
        0, 1, 2, 4, 8, 16, 32, 64,
        -128, -1, -2, -4, -8, -16, -32, -64
    };

    public byte[] decode(byte[] compressed) {
        byte[] output = new byte[compressed.length * 2];
        int outPos = 0;
        int accumulator = 0x80; // Unsigned 8-bit centre

        for (byte b : compressed) {
            int low = b & 0x0F;       // Process low nibble first (matches many SMPS drivers)
            int high = (b >> 4) & 0xF; // Then high nibble

            accumulator += DELTA_TABLE[low];
            if (accumulator < 0) accumulator = 0;
            if (accumulator > 255) accumulator = 255;
            output[outPos++] = (byte) accumulator;

            accumulator += DELTA_TABLE[high];
            if (accumulator < 0) accumulator = 0;
            if (accumulator > 255) accumulator = 255;
            output[outPos++] = (byte) accumulator;
        }
        return output;
    }
}
