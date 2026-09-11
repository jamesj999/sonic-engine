package com.openggf.game.sonic3k.audio.smps;

import com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants;
import com.openggf.data.compression.DcmDecoder;

/**
 * DPCM decoder for Sonic 3 &amp; Knuckles DAC samples.
 *
 * <p>From Pointers.txt: "Data is DPCM compressed with the usual delta-array."
 * The delta table is identical to the one used in Sonic 2 ({@link DcmDecoder}),
 * but this class is S3K-specific and uses the constants from {@link Sonic3kSmpsConstants}.
 *
 * <p>Decoding: each input byte contains two 4-bit nibbles (high then low).
 * Each nibble indexes the delta table. The output sample accumulates:
 * {@code sample = (prevSample + deltaTable[nibble]) & 0xFF}.
 * The accumulator starts at 0x80 (unsigned center).
 */
public class Sonic3kDpcmDecoder {

    private static final int[] DELTA_TABLE = Sonic3kSmpsConstants.DPCM_DELTA_TABLE;

    /**
     * Decodes DPCM-compressed DAC data into unsigned 8-bit PCM.
     *
     * @param compressed the DPCM-compressed input bytes
     * @return decoded PCM samples (2x input length), or empty array if input is null/empty
     */
    public byte[] decode(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            return new byte[0];
        }

        byte[] output = new byte[compressed.length * 2];
        int sample = 0x80; // Start at unsigned center
        int outIdx = 0;

        for (byte b : compressed) {
            int highNibble = (b >> 4) & 0x0F;
            sample = (sample + DELTA_TABLE[highNibble]) & 0xFF;
            output[outIdx++] = (byte) sample;

            int lowNibble = b & 0x0F;
            sample = (sample + DELTA_TABLE[lowNibble]) & 0xFF;
            output[outIdx++] = (byte) sample;
        }

        return output;
    }
}
