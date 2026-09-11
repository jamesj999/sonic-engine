package com.openggf.tests.trace.s2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S2SpecialStageTrackDurationMappingTest {

    @Test
    void rawZeroAtZeroSpeedMeansUninitializedElapsedZero() {
        assertEquals(0, AbstractS2SpecialStageTraceReplayTest.mapTrackDurationElapsed(0, 0));
    }

    @Test
    void mapsRomCountdownToEngineElapsedCounter() {
        assertEquals(0, AbstractS2SpecialStageTraceReplayTest.mapTrackDurationElapsed(12, 5));
        assertEquals(1, AbstractS2SpecialStageTraceReplayTest.mapTrackDurationElapsed(12, 4));
        assertEquals(4, AbstractS2SpecialStageTraceReplayTest.mapTrackDurationElapsed(12, 1));
    }
}
