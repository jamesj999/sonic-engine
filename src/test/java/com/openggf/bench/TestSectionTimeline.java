package com.openggf.bench;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSectionTimeline {

    @Test
    void recordsRawPerFrameSamplesPerSection() {
        SectionTimeline timeline = new SectionTimeline(3);

        timeline.frameSample("physics", 100);
        timeline.frameSample("objects", 200);
        timeline.frameComplete(300);

        timeline.frameSample("physics", 110);
        timeline.frameSample("objects", 210);
        timeline.frameComplete(320);

        assertEquals(2, timeline.frameCount());
        assertEquals(List.of("physics", "objects"), timeline.sectionNames());
        assertEquals(100, timeline.rawSection("physics")[0]);
        assertEquals(110, timeline.rawSection("physics")[1]);
        assertEquals(320, timeline.rawFrameNanos()[1]);
    }

    @Test
    void repeatedSamplesWithinOneFrameAccumulate() {
        SectionTimeline timeline = new SectionTimeline(1);

        timeline.frameSample("objects", 40);
        timeline.frameSample("objects", 60);
        timeline.frameComplete(100);

        assertEquals(100, timeline.rawSection("objects")[0]);
    }

    @Test
    void sectionSeenLateReadsZeroForEarlierFrames() {
        SectionTimeline timeline = new SectionTimeline(3);

        timeline.frameSample("physics", 100);
        timeline.frameComplete(100);

        // A section that only starts costing time partway through a run
        // contributed nothing before that, and must summarise as such rather
        // than being averaged over only the frames it appeared in.
        timeline.frameSample("rewind.capture", 500);
        timeline.frameComplete(600);

        assertEquals(0, timeline.rawSection("rewind.capture")[0]);
        assertEquals(500, timeline.rawSection("rewind.capture")[1]);
        assertEquals(2, timeline.timing("rewind.capture").frames());
    }

    @Test
    void overflowIsReportedRatherThanSilentlyTruncating() {
        SectionTimeline timeline = new SectionTimeline(2);

        timeline.frameSample("physics", 10);
        timeline.frameComplete(10);
        timeline.frameSample("physics", 20);
        timeline.frameComplete(20);
        assertFalse(timeline.overflowed());

        timeline.frameSample("physics", 30);
        timeline.frameComplete(30);

        assertTrue(timeline.overflowed(), "a run past capacity must be flagged");
        assertEquals(2, timeline.frameCount());
        assertEquals(20, timeline.rawSection("physics")[1], "existing samples stay intact");
    }

    @Test
    void allTimingsAreOrderedByHeaviestTotal() {
        SectionTimeline timeline = new SectionTimeline(2);

        timeline.frameSample("physics", 10);
        timeline.frameSample("objects", 90);
        timeline.frameComplete(100);
        timeline.frameSample("physics", 10);
        timeline.frameSample("objects", 90);
        timeline.frameComplete(100);

        List<SectionTiming> timings = timeline.allTimings();
        assertEquals("objects", timings.get(0).name());
        assertEquals("physics", timings.get(1).name());
    }

    @Test
    void unknownSectionIsAnErrorNotAnEmptySeries() {
        SectionTimeline timeline = new SectionTimeline(1);
        timeline.frameComplete(10);

        assertThrows(IllegalArgumentException.class, () -> timeline.rawSection("nope"));
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new SectionTimeline(0));
    }
}
