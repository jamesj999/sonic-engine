package com.openggf.audio.synth;

import com.openggf.audio.synth.nuked.NukedOpn2;
import com.openggf.audio.synth.nuked.NukedOpn2State;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hardware-derived behaviour vectors for the FM core.
 *
 * <p>Every expected value comes from
 * {@code docs/architecture/research/audio/2026-08-29-ym2612-behaviour-vectors.md}
 * (the "vectors doc"); each assertion message names the section or vector id it
 * asserts. Where the doc and the pinned Nuked-OPN2 die model disagreed, the doc
 * was corrected and the correction is recorded there under "Nuked-OPN2
 * cross-check"; nothing here is a value read back from the port.
 *
 * <p>Vectors that need internal state (attenuation, phase increment, LFO
 * counter, timer counters) run against {@link NukedOpn2} through its public
 * state object; vectors that are visible through the engine facade run against
 * {@link Ym2612Chip} at its internal rate, where one rendered frame is one chip
 * sample. Rows the doc tags {@code ?} are not asserted.
 */
class TestYm2612HardwareBehaviour {

    /** Vectors doc, Clock frame: Fs (NTSC) = 7,670,453.57 / 144. */
    private static final double FS_NTSC = 53267.04;
    private static final int SILENT = 0x3ff;

    /** Register-order slot offset for an algorithm-order operator 1..4 (doc Group 1: S1 S3 S2 S4). */
    private static final int[] REG_SLOT = { -1, 0x0, 0x8, 0x4, 0xC };

    /**
     * Nuked's 24-entry slot index for channel {@code ch} (0-5) and
     * algorithm-order operator {@code op} (1-4). The port's key-on stage stores
     * op1 at {@code ch}, op2 at {@code ch + 12}, op3 at {@code ch + 6} and op4 at
     * {@code ch + 18}.
     */
    private static int slot(int ch, int op) {
        return switch (op) {
            case 1 -> ch;
            case 2 -> ch + 12;
            case 3 -> ch + 6;
            case 4 -> ch + 18;
            default -> throw new IllegalArgumentException("op " + op);
        };
    }

    /** $28 channel field (doc Group 1): 0-2 for channels 1-3, 4-6 for channels 4-6. */
    private static int keySelect(int ch) {
        return (ch % 3) | ((ch / 3) << 2);
    }

    /** Bus harness over the port with the same pacing the C parity harness uses. */
    private static final class Core {
        final NukedOpn2 chip = new NukedOpn2();
        final NukedOpn2State st;
        private final int[] pins = new int[2];
        /** Timer overflow pulses last one internal cycle, so they are latched over each {@link #frame()}. */
        boolean overflowA;
        boolean overflowB;

        Core(int chipType) {
            chip.setChipType(chipType);
            st = chip.state();
        }

        static Core ym3438() {
            return new Core(0);
        }

        static Core ym2612() {
            return new Core(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);
        }

        void clock(int cycles) {
            for (int i = 0; i < cycles; i++) {
                chip.clock(pins);
            }
        }

        /** Address strobe, four cycles, data strobe, 28 cycles (the doc's ≥ 83 T-state data spacing, in cycles). */
        void write(int port, int reg, int val) {
            chip.write(port * 2, reg);
            clock(4);
            chip.write(port * 2 + 1, val);
            clock(28);
        }

        /** The clock cost of one {@link #write} without the write, so two cores keep the same schedule. */
        void nop() {
            clock(32);
        }

        /** Operator register {@code base} ($30..$90) for channel {@code ch}, algorithm-order operator {@code op}. */
        void op(int ch, int op, int base, int val) {
            write(ch / 3, base + (ch % 3) + REG_SLOT[op], val);
        }

        void channel(int ch, int base, int val) {
            write(ch / 3, base + (ch % 3), val);
        }

        void keyOn(int ch, int opMask) {
            write(0, 0x28, (opMask << 4) | keySelect(ch));
        }

        void keyOff(int ch) {
            keyOn(ch, 0);
        }

        int status() {
            return chip.read(0);
        }

        /** Clocks to the next sample boundary (cycle 0). */
        void settle() {
            while (st.cycles != 0) {
                clock(1);
            }
        }

        /** One full 24-cycle sample from a boundary; returns the MOL and MOR pin sums. */
        int[] frame() {
            settle();
            int l = 0;
            int r = 0;
            overflowA = false;
            overflowB = false;
            for (int i = 0; i < NukedOpn2.CYCLES_PER_FRAME; i++) {
                chip.clock(pins);
                l += pins[0];
                r += pins[1];
                overflowA |= st.timerAOverflow != 0;
                overflowB |= st.timerBOverflow != 0;
            }
            return new int[] { l, r };
        }

        void frames(int n) {
            for (int i = 0; i < n; i++) {
                frame();
            }
        }

        int[] levels(int slot, int n) {
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                frame();
                out[i] = st.egLevel[slot];
            }
            return out;
        }

