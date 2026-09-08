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

import static com.openggf.audio.synth.nuked.NukedOpn2Tables.CH_OFFSET;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.EG_AM_SHIFT;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.EG_NUM_ATTACK;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.EG_NUM_DECAY;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.EG_NUM_RELEASE;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.EG_NUM_SUSTAIN;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.EG_STEPHI_FLAT;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.EXPROM;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.FM_ALGORITHM_BITS;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.FN_NOTE;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.LFO_CYCLES;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.LOGSINROM;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.OP_OFFSET;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.PG_DETUNE_SIGNED;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.PG_LFO_SH1_FLAT;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.PG_LFO_SH2_FLAT;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.SLOT_CHANNEL;
import static com.openggf.audio.synth.nuked.NukedOpn2Tables.SLOT_OP;

/**
 * Cycle-accurate Yamaha YM3438 / YM2612 (OPN2) emulator: a faithful Java port
 * of {@code ym3438.c} from Nuked-OPN2.
 *
 * <p>One {@link #clock(int[])} call advances the chip by one internal cycle
 * (6 master clocks). The sequencer visits the 24 operator slots in 24 cycles,
 * so one 6-channel output frame is 24 clocks = 144 master clocks; at the Mega
 * Drive's 7.67 MHz FM clock that is 53267 frames per second. Each clock
 * leaves the current time-multiplexed pin values in {@code buffer[0]} (MOL)
 * and {@code buffer[1]} (MOR); mixing those into one sample per frame and
 * resampling to a host rate is the caller's job, exactly as it is for the C
 * library.
 *
 * <p>Register writes go through {@link #write(int, int)} and are latched into
 * a one-cycle strobe; they are decoded by {@code doIo}/{@code doRegWrite} on
 * the following clocks and land only when the sequencer reaches the addressed
 * slot, so a write becomes effective 1 to 24 cycles after the strobe and the
 * chip reports busy for 32 cycles after each data write. Two writes issued
 * without a clock between them overwrite each other's strobe, as they would on
 * the real bus; a caller that needs spacing supplies its own queue.
 *
 * <p>Method names are the C names with the {@code OPN2_} prefix dropped and
 * {@code lowerCamelCase} applied; the state field name map is in the Javadoc
 * of {@link NukedOpn2State}. Every method carries a {@code // ym3438.c:NNN}
 * citation to the pinned upstream line. The only deliberate departure from
 * upstream is that the chip type, a file-scope global {@code chip_type} in C,
 * is a per-instance field here.
 *
 * <h2>Width discipline</h2>
 * The C code relies on the wrap-around of {@code Bit8u}/{@code Bit16u}
 * fields and the truncation of {@code Bit16s} assignments. All arithmetic
 * here is on Java {@code int}; wherever the C compiler would have narrowed a
 * stored value, the port masks explicitly and says so in a comment at the
 * store. Signed right shifts of C {@code Bit16s} values are arithmetic
 * ({@code >>}); logical shifts of C unsigned fields are {@code >>>} where the
 * value could carry bit 31 (it never does, but the shape is kept honest).
 */
public final class NukedOpn2 {

    /** Enables YM2612 emulation (MD1, MD2 VA2). */
    // ym3438.h:38
    public static final int MODE_YM2612 = 0x01;
    /** Enables status read on any port (TeraDrive, MD1 VA7, MD2, etc). */
    // ym3438.h:39
    public static final int MODE_READMODE = 0x02;

    /** Number of internal cycles per output frame (one visit of all 24 slots). */
    public static final int CYCLES_PER_FRAME = 24;

    private final NukedOpn2State chip = new NukedOpn2State();

    /**
     * Per-instance replacement for the upstream file-scope {@code chip_type}
     * global; the upstream default is {@code ym3438_mode_readmode}.
     */
    // ym3438.c:222
    private int chipType = MODE_READMODE;

    /** Creates a chip in the hardware reset state with the upstream default chip type. */
    public NukedOpn2() {
        reset();
    }

    /** Direct access to the chip state; the returned object is live, not a copy. */
    public NukedOpn2State state() {
        return chip;
    }

