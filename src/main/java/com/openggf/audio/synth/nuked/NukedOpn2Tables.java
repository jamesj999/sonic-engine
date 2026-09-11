/*
 * Copyright (C) 2017-2022 Alexey Khokholov (Nuke.YKT)
 * Java port Copyright (C) 2026 the OpenGGF contributors
 * Modified 2026-08 by the OpenGGF contributors: translated from C to Java;
 * chip type made per instance; state copy helpers and value equality added.
 *
 * This file is part of Nuked OPN2 (Java port).
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 *  Nuked OPN2(Yamaha YM3438) emulator.
 *  Thanks:
 *      Silicon Pr0n:
 *          Yamaha YM3438 decap and die shot(digshadow).
 *      OPLx decapsulated(Matthew Gambrell, Olli Niemitalo):
 *          OPL2 ROMs.
 *
 * Upstream: https://github.com/nukeykt/Nuked-OPN2 at 335747d78cb0abbc3b55b004e62dad9763140115
 * (ym3438.c version 1.0.12, sha256 8fa385546f0f2d1c975d097002af00cd729ae2ae097c068e9c883ce08ddf3a76).
 */
package com.openggf.audio.synth.nuked;

/**
 * The constant ROM tables of {@code ym3438.c}, reproduced byte for byte.
 *
 * <p>Every table carries a {@code // ym3438.c:NNN} citation naming the line of
 * the pinned upstream source it was copied from. The tables are read-only and
 * shared by every {@link NukedOpn2} instance; nothing in this class is ever
 * written after class initialisation.
 */
final class NukedOpn2Tables {

    private NukedOpn2Tables() {
    }

    /** Envelope generator state numbers. */
    // ym3438.c:35
    static final int EG_NUM_ATTACK = 0;
    static final int EG_NUM_DECAY = 1;
    static final int EG_NUM_SUSTAIN = 2;
    static final int EG_NUM_RELEASE = 3;

    /** logsin table (quarter-wave log-sine, 12-bit). */
    // ym3438.c:43
    static final int[] LOGSINROM = {
        0x859, 0x6c3, 0x607, 0x58b, 0x52e, 0x4e4, 0x4a6, 0x471,
        0x443, 0x41a, 0x3f5, 0x3d3, 0x3b5, 0x398, 0x37e, 0x365,
        0x34e, 0x339, 0x324, 0x311, 0x2ff, 0x2ed, 0x2dc, 0x2cd,
        0x2bd, 0x2af, 0x2a0, 0x293, 0x286, 0x279, 0x26d, 0x261,
        0x256, 0x24b, 0x240, 0x236, 0x22c, 0x222, 0x218, 0x20f,
        0x206, 0x1fd, 0x1f5, 0x1ec, 0x1e4, 0x1dc, 0x1d4, 0x1cd,
        0x1c5, 0x1be, 0x1b7, 0x1b0, 0x1a9, 0x1a2, 0x19b, 0x195,
        0x18f, 0x188, 0x182, 0x17c, 0x177, 0x171, 0x16b, 0x166,
        0x160, 0x15b, 0x155, 0x150, 0x14b, 0x146, 0x141, 0x13c,
        0x137, 0x133, 0x12e, 0x129, 0x125, 0x121, 0x11c, 0x118,
        0x114, 0x10f, 0x10b, 0x107, 0x103, 0x0ff, 0x0fb, 0x0f8,
        0x0f4, 0x0f0, 0x0ec, 0x0e9, 0x0e5, 0x0e2, 0x0de, 0x0db,
        0x0d7, 0x0d4, 0x0d1, 0x0cd, 0x0ca, 0x0c7, 0x0c4, 0x0c1,
        0x0be, 0x0bb, 0x0b8, 0x0b5, 0x0b2, 0x0af, 0x0ac, 0x0a9,
        0x0a7, 0x0a4, 0x0a1, 0x09f, 0x09c, 0x099, 0x097, 0x094,
        0x092, 0x08f, 0x08d, 0x08a, 0x088, 0x086, 0x083, 0x081,
        0x07f, 0x07d, 0x07a, 0x078, 0x076, 0x074, 0x072, 0x070,
        0x06e, 0x06c, 0x06a, 0x068, 0x066, 0x064, 0x062, 0x060,
        0x05e, 0x05c, 0x05b, 0x059, 0x057, 0x055, 0x053, 0x052,
        0x050, 0x04e, 0x04d, 0x04b, 0x04a, 0x048, 0x046, 0x045,
        0x043, 0x042, 0x040, 0x03f, 0x03e, 0x03c, 0x03b, 0x039,
        0x038, 0x037, 0x035, 0x034, 0x033, 0x031, 0x030, 0x02f,
        0x02e, 0x02d, 0x02b, 0x02a, 0x029, 0x028, 0x027, 0x026,
        0x025, 0x024, 0x023, 0x022, 0x021, 0x020, 0x01f, 0x01e,
        0x01d, 0x01c, 0x01b, 0x01a, 0x019, 0x018, 0x017, 0x017,
        0x016, 0x015, 0x014, 0x014, 0x013, 0x012, 0x011, 0x011,
        0x010, 0x00f, 0x00f, 0x00e, 0x00d, 0x00d, 0x00c, 0x00c,
        0x00b, 0x00a, 0x00a, 0x009, 0x009, 0x008, 0x008, 0x007,
        0x007, 0x007, 0x006, 0x006, 0x005, 0x005, 0x005, 0x004,
        0x004, 0x004, 0x003, 0x003, 0x003, 0x002, 0x002, 0x002,
        0x002, 0x001, 0x001, 0x001, 0x001, 0x001, 0x001, 0x001,
        0x000, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000
    };

