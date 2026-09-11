package com.openggf.audio.synth;

import com.openggf.audio.smps.DacData;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TestVirtualSynthesizerSnapshot {
    @Test
    void restoreSnapshotProducesBitExactMixedFutureFrames() {
        VirtualSynthesizer uninterrupted = configuredSynth();
        VirtualSynthesizer restored = configuredSynth();

        prime(uninterrupted);
        prime(restored);

        uninterrupted.renderFrames(new short[82], 0, 41);
        restored.renderFrames(new short[82], 0, 41);

        VirtualSynthesizer.Snapshot snapshot = uninterrupted.captureSynthSnapshot();
        perturb(uninterrupted);
        short[] expected = new short[256];
        uninterrupted.renderFrames(expected, 0, expected.length / 2);

        perturb(restored);
        restored.restoreSynthSnapshot(snapshot);
        perturb(restored);
        short[] actual = new short[256];
        restored.renderFrames(actual, 0, actual.length / 2);

        assertArrayEquals(expected, actual);
    }

    private static VirtualSynthesizer configuredSynth() {
        VirtualSynthesizer synth = new VirtualSynthesizer(44100.0);
        synth.setDacData(dacData());
        synth.setDacInterpolate(true);
        return synth;
    }

    private static DacData dacData() {
        return new DacData(
                Map.of(1, new byte[] { 0, 24, 64, 127, (byte) 255, (byte) 196, 96, 32, 8, 0 }),
                Map.of(0x81, new DacData.DacEntry(1, 4)),
                295);
    }

    private static void prime(VirtualSynthesizer synth) {
        synth.writeFm(synth, 0, 0x22, 0x0B);
        synth.writeFm(synth, 0, 0x2B, 0x80);
        synth.setInstrument(synth, 0, new byte[] {
                0x32,
                0x71, 0x0D, 0x33, 0x01,
                0x5F, 0x5F, 0x5F, 0x5F,
                0x14, 0x0E, 0x0E, 0x0E,
                0x08, 0x08, 0x08, 0x08,
                0x0F, 0x0F, 0x0F, 0x0F,
                0x1B, 0x16, 0x1F, 0x00
        });
        synth.writeFm(synth, 0, 0xA4, 0x22);
        synth.writeFm(synth, 0, 0xA0, 0x69);
        synth.writeFm(synth, 0, 0xB4, 0xC7);
        synth.writeFm(synth, 0, 0x28, 0xF0);
        synth.playDac(synth, 0x81);
        synth.writePsg(synth, 0x80 | 0x04);
        synth.writePsg(synth, 0x12);
        synth.writePsg(synth, 0x90 | 0x02);
        synth.writePsg(synth, 0xE4);
        synth.writePsg(synth, 0xF0 | 0x04);
    }

    private static void perturb(VirtualSynthesizer synth) {
        synth.writeFm(synth, 0, 0x2A, 0x5A);
        synth.writeFm(synth, 0, 0x40, 0x23);
        synth.writePsg(synth, 0xE7);
        synth.writePsg(synth, 0xF2);
        synth.playDac(synth, 0x81);
    }
}
