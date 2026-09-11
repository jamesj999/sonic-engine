package com.openggf.tests.trace;

import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.trace.TraceData;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kAizInputProvenance {
    private static final Path TRACE_DIR =
            Path.of("src/test/resources/traces/s3k/aiz1_to_hcz_fullrun");
    private static final Path BK2 =
            TRACE_DIR.resolve("s3-aiz1-2-sonictails.bk2");

    @Test
    void everyTraceInputComesFromCanonicalBk2Row() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        Bk2Movie movie = new Bk2MovieLoader().load(BK2);
        int offset = trace.metadata().bk2FrameOffset();

        int firstKnownEdge = 161;
        int firstKnownBk2Row = offset + firstKnownEdge;
        assertEquals(672, firstKnownBk2Row);
        assertEquals(0x0004, movie.getFrame(firstKnownBk2Row).p1InputMask(),
                "canonical BK2 row 672 is the first known input edge");
        assertEquals(0x0004, trace.getFrame(firstKnownEdge).input(),
                "trace frame 161 must preserve the canonical BK2 input edge");

        assertTrue(trace.frameCount() + offset <= movie.getFrameCount(),
                "BK2 must contain every canonical source row");
        for (int row = 0; row < trace.frameCount(); row++) {
            assertEquals(movie.getFrame(offset + row).p1InputMask(),
                    trace.getFrame(row).input(),
                    "trace input provenance at row " + row);
        }
    }
}