    /** exp table (10-bit mantissa). */
    // ym3438.c:79
    static final int[] EXPROM = {
        0x000, 0x003, 0x006, 0x008, 0x00b, 0x00e, 0x011, 0x014,
        0x016, 0x019, 0x01c, 0x01f, 0x022, 0x025, 0x028, 0x02a,
        0x02d, 0x030, 0x033, 0x036, 0x039, 0x03c, 0x03f, 0x042,
        0x045, 0x048, 0x04b, 0x04e, 0x051, 0x054, 0x057, 0x05a,
        0x05d, 0x060, 0x063, 0x066, 0x069, 0x06c, 0x06f, 0x072,
        0x075, 0x078, 0x07b, 0x07e, 0x082, 0x085, 0x088, 0x08b,
        0x08e, 0x091, 0x094, 0x098, 0x09b, 0x09e, 0x0a1, 0x0a4,
        0x0a8, 0x0ab, 0x0ae, 0x0b1, 0x0b5, 0x0b8, 0x0bb, 0x0be,
        0x0c2, 0x0c5, 0x0c8, 0x0cc, 0x0cf, 0x0d2, 0x0d6, 0x0d9,
        0x0dc, 0x0e0, 0x0e3, 0x0e7, 0x0ea, 0x0ed, 0x0f1, 0x0f4,
        0x0f8, 0x0fb, 0x0ff, 0x102, 0x106, 0x109, 0x10c, 0x110,
        0x114, 0x117, 0x11b, 0x11e, 0x122, 0x125, 0x129, 0x12c,
        0x130, 0x134, 0x137, 0x13b, 0x13e, 0x142, 0x146, 0x149,
        0x14d, 0x151, 0x154, 0x158, 0x15c, 0x160, 0x163, 0x167,
        0x16b, 0x16f, 0x172, 0x176, 0x17a, 0x17e, 0x181, 0x185,
        0x189, 0x18d, 0x191, 0x195, 0x199, 0x19c, 0x1a0, 0x1a4,
        0x1a8, 0x1ac, 0x1b0, 0x1b4, 0x1b8, 0x1bc, 0x1c0, 0x1c4,
        0x1c8, 0x1cc, 0x1d0, 0x1d4, 0x1d8, 0x1dc, 0x1e0, 0x1e4,
        0x1e8, 0x1ec, 0x1f0, 0x1f5, 0x1f9, 0x1fd, 0x201, 0x205,
        0x209, 0x20e, 0x212, 0x216, 0x21a, 0x21e, 0x223, 0x227,
        0x22b, 0x230, 0x234, 0x238, 0x23c, 0x241, 0x245, 0x249,
        0x24e, 0x252, 0x257, 0x25b, 0x25f, 0x264, 0x268, 0x26d,
        0x271, 0x276, 0x27a, 0x27f, 0x283, 0x288, 0x28c, 0x291,
        0x295, 0x29a, 0x29e, 0x2a3, 0x2a8, 0x2ac, 0x2b1, 0x2b5,
        0x2ba, 0x2bf, 0x2c4, 0x2c8, 0x2cd, 0x2d2, 0x2d6, 0x2db,
        0x2e0, 0x2e5, 0x2e9, 0x2ee, 0x2f3, 0x2f8, 0x2fd, 0x302,
        0x306, 0x30b, 0x310, 0x315, 0x31a, 0x31f, 0x324, 0x329,
        0x32e, 0x333, 0x338, 0x33d, 0x342, 0x347, 0x34c, 0x351,
        0x356, 0x35b, 0x360, 0x365, 0x36a, 0x370, 0x375, 0x37a,
        0x37f, 0x384, 0x38a, 0x38f, 0x394, 0x399, 0x39f, 0x3a4,
        0x3a9, 0x3ae, 0x3b4, 0x3b9, 0x3bf, 0x3c4, 0x3c9, 0x3cf,
        0x3d4, 0x3da, 0x3df, 0x3e4, 0x3ea, 0x3ef, 0x3f5, 0x3fa
    };