        int[] egOut(int slot, int n) {
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                frame();
                out[i] = st.egOut[slot];
            }
            return out;
        }

        int[] channelOut(int ch, int n) {
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                frame();
                out[i] = st.chOut[ch];
            }
            return out;
        }

        int[] leftSums(int n) {
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                out[i] = frame()[0];
            }
            return out;
        }

        /**
         * A one-operator voice on {@code ch}: algorithm {@code alg}, feedback
         * {@code fb}, per-operator TL, AR/D1R/D2R/D1L/RR, DT 0, MUL 1,
         * L = R = 1, SSG-EG off, no AM.
         */
        void voice(int ch, int alg, int fb, int[] tl, int ar, int d1r, int d2r, int d1l, int rr) {
            channel(ch, 0xB0, (fb << 3) | alg);
            channel(ch, 0xB4, 0xC0);
            for (int op = 1; op <= 4; op++) {
                op(ch, op, 0x30, 0x01);
                op(ch, op, 0x40, tl[op - 1]);
                op(ch, op, 0x50, ar);
                op(ch, op, 0x60, d1r);
                op(ch, op, 0x70, d2r);
                op(ch, op, 0x80, (d1l << 4) | rr);
                op(ch, op, 0x90, 0x00);
            }
        }

        /** Block / F-number through the $A4 latch then $A0 (doc Group 1 latch order). */
        void note(int ch, int block, int fnum) {
            channel(ch, 0xA4, (block << 3) | (fnum >> 8));
            channel(ch, 0xA0, fnum & 0xff);
        }
    }

    private static final int[] ONE_OP = { 0, 127, 127, 127 };
    private static final int[] ALL_OPS = { 0, 0, 0, 0 };

    /** Consecutive runs of equal values as (value, length) pairs. */
    private static List<int[]> runs(int[] seq) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < seq.length) {
            int j = i;
            while (j < seq.length && seq[j] == seq[i]) {
                j++;
            }
            out.add(new int[] { seq[i], j - i });
            i = j;
        }
        return out;
    }

    private static int firstIndexOf(int[] seq, int value) {
        for (int i = 0; i < seq.length; i++) {
            if (seq[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static int firstIndexAtMost(int[] seq, int value) {
        for (int i = 0; i < seq.length; i++) {
            if (seq[i] <= value) {
                return i;
            }
        }
        return -1;
    }

    private static int firstIndexAtLeast(int[] seq, int value) {
        for (int i = 0; i < seq.length; i++) {
            if (seq[i] >= value) {
                return i;
            }
        }
        return -1;
    }

    private static int max(int[] seq) {
        int m = Integer.MIN_VALUE;
        for (int v : seq) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static int min(int[] seq) {
        int m = Integer.MAX_VALUE;
        for (int v : seq) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static boolean anyNonZero(int[] seq) {
        for (int v : seq) {
            if (v != 0) {
                return true;
            }
        }
        return false;
    }

    /** Doc Group 3: logsin[i] = round(-log2(sin((i + 0.5) * pi / 512)) * 256). */
    private static int docLogSin(int i) {
        return (int) Math.round(-Math.log(Math.sin((i + 0.5) * Math.PI / 512)) / Math.log(2) * 256);
    }

    /** Doc Group 3: exp[j] = round((2^(j / 256) - 1) * 1024). */
    private static int docExp(int j) {
        return (int) Math.round((Math.pow(2, j / 256.0) - 1) * 1024);
    }

    /** Doc Group 3 pipeline: 14-bit signed operator output for a 10-bit phase index and 10-bit attenuation. */
    private static int docOperatorOutput(int phase, int att) {
        int index = (phase & 0x100) != 0 ? (~phase & 0xff) : (phase & 0xff);
        int level = docLogSin(index) + (att << 2);
        int mag = ((docExp(~level & 0xff) | 0x400) << 2) >> (level >> 8);
        return (phase & 0x200) != 0 ? -mag : mag;
    }

    /** Doc Group 4: inc0 = (fnum << block) >> 1, before detune and multiple. */
    private static int docInc0(int fnum, int block) {
        return (fnum << block) >> 1;
    }

    @Nested
    class ClockFrame {
        @Test
        void sampleRateIsMasterOver7Over144() {
            assertEquals(FS_NTSC, Ym2612Chip.getInternalRate(), 0.01,
                    "Clock frame: Fs (NTSC) = 7,670,453.57 / 144 = 53,267.04 Hz");
            assertEquals(53267, (int) Ym2612Chip.getInternalRate(),
                    "CLK-01: 53,267 samples per NTSC second");
            assertEquals(NukedOpn2.CYCLES_PER_FRAME, 24,
                    "Clock frame: 24 internal cycles per sample (6 channels x 4 operators)");
            assertEquals(FS_NTSC, 53693175.0 / 7 / 144, 0.01,
                    "Clock frame: NTSC master 53,693,175 / 7 / 144");
            assertEquals(52781.17, 53203424.0 / 7 / 144, 0.01,
                    "CLK-03: PAL 53,203,424 / 7 / 144 = 52,781.17 Hz");
            assertEquals(1.0092, (53693175.0 / 7 / 144) / (53203424.0 / 7 / 144), 0.0001,
                    "CLK-03: PAL millisecond figures are NTSC x 1.0092");
            assertEquals(1.0 / 67.2, 15.0 / 1008, 1e-5,
                    "Clock frame: one Z80 T-state = 15 master cycles = 1/67.2 sample");
        }

        @Test
        void twentyFourCyclesAdvanceTimerAOnceAndTheEgEveryThirdSample() {
            Core core = Core.ym3438();
            core.write(0, 0x24, 0x00);
            core.write(0, 0x25, 0x00);
            core.write(0, 0x27, 0x01);
            core.settle();
            assertEquals(0, core.st.cycles, "CLK-02: a sample boundary is cycle 0 of 24");
            int previousTimer = core.st.timerACnt;
            int previousEg = core.st.egTimer;
            int egSteps = 0;
            for (int i = 0; i < 300; i++) {
                core.frame();
                assertEquals((previousTimer + 1) & 0x3ff, core.st.timerACnt,
                        "CLK-02 / TMR: timer A counts exactly once per 144-cycle sample");
                previousTimer = core.st.timerACnt;
                if (core.st.egTimer != previousEg) {
                    egSteps++;
                    previousEg = core.st.egTimer;
                }
            }
            assertEquals(100, egSteps, "CLK-02 / Group 2: the EG step counter advances once every 3 samples");
        }
    }

    @Nested
    class Group1RegisterMapAndLatch {
        @Test
        void reg01PartIWritesReachChannelOneOnly() {
            Core core = Core.ym3438();
            core.write(0, 0xB4, 0xC0);
            core.write(0, 0x30, 0x71);
            core.note(0, 4, 0x439);
            core.frames(2);
            assertEquals(1, core.st.panL[0], "REG-01: $B4 = $C0 sets channel 1 L");
            assertEquals(1, core.st.panR[0], "REG-01: $B4 = $C0 sets channel 1 R");
            assertEquals(0, core.st.ams[0], "REG-01: AMS = 0");
            assertEquals(0, core.st.pms[0], "REG-01: PMS = 0");
            assertEquals(7, core.st.dt[0], "REG-01: $30 = $71 -> ch1 op1 DT = 7");
            assertEquals(docInc0(0x439, 4) - 9, core.st.pgInc[0],
                    "REG-01 + PG-01 + Group 4 table: MUL 1, DT 7 = -DT3 at kc 18 (9) -> 8648 - 9");
            assertEquals(0, core.st.fnum[3], "REG-01: channel 4 F-number unchanged");
            assertEquals(0, core.st.dt[3], "REG-01: channel 4 op1 DT unchanged");
            assertEquals(0, core.st.pgInc[3], "REG-01: channel 4 phase increment unchanged");
        }

        @Test
        void reg02PartIIWritesReachChannelFourThroughTheLatch() {
            Core core = Core.ym3438();
            core.write(1, 0xA4, 0x22);
            core.frames(1);
            assertEquals(0, core.st.fnum[3], "REG-02: the $A4 value is not applied on its own write");
            assertEquals(0, core.st.block[3], "REG-02: block not applied before the $A0 write");
            core.write(1, 0xA0, 0x39);
            core.frames(1);
            assertEquals(0x239, core.st.fnum[3], "REG-02: Part II $A4/$A0 -> channel 4 F-number $239");
            assertEquals(4, core.st.block[3], "REG-02: channel 4 block 4");
            assertEquals(0, core.st.fnum[0], "REG-02: channel 1 untouched by a Part II write");
        }

        @Test
        void reg03BlockLatchIsSharedAcrossTheChannelsOfAPart() {
            Core core = Core.ym3438();
            core.write(0, 0xA4, 0x22);
            core.frames(1);
            assertEquals(0, core.st.fnum[0], "REG-03: $A4 alone leaves channel 1 F-number unchanged");
            assertEquals(0, core.st.block[0], "REG-03: $A4 alone leaves channel 1 block unchanged");
            core.write(0, 0xA1, 0x00);
            core.frames(1);
            assertEquals(0x200, core.st.fnum[1], "REG-03: the held $A4 latch commits into channel 2 on its $A1 write");
            assertEquals(4, core.st.block[1], "REG-03: channel 2 block 4 from the shared latch");
            assertEquals(0, core.st.fnum[0], "REG-03: channel 1 still unchanged");
        }

        private Core instantVoicesOnEveryChannel() {
            Core core = Core.ym3438();
            for (int ch = 0; ch < 6; ch++) {
                core.voice(ch, 7, 0, ALL_OPS, 31, 0, 0, 0, 15);
                core.note(ch, 4, 0x439);
            }
            return core;
        }

        private void assertChannelKeyed(Core core, int ch, boolean keyed, String message) {
            for (int op = 1; op <= 4; op++) {
                assertEquals(keyed ? 0 : SILENT, core.st.egLevel[slot(ch, op)],
                        message + " (channel " + (ch + 1) + " op" + op + ")");
            }
        }

        @Test
        void reg04ChannelFieldThreeIsIgnored() {
            Core core = instantVoicesOnEveryChannel();
            core.write(0, 0x28, 0xF0);
            core.frames(4);
            assertChannelKeyed(core, 0, true, "REG-04: $F0 keys on all four operators of channel 1");
            core.write(0, 0x28, 0x03);
            core.frames(4);
            assertChannelKeyed(core, 0, true, "REG-04: channel field 3 is ignored, channel 1 stays keyed on");
            for (int ch = 1; ch < 6; ch++) {
                assertChannelKeyed(core, ch, false, "REG-04: no other channel changes");
            }
        }

        @Test
        void reg05ChannelFieldFiveSelectsChannelFive() {
            Core core = instantVoicesOnEveryChannel();
            core.write(0, 0x28, 0xF5);
            core.frames(4);
            assertChannelKeyed(core, 4, true, "REG-05 (corrected): $F5 -> bit 2 set, low bits 1 -> channel 5 (index 4)");
            for (int ch : new int[] { 0, 1, 2, 3, 5 }) {
                assertChannelKeyed(core, ch, false, "REG-05: only channel 5 is keyed on");
            }
        }

        @Test
        void reg06BusyFlagLastsThirtyTwoInternalCycles() {
            Core core = Core.ym2612();
            core.write(0, 0x22, 0x00);
            core.clock(64);
            core.chip.write(1, 0x00);
            core.clock(2);
            assertEquals(0x80, core.status() & 0x80, "REG-06: busy after a data write");
            int busyCycles = 0;
            while ((core.status() & 0x80) != 0) {
                busyCycles++;
                core.clock(1);
                assertTrue(busyCycles < 100, "REG-06: busy must clear");
            }
            assertEquals(32, busyCycles, "Group 1 latch: busy stays set for 32 internal cycles (1.33 samples)");
            Core again = Core.ym2612();
            again.write(0, 0x22, 0x00);
            again.clock(64);
            again.chip.write(1, 0x00);
            again.clock(2 * NukedOpn2.CYCLES_PER_FRAME);
            assertEquals(0, again.status() & 0x80, "REG-06: read again after 2 samples -> bit 7 = 0");
        }

        @Test
        void reg07GlobalRegistersAreIgnoredThroughPartII() {
            Core core = Core.ym3438();
            core.write(1, 0x22, 0x0F);
            core.frames(200);
            assertEquals(0, core.st.lfoEn, "REG-07: LFO enable written through Part II is ignored");
            assertEquals(0, core.st.lfoCnt, "REG-07: LFO counter never advances");
            core.write(0, 0x22, 0x0F);
            core.frames(200);
            assertNotEquals(0, core.st.lfoCnt, "REG-07 control: the same write through Part I enables the LFO");
        }

        @Test
        void keyOnBitsFollowAlgorithmOrder() {
            // Algorithm 4 carries op2 and op4 (doc Group 5); bit 5 = op2, bit 6 = op3 (doc $28 encoding).
            Core core = Core.ym3438();
            core.voice(0, 4, 0, ALL_OPS, 31, 0, 0, 0, 15);
            core.note(0, 2, 0x200);
            core.keyOn(0, 0x2);
            core.frames(4);
            assertEquals(0, core.st.egLevel[slot(0, 2)], "$28 bit 5 keys op2");
            assertEquals(SILENT, core.st.egLevel[slot(0, 3)], "$28 bit 5 leaves op3 off");
            assertTrue(anyNonZero(core.channelOut(0, 200)), "Group 5: op2 is a carrier in algorithm 4, so it is audible");
            core.keyOff(0);
            core.frames(400);
            core.keyOn(0, 0x4);
            core.frames(4);
            assertEquals(0, core.st.egLevel[slot(0, 3)], "$28 bit 6 keys op3");
            assertEquals(SILENT, core.st.egLevel[slot(0, 2)], "$28 bit 6 leaves op2 off");
            assertFalse(anyNonZero(core.channelOut(0, 200)),
                    "Group 5: op3 only modulates op4 in algorithm 4, so op3 alone is silent");
        }

        @Test
        void registerSlotOrderIsS1S3S2S4() {
            // In algorithm 4 the register slot +8 (S3) must be op2, a carrier, and +4 (S2) op3, a modulator.
            for (int regSlot : new int[] { 0x8, 0x4 }) {
                Core core = Core.ym3438();
                core.voice(0, 4, 0, new int[] { 127, 127, 127, 127 }, 31, 0, 0, 0, 15);
                core.write(0, 0x40 + regSlot, 0x00);
                core.note(0, 2, 0x200);
                core.keyOn(0, 0xF);
                core.frames(4);
                boolean audible = anyNonZero(core.channelOut(0, 300));
                if (regSlot == 0x8) {
                    assertTrue(audible, "Group 1 slot order: $48 (S3) is op2, a carrier in algorithm 4");
                } else {
                    assertFalse(audible, "Group 1 slot order: $44 (S2) is op3, a modulator in algorithm 4");
                }
            }
        }
    }

    @Nested
    class Group2EnvelopeGenerator {
        private Core voice(int ar, int d1r, int d2r, int d1l, int rr) {
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, ar, d1r, d2r, d1l, rr);
            core.note(0, 4, 0x439);
            return core;
        }

        @Test
        void eg01AttackRate31IsInstant() {
            Core core = voice(31, 0, 0, 0, 15);
            core.keyOn(0, 0xF);
            int[] levels = core.levels(0, 8);
            int first = firstIndexOf(levels, 0);
            assertTrue(first >= 0 && first <= 3,
                    "EG-01: attenuation is 0 on the first sample after key on (writes land at slot granularity, Group 11)");
            for (int i = first; i < levels.length; i++) {
                assertEquals(0, levels[i], "EG-01: already past attack with D1R 0, the level holds at 0");
            }
        }

        @Test
        void eg02RateScalingCannotSlowAnInstantAttack() {
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, 31, 0, 0, 0, 15);
            core.op(0, 1, 0x50, 0xDF);
            core.note(0, 7, 0x7FF);
            core.frames(1);
            assertEquals(31, core.st.kcode[0], "EG-02: block 7, F-number $7FF -> kc = 31");
            core.keyOn(0, 0xF);
            int[] levels = core.levels(0, 8);
            int first = firstIndexOf(levels, 0);
            assertTrue(first >= 0 && first <= 3, "EG-02: rate min(63, 62 + 31) still attacks instantly");
        }

        @Test
        void eg03DecayAtRate62AddsEightPerEgStepUntilSilence() {
            // D1L 15 ends decay-1 at $3E0 (doc, corrected), so D2R 31 continues the +8 ramp to silence.
            Core core = voice(31, 31, 31, 15, 15);
            core.keyOn(0, 0xF);
            int[] levels = core.levels(0, 420);
            int t0 = firstIndexOf(levels, 0);
            assertTrue(t0 >= 0 && t0 <= 3, "EG-03: instant attack first");
            List<int[]> runs = runs(java.util.Arrays.copyOfRange(levels, t0, levels.length));
            assertEquals(0, runs.get(0)[0]);
            assertTrue(runs.get(0)[1] >= 2 && runs.get(0)[1] <= 4,
                    "EG-03: the first decay step lands within one EG step (3 samples) of the attack ending");
            for (int i = 1; i <= 125; i++) {
                assertEquals(8 * i, runs.get(i)[0], "EG-03: +8 per EG step at rate 62 (step " + i + ")");
                assertEquals(3, runs.get(i)[1], "EG-03: one EG step is exactly 3 samples (step " + i + ")");
            }
            assertEquals(0x3F0, runs.get(126)[0], "EG-03 (corrected): the ramp's last stored value is $3F0 after 126 steps");
            assertEquals(1, runs.get(126)[1], "EG-03 (corrected): $3F0 is replaced by $3FF on the next sample");
            assertEquals(SILENT, runs.get(127)[0], "EG-03: then the EG reports $3FF (silence)");
            int tSilent = t0 + firstIndexOf(java.util.Arrays.copyOfRange(levels, t0, levels.length), SILENT);
            assertTrue(tSilent - t0 <= 384 && tSilent - t0 >= 376,
                    "EG-03: silence within 384 samples of key on (126 steps x 3 + 1 sample), was " + (tSilent - t0));
        }

        @Test
        void eg04DecayOneStopsAtSustainLevelThenDecayTwoTakesOver() {
            // RS 0 still adds kc >> 3, so rate 52 exactly needs kc < 8: block 1 (doc EG-04, corrected).
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, 31, 26, 0, 4, 15);
            core.note(0, 1, 0x300);
            core.keyOn(0, 0xF);
            int[] levels = core.levels(0, 600);
            int t0 = firstIndexOf(levels, 0);
            assertTrue(t0 >= 0 && t0 <= 3);
            List<int[]> runs = runs(java.util.Arrays.copyOfRange(levels, t0, levels.length));
            for (int i = 1; i <= 63; i++) {
                assertEquals(2 * i, runs.get(i)[0], "EG-04: +2 per EG step at rate 52 (step " + i + ")");
                assertEquals(3, runs.get(i)[1], "EG-04: 3 samples per EG step");
            }
            assertEquals(0x80, runs.get(64)[0], "EG-04: decay-1 stops at D1L << 5 = $080");
            assertTrue(runs.get(64)[1] >= 400, "EG-04: decay-2 at D2R 0 holds the level (no change)");
            int t128 = t0 + firstIndexOf(java.util.Arrays.copyOfRange(levels, t0, levels.length), 0x80);
            assertTrue(t128 - t0 >= 190 && t128 - t0 <= 192,
                    "EG-04: 64 EG steps = 192 samples to reach $080, was " + (t128 - t0));
        }

        @Test
        void eg05DecayRateZeroNeverChangesTheLevel() {
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, 31, 0, 0, 15, 15);
            core.op(0, 1, 0x50, 0xDF);
            core.note(0, 7, 0x7FF);
            core.keyOn(0, 0xF);
            core.frames(4);
            assertEquals(0, core.st.egLevel[0]);
            for (int level : core.levels(0, 1000)) {
                assertEquals(0, level, "EG-05: D1R 0 means no change regardless of RS / kc");
            }
        }

        @Test
        void eg06ReleaseAtRate62ReachesSilenceWithin384Samples() {
            Core core = voice(31, 0, 0, 0, 15);
            core.keyOn(0, 0xF);
            core.frames(6);
            assertEquals(0, core.st.egLevel[0]);
            core.keyOff(0);
            int[] levels = core.levels(0, 420);
            List<int[]> runs = runs(levels);
            assertEquals(0, runs.get(0)[0], "EG-06: release starts from att 0");
            for (int i = 1; i <= 125; i++) {
                assertEquals(8 * i, runs.get(i)[0], "EG-06: RR 15 -> rate 62 -> +8 per EG step");
                assertEquals(3, runs.get(i)[1], "EG-06: 3 samples per EG step");
            }
            int tSilent = firstIndexOf(levels, SILENT);
            assertTrue(tSilent > 0 && tSilent <= 384, "EG-06: silence within 384 samples of key off, was " + tSilent);
        }

        private int incrementsIn(int[] levels) {
            int count = 0;
            for (int i = 1; i < levels.length; i++) {
                if (levels[i] != levels[i - 1]) {
                    count++;
                }
            }
            return count;
        }

        @Test
        void eg07KeyCodeAndRateScalingFormRate44() {
            Core core = voice(31, 20, 0, 15, 15);
            core.op(0, 1, 0x50, 0x40 | 31);
            core.frames(1);
            assertEquals(18, core.st.kcode[0], "EG-07: F-number $439 block 4 -> kc = (4 << 2) | (N4 = 1) << 1 | N3 = 0 -> 18");
            core.keyOn(0, 0xF);
            core.frames(6);
            int[] rs1 = core.levels(0, 600);
            int steps = incrementsIn(rs1);
            assertTrue(steps >= 99 && steps <= 101,
                    "EG-07: RS 1 -> ks = 18 >> 2 = 4, rate 44 -> +1 every 2 EG steps (6 samples): 100 per 600, was " + steps);

            Core control = voice(31, 20, 0, 15, 15);
            control.op(0, 1, 0x50, 31);
            control.frames(1);
            control.keyOn(0, 0xF);
            control.frames(6);
            int rs0 = incrementsIn(control.levels(0, 600));
            assertTrue(rs0 >= 74 && rs0 <= 76,
                    "EG-07 / rate table row 2: RS 0 -> ks = 18 >> 3 = 2, rate 42 -> 6 increments per 8 EG steps: 75 per 600, was " + rs0);
        }

        @Test
        void eg08KeyOnWhileKeyedOnDoesNotRetrigger() {
            Core core = voice(20, 0, 0, 0, 15);
            Core control = voice(20, 0, 0, 0, 15);
            core.keyOn(0, 0xF);
            control.keyOn(0, 0xF);
            core.frames(10);
            control.frames(10);
            core.keyOn(0, 0xF);
            control.nop();
            assertArrayEquals(control.levels(0, 600), core.levels(0, 600),
                    "EG-08: a second key on 10 samples into the attack has no effect on the envelope");
            assertEquals(control.st.pgPhase[0], core.st.pgPhase[0], "EG-08: the phase accumulator is not reset either");
        }

        @Test
        void eg09KeyOffDuringAttackReleasesFromThereAndKeyOnResumesFromTheRelease() {
            Core core = voice(20, 0, 0, 0, 12);
            core.keyOn(0, 0xF);
            int[] attack = core.levels(0, 4000);
            int t = firstIndexAtMost(attack, 0x100);
            assertTrue(t > 0, "EG-09: attack at rate 42 passes $100");
            int atKeyOff = core.st.egLevel[0];
            core.keyOff(0);
            int[] release = core.levels(0, 30);
            List<int[]> runs = runs(release);
            assertEquals(atKeyOff, runs.get(0)[0], "EG-09: release starts from the attenuation the attack had reached");
            assertEquals(atKeyOff + 2, runs.get(1)[0], "EG-09: RR 12 -> rate 52 -> +2 per EG step from there");
            int atKeyOn = core.st.egLevel[0];
            core.keyOn(0, 0xF);
            int[] resumed = core.levels(0, 600);
            for (int level : resumed) {
                assertTrue(level <= atKeyOn, "EG-09: key on during release resumes attack from the release level, never from $3FF");
            }
            assertTrue(min(resumed) < atKeyOn, "EG-09: the resumed attack keeps falling");
        }

        @Test
        void attackAtAMidRateUpdatesEvery12SamplesUntilZero() {
            // Rate 40 (AR 20, RS 0, kc 4): shift 1, row 0 -> one update per 4 EG steps = 12 samples.
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, 20, 0, 0, 0, 15);
            core.note(0, 1, 0x300);
            core.frames(1);
            assertEquals(4, core.st.kcode[0]);
            core.keyOn(0, 0xF);
            int[] levels = core.levels(0, 4000);
            int end = firstIndexOf(levels, 0);
            assertTrue(end > 0, "Group 2 attack: reaches 0");
            List<int[]> runs = runs(java.util.Arrays.copyOfRange(levels, 0, end));
            assertEquals(SILENT, runs.get(0)[0]);
            for (int i = 1; i < runs.size(); i++) {
                assertTrue(runs.get(i)[0] < runs.get(i - 1)[0], "Group 2 attack: attenuation falls monotonically");
                assertEquals(12, runs.get(i)[1], "Group 2 rate table: rate 40 updates every 12 samples (step " + i + ")");
            }
        }

        @Test
        void attackRateZeroNeverLeavesSilence() {
            Core core = voice(0, 31, 31, 0, 15);
            core.keyOn(0, 0xF);
            for (int level : core.levels(0, 2000)) {
                assertEquals(SILENT, level, "Group 2 / RST-02: attack rate 0 = no change, the operator stays at $3FF");
            }
        }
    }

    @Nested
    class Group3TotalLevelToOutput {
        /** One carrier stepping one sine index per sample (fnum $200, block 2: inc 1024). */
        private Core sweep(int tl) {
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, 31, 0, 0, 0, 15);
            core.op(0, 1, 0x40, tl);
            core.note(0, 2, 0x200);
            core.keyOn(0, 0xF);
            core.frames(8);
            assertEquals(1024, core.st.pgInc[0], "Group 4: fnum $200 block 2 -> inc0 = 1024 (one sine index per sample)");
            return core;
        }

        private void assertCycleMatchesDoc(Core core, int att, String vector) {
            int previousIndex = core.st.pgPhase[0] >> 10;
            for (int n = 0; n < 1030; n++) {
                core.frame();
                // fm_out for the slot is produced in the same cycle its phase steps, from the pre-step phase.
                assertEquals(docOperatorOutput(previousIndex, att), core.st.fmOut[0],
                        vector + ": Group 3 log-sin + exp pipeline at phase index " + previousIndex + ", att " + att);
                previousIndex = core.st.pgPhase[0] >> 10;
            }
        }

        @Test
        void tl01PeakOperatorOutputIs8168() {
            assertEquals(0x859, docLogSin(0), "Group 3: logsin[0] = $859");
            assertEquals(0, docLogSin(255), "Group 3: logsin[255] = 0");
            assertEquals(0, docExp(0), "Group 3: exp[0] = 0");
            assertEquals(1018, docExp(255), "Group 3: exp[255] = 1018");
            assertEquals(8168, docOperatorOutput(255, 0), "TL-01: ((1018 | 1024) << 2) >> 0 = 8168");
            Core core = sweep(0);
            assertCycleMatchesDoc(core, 0, "TL-01");
            int peak = Integer.MIN_VALUE;
            for (int n = 0; n < 1024; n++) {
                core.frame();
                peak = Math.max(peak, core.st.fmOut[0]);
            }
            assertEquals(8168, peak, "TL-01: operator peak is +8168, not 8191");
        }

        @Test
        void tl02SixDecibelsHalvesTheOutput() {
            assertEquals(4084, docOperatorOutput(255, 8 << 3), "TL-02: TL 8 -> level 256 -> 8168 >> 1");
            assertCycleMatchesDoc(sweep(8), 8 << 3, "TL-02");
        }

        @Test
        void tl03TotalLevel127Silences() {
            Core core = sweep(127);
            for (int n = 0; n < 1024; n++) {
                core.frame();
                assertEquals(0, core.st.fmOut[0], "TL-03: TL 127 -> level 4064 -> shift 15 -> 0");
            }
        }

        @Test
        void tl04PhaseZeroAndPhase512DifferOnlyInSign() {
            assertEquals(25, docOperatorOutput(0, 0), "TL-04: level $859 -> shift 8, mantissa index $A6 -> 25");
            assertEquals(-25, docOperatorOutput(512, 0), "TL-04: phase 512 is the same magnitude, negative");
            Core core = sweep(0);
            int previousIndex = core.st.pgPhase[0] >> 10;
            boolean sawZero = false;
            boolean sawHalf = false;
            for (int n = 0; n < 1030; n++) {
                core.frame();
                if (previousIndex == 0) {
                    assertEquals(25, core.st.fmOut[0], "TL-04: output at phase 0");
                    sawZero = true;
                } else if (previousIndex == 512) {
                    assertEquals(-25, core.st.fmOut[0], "TL-04: output at phase 512");
                    sawHalf = true;
                }
                previousIndex = core.st.pgPhase[0] >> 10;
            }
            assertTrue(sawZero && sawHalf);
        }

        @Test
        void tl05FourCarriersAtPeakClampTheChannel() {
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ALL_OPS, 31, 0, 0, 0, 15);
            core.note(0, 2, 0x200);
            core.keyOn(0, 0xF);
            core.frames(8);
            int[] out = core.channelOut(0, 1024);
            assertEquals(255, max(out), "TL-05 / DAC-04 (corrected): four carriers in phase clamp at the 9-bit +255");
            assertEquals(-256, min(out), "TL-05 / DAC-04: and at -256");
        }
    }

    @Nested
    class Group4PhaseIncrement {
        private Core tone(int block, int fnum, int dt, int mul) {
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, 31, 0, 0, 0, 15);
            core.op(0, 1, 0x30, (dt << 4) | mul);
            core.note(0, block, fnum);
            core.frames(2);
            return core;
        }

        private double hertz(int inc) {
            return inc * FS_NTSC / (1 << 20);
        }

        @Test
        void pg01A4Is8648Or439Hertz() {
            assertEquals(8648, docInc0(0x439, 4), "PG-01: ($439 << 4) >> 1 = 8648");
            assertEquals(8648, tone(4, 0x439, 0, 1).st.pgInc[0], "PG-01: DT 0, MUL 1 -> inc 8648");
            assertEquals(439.3, hertz(8648), 0.05, "PG-01: 8648 x 53267.04 / 2^20 = 439.3 Hz (Sega's A4)");
        }

        @Test
        void pg02MultipleZeroHalves() {
            assertEquals(4324, tone(4, 0x439, 0, 0).st.pgInc[0], "PG-02: MUL 0 = x0.5 -> 4324");
            assertEquals(219.7, hertz(4324), 0.05, "PG-02: 219.7 Hz");
        }

        @Test
        void pg03DetuneAppliesBeforeTheMultiple() {
            assertEquals(17302, tone(4, 0x439, 1, 2).st.pgInc[0], "PG-03: (8648 + 3) x 2 = 17302");
            assertEquals(878.9, hertz(17302), 0.05, "PG-03 (corrected): 878.9 Hz");
        }

        @Test
        void pg04NegativeDetuneSubtracts() {
            assertEquals(8645, tone(4, 0x439, 5, 1).st.pgInc[0], "PG-04: DT 5 = -1 x table -> 8648 - 3");
            assertEquals(439.2, hertz(8645), 0.05, "PG-04: 439.2 Hz, a 0.15 Hz beat against PG-01");
        }

        @Test
        void pg05BlockZeroLosesTheFnumLsb() {
            Core core = tone(0, 0x001, 0, 1);
            assertEquals(0, core.st.pgInc[0], "PG-05: inc0 = ($001 << 0) >> 1 = 0");
            core.keyOn(0, 0xF);
            core.frames(8);
            int phase = core.st.pgPhase[0];
            core.frames(100);
            assertEquals(phase, core.st.pgPhase[0], "PG-05: the operator phase never advances");
        }

        @Test
        void pg06TopOfRangeWrapsIn20Bits() {
            assertEquals(131008, docInc0(0x7FF, 7), "PG-06 (corrected): ($7FF << 7) >> 1 = 131,008");
            assertEquals(916874, ((131008 + 22) * 15) & 0xfffff, "PG-06 (corrected): (131,008 + 22) x 15 mod 2^20");
            assertEquals(916874, tone(7, 0x7FF, 3, 15).st.pgInc[0], "PG-06: kc 31 -> detune 22, MUL 15, 20-bit wrap");
        }

        @Test
        void manualDetuneTableForEveryKeyCode() {
            int[][] table = {
                { 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 8, 8, 8 },
                { 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 10, 11, 12, 13, 14, 16, 16, 16, 16 },
                { 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 8, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 20, 22, 22, 22, 22 },
            };
            int[] fnumForNote = { 0x300, 0x380, 0x400, 0x480 };
            for (int kc = 0; kc < 32; kc++) {
                int block = kc >> 2;
                int fnum = fnumForNote[kc & 3];
                Core core = Core.ym3438();
                core.voice(0, 7, 0, ONE_OP, 31, 0, 0, 0, 15);
                core.note(0, block, fnum);
                core.frames(2);
                assertEquals(kc, core.st.kcode[0], "Group 2 rate formula: key code from block and F-number bits 10-8");
                int inc0 = docInc0(fnum, block);
                for (int dt = 0; dt < 8; dt++) {
                    core.op(0, 1, 0x30, (dt << 4) | 1);
                    core.frames(2);
                    int expected = switch (dt) {
                        case 0, 4 -> inc0;
                        case 1, 2, 3 -> inc0 + table[dt - 1][kc];
                        default -> inc0 - table[dt - 5][kc];
                    };
                    assertEquals(expected, core.st.pgInc[0],
                            "Group 4 detune table (DT1 row kc 12-15 corrected): kc " + kc + " DT " + dt);
                }
            }
        }
    }

    @Nested
    class Group5AlgorithmsAndFeedback {
        private static final int[][] CARRIERS = {
            { 4 }, { 4 }, { 4 }, { 4 }, { 2, 4 }, { 2, 3, 4 }, { 2, 3, 4 }, { 1, 2, 3, 4 },
        };

        @Test
        void carrierSetsOfAllEightAlgorithms() {
            for (int alg = 0; alg < 8; alg++) {
                for (int op = 1; op <= 4; op++) {
                    int[] tl = { 127, 127, 127, 127 };
                    tl[op - 1] = 0;
                    Core core = Core.ym3438();
                    core.voice(0, alg, 0, tl, 31, 0, 0, 0, 15);
                    core.note(0, 2, 0x200);
                    core.keyOn(0, 0xF);
                    core.frames(4);
                    boolean carrier = false;
                    for (int c : CARRIERS[alg]) {
                        carrier |= c == op;
                    }
                    assertEquals(carrier, anyNonZero(core.channelOut(0, 300)),
                            "Group 5 topology table: algorithm " + alg + " op" + op + (carrier ? " is a carrier" : " only modulates"));
                }
            }
        }

        @Test
        void alg02SilentModulatorsLeaveAPureCarrier() {
            Core chain = Core.ym3438();
            chain.voice(0, 0, 0, new int[] { 127, 127, 127, 0 }, 31, 0, 0, 0, 15);
            chain.note(0, 2, 0x200);
            chain.keyOn(0, 0xF);
            Core alone = Core.ym3438();
            alone.voice(0, 7, 0, new int[] { 127, 127, 127, 0 }, 31, 0, 0, 0, 15);
            alone.note(0, 2, 0x200);
            alone.keyOn(0, 0xF);
            assertArrayEquals(alone.channelOut(0, 1100), chain.channelOut(0, 1100),
                    "ALG-02: algorithm 0 with silent op1-op3 equals op4 on its own");
            assertTrue(anyNonZero(alone.channelOut(0, 100)));
        }

        @Test
        void alg03TwoCarriersSum() {
            Core both = Core.ym3438();
            both.voice(0, 4, 0, new int[] { 127, 0, 127, 0 }, 31, 0, 0, 0, 15);
            both.note(0, 2, 0x200);
            both.keyOn(0, 0xF);
            Core single = Core.ym3438();
            single.voice(0, 7, 0, new int[] { 127, 127, 127, 0 }, 31, 0, 0, 0, 15);
            single.note(0, 2, 0x200);
            single.keyOn(0, 0xF);
            int[] sum = both.channelOut(0, 1100);
            int[] one = single.channelOut(0, 1100);
            for (int n = 0; n < sum.length; n++) {
                assertEquals(Math.max(-256, Math.min(255, 2 * one[n])), sum[n],
                        "ALG-03 (clamp corrected to the 9-bit channel value): two in-phase carriers sum, clamped");
            }
            assertEquals(255, max(sum), "ALG-03: the sum reaches the clamp");
        }

        @Test
        void alg04FeedbackIsTheTwoPreviousOutputsShiftedBy10MinusFb() {
            for (int fb = 0; fb <= 7; fb++) {
                Core core = Core.ym3438();
                core.voice(0, 7, fb, ONE_OP, 31, 0, 0, 0, 15);
                core.note(0, 2, 0x200);
                core.keyOn(0, 0xF);
                core.frames(8);
                int previous = core.st.fmOut[0];
                int peakMod = 0;
                for (int n = 0; n < 2048; n++) {
                    core.frame();
                    int current = core.st.fmOut[0];
                    int expected = fb == 0 ? 0 : (current + previous) >> (10 - fb);
                    assertEquals(expected & 0xffff, core.st.fmMod[0],
                            "Group 5 feedback: (out_prev + out_prev2) >> (10 - FB), FB " + fb + " (16-bit field)");
                    peakMod = Math.max(peakMod, Math.abs((short) core.st.fmMod[0]));
                    previous = current;
                }
                if (fb == 7) {
                    assertTrue(peakMod <= 2042 && peakMod > 1024,
                            "ALG-04: FB 7 peak phase deviation approaches 2042 units (4 pi), was " + peakMod);
                }
            }
        }

        @Test
        void alg04NoFeedbackIsAPureSine() {
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, 31, 0, 0, 0, 15);
            core.note(0, 2, 0x200);
            core.keyOn(0, 0xF);
            core.frames(8);
            int[] out = new int[1536];
            for (int n = 0; n < out.length; n++) {
                core.frame();
                out[n] = core.st.fmOut[0];
            }
            for (int n = 0; n + 512 < out.length; n++) {
                assertEquals(-out[n], out[n + 512], "ALG-04: FB 0 is a pure sine, antisymmetric over half a period");
            }
        }
    }

    @Nested
    class Group6Lfo {
        /** Corrected in the doc: Nuked's die model steps the LFO every lfo_cycles samples exactly. */
        private static final int[] PERIOD = { 108, 77, 71, 67, 62, 44, 8, 5 };

        @Test
        void lfo01And02PositionAdvancesEveryPeriodSamples() {
            for (int f = 0; f < 8; f++) {
                Core core = Core.ym3438();
                core.write(0, 0x22, 0x08 | f);
                core.frames(PERIOD[f] * 2);
                int previous = core.st.lfoCnt;
                int lastStep = -1;
                int steps = 0;
                int wraps = 0;
                int frames = PERIOD[f] * 128 * 2 + 10;
                for (int n = 0; n < frames; n++) {
                    core.frame();
                    if (core.st.lfoCnt != previous) {
                        if (core.st.lfoCnt == 0) {
                            wraps++;
                            assertEquals(127, previous, "Group 6: 128 positions per LFO cycle");
                        } else {
                            assertEquals((previous + 1) & 0x7f, core.st.lfoCnt);
                        }
                        if (lastStep >= 0) {
                            assertEquals(PERIOD[f], n - lastStep,
                                    "LFO-01/02 (corrected): $22 select " + f + " steps every " + PERIOD[f] + " samples");
                        }
                        lastStep = n;
                        steps++;
                        previous = core.st.lfoCnt;
                    }
                }
                assertTrue(steps >= 256, "LFO: at least two cycles observed for select " + f);
                assertTrue(wraps >= 1);
            }
            assertEquals(3.85, FS_NTSC / (128 * 108), 0.01, "LFO-01 (corrected): 3.85 Hz at NTSC Fs");
            assertEquals(83.2, FS_NTSC / (128 * 5), 0.1, "LFO-02 (corrected): 83.2 Hz at NTSC Fs");
        }

        private Core amVoice(int ams, int select) {
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, 31, 0, 0, 0, 15);
            core.op(0, 1, 0x60, 0x80);
            core.channel(0, 0xB4, 0xC0 | (ams << 4));
            core.note(0, 4, 0x439);
            core.write(0, 0x22, 0x08 | select);
            core.keyOn(0, 0xF);
            core.frames(8);
            assertEquals(0, core.st.egLevel[0]);
            return core;
        }

        @Test
        void lfo03AmDepthAtAms3Is126StepsOver1024Samples() {
            int[] att = amVoice(3, 6).egOut(0, 2048 + 128);
            assertEquals(126, max(att), "LFO-03: AMS 3 peak attenuation 126 EG steps = 11.8 dB");
            assertEquals(0, min(att), "LFO-03: swings back to 0 dB");
            for (int n = 0; n + 1024 < att.length; n++) {
                assertEquals(att[n], att[n + 1024], "LFO-03 (corrected): one AM cycle per 8 x 128 = 1024 samples");
            }
        }

        @Test
        void lfo04AmDepthAtAms1Is15Steps() {
            int[] att = amVoice(1, 6).egOut(0, 1100);
            assertEquals(15, max(att), "LFO-04: AMS 1 depth 15 EG steps = 1.4 dB");
            assertEquals(0, min(att));
            assertEquals(63, max(amVoice(2, 6).egOut(0, 1100)), "Group 6 AM table: AMS 2 = 63 steps = 5.9 dB");
            assertEquals(0, max(amVoice(0, 6).egOut(0, 1100)), "Group 6 AM table: AMS 0 = off");
        }

        private Core pmVoice(int lfoRegister) {
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, 31, 0, 0, 0, 15);
            core.channel(0, 0xB4, 0xC7);
            core.note(0, 4, 0x400);
            core.write(0, 0x22, lfoRegister);
            core.frames(8);
            return core;
        }

        @Test
        void lfo05PmAtPms7SwingsTheFnumBy48() {
            Core core = pmVoice(0x0F);
            int[] inc = new int[640 * 2 + 16];
            for (int n = 0; n < inc.length; n++) {
                core.frame();
                inc[n] = core.st.pgInc[0];
            }
            assertEquals(docInc0(0x400 + 48, 4), max(inc), "LFO-05: F-number $400 + 48");
            assertEquals(docInc0(0x400 - 48, 4), min(inc), "LFO-05: F-number $400 - 48 (4.7 %, about 80 cents)");
            for (int n = 0; n + 640 < inc.length; n++) {
                assertEquals(inc[n], inc[n + 640], "LFO-05 (corrected): 5 x 128 = 640 samples per PM cycle at select 7");
            }
        }

        @Test
        void lfo06DisabledLfoHoldsPositionZeroWithNoPitchModulation() {
            Core core = pmVoice(0x00);
            for (int n = 0; n < 2000; n++) {
                core.frame();
                assertEquals(0, core.st.lfoCnt, "LFO-06: the counter holds at 0 while disabled");
                assertEquals(docInc0(0x400, 4), core.st.pgInc[0], "LFO-06: PM at position 0 is 0");
            }
        }
    }

    @Nested
    class Group7SsgEg {
        private Core ssgVoice(int ssg, int d1r, int block, int fnum) {
            Core core = Core.ym3438();
            core.voice(0, 7, 0, ONE_OP, 31, d1r, 0, 15, 15);
            core.op(0, 1, 0x90, ssg);
            core.note(0, block, fnum);
            core.keyOn(0, 0xF);
            return core;
        }

        @Test
        void ssg01Mode8RepeatsA48SampleSawtoothAndResetsThePhase() {
            Core core = ssgVoice(8, 31, 2, 0x200);
            core.frames(8);
            int[] levels = new int[400];
            int[] phase = new int[400];
            for (int n = 0; n < levels.length; n++) {
                core.frame();
                levels[n] = core.st.egLevel[0];
                phase[n] = core.st.pgPhase[0];
            }
            for (int n = 0; n + 48 < levels.length; n++) {
                assertEquals(levels[n], levels[n + 48], "SSG-01 (corrected): 16 EG steps x 3 = 48 samples per repeat");
            }
            List<int[]> runs = runs(levels);
            int restarts = 0;
            for (int i = 1; i < runs.size(); i++) {
                int value = runs.get(i)[0];
                if (value == 0) {
                    restarts++;
                    assertEquals(0x200, runs.get(i - 1)[0], "SSG-01: the restart follows attenuation $200");
                } else if (value != 0x200) {
                    assertEquals(32, value - runs.get(i - 1)[0], "SSG-01: +8 x 4 = +32 per EG step with SSG-EG on");
                    assertEquals(3, runs.get(i)[1]);
                }
            }
            assertTrue(restarts >= 7);
            for (int n = 1; n < levels.length; n++) {
                if (levels[n] == 0 && levels[n - 1] == 0x200) {
                    assertTrue(phase[n] < phase[n - 1], "SSG-01: the phase accumulator is reset at the restart");
                }
            }
        }

        @Test
        void ssg02Mode9DecaysOnceThenHoldsSilent() {
            Core core = ssgVoice(9, 31, 4, 0x439);
            int[] att = core.egOut(0, 600);
            int start = firstIndexOf(att, 0);
            assertTrue(start >= 0 && start <= 3, "SSG-02: mode 9 starts loud");
            int t = start + firstIndexOf(java.util.Arrays.copyOfRange(att, start, att.length), SILENT);
            assertTrue(t - start > 0 && t - start <= 48 + 4, "SSG-02 (corrected): silent after one 48-sample ramp, was " + (t - start));
            assertTrue(att[t - 1] < SILENT);
            for (int n = t; n < att.length; n++) {
                assertEquals(SILENT, att[n], "SSG-02: held at >= $200, reported as $3FF until key off");
            }
        }

        @Test
        void ssg03Mode10AlternatesA96SampleTriangle() {
            Core core = ssgVoice(10, 31, 4, 0x439);
            core.frames(8);
            int[] att = core.egOut(0, 96 * 4 + 8);
            for (int n = 0; n + 96 < att.length; n++) {
                assertEquals(att[n], att[n + 96], "SSG-03 (corrected): two 48-sample ramps per triangle period");
            }
            assertEquals(0, min(att), "SSG-03: returns to 0 dB every period");
            assertTrue(max(att) >= 480 && max(att) <= 512, "SSG-03: fades to the $200 end threshold");
        }

        @Test
        void ssg04Mode11DecaysOnceThenHoldsLoud() {
            Core core = ssgVoice(11, 31, 4, 0x439);
            int[] att = core.egOut(0, 600);
            assertTrue(max(java.util.Arrays.copyOfRange(att, 0, 60)) >= 480, "SSG-04: the first ramp fades out");
            for (int n = 60; n < att.length; n++) {
                assertEquals(0, att[n], "SSG-04: after the ramp the inversion toggles and holds at 0 dB");
            }
        }

        @Test
        void ssg05Mode15RisesOnceThenHoldsSilentWhileMode9StartsLoud() {
            int[] rise = ssgVoice(15, 31, 4, 0x439).egOut(0, 200);
            int start = firstIndexAtMost(rise, 512);
            assertTrue(start >= 0 && start <= 3, "SSG-05: mode 15 starts at the $200 end level (inverted attack)");
            assertTrue(rise[start] >= 480, "SSG-05: mode 15 starts silent");
            int loud = firstIndexAtMost(rise, 32);
            assertTrue(loud > start && loud <= start + 48,
                    "SSG-05 (corrected): rises once to within one SSG step of 0 dB before the inversion clears at $200");
            assertEquals(SILENT, rise[199], "SSG-05: then holds silent");
            int[] fall = ssgVoice(9, 31, 4, 0x439).egOut(0, 200);
            assertEquals(0, fall[3], "SSG-05: mode 9 starts loud");
            assertEquals(SILENT, fall[199], "SSG-05: decays once, holds silent");
        }

        @Test
        void ssg06SsgDecayRunsFourTimesFaster() {
            Core plain = ssgVoice(0, 20, 0, 0x200);
            Core ssg = ssgVoice(8, 20, 0, 0x200);
            assertEquals(0, plain.st.kcode[0]);
            int tPlain = firstIndexAtLeast(plain.levels(0, 8000), 0x200);
            int tSsg = firstIndexAtLeast(ssg.levels(0, 8000), 0x200);
            assertTrue(tPlain > 0 && tSsg > 0);
            assertTrue(Math.abs(4 * tSsg - tPlain) <= 36,
                    "SSG-06 (corrected: increment x4): 0 -> $200 four times faster at rate 40, within one 12-sample update; "
                            + tPlain + " vs " + tSsg);
        }
    }

    @Nested
    class Group8TimersAndCsm {
        private int[] overflowFrames(Core core, int frames) {
            List<Integer> hits = new ArrayList<>();
            for (int n = 0; n < frames; n++) {
                core.frame();
                if (core.overflowA) {
                    hits.add(n);
                }
            }
            return hits.stream().mapToInt(Integer::intValue).toArray();
        }

        @Test
        void tmr01TimerA1023OverflowsEverySample() {
            Core core = Core.ym2612();
            core.write(0, 0x24, 0xFF);
            core.write(0, 0x25, 0x03);
            core.write(0, 0x27, 0x05);
            int[] hits = overflowFrames(core, 60);
            assertTrue(hits.length >= 55, "TMR-01: (1024 - 1023) = 1 sample period");
            for (int i = 1; i < hits.length; i++) {
                assertEquals(1, hits[i] - hits[i - 1], "TMR-01: overflow every sample");
            }
            assertEquals(1, core.status() & 0x01, "TMR-01: status bit 0 set and staying set until reset");
        }

        private Core timerA992(int control) {
            Core core = Core.ym2612();
            core.write(0, 0x24, 992 >> 2);
            core.write(0, 0x25, 992 & 3);
            core.write(0, 0x27, control);
            return core;
        }

        @Test
        void tmr02TimerA992OverflowsEvery32Samples() {
            Core core = timerA992(0x05);
            int first = -1;
            for (int n = 0; n < 40; n++) {
                core.frame();
                if ((core.status() & 1) != 0) {
                    first = n;
                    break;
                }
            }
            assertTrue(first >= 30 && first <= 32, "TMR-02: status bit 0 first set 32 samples after load, was " + first);
            int[] hits = overflowFrames(core, 200);
            for (int i = 1; i < hits.length; i++) {
                assertEquals(32, hits[i] - hits[i - 1], "TMR-02: (1024 - 992) = 32 samples between overflows");
            }
            assertEquals(0.6, 32 / FS_NTSC * 1000, 0.01, "TMR-02: 600 us at NTSC");
        }

        @Test
        void tmr03TimerB192OverflowsEvery1024Samples() {
            Core core = Core.ym2612();
            core.write(0, 0x26, 0xC0);
            core.write(0, 0x27, 0x0A);
            int first = -1;
            List<Integer> hits = new ArrayList<>();
            for (int n = 0; n < 1024 * 3 + 40; n++) {
                core.frame();
                if (first < 0 && (core.status() & 2) != 0) {
                    first = n;
                }
                if (core.overflowB) {
                    hits.add(n);
                }
            }
            assertTrue(first > 1024 - 17 && first <= 1024,
                    "TMR-03: (256 - 192) x 16 = 1024 samples, less the free-running 16-sample prescaler phase; was " + first);
            assertTrue(hits.size() >= 3, "TMR-03: three overflows in 3 x 1024 + 40 samples, saw " + hits.size());
            for (int i = 1; i < hits.size(); i++) {
                assertEquals(1024, hits.get(i) - hits.get(i - 1), "TMR-03: 1024 samples between timer B overflows");
            }
            assertEquals(19.2, 1024 / FS_NTSC * 1000, 0.05, "TMR-03: 19.2 ms at NTSC");
        }

        @Test
        void tmr04ResetClearsTheFlagAndTheTimerKeepsRunning() {
            Core core = timerA992(0x05);
            core.frames(40);
            assertEquals(1, core.status() & 1, "TMR-04: flag set by the first overflow");
            core.write(0, 0x27, 0x15);
            assertEquals(0, core.status() & 1, "TMR-04: reset bit clears status bit 0 immediately");
            int[] hits = overflowFrames(core, 100);
            assertTrue(hits.length >= 2, "TMR-04: overflows keep coming after the reset, saw " + hits.length);
            for (int i = 1; i < hits.length; i++) {
                assertEquals(32, hits[i] - hits[i - 1], "TMR-04: the timer keeps its 32-sample period through the reset");
            }
            assertEquals(1, core.status() & 1, "TMR-04: set again at the next overflow");
        }

        @Test
        void tmr05DisabledFlagNeverSetsWhileTheTimerRuns() {
            Core core = timerA992(0x01);
            List<Integer> hits = new ArrayList<>();
            for (int n = 0; n < 200; n++) {
                core.frame();
                assertEquals(0, core.status() & 1, "TMR-05: enable A = 0 -> status bit 0 never becomes 1");
                if (core.overflowA) {
                    hits.add(n);
                }
            }
            assertTrue(hits.size() >= 5, "TMR-05: the timer still runs and reloads");
            for (int i = 1; i < hits.size(); i++) {
                assertEquals(32, hits.get(i) - hits.get(i - 1));
            }
        }

        @Test
        void tmr06CsmRetriggersChannelThreeAtTheTimerARate() {
            Core core = Core.ym2612();
            core.voice(2, 7, 0, ALL_OPS, 31, 0, 0, 0, 15);
            core.note(2, 4, 0x439);
            core.keyOff(2);
            core.write(0, 0x24, 992 >> 2);
            core.write(0, 0x25, 992 & 3);
            core.write(0, 0x27, 0x85);
            List<Integer> restarts = new ArrayList<>();
            int previous = core.st.egLevel[slot(2, 1)];
            for (int n = 0; n < 400; n++) {
                core.frame();
                int level = core.st.egLevel[slot(2, 1)];
                if (level < previous) {
                    restarts.add(n);
                }
                previous = level;
            }
            assertTrue(restarts.size() >= 10, "TMR-06: channel 3 restarts its envelope without any $28 write");
            for (int i = 1; i < restarts.size(); i++) {
                assertEquals(32, restarts.get(i) - restarts.get(i - 1), "TMR-06: every 32 samples");
            }
            assertEquals(1, core.status() & 1, "TMR-06: timer A's flag runs alongside CSM");
            for (int ch : new int[] { 0, 1, 3, 4, 5 }) {
                assertEquals(SILENT, core.st.egLevel[slot(ch, 1)], "TMR-06: CSM only touches channel 3");
            }
        }
    }

    @Nested
    class Group9DacAndOutput {
        private Core dac(int chipType, int data) {
            Core core = new Core(chipType);
            core.write(1, 0xB6, 0xC0);
            core.write(0, 0x2B, 0x80);
            core.write(0, 0x2A, data);
            core.frames(2);
            return core;
        }

        private int nineBit(int dacdata) {
            return (dacdata & 0xff) - (dacdata & 0x100);
        }

        @Test
        void dac01CentreContributesNothing() {
            Core core = dac(0, 0x80);
            assertEquals(0, nineBit(core.st.dacdata), "DAC-01: $80 is the 9-bit centre");
            for (int n = 0; n < 10; n++) {
                int[] pins = core.frame();
                assertEquals(0, pins[0], "DAC-01: channel 6 contributes 0 to the left output");
                assertEquals(0, pins[1], "DAC-01: and 0 to the right output");
            }
        }

        @Test
        void dac02And03ExtremesAre254AndMinus256() {
            Core top = dac(0, 0xFF);
            Core bottom = dac(0, 0x00);
            assertEquals(254, nineBit(top.st.dacdata), "DAC-02: ($FF - $80) << 1 = +254");
            assertEquals(-256, nineBit(bottom.st.dacdata), "DAC-03: -256");
            int[] high = top.frame();
            int[] low = bottom.frame();
            assertEquals(high[0], high[1], "DAC-02: L = R");
            assertTrue(high[0] > 0 && low[0] < 0);
            assertEquals(-low[0] * 254, high[0] * 256, "DAC-02/03: pin swing ratio +254 : -256");
        }

        @Test
        void panBitsGateAChannelOutOfEachSide() {
            Core core = dac(0, 0xFF);
            core.write(1, 0xB6, 0x80);
            core.frames(2);
            int[] leftOnly = core.frame();
            assertTrue(leftOnly[0] > 0, "Group 9: L bit keeps the channel on the left");
            assertEquals(0, leftOnly[1], "Group 9: R bit clear removes it from the right");
            core.write(1, 0xB6, 0x00);
            core.frames(2);
            int[] neither = core.frame();
            assertEquals(0, neither[0], "Group 9: both clear = absent from both outputs");
            assertEquals(0, neither[1]);
        }

        @Test
        void dac05ChannelsSumOnThePinWithoutACrossChannelClamp() {
            Core fmOnly = Core.ym3438();
            Core both = Core.ym3438();
            for (Core core : new Core[] { fmOnly, both }) {
                core.voice(0, 7, 0, ONE_OP, 31, 0, 0, 0, 15);
                core.note(0, 2, 0x200);
                core.keyOn(0, 0xF);
            }
            both.write(1, 0xB6, 0xC0);
            both.write(0, 0x2B, 0x80);
            both.write(0, 0x2A, 0xFF);
            fmOnly.nop();
            fmOnly.nop();
            fmOnly.nop();
            Core dacOnly = dac(0, 0xFF);
            int dacLevel = dacOnly.frame()[0];
            int[] fm = fmOnly.leftSums(1100);
            int[] sum = both.leftSums(1100);
            for (int n = 0; n < fm.length; n++) {
                assertEquals(fm[n] + dacLevel, sum[n], "DAC-05: the left pin integrates both channels, no digital clamp across channels");
            }
            assertTrue(max(sum) > max(fm), "DAC-05: the sum exceeds one channel's full scale");
        }

        @Test
        void dac06LadderStepIsOneLsbOnYm3438AndLargerOnYm2612() {
            int span3438 = dac(0, 0xFF).frame()[0] - dac(0, 0x80).frame()[0];
            int step3438 = dac(0, 0x80).frame()[0] - dac(0, 0x7F).frame()[0];
            assertEquals(2 * span3438, step3438 * 254,
                    "DAC-06: YM3438 is monotonic, $7F -> $80 is exactly the 2 LSB of the 9-bit values (-2 -> 0)");
            int ym2612 = NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE;
            int span2612 = dac(ym2612, 0xFF).frame()[0] - dac(ym2612, 0x80).frame()[0];
            int step2612 = dac(ym2612, 0x80).frame()[0] - dac(ym2612, 0x7F).frame()[0];
            assertTrue(step2612 * 254 > 2 * span2612,
                    "DAC-06: the YM2612 ladder adds a crossover gap of several LSBs between negative and non-negative values");
        }
    }

    @Nested
    class Group10ChannelThreeSpecialMode {
        private Core specialMode(int control) {
            Core core = Core.ym3438();
            core.voice(2, 7, 0, ALL_OPS, 31, 0, 0, 0, 15);
            core.write(0, 0x27, control);
            core.write(0, 0xAD, (4 << 3) | 4);
            core.write(0, 0xA9, 0x39);
            core.write(0, 0xAE, (5 << 3) | 4);
            core.write(0, 0xAA, 0x39);
            core.write(0, 0xAC, (3 << 3) | 4);
            core.write(0, 0xA8, 0x39);
            core.write(0, 0xA6, (4 << 3) | 2);
            core.write(0, 0xA2, 0x1A);
            core.frames(2);
            return core;
        }

        @Test
        void ch301EachOperatorTakesItsOwnFrequency() {
            Core core = specialMode(0x40);
            assertEquals(8648, core.st.pgInc[slot(2, 1)], "CH3-01: op1 from $A9/$AD -> 439.3 Hz");
            assertEquals(17296, core.st.pgInc[slot(2, 2)], "CH3-01: op2 from $AA/$AE -> 878.6 Hz");
            assertEquals(4324, core.st.pgInc[slot(2, 3)], "CH3-01: op3 from $A8/$AC -> 219.7 Hz");
            assertEquals(4304, core.st.pgInc[slot(2, 4)], "CH3-01 (corrected): op4 from $A2/$A6 fnum $21A -> 218.6 Hz");
            assertEquals(218.6, 4304 * FS_NTSC / (1 << 20), 0.05);
        }

        @Test
        void ch302NormalModeUsesTheChannelFrequencyForAllFour() {
            Core core = specialMode(0x00);
            for (int op = 1; op <= 4; op++) {
                assertEquals(4304, core.st.pgInc[slot(2, op)], "CH3-02: $27 = 0 -> all four operators at the $A2/$A6 frequency");
            }
        }

        @Test
        void ch303KeyCodeIsPerOperator() {
            Core core = Core.ym3438();
            core.voice(2, 7, 0, ALL_OPS, 31, 16, 0, 15, 15);
            core.op(2, 1, 0x50, 0xDF);
            core.op(2, 4, 0x50, 0xDF);
            core.write(0, 0x27, 0x40);
            core.write(0, 0xAD, (7 << 3) | 7);
            core.write(0, 0xA9, 0xFF);
            core.write(0, 0xA6, 0x01);
            core.write(0, 0xA2, 0x00);
            core.frames(2);
            assertEquals(31, core.st.kcode3ch[1], "CH3-03: op1 block 7 / $7FF -> kc 31");
            assertEquals(0, core.st.kcode[2], "CH3-03: op4 block 0 / $100 -> kc 0");
            core.keyOn(2, 0xF);
            core.frames(6);
            int[] op1 = core.levels(slot(2, 1), 200);
            List<int[]> fast = runs(op1);
            for (int i = 1; i < 20; i++) {
                assertEquals(8, fast.get(i)[0] - fast.get(i - 1)[0], "CH3-03: op1 rate min(63, 32 + 31) -> +8 per EG step");
                assertEquals(3, fast.get(i)[1]);
            }
            Core slow = Core.ym3438();
            slow.voice(2, 7, 0, ALL_OPS, 31, 16, 0, 15, 15);
            slow.op(2, 1, 0x50, 0xDF);
            slow.op(2, 4, 0x50, 0xDF);
            slow.write(0, 0x27, 0x40);
            slow.write(0, 0xAD, (7 << 3) | 7);
            slow.write(0, 0xA9, 0xFF);
            slow.write(0, 0xA6, 0x01);
            slow.write(0, 0xA2, 0x00);
            slow.frames(2);
            slow.keyOn(2, 0xF);
            slow.frames(6);
            List<int[]> steps = runs(slow.levels(slot(2, 4), 1000));
            for (int i = 1; i < steps.size() - 1; i++) {
                assertEquals(1, steps.get(i)[0] - steps.get(i - 1)[0], "CH3-03: op4 rate 32 -> +1 per update");
                assertEquals(48, steps.get(i)[1], "CH3-03: rate 32 = shift 3, row 0 -> one update per 16 EG steps");
            }
        }
    }

    @Nested
    class Group12ResetState {
        @Test
        void rst01StatusIsZeroAfterReset() {
            assertEquals(0, Core.ym2612().status(), "RST-01: read $4000 -> $00");
            assertEquals(0, new Ym2612Chip().readStatus(), "RST-01: facade status after construction");
        }

        @Test
        void rst02KeyOnAloneAfterResetIsSilent() {
            Core core = Core.ym3438();
            core.write(0, 0x28, 0xF0);
            for (int n = 0; n < 1000; n++) {
                int[] pins = core.frame();
                assertEquals(0, pins[0], "RST-02: silence, every rate is 0");
                assertEquals(0, pins[1]);
            }
            for (int s = 0; s < 24; s++) {
                assertEquals(SILENT, core.st.egLevel[s], "RST table: envelopes at $3FF, attack rate 0 = no change");
            }
            Ym2612Chip chip = new Ym2612Chip();
            chip.setChipType(1);
            chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
            chip.write(0, 0x28, 0xF0);
            int[] left = new int[2000];
            int[] right = new int[2000];
            chip.renderStereo(left, right, left.length);
            assertFalse(anyNonZero(left) || anyNonZero(right), "RST-02: facade renders silence after a bare key on");
        }

        @Test
        void rst03MinimalWritesMakeChannelOneAudible() {
            Core core = Core.ym3438();
            core.write(0, 0xB4, 0xC0);
            for (int reg : new int[] { 0x50, 0x54, 0x58, 0x5C }) {
                core.write(0, reg, 0x1F);
            }
            core.write(0, 0xA4, 0x24);
            core.write(0, 0xA0, 0x39);
            core.write(0, 0x28, 0xF0);
            core.frames(6);
            for (int op = 1; op <= 4; op++) {
                assertEquals(0, core.st.egLevel[slot(0, op)], "RST-03 (corrected): AR 31 on every operator -> all four at 0 dB at once");
            }
            assertEquals(4324, core.st.pgInc[slot(0, 4)], "RST-03 (corrected): reset MUL 0 = x0.5 -> 219.7 Hz, not 439.3");
            assertEquals(0, core.st.connect[0], "RST-03: algorithm 0 after reset");
            assertTrue(anyNonZero(core.channelOut(0, 400)), "RST-03: channel 1 audible through carrier op4");
            for (int ch = 1; ch < 6; ch++) {
                assertEquals(SILENT, core.st.egLevel[slot(ch, 4)], "RST-03: other channels untouched");
            }
        }

        @Test
        void rst04ResetClearsTheRegisterFile() {
            Core core = Core.ym3438();
            core.voice(0, 5, 3, new int[] { 10, 20, 30, 40 }, 31, 12, 3, 4, 7);
            core.note(0, 4, 0x439);
            core.write(0, 0x22, 0x0F);
            core.write(0, 0x24, 0x55);
            core.write(0, 0x26, 0x66);
            core.write(0, 0x27, 0x4F);
            core.write(0, 0x2B, 0x80);
            core.write(0, 0x2A, 0x10);
            core.keyOn(0, 0xF);
            core.frames(50);
            core.chip.reset();
            assertEquals(0, core.status(), "RST-01 after a reset mid-run");
            for (int ch = 0; ch < 6; ch++) {
                assertEquals(0, core.st.fnum[ch], "RST table: $A0-$AE cleared");
                assertEquals(0, core.st.block[ch]);
                assertEquals(0, core.st.connect[ch], "RST table: algorithm 0");
                assertEquals(0, core.st.fb[ch], "RST table: feedback 0");
            }
            for (int s = 0; s < 24; s++) {
                assertEquals(0, core.st.tl[s], "RST table: TL 0");
                assertEquals(0, core.st.ar[s], "RST table: AR 0");
                assertEquals(0, core.st.dr[s], "RST table: D1R 0");
                assertEquals(0, core.st.rr[s], "RST table: RR 0");
                assertEquals(0, core.st.dt[s], "RST table: DT 0");
                assertEquals(0, core.st.pgPhase[s], "RST table: phase accumulators 0");
                assertEquals(SILENT, core.st.egLevel[s], "RST table: envelopes at $3FF");
            }
            assertEquals(0, core.st.lfoEn, "RST table: LFO disabled");
            assertEquals(0, core.st.lfoCnt, "RST table: LFO counter 0");
            assertEquals(0, core.st.timerAReg, "RST table: timers cleared");
            assertEquals(0, core.st.timerBReg);
            assertEquals(0, core.st.modeCh3, "RST table: channel 3 normal mode");
            assertEquals(0, core.st.dacen, "RST table: DAC disabled");
            core.write(0, 0x28, 0xF0);
            core.frames(100);
            for (int s = 0; s < 24; s++) {
                assertEquals(SILENT, core.st.egLevel[s], "RST-02 after reset: silence");
            }
        }
    }

    @Nested
    class FacadeContract {
        private Ym2612Chip internalRateChip(int chipType) {
            Ym2612Chip chip = new Ym2612Chip();
            chip.setChipType(chipType);
            chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
            return chip;
        }

        private int renderOne(Ym2612Chip chip) {
            int[] left = new int[1];
            int[] right = new int[1];
            chip.renderStereo(left, right, 1);
            return left[0];
        }

        @Test
        void timerAFlagThroughReadStatus() {
            Ym2612Chip chip = internalRateChip(1);
            chip.write(0, 0x24, 992 >> 2);
            chip.write(0, 0x25, 992 & 3);
            chip.write(0, 0x27, 0x05);
            int first = -1;
            for (int n = 0; n < 64; n++) {
                renderOne(chip);
                if ((chip.readStatus() & 1) != 0) {
                    first = n;
                    break;
                }
            }
            // Each facade write drains its own bus pacing (about 1.3 samples) through the core before the next
            // rendered sample (contract, write/render interleaving), so three writes shift the count by up to two.
            assertTrue(first >= 30 && first <= 35,
                    "TMR-02 through the facade: 32 samples after load plus the drained write pacing; was " + first);
            chip.write(0, 0x27, 0x15);
            assertEquals(0, chip.readStatus() & 1, "TMR-04 through the facade: reset clears bit 0");
            int again = -1;
            for (int n = 0; n < 64; n++) {
                renderOne(chip);
                if ((chip.readStatus() & 1) != 0) {
                    again = n;
                    break;
                }
            }
            assertTrue(again >= 0, "TMR-04: set again by the next overflow");
            Ym2612Chip max = internalRateChip(1);
            max.write(0, 0x24, 0xFF);
            max.write(0, 0x25, 0x03);
            max.write(0, 0x27, 0x05);
            int maxFirst = -1;
            for (int n = 0; n < 8; n++) {
                renderOne(max);
                if ((max.readStatus() & 1) != 0) {
                    maxFirst = n;
                    break;
                }
            }
            assertTrue(maxFirst >= 0 && maxFirst <= 4,
                    "TMR-01 through the facade: NA 1023 overflows one sample after the load lands in the drained write; was " + maxFirst);
        }

        /** Instant-attack, fast-release voice on every channel through the public write API. */
        private void instantVoices(Ym2612Chip chip) {
            for (int ch = 0; ch < 6; ch++) {
                int port = ch / 3;
                int base = ch % 3;
                chip.write(port, 0xB0 + base, 0x07);
                chip.write(port, 0xB4 + base, 0xC0);
                for (int regSlot = 0; regSlot < 16; regSlot += 4) {
                    chip.write(port, 0x30 + base + regSlot, 0x01);
                    chip.write(port, 0x40 + base + regSlot, 0x00);
                    chip.write(port, 0x50 + base + regSlot, 0x1F);
                    chip.write(port, 0x60 + base + regSlot, 0x00);
                    chip.write(port, 0x70 + base + regSlot, 0x00);
                    chip.write(port, 0x80 + base + regSlot, 0x0F);
                    chip.write(port, 0x90 + base + regSlot, 0x00);
                }
                chip.write(port, 0xA4 + base, (4 << 3) | (0x439 >> 8));
                chip.write(port, 0xA0 + base, 0x439 & 0xff);
            }
        }

        private void assertFacadeChannelLevel(Ym2612Chip chip, int ch, int level, String message) {
            NukedOpn2State core = chip.captureSnapshot().core();
            for (int op = 1; op <= 4; op++) {
                assertEquals(level, core.egLevel[slot(ch, op)], message + " (channel " + (ch + 1) + " op" + op + ")");
            }
        }

        /**
         * Two consecutive {@code $28} writes through the public API both take
         * effect, for every ordered pair of channels. The {@code $28} latch is
         * consumed only when the sequencer reaches its channel (up to 23
         * cycles later) and the next {@code $28} data strobe overwrites it, so
         * this holds only while the facade keeps each data strobe on the bus
         * for the busy window (doc "Address latch behaviour", REG-06), as a
         * busy-polling driver does.
         */
        @Test
        void consecutiveKeyWritesThroughTheFacadeBothLand() {
            for (int first = 0; first < 6; first++) {
                for (int second = 0; second < 6; second++) {
                    if (first == second) {
                        continue;
                    }
                    Ym2612Chip chip = internalRateChip(1);
                    instantVoices(chip);
                    // Frames completed while the voice writes drained are rendered first; clear that backlog
                    // so the key writes below are followed by live chip time.
                    int[] left = new int[400];
                    int[] right = new int[400];
                    chip.renderStereo(left, right, 400);
                    chip.write(0, 0x28, 0xF0 | keySelect(first));
                    chip.write(0, 0x28, 0xF0 | keySelect(second));
                    // The EG steps each slot every third sample, so an instant attack needs up to three frames.
                    for (int frame = 0; frame < 12; frame++) {
                        renderOne(chip);
                    }
                    String pair = "key-on " + (first + 1) + " then " + (second + 1);
                    assertFacadeChannelLevel(chip, first, 0, pair + ": first write landed");
                    assertFacadeChannelLevel(chip, second, 0, pair + ": second write landed");
                    chip.write(0, 0x28, keySelect(first));
                    chip.write(0, 0x28, keySelect(second));
                    chip.renderStereo(left, right, 400);
                    pair = "key-off " + (first + 1) + " then " + (second + 1);
                    assertFacadeChannelLevel(chip, first, SILENT, pair + ": first write landed");
                    assertFacadeChannelLevel(chip, second, SILENT, pair + ": second write landed");
                }
            }
        }

        private int dacLevel(int chipType, int data) {
            Ym2612Chip chip = internalRateChip(chipType);
            chip.write(1, 0xB6, 0xC0);
            chip.write(0, 0x2B, 0x80);
            chip.write(0, 0x2A, data);
            renderOne(chip);
            renderOne(chip);
            int[] left = new int[4];
            int[] right = new int[4];
            chip.renderStereo(left, right, 4);
            assertArrayEquals(left, right, "DAC through the facade: L = R with both pan bits set");
            return left[3];
        }

        @Test
        void dacLevelsThroughTheFacade() {
            int centre = dacLevel(1, 0x80);
            int top = dacLevel(1, 0xFF);
            int bottom = dacLevel(1, 0x00);
            assertEquals(0, centre, "DAC-01 through the facade (YM3438 stage): $80 contributes 0");
            assertTrue(top > 0 && bottom < 0);
            assertEquals(-bottom * 254, top * 256, "DAC-02/03 through the facade: +254 : -256");
            int step3438 = dacLevel(1, 0x80) - dacLevel(1, 0x7F);
            assertEquals(2 * (top - centre), step3438 * 254, "DAC-06 through the facade: YM3438 monotonic");
            int centre2612 = dacLevel(0, 0x80);
            int step2612 = centre2612 - dacLevel(0, 0x7F);
            int span2612 = dacLevel(0, 0xFF) - centre2612;
            assertTrue(step2612 * 254 > 2 * span2612, "DAC-06 through the facade: the discrete YM2612 ladder gap");
        }

        private void prime(Ym2612Chip chip) {
            chip.write(0, 0x22, 0x0B);
            chip.write(0, 0x2B, 0x80);
            chip.write(0, 0x24, 0xF0);
            chip.write(0, 0x25, 0x01);
            chip.write(0, 0x26, 0xE0);
            chip.write(0, 0x27, 0x8F);
            for (int port = 0; port < 2; port++) {
                for (int chInPart = 0; chInPart < 3; chInPart++) {
                    chip.write(port, 0xB0 + chInPart, 0x3C | chInPart);
                    chip.write(port, 0xB4 + chInPart, 0xC5);
                    for (int regSlot = 0; regSlot < 16; regSlot += 4) {
                        int reg = chInPart + regSlot;
                        chip.write(port, 0x30 + reg, 0x21 + regSlot);
                        chip.write(port, 0x40 + reg, 8 + regSlot);
                        chip.write(port, 0x50 + reg, 0x9F);
                        chip.write(port, 0x60 + reg, 0x8A);
                        chip.write(port, 0x70 + reg, 0x05);
                        chip.write(port, 0x80 + reg, 0x27);
                        chip.write(port, 0x90 + reg, regSlot == 8 ? 0x0A : 0x00);
                    }
                    chip.write(port, 0xA4 + chInPart, 0x22 + chInPart);
                    chip.write(port, 0xA0 + chInPart, 0x39 + 7 * chInPart);
                }
            }
            chip.write(0, 0xAC, 0x1C);
            chip.write(0, 0xA8, 0x40);
            for (int ch = 0; ch < 6; ch++) {
                chip.write(0, 0x28, 0xF0 | keySelect(ch));
            }
            chip.write(0, 0x2A, 0x5A);
        }

        private void perturb(Ym2612Chip chip) {
            chip.write(0, 0x2A, 0xC3);
            chip.write(0, 0x40, 0x23);
            chip.write(0, 0x28, 0x00);
            chip.write(0, 0x27, 0x35);
            chip.write(0, 0x22, 0x00);
            chip.write(1, 0xB5, 0x00);
            chip.setMute(2, true);
        }

        @Test
        void snapshotRoundTripIsExact() {
            Ym2612Chip live = internalRateChip(0);
            prime(live);
            int[] warm = new int[137];
            live.renderStereo(warm, new int[137], warm.length);
            Ym2612Chip.Snapshot snapshot = live.captureSnapshot();
            int[] expectedLeft = new int[4096];
            int[] expectedRight = new int[4096];
            live.renderStereo(expectedLeft, expectedRight, expectedLeft.length);

            Ym2612Chip fresh = internalRateChip(0);
            fresh.restoreSnapshot(snapshot);
            int[] freshLeft = new int[4096];
            int[] freshRight = new int[4096];
            fresh.renderStereo(freshLeft, freshRight, freshLeft.length);
            assertArrayEquals(expectedLeft, freshLeft, "Snapshot: a fresh chip restored from the snapshot renders the same future");
            assertArrayEquals(expectedRight, freshRight);

            Ym2612Chip perturbed = internalRateChip(0);
            prime(perturbed);
            perturbed.renderStereo(new int[137], new int[137], 137);
            perturb(perturbed);
            perturbed.renderStereo(new int[300], new int[300], 300);
            perturbed.restoreSnapshot(snapshot);
            int[] left = new int[4096];
            int[] right = new int[4096];
            perturbed.renderStereo(left, right, left.length);
            assertArrayEquals(expectedLeft, left, "Snapshot: restore after perturbation is bit-exact, mutes included");
            assertArrayEquals(expectedRight, right);
            assertTrue(anyNonZero(expectedLeft), "Snapshot: the primed state is audible");
        }
    }
}
