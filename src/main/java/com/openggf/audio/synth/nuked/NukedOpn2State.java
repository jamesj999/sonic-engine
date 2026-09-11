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
 * (ym3438.h version 1.0.9, sha256 8e60e35f77049d0e600ad1a47bfc3dfc8b832483e614104473a83c1f33cd7189).
 */
package com.openggf.audio.synth.nuked;

import java.util.Arrays;

/**
 * The complete chip state of one Nuked OPN2 instance: a field-for-field port
 * of the {@code ym3438_t} struct in {@code ym3438.h} (lines 56-207).
 *
 * <p>This is a plain data holder with no behaviour of its own beyond
 * {@link #clear()} (the {@code memset} of {@code OPN2_Reset}) and
 * {@link #copyFrom(NukedOpn2State)} / {@link #copy()} (the plain struct copy
 * a snapshot needs: every field is a scalar or a primitive array, there are
 * no references). {@link NukedOpn2} owns one of these and is the only writer.
 *
 * <h2>Width contract</h2>
 * Every C field is held in a Java {@code int}. The C struct uses unsigned
 * narrow types ({@code Bit8u}, {@code Bit16u}) whose wrap-around is part of
 * the algorithm, so the chip code masks explicitly at every point the C
 * compiler would have truncated; see the width column below. Fields typed
 * {@code Bit16s} in C are held sign-extended (their natural signed value).
 *
 * <h2>Name map</h2>
 * The rule is mechanical: the C {@code snake_case} name becomes the same
 * words in {@code lowerCamelCase}, digits kept ({@code mode_test_21 ->
 * modeTest21}), array ranks kept. The full map, in struct order:
 *
 * <pre>
 * C field (ym3438.h)         Java field            C width   notes
 * --------------------------------------------------------------------------
 * cycles                     cycles                Bit32u    0..23
 * channel                    channel               Bit32u    cycles % 6
 * mol, mor                   mol, mor              Bit16s    output pins
 * -- IO --
 * write_data                 writeData             Bit16u    9 bits used
 * write_a                    writeA                Bit8u     strobe shift register
 * write_d                    writeD                Bit8u     strobe shift register
 * write_a_en                 writeAEn              Bit8u     0/1
 * write_d_en                 writeDEn              Bit8u     0/1
 * write_busy                 writeBusy             Bit8u     0/1
 * write_busy_cnt             writeBusyCnt          Bit8u     5 bits used
 * write_fm_address           writeFmAddress        Bit8u     0/1
 * write_fm_data              writeFmData           Bit8u     0/1
 * write_fm_mode_a            writeFmModeA          Bit16u    9 bits used
 * address                    address               Bit16u    9 bits used
 * data                       data                  Bit8u
 * pin_test_in                pinTestIn             Bit8u     0/1
 * pin_irq                    pinIrq                Bit8u     (never written upstream)
 * busy                       busy                  Bit8u     0/1
 * -- LFO --
 * lfo_en                     lfoEn                 Bit8u     0 or 0x7f
 * lfo_freq                   lfoFreq               Bit8u     0..7
 * lfo_pm                     lfoPm                 Bit8u
 * lfo_am                     lfoAm                 Bit8u
 * lfo_cnt                    lfoCnt                Bit8u
 * lfo_inc                    lfoInc                Bit8u
 * lfo_quotient               lfoQuotient           Bit8u
 * -- Phase generator --
 * pg_fnum                    pgFnum                Bit16u    11 bits
 * pg_block                   pgBlock               Bit8u     3 bits
 * pg_kcode                   pgKcode               Bit8u     5 bits
 * pg_inc[24]                 pgInc[24]             Bit32u    20 bits
 * pg_phase[24]               pgPhase[24]           Bit32u    20 bits
 * pg_reset[24]               pgReset[24]           Bit8u     0/1
 * pg_read                    pgRead                Bit32u    10 bits, shifted right per cycle
 * -- Envelope generator --
 * eg_cycle                   egCycle               Bit8u
 * eg_cycle_stop              egCycleStop           Bit8u     0/1
 * eg_shift                   egShift               Bit8u
 * eg_shift_lock              egShiftLock           Bit8u
 * eg_timer_low_lock          egTimerLowLock        Bit8u     2 bits
 * eg_timer                   egTimer               Bit16u    12 bits
 * eg_timer_inc               egTimerInc            Bit8u     0/1
 * eg_quotient                egQuotient            Bit16u    0..2
 * eg_custom_timer            egCustomTimer         Bit8u     0/1
 * eg_rate                    egRate                Bit8u     6 bits
 * eg_ksv                     egKsv                 Bit8u     5 bits
 * eg_inc                     egInc                 Bit8u     0..4
 * eg_ratemax                 egRatemax             Bit8u     0/1
 * eg_sl[2]                   egSl[2]               Bit8u     delay pipe
 * eg_lfo_am                  egLfoAm               Bit8u
 * eg_tl[2]                   egTl[2]               Bit8u     delay pipe
 * eg_state[24]               egState[24]           Bit8u     EG_NUM_*
 * eg_level[24]               egLevel[24]           Bit16u    10 bits
 * eg_out[24]                 egOut[24]             Bit16u    10 bits
 * eg_kon[24]                 egKon[24]             Bit8u     0/1
 * eg_kon_csm[24]             egKonCsm[24]          Bit8u     0/1
 * eg_kon_latch[24]           egKonLatch[24]        Bit8u     0/1
 * eg_csm_mode[24]            egCsmMode[24]         Bit8u     (never written upstream)
 * eg_ssg_enable[24]          egSsgEnable[24]       Bit8u     0/1
 * eg_ssg_pgrst_latch[24]     egSsgPgrstLatch[24]   Bit8u     0/1
 * eg_ssg_repeat_latch[24]    egSsgRepeatLatch[24]  Bit8u     0/1
 * eg_ssg_hold_up_latch[24]   egSsgHoldUpLatch[24]  Bit8u     0/1
 * eg_ssg_dir[24]             egSsgDir[24]          Bit8u     0/1
 * eg_ssg_inv[24]             egSsgInv[24]          Bit8u     0/1
 * eg_read[2]                 egRead[2]             Bit32u    [0] 0/1, [1] 10-bit shifted per cycle
 * eg_read_inc                egReadInc             Bit8u     0/1
 * -- FM --
 * fm_op1[6][2]               fmOp1[6][2]           Bit16s    14-bit signed, sign-extended
 * fm_op2[6]                  fmOp2[6]              Bit16s    14-bit signed, sign-extended
 * fm_out[24]                 fmOut[24]             Bit16s    14-bit signed, sign-extended
 * fm_mod[24]                 fmMod[24]             Bit16u    held masked to 16 bits
 * -- Channel --
 * ch_acc[6]                  chAcc[6]              Bit16s    -256..255
 * ch_out[6]                  chOut[6]              Bit16s    -256..255
 * ch_lock                    chLock                Bit16s    -256..255
 * ch_lock_l                  chLockL               Bit8u     0/1
 * ch_lock_r                  chLockR               Bit8u     0/1
 * ch_read                    chRead                Bit16s    -256..255
 * -- Timer A --
 * timer_a_cnt                timerACnt             Bit16u    10 bits
 * timer_a_reg                timerAReg             Bit16u    10 bits
 * timer_a_load_lock          timerALoadLock        Bit8u     0/1
 * timer_a_load               timerALoad            Bit8u     0/1
 * timer_a_enable             timerAEnable          Bit8u     0/1
 * timer_a_reset              timerAReset           Bit8u     0/1
 * timer_a_load_latch         timerALoadLatch       Bit8u     0/1
 * timer_a_overflow_flag      timerAOverflowFlag    Bit8u     0/1
 * timer_a_overflow           timerAOverflow        Bit8u     0/1
 * -- Timer B --
 * timer_b_cnt                timerBCnt             Bit16u    8 bits
 * timer_b_subcnt             timerBSubcnt          Bit8u     4 bits
 * timer_b_reg                timerBReg             Bit16u    8 bits
 * timer_b_load_lock          timerBLoadLock        Bit8u     0/1
 * timer_b_load               timerBLoad            Bit8u     0/1
 * timer_b_enable             timerBEnable          Bit8u     0/1
 * timer_b_reset              timerBReset           Bit8u     0/1
 * timer_b_load_latch         timerBLoadLatch       Bit8u     0/1
 * timer_b_overflow_flag      timerBOverflowFlag    Bit8u     0/1
 * timer_b_overflow           timerBOverflow        Bit8u     0/1
 * -- Register set --
 * mode_test_21[8]            modeTest21[8]         Bit8u     one bit per element
 * mode_test_2c[8]            modeTest2c[8]         Bit8u     one bit per element
 * mode_ch3                   modeCh3               Bit8u     0..3
 * mode_kon_channel           modeKonChannel        Bit8u     0..5 or 0xff
 * mode_kon_operator[4]       modeKonOperator[4]    Bit8u     0/1
 * mode_kon[24]               modeKon[24]           Bit8u     0/1
 * mode_csm                   modeCsm               Bit8u     0/1
 * mode_kon_csm               modeKonCsm            Bit8u     0/1
 * dacen                      dacen                 Bit8u     0/1
 * dacdata                    dacdata               Bit16s    0..0x1ff (bit 0 from test 2C[3])
 * ks[24]                     ks[24]                Bit8u
 * ar[24]                     ar[24]                Bit8u
 * sr[24]                     sr[24]                Bit8u
 * dt[24]                     dt[24]                Bit8u
 * multi[24]                  multi[24]             Bit8u     1 or 2..30 (already doubled)
 * sl[24]                     sl[24]                Bit8u     0..14 or 31
 * rr[24]                     rr[24]                Bit8u
 * dr[24]                     dr[24]                Bit8u
 * am[24]                     am[24]                Bit8u     0/1
 * tl[24]                     tl[24]                Bit8u     7 bits
 * ssg_eg[24]                 ssgEg[24]             Bit8u     4 bits
 * fnum[6]                    fnum[6]               Bit16u    11 bits
 * block[6]                   block[6]              Bit8u     3 bits
 * kcode[6]                   kcode[6]              Bit8u     5 bits
 * fnum_3ch[6]                fnum3ch[6]            Bit16u    11 bits
 * block_3ch[6]               block3ch[6]           Bit8u     3 bits
 * kcode_3ch[6]               kcode3ch[6]           Bit8u     5 bits
 * reg_a4                     regA4                 Bit8u
 * reg_ac                     regAc                 Bit8u
 * connect[6]                 connect[6]            Bit8u     0..7
 * fb[6]                      fb[6]                 Bit8u     0..7
 * pan_l[6], pan_r[6]         panL[6], panR[6]      Bit8u     0/1
 * ams[6]                     ams[6]                Bit8u     0..3
 * pms[6]                     pms[6]                Bit8u     0..7
 * status                     status                Bit8u
 * status_time                statusTime            Bit32u    cycles until status decays
 * </pre>
 */