    // ym3438.c:224
    private void doIo() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        /* Write signal check */
        chip.writeAEn = (chip.writeA & 0x03) == 0x01 ? 1 : 0;
        chip.writeDEn = (chip.writeD & 0x03) == 0x01 ? 1 : 0;
        chip.writeA = (chip.writeA << 1) & 0xff; /* Bit8u shift register */
        chip.writeD = (chip.writeD << 1) & 0xff; /* Bit8u shift register */
        /* Busy counter */
        chip.busy = chip.writeBusy;
        chip.writeBusyCnt = (chip.writeBusyCnt + chip.writeBusy) & 0xff; /* Bit8u */
        chip.writeBusy = ((chip.writeBusy != 0 && (chip.writeBusyCnt >> 5) == 0) || chip.writeDEn != 0) ? 1 : 0;
        chip.writeBusyCnt &= 0x1f;
    }

    /**
     * {@code OPN2_DoRegWrite}, split for the JIT: the per-cycle guards stay
     * here and inline into {@link #clock(int[])}; the slot, channel and mode
     * register decodes, which run only on a matching cycle or a strobe, are
     * the three helpers below, in the C order. Same tests, same stores.
     */
    // ym3438.c:238
    private void doRegWrite() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int slot = chip.cycles >= 12 ? chip.cycles - 12 : chip.cycles; /* cycles % 12 on 0..23 */
        int channel = chip.channel;
        /* Update registers */
        if (chip.writeFmData != 0) {
            /* Slot */
            if (OP_OFFSET[slot] == (chip.address & 0x107)) {
                doRegWriteSlot(slot);
            }

            /* Channel */
            if (CH_OFFSET[channel] == (chip.address & 0x103)) {
                doRegWriteChannel(channel);
            }
        }

        if (chip.writeAEn != 0 || chip.writeDEn != 0) {
            doRegWriteMode();
        }

        if (chip.writeFmData != 0) {
            chip.data = chip.writeData & 0xff;
        }
    }

    /** Slot register decode of {@code OPN2_DoRegWrite}; {@code slot} is {@code cycles % 12}. */
    // ym3438.c:247
    private void doRegWriteSlot(int slot) {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int address;
        if ((chip.address & 0x08) != 0) {
            /* OP2, OP4 */
            slot += 12;
        }
        address = chip.address & 0xf0;
        switch (address) {
        case 0x30: /* DT, MULTI */
            chip.multi[slot] = chip.data & 0x0f;
            if (chip.multi[slot] == 0) {
                chip.multi[slot] = 1;
            } else {
                chip.multi[slot] <<= 1;
            }
            chip.dt[slot] = (chip.data >> 4) & 0x07;
            break;
        case 0x40: /* TL */
            chip.tl[slot] = chip.data & 0x7f;
            break;
        case 0x50: /* KS, AR */
            chip.ar[slot] = chip.data & 0x1f;
            chip.ks[slot] = (chip.data >> 6) & 0x03;
            break;
        case 0x60: /* AM, DR */
            chip.dr[slot] = chip.data & 0x1f;
            chip.am[slot] = (chip.data >> 7) & 0x01;
            break;
        case 0x70: /* SR */
            chip.sr[slot] = chip.data & 0x1f;
            break;
        case 0x80: /* SL, RR */
            chip.rr[slot] = chip.data & 0x0f;
            chip.sl[slot] = (chip.data >> 4) & 0x0f;
            chip.sl[slot] |= (chip.sl[slot] + 1) & 0x10;
            break;
        case 0x90: /* SSG-EG */
            chip.ssgEg[slot] = chip.data & 0x0f;
            break;
        default:
            break;
        }
    }

    /** Channel register decode of {@code OPN2_DoRegWrite}. */
    // ym3438.c:297
    private void doRegWriteChannel(int channel) {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int address = chip.address & 0xfc;
        switch (address) {
        case 0xa0:
            chip.fnum[channel] = (chip.data & 0xff) | ((chip.regA4 & 0x07) << 8);
            chip.block[channel] = (chip.regA4 >> 3) & 0x07;
            chip.kcode[channel] = (chip.block[channel] << 2) | FN_NOTE[chip.fnum[channel] >> 7];
            break;
        case 0xa4:
            chip.regA4 = chip.data & 0xff;
            break;
        case 0xa8:
            chip.fnum3ch[channel] = (chip.data & 0xff) | ((chip.regAc & 0x07) << 8);
            chip.block3ch[channel] = (chip.regAc >> 3) & 0x07;
            chip.kcode3ch[channel] = (chip.block3ch[channel] << 2) | FN_NOTE[chip.fnum3ch[channel] >> 7];
            break;
        case 0xac:
            chip.regAc = chip.data & 0xff;
            break;
        case 0xb0:
            chip.connect[channel] = chip.data & 0x07;
            chip.fb[channel] = (chip.data >> 3) & 0x07;
            break;
        case 0xb4:
            chip.pms[channel] = chip.data & 0x07;
            chip.ams[channel] = (chip.data >> 4) & 0x03;
            chip.panL[channel] = (chip.data >> 7) & 0x01;
            chip.panR[channel] = (chip.data >> 6) & 0x01;
            break;
        default:
            break;
        }
    }

    /** Strobe handling and mode-register ({@code 0x21}-{@code 0x2c}) decode of {@code OPN2_DoRegWrite}. */
    // ym3438.c:335
    private void doRegWriteMode() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        /* Data */
        if (chip.writeAEn != 0) {
            chip.writeFmData = 0;
        }

        if (chip.writeFmAddress != 0 && chip.writeDEn != 0) {
            chip.writeFmData = 1;
        }

        /* Address */
        if (chip.writeAEn != 0) {
            if ((chip.writeData & 0xf0) != 0x00) {
                /* FM Write */
                chip.address = chip.writeData;
                chip.writeFmAddress = 1;
            } else {
                /* SSG write */
                chip.writeFmAddress = 0;
            }
        }

        /* FM Mode */
        /* Data */
        if (chip.writeDEn != 0 && (chip.writeData & 0x100) == 0) {
            switch (chip.writeFmModeA) {
            case 0x21: /* LSI test 1 */
                for (int i = 0; i < 8; i++) {
                    chip.modeTest21[i] = (chip.writeData >> i) & 0x01;
                }
                break;
            case 0x22: /* LFO control */
                if (((chip.writeData >> 3) & 0x01) != 0) {
                    chip.lfoEn = 0x7f;
                } else {
                    chip.lfoEn = 0;
                }
                chip.lfoFreq = chip.writeData & 0x07;
                break;
            case 0x24: /* Timer A */
                chip.timerAReg &= 0x03;
                chip.timerAReg |= (chip.writeData & 0xff) << 2;
                break;
            case 0x25:
                chip.timerAReg &= 0x3fc;
                chip.timerAReg |= chip.writeData & 0x03;
                break;
            case 0x26: /* Timer B */
                chip.timerBReg = chip.writeData & 0xff;
                break;
            case 0x27: /* CSM, Timer control */
                chip.modeCh3 = (chip.writeData & 0xc0) >> 6;
                chip.modeCsm = chip.modeCh3 == 2 ? 1 : 0;
                chip.timerALoad = chip.writeData & 0x01;
                chip.timerAEnable = (chip.writeData >> 2) & 0x01;
                chip.timerAReset = (chip.writeData >> 4) & 0x01;
                chip.timerBLoad = (chip.writeData >> 1) & 0x01;
                chip.timerBEnable = (chip.writeData >> 3) & 0x01;
                chip.timerBReset = (chip.writeData >> 5) & 0x01;
                break;
            case 0x28: /* Key on/off */
                for (int i = 0; i < 4; i++) {
                    chip.modeKonOperator[i] = (chip.writeData >> (4 + i)) & 0x01;
                }
                if ((chip.writeData & 0x03) == 0x03) {
                    /* Invalid address */
                    chip.modeKonChannel = 0xff;
                } else {
                    chip.modeKonChannel = (chip.writeData & 0x03) + ((chip.writeData >> 2) & 1) * 3;
                }
                break;
            case 0x2a: /* DAC data */
                chip.dacdata &= 0x01;
                chip.dacdata |= (chip.writeData ^ 0x80) << 1; /* write_data < 0x100 here, so stays 9-bit */
                break;
            case 0x2b: /* DAC enable */
                chip.dacen = chip.writeData >> 7;
                break;
            case 0x2c: /* LSI test 2 */
                for (int i = 0; i < 8; i++) {
                    chip.modeTest2c[i] = (chip.writeData >> i) & 0x01;
                }
                chip.dacdata &= 0x1fe;
                chip.dacdata |= chip.modeTest2c[3];
                chip.egCustomTimer = (chip.modeTest2c[7] == 0 && chip.modeTest2c[6] != 0) ? 1 : 0;
                break;
            default:
                break;
            }
        }

        /* Address */
        if (chip.writeAEn != 0) {
            chip.writeFmModeA = chip.writeData & 0x1ff;
        }
    }

    // ym3438.c:457
    private void phaseCalcIncrement() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int chan = chip.channel;
        int slot = chip.cycles;
        int fnum = chip.pgFnum;
        int fm;
        int basefreq;
        int pms = chip.pms[chan];
        int dt = chip.dt[slot];
        int kcode = chip.pgKcode;

        fnum <<= 1;
        /* At PMS=0 both C shift-table entries are 7: the 11-bit FNUM's
         * high seven bits shift to zero. Skip only that zero arithmetic,
         * never the LFO clock or phase pipeline (ym3438.c:457). */
        if (pms != 0) {
            int fnumH = fnum >> 5; /* Original fnum >> 4, before doubling. */
            int lfo = chip.lfoPm;
            int lfoL = lfo & 0x0f;
            if ((lfoL & 0x08) != 0) {
                lfoL ^= 0x0f;
            }
            /* Flattened [pms][lfoL] lookups: one load each, same entries */
            fm = (fnumH >> PG_LFO_SH1_FLAT[(pms << 3) | lfoL]) + (fnumH >> PG_LFO_SH2_FLAT[(pms << 3) | lfoL]);
            if (pms > 5) {
                fm <<= pms - 5;
            }
            fm >>= 2;
            if ((lfo & 0x10) != 0) {
                fnum -= fm; /* Bit32u wrap, then masked below */
            } else {
                fnum += fm;
            }
        }
        fnum &= 0xfff;

        basefreq = (fnum << chip.pgBlock) >> 2;

        /* Same signed detune arithmetic, precomputed for the 3-bit DT and
         * 5-bit keycode domain; unsigned wrap is still masked below. */
        basefreq += PG_DETUNE_SIGNED[(dt << 5) | kcode];
        basefreq &= 0x1ffff;
        chip.pgInc[slot] = ((basefreq * chip.multi[slot]) >> 1) & 0xfffff; /* one store for the C = then &= */
    }

    // ym3438.c:526
    private void phaseGenerate() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int slot;
        /* Mask increment */
        slot = wrap24(chip.cycles + 20);
        if (chip.pgReset[slot] != 0) {
            chip.pgInc[slot] = 0;
        }
        /* Phase step */
        slot = wrap24(chip.cycles + 19);
        if (chip.pgReset[slot] != 0 || chip.modeTest21[3] != 0) {
            chip.pgPhase[slot] = 0;
        }
        chip.pgPhase[slot] = (chip.pgPhase[slot] + chip.pgInc[slot]) & 0xfffff; /* one store for the C += then &= */
    }

    // ym3438.c:545
    private void envelopeSsgEg() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int slot = chip.cycles;
        int direction = 0;
        /* ssg_eg[slot] and eg_kon[slot] are not written here, so one load each stands for the C re-reads */
        int ssgEg = chip.ssgEg[slot];
        int egKon = chip.egKon[slot];
        chip.egSsgPgrstLatch[slot] = 0;
        chip.egSsgRepeatLatch[slot] = 0;
        chip.egSsgHoldUpLatch[slot] = 0;
        if ((ssgEg & 0x08) != 0) {
            direction = chip.egSsgDir[slot];
            if ((chip.egLevel[slot] & 0x200) != 0) {
                /* Reset */
                if ((ssgEg & 0x03) == 0x00) {
                    chip.egSsgPgrstLatch[slot] = 1;
                }
                /* Repeat */
                if ((ssgEg & 0x01) == 0x00) {
                    chip.egSsgRepeatLatch[slot] = 1;
                }
                /* Inverse */
                if ((ssgEg & 0x03) == 0x02) {
                    direction ^= 1;
                }
                if ((ssgEg & 0x03) == 0x03) {
                    direction = 1;
                }
            }
            /* Hold up */
            if (chip.egKonLatch[slot] != 0
                    && ((ssgEg & 0x07) == 0x05 || (ssgEg & 0x07) == 0x03)) {
                chip.egSsgHoldUpLatch[slot] = 1;
            }
            direction &= egKon;
        }
        chip.egSsgDir[slot] = direction;
        chip.egSsgEnable[slot] = (ssgEg >> 3) & 0x01;
        chip.egSsgInv[slot] = (direction ^ (((ssgEg >> 2) & 0x01) & ((ssgEg >> 3) & 0x01)))
                & egKon; /* eg_ssg_dir[slot] was just stored from direction */
    }

    // ym3438.c:591
    private void envelopeAdsr() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int slot = wrap24(chip.cycles + 22);

        int nkon = chip.egKonLatch[slot];
        int okon = chip.egKon[slot];
        boolean konEvent;
        boolean koffEvent;
        int egOff;
        int level;
        int nextlevel;
        int ssgLevel;
        /* eg_state[slot], eg_ssg_enable[slot], eg_inc and eg_ratemax are read-only in this
         * function, so each is loaded once where the C re-reads the field */
        int state = chip.egState[slot];
        int ssgEnable = chip.egSsgEnable[slot];
        int egInc = chip.egInc;
        int egRatemax = chip.egRatemax;
        int nextstate = state;
        int inc = 0;
        chip.egRead[0] = chip.egReadInc;
        chip.egReadInc = egInc > 0 ? 1 : 0;

        /* Reset phase generator */
        chip.pgReset[slot] = ((nkon != 0 && okon == 0) || chip.egSsgPgrstLatch[slot] != 0) ? 1 : 0;

        /* KeyOn/Off */
        konEvent = (nkon != 0 && okon == 0) || (okon != 0 && chip.egSsgRepeatLatch[slot] != 0);
        koffEvent = okon != 0 && nkon == 0;

        ssgLevel = level = chip.egLevel[slot];

        if (chip.egSsgInv[slot] != 0) {
            /* Inverse */
            ssgLevel = 512 - level;
            ssgLevel &= 0x3ff;
        }
        if (koffEvent) {
            level = ssgLevel;
        }
        if (ssgEnable != 0) {
            egOff = level >> 9;
        } else {
            egOff = (level & 0x3f0) == 0x3f0 ? 1 : 0;
        }
        nextlevel = level;
        if (konEvent) {
            nextstate = EG_NUM_ATTACK;
            /* Instant attack */
            if (egRatemax != 0) {
                nextlevel = 0;
            } else if (state == EG_NUM_ATTACK && level != 0 && egInc != 0 && nkon != 0) {
                inc = (~level << egInc) >> 5; /* arithmetic shift of a negative int, as in C */
            }
        } else {
            switch (state) {
            case EG_NUM_ATTACK:
                if (level == 0) {
                    nextstate = EG_NUM_DECAY;
                } else if (egInc != 0 && egRatemax == 0 && nkon != 0) {
                    inc = (~level << egInc) >> 5;
                }
                break;
            case EG_NUM_DECAY:
                if ((level >> 4) == (chip.egSl[1] << 1)) {
                    nextstate = EG_NUM_SUSTAIN;
                } else if (egOff == 0 && egInc != 0) {
                    inc = 1 << (egInc - 1);
                    if (ssgEnable != 0) {
                        inc <<= 2;
                    }
                }
                break;
            case EG_NUM_SUSTAIN:
            case EG_NUM_RELEASE:
                if (egOff == 0 && egInc != 0) {
                    inc = 1 << (egInc - 1);
                    if (ssgEnable != 0) {
                        inc <<= 2;
                    }
                }
                break;
            default:
                break;
            }
            if (nkon == 0) {
                nextstate = EG_NUM_RELEASE;
            }
        }
        if (chip.egKonCsm[slot] != 0) {
            nextlevel |= chip.egTl[1] << 3;
        }

        /* Envelope off */
        if (!konEvent && chip.egSsgHoldUpLatch[slot] == 0 && state != EG_NUM_ATTACK && egOff != 0) {
            nextstate = EG_NUM_RELEASE;
            nextlevel = 0x3ff;
        }

        nextlevel += inc;

        chip.egKon[slot] = nkon; /* eg_kon_latch[slot], unchanged since it was read into nkon */
        chip.egLevel[slot] = nextlevel & 0x3ff; /* (Bit16u)nextlevel & 0x3ff */
        chip.egState[slot] = nextstate;
    }

    // ym3438.c:715
    private void envelopePrepare() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int rate;
        int sum;
        int inc = 0;
        int slot = chip.cycles;
        int rateSel;

        /* Prepare increment */
        rate = (chip.egRate << 1) + chip.egKsv; /* Bit8u: max 0x7e + 0x1f, never wraps */

        if (rate > 0x3f) {
            rate = 0x3f;
        }

        sum = ((rate >> 2) + chip.egShiftLock) & 0x0f;
        if (chip.egRate != 0 && chip.egQuotient == 2) {
            if (rate < 48) {
                switch (sum) {
                case 12:
                    inc = 1;
                    break;
                case 13:
                    inc = (rate >> 1) & 0x01;
                    break;
                case 14:
                    inc = rate & 0x01;
                    break;
                default:
                    break;
                }
            } else {
                inc = EG_STEPHI_FLAT[((rate & 0x03) << 2) | chip.egTimerLowLock] + (rate >> 2) - 11; /* flattened [4][4] */
                if (inc > 4) {
                    inc = 4;
                }
            }
        }
        chip.egInc = inc;
        chip.egRatemax = (rate >> 1) == 0x1f ? 1 : 0;

        /* Prepare rate & ksv */
        rateSel = chip.egState[slot];
        int egKon = chip.egKon[slot]; /* read twice in C, not written here */
        if ((egKon != 0 && chip.egSsgRepeatLatch[slot] != 0)
                || (egKon == 0 && chip.egKonLatch[slot] != 0)) {
            rateSel = EG_NUM_ATTACK;
        }
        switch (rateSel) {
        case EG_NUM_ATTACK:
            chip.egRate = chip.ar[slot];
            break;
        case EG_NUM_DECAY:
            chip.egRate = chip.dr[slot];
            break;
        case EG_NUM_SUSTAIN:
            chip.egRate = chip.sr[slot];
            break;
        case EG_NUM_RELEASE:
            chip.egRate = (chip.rr[slot] << 1) | 0x01;
            break;
        default:
            break;
        }
        chip.egKsv = chip.pgKcode >> (chip.ks[slot] ^ 0x03);
        if (chip.am[slot] != 0) {
            chip.egLfoAm = chip.lfoAm >> EG_AM_SHIFT[chip.ams[chip.channel]];
        } else {
            chip.egLfoAm = 0;
        }
        /* Delay TL & SL value */
        chip.egTl[1] = chip.egTl[0];
        chip.egTl[0] = chip.tl[slot];
        chip.egSl[1] = chip.egSl[0];
        chip.egSl[0] = chip.sl[slot];
    }

    // ym3438.c:803
    private void envelopeGenerate() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int slot = wrap24(chip.cycles + 23);
        int level;

        level = chip.egLevel[slot];

        if (chip.egSsgInv[slot] != 0) {
            /* Inverse */
            level = 512 - level; /* Bit16u wrap absorbed by the mask below */
        }
        if (chip.modeTest21[5] != 0) {
            level = 0;
        }
        level &= 0x3ff;

        /* Apply AM LFO */
        level += chip.egLfoAm;

        /* Apply TL */
        if (!(chip.modeCsm != 0 && chip.channel == 2 + 1)) {
            level += chip.egTl[0] << 3;
        }
        if (level > 0x3ff) {
            level = 0x3ff;
        }
        chip.egOut[slot] = level;
    }

    // ym3438.c:836
    private void updateLfo() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        if ((chip.lfoQuotient & LFO_CYCLES[chip.lfoFreq]) == LFO_CYCLES[chip.lfoFreq]) {
            chip.lfoQuotient = 0;
            chip.lfoCnt = (chip.lfoCnt + 1) & 0xff; /* Bit8u */
        } else {
            chip.lfoQuotient = (chip.lfoQuotient + chip.lfoInc) & 0xff; /* Bit8u */
        }
        chip.lfoCnt &= chip.lfoEn;
    }

    // ym3438.c:850
    private void fmPrepare() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int slot = wrap24(chip.cycles + 6);
        int channel = chip.channel;
        int mod;
        int mod1;
        int mod2;
        int op = SLOT_OP[slot]; /* slot / 6 */
        int connect = chip.connect[channel];
        int prevslot = wrap24(chip.cycles + 18);
        int prevOut = chip.fmOut[prevslot]; /* read by two flags below, not written here */

        /* Calculate modulation */
        mod1 = mod2 = 0;

        /* The six FM_ALGORITHM[op][k][connect] flags read as bits of one packed entry */
        int alg = FM_ALGORITHM_BITS[(op << 3) | connect];
        if ((alg & 0x01) != 0) {
            mod2 |= chip.fmOp1[channel][0];
        }
        if ((alg & 0x02) != 0) {
            mod1 |= chip.fmOp1[channel][1];
        }
        if ((alg & 0x04) != 0) {
            mod1 |= chip.fmOp2[channel];
        }
        if ((alg & 0x08) != 0) {
            mod2 |= prevOut;
        }
        if ((alg & 0x10) != 0) {
            mod1 |= prevOut;
        }
        /* Bit16s: operands are 14-bit sign-extended, so OR and sum stay within 16 bits */
        mod = (short) (mod1 + mod2);
        if (op == 0) {
            /* Feedback */
            int fb = chip.fb[channel];
            mod = mod >> (10 - fb);
            if (fb == 0) {
                mod = 0;
            }
        } else {
            mod >>= 1;
        }
        chip.fmMod[slot] = mod & 0xffff; /* Bit16u */

        slot = prevslot; /* (cycles + 18) % 24 again */
        /* OP1 */
        if (SLOT_OP[slot] == 0) {
            int[] op1 = chip.fmOp1[channel];
            op1[1] = op1[0];
            op1[0] = prevOut;
        }
        /* OP2 */
        if (SLOT_OP[slot] == 2) {
            chip.fmOp2[channel] = prevOut;
        }
    }

    // ym3438.c:912
    private void chGenerate() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int slot = wrap24(chip.cycles + 18);
        int channel = chip.channel;
        int op = SLOT_OP[slot]; /* slot / 6 */
        int testDac = chip.modeTest2c[5];
        int accIn = chip.chAcc[channel];
        int acc = accIn;
        int add = testDac;
        int sum;
        if (op == 0 && testDac == 0) {
            acc = 0;
        }
        if ((FM_ALGORITHM_BITS[(op << 3) | chip.connect[channel]] & 0x20) != 0 && testDac == 0) { /* [op][5][connect] */
            add += chip.fmOut[slot] >> 5; /* arithmetic shift of Bit16s */
        }
        sum = acc + add;
        /* Clamp */
        if (sum > 255) {
            sum = 255;
        } else if (sum < -256) {
            sum = -256;
        }

        if (op == 0 || testDac != 0) {
            chip.chOut[channel] = accIn; /* ch_acc[channel] before this cycle's store */
        }
        chip.chAcc[channel] = sum;
    }

    // ym3438.c:947
    private void chOutput() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int cycles = chip.cycles;
        int slot = chip.cycles;
        int channel = chip.channel;
        int testDac = chip.modeTest2c[5];
        int out;
        int sign;
        boolean outEn;
        chip.chRead = chip.chLock;
        if (slot < 12) {
            /* Ch 4,5,6 */
            channel++;
        }
        if ((cycles & 3) == 0) {
            if (testDac == 0) {
                /* Lock value */
                chip.chLock = chip.chOut[channel];
            }
            chip.chLockL = chip.panL[channel];
            chip.chLockR = chip.panR[channel];
        }
        /* Ch 6 */
        if (((cycles >> 2) == 1 && chip.dacen != 0) || testDac != 0) {
            out = chip.dacdata;
            out = signExtend(8, out);
        } else {
            out = chip.chLock;
        }
        chip.mol = 0;
        chip.mor = 0;

        if ((chipType & MODE_YM2612) != 0) {
            outEn = ((cycles & 3) == 3) || testDac != 0;
            /* YM2612 DAC emulation(not verified) */
            sign = out >> 8; /* arithmetic: -1 for negative, 0 otherwise */
            if (out >= 0) {
                out++;
                sign++;
            }
            if (chip.chLockL != 0 && outEn) {
                chip.mol = out;
            } else {
                chip.mol = sign;
            }
            if (chip.chLockR != 0 && outEn) {
                chip.mor = out;
            } else {
                chip.mor = sign;
            }
            /* Amplify signal */
            chip.mol *= 3;
            chip.mor *= 3;
        } else {
            outEn = ((cycles & 3) != 0) || testDac != 0;
            if (chip.chLockL != 0 && outEn) {
                chip.mol = out;
            }
            if (chip.chLockR != 0 && outEn) {
                chip.mor = out;
            }
        }
    }

    // ym3438.c:1029
    private void fmGenerate() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int slot = wrap24(chip.cycles + 19);
        /* Calculate phase */
        int phase = (chip.fmMod[slot] + (chip.pgPhase[slot] >> 10)) & 0x3ff;
        int quarter;
        int level;
        int output;
        if ((phase & 0x100) != 0) {
            quarter = (phase ^ 0xff) & 0xff;
        } else {
            quarter = phase & 0xff;
        }
        level = LOGSINROM[quarter];
        /* Apply envelope */
        level += chip.egOut[slot] << 2;
        /* Transform */
        if (level > 0x1fff) {
            level = 0x1fff;
        }
        output = ((EXPROM[(level & 0xff) ^ 0xff] | 0x400) << 2) >> (level >> 8);
        if ((phase & 0x200) != 0) {
            output = ((~output) ^ (chip.modeTest21[4] << 13)) + 1;
        } else {
            output = output ^ (chip.modeTest21[4] << 13);
        }
        /* The C Bit16s truncation here only drops bits above 15, which SIGN_EXTEND(13) discards anyway */
        output = signExtend(13, output);
        chip.fmOut[slot] = output;
    }

    // ym3438.c:1066
    private void doTimerA() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int time;
        int load;
        load = chip.timerAOverflow;
        if (chip.cycles == 2) {
            /* Lock load value */
            load |= (chip.timerALoadLock == 0 && chip.timerALoad != 0) ? 1 : 0;
            chip.timerALoadLock = chip.timerALoad;
            if (chip.modeCsm != 0) {
                /* CSM KeyOn */
                chip.modeKonCsm = load;
            } else {
                chip.modeKonCsm = 0;
            }
        }
        /* Load counter */
        if (chip.timerALoadLatch != 0) {
            time = chip.timerAReg;
        } else {
            time = chip.timerACnt;
        }
        chip.timerALoadLatch = load;
        /* Increase counter */
        if ((chip.cycles == 1 && chip.timerALoadLock != 0) || chip.modeTest21[2] != 0) {
            time++;
        }
        /* Set overflow flag */
        if (chip.timerAReset != 0) {
            chip.timerAReset = 0;
            chip.timerAOverflowFlag = 0;
        } else {
            chip.timerAOverflowFlag |= chip.timerAOverflow & chip.timerAEnable;
        }
        chip.timerAOverflow = (time >> 10);
        chip.timerACnt = time & 0x3ff;
    }

    // ym3438.c:1115
    private void doTimerB() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int time;
        int load;
        load = chip.timerBOverflow;
        if (chip.cycles == 2) {
            /* Lock load value */
            load |= (chip.timerBLoadLock == 0 && chip.timerBLoad != 0) ? 1 : 0;
            chip.timerBLoadLock = chip.timerBLoad;
        }
        /* Load counter */
        if (chip.timerBLoadLatch != 0) {
            time = chip.timerBReg;
        } else {
            time = chip.timerBCnt;
        }
        chip.timerBLoadLatch = load;
        /* Increase counter */
        if (chip.cycles == 1) {
            chip.timerBSubcnt = (chip.timerBSubcnt + 1) & 0xff; /* Bit8u */
        }
        if ((chip.timerBSubcnt == 0x10 && chip.timerBLoadLock != 0) || chip.modeTest21[2] != 0) {
            time++;
        }
        chip.timerBSubcnt &= 0x0f;
        /* Set overflow flag */
        if (chip.timerBReset != 0) {
            chip.timerBReset = 0;
            chip.timerBOverflowFlag = 0;
        } else {
            chip.timerBOverflowFlag |= chip.timerBOverflow & chip.timerBEnable;
        }
        chip.timerBOverflow = (time >> 8);
        chip.timerBCnt = time & 0xff;
    }

    // ym3438.c:1160
    private void keyOn() {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int slot = chip.cycles;
        int chan = chip.channel;
        /* Key On */
        chip.egKonLatch[slot] = chip.modeKon[slot];
        chip.egKonCsm[slot] = 0;
        if (chip.channel == 2 && chip.modeKonCsm != 0) {
            /* CSM Key On */
            chip.egKonLatch[slot] = 1;
            chip.egKonCsm[slot] = 1;
        }
        if (chip.cycles == chip.modeKonChannel) {
            /* OP1 */
            chip.modeKon[chan] = chip.modeKonOperator[0];
            /* OP2 */
            chip.modeKon[chan + 12] = chip.modeKonOperator[1];
            /* OP3 */
            chip.modeKon[chan + 6] = chip.modeKonOperator[2];
            /* OP4 */
            chip.modeKon[chan + 18] = chip.modeKonOperator[3];
        }
    }

    /** Hardware reset ({@code OPN2_Reset}); the chip type is retained. */
    // ym3438.c:1186
    public void reset() {
        chip.clear();
        for (int i = 0; i < 24; i++) {
            chip.egOut[i] = 0x3ff;
            chip.egLevel[i] = 0x3ff;
            chip.egState[i] = EG_NUM_RELEASE;
            chip.multi[i] = 1;
        }
        for (int i = 0; i < 6; i++) {
            chip.panL[i] = 1;
            chip.panR[i] = 1;
        }
    }

    /**
     * Sets the chip type flags ({@link #MODE_YM2612}, {@link #MODE_READMODE});
     * per instance here, where upstream {@code OPN2_SetChipType} sets a global.
     */
    // ym3438.c:1204
    public void setChipType(int type) {
        chipType = type;
    }

    /** The chip type flags in effect. */
    public int chipType() {
        return chipType;
    }

    /**
     * Advances the chip by one internal cycle ({@code OPN2_Clock}) and stores
     * the MOL / MOR pin values in {@code buffer[0]} / {@code buffer[1]}.
     */
    // ym3438.c:1209
    public void clock(int[] buffer) {
        final NukedOpn2State chip = this.chip; /* local copy of the reference: shorter bytecode, same object */
        int slot = chip.cycles;
        chip.lfoInc = chip.modeTest21[1];
        chip.pgRead >>>= 1;
        chip.egRead[1] >>>= 1;
        chip.egCycle = (chip.egCycle + 1) & 0xff; /* Bit8u */
        /* Lock envelope generator timer value */
        if (chip.cycles == 1 && chip.egQuotient == 2) {
            if (chip.egCycleStop != 0) {
                chip.egShiftLock = 0;
            } else {
                chip.egShiftLock = chip.egShift + 1;
            }
            chip.egTimerLowLock = chip.egTimer & 0x03;
        }
        /* Cycle specific functions */
        switch (chip.cycles) {
        case 0:
            chip.lfoPm = chip.lfoCnt >> 2;
            if ((chip.lfoCnt & 0x40) != 0) {
                chip.lfoAm = chip.lfoCnt & 0x3f;
            } else {
                chip.lfoAm = chip.lfoCnt ^ 0x3f;
            }
            chip.lfoAm <<= 1; /* Bit8u: lfo_cnt <= 0x7f here, so at most 0xfe; no wrap */
            break;
        case 1:
            chip.egQuotient++;
            chip.egQuotient %= 3;
            chip.egCycle = 0;
            chip.egCycleStop = 1;
            chip.egShift = 0;
            chip.egTimerInc |= chip.egQuotient >> 1;
            chip.egTimer = chip.egTimer + chip.egTimerInc;
            chip.egTimerInc = chip.egTimer >> 12;
            chip.egTimer &= 0xfff;
            break;
        case 2:
            chip.pgRead = chip.pgPhase[21] & 0x3ff;
            chip.egRead[1] = chip.egOut[0];
            break;
        case 13:
            chip.egCycle = 0;
            chip.egCycleStop = 1;
            chip.egShift = 0;
            chip.egTimer = chip.egTimer + chip.egTimerInc;
            chip.egTimerInc = chip.egTimer >> 12;
            chip.egTimer &= 0xfff;
            break;
        case 23:
            chip.lfoInc |= 1;
            break;
        default:
            break;
        }
        chip.egTimer &= ~(chip.modeTest21[5] << chip.egCycle);
        /* The C expression ANDs with eg_cycle_stop (0/1), so only bit 0 of the OR decides */
        if ((((chip.egTimer >> chip.egCycle) | (chip.pinTestIn & chip.egCustomTimer)) & chip.egCycleStop) != 0) {
            chip.egShift = chip.egCycle;
            chip.egCycleStop = 0;
        }

        doIo();

        doTimerA();
        doTimerB();
        keyOn();

        chOutput();
        chGenerate();

        fmPrepare();
        fmGenerate();

        phaseGenerate();
        phaseCalcIncrement();

        envelopeAdsr();
        envelopeGenerate();
        envelopeSsgEg();
        envelopePrepare();

        /* Prepare fnum & block */
        int nextChannel = chip.channel + 1 == 6 ? 0 : chip.channel + 1; /* (channel + 1) % 6, channel in 0..5 */
        if (chip.modeCh3 != 0) {
            /* Channel 3 special mode */
            switch (slot) {
            case 1: /* OP1 */
                chip.pgFnum = chip.fnum3ch[1];
                chip.pgBlock = chip.block3ch[1];
                chip.pgKcode = chip.kcode3ch[1];
                break;
            case 7: /* OP3 */
                chip.pgFnum = chip.fnum3ch[0];
                chip.pgBlock = chip.block3ch[0];
                chip.pgKcode = chip.kcode3ch[0];
                break;
            case 13: /* OP2 */
                chip.pgFnum = chip.fnum3ch[2];
                chip.pgBlock = chip.block3ch[2];
                chip.pgKcode = chip.kcode3ch[2];
                break;
            case 19: /* OP4 */
            default:
                chip.pgFnum = chip.fnum[nextChannel];
                chip.pgBlock = chip.block[nextChannel];
                chip.pgKcode = chip.kcode[nextChannel];
                break;
            }
        } else {
            chip.pgFnum = chip.fnum[nextChannel];
            chip.pgBlock = chip.block[nextChannel];
            chip.pgKcode = chip.kcode[nextChannel];
        }

        updateLfo();
        doRegWrite();
        chip.cycles = wrap24(chip.cycles + 1);
        chip.channel = SLOT_CHANNEL[chip.cycles]; /* cycles % 6 */

        buffer[0] = chip.mol;
        buffer[1] = chip.mor;

        if (chip.statusTime != 0) {
            chip.statusTime--;
        }
    }

    /**
     * Presents a bus write ({@code OPN2_Write}): {@code port} bit 0 selects
     * address (0) or data (1) strobe, bit 1 selects register bank part I (0)
     * or part II (1). The strobe is consumed by the next {@link #clock(int[])}.
     */
    // ym3438.c:1346
    public void write(int port, int data) {
        port &= 3;
        chip.writeData = ((port << 7) & 0x100) | (data & 0xff); /* Bit8u data */
        if ((port & 1) != 0) {
            /* Data */
            chip.writeD |= 1;
        } else {
            /* Address */
            chip.writeA |= 1;
        }
    }

    /** Drives the TEST input pin ({@code OPN2_SetTestPin}). */
    // ym3438.c:1362
    public void setTestPin(int value) {
        chip.pinTestIn = value & 1;
    }

    /** Reads the TEST output pin ({@code OPN2_ReadTestPin}). */
    // ym3438.c:1367
    public int readTestPin() {
        if (chip.modeTest2c[7] == 0) {
            return 0;
        }
        return chip.cycles == 23 ? 1 : 0;
    }

    /** Reads the IRQ pin ({@code OPN2_ReadIRQPin}): timer A or timer B overflow. */
    // ym3438.c:1376
    public int readIrqPin() {
        return chip.timerAOverflowFlag | chip.timerBOverflowFlag;
    }

    /**
     * Reads the status port ({@code OPN2_Read}). Only {@code port} 0 refreshes
     * the status latch unless {@link #MODE_READMODE} is set; the latched
     * value decays to zero after {@code status_time} cycles.
     */
    // ym3438.c:1381
    public int read(int port) {
        if ((port & 3) == 0 || (chipType & MODE_READMODE) != 0) {
            if (chip.modeTest21[6] != 0) {
                /* Read test data */
                int slot = wrap24(chip.cycles + 18);
                int testdata = ((chip.pgRead & 0x01) << 15)
                        | ((chip.egRead[chip.modeTest21[0]] & 0x01) << 14);
                if (chip.modeTest2c[4] != 0) {
                    testdata |= chip.chRead & 0x1ff;
                } else {
                    testdata |= chip.fmOut[slot] & 0x3fff;
                }
                if (chip.modeTest21[7] != 0) {
                    chip.status = testdata & 0xff;
                } else {
                    chip.status = testdata >> 8;
                }
            } else {
                chip.status = (chip.busy << 7) | (chip.timerBOverflowFlag << 1)
                        | chip.timerAOverflowFlag;
            }
            if ((chipType & MODE_YM2612) != 0) {
                chip.statusTime = 300000;
            } else {
                chip.statusTime = 40000000;
            }
        }
        if (chip.statusTime != 0) {
            return chip.status;
        }
        return 0;
    }

    /**
     * {@code x % 24} for {@code x} in {@code 0..47}: every caller adds a
     * constant below 24 to {@code cycles} (itself 0..23), so one conditional
     * subtract gives the same slot as the C modulo without the division.
     */
    private static int wrap24(int x) {
        return x >= 24 ? x - 24 : x;
    }

    /**
     * {@code SIGN_EXTEND(bit_index, value)}: sign-extends {@code value} from
     * bit {@code bitIndex} (the sign bit) downwards. The C macro computes in
     * unsigned arithmetic and lets the store to {@code Bit16s} wrap; on Java
     * ints the subtraction yields the signed result directly.
     */
    // ym3438.c:33
    private static int signExtend(int bitIndex, int value) {
        return (value & ((1 << bitIndex) - 1)) - (value & (1 << bitIndex));
    }
}
