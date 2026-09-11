package com.openggf.audio.synth;

import com.openggf.audio.smps.DacData;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestYm2612ChipSnapshot {
    @Test
    void restoreSnapshotProducesBitExactFutureSamplesForFmDacAndResampler() {
        Ym2612Chip uninterrupted = configuredChip();
        Ym2612Chip restored = configuredChip();

        prime(uninterrupted);
        prime(restored);

        int[] ignoredLeft = new int[41];
        int[] ignoredRight = new int[41];
        uninterrupted.renderStereo(ignoredLeft, ignoredRight, ignoredLeft.length);
        restored.renderStereo(new int[41], new int[41], 41);

        Ym2612Chip.Snapshot snapshot = uninterrupted.captureSnapshot();
        perturb(uninterrupted);
        int[] expectedLeft = new int[128];
        int[] expectedRight = new int[128];
        uninterrupted.renderStereo(expectedLeft, expectedRight, expectedLeft.length);

        perturb(restored);
        restored.restoreSnapshot(snapshot);
        Ym2612Chip.Snapshot restoredState = restored.captureSnapshot();
        assertEquals(snapshot.currentDacSampleId(),
                restoredState.currentDacSampleId());
        assertEquals(snapshot.dacPos(), restoredState.dacPos());
        perturb(restored);
        int[] actualLeft = new int[128];
        int[] actualRight = new int[128];
        restored.renderStereo(actualLeft, actualRight, actualLeft.length);

        assertArrayEquals(expectedLeft, actualLeft);
        assertArrayEquals(expectedRight, actualRight);
    }

    private static Ym2612Chip configuredChip() {
        Ym2612Chip chip = new Ym2612Chip();
        chip.setOutputSampleRate(44100.0);
        chip.setDacData(dacData());
        chip.setDacInterpolate(true);
        return chip;
    }

    private static DacData dacData() {
        return new DacData(
                Map.of(1, new byte[] { 0, 24, 64, 127, (byte) 255, (byte) 196, 96, 32, 8, 0 }),
                Map.of(0x81, new DacData.DacEntry(1, 4)),
                295);
    }

    private static void prime(Ym2612Chip chip) {
        chip.write(0, 0x22, 0x0B);
        chip.write(0, 0x2B, 0x80);
        chip.setInstrument(0, new byte[] {
                0x32,
                0x71, 0x0D, 0x33, 0x01,
                0x5F, 0x5F, 0x5F, 0x5F,
                0x14, 0x0E, 0x0E, 0x0E,
                0x08, 0x08, 0x08, 0x08,
                0x0F, 0x0F, 0x0F, 0x0F,
                0x1B, 0x16, 0x1F, 0x00
        });
        chip.write(0, 0xA4, 0x22);
        chip.write(0, 0xA0, 0x69);
        chip.write(0, 0xB4, 0xC7);
        chip.write(0, 0x28, 0xF0);
        chip.playDac(0x81);
    }

    private static void perturb(Ym2612Chip chip) {
        chip.write(0, 0x2A, 0x5A);
        chip.write(0, 0x40, 0x23);
        chip.write(0, 0x28, 0x00);
        chip.write(0, 0x28, 0xF0);
        chip.playDac(0x81);
    }
}
