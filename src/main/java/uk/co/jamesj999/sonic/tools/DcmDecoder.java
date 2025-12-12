package uk.co.jamesj999.sonic.tools;

public class DcmDecoder {
    private static final int[] DELTA_TABLE = {
        0, 1, 2, 4, 8, 16, 32, 64,
        -128, -1, -2, -4, -8, -16, -32, -64
    };

    /**
     * Decodes SMPS 4-bit DPCM (shared delta table). The accumulator starts at 0x80 (unsigned),
     * nibbles are applied high-first, arithmetic wraps in the unsigned 8-bit domain (no clamping),
     * and output is 8-bit unsigned PCM (0..255) ready for YM2612 DAC.
     */
    public byte[] decode(byte[] compressed) {
        byte[] output = new byte[compressed.length * 2];
        int outPos = 0;
        int accumulator = 0x80; // unsigned centre as per SMPS drivers

        for (byte b : compressed) {
            int high = (b >> 4) & 0x0F; // high nibble first
            int low = b & 0x0F;         // then low nibble

            accumulator = (accumulator + DELTA_TABLE[high]) & 0xFF; // wrap

            output[outPos++] = (byte) accumulator;

            accumulator = (accumulator + DELTA_TABLE[low]) & 0xFF; // wrap
            output[outPos++] = (byte) accumulator;
        }
        return output;
    }
}
