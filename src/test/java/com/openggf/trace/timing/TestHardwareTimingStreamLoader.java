package com.openggf.trace.timing;

import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.trace.TraceData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHardwareTimingStreamLoader {

    @TempDir
    Path temporaryDirectory;

    @Test
    void absentStreamHasNoRecordedTimingAuthority() throws IOException {
        HardwareTimingSchedule schedule = TraceData.load(writeFixture(null)).hardwareTimingSchedule();

        assertFalse(schedule.hasRecordedInput());
        assertTrue(schedule.edges().isEmpty());
    }

    @Test
    void presentEmptyStreamUsesTheCompleteV5Registry() throws IOException {
        HardwareTimingSchedule schedule = TraceData.load(writeFixture(""))
                .hardwareTimingSchedule();

        assertTrue(schedule.hasRecordedInput());
        assertTrue(schedule.edges().isEmpty());
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                schedule.admissionPolicies().get(HardwareWorkKind.KOS_MODULE_QUEUE));
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                schedule.admissionPolicies().get(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE));
    }

    @Test
    void oneKindStreamStillUsesTheCompleteV5Registry() throws IOException {
        for (String kind : List.of("kos_module_queue", "kos_decompression_queue")) {
            HardwareTimingSchedule schedule = TraceData.load(
                    writeFixture(edge(0,
                            kind.equals("kos_decompression_queue")
                                    ? "pre_main_loop" : "post_objects",
                            kind, 0) + "\n"))
                    .hardwareTimingSchedule();

            assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                    schedule.admissionPolicies().get(HardwareWorkKind.KOS_MODULE_QUEUE),
                    kind);
            assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                    schedule.admissionPolicies().get(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE),
                    kind);
        }
    }

    @Test
    void presentV5StreamAuthorizesBothModuleAndDirectRomWork() throws IOException {
        Path fixture = writeFixture(edge(0, "post_objects", "kos_module_queue", 0) + "\n"
                + edge(1, "pre_main_loop", "kos_decompression_queue", 0) + "\n");

        HardwareTimingSchedule schedule = TraceData.load(fixture).hardwareTimingSchedule();

        assertTrue(schedule.hasRecordedInput());
        assertEquals(2, schedule.edges().size());
        assertEquals(HardwareWorkKind.KOS_MODULE_QUEUE, schedule.edges().getFirst().kind());
        assertEquals(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, schedule.edges().getLast().kind());
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                schedule.admissionPolicies().get(HardwareWorkKind.KOS_MODULE_QUEUE));
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                schedule.admissionPolicies().get(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE));
        assertEquals(List.of(schedule.edges().getFirst()),
                schedule.edgesAt(0, HardwareServiceBoundary.POST_OBJECTS));
    }

    @Test
    void directEdgesRemainConstrainedToTheirProductionBoundary() throws IOException {
        Path fixture = writeFixture(edge(0, "post_objects", "kos_decompression_queue", 0) + "\n");

        assertRejected(fixture, "pre_main_loop");
    }

    @Test
    void v5StreamRejectsOutOfOrderEventsInsteadOfNormalizingThem() throws IOException {
        Path fixture = writeFixture(edge(0, "pre_main_loop", "kos_decompression_queue", 0) + "\n"
                + edge(0, "post_objects", "kos_module_queue", 0) + "\n");

        assertRejected(fixture, "canonical ordering");
    }

    @Test
    void timingStreamRetainsStrictWireAndRangeValidation() throws IOException {
        Path malformed = writeFixture("{\"event\":\n");
        assertRejected(malformed, "malformed JSON");

        Path outOfRange = writeFixture(edge(2, "vint_service", "kos_module_queue", 0) + "\n");
        assertRejected(outOfRange, "raw_frame");

        Path measurement = writeFixture(null);
        Files.writeString(measurement.resolve("load_time_measurements.jsonl"), "{}\n");
        assertRejected(measurement, "tooling-only");
    }

    @Test
    void timingStreamRejectsUnknownAndMissingFields() throws IOException {
        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 0)
                .replace("}", ",\"extra\":true}") + "\n"), "unknown or missing field");
        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 0)
                .replace(",\"ordinal\":0", "") + "\n"), "unknown or missing field");
    }

    @Test
    void timingStreamRejectsUnknownEventDuplicateFieldAndTrailingJson() throws IOException {
        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 0)
                .replace("hardware_work_completed", "hardware_work_started") + "\n"),
                "invalid event");
        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 0)
                .replace("{", "{\"event\":\"hardware_work_completed\",") + "\n"),
                "duplicate JSON field");
        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 0)
                + " {}\n"), "trailing JSON values");
    }

    @Test
    void timingStreamRejectsNonCanonicalWireNamesAndFingerprints() throws IOException {
        assertRejected(writeFixture(edge(0, "POST_OBJECTS", "kos_module_queue", 0) + "\n"),
                "invalid boundary");
        assertRejected(writeFixture(edge(0, "post_objects", "KOS_MODULE_QUEUE", 0) + "\n"),
                "invalid kind");
        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 0)
                .replace("sha256:", "SHA256:") + "\n"), "invalid submission_fingerprint");
        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 0)
                .replace("a".repeat(64), "A" + "a".repeat(63)) + "\n"),
                "invalid submission_fingerprint");
    }

    @Test
    void timingStreamRejectsInvalidUtf8AndNonLfFraming() throws IOException {
        Path invalidUtf8 = writeFixture(null);
        Files.write(invalidUtf8.resolve("hardware_timing.jsonl"), new byte[]{(byte) 0xC3, 0x28});
        assertRejected(invalidUtf8, "valid UTF-8");

        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 0) + "\r\n"),
                "LF-terminated UTF-8 lines");
        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 0)),
                "LF-terminated UTF-8 lines");
    }

    @Test
    void timingStreamRejectsDuplicateIdentityAndNonIncreasingPerKindOrdinal() throws IOException {
        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 0) + "\n"
                + edge(1, "post_objects", "kos_module_queue", 0) + "\n"),
                "duplicate identity");
        assertRejected(writeFixture(edge(0, "post_objects", "kos_module_queue", 1) + "\n"
                + edge(1, "post_objects", "kos_module_queue", 0) + "\n"),
                "ordinal must increase per kind");
    }

    @Test
    void timingStreamRejectsNegativeRawFrame() throws IOException {
        assertRejected(writeFixture(edge(-1, "post_objects", "kos_module_queue", 0) + "\n"),
                "raw_frame");
    }

    private Path writeFixture(String hardwareTiming) throws IOException {
        Path fixture = Files.createTempDirectory(temporaryDirectory, "fixture-");
        Files.writeString(fixture.resolve("metadata.json"), """
                {
                  "game": "s3k",
                  "zone": "test",
                  "zone_id": 0,
                  "act": 1,
                  "bk2_frame_offset": 0,
                  "trace_frame_count": 2,
                  "start_x": "0x0000",
                  "start_y": "0x0000",
                  "trace_schema": 5
                }
                """);
        Files.writeString(fixture.resolve("physics.csv"), levelRow(0) + "\n" + levelRow(1) + "\n");
        if (hardwareTiming != null) {
            Files.writeString(fixture.resolve("hardware_timing.jsonl"), hardwareTiming);
        }
        return fixture;
    }

    private void assertRejected(Path fixture, String detail) {
        Exception error = assertThrows(Exception.class, () -> TraceData.load(fixture));
        assertTrue(error.getMessage().contains("hardware_timing.jsonl")
                || error.getMessage().contains("load_time_measurements.jsonl"), error::getMessage);
        assertTrue(error.getMessage().contains(detail), error::getMessage);
    }

    private static String levelRow(int frame) {
        return "%04X,0000,0000,0000,0000,0000,0000,0000,".formatted(frame)
                + "01,0050,03B0,0000,0000,0000,00,0,0,0,0000,0000,02,00,00,00,41,"
                + "01,0060,03C0,0000,0000,0000,00,0,0,0,0000,0000,02,00,00,00,42";
    }

    private static String edge(int rawFrame, String boundary, String kind, long ordinal) {
        return "{\"event\":\"hardware_work_completed\",\"raw_frame\":" + rawFrame
                + ",\"boundary\":\"" + boundary + "\",\"kind\":\"" + kind + "\","
                + "\"ordinal\":" + ordinal + ",\"submission_fingerprint\":\""
                + "sha256:" + "a".repeat(64) + "\"}";
    }
}
