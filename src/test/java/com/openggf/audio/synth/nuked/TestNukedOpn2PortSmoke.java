package com.openggf.audio.synth.nuked;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Port-consistency pin for {@link NukedOpn2}.
 *
 * <p>Drives one fixed register script (a single-operator voice on channel 1,
 * algorithm 7, key-on) and folds every MOL/MOR pin value into a 64-bit FNV-1a
 * checksum. The expected values were taken from the pinned C build on this
 * same script and pinned here so any later edit that changes the output is
 * caught; the bit-exactness stage re-confirms them independently against the
 * pinned C build ({@code tools/audio/nuked-opn2/PIN.md}). Everything a C
 * harness needs to reproduce the number is spelled out in {@link #runScript}.
 */
class TestNukedOpn2PortSmoke {

    private static final int FRAMES = 4096;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /** FNV-1a over (mol &amp; 0xffff, mor &amp; 0xffff) for every clock, YM2612 + readmode. */
    private static final long EXPECTED_CHECKSUM_YM2612 = 0xba1b4bba3bcb91bdL;
    /** Same script with chip type 0 (YM3438 output stage). */
    private static final long EXPECTED_CHECKSUM_YM3438 = 0x05a1b4e168ee0315L;

    /** Register script shared with the C harness: (port, register, value) triples, in order. */
    private static final int[][] SCRIPT = {
        { 0, 0x22, 0x00 }, /* LFO off */
        { 0, 0x27, 0x00 }, /* timers off, ch3 normal */
        { 0, 0x28, 0x00 }, /* key off ch1 */
        { 0, 0xB0, 0x07 }, /* algorithm 7, feedback 0 */
        { 0, 0xB4, 0xC0 }, /* pan L+R, no AMS/PMS */
        { 0, 0x30, 0x01 }, { 0, 0x34, 0x01 }, { 0, 0x38, 0x01 }, { 0, 0x3C, 0x01 }, /* DT 0, MUL 1 */
        { 0, 0x40, 0x00 }, { 0, 0x44, 0x7F }, { 0, 0x48, 0x7F }, { 0, 0x4C, 0x7F }, /* TL: OP1 full, rest silent */
        { 0, 0x50, 0x1F }, { 0, 0x54, 0x1F }, { 0, 0x58, 0x1F }, { 0, 0x5C, 0x1F }, /* AR 31 */
        { 0, 0x60, 0x00 }, { 0, 0x64, 0x00 }, { 0, 0x68, 0x00 }, { 0, 0x6C, 0x00 }, /* DR 0 */
        { 0, 0x70, 0x00 }, { 0, 0x74, 0x00 }, { 0, 0x78, 0x00 }, { 0, 0x7C, 0x00 }, /* SR 0 */
        { 0, 0x80, 0x0F }, { 0, 0x84, 0x0F }, { 0, 0x88, 0x0F }, { 0, 0x8C, 0x0F }, /* SL 0, RR 15 */
        { 0, 0x90, 0x00 }, { 0, 0x94, 0x00 }, { 0, 0x98, 0x00 }, { 0, 0x9C, 0x00 }, /* SSG-EG off */
        { 0, 0xA4, 0x22 }, /* block 4, fnum high */
        { 0, 0xA0, 0x69 }, /* fnum 0x269 */
        { 0, 0x28, 0xF0 }, /* key on ch1, all operators */
    };

    private static final class Run {
        long checksum = FNV_OFFSET;
        final int[] frameLeft = new int[FRAMES];
        final int[] frameRight = new int[FRAMES];
        final int[] buffer = new int[2];

        void clock(NukedOpn2 chip) {
            chip.clock(buffer);
            checksum ^= buffer[0] & 0xffff;
            checksum *= FNV_PRIME;
            checksum ^= buffer[1] & 0xffff;
            checksum *= FNV_PRIME;
        }
    }

    /**
     * The script: address strobe, 4 clocks, data strobe, 28 clocks (the busy
     * window) per register; then {@link #FRAMES} frames of 24 clocks each,
     * summing the 24 pin values of every frame into one sample per side.
     */
    private static Run runScript(int chipType) {
        NukedOpn2 chip = new NukedOpn2();
        chip.setChipType(chipType);
        Run run = new Run();
        for (int[] entry : SCRIPT) {
            chip.write(entry[0] * 2, entry[1]);
            for (int i = 0; i < 4; i++) {
                run.clock(chip);
            }
            chip.write(entry[0] * 2 + 1, entry[2]);
            for (int i = 0; i < 28; i++) {
                run.clock(chip);
            }
        }
        for (int frame = 0; frame < FRAMES; frame++) {
            int left = 0;
            int right = 0;
            for (int cycle = 0; cycle < NukedOpn2.CYCLES_PER_FRAME; cycle++) {
                run.clock(chip);
                left += run.buffer[0];
                right += run.buffer[1];
            }
            run.frameLeft[frame] = left;
            run.frameRight[frame] = right;
        }
        return run;
    }

    @Test
    void keyedOnVoiceProducesNonZeroOutputAndPinnedChecksumInYm2612Mode() {
        Run run = runScript(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);

        int distinct = 0;
        int previous = Integer.MIN_VALUE;
        int peak = 0;
        for (int i = 0; i < FRAMES; i++) {
            if (run.frameLeft[i] != previous) {
                distinct++;
                previous = run.frameLeft[i];
            }
            peak = Math.max(peak, Math.abs(run.frameLeft[i]));
            assertEquals(run.frameLeft[i], run.frameRight[i], "pan L+R must give identical sides at frame " + i);
        }
        assertTrue(distinct > 100, "expected an oscillating waveform, saw " + distinct + " distinct sample runs");
        assertTrue(peak > 100, "expected audible amplitude, peak was " + peak);

        assertEquals(EXPECTED_CHECKSUM_YM2612, run.checksum,
                () -> String.format("YM2612 checksum drifted: 0x%016xL", run.checksum));
    }

    @Test
    void ym3438OutputStageIsDistinctAndPinned() {
        Run run = runScript(0);
        assertEquals(EXPECTED_CHECKSUM_YM3438, run.checksum,
                () -> String.format("YM3438 checksum drifted: 0x%016xL", run.checksum));
        assertNotEquals(EXPECTED_CHECKSUM_YM2612, run.checksum);
    }

    @Test
    void outputIsDeterministicAcrossInstances() {
        Run first = runScript(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);
        Run second = runScript(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);
        assertEquals(first.checksum, second.checksum);
        assertArrayEquals(first.frameLeft, second.frameLeft);
    }

    @Test
    void resetChipInYm3438ModeIsSilent() {
        NukedOpn2 chip = new NukedOpn2();
        chip.setChipType(0);
        int[] buffer = new int[2];
        for (int i = 0; i < 24 * 64; i++) {
            chip.clock(buffer);
            assertEquals(0, buffer[0]);
            assertEquals(0, buffer[1]);
        }
    }

    @Test
    void stateCopyResumesBitExactly() {
        NukedOpn2 live = new NukedOpn2();
        live.setChipType(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);
        int[] buffer = new int[2];
        for (int[] entry : SCRIPT) {
            live.write(entry[0] * 2, entry[1]);
            for (int i = 0; i < 4; i++) {
                live.clock(buffer);
            }
            live.write(entry[0] * 2 + 1, entry[2]);
            for (int i = 0; i < 28; i++) {
                live.clock(buffer);
            }
        }
        for (int i = 0; i < 24 * 200; i++) {
            live.clock(buffer);
        }
        NukedOpn2State snapshot = live.state().copy();

        int[] expected = new int[24 * 500 * 2];
        for (int i = 0; i < expected.length; i += 2) {
            live.clock(buffer);
            expected[i] = buffer[0];
            expected[i + 1] = buffer[1];
        }

        NukedOpn2 restored = new NukedOpn2();
        restored.setChipType(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);
        restored.state().copyFrom(snapshot);
        int[] actual = new int[expected.length];
        for (int i = 0; i < actual.length; i += 2) {
            restored.clock(buffer);
            actual[i] = buffer[0];
            actual[i + 1] = buffer[1];
        }
        assertArrayEquals(expected, actual);
    }

    @Test
    void statusReadReportsBusyAfterDataWriteAndClearsAfterThirtyTwoCycles() {
        NukedOpn2 chip = new NukedOpn2();
        chip.setChipType(NukedOpn2.MODE_YM2612 | NukedOpn2.MODE_READMODE);
        int[] buffer = new int[2];
        chip.write(0, 0x22);
        chip.clock(buffer);
        chip.write(1, 0x00);
        chip.clock(buffer);
        chip.clock(buffer);
        assertEquals(0x80, chip.read(0) & 0x80, "busy after a data strobe");
        for (int i = 0; i < 32; i++) {
            chip.clock(buffer);
        }
        assertEquals(0, chip.read(0) & 0x80, "busy released 32 cycles after the data strobe");
    }
}
