package uk.co.jamesj999.sonic.audio.synth;

import java.util.Arrays;

/**
 * Delta-based band-limited step buffer matching Genesis Plus GX blip_buf.c
 * for optimal PSG synthesis quality.
 */
public class BlipDeltaBuffer {
    // Constants from blip_buf.c
    private static final int HALF_WIDTH = 8;
    private static final int PHASE_BITS = 5;
    private static final int PHASE_COUNT = 1 << PHASE_BITS;  // 32
    private static final int DELTA_BITS = 15;
    private static final int DELTA_UNIT = 1 << DELTA_BITS;   // 32768
    private static final int FRAC_BITS = 20;
    private static final int TIME_UNIT = 1 << FRAC_BITS;     // 1048576
    private static final int PHASE_SHIFT = FRAC_BITS - PHASE_BITS;  // 15
    private static final int BUF_EXTRA = HALF_WIDTH * 2 + 2;

    // High-pass filter constant (GPGX bass_shift = 9)
    // This DC-blocking filter removes low-frequency drift and improves crispness
    private static final int BASS_SHIFT = 9;

    // Kernel from blip_buf.c: Sinc_Generator(0.9, 0.55, 4.5)
    // Stored as contiguous array for proper C-style pointer arithmetic emulation
    private static final short[] BL_STEP = {
        // Phase 0
           43, -115,  350, -488, 1136, -914, 5861,21022,
        // Phase 1
           44, -118,  348, -473, 1076, -799, 5274,21001,
        // Phase 2
           45, -121,  344, -454, 1011, -677, 4706,20936,
        // Phase 3
           46, -122,  336, -431,  942, -549, 4156,20829,
        // Phase 4
           47, -123,  327, -404,  868, -418, 3629,20679,
        // Phase 5
           47, -122,  316, -375,  792, -285, 3124,20488,
        // Phase 6
           47, -120,  303, -344,  714, -151, 2644,20256,
        // Phase 7
           46, -117,  289, -310,  634,  -17, 2188,19985,
        // Phase 8
           46, -114,  273, -275,  553,  117, 1758,19675,
        // Phase 9
           44, -108,  255, -237,  471,  247, 1356,19327,
        // Phase 10
           43, -103,  237, -199,  390,  373,  981,18944,
        // Phase 11
           42,  -98,  218, -160,  310,  495,  633,18527,
        // Phase 12
           40,  -91,  198, -121,  231,  611,  314,18078,
        // Phase 13
           38,  -84,  178,  -81,  153,  722,   22,17599,
        // Phase 14
           36,  -76,  157,  -43,   80,  824, -241,17092,
        // Phase 15
           34,  -68,  135,   -3,    8,  919, -476,16558,
        // Phase 16
           32,  -61,  115,   34,  -60, 1006, -683,16001,
        // Phase 17
           29,  -52,   94,   70, -123, 1083, -862,15422,
        // Phase 18
           27,  -44,   73,  106, -184, 1152,-1015,14824,
        // Phase 19
           25,  -36,   53,  139, -239, 1211,-1142,14210,
        // Phase 20
           22,  -27,   34,  170, -290, 1261,-1244,13582,
        // Phase 21
           20,  -20,   16,  199, -335, 1301,-1322,12942,
        // Phase 22
           18,  -12,   -3,  226, -375, 1331,-1376,12293,
        // Phase 23
           15,   -4,  -19,  250, -410, 1351,-1408,11638,
        // Phase 24
           13,    3,  -35,  272, -439, 1361,-1419,10979,
        // Phase 25
           11,    9,  -49,  292, -464, 1362,-1410,10319,
        // Phase 26
            9,   16,  -63,  309, -483, 1354,-1383, 9660,
        // Phase 27
            7,   22,  -75,  322, -496, 1337,-1339, 9005,
        // Phase 28
            6,   26,  -85,  333, -504, 1312,-1280, 8355,
        // Phase 29
            4,   31,  -94,  341, -507, 1278,-1205, 7713,
        // Phase 30
            3,   35, -102,  347, -506, 1238,-1119, 7082,
        // Phase 31
            1,   40, -110,  350, -499, 1190,-1021, 6464,
        // Phase 32 (boundary)
            0,   43, -115,  350, -488, 1136, -914, 5861
    };

    private long factor;
    private long offset;
    private int[] bufferL;
    private int[] bufferR;
    private int size;
    private int integL;
    private int integR;

    public BlipDeltaBuffer(double clockRate, double sampleRate) {
        setRates(clockRate, sampleRate);
        ensureCapacity(1024);
    }

    public void setRates(double clockRate, double sampleRate) {
        double factor = TIME_UNIT * sampleRate / clockRate;
        long rounded = (long) factor;
        if (rounded < factor) {
            rounded++;
        }
        this.factor = rounded;
    }

    public void reset(double clockRate, double sampleRate) {
        setRates(clockRate, sampleRate);
        clear();
    }

