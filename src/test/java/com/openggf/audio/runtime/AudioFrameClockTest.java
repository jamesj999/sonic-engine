package com.openggf.audio.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioFrameClockTest {

    @Test
    void peekMatchesNextProductionButDoesNotMutate() {
        AudioFrameClock clock = new AudioFrameClock(48000, 60);
        int peek = clock.peekSamplesForNextFrame();
        // Peeking repeatedly must not change anything.
        assertEquals(peek, clock.peekSamplesForNextFrame());
        assertEquals(0, clock.totalSamplesProduced());
        // The actual production matches the prior peek.
        int produced = clock.samplesForNextFrame();
        assertEquals(peek, produced);
        assertEquals(produced, clock.totalSamplesProduced());
    }

    @Test
    void peekTracksRemainderAcrossFrames() {
        AudioFrameClock clock = new AudioFrameClock(48000, 60); // 800 exactly -> always 800
        for (int i = 0; i < 10; i++) {
            assertEquals(clock.peekSamplesForNextFrame(), clock.samplesForNextFrame());
        }
    }

    @Test
    void peekReflectsFractionalRates() {
        AudioFrameClock clock = new AudioFrameClock(44100, 60); // 735 with remainder
        long total = 0;
        for (int i = 0; i < 60; i++) {
            int peek = clock.peekSamplesForNextFrame();
            int produced = clock.samplesForNextFrame();
            assertEquals(peek, produced);
            total += produced;
        }
        assertEquals(44100, total, "one second integrates to the sample rate");
    }
}
