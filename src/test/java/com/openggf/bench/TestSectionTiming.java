package com.openggf.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSectionTiming {

    @Test
    void percentilesUseNearestRankOverObservedSamples() {
        long[] samples = new long[100];
        for (int i = 0; i < 100; i++) {
            samples[i] = i + 1;
        }

        SectionTiming timing = SectionTiming.of("frame", samples, 100);

        assertEquals(100, timing.frames());
        assertEquals(50, timing.p50Nanos());
        assertEquals(90, timing.p90Nanos());
        assertEquals(99, timing.p99Nanos());
        assertEquals(100, timing.maxNanos());
        assertEquals(5050, timing.totalNanos());
        assertEquals(50.5, timing.meanNanos(), 1e-9);
    }

    @Test
    void tailOutliersMoveThePercentilesButNotTheMedian() {
        // The case the whole harness exists for: a run that stalls occasionally
        // has an unremarkable median and a ruinous tail. A mean-only summary
        // would rank this as barely worse than a smooth run.
        long[] smooth = new long[1000];
        long[] stuttering = new long[1000];
        for (int i = 0; i < 1000; i++) {
            smooth[i] = 1000;
            stuttering[i] = 1000;
        }
        for (int i = 0; i < 5; i++) {
            stuttering[i * 100] = 40_000_000;
        }

        SectionTiming smoothTiming = SectionTiming.of("frame", smooth, 1000);
        SectionTiming stutterTiming = SectionTiming.of("frame", stuttering, 1000);

        assertEquals(smoothTiming.p50Nanos(), stutterTiming.p50Nanos(),
                "the median hides the stalls entirely");
        assertTrue(stutterTiming.p999Nanos() > smoothTiming.p999Nanos() * 1000,
                "p99.9 must expose them");
        assertEquals(40_000_000, stutterTiming.maxNanos());
    }

    @Test
    void inputArrayIsNotModified() {
        long[] samples = {5, 3, 1, 4, 2};

        SectionTiming.of("frame", samples, 5);

        // The timeline hands over its live backing store; sorting it in place
        // would silently reorder every later summary of the same section.
        assertEquals(5, samples[0]);
        assertEquals(3, samples[1]);
        assertEquals(2, samples[4]);
    }

    @Test
    void onlyTheFirstCountEntriesAreSummarised() {
        long[] samples = new long[100];
        for (int i = 0; i < 10; i++) {
            samples[i] = 10;
        }

        SectionTiming timing = SectionTiming.of("frame", samples, 10);

        assertEquals(10, timing.frames());
        assertEquals(10, timing.p50Nanos());
        assertEquals(100, timing.totalNanos());
    }

    @Test
    void emptySampleSetSummarisesToZeroRatherThanFailing() {
        SectionTiming timing = SectionTiming.of("frame", new long[10], 0);

        assertEquals(0, timing.frames());
        assertEquals(0, timing.p50Nanos());
        assertEquals(0, timing.effectiveFps());
    }

    @Test
    void effectiveFpsInvertsTheMean() {
        long[] samples = new long[10];
        java.util.Arrays.fill(samples, 16_666_667L);

        SectionTiming timing = SectionTiming.of("frame", samples, 10);

        assertEquals(60.0, timing.effectiveFps(), 0.01);
    }
}
