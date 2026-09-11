package com.openggf.tests.trace;

import com.openggf.trace.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSyntheticV3Fixture {

    @Test
    void parsesS3kV5MetadataAndExercisesLagCounter(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("metadata.json"), """
                {"game":"s3k","zone":"aiz","zone_id":0,"act":1,
                 "bk2_frame_offset":0,"trace_frame_count":2,
                 "start_x":"0000","start_y":"0000",
                 "recorder":"native-bizhawk-headless","recorder_version":"3.0",
                 "trace_schema":5}
                """);
        Files.writeString(dir.resolve("physics.csv"), TraceV5TestFixture.LEVEL_HEADER + "\n"
                + counterRow(0, 1, 1, 0) + "\n"
                + counterRow(1, 1, 2, 1) + "\n");

        TraceData data = TraceData.load(dir);

        assertEquals(5, data.metadata().traceSchema());
        assertEquals("s3k", data.metadata().game());

        TraceFrame f0 = data.getFrame(0);
        TraceFrame f1 = data.getFrame(1);

        assertEquals(f0.gameplayFrameCounter(), f1.gameplayFrameCounter(), "frame 1 is a lag frame");
        assertTrue(f1.vblankCounter() > f0.vblankCounter());
        assertEquals(0, f0.lagCounter());
        assertTrue(f1.lagCounter() > 0);

        TraceExecutionPhase phase = TraceExecutionModel.forGame("s3k").phaseFor(f0, f1);
        assertEquals(TraceExecutionPhase.VBLANK_ONLY, phase);
    }

    private static String counterRow(int frame, int gameplay, int vblank, int lag) {
        String[] fields = TraceV5TestFixture.levelRow(frame).split(",", -1);
        fields[5] = Integer.toString(gameplay);
        fields[6] = Integer.toString(vblank);
        fields[7] = Integer.toString(lag);
        return String.join(",", fields);
    }
}
