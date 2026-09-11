package com.openggf.tests.trace;

import com.openggf.trace.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS2SyntheticV3Fixture {

    @Test
    void parsesS2V5MetadataAndMonotonicCounters(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("metadata.json"), """
                {"game":"s2","zone":"ehz","zone_id":0,"act":1,
                 "bk2_frame_offset":0,"trace_frame_count":2,
                 "start_x":"0000","start_y":"0000",
                 "recorder":"native-bizhawk-headless","recorder_version":"3.0",
                 "trace_schema":5}
                """);
        Files.writeString(dir.resolve("physics.csv"), TraceV5TestFixture.LEVEL_HEADER + "\n"
                + counterRow(0, 1, 1, 0) + "\n"
                + counterRow(1, 2, 2, 0) + "\n");

        TraceData data = TraceData.load(dir);

        assertEquals(5, data.metadata().traceSchema());
        assertEquals("s2", data.metadata().game());

        TraceFrame f0 = data.getFrame(0);
        TraceFrame f1 = data.getFrame(1);

        assertTrue(f1.gameplayFrameCounter() > f0.gameplayFrameCounter());
        assertTrue(f1.vblankCounter() > f0.vblankCounter());
        assertEquals(0, f0.lagCounter());
        assertEquals(0, f1.lagCounter());
    }

    private static String counterRow(int frame, int gameplay, int vblank, int lag) {
        String[] fields = TraceV5TestFixture.levelRow(frame).split(",", -1);
        fields[5] = Integer.toString(gameplay);
        fields[6] = Integer.toString(vblank);
        fields[7] = Integer.toString(lag);
        return String.join(",", fields);
    }
}