public final class NukedOpn2State {

    // ym3438.h:56
    public int cycles;
    public int channel;
    public int mol;
    public int mor;
    /* IO */
    public int writeData;
    public int writeA;
    public int writeD;
    public int writeAEn;
    public int writeDEn;
    public int writeBusy;
    public int writeBusyCnt;
    public int writeFmAddress;
    public int writeFmData;
    public int writeFmModeA;
    public int address;
    public int data;
    public int pinTestIn;
    public int pinIrq;
    public int busy;
    /* LFO */
    public int lfoEn;
    public int lfoFreq;
    public int lfoPm;
    public int lfoAm;
    public int lfoCnt;
    public int lfoInc;
    public int lfoQuotient;
    /* Phase generator */
    public int pgFnum;
    public int pgBlock;
    public int pgKcode;
    public final int[] pgInc = new int[24];
    public final int[] pgPhase = new int[24];
    public final int[] pgReset = new int[24];
    public int pgRead;
    /* Envelope generator */
    public int egCycle;
    public int egCycleStop;
    public int egShift;
    public int egShiftLock;
    public int egTimerLowLock;
    public int egTimer;
    public int egTimerInc;
    public int egQuotient;
    public int egCustomTimer;
    public int egRate;
    public int egKsv;
    public int egInc;
    public int egRatemax;
    public final int[] egSl = new int[2];
    public int egLfoAm;
    public final int[] egTl = new int[2];
    public final int[] egState = new int[24];
    public final int[] egLevel = new int[24];
    public final int[] egOut = new int[24];
    public final int[] egKon = new int[24];
    public final int[] egKonCsm = new int[24];
    public final int[] egKonLatch = new int[24];
    public final int[] egCsmMode = new int[24];
    public final int[] egSsgEnable = new int[24];
    public final int[] egSsgPgrstLatch = new int[24];
    public final int[] egSsgRepeatLatch = new int[24];
    public final int[] egSsgHoldUpLatch = new int[24];
    public final int[] egSsgDir = new int[24];
    public final int[] egSsgInv = new int[24];
    public final int[] egRead = new int[2];
    public int egReadInc;
    /* FM */
    public final int[][] fmOp1 = new int[6][2];
    public final int[] fmOp2 = new int[6];
    public final int[] fmOut = new int[24];
    public final int[] fmMod = new int[24];
    /* Channel */
    public final int[] chAcc = new int[6];
    public final int[] chOut = new int[6];
    public int chLock;
    public int chLockL;
    public int chLockR;
    public int chRead;
    /* Timer */
    public int timerACnt;
    public int timerAReg;
    public int timerALoadLock;
    public int timerALoad;
    public int timerAEnable;
    public int timerAReset;
    public int timerALoadLatch;
    public int timerAOverflowFlag;
    public int timerAOverflow;

