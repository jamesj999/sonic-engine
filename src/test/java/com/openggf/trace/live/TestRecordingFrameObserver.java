package com.openggf.trace.live;

import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TestRecordingFrameObserver {

    @Test
    void recordsEachAcceptedFrame() {
        RecordingFrameObserver obs = new RecordingFrameObserver();
        FrameComparison a = dummyComparison(1);
        FrameComparison b = dummyComparison(2);
        obs.accept(a);
        obs.accept(b);
        assertEquals(2, obs.frames().size());
        assertSame(a, obs.frames().get(0));
        assertSame(b, obs.frames().get(1));
    }

    @Test
    void framesAccessorReturnsImmutableSnapshot() {
        RecordingFrameObserver obs = new RecordingFrameObserver();
        obs.accept(dummyComparison(1));
        var view = obs.frames();
        assertThrows(UnsupportedOperationException.class,
                () -> view.add(dummyComparison(2)),
                "frames() should return an immutable view");
    }

    @Test
    void diffEmptyWhenIdentical() {
        RecordingFrameObserver lhs = new RecordingFrameObserver();
        RecordingFrameObserver rhs = new RecordingFrameObserver();
        FrameComparison fa = dummyComparison(1);
        lhs.accept(fa);
        rhs.accept(fa);
        Optional<FrameComparison> diff = lhs.diff(rhs);
        assertTrue(diff.isEmpty(), "Identical sequences must produce no diff");
    }

    @Test
    void diffReportsFirstDifferingFrame() {
        RecordingFrameObserver lhs = new RecordingFrameObserver();
        RecordingFrameObserver rhs = new RecordingFrameObserver();
        FrameComparison fa = dummyComparison(1);
        FrameComparison fb1 = dummyComparison(2);
        FrameComparison fb2 = dummyComparison(99);   // distinguishable from fb1
        lhs.accept(fa); lhs.accept(fb1);
        rhs.accept(fa); rhs.accept(fb2);
        Optional<FrameComparison> diff = lhs.diff(rhs);
        assertTrue(diff.isPresent(), "Differing sequences must produce a diff");
        assertSame(fb1, diff.get(),
                "Diff must surface the first frame from THIS observer that differs");
    }

    @Test
    void diffSurfacesLengthMismatch() {
        RecordingFrameObserver lhs = new RecordingFrameObserver();
        RecordingFrameObserver rhs = new RecordingFrameObserver();
        FrameComparison fa = dummyComparison(1);
        lhs.accept(fa); lhs.accept(dummyComparison(2));
        rhs.accept(fa);
        // lhs has one more frame than rhs
        Optional<FrameComparison> diff = lhs.diff(rhs);
        assertTrue(diff.isPresent(),
                "Different lengths must produce a diff at the divergence point");
    }

    /**
     * Build a minimal FrameComparison instance for testing. Each frame is
     * a record with (frame, fields, romDiagnostics, engineDiagnostics).
     * The frame number is set to seed, and a single field with seed-based
     * value is added to ensure distinct comparisons by seed.
     */
    private static FrameComparison dummyComparison(int seed) {
        FieldComparison fc = new FieldComparison(
                "test_field_" + seed,
                String.valueOf(seed),
                String.valueOf(seed),
                Severity.MATCH,
                0
        );
        return new FrameComparison(seed, Map.of("test_field_" + seed, fc));
    }
}