    /** Note table: fnum bits 10..7 to the 2-bit note part of the key code. */
    // ym3438.c:115
    static final int[] FN_NOTE = {
        0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 3, 3, 3, 3, 3, 3
    };

    /** Envelope generator high-rate step pattern, indexed [rate &amp; 3][eg_timer_low_lock]. */
    // ym3438.c:120
    static final int[][] EG_STEPHI = {
        { 0, 0, 0, 0 },
        { 1, 0, 0, 0 },
        { 1, 0, 1, 0 },
        { 1, 1, 1, 0 }
    };

    /** AM depth shift per AMS value. */
    // ym3438.c:127
    static final int[] EG_AM_SHIFT = {
        7, 3, 1, 0
    };

    /** Phase generator detune magnitudes. */
    // ym3438.c:132
    static final int[] PG_DETUNE = { 16, 17, 19, 20, 22, 24, 27, 29 };

    /** PM LFO shift table 1, indexed [pms][lfo phase]. */
    // ym3438.c:134
    static final int[][] PG_LFO_SH1 = {
        { 7, 7, 7, 7, 7, 7, 7, 7 },
        { 7, 7, 7, 7, 7, 7, 7, 7 },
        { 7, 7, 7, 7, 7, 7, 1, 1 },
        { 7, 7, 7, 7, 1, 1, 1, 1 },
        { 7, 7, 7, 1, 1, 1, 1, 0 },
        { 7, 7, 1, 1, 0, 0, 0, 0 },
        { 7, 7, 1, 1, 0, 0, 0, 0 },
        { 7, 7, 1, 1, 0, 0, 0, 0 }
    };

    /** PM LFO shift table 2, indexed [pms][lfo phase]. */
    // ym3438.c:145
    static final int[][] PG_LFO_SH2 = {
        { 7, 7, 7, 7, 7, 7, 7, 7 },
        { 7, 7, 7, 7, 2, 2, 2, 2 },
        { 7, 7, 7, 2, 2, 2, 7, 7 },
        { 7, 7, 2, 2, 7, 7, 2, 2 },
        { 7, 7, 2, 7, 7, 7, 2, 7 },
        { 7, 7, 7, 2, 7, 7, 2, 1 },
        { 7, 7, 7, 2, 7, 7, 2, 1 },
        { 7, 7, 7, 2, 7, 7, 2, 1 }
    };

    /** Address decoder: operator register offset reached by sequencer cycle {@code cycles % 12}. */
    // ym3438.c:157
    static final int[] OP_OFFSET = {
        0x000, /* Ch1 OP1/OP2 */
        0x001, /* Ch2 OP1/OP2 */
        0x002, /* Ch3 OP1/OP2 */
        0x100, /* Ch4 OP1/OP2 */
        0x101, /* Ch5 OP1/OP2 */
        0x102, /* Ch6 OP1/OP2 */
        0x004, /* Ch1 OP3/OP4 */
        0x005, /* Ch2 OP3/OP4 */
        0x006, /* Ch3 OP3/OP4 */
        0x104, /* Ch4 OP3/OP4 */
        0x105, /* Ch5 OP3/OP4 */
        0x106  /* Ch6 OP3/OP4 */
    };

    /** Address decoder: channel register offset reached by sequencer channel. */
    // ym3438.c:172
    static final int[] CH_OFFSET = {
        0x000, /* Ch1 */
        0x001, /* Ch2 */
        0x002, /* Ch3 */
        0x100, /* Ch4 */
        0x101, /* Ch5 */
        0x102  /* Ch6 */
    };

    /** LFO period selector per LFO frequency. */
    // ym3438.c:182
    static final int[] LFO_CYCLES = {
        108, 77, 71, 67, 62, 44, 8, 5
    };