    public int timerBCnt;
    public int timerBSubcnt;
    public int timerBReg;
    public int timerBLoadLock;
    public int timerBLoad;
    public int timerBEnable;
    public int timerBReset;
    public int timerBLoadLatch;
    public int timerBOverflowFlag;
    public int timerBOverflow;

    /* Register set */
    public final int[] modeTest21 = new int[8];
    public final int[] modeTest2c = new int[8];
    public int modeCh3;
    public int modeKonChannel;
    public final int[] modeKonOperator = new int[4];
    public final int[] modeKon = new int[24];
    public int modeCsm;
    public int modeKonCsm;
    public int dacen;
    public int dacdata;

    public final int[] ks = new int[24];
    public final int[] ar = new int[24];
    public final int[] sr = new int[24];
    public final int[] dt = new int[24];
    public final int[] multi = new int[24];
    public final int[] sl = new int[24];
    public final int[] rr = new int[24];
    public final int[] dr = new int[24];
    public final int[] am = new int[24];
    public final int[] tl = new int[24];
    public final int[] ssgEg = new int[24];

    public final int[] fnum = new int[6];
    public final int[] block = new int[6];
    public final int[] kcode = new int[6];
    public final int[] fnum3ch = new int[6];
    public final int[] block3ch = new int[6];
    public final int[] kcode3ch = new int[6];
    public int regA4;
    public int regAc;
    public final int[] connect = new int[6];
    public final int[] fb = new int[6];
    public final int[] panL = new int[6];
    public final int[] panR = new int[6];
    public final int[] ams = new int[6];
    public final int[] pms = new int[6];
    public int status;
    public int statusTime;

