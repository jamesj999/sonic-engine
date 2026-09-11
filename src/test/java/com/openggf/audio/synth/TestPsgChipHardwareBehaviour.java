package com.openggf.audio.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box assertions of the SN76489 behaviour specified in
 * {@code docs/architecture/research/audio/2026-08-29-sn76489-clean-room-spec.md}
 * (section numbers below). Generator state is observed through
 * {@link PsgChip#captureSnapshot()} only, and the chip is stepped one internal
 * tick at a time by rendering at exactly the tick rate, where one output
 * sample is one ÷16 tick (§1). No reflection, no emulator-derived vectors.
 */
class TestPsgChipHardwareBehaviour {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** §9.1, Sega white noise: register contents after each of the first 32 shifts. */
    private static final int[] SEGA_WHITE_STATES = {
            0x4000, 0x2000, 0x1000, 0x0800, 0x0400, 0x0200, 0x0100, 0x0080,
            0x0040, 0x0020, 0x0010, 0x0008, 0x8004, 0x4002, 0x2001, 0x9000,
            0x4800, 0x2400, 0x1200, 0x0900, 0x0480, 0x0240, 0x0120, 0x0090,
            0x0048, 0x8024, 0x4012, 0x2009, 0x1004, 0x0802, 0x0401, 0x8200};

    /** §9.1, Sega periodic noise. */
    private static final int[] SEGA_PERIODIC_STATES = {
            0x4000, 0x2000, 0x1000, 0x0800, 0x0400, 0x0200, 0x0100, 0x0080,
            0x0040, 0x0020, 0x0010, 0x0008, 0x0004, 0x0002, 0x0001, 0x8000,
            0x4000, 0x2000, 0x1000, 0x0800, 0x0400, 0x0200, 0x0100, 0x0080,
            0x0040, 0x0020, 0x0010, 0x0008, 0x0004, 0x0002, 0x0001, 0x8000};

    /** §9.1, TI discrete white noise. */
    private static final int[] TI_WHITE_STATES = {
            0x2000, 0x1000, 0x0800, 0x0400, 0x0200, 0x0100, 0x0080, 0x0040,
            0x0020, 0x0010, 0x0008, 0x0004, 0x0002, 0x4001, 0x6000, 0x3000,
            0x1800, 0x0C00, 0x0600, 0x0300, 0x0180, 0x00C0, 0x0060, 0x0030,
            0x0018, 0x000C, 0x0006, 0x4003, 0x2001, 0x5000, 0x2800, 0x1400};

    /** §9.2, the ×8191 column of the attenuator ladder. */
    private static final int[] LEVELS_8191 = {
            8191, 6506, 5168, 4105, 3261, 2590, 2057, 1634,
            1298, 1031, 819, 651, 517, 411, 326, 0};

    // --- §9.1 shift-register sequences ----------------------------------

    @Test
    void segaWhiteNoiseShiftsThroughTheSpecifiedStates() {
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(0xE4); // white, rate 00: one shift per 32 ticks (§4.1)
        assertEquals(0x8000, chip.captureSnapshot().lfsr(), "reset seed (§4.3)");
        assertArrayEquals(SEGA_WHITE_STATES, lfsrStatesPerShift(chip, 32, 32));
        assertEquals("00000000000000100000000000010010", bits(SEGA_WHITE_STATES));
    }

    @Test
    void segaPeriodicNoiseHasPeriodSixteen() {
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(0xE0); // periodic, rate 00
        assertArrayEquals(SEGA_PERIODIC_STATES, lfsrStatesPerShift(chip, 32, 32));
        assertEquals("00000000000000100000000000000010", bits(SEGA_PERIODIC_STATES));
    }

    @Test
    void discreteWhiteNoiseUsesFifteenBitRegister() {
        PsgChip chip = tickChip(PsgChip.ChipType.DISCRETE);
        chip.write(0xE4);
        assertEquals(0x4000, chip.captureSnapshot().lfsr(), "TI reset seed (§4.3)");
        assertArrayEquals(TI_WHITE_STATES, lfsrStatesPerShift(chip, 32, 32));
        assertEquals("00000000000001000000000000011000", bits(TI_WHITE_STATES));
    }

    @Test
    void discretePeriodicNoiseHasPeriodFifteen() {
        PsgChip chip = tickChip(PsgChip.ChipType.DISCRETE);
        chip.write(0xE0);
        int[] states = lfsrStatesPerShift(chip, 32, 32);
        StringBuilder expected = new StringBuilder();
        for (int shift = 1; shift <= 32; shift++) {
            expected.append(shift % 15 == 14 ? '1' : '0');
        }
        assertEquals(expected.toString(), bits(states), "ones at shifts 14 and 29");
        assertEquals(0x4000, states[14], "back at the seed after 15 shifts");
    }

    @Test
    void segaWhiteNoiseCycleFromResetIsFiftySevenThousandThreeHundredThirtySeven() {
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(0xE4);
        advanceTicks(chip, 1); // the reset counter's reload tick (§7)
        int shifts = 0;
        int[] one = new int[32];
        do {
            chip.renderStereo(one, one, 32);
            shifts++;
            if (shifts > 65_535) {
                throw new AssertionError("no return to the seed within 65,535 shifts");
            }
        } while (chip.captureSnapshot().lfsr() != 0x8000);
        assertEquals(57_337, shifts, "§4.3: 0x8000 lies on the 57,337-state cycle");
    }

    // --- §9.2 attenuation ------------------------------------------------

    @Test
    void attenuationLadderIsTwoDecibelStepsWithATrueOff() {
        for (int a = 0; a < 16; a++) {
            assertEquals(LEVELS_8191[a], PsgChip.attenuationLevel(a), "A = " + a);
        }
        assertEquals(PsgChip.FULL_SCALE, PsgChip.attenuationLevel(0));
        assertEquals(819, PsgChip.attenuationLevel(10), "lin[10] = 0.1 exactly");
        assertEquals(0, PsgChip.attenuationLevel(15), "0xF is off, not -30 dB");
        double step = Math.pow(10.0, 0.1);
        for (int a = 0; a < 14; a++) {
            double ratio = (double) PsgChip.attenuationLevel(a) / PsgChip.attenuationLevel(a + 1);
            assertEquals(step, ratio, 0.004, "lin[A] / lin[A+1] at A = " + a);
        }
    }

    @Test
    void attenuationWriteMovesTheOutputBeforeTheNextPolarityFlip() {
        // A channel held high (N = 0, §3.2) is a DC level; a volume write must
        // step it at the write time, not at a flip (§5).
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(0xC0);
        chip.write(0x00);
        chip.write(0xD0);
        int[] left = new int[64];
        int[] right = new int[64];
        chip.renderStereo(left, right, 64);
        int settled = left[63];
        assertTrue(settled > PsgChip.FULL_SCALE / 2, "held high at attenuation 0");

        chip.write(0xDF);
        left = new int[32];
        right = new int[32];
        chip.renderStereo(left, right, 32);
        assertTrue(left[31] < settled / 4,
                "attenuation 0xF removes the level within the same render");
    }

    // --- §9.3 polarity-flip timing ---------------------------------------

    @Test
    void toneFlipsEveryPeriodTicksAfterTheReloadTick() {
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(0x8C);
        chip.write(0x1A); // tone 0 N = 0x1AC
        chip.write(0x90);
        assertEquals(List.of(1 + 428, 1 + 856, 1 + 1284), flipTicks(chip, 0, 1300),
                "counter at 0 reloads on the first tick t0 (§7); flips at t0 + 428k (§9.3)");
    }

    @Test
    void shortPeriodsFlipAtTheirOwnRate() {
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(0xA0);
        chip.write(0x01); // tone 1 N = 0x010
        chip.write(0xC2);
        chip.write(0x00); // tone 2 N = 0x002
        chip.write(0xB0);
        chip.write(0xD0);
        assertEquals(List.of(17, 33, 49, 65), flipTicks(chip, 1, 70));
        PsgChip second = tickChip(PsgChip.ChipType.INTEGRATED);
        second.write(0xC2);
        second.write(0x00);
        second.write(0xD0);
        assertEquals(List.of(3, 5, 7, 9, 11), flipTicks(second, 2, 12));
    }

    @Test
    void integratedPeriodsZeroAndOneHoldTheOutputHigh() {
        for (int period : new int[] {0, 1}) {
            PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
            chip.write(0x80 | period);
            chip.write(0x00);
            chip.write(0x90);
            assertEquals(List.of(), flipTicks(chip, 0, 4096), "N = " + period + " never flips");
            assertTrue(chip.captureSnapshot().polarities()[0], "N = " + period + " is high");
        }
    }

    @Test
    void integratedPeriodZeroIsADcLevelNotALowBuzzAtTheOutputRate() {
        PsgChip chip = new PsgChip(44100.0, PsgChip.ChipType.INTEGRATED);
        chip.write(0xC0);
        chip.write(0x00);
        chip.write(0xD0);
        int[] left = new int[44100];
        int[] right = new int[44100];
        chip.renderStereo(left, right, left.length);
        int peak = 0;
        int risingSamples = 0;
        for (int i = 1; i < left.length; i++) {
            peak = Math.max(peak, left[i]);
            if (i >= 200 && left[i] > left[i - 1]) {
                risingSamples++;
            }
        }
        assertTrue(peak > PsgChip.FULL_SCALE * 3 / 4, "the DC step reaches the attenuator level");
        assertEquals(0, risingSamples,
                "after the step the AC-coupled level only decays; a 109 Hz or 112 kHz tone would rise again");
    }

    @Test
    void discretePeriodZeroWrapsAndPeriodOneFlipsEveryTick() {
        PsgChip zero = tickChip(PsgChip.ChipType.DISCRETE);
        zero.write(0x80);
        zero.write(0x00);
        zero.write(0x90);
        assertEquals(List.of(1 + 0x400, 1 + 0x800), flipTicks(zero, 0, 0x800 + 1),
                "§3.2: the discrete part's ten-bit counter wraps, period 0 runs as 0x400");

        PsgChip one = tickChip(PsgChip.ChipType.DISCRETE);
        one.write(0x81);
        one.write(0x00);
        one.write(0x90);
        assertEquals(List.of(2, 3, 4, 5, 6), flipTicks(one, 0, 6));
    }

    @Test
    void changingThePeriodTakesEffectAtTheNextReload() {
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(0x80);
        chip.write(0x04); // N = 0x40
        chip.write(0x90);
        advanceTicks(chip, 1 + 10);
        chip.write(0x80);
        chip.write(0x01); // N = 0x10 while 54 ticks of the old count remain
        List<Integer> flips = flipTicks(chip, 0, 200);
        assertEquals(List.of(54, 54 + 16, 54 + 32, 54 + 48), flips.subList(0, 4),
                "§3.1: the running count finishes, the new period is used from the next reload");
    }

    @Test
    void toneFrequencyAtTheOutputRateMatchesTheClockFormula() {
        for (double rate : new double[] {44100.0, 48000.0}) {
            PsgChip chip = new PsgChip(rate, PsgChip.ChipType.INTEGRATED);
            chip.write(0x8C);
            chip.write(0x1A);
            chip.write(0x90);
            int seconds = 10;
            int[] left = new int[(int) rate * seconds];
            int[] right = new int[left.length];
            chip.renderStereo(left, right, left.length);
            int rising = 0;
            for (int i = 1000; i < left.length; i++) {
                if (left[i - 1] < 0 && left[i] >= 0) {
                    rising++;
                }
            }
            double expected = PsgChip.INPUT_CLOCK_HZ / (32.0 * 0x1AC) * (seconds - 1000.0 / rate);
            assertEquals(expected, rising, 2.0, "261.36 Hz at " + rate + " Hz (§3.1)");
        }
    }

    // --- §4 noise clocking ----------------------------------------------

    @Test
    void noiseRateBitsSelectSixteenThirtyTwoAndSixtyFourTickReloads() {
        assertEquals(List.of(1 + 32, 1 + 64, 1 + 96), shiftTicks(noiseChip(0xE4), 100));
        assertEquals(List.of(1 + 64, 1 + 128), shiftTicks(noiseChip(0xE5), 130));
        assertEquals(List.of(1 + 128, 1 + 256), shiftTicks(noiseChip(0xE6), 260));
    }

    @Test
    void firstNonZeroNoiseOutputArrivesAtTheFifteenthShift() {
        for (int control : new int[] {0xE4, 0xE0}) {
            PsgChip chip = noiseChip(control);
            List<Integer> highs = new ArrayList<>();
            for (int tick = 1; tick <= 520; tick++) {
                advanceTicks(chip, 1);
                if ((chip.captureSnapshot().lfsr() & 1) != 0 && highs.isEmpty()) {
                    highs.add(tick);
                }
            }
            assertEquals(List.of(1 + 480), highs, "§9.3: 15 shifts x 32 ticks after t0 for " + control);
        }
    }

    @Test
    void toneTwoLinkedNoiseFollowsToneTwoPeriodAndKeepsClockingAtTheTopOfTheTable() {
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(0xC0);
        chip.write(0x01); // tone 2 N = 0x10
        chip.write(0xE7); // white, tone-2 rate
        chip.write(0xF0);
        assertEquals(List.of(1 + 32, 1 + 64, 1 + 96), shiftTicks(chip, 100));

        chip.write(0xC0);
        chip.write(0x02); // N2 = 0x20: picked up at the next reload
        List<Integer> slower = shiftTicks(chip, 200);
        assertEquals(64, slower.get(slower.size() - 1) - slower.get(slower.size() - 2));

        // §10.5: N2 <= 1 pins tone 2's output high, but the counter that feeds
        // the noise clock still expires every tick, so the LFSR shifts every
        // second tick — the highest noise pitch, not silence. The SMPS note
        // tables end in exactly this value (§3.3).
        for (int[] topOfTable : new int[][]{{0xC1, 0x00}, {0xC0, 0x00}}) {
            chip.write(topOfTable[0]);
            chip.write(topOfTable[1]);
            advanceTicks(chip, 200);
            int before = chip.captureSnapshot().lfsr();
            List<Integer> fastest = shiftTicks(chip, 1000);
            assertEquals(500, fastest.size(), "one shift per two ticks at N2 = " + (topOfTable[0] & 1));
            for (int i = 1; i < fastest.size(); i++) {
                assertEquals(2, fastest.get(i) - fastest.get(i - 1));
            }
            assertNotEquals(before, chip.captureSnapshot().lfsr());
            assertTrue(chip.captureSnapshot().polarities()[2], "tone 2 itself holds high (§3.2)");
        }
    }

    @Test
    void discreteToneTwoPeriodOneClocksNoiseEveryOtherTick() {
        PsgChip chip = tickChip(PsgChip.ChipType.DISCRETE);
        chip.write(0xC1);
        chip.write(0x00);
        chip.write(0xE7);
        chip.write(0xF0);
        List<Integer> shifts = shiftTicks(chip, 40);
        assertEquals(2, shifts.get(1) - shifts.get(0));
        assertEquals(2, shifts.get(5) - shifts.get(4));
    }

    @Test
    void noiseShiftsOncePerRisingEdgeOnly() {
        PsgChip edge = noiseChip(0xE4);
        List<Integer> edgeShifts = shiftTicks(edge, 32 * 64 + 1);
        assertEquals(64, edgeShifts.size(),
                "the hardware rule (§4.1): one shift per rising edge of the noise square wave");
    }

    @Test
    void noiseRegisterWritesResetTheLfsrWithoutReloadingItsCounter() {
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(0xF0);
        chip.write(0xE4); // leaves the latch on the noise register
        advanceTicks(chip, 1 + 32 * 3 + 5);
        assertEquals(0x1000, chip.captureSnapshot().lfsr(), "three shifts in (ticks 33, 65, 97)");
        chip.write(0x04); // data byte to the latched noise register, same value
        assertEquals(0x8000, chip.captureSnapshot().lfsr(),
                "§4.5: any write landing in the noise register reseeds, changed or not");
        // The next shift keeps the existing schedule (§4.5 assumption, §10.14):
        // 27 ticks remain of the 32-tick cadence that began at tick 1.
        assertEquals(List.of(27, 27 + 32), shiftTicks(chip, 60));
    }

    // --- §9.4 write protocol ----------------------------------------------

    @Test
    void latchAndDataBytesAssembleRegistersAsSpecified() {
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(0x8C);
        chip.write(0x2A);
        assertEquals(0x2AC, chip.captureSnapshot().tonePeriods()[0]);
        assertEquals(0, chip.captureSnapshot().latch(), "latch = tone 0 period");
        chip.write(0x1A);
        assertEquals(0x1AC, chip.captureSnapshot().tonePeriods()[0],
                "a second data byte replaces bits 9-4 only");

        chip.write(0x90);
        assertEquals(0, chip.captureSnapshot().attenuations()[0]);
        assertEquals(1, chip.captureSnapshot().latch(), "latch = tone 0 attenuation");
        chip.write(0x0F);
        assertEquals(0xF, chip.captureSnapshot().attenuations()[0],
                "a data byte to an attenuation latch takes bits 3-0");
        chip.write(0x3A);
        assertEquals(0xA, chip.captureSnapshot().attenuations()[0], "bits 5-4 ignored");

        chip.write(0xE7);
        PsgChip.Snapshot noise = chip.captureSnapshot();
        assertEquals(0x7, noise.noiseControl(), "white, tone-2 rate");
        assertEquals(0x8000, noise.lfsr());
        assertEquals(6, noise.latch(), "latch = noise");
        advanceTicks(chip, 1 + 32 * 40); // N2 = 0 holds; give the counter a chance anyway
        chip.write(0x03);
        assertEquals(0x3, chip.captureSnapshot().noiseControl(), "periodic, tone-2 rate");
        assertEquals(0x8000, chip.captureSnapshot().lfsr(), "the data byte reseeds too");
        chip.write(0xE4);
        advanceTicks(chip, 1 + 32 * 3);
        int running = chip.captureSnapshot().lfsr();
        assertEquals(0x1000, running);
        chip.write(0xFF);
        assertEquals(running, chip.captureSnapshot().lfsr(), "noise attenuation leaves the LFSR alone");
        assertEquals(0xF, chip.captureSnapshot().attenuations()[3]);
        assertEquals(7, chip.captureSnapshot().latch());

        chip.write(0xA0);
        chip.write(0x3F);
        chip.write(0xBF);
        PsgChip.Snapshot tone1 = chip.captureSnapshot();
        assertEquals(0x3F0, tone1.tonePeriods()[1]);
        assertEquals(0xF, tone1.attenuations()[1]);
        assertEquals(3, tone1.latch(), "latch = tone 1 attenuation");

        chip.write(0xC1);
        chip.write(0x00);
        assertEquals(0x001, chip.captureSnapshot().tonePeriods()[2]);
        chip.write(0x40 | 0x15);
        assertEquals(0x151, chip.captureSnapshot().tonePeriods()[2], "bit 6 of a data byte is ignored");
    }

    @Test
    void onlyTheLowEightBitsOfAWriteAreSignificant() {
        PsgChip chip = new PsgChip();
        chip.write(0x190);
        assertEquals(0, chip.captureSnapshot().attenuations()[0]);
        assertEquals(1, chip.captureSnapshot().latch());
        chip.write(0x19F);
        assertEquals(0xF, chip.captureSnapshot().attenuations()[0]);
    }

    @Test
    void resetRestoresThePowerOnStateWithoutEmittingWrites() {
        List<Integer> observed = new ArrayList<>();
        PsgChip chip = new PsgChip(48000.0, PsgChip.ChipType.INTEGRATED);
        chip.setWriteObserver(new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
            }

            @Override
            public void onPsgWrite(int value) {
                observed.add(value);
            }
        });
        chip.setMute(2, true);
        chip.write(0x8C);
        chip.write(0x1A);
        chip.write(0x90);
        chip.write(0xE7);
        chip.write(0xF3);
        advanceSamples(chip, 500);
        observed.clear();

        chip.reset();

        PsgChip.Snapshot s = chip.captureSnapshot();
        assertArrayEquals(new int[] {0, 0, 0}, s.tonePeriods());
        assertArrayEquals(new int[] {0xF, 0xF, 0xF, 0xF}, s.attenuations());
        assertEquals(0, s.noiseControl());
        assertEquals(0, s.latch());
        assertEquals(0x8000, s.lfsr());
        assertArrayEquals(new boolean[] {true, true, true, true}, s.polarities());
        assertArrayEquals(new int[] {0, 0, 0, 0}, s.counters());
        assertArrayEquals(new boolean[] {false, false, true, false}, s.mutes(), "mutes survive");
        assertEquals(48000.0, s.sampleRate());
        assertEquals(List.of(), observed, "reset is not a write");

        PsgChip silence = new PsgChip();
        silence.setWriteObserver(new ChipWriteObserver() {
            @Override
            public void onYm2612Write(int port, int register, int value) {
            }

            @Override
            public void onPsgWrite(int value) {
                observed.add(value);
            }
        });
        silence.silenceAll();
        assertEquals(List.of(0x9F, 0xBF, 0xDF, 0xFF), observed, "ROM zPSGSilenceAll order");
    }

    // --- Rendering contract ----------------------------------------------

    @Test
    void renderingIsInvariantToBufferSlicingAndStaysBounded() {
        for (double rate : new double[] {44100.0, 48000.0}) {
            PsgChip whole = scriptedChip(rate);
            PsgChip sliced = scriptedChip(rate);
            int count = 200_000;
            int[] expectedLeft = new int[count];
            int[] expectedRight = new int[count];
            long start = System.nanoTime();
            whole.renderStereo(expectedLeft, expectedRight, count);
            int[] actualLeft = new int[count];
            int[] actualRight = new int[count];
            int[] oneLeft = new int[1];
            int[] oneRight = new int[1];
            for (int i = 0; i < count; i++) {
                oneLeft[0] = 0;
                oneRight[0] = 0;
                sliced.renderStereo(oneLeft, oneRight, 1);
                actualLeft[i] = oneLeft[0];
                actualRight[i] = oneRight[0];
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertArrayEquals(expectedLeft, actualLeft, "left at " + rate);
            assertArrayEquals(expectedRight, actualRight, "right at " + rate);
            assertEquals(withoutDeltaBuffer(whole.captureSnapshot()),
                    withoutDeltaBuffer(sliced.captureSnapshot()),
                    "the same ticks were executed either way");
            assertTrue(elapsedMs < 10_000,
                    "400k samples took " + elapsedMs + " ms; a growing backlog shows up as a slowdown");
        }
    }

    @Test
    void renderAccumulatesAndClampsLengthAndIgnoresNonPositiveLengths() {
        PsgChip chip = new PsgChip();
        chip.write(0xC0);
        chip.write(0x00);
        chip.write(0xD0);
        int[] left = new int[64];
        int[] right = new int[64];
        java.util.Arrays.fill(left, 1000);
        java.util.Arrays.fill(right, -1000);
        PsgChip.Snapshot before = chip.captureSnapshot();
        chip.renderStereo(left, right, 0);
        chip.renderStereo(left, right, -5);
        assertEquals(JSON.valueToTree(before), JSON.valueToTree(chip.captureSnapshot()),
                "len <= 0 does not advance time");
        chip.renderStereo(left, right, 128);
        boolean moved = false;
        for (int i = 0; i < 64; i++) {
            assertEquals(left[i] - 1000, right[i] + 1000, "same PSG contribution on both sides");
            moved |= left[i] != 1000;
        }
        assertTrue(moved, "the render added to the pre-filled arrays");
    }

    @Test
    void panningByteGatesEachSideAndPreampScalesTheLevel() {
        PsgChip both = new PsgChip();
        PsgChip rightOnly = new PsgChip();
        PsgChip half = new PsgChip();
        rightOnly.configure(100, 0x0F);
        half.configure(50, 0xFF);
        for (PsgChip chip : new PsgChip[] {both, rightOnly, half}) {
            chip.write(0xC0);
            chip.write(0x00);
            chip.write(0xD0);
        }
        int[] bothL = new int[64];
        int[] bothR = new int[64];
        int[] rightL = new int[64];
        int[] rightR = new int[64];
        int[] halfL = new int[64];
        int[] halfR = new int[64];
        both.renderStereo(bothL, bothR, 64);
        rightOnly.renderStereo(rightL, rightR, 64);
        half.renderStereo(halfL, halfR, 64);
        assertArrayEquals(bothL, bothR);
        assertArrayEquals(new int[64], rightL, "bits 7..4 clear: nothing on the left");
        assertArrayEquals(bothR, rightR);
        assertTrue(halfL[63] > 0 && Math.abs(halfL[63] * 2 - bothL[63]) <= 2, "50 % preamp halves the level");
    }

    @Test
    void mutedChannelKeepsAdvancingSoUnmutingIsSeamless() {
        PsgChip muted = tickChip(PsgChip.ChipType.INTEGRATED);
        PsgChip open = tickChip(PsgChip.ChipType.INTEGRATED);
        for (PsgChip chip : new PsgChip[] {muted, open}) {
            chip.write(0x80);
            chip.write(0x04);
            chip.write(0x90);
        }
        muted.setMute(0, true);
        int[] left = new int[300];
        int[] right = new int[300];
        muted.renderStereo(left, right, 300);
        assertArrayEquals(new int[300], left, "a muted channel contributes nothing");
        advanceTicks(open, 300);
        assertArrayEquals(open.captureSnapshot().counters(), muted.captureSnapshot().counters());
        assertArrayEquals(open.captureSnapshot().polarities(), muted.captureSnapshot().polarities());
        muted.setMute(0, false);
        muted.setMute(7, true);
        muted.setMute(-1, true);
        assertEquals(JSON.valueToTree(open.captureSnapshot()).get("mutes"),
                JSON.valueToTree(muted.captureSnapshot()).get("mutes"));
    }

    @Test
    void setSampleRateNeverThrowsAndKeepsRegisters() {
        PsgChip chip = new PsgChip();
        chip.write(0x8C);
        chip.write(0x1A);
        chip.write(0x92);
        for (double rate : new double[] {0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY,
                48000.0, 53267.0, 8000.0, 44100.0}) {
            chip.setSampleRate(rate);
            chip.renderStereo(new int[100], new int[100], 100);
        }
        PsgChip.Snapshot s = chip.captureSnapshot();
        assertEquals(0x1AC, s.tonePeriods()[0]);
        assertEquals(2, s.attenuations()[0]);
        assertEquals(1, s.latch());
        assertEquals(44100.0, s.sampleRate());
    }

    // --- Snapshot round trip ------------------------------------------------

    @Test
    void snapshotIsPureCopiesItsArraysAndRestoresBitExactlyIntoAnyChip() throws Exception {
        PsgChip source = scriptedChip(44100.0);
        source.setMute(1, true);
        advanceSamples(source, 777);
        PsgChip.Snapshot first = source.captureSnapshot();
        PsgChip.Snapshot second = source.captureSnapshot();
        assertEquals(JSON.valueToTree(first), JSON.valueToTree(second), "capture is pure");
        assertNotSame(first.mutes(), first.mutes(), "array components are copied on access");

        String json = JSON.writeValueAsString(first);
        PsgChip.Snapshot decoded = JSON.readValue(json, PsgChip.Snapshot.class);
        JsonNode tree = JSON.valueToTree(decoded);
        assertEquals(JSON.valueToTree(first), tree, "Jackson round trip is lossless");

        PsgChip target = new PsgChip(8000.0, PsgChip.ChipType.DISCRETE);
        target.configure(30, 0x11);
        target.write(0xE5);
        advanceSamples(target, 91);
        target.restoreSnapshot(decoded);

        for (PsgChip chip : new PsgChip[] {source, target}) {
            chip.write(0xE7);
            chip.write(0xF2);
            chip.write(0xA3);
            chip.write(0x05);
        }
        int[] expectedLeft = new int[4096];
        int[] expectedRight = new int[4096];
        int[] actualLeft = new int[4096];
        int[] actualRight = new int[4096];
        source.renderStereo(expectedLeft, expectedRight, 4096);
        target.renderStereo(actualLeft, actualRight, 4096);
        assertArrayEquals(expectedLeft, actualLeft);
        assertArrayEquals(expectedRight, actualRight);
        assertEquals(JSON.valueToTree(source.captureSnapshot()), JSON.valueToTree(target.captureSnapshot()));
    }

    // --- Helpers ---------------------------------------------------------------

    /** A chip rendering at the tick rate, so one output sample is one ÷16 tick. */
    private static PsgChip tickChip(PsgChip.ChipType type) {
        return new PsgChip(PsgChip.TICK_RATE_HZ, type);
    }

    private static PsgChip noiseChip(int control) {
        PsgChip chip = tickChip(PsgChip.ChipType.INTEGRATED);
        chip.write(control);
        chip.write(0xF0);
        return chip;
    }

    private static PsgChip scriptedChip(double rate) {
        PsgChip chip = new PsgChip(rate, PsgChip.ChipType.INTEGRATED);
        chip.write(0x8C);
        chip.write(0x1A);
        chip.write(0x90);
        chip.write(0xA5);
        chip.write(0x03);
        chip.write(0xB4);
        chip.write(0xC1);
        chip.write(0x00);
        chip.write(0xD8);
        chip.write(0xE4);
        chip.write(0xF2);
        return chip;
    }

    private static void advanceTicks(PsgChip chip, int ticks) {
        advanceSamples(chip, ticks);
    }

    private static void advanceSamples(PsgChip chip, int samples) {
        int[] scratch = new int[samples];
        chip.renderStereo(scratch, scratch, samples);
    }

    /** Ticks (1-based, counted from the first tick after the writes) at which channel {@code ch} flips. */
    private static List<Integer> flipTicks(PsgChip chip, int ch, int ticks) {
        List<Integer> flips = new ArrayList<>();
        boolean last = chip.captureSnapshot().polarities()[ch];
        for (int tick = 1; tick <= ticks; tick++) {
            advanceTicks(chip, 1);
            boolean now = chip.captureSnapshot().polarities()[ch];
            if (now != last) {
                flips.add(tick);
                last = now;
            }
        }
        return flips;
    }

    /** Ticks at which the LFSR state changes. */
    private static List<Integer> shiftTicks(PsgChip chip, int ticks) {
        List<Integer> shifts = new ArrayList<>();
        int last = chip.captureSnapshot().lfsr();
        for (int tick = 1; tick <= ticks; tick++) {
            advanceTicks(chip, 1);
            int now = chip.captureSnapshot().lfsr();
            if (now != last) {
                shifts.add(tick);
                last = now;
            }
        }
        return shifts;
    }

    /** LFSR state after each of {@code shifts} shifts, {@code ticksPerShift} ticks apart. */
    private static int[] lfsrStatesPerShift(PsgChip chip, int shifts, int ticksPerShift) {
        advanceTicks(chip, 1); // the reset counter's reload tick (§7)
        int[] states = new int[shifts];
        for (int i = 0; i < shifts; i++) {
            advanceTicks(chip, ticksPerShift);
            states[i] = chip.captureSnapshot().lfsr();
        }
        return states;
    }

    /** Generator state only; the delta buffer's capacity depends on the largest render. */
    private static JsonNode withoutDeltaBuffer(PsgChip.Snapshot snapshot) {
        com.fasterxml.jackson.databind.node.ObjectNode tree = JSON.valueToTree(snapshot);
        tree.remove("blip");
        return tree;
    }

    private static String bits(int[] states) {
        StringBuilder sb = new StringBuilder();
        for (int state : states) {
            sb.append(state & 1);
        }
        return sb.toString();
    }
}