    public void clear() {
        if (bufferL != null) {
            Arrays.fill(bufferL, 0);
            Arrays.fill(bufferR, 0);
        }
        offset = factor / 2;
        integL = 0;
        integR = 0;
    }

    public void ensureCapacity(int neededSamples) {
        int needed = neededSamples + BUF_EXTRA;
        if (bufferL != null && size >= needed) {
            return;
        }
        int[] newL = new int[needed];
        int[] newR = new int[needed];
        if (bufferL != null) {
            System.arraycopy(bufferL, 0, newL, 0, Math.min(size, needed));
            System.arraycopy(bufferR, 0, newR, 0, Math.min(size, needed));
        }
        bufferL = newL;
        bufferR = newR;
        size = needed;
    }

    /**
     * Add delta using simple linear interpolation (GPGX blip_add_delta_fast).
     * No sinc filtering - preserves more high-frequency content including aliasing.
     * Results in "rawer", "brighter" sound characteristic of Genesis PSG.
     */
    public void addDeltaFast(int clockTime, int deltaL, int deltaR) {
        if ((deltaL | deltaR) == 0) {
            return;
        }

        // Convert to fixed-point position
        long fixed = (long) clockTime * factor + offset;
        if (fixed < 0) {
            fixed = 0;
        }

        // Interpolation factor
        int interp = (int) (fixed >> (FRAC_BITS - DELTA_BITS)) & (DELTA_UNIT - 1);

        // Buffer position
        int pos = (int) (fixed >> FRAC_BITS);
        if (pos < 0) {
            pos = 0;
        }

        if (pos + 9 > size) {
            ensureCapacity(pos + 64);
        }

        // Simple 2-tap linear interpolation (matches GPGX blip_add_delta_fast)
        int delta2L = deltaL * interp;
        int delta2R = deltaR * interp;
        bufferL[pos + 7] += deltaL * DELTA_UNIT - delta2L;
        bufferL[pos + 8] += delta2L;
        bufferR[pos + 7] += deltaR * DELTA_UNIT - delta2R;
        bufferR[pos + 8] += delta2R;
    }