    /** Creates an all-zero state; call {@link NukedOpn2#reset()} for a hardware reset. */
    public NukedOpn2State() {
    }

    /** Zeroes every field: the {@code memset(chip, 0, sizeof(ym3438_t))} of {@code OPN2_Reset}. */
    // ym3438.c:1189
    public void clear() {
        cycles = 0;
        channel = 0;
        mol = 0;
        mor = 0;
        writeData = 0;
        writeA = 0;
        writeD = 0;
        writeAEn = 0;
        writeDEn = 0;
        writeBusy = 0;
        writeBusyCnt = 0;
        writeFmAddress = 0;
        writeFmData = 0;
        writeFmModeA = 0;
        address = 0;
        data = 0;
        pinTestIn = 0;
        pinIrq = 0;
        busy = 0;
        lfoEn = 0;
        lfoFreq = 0;
        lfoPm = 0;
        lfoAm = 0;
        lfoCnt = 0;
        lfoInc = 0;
        lfoQuotient = 0;
        pgFnum = 0;
        pgBlock = 0;
        pgKcode = 0;
        Arrays.fill(pgInc, 0);
        Arrays.fill(pgPhase, 0);
        Arrays.fill(pgReset, 0);
        pgRead = 0;
        egCycle = 0;
        egCycleStop = 0;
        egShift = 0;
        egShiftLock = 0;
        egTimerLowLock = 0;
        egTimer = 0;
        egTimerInc = 0;
        egQuotient = 0;
        egCustomTimer = 0;
        egRate = 0;
        egKsv = 0;
        egInc = 0;
        egRatemax = 0;
        Arrays.fill(egSl, 0);
        egLfoAm = 0;
        Arrays.fill(egTl, 0);
        Arrays.fill(egState, 0);
        Arrays.fill(egLevel, 0);
        Arrays.fill(egOut, 0);
        Arrays.fill(egKon, 0);
        Arrays.fill(egKonCsm, 0);
        Arrays.fill(egKonLatch, 0);
        Arrays.fill(egCsmMode, 0);
        Arrays.fill(egSsgEnable, 0);
        Arrays.fill(egSsgPgrstLatch, 0);
        Arrays.fill(egSsgRepeatLatch, 0);
        Arrays.fill(egSsgHoldUpLatch, 0);
        Arrays.fill(egSsgDir, 0);
        Arrays.fill(egSsgInv, 0);
        Arrays.fill(egRead, 0);
        egReadInc = 0;
        for (int[] pair : fmOp1) {
            Arrays.fill(pair, 0);
        }
        Arrays.fill(fmOp2, 0);
        Arrays.fill(fmOut, 0);
        Arrays.fill(fmMod, 0);
        Arrays.fill(chAcc, 0);
        Arrays.fill(chOut, 0);
        chLock = 0;
        chLockL = 0;
        chLockR = 0;
        chRead = 0;
        timerACnt = 0;
        timerAReg = 0;
        timerALoadLock = 0;
        timerALoad = 0;
        timerAEnable = 0;
        timerAReset = 0;
        timerALoadLatch = 0;
        timerAOverflowFlag = 0;
        timerAOverflow = 0;
        timerBCnt = 0;
        timerBSubcnt = 0;
        timerBReg = 0;
        timerBLoadLock = 0;
        timerBLoad = 0;
        timerBEnable = 0;
        timerBReset = 0;
        timerBLoadLatch = 0;
        timerBOverflowFlag = 0;
        timerBOverflow = 0;
        Arrays.fill(modeTest21, 0);
        Arrays.fill(modeTest2c, 0);
        modeCh3 = 0;
        modeKonChannel = 0;
        Arrays.fill(modeKonOperator, 0);
        Arrays.fill(modeKon, 0);
        modeCsm = 0;
        modeKonCsm = 0;
        dacen = 0;
        dacdata = 0;
        Arrays.fill(ks, 0);
        Arrays.fill(ar, 0);
        Arrays.fill(sr, 0);
        Arrays.fill(dt, 0);
        Arrays.fill(multi, 0);
        Arrays.fill(sl, 0);
        Arrays.fill(rr, 0);
        Arrays.fill(dr, 0);
        Arrays.fill(am, 0);
        Arrays.fill(tl, 0);
        Arrays.fill(ssgEg, 0);
        Arrays.fill(fnum, 0);
        Arrays.fill(block, 0);
        Arrays.fill(kcode, 0);
        Arrays.fill(fnum3ch, 0);
        Arrays.fill(block3ch, 0);
        Arrays.fill(kcode3ch, 0);
        regA4 = 0;
        regAc = 0;
        Arrays.fill(connect, 0);
        Arrays.fill(fb, 0);
        Arrays.fill(panL, 0);
        Arrays.fill(panR, 0);
        Arrays.fill(ams, 0);
        Arrays.fill(pms, 0);
        status = 0;
        statusTime = 0;
    }

