package com.openggf.audio.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestAudioFrameClock {
    @Test
    void divisibleSampleRateProducesSameCountEveryFrame() {
        AudioFrameClock clock = new AudioFrameClock(48_000, 60);

        assertEquals(800, clock.samplesForNextFrame());
        assertEquals(800, clock.samplesForNextFrame());
        assertEquals(1_600, clock.totalSamplesProduced());
    }

    @Test
    void nonDivisibleSampleRateCarriesRemainderAcrossFrames() {
        AudioFrameClock clock = new AudioFrameClock(44_100, 64);

        int total = 0;
        for (int i = 0; i < 64; i++) {
            total += clock.samplesForNextFrame();
        }

        assertEquals(44_100, total);
        assertEquals(44_100, clock.totalSamplesProduced());
        assertEquals(0, clock.remainder());
    }

    @Test
    void restoreReinstatesTotalAndRemainder() {
        AudioFrameClock clock = new AudioFrameClock(44_100, 64);
        clock.samplesForNextFrame();
        AudioFrameClock.Snapshot snapshot = clock.captureSnapshot();
        clock.samplesForNextFrame();

        clock.restoreSnapshot(snapshot);

        assertEquals(snapshot.totalSamplesProduced(), clock.totalSamplesProduced());
        assertEquals(snapshot.remainder(), clock.remainder());
        assertEquals(689, clock.samplesForNextFrame());
    }
}
