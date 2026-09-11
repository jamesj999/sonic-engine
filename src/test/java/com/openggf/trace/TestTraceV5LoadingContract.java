package com.openggf.trace;

import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageTraceData;
import com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceData;
import com.openggf.tests.TestTempFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceV5LoadingContract {

    @Test
    void acceptsOnlySchemaFiveAndTreatsProvenanceAsOpaque() throws IOException {
        Path directory = writeLevelTrace("""
                "trace_schema": 5,
                "recorder": "another-recorder",
                "recorder_version": "unparseable-but-opaque",
                "aux_schema_extras": ["native_prelude_bootstrap"]
                """);

        TraceMetadata metadata = assertDoesNotThrow(
                () -> TraceMetadata.load(directory.resolve("metadata.json")));

        assertEquals(5, metadata.traceSchema());
        assertEquals("another-recorder", metadata.recorder());
        assertEquals("unparseable-but-opaque", metadata.recorderVersion());
        assertTrue(metadata.hasNativePreludeBootstrap());
    }

    @Test
    void rejectsAbsentOtherSchemaAndRemovedVersionFields() throws IOException {
        for (String envelope : List.of(
                "\"trace_schema\": 4",
                "\"trace_schema\": 6",
                "\"recorder\": \"native\"",
                "\"trace_schema\": 5, \"csv_version\": 7",
                "\"trace_schema\": 5, \"lua_script_version\": \"9.2-s2\"",
                "\"trace_schema\": 5, \"ss_csv_version\": 1",
                "\"trace_schema\": 5, \"hardware_timing_schema\": 2",
                "\"trace_schema\": 5, \"run_schema\": 2")) {
            Path directory = writeLevelTrace(envelope);
            assertThrows(IllegalArgumentException.class,
                    () -> TraceMetadata.load(directory.resolve("metadata.json")), envelope);
        }
    }

    @Test
    void parsesOnlyTheV5FortyTwoColumnLevelRow() {
        TraceFrame frame = TraceFrame.parseCsvRow(levelRow());

        assertEquals(0, frame.frame());
        assertEquals(0x41, frame.mappingFrame());
        assertTrue(frame.sidekick().present());
        assertEquals(0, frame.animationId());

        for (int width : List.of(11, 18, 19, 20, 22, 37, 38)) {
            assertThrows(IllegalArgumentException.class,
                    () -> TraceFrame.parseCsvRow(rowWithColumns(width)), "width=" + width);
        }
    }

    @Test
    void ordinaryLoaderRejectsSpecialStageProfiles() throws IOException {
        Path directory = writeTrace("s1", "s1_special_stage", """
                0,0,0,0,0,0,0,0,0,0,0,0,0,0
                """);

        assertThrows(IllegalArgumentException.class, () -> TraceData.load(directory));
    }

    @Test
    void specialStageReadersRequireTheirGameOwnedFixedWidths() throws IOException {
        Path s1 = writeTrace("s1", "s1_special_stage", rowWithColumns(13));
        assertThrows(IllegalArgumentException.class, () -> Sonic1SpecialStageTraceData.load(s1));

        Path s2 = writeTrace("s2", "s2_special_stage", rowWithColumns(47));
        assertThrows(IllegalArgumentException.class, () -> SpecialStageTraceData.load(s2));

        Path s3k = writeTrace("s3k", "s3k_special_stage", rowWithColumns(19));
        assertThrows(IllegalArgumentException.class, () -> S3kSpecialStageTraceData.load(s3k));
    }

    @Test
    void v5MakesAnimationAndSubpixelFieldsInherent() throws IOException {
        TraceMetadata metadata = TraceMetadata.load(
                writeLevelTrace("\"trace_schema\": 5").resolve("metadata.json"));

        assertTrue(metadata.hasPerFrameCharacterAnimation());
        assertTrue(metadata.hasSubpixel());
        assertFalse(metadata.hasNativePreludeBootstrap());
    }

    private static Path writeLevelTrace(String envelope) throws IOException {
        return writeTrace("s2", "level_gated_reset_aware", levelRow(), envelope);
    }

    private static Path writeTrace(String game, String profile, String row) throws IOException {
        return writeTrace(game, profile, row, "\"trace_schema\": 5");
    }

    private static Path writeTrace(String game, String profile, String row, String envelope)
            throws IOException {
        Path directory = TestTempFiles.createTempDirectory("trace-v5-contract");
        Files.writeString(directory.resolve("metadata.json"), """
                {
                  "game": "%s",
                  "zone": "test",
                  "zone_id": 0,
                  "act": 1,
                  "bk2_frame_offset": 0,
                  "trace_frame_count": 1,
                  "start_x": "0x0000",
                  "start_y": "0x0000",
                  "trace_profile": "%s",
                  %s
                }
                """.formatted(game, profile, envelope));
        Files.writeString(directory.resolve("physics.csv"), row + "\n");
        return directory;
    }

    private static String levelRow() {
        return "0000,0000,0000,0000,0000,0000,0000,0000,"
                + "01,0050,03B0,0000,0000,0000,00,0,0,0,0000,0000,02,00,00,00,41,"
                + "01,0060,03C0,0000,0000,0000,00,0,0,0,0000,0000,02,00,00,00,42";
    }

    private static String rowWithColumns(int width) {
        return String.join(",", java.util.Collections.nCopies(width, "0"));
    }
}
