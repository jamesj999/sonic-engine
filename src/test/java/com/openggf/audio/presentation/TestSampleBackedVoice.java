package com.openggf.audio.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSampleBackedVoice {
    private static final int UNITY_GAIN_Q16 = 1 << 16;

    @Test
    void monoDuplicatesAndLinearInterpolationUsesFixedPointStep() {
        SampleBackedVoice voice = oneShot(new short[] {0, 1_000, 2_000}, 1, 1_000, 1_000, 0.5f, 1.0f);
        long[] accumulation = new long[8];

        voice.mixInto(accumulation, 4);

        assertArrayEquals(new long[] {0, 0, 500, 500, 1_000, 1_000, 1_500, 1_500}, accumulation);
    }

    @Test
    void stereoChannelsRemainIndependent() {
        SampleBackedVoice voice = oneShot(new short[] {100, -100, 300, -300}, 2, 1_000, 1_000, 1.0f, 1.0f);
        long[] accumulation = new long[4];

        voice.mixInto(accumulation, 2);

        assertArrayEquals(new long[] {100, -100, 300, -300}, accumulation);
    }

    @Test
    void pitchChangesSourceFrameStepWithoutChangingOutputPacketSize() {
        SampleBackedVoice voice = oneShot(new short[] {100, 200, 300, 400}, 1, 1_000, 1_000, 1.0f, 1.0f);
        voice.setPitch(2.0f, 1_000);
        long[] accumulation = new long[8];

        voice.mixInto(accumulation, 4);

        assertArrayEquals(new long[] {100, 100, 300, 300, 0, 0, 0, 0}, accumulation);
    }

    @Test
    void loopingMusicWrapsExactlyAcrossPacketBoundary() {
        SampleBackedVoice voice = SampleBackedVoice.loopingMusic(1,
                new DecodedPcm("fixture", 1, 1_000, new short[] {100, 200}), 1_000, 1.0f);
        long[] firstPacket = new long[6];
        long[] secondPacket = new long[6];

        voice.mixInto(firstPacket, 3);
        voice.mixInto(secondPacket, 3);

        assertArrayEquals(new long[] {100, 100, 200, 200, 100, 100}, firstPacket);
        assertArrayEquals(new long[] {200, 200, 100, 100, 200, 200}, secondPacket);
        assertFalse(voice.isComplete());
    }

    @Test
    void oneShotCompletesAndStopIsExplicit() {
        SampleBackedVoice voice = oneShot(new short[] {100}, 1, 1_000, 1_000, 1.0f, 1.0f);
        long[] accumulation = new long[4];

        voice.mixInto(accumulation, 2);

        assertArrayEquals(new long[] {100, 100, 0, 0}, accumulation);
        assertTrue(voice.isComplete());
        voice.stop();
        assertTrue(voice.isComplete());
    }

    @Test
    void gainIsAppliedBeforeWideAccumulation() {
        SampleBackedVoice voice = oneShot(new short[] {1_000}, 1, 1_000, 1_000, 1.0f, 1.0f);
        voice.setGain(0.5f);
        long[] accumulation = new long[] {50, -50};

        voice.mixInto(accumulation, 1);

        assertArrayEquals(new long[] {550, 450}, accumulation);
    }

    @Test
    void snapshotRestoreReproducesTheNextPacketBitExactly() {
        DecodedPcm pcm = new DecodedPcm("fixture", 1, 1_000, new short[] {0, 1_000, 2_000, 3_000, 4_000});
        SampleBackedVoice voice = SampleBackedVoice.oneShot(1, 0, pcm, 1_000, 0.5f, 1.0f);
        voice.mixInto(new long[6], 3);
        PresentationVoiceSnapshot.Sample snapshot = (PresentationVoiceSnapshot.Sample) voice.snapshot();
        long[] original = new long[8];
        voice.mixInto(original, 4);

        SampleBackedVoice restored = SampleBackedVoice.oneShot(1, 0, pcm, 1_000, 1.0f, 1.0f);
        restored.restore(snapshot);
        long[] replayed = new long[8];
        restored.mixInto(replayed, 4);

        assertArrayEquals(original, replayed);
    }

    @Test
    void unsignedRawSegaPcmUsesExistingYmDacGain() {
        SampleBackedVoice voice = SampleBackedVoice.unsigned8Mono(1, 0, "sega",
                new byte[] {0, (byte) 0x80, (byte) 0xFF}, 48_000, 48_000, 0.25f);
        long[] accumulation = new long[6];

        voice.mixInto(accumulation, 3);

        assertArrayEquals(new long[] {-8_192, -8_192, 0, 0, 8_128, 8_128}, accumulation);
    }

    @Test
    void rawPcmRegistrationCopiesCallerBytesAndKeepsStableAssetIdentity() {
        byte[] callerBytes = new byte[] {(byte) 128};
        SampleBackedVoice voice = SampleBackedVoice.unsigned8Mono(1, 0, "sega", callerBytes,
                48_000, 48_000, 0.25f);
        callerBytes[0] = 0;
        PresentationVoiceSnapshot.Sample snapshot = (PresentationVoiceSnapshot.Sample) voice.snapshot();
        long[] accumulation = new long[2];
        voice.mixInto(accumulation, 1);

        assertArrayEquals(new long[] {0, 0}, accumulation);
        assertTrue(snapshot.assetId().equals("sega"));
    }

    @Test
    void terminalLoopingSnapshotNormalizesToTheFirstFrameBeforeMixing() {
        DecodedPcm pcm = new DecodedPcm("fixture", 1, 1_000, new short[] {100, 200});
        SampleBackedVoice voice = SampleBackedVoice.loopingMusic(1, pcm, 1_000, 1.0f);
        voice.restore(new PresentationVoiceSnapshot.Sample(1, 0, "fixture", null, null,
                2L << 32, 1L << 32, UNITY_GAIN_Q16, true, false));
        long[] accumulation = new long[4];

        voice.mixInto(accumulation, 2);

        assertArrayEquals(new long[] {100, 100, 200, 200}, accumulation);
        assertFalse(voice.isComplete());
    }

    private static SampleBackedVoice oneShot(short[] samples, int channels, int sourceRate, int outputRate,
                                             float pitch, float gain) {
        return SampleBackedVoice.oneShot(1, 0, new DecodedPcm("fixture", channels, sourceRate, samples),
                outputRate, pitch, gain);
    }
}
