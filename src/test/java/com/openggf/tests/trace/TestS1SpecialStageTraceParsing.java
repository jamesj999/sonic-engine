package com.openggf.tests.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageTraceData;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageTraceFrame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * TDD coverage for {@link Sonic1SpecialStageTraceFrame#parseCsvRow(String)} and
 * {@link Sonic1SpecialStageTraceData#load(Path)}: verifies every CSV column maps
 * to its named field (hex decoding, decimal frame, boolean lag), and that
 * {@code load} enforces the {@code s1_special_stage} trace profile.
 */
class TestS1SpecialStageTraceParsing {

    private static final String HEADER =
        "frame,input,lag,x_pos,y_pos,vel_x,vel_y,inertia,status,ss_angle,ss_rotate,"
        + "bg_anim,rings,emeralds";

    @Test
    void parseCsvRowMapsEveryFieldByName() {
        String row = "7,208,1,fffe8000,00478000,fe00,0123,0456,03,4000,ff80,6,17,1";

        Sonic1SpecialStageTraceFrame f = Sonic1SpecialStageTraceFrame.parseCsvRow(row);

        assertEquals(7, f.frame());
        assertEquals(0x208, f.input());
        assertTrue(f.lag());
        assertEquals(0xfffe8000L, f.xPos());
        assertEquals(0x00478000L, f.yPos());
        assertEquals(0xfe00, f.velX());
        assertEquals(0x0123, f.velY());
        assertEquals(0x0456, f.inertia());
        assertEquals(0x03, f.status());
        assertEquals(0x4000, f.ssAngle());
        assertEquals(0xff80, f.ssRotate());
        assertEquals(6, f.bgAnim());
        assertEquals(0x17, f.rings());
        assertEquals(1, f.emeralds());
    }

    @Test
    void parseCsvRowLagZeroIsFalse() {
        String row = "7,208,0,fffe8000,00478000,fe00,0123,0456,03,4000,ff80,6,17,1";
        assertFalse(Sonic1SpecialStageTraceFrame.parseCsvRow(row).lag());
    }

    @Test
    void parseCsvRowRejectsWrongColumnCount() {
        String tooFew = "7,208,1,fffe8000,00478000,fe00,0123,0456,03,4000,ff80,6,17";
        String tooMany = "7,208,1,fffe8000,00478000,fe00,0123,0456,03,4000,ff80,6,17,1,extra";

        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
            () -> Sonic1SpecialStageTraceFrame.parseCsvRow(tooFew));
        assertTrue(ex1.getMessage().contains("14"),
            "exception must name the expected column count: " + ex1.getMessage());

        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
            () -> Sonic1SpecialStageTraceFrame.parseCsvRow(tooMany));
        assertTrue(ex2.getMessage().contains("14"),
            "exception must name the expected column count: " + ex2.getMessage());
    }

    @Test
    void loadRejectsWrongTraceProfile(@TempDir Path dir) throws IOException {
        writeMetadata(dir, "s3k_special_stage");
        Files.writeString(dir.resolve("physics.csv"),
            HEADER + "\n" + rowForFrame(0) + "\n");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> Sonic1SpecialStageTraceData.load(dir));
        assertTrue(ex.getMessage().contains("s1_special_stage"),
            "exception must name the expected profile: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("s3k_special_stage"),
            "exception must name the actual profile: " + ex.getMessage());
    }

    @Test
    void loadRoundTripTwoRowsAllColumnsAsserted(@TempDir Path dir) throws IOException {
        writeMetadata(dir, "s1_special_stage");
        Files.writeString(dir.resolve("physics.csv"),
            HEADER + "\n" + rowForFrame(0) + "\n" + rowForFrame(1) + "\n");

        Sonic1SpecialStageTraceData data = Sonic1SpecialStageTraceData.load(dir);

        assertEquals(2, data.frameCount());

        Sonic1SpecialStageTraceFrame f0 = data.getFrame(0);
        assertEquals(0, f0.frame());
        assertEquals(0x208, f0.input());
        assertTrue(f0.lag());
        assertEquals(0xfffe8000L, f0.xPos());
        assertEquals(0x00478000L, f0.yPos());
        assertEquals(0xfe00, f0.velX());
        assertEquals(0x0123, f0.velY());
        assertEquals(0x0456, f0.inertia());
        assertEquals(0x03, f0.status());
        assertEquals(0x4000, f0.ssAngle());
        assertEquals(0xff80, f0.ssRotate());
        assertEquals(6, f0.bgAnim());
        assertEquals(0x17, f0.rings());
        assertEquals(1, f0.emeralds());

        Sonic1SpecialStageTraceFrame f1 = data.getFrame(1);
        assertEquals(1, f1.frame());
        assertEquals(0x208, f1.input());
        assertTrue(f1.lag());
        assertEquals(0xfffe8000L, f1.xPos());
        assertEquals(0x00478000L, f1.yPos());
        assertEquals(0xfe00, f1.velX());
        assertEquals(0x0123, f1.velY());
        assertEquals(0x0456, f1.inertia());
        assertEquals(0x03, f1.status());
        assertEquals(0x4000, f1.ssAngle());
        assertEquals(0xff80, f1.ssRotate());
        assertEquals(6, f1.bgAnim());
        assertEquals(0x17, f1.rings());
        assertEquals(1, f1.emeralds());

        assertEquals("s1_special_stage", data.metadata().traceProfile());
    }

    private static String rowForFrame(int frame) {
        return frame + ",208,1,fffe8000,00478000,fe00,0123,0456,03,4000,ff80,6,17,1";
    }

    private static void writeMetadata(Path dir, String traceProfile) throws IOException {
        Files.writeString(dir.resolve("metadata.json"), """
            {
              "game": "s1",
              "trace_profile": "%s",
              "trace_schema": 5,
              "act": 1,
              "bk2_frame_offset": 0,
              "trace_frame_count": 2,
              "start_x": "0x0000",
              "start_y": "0x0000",
              "recording_date": "2026-07-19",
              "rom_checksum": ""
            }
            """.formatted(traceProfile));
    }
}
