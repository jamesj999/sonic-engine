package com.openggf.audio.synth;

import com.openggf.audio.synth.fast.FastYm2612Dsp;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Release contracts independent of the mono fidelity oracle. */
class TestFastFmReleaseContracts {
    private static FastYm2612Chip tone() {
        FastYm2612Chip chip = new FastYm2612Chip(new FastYm2612Dsp());
        chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
        for (int operator = 0; operator < 4; operator++) chip.write(0, 0x40 + operator * 4, 0x7f);
        chip.write(0, 0x30, 1);
        chip.write(0, 0x40, 0);
        chip.write(0, 0x50, 0x1f);
        chip.write(0, 0x60, 0);
        chip.write(0, 0x70, 0);
        chip.write(0, 0x80, 0x0f);
        chip.write(0, 0xa4, 0x22);
        chip.write(0, 0xa0, 0x69);
        chip.write(0, 0xb0, 7);
        chip.write(0, 0xb4, 0x80);
        chip.write(0, 0x28, 0x10);
        render(chip, 64);
        return chip;
    }

    private static int[][] render(FastYm2612Chip chip, int count) {
        int[][] result = new int[2][count];
        chip.renderStereo(result[0], result[1], count);
        return result;
    }

    @Test
    void realDspPanningAndPendingWriteSnapshotReplayAreExact() {
        FastYm2612Chip chip = tone();
        int[][] left = render(chip, 256);
        assertTrue(Arrays.stream(left[0]).anyMatch(value -> value != 0));
        assertArrayEquals(new int[256], left[1]);
        chip.write(0, 0xb4, 0x40);
        FmChip.Snapshot snapshot = chip.captureSnapshot();
        int[][] expected = render(chip, 256);
        chip.restoreSnapshot(snapshot);
        int[][] replayed = render(chip, 256);
        assertArrayEquals(expected[0], replayed[0]);
        assertArrayEquals(expected[1], replayed[1]);
        assertTrue(Arrays.stream(expected[1]).anyMatch(value -> value != 0));
        assertArrayEquals(new int[128], Arrays.copyOfRange(expected[0], 128, 256));
    }

    @Test
    void registerDiagnosticsPreserveBanksAndFrameClockAcrossRewindAndRollback() {
        FastYm2612Chip chip = tone();
        java.util.List<String> strobes = new java.util.ArrayList<>();
        chip.setWriteObserver(new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) { }
            @Override public void onPsgWrite(int value) { }
            @Override public boolean observesPhysicalWrites() { return true; }
            @Override public void onYm2612BusWrite(long cycle, int port, int value,
                    PhysicalWriteOrigin origin) {
                strobes.add(cycle + ":" + port + ":" + value);
            }
        });
        chip.write(1, 0xb4, 0x40);
        FmChip.Snapshot snapshot = chip.captureSnapshot();
        FmChip.MutationBackup backup = chip.createMutationBackup();
        chip.captureMutation(backup);
        render(chip, 4);
        java.util.List<String> expected = java.util.List.of("1536:2:180", "1536:3:64");
        assertEquals(expected, strobes, "bank 1 must report ports 2/3 at 64 * 24 chip cycles");
        strobes.clear();
        chip.restoreSnapshot(snapshot);
        render(chip, 4);
        assertEquals(expected, strobes);
        strobes.clear();
        chip.restoreMutation(backup);
        render(chip, 4);
        assertEquals(expected, strobes);
    }

    @Test
    void exposedDspCannotMutateCapturedRewindState() {
        FastYm2612Chip chip = tone();
        FastYm2612Chip.Snapshot snapshot = (FastYm2612Chip.Snapshot) chip.captureSnapshot();
        int[][] expected = render(chip, 256);
        snapshot.dsp().reset();
        chip.restoreSnapshot(snapshot);
        int[][] restored = render(chip, 256);
        assertArrayEquals(expected[0], restored[0], "public snapshot access must not corrupt a later rewind");
        assertArrayEquals(expected[1], restored[1]);
    }
}
