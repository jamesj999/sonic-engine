package com.openggf.debug;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample sink is the seam the offline benchmark harness hangs off, so what
 * matters here is that it sees every frame's raw numbers exactly once, and that
 * installing it changes nothing about the profiler's own behaviour.
 */
class TestPerformanceProfilerSampleSink {

    private PerformanceProfiler profiler;
    private RecordingSink sink;

    @BeforeEach
    void setUp() {
        profiler = PerformanceProfiler.getInstance();
        profiler.reset();
        profiler.setEnabled(true);
        profiler.setAllocationTrackingEnabled(false);
        sink = new RecordingSink();
    }

    @AfterEach
    void tearDown() {
        profiler.setSampleSink(null);
        profiler.setAllocationTrackingEnabled(true);
        profiler.reset();
    }

    @Test
    void everyClosedFrameIsReportedOnceWithItsSections() {
        profiler.setSampleSink(sink);

        for (int frame = 0; frame < 3; frame++) {
            profiler.beginFrame();
            profiler.beginSection("physics");
            profiler.endSection("physics");
            profiler.beginSection("objects");
            profiler.endSection("objects");
            profiler.endFrame();
        }

        assertEquals(3, sink.frames.size());
        assertEquals(3, sink.sectionCounts.get("physics"));
        assertEquals(3, sink.sectionCounts.get("objects"));
    }

    @Test
    void aFrameThatIsBegunButNeverClosedReportsNothing() {
        // The benchmark loop relies on this: it opens a frame before it knows
        // whether the trace phase will actually tick gameplay, and simply
        // re-arms instead of closing when it does not. A frame leaked into the
        // samples that way would be a near-zero reading that drags every
        // percentile down.
        profiler.setSampleSink(sink);

        profiler.beginFrame();
        profiler.beginSection("physics");
        profiler.endSection("physics");

        profiler.beginFrame();
        profiler.beginSection("physics");
        profiler.endSection("physics");
        profiler.endFrame();

        assertEquals(1, sink.frames.size());
        assertEquals(1, sink.sectionCounts.get("physics"));
    }

    @Test
    void sectionsAreNotCarriedOverBetweenFrames() {
        profiler.setSampleSink(sink);

        profiler.beginFrame();
        profiler.beginSection("rewind.capture");
        profiler.endSection("rewind.capture");
        profiler.endFrame();

        profiler.beginFrame();
        profiler.beginSection("physics");
        profiler.endSection("physics");
        profiler.endFrame();

        assertEquals(1, sink.sectionCounts.get("rewind.capture"));
        assertEquals(1, sink.sectionCounts.get("physics"));
    }

    @Test
    void clearingTheSinkStopsDelivery() {
        profiler.setSampleSink(sink);
        profiler.beginFrame();
        profiler.endFrame();

        profiler.setSampleSink(null);
        profiler.beginFrame();
        profiler.endFrame();

        assertEquals(1, sink.frames.size());
    }

    @Test
    void reportedFrameTimeCoversTheWholeFrame() {
        profiler.setSampleSink(sink);

        profiler.beginFrame();
        profiler.beginSection("physics");
        busyWork();
        profiler.endSection("physics");
        profiler.endFrame();

        assertEquals(1, sink.frames.size());
        long frameNanos = sink.frames.get(0);
        long physicsNanos = sink.sectionNanos.get("physics");
        assertTrue(frameNanos > 0, "frame time must be positive");
        assertTrue(frameNanos >= physicsNanos,
                "frame " + frameNanos + "ns must cover section " + physicsNanos + "ns");
    }

    @Test
    void theProfilerStillFeedsItsOwnOverlaySnapshotWithASinkInstalled() {
        profiler.setSampleSink(sink);

        for (int frame = 0; frame < 5; frame++) {
            profiler.beginFrame();
            profiler.beginSection("physics");
            busyWork();
            profiler.endSection("physics");
            profiler.endFrame();
        }

        ProfileSnapshot snapshot = profiler.getSnapshot();
        assertTrue(snapshot.hasData(), "the sink must not displace the rolling overlay data");
        assertEquals(5, snapshot.frameCount());
    }

    @Test
    void rollingSnapshotRetainsOrderAndExpiresAbsentSections() {
        profiler.beginFrame();
        profiler.recordSectionTime("b", 1_000_000);
        profiler.recordSectionTime("a", 2_000_000);
        profiler.recordSectionTime("b", 2_000_000);
        profiler.endFrame();
        ProfileSnapshot snapshot = profiler.getSnapshot();
        assertEquals(List.of("b", "a"), new ArrayList<>(snapshot.sections().keySet()));
        assertEquals(3.0, snapshot.sections().get("b").timeMs());
        assertEquals(60.0, snapshot.sections().get("b").percentage());
        for (int i = 0; i < 60; i++) {
            profiler.beginFrame();
            profiler.endFrame();
        }
        snapshot = profiler.getSnapshot();
        assertEquals(0.0, snapshot.sections().get("b").timeMs());
        assertEquals(0.0, snapshot.totalFrameTimeMs());
        profiler.reset();
        assertTrue(profiler.getSnapshot().sections().isEmpty());
        assertEquals(0, profiler.getSnapshot().frameCount());
    }

    private static void busyWork() {
        long sum = 0;
        for (int i = 0; i < 20_000; i++) {
            sum += i;
        }
        if (sum == Long.MIN_VALUE) {
            throw new AssertionError("unreachable; keeps the loop from being optimised away");
        }
    }

    private static final class RecordingSink implements FrameSampleSink {
        private final List<Long> frames = new ArrayList<>();
        private final Map<String, Integer> sectionCounts = new LinkedHashMap<>();
        private final Map<String, Long> sectionNanos = new LinkedHashMap<>();

        @Override
        public void frameSample(String section, long nanos) {
            sectionCounts.merge(section, 1, Integer::sum);
            sectionNanos.put(section, nanos);
        }

        @Override
        public void frameComplete(long frameNanos) {
            frames.add(frameNanos);
        }
    }
}
