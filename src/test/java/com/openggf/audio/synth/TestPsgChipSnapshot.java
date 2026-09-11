package com.openggf.audio.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TestPsgChipSnapshot {

    @Test
    void restoreSnapshotProducesBitExactFutureSamples() {
        PsgChip uninterrupted = configuredChip();
        PsgChip restored = configuredChip();

        prime(uninterrupted);
        prime(restored);

        int[] ignoredLeft = new int[37];
        int[] ignoredRight = new int[37];
        uninterrupted.renderStereo(ignoredLeft, ignoredRight, ignoredLeft.length);
        restored.renderStereo(new int[37], new int[37], 37);

        PsgChip.Snapshot snapshot = uninterrupted.captureSnapshot();
        uninterrupted.write(0xE7);
        uninterrupted.write(0xF2);
        int[] expectedLeft = new int[96];
        int[] expectedRight = new int[96];
        uninterrupted.renderStereo(expectedLeft, expectedRight, expectedLeft.length);

        restored.write(0xE7);
        restored.write(0xF2);
        restored.restoreSnapshot(snapshot);
        restored.write(0xE7);
        restored.write(0xF2);
        int[] actualLeft = new int[96];
        int[] actualRight = new int[96];
        restored.renderStereo(actualLeft, actualRight, actualLeft.length);

        assertArrayEquals(expectedLeft, actualLeft);
        assertArrayEquals(expectedRight, actualRight);
    }

    private static PsgChip configuredChip() {
        PsgChip chip = new PsgChip(48000.0, PsgChip.ChipType.INTEGRATED);
        return chip;
    }

    private static void prime(PsgChip chip) {
        chip.write(0x80 | 0x04);
        chip.write(0x12);
        chip.write(0x90 | 0x02);
        chip.write(0xA0 | 0x06);
        chip.write(0x20);
        chip.write(0xB0 | 0x03);
        chip.write(0xE4);
        chip.write(0xF0 | 0x04);
    }
}
