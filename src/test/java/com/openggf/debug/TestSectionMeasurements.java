package com.openggf.debug;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestSectionMeasurements {
    @Test
    void repeatedAndAbsentSectionsRollOutAcrossBothCollectorWindows() {
        for (int window : new int[]{60, 300}) {
            SectionMeasurements measurements = new SectionMeasurements(window);
            measurements.add("first", 200);
            measurements.add("first", 300);
            measurements.finishFrame(0);
            assertEquals(500, measurements.get(0).sum);
            measurements.clearFrame();
            for (int frame = 1; frame < window; frame++) {
                measurements.add("second", 7);
                measurements.finishFrame(frame);
                measurements.clearFrame();
            }
            assertEquals(500, measurements.get(0).sum);
            assertEquals(7L * (window - 1), measurements.get(1).sum);
            measurements.finishFrame(0);
            measurements.clearFrame();
            assertEquals(0, measurements.get(0).sum);
            assertEquals(7L * (window - 1), measurements.get(1).sum);
            measurements.add("first", 11);
            measurements.finishFrame(1);
            assertEquals(11, measurements.get(0).sum);
            assertEquals(7L * (window - 2), measurements.get(1).sum);
        }
    }

    @Test
    void discardedFramesDoNotEstablishHistoryOrderAndSamplesUseCurrentOrder() {
        SectionMeasurements measurements = new SectionMeasurements(60);
        measurements.add("discarded", 100);
        measurements.clearFrame();
        measurements.add("a", 2);
        measurements.add("b", 3);
        measurements.finishFrame(0);
        measurements.clearFrame();
        measurements.add("b", 5);
        measurements.add("a", 7);
        measurements.add("b", 11);
        List<String> samples = new ArrayList<>();
        measurements.emit(new FrameSampleSink() {
            public void frameSample(String section, long nanos) { samples.add(section + ":" + nanos); }
            public void frameComplete(long nanos) { fail("Only the profiler closes a sample frame"); }
        });
        assertEquals(List.of("b:16", "a:7"), samples);
        measurements.finishFrame(1);
        assertEquals(2, measurements.size());
        assertEquals("a", measurements.get(0).name);
        assertEquals("b", measurements.get(1).name);
        assertEquals(9, measurements.get(0).sum);
        assertEquals(19, measurements.get(1).sum);
        measurements.reset();
        assertEquals(0, measurements.size());
        measurements.add("b", 13);
        measurements.finishFrame(0);
        assertEquals(13, measurements.get(0).sum);
    }
}