    /**
     * FM algorithm routing, indexed [op][source][connect].
     * Sources: 0 = OP1 delayed once, 1 = OP1 delayed twice, 2 = OP2,
     * 3 = previous slot (mod2), 4 = previous slot (mod1), 5 = channel output.
     */
    // ym3438.c:187
    static final int[][][] FM_ALGORITHM = {
        {
            { 1, 1, 1, 1, 1, 1, 1, 1 }, /* OP1_0         */
            { 1, 1, 1, 1, 1, 1, 1, 1 }, /* OP1_1         */
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* OP2           */
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* Last operator */
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* Last operator */
            { 0, 0, 0, 0, 0, 0, 0, 1 }  /* Out           */
        },
        {
            { 0, 1, 0, 0, 0, 1, 0, 0 }, /* OP1_0         */
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* OP1_1         */
            { 1, 1, 1, 0, 0, 0, 0, 0 }, /* OP2           */
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* Last operator */
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* Last operator */
            { 0, 0, 0, 0, 0, 1, 1, 1 }  /* Out           */
        },
        {
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* OP1_0         */
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* OP1_1         */
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* OP2           */
            { 1, 0, 0, 1, 1, 1, 1, 0 }, /* Last operator */
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* Last operator */
            { 0, 0, 0, 0, 1, 1, 1, 1 }  /* Out           */
        },
        {
            { 0, 0, 1, 0, 0, 1, 0, 0 }, /* OP1_0         */
            { 0, 0, 0, 0, 0, 0, 0, 0 }, /* OP1_1         */
            { 0, 0, 0, 1, 0, 0, 0, 0 }, /* OP2           */
            { 1, 1, 0, 1, 1, 0, 0, 0 }, /* Last operator */
            { 0, 0, 1, 0, 0, 0, 0, 0 }, /* Last operator */
            { 1, 1, 1, 1, 1, 1, 1, 1 }  /* Out           */
        }
    };

    /*
     * Derived lookups. Nothing below is a new table: each is built once from
     * the C tables above so a hot path can do one bounds-checked load where
     * the multi-dimensional shape would cost two or three, and the values it
     * yields are, by construction, exactly the C table's.
     */

    /**
     * {@code FM_ALGORITHM[op][k][connect]} packed as bit {@code k} of entry
     * {@code op * 8 + connect}; {@code fmPrepare} / {@code chGenerate} test
     * the six flags with one load instead of six triple-indexed ones.
     */
    static final int[] FM_ALGORITHM_BITS = new int[4 * 8];

    /** {@code PG_LFO_SH1[pms][lfo]} as {@code [pms * 8 + lfo]}. */
    static final int[] PG_LFO_SH1_FLAT = new int[8 * 8];

    /** {@code PG_LFO_SH2[pms][lfo]} as {@code [pms * 8 + lfo]}. */
    static final int[] PG_LFO_SH2_FLAT = new int[8 * 8];

    /** {@code EG_STEPHI[rate & 3][timer]} as {@code [(rate & 3) * 4 + timer]}. */
    static final int[] EG_STEPHI_FLAT = new int[4 * 4];

    /** {@code slot / 6} for slot 0..23: the operator index the C computes by division. */
    static final int[] SLOT_OP = new int[24];

    /** {@code cycles % 6} for cycles 0..23: the channel the C computes by modulo. */
    static final int[] SLOT_CHANNEL = new int[24];

    /** Signed OPN2_PhaseCalcIncrement detune for [DT * 32 + keycode]. */
    static final byte[] PG_DETUNE_SIGNED = new byte[8 * 32];

    static {
        /* Generate once at class initialization using the original
         * ym3438.c:OPN2_PhaseCalcIncrement detune calculation below.
         * The signed result fits in a byte. */
        for (int dt = 0; dt < 8; dt++) {
            for (int code = 0; code < 32; code++) {
                PG_DETUNE_SIGNED[(dt << 5) | code] = (byte) calculateSignedDetune(dt, code);
            }
        }
        for (int op = 0; op < 4; op++) {
            for (int connect = 0; connect < 8; connect++) {
                int bits = 0;
                for (int k = 0; k < 6; k++) {
                    bits |= FM_ALGORITHM[op][k][connect] << k;
                }
                FM_ALGORITHM_BITS[op * 8 + connect] = bits;
            }
        }
        for (int pms = 0; pms < 8; pms++) {
            for (int lfo = 0; lfo < 8; lfo++) {
                PG_LFO_SH1_FLAT[pms * 8 + lfo] = PG_LFO_SH1[pms][lfo];
                PG_LFO_SH2_FLAT[pms * 8 + lfo] = PG_LFO_SH2[pms][lfo];
            }
        }
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                EG_STEPHI_FLAT[i * 4 + j] = EG_STEPHI[i][j];
            }
        }
        for (int slot = 0; slot < 24; slot++) {
            SLOT_OP[slot] = slot / 6;
            SLOT_CHANNEL[slot] = slot % 6;
        }
    }

    /** Original detune calculation, run only while building the runtime table. */
    private static int calculateSignedDetune(int dt, int kcode) {
        int dtL = dt & 0x03;
        int detune = 0;
        if (dtL != 0) {
            if (kcode > 0x1c) {
                kcode = 0x1c;
            }
            int block = kcode >> 2;
            int note = kcode & 0x03;
            int sum = block + 9 + ((dtL == 3 ? 1 : 0) | (dtL & 0x02));
            int sumH = sum >> 1;
            int sumL = sum & 0x01;
            detune = PG_DETUNE[(sumL << 2) | note] >> (9 - sumH);
        }
        return (dt & 0x04) != 0 ? -detune : detune;
    }
}