    /**
     * Add delta using GPGX-style integer arithmetic with phase interpolation.
     * Uses integer clock times to eliminate floating-point precision loss.
     */
    public void addDelta(int clockTime, int deltaL, int deltaR) {
        if ((deltaL | deltaR) == 0) {
            return;
        }

        // Convert to fixed-point position using pure integer math (matches GPGX)
        long fixed = (long) clockTime * factor + offset;

        // Protect against negative fixed values (can happen if offset drifts negative)
        if (fixed < 0) {
            fixed = 0;
        }

        // Extract phase (0-31)
        int phase = (int) ((fixed >> PHASE_SHIFT) & (PHASE_COUNT - 1));

        // Kernel pointers (as array offsets)
        int inPtr = phase * HALF_WIDTH;
        int revPtr = (PHASE_COUNT - phase) * HALF_WIDTH;

        // Interpolation factor between phases
        int interp = (int) (fixed & (DELTA_UNIT - 1));

        // Buffer position
        int pos = (int) (fixed >> FRAC_BITS);

        // Bounds protection: ensure pos is non-negative
        if (pos < 0) {
            pos = 0;
        }

        if (pos + HALF_WIDTH * 2 > size) {
            ensureCapacity(pos + HALF_WIDTH * 2 + 64);
        }

        // Split delta for interpolation between current and next phase
        int delta2L = (deltaL * interp) >> DELTA_BITS;
        int dL = deltaL - delta2L;
        int delta2R = (deltaR * interp) >> DELTA_BITS;
        int dR = deltaR - delta2R;

        // First 8 taps: in[i]*delta + in[i+8]*delta2 (next phase)
        bufferL[pos+0] += BL_STEP[inPtr+0]*dL + BL_STEP[inPtr+8]*delta2L;
        bufferL[pos+1] += BL_STEP[inPtr+1]*dL + BL_STEP[inPtr+9]*delta2L;
        bufferL[pos+2] += BL_STEP[inPtr+2]*dL + BL_STEP[inPtr+10]*delta2L;
        bufferL[pos+3] += BL_STEP[inPtr+3]*dL + BL_STEP[inPtr+11]*delta2L;
        bufferL[pos+4] += BL_STEP[inPtr+4]*dL + BL_STEP[inPtr+12]*delta2L;
        bufferL[pos+5] += BL_STEP[inPtr+5]*dL + BL_STEP[inPtr+13]*delta2L;
        bufferL[pos+6] += BL_STEP[inPtr+6]*dL + BL_STEP[inPtr+14]*delta2L;
        bufferL[pos+7] += BL_STEP[inPtr+7]*dL + BL_STEP[inPtr+15]*delta2L;

        bufferR[pos+0] += BL_STEP[inPtr+0]*dR + BL_STEP[inPtr+8]*delta2R;
        bufferR[pos+1] += BL_STEP[inPtr+1]*dR + BL_STEP[inPtr+9]*delta2R;
        bufferR[pos+2] += BL_STEP[inPtr+2]*dR + BL_STEP[inPtr+10]*delta2R;
        bufferR[pos+3] += BL_STEP[inPtr+3]*dR + BL_STEP[inPtr+11]*delta2R;
        bufferR[pos+4] += BL_STEP[inPtr+4]*dR + BL_STEP[inPtr+12]*delta2R;
        bufferR[pos+5] += BL_STEP[inPtr+5]*dR + BL_STEP[inPtr+13]*delta2R;
        bufferR[pos+6] += BL_STEP[inPtr+6]*dR + BL_STEP[inPtr+14]*delta2R;
        bufferR[pos+7] += BL_STEP[inPtr+7]*dR + BL_STEP[inPtr+15]*delta2R;

        // Last 8 taps: rev[7-i]*delta + rev[7-i-8]*delta2 (previous phase)
        bufferL[pos+8]  += BL_STEP[revPtr+7]*dL + BL_STEP[revPtr-1]*delta2L;
        bufferL[pos+9]  += BL_STEP[revPtr+6]*dL + BL_STEP[revPtr-2]*delta2L;
        bufferL[pos+10] += BL_STEP[revPtr+5]*dL + BL_STEP[revPtr-3]*delta2L;
        bufferL[pos+11] += BL_STEP[revPtr+4]*dL + BL_STEP[revPtr-4]*delta2L;
        bufferL[pos+12] += BL_STEP[revPtr+3]*dL + BL_STEP[revPtr-5]*delta2L;
        bufferL[pos+13] += BL_STEP[revPtr+2]*dL + BL_STEP[revPtr-6]*delta2L;
        bufferL[pos+14] += BL_STEP[revPtr+1]*dL + BL_STEP[revPtr-7]*delta2L;
        bufferL[pos+15] += BL_STEP[revPtr+0]*dL + BL_STEP[revPtr-8]*delta2L;

        bufferR[pos+8]  += BL_STEP[revPtr+7]*dR + BL_STEP[revPtr-1]*delta2R;
        bufferR[pos+9]  += BL_STEP[revPtr+6]*dR + BL_STEP[revPtr-2]*delta2R;
        bufferR[pos+10] += BL_STEP[revPtr+5]*dR + BL_STEP[revPtr-3]*delta2R;
        bufferR[pos+11] += BL_STEP[revPtr+4]*dR + BL_STEP[revPtr-4]*delta2R;
        bufferR[pos+12] += BL_STEP[revPtr+3]*dR + BL_STEP[revPtr-5]*delta2R;
        bufferR[pos+13] += BL_STEP[revPtr+2]*dR + BL_STEP[revPtr-6]*delta2R;
        bufferR[pos+14] += BL_STEP[revPtr+1]*dR + BL_STEP[revPtr-7]*delta2R;
        bufferR[pos+15] += BL_STEP[revPtr+0]*dR + BL_STEP[revPtr-8]*delta2R;
    }

    public void readSamples(int[] left, int[] right, int count) {
        int available = (int) (offset >> FRAC_BITS);
        if (count > available) {
            count = available;
        }
        if (count <= 0) {
            return;
        }

        for (int i = 0; i < count; i++) {
            // Extract sample (arithmetic right shift)
            int sL = integL >> DELTA_BITS;
            int sR = integR >> DELTA_BITS;

            // Integrate
            integL += bufferL[i];
            integR += bufferR[i];
            bufferL[i] = 0;
            bufferR[i] = 0;

            // Clamp to 16-bit
            if (sL > 32767) sL = 32767;
            else if (sL < -32768) sL = -32768;
            if (sR > 32767) sR = 32767;
            else if (sR < -32768) sR = -32768;

            left[i] += sL;
            right[i] += sR;

            // High-pass filter: remove DC offset for crispness (GPGX style)
            integL -= sL << (DELTA_BITS - BASS_SHIFT);
            integR -= sR << (DELTA_BITS - BASS_SHIFT);
        }

        // Shift buffer
        int remain = available + BUF_EXTRA - count;
        if (remain > 0 && count < size) {
            // Ensure source range [count, count+remain) doesn't exceed buffer size
            if (count + remain > size) {
                remain = size - count;
            }
            if (remain > 0) {
                System.arraycopy(bufferL, count, bufferL, 0, remain);
                System.arraycopy(bufferR, count, bufferR, 0, remain);
                Arrays.fill(bufferL, remain, size, 0);
                Arrays.fill(bufferR, remain, size, 0);
            }
        }

        offset -= (long) count << FRAC_BITS;
    }

    /**
     * End the current frame and advance the buffer position.
     * Uses integer clocks to eliminate floating-point precision loss.
     */
    public void endFrame(int clocks) {
        offset += (long) clocks * factor;
    }
}