    /** Copies every field of {@code other} into this state (a plain struct copy). */
    public void copyFrom(NukedOpn2State other) {
        cycles = other.cycles;
        channel = other.channel;
        mol = other.mol;
        mor = other.mor;
        writeData = other.writeData;
        writeA = other.writeA;
        writeD = other.writeD;
        writeAEn = other.writeAEn;
        writeDEn = other.writeDEn;
        writeBusy = other.writeBusy;
        writeBusyCnt = other.writeBusyCnt;
        writeFmAddress = other.writeFmAddress;
        writeFmData = other.writeFmData;
        writeFmModeA = other.writeFmModeA;
        address = other.address;
        data = other.data;
        pinTestIn = other.pinTestIn;
        pinIrq = other.pinIrq;
        busy = other.busy;
        lfoEn = other.lfoEn;
        lfoFreq = other.lfoFreq;
        lfoPm = other.lfoPm;
        lfoAm = other.lfoAm;
        lfoCnt = other.lfoCnt;
        lfoInc = other.lfoInc;
        lfoQuotient = other.lfoQuotient;
        pgFnum = other.pgFnum;
        pgBlock = other.pgBlock;
        pgKcode = other.pgKcode;
        copy(other.pgInc, pgInc);
        copy(other.pgPhase, pgPhase);
        copy(other.pgReset, pgReset);
        pgRead = other.pgRead;
        egCycle = other.egCycle;
        egCycleStop = other.egCycleStop;
        egShift = other.egShift;
        egShiftLock = other.egShiftLock;
        egTimerLowLock = other.egTimerLowLock;
        egTimer = other.egTimer;
        egTimerInc = other.egTimerInc;
        egQuotient = other.egQuotient;
        egCustomTimer = other.egCustomTimer;
        egRate = other.egRate;
        egKsv = other.egKsv;
        egInc = other.egInc;
        egRatemax = other.egRatemax;
        copy(other.egSl, egSl);
        egLfoAm = other.egLfoAm;
        copy(other.egTl, egTl);
        copy(other.egState, egState);
        copy(other.egLevel, egLevel);
        copy(other.egOut, egOut);
        copy(other.egKon, egKon);
        copy(other.egKonCsm, egKonCsm);
        copy(other.egKonLatch, egKonLatch);
        copy(other.egCsmMode, egCsmMode);
        copy(other.egSsgEnable, egSsgEnable);
        copy(other.egSsgPgrstLatch, egSsgPgrstLatch);
        copy(other.egSsgRepeatLatch, egSsgRepeatLatch);
        copy(other.egSsgHoldUpLatch, egSsgHoldUpLatch);
        copy(other.egSsgDir, egSsgDir);
        copy(other.egSsgInv, egSsgInv);
        copy(other.egRead, egRead);
        egReadInc = other.egReadInc;
        for (int i = 0; i < 6; i++) {
            copy(other.fmOp1[i], fmOp1[i]);
        }
        copy(other.fmOp2, fmOp2);
        copy(other.fmOut, fmOut);
        copy(other.fmMod, fmMod);
        copy(other.chAcc, chAcc);
        copy(other.chOut, chOut);
        chLock = other.chLock;
        chLockL = other.chLockL;
        chLockR = other.chLockR;
        chRead = other.chRead;
        timerACnt = other.timerACnt;
        timerAReg = other.timerAReg;
        timerALoadLock = other.timerALoadLock;
        timerALoad = other.timerALoad;
        timerAEnable = other.timerAEnable;
        timerAReset = other.timerAReset;
        timerALoadLatch = other.timerALoadLatch;
        timerAOverflowFlag = other.timerAOverflowFlag;
        timerAOverflow = other.timerAOverflow;
        timerBCnt = other.timerBCnt;
        timerBSubcnt = other.timerBSubcnt;
        timerBReg = other.timerBReg;
        timerBLoadLock = other.timerBLoadLock;
        timerBLoad = other.timerBLoad;
        timerBEnable = other.timerBEnable;
        timerBReset = other.timerBReset;
        timerBLoadLatch = other.timerBLoadLatch;
        timerBOverflowFlag = other.timerBOverflowFlag;
        timerBOverflow = other.timerBOverflow;
        copy(other.modeTest21, modeTest21);
        copy(other.modeTest2c, modeTest2c);
        modeCh3 = other.modeCh3;
        modeKonChannel = other.modeKonChannel;
        copy(other.modeKonOperator, modeKonOperator);
        copy(other.modeKon, modeKon);
        modeCsm = other.modeCsm;
        modeKonCsm = other.modeKonCsm;
        dacen = other.dacen;
        dacdata = other.dacdata;
        copy(other.ks, ks);
        copy(other.ar, ar);
        copy(other.sr, sr);
        copy(other.dt, dt);
        copy(other.multi, multi);
        copy(other.sl, sl);
        copy(other.rr, rr);
        copy(other.dr, dr);
        copy(other.am, am);
        copy(other.tl, tl);
        copy(other.ssgEg, ssgEg);
        copy(other.fnum, fnum);
        copy(other.block, block);
        copy(other.kcode, kcode);
        copy(other.fnum3ch, fnum3ch);
        copy(other.block3ch, block3ch);
        copy(other.kcode3ch, kcode3ch);
        regA4 = other.regA4;
        regAc = other.regAc;
        copy(other.connect, connect);
        copy(other.fb, fb);
        copy(other.panL, panL);
        copy(other.panR, panR);
        copy(other.ams, ams);
        copy(other.pms, pms);
        status = other.status;
        statusTime = other.statusTime;
    }

