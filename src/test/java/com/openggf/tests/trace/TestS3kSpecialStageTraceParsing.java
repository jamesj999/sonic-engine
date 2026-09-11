package com.openggf.tests.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceData;
import com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceFrame;
import com.openggf.trace.TraceEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD coverage for {@link S3kSpecialStageTraceFrame#parseCsvRow(String)} and
 * {@link S3kSpecialStageTraceData#load(Path)}: verifies every CSV column maps to
 * its named field (hex decoding, decimal frame, boolean lag/started), and that
 * {@code load} enforces the {@code s3k_special_stage} trace profile and supports
 * both plain and gzip-compressed physics files.
 */
class TestS3kSpecialStageTraceParsing {

    private static final String HEADER =
        "frame,input,input_p2,lag,anim_frame,x_pos,y_pos,angle,velocity,turning,"
        + "jumping,fade_timer,spheres_left,ring_count,rings_left,rate,rate_timer,"
        + "clear_timer,clear_routine,started";

    // One hand-built row exercising every column with a distinct, recognizable
    // hex value so a transposition bug shows up as a wrong-field mismatch
    // rather than a coincidentally-matching value.
    private static final String ROW =
        "1,8,0,1,2a1b,3c4d,5e6f,70,81,92,"
        + "a3,b4,c5d6,e7f8,1a2b,3c4d,5e6f,"
        + "7089,9a,1";

    @Test
    void parseCsvRowMapsEveryFieldByName() {
        S3kSpecialStageTraceFrame frame = S3kSpecialStageTraceFrame.parseCsvRow(ROW);

        assertEquals(1, frame.frame(), "frame is decimal");
        assertEquals(0x8, frame.input());
        assertEquals(0x0, frame.inputP2());
        assertTrue(frame.lag(), "lag=1 -> true");
        assertEquals(0x2a1b, frame.animFrame());
        assertEquals(0x3c4d, frame.xPos());
        assertEquals(0x5e6f, frame.yPos());
        assertEquals(0x70, frame.angle());
        assertEquals(0x81, frame.velocity());
        assertEquals(0x92, frame.turning());
        assertEquals(0xa3, frame.jumping());
        assertEquals(0xb4, frame.fadeTimer());
        assertEquals(0xc5d6, frame.spheresLeft());
        assertEquals(0xe7f8, frame.ringCount());
        assertEquals(0x1a2b, frame.ringsLeft());
        assertEquals(0x3c4d, frame.rate());
        assertEquals(0x5e6f, frame.rateTimer());
        assertEquals(0x7089, frame.clearTimer());
        assertEquals(0x9a, frame.clearRoutine());
        assertTrue(frame.started(), "started=1 -> true");
    }

    @Test
    void parseCsvRowLagZeroIsFalse() {
        String row = ROW.replaceFirst("^1,8,0,1", "1,8,0,0");
        assertFalse(S3kSpecialStageTraceFrame.parseCsvRow(row).lag());
    }

    @Test
    void parseCsvRowStartedZeroIsFalse() {
        String row = ROW.replaceAll(",1$", ",0");
        assertFalse(S3kSpecialStageTraceFrame.parseCsvRow(row).started());
    }

    @Test
    void parseCsvRowRejectsWrongColumnCount() {
        String tooFew = "1,8,0,1,2a1b,3c4d,5e6f,70";
        String tooMany = ROW + ",extra";

        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
            () -> S3kSpecialStageTraceFrame.parseCsvRow(tooFew));
        assertTrue(ex1.getMessage().contains("20"),
            "exception must name the expected column count: " + ex1.getMessage());

        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
            () -> S3kSpecialStageTraceFrame.parseCsvRow(tooMany));
        assertTrue(ex2.getMessage().contains("20"),
            "exception must name the expected column count: " + ex2.getMessage());
    }

    @Test
    void loadRoundTripTwoRowsAllColumnsAsserted(@TempDir Path dir) throws IOException {
        writeMetadata(dir, "s3k_special_stage", 0);
        Files.writeString(dir.resolve("physics.csv"),
            HEADER + "\n" + rowForFrame(0) + "\n" + rowForFrame(1) + "\n");

        S3kSpecialStageTraceData data = S3kSpecialStageTraceData.load(dir);

        assertEquals(2, data.frameCount());

        // First row: all values as in ROW
        S3kSpecialStageTraceFrame f0 = data.getFrame(0);
        assertEquals(0, f0.frame(), "first row frame is replaced with 0");
        assertEquals(0x8, f0.input());
        assertEquals(0x0, f0.inputP2());
        assertTrue(f0.lag());
        assertEquals(0x2a1b, f0.animFrame());
        assertEquals(0x3c4d, f0.xPos());
        assertEquals(0x5e6f, f0.yPos());
        assertEquals(0x70, f0.angle());
        assertEquals(0x81, f0.velocity());
        assertEquals(0x92, f0.turning());
        assertEquals(0xa3, f0.jumping());
        assertEquals(0xb4, f0.fadeTimer());
        assertEquals(0xc5d6, f0.spheresLeft());
        assertEquals(0xe7f8, f0.ringCount());
        assertEquals(0x1a2b, f0.ringsLeft());
        assertEquals(0x3c4d, f0.rate());
        assertEquals(0x5e6f, f0.rateTimer());
        assertEquals(0x7089, f0.clearTimer());
        assertEquals(0x9a, f0.clearRoutine());
        assertTrue(f0.started());

        // Second row: same pattern but frame=1
        S3kSpecialStageTraceFrame f1 = data.getFrame(1);
        assertEquals(1, f1.frame());
        assertEquals(0x8, f1.input());
        assertEquals(0x0, f1.inputP2());
        assertTrue(f1.lag());
        assertEquals(0x2a1b, f1.animFrame());
        assertEquals(0x3c4d, f1.xPos());
        assertEquals(0x5e6f, f1.yPos());
        assertEquals(0x70, f1.angle());
        assertEquals(0x81, f1.velocity());
        assertEquals(0x92, f1.turning());
        assertEquals(0xa3, f1.jumping());
        assertEquals(0xb4, f1.fadeTimer());
        assertEquals(0xc5d6, f1.spheresLeft());
        assertEquals(0xe7f8, f1.ringCount());
        assertEquals(0x1a2b, f1.ringsLeft());
        assertEquals(0x3c4d, f1.rate());
        assertEquals(0x5e6f, f1.rateTimer());
        assertEquals(0x7089, f1.clearTimer());
        assertEquals(0x9a, f1.clearRoutine());
        assertTrue(f1.started());

        assertEquals("s3k_special_stage", data.metadata().traceProfile());
        assertEquals(0, data.metadata().specialStageIndex());
    }

    @Test
    void loadRejectsWrongTraceProfile(@TempDir Path dir) throws IOException {
        writeMetadata(dir, "level", null);
        Files.writeString(dir.resolve("physics.csv"), HEADER + "\n" + rowForFrame(0) + "\n");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> S3kSpecialStageTraceData.load(dir));
        assertTrue(ex.getMessage().contains("level"),
            "exception must name the actual profile: " + ex.getMessage());
    }

    @Test
    void loadSupportsGzipCompression(@TempDir Path dir) throws IOException {
        writeMetadata(dir, "s3k_special_stage", 1);
        // Write as gzip-compressed .gz file
        String csvData = HEADER + "\n" + rowForFrame(0) + "\n" + rowForFrame(1) + "\n";
        try (OutputStream os = Files.newOutputStream(dir.resolve("physics.csv.gz"));
             GZIPOutputStream gzip = new GZIPOutputStream(os)) {
            gzip.write(csvData.getBytes());
        }

        S3kSpecialStageTraceData data = S3kSpecialStageTraceData.load(dir);

        assertEquals(2, data.frameCount());
        assertEquals(0, data.getFrame(0).frame());
        assertEquals(1, data.getFrame(1).frame());
        assertEquals("s3k_special_stage", data.metadata().traceProfile());
        assertEquals(1, data.metadata().specialStageIndex());
    }

    private static String rowForFrame(int frame) {
        return ROW.replaceFirst("^1,", frame + ",");
    }

    private static void writeMetadata(Path dir, String traceProfile, Integer specialStageIndex)
            throws IOException {
        String ssIndexLine = specialStageIndex != null
            ? ",\n  \"special_stage_index\": " + specialStageIndex
            : "";
        Files.writeString(dir.resolve("metadata.json"), """
            {
              "game": "s3k",
              "trace_profile": "%s",
              "trace_schema": 5,
              "act": 1,
              "bk2_frame_offset": 0,
              "trace_frame_count": 2,
              "start_x": "0x0000",
              "start_y": "0x0000",
              "recording_date": "2026-07-19",
              "rom_checksum": ""%s
            }
            """.formatted(traceProfile, ssIndexLine));
    }
}
