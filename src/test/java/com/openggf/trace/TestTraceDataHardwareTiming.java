package com.openggf.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceDataHardwareTiming {

    @TempDir
    Path temporaryDirectory;

    @Test
    void metadataOnlyUsesTimingFilePresenceAsTheV5AuthoritySignal() throws IOException {
        writeMetadata(2);
        Files.writeString(temporaryDirectory.resolve("hardware_timing.jsonl"), """
                {"event":"hardware_work_completed","raw_frame":1,"boundary":"pre_main_loop","kind":"kos_decompression_queue","ordinal":0,"submission_fingerprint":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """);

        TraceData trace = TraceData.loadMetadataOnly(temporaryDirectory);

        assertEquals(0, trace.frameCount());
        assertTrue(trace.hardwareTimingSchedule().hasRecordedInput());
        assertEquals(1, trace.hardwareTimingSchedule().edges().size());
    }

    @Test
    void metadataOnlyWithoutTimingFileHasNoRecordedAuthority() throws IOException {
        writeMetadata(1);

        TraceData trace = TraceData.loadMetadataOnly(temporaryDirectory);

        assertFalse(trace.hardwareTimingSchedule().hasRecordedInput());
        assertTrue(trace.hardwareTimingSchedule().edges().isEmpty());
    }

    private void writeMetadata(int frameCount) throws IOException {
        Files.writeString(temporaryDirectory.resolve("metadata.json"), """
                {
                  "game": "s3k",
                  "zone": "test",
                  "zone_id": 0,
                  "act": 1,
                  "bk2_frame_offset": 0,
                  "trace_frame_count": %d,
                  "start_x": "0x0000",
                  "start_y": "0x0000",
                  "trace_schema": 5
                }
                """.formatted(frameCount));
    }
}