    /**
     * Value equality over every field, so a snapshot that carries this state
     * compares by contents rather than identity (the struct compare a
     * {@code memcmp} of two {@code ym3438_t} would give).
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof NukedOpn2State other)) {
            return false;
        }
        return cycles == other.cycles
                && channel == other.channel
                && mol == other.mol
                && mor == other.mor
                && writeData == other.writeData
                && writeA == other.writeA
                && writeD == other.writeD
                && writeAEn == other.writeAEn
                && writeDEn == other.writeDEn
                && writeBusy == other.writeBusy
                && writeBusyCnt == other.writeBusyCnt
                && writeFmAddress == other.writeFmAddress
                && writeFmData == other.writeFmData
                && writeFmModeA == other.writeFmModeA
                && address == other.address
                && data == other.data
                && pinTestIn == other.pinTestIn
                && pinIrq == other.pinIrq
                && busy == other.busy
                && lfoEn == other.lfoEn
                && lfoFreq == other.lfoFreq
                && lfoPm == other.lfoPm
                && lfoAm == other.lfoAm
                && lfoCnt == other.lfoCnt
                && lfoInc == other.lfoInc
                && lfoQuotient == other.lfoQuotient
                && pgFnum == other.pgFnum
                && pgBlock == other.pgBlock
                && pgKcode == other.pgKcode
                && Arrays.equals(pgInc, other.pgInc)
                && Arrays.equals(pgPhase, other.pgPhase)
                && Arrays.equals(pgReset, other.pgReset)
                && pgRead == other.pgRead
                && egCycle == other.egCycle
                && egCycleStop == other.egCycleStop
                && egShift == other.egShift
                && egShiftLock == other.egShiftLock
                && egTimerLowLock == other.egTimerLowLock
                && egTimer == other.egTimer
                && egTimerInc == other.egTimerInc
                && egQuotient == other.egQuotient
                && egCustomTimer == other.egCustomTimer
                && egRate == other.egRate
                && egKsv == other.egKsv
                && egInc == other.egInc
                && egRatemax == other.egRatemax
                && Arrays.equals(egSl, other.egSl)
                && egLfoAm == other.egLfoAm
                && Arrays.equals(egTl, other.egTl)
                && Arrays.equals(egState, other.egState)
                && Arrays.equals(egLevel, other.egLevel)
                && Arrays.equals(egOut, other.egOut)
                && Arrays.equals(egKon, other.egKon)
                && Arrays.equals(egKonCsm, other.egKonCsm)
                && Arrays.equals(egKonLatch, other.egKonLatch)
                && Arrays.equals(egCsmMode, other.egCsmMode)
                && Arrays.equals(egSsgEnable, other.egSsgEnable)
                && Arrays.equals(egSsgPgrstLatch, other.egSsgPgrstLatch)
                && Arrays.equals(egSsgRepeatLatch, other.egSsgRepeatLatch)
                && Arrays.equals(egSsgHoldUpLatch, other.egSsgHoldUpLatch)
                && Arrays.equals(egSsgDir, other.egSsgDir)
                && Arrays.equals(egSsgInv, other.egSsgInv)
                && Arrays.equals(egRead, other.egRead)
                && egReadInc == other.egReadInc
                && Arrays.deepEquals(fmOp1, other.fmOp1)
                && Arrays.equals(fmOp2, other.fmOp2)
                && Arrays.equals(fmOut, other.fmOut)
                && Arrays.equals(fmMod, other.fmMod)
                && Arrays.equals(chAcc, other.chAcc)
                && Arrays.equals(chOut, other.chOut)
                && chLock == other.chLock
                && chLockL == other.chLockL
                && chLockR == other.chLockR
                && chRead == other.chRead
                && timerACnt == other.timerACnt
                && timerAReg == other.timerAReg
                && timerALoadLock == other.timerALoadLock
                && timerALoad == other.timerALoad
                && timerAEnable == other.timerAEnable
                && timerAReset == other.timerAReset
                && timerALoadLatch == other.timerALoadLatch
                && timerAOverflowFlag == other.timerAOverflowFlag
                && timerAOverflow == other.timerAOverflow
                && timerBCnt == other.timerBCnt
                && timerBSubcnt == other.timerBSubcnt
                && timerBReg == other.timerBReg
                && timerBLoadLock == other.timerBLoadLock
                && timerBLoad == other.timerBLoad
                && timerBEnable == other.timerBEnable
                && timerBReset == other.timerBReset
                && timerBLoadLatch == other.timerBLoadLatch
                && timerBOverflowFlag == other.timerBOverflowFlag
                && timerBOverflow == other.timerBOverflow
                && Arrays.equals(modeTest21, other.modeTest21)
                && Arrays.equals(modeTest2c, other.modeTest2c)
                && modeCh3 == other.modeCh3
                && modeKonChannel == other.modeKonChannel
                && Arrays.equals(modeKonOperator, other.modeKonOperator)
                && Arrays.equals(modeKon, other.modeKon)
                && modeCsm == other.modeCsm
                && modeKonCsm == other.modeKonCsm
                && dacen == other.dacen
                && dacdata == other.dacdata
                && Arrays.equals(ks, other.ks)
                && Arrays.equals(ar, other.ar)
                && Arrays.equals(sr, other.sr)
                && Arrays.equals(dt, other.dt)
                && Arrays.equals(multi, other.multi)
                && Arrays.equals(sl, other.sl)
                && Arrays.equals(rr, other.rr)
                && Arrays.equals(dr, other.dr)
                && Arrays.equals(am, other.am)
                && Arrays.equals(tl, other.tl)
                && Arrays.equals(ssgEg, other.ssgEg)
                && Arrays.equals(fnum, other.fnum)
                && Arrays.equals(block, other.block)
                && Arrays.equals(kcode, other.kcode)
                && Arrays.equals(fnum3ch, other.fnum3ch)
                && Arrays.equals(block3ch, other.block3ch)
                && Arrays.equals(kcode3ch, other.kcode3ch)
                && regA4 == other.regA4
                && regAc == other.regAc
                && Arrays.equals(connect, other.connect)
                && Arrays.equals(fb, other.fb)
                && Arrays.equals(panL, other.panL)
                && Arrays.equals(panR, other.panR)
                && Arrays.equals(ams, other.ams)
                && Arrays.equals(pms, other.pms)
                && status == other.status
                && statusTime == other.statusTime;
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = 31 * result + cycles;
        result = 31 * result + channel;
        result = 31 * result + mol;
        result = 31 * result + mor;
        result = 31 * result + writeData;
        result = 31 * result + writeA;
        result = 31 * result + writeD;
        result = 31 * result + writeAEn;
        result = 31 * result + writeDEn;
        result = 31 * result + writeBusy;
        result = 31 * result + writeBusyCnt;
        result = 31 * result + writeFmAddress;
        result = 31 * result + writeFmData;
        result = 31 * result + writeFmModeA;
        result = 31 * result + address;
        result = 31 * result + data;
        result = 31 * result + pinTestIn;
        result = 31 * result + pinIrq;
        result = 31 * result + busy;
        result = 31 * result + lfoEn;
        result = 31 * result + lfoFreq;
        result = 31 * result + lfoPm;
        result = 31 * result + lfoAm;
        result = 31 * result + lfoCnt;
        result = 31 * result + lfoInc;
        result = 31 * result + lfoQuotient;
        result = 31 * result + pgFnum;
        result = 31 * result + pgBlock;
        result = 31 * result + pgKcode;
        result = 31 * result + Arrays.hashCode(pgInc);
        result = 31 * result + Arrays.hashCode(pgPhase);
        result = 31 * result + Arrays.hashCode(pgReset);
        result = 31 * result + pgRead;
        result = 31 * result + egCycle;
        result = 31 * result + egCycleStop;
        result = 31 * result + egShift;
        result = 31 * result + egShiftLock;
        result = 31 * result + egTimerLowLock;
        result = 31 * result + egTimer;
        result = 31 * result + egTimerInc;
        result = 31 * result + egQuotient;
        result = 31 * result + egCustomTimer;
        result = 31 * result + egRate;
        result = 31 * result + egKsv;
        result = 31 * result + egInc;
        result = 31 * result + egRatemax;
        result = 31 * result + Arrays.hashCode(egSl);
        result = 31 * result + egLfoAm;
        result = 31 * result + Arrays.hashCode(egTl);
        result = 31 * result + Arrays.hashCode(egState);
        result = 31 * result + Arrays.hashCode(egLevel);
        result = 31 * result + Arrays.hashCode(egOut);
        result = 31 * result + Arrays.hashCode(egKon);
        result = 31 * result + Arrays.hashCode(egKonCsm);
        result = 31 * result + Arrays.hashCode(egKonLatch);
        result = 31 * result + Arrays.hashCode(egCsmMode);
        result = 31 * result + Arrays.hashCode(egSsgEnable);
        result = 31 * result + Arrays.hashCode(egSsgPgrstLatch);
        result = 31 * result + Arrays.hashCode(egSsgRepeatLatch);
        result = 31 * result + Arrays.hashCode(egSsgHoldUpLatch);
        result = 31 * result + Arrays.hashCode(egSsgDir);
        result = 31 * result + Arrays.hashCode(egSsgInv);
        result = 31 * result + Arrays.hashCode(egRead);
        result = 31 * result + egReadInc;
        result = 31 * result + Arrays.deepHashCode(fmOp1);
        result = 31 * result + Arrays.hashCode(fmOp2);
        result = 31 * result + Arrays.hashCode(fmOut);
        result = 31 * result + Arrays.hashCode(fmMod);
        result = 31 * result + Arrays.hashCode(chAcc);
        result = 31 * result + Arrays.hashCode(chOut);
        result = 31 * result + chLock;
        result = 31 * result + chLockL;
        result = 31 * result + chLockR;
        result = 31 * result + chRead;
        result = 31 * result + timerACnt;
        result = 31 * result + timerAReg;
        result = 31 * result + timerALoadLock;
        result = 31 * result + timerALoad;
        result = 31 * result + timerAEnable;
        result = 31 * result + timerAReset;
        result = 31 * result + timerALoadLatch;
        result = 31 * result + timerAOverflowFlag;
        result = 31 * result + timerAOverflow;
        result = 31 * result + timerBCnt;
        result = 31 * result + timerBSubcnt;
        result = 31 * result + timerBReg;
        result = 31 * result + timerBLoadLock;
        result = 31 * result + timerBLoad;
        result = 31 * result + timerBEnable;
        result = 31 * result + timerBReset;
        result = 31 * result + timerBLoadLatch;
        result = 31 * result + timerBOverflowFlag;
        result = 31 * result + timerBOverflow;
        result = 31 * result + Arrays.hashCode(modeTest21);
        result = 31 * result + Arrays.hashCode(modeTest2c);
        result = 31 * result + modeCh3;
        result = 31 * result + modeKonChannel;
        result = 31 * result + Arrays.hashCode(modeKonOperator);
        result = 31 * result + Arrays.hashCode(modeKon);
        result = 31 * result + modeCsm;
        result = 31 * result + modeKonCsm;
        result = 31 * result + dacen;
        result = 31 * result + dacdata;
        result = 31 * result + Arrays.hashCode(ks);
        result = 31 * result + Arrays.hashCode(ar);
        result = 31 * result + Arrays.hashCode(sr);
        result = 31 * result + Arrays.hashCode(dt);
        result = 31 * result + Arrays.hashCode(multi);
        result = 31 * result + Arrays.hashCode(sl);
        result = 31 * result + Arrays.hashCode(rr);
        result = 31 * result + Arrays.hashCode(dr);
        result = 31 * result + Arrays.hashCode(am);
        result = 31 * result + Arrays.hashCode(tl);
        result = 31 * result + Arrays.hashCode(ssgEg);
        result = 31 * result + Arrays.hashCode(fnum);
        result = 31 * result + Arrays.hashCode(block);
        result = 31 * result + Arrays.hashCode(kcode);
        result = 31 * result + Arrays.hashCode(fnum3ch);
        result = 31 * result + Arrays.hashCode(block3ch);
        result = 31 * result + Arrays.hashCode(kcode3ch);
        result = 31 * result + regA4;
        result = 31 * result + regAc;
        result = 31 * result + Arrays.hashCode(connect);
        result = 31 * result + Arrays.hashCode(fb);
        result = 31 * result + Arrays.hashCode(panL);
        result = 31 * result + Arrays.hashCode(panR);
        result = 31 * result + Arrays.hashCode(ams);
        result = 31 * result + Arrays.hashCode(pms);
        result = 31 * result + status;
        result = 31 * result + statusTime;
        return result;
    }

    /** Returns a deep copy of this state. */
    public NukedOpn2State copy() {
        NukedOpn2State copy = new NukedOpn2State();
        copy.copyFrom(this);
        return copy;
    }

    private static void copy(int[] from, int[] to) {
        System.arraycopy(from, 0, to, 0, to.length);
    }
}
