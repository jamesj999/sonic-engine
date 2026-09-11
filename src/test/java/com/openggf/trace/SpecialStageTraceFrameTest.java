package com.openggf.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD coverage for {@link SpecialStageTraceFrame#parseCsvRow(String)} and
 * {@link SpecialStageTraceData#load(Path)}: verifies every CSV column maps to
 * its named field (hex decoding, decimal frame, boolean lag/present), and that
 * {@code load} enforces the {@code s2_special_stage} trace profile and
 * surfaces the final-checkpoint {@code stage_finished} boundary and later
 * {@code results_started} observation independently.
 */
class SpecialStageTraceFrameTest {

    private static final String HEADER =
        "frame,input,input_p2,lag,speed_factor,track_anim,track_anim_frame,track_drawing_index,"
        + "track_orientation,track_duration_timer,current_segment,player_anim_frame_timer,"
        + "rings_togo_bcd,check_rings_flag,tails_control_counter,swap_positions_flag,"
        + "sonic_present,sonic_ss_x,sonic_ss_x_sub,sonic_ss_y,sonic_ss_y_sub,sonic_ss_z,"
        + "sonic_angle,sonic_routine,sonic_routine_secondary,sonic_status,sonic_anim,"
        + "sonic_anim_frame,sonic_rings_bcd,sonic_hurt_timer,sonic_slide_timer,sonic_flip_timer,"
        + "tails_present,tails_ss_x,tails_ss_x_sub,tails_ss_y,tails_ss_y_sub,tails_ss_z,"
        + "tails_angle,tails_routine,tails_routine_secondary,tails_status,tails_anim,"
        + "tails_anim_frame,tails_rings_bcd,tails_hurt_timer,tails_slide_timer,tails_flip_timer";

    // One hand-built row exercising every column with a distinct, recognizable
    // hex value so a transposition bug shows up as a wrong-field mismatch
    // rather than a coincidentally-matching value.
    private static final String ROW =
        "1,8,0,1,2,3,4,5,"
        + "6,7,8,9,"
        + "a1a2a3,b,c,d,"
        + "1,10,11,12,13,14,"
        + "15,16,17,18,19,"
        + "1a,010203,1b,1c,1d,"
        + "0,20,21,22,23,24,"
        + "25,26,27,28,29,"
        + "2a,040506,2b,2c,2d";

    @Test
    void parseCsvRowMapsEveryFieldByName() {
        SpecialStageTraceFrame frame = SpecialStageTraceFrame.parseCsvRow(ROW);

        assertEquals(1, frame.frame(), "frame is decimal");
        assertEquals(0x8, frame.input());
        assertEquals(0x0, frame.inputP2());
        assertTrue(frame.lag(), "lag=1 -> true");
        assertEquals(0x2, frame.speedFactor());
        assertEquals(0x3, frame.trackAnim());
        assertEquals(0x4, frame.trackAnimFrame());
        assertEquals(0x5, frame.trackDrawingIndex());
        assertEquals(0x6, frame.trackOrientation());
        assertEquals(0x7, frame.trackDurationTimer());
        assertEquals(0x8, frame.currentSegment());
        assertEquals(0x9, frame.playerAnimFrameTimer());
        assertEquals(0xa1a2a3, frame.ringsToGoBcd());
        assertEquals(0xb, frame.checkRingsFlag());
        assertEquals(0xc, frame.tailsControlCounter());
        assertEquals(0xd, frame.swapPositionsFlag());

        SpecialStageTraceFrame.CharacterState sonic = frame.sonic();
        assertTrue(sonic.present(), "sonic_present=1 (nonzero) -> true");
        assertEquals(0x10, sonic.ssX());
        assertEquals(0x11, sonic.ssXSub());
        assertEquals(0x12, sonic.ssY());
        assertEquals(0x13, sonic.ssYSub());
        assertEquals(0x14, sonic.ssZ());
        assertEquals(0x15, sonic.angle());
        assertEquals(0x16, sonic.routine());
        assertEquals(0x17, sonic.routineSecondary());
        assertEquals(0x18, sonic.status());
        assertEquals(0x19, sonic.anim());
        assertEquals(0x1a, sonic.animFrame());
        assertEquals(0x010203, sonic.ringsBcd());
        assertEquals(0x1b, sonic.hurtTimer());
        assertEquals(0x1c, sonic.slideTimer());
        assertEquals(0x1d, sonic.flipTimer());
        assertEquals(1 * 100 + 2 * 10 + 3, sonic.ringsBinary(),
            "ringsBinary decodes hundreds<<16|tens<<8|units as BCD digits");

        SpecialStageTraceFrame.CharacterState tails = frame.tails();
        assertFalse(tails.present(), "tails_present=0 -> false");
        assertEquals(0x20, tails.ssX());
        assertEquals(0x21, tails.ssXSub());
        assertEquals(0x22, tails.ssY());
        assertEquals(0x23, tails.ssYSub());
        assertEquals(0x24, tails.ssZ());
        assertEquals(0x25, tails.angle());
        assertEquals(0x26, tails.routine());
        assertEquals(0x27, tails.routineSecondary());
        assertEquals(0x28, tails.status());
        assertEquals(0x29, tails.anim());
        assertEquals(0x2a, tails.animFrame());
        assertEquals(0x040506, tails.ringsBcd());
        assertEquals(0x2b, tails.hurtTimer());
        assertEquals(0x2c, tails.slideTimer());
        assertEquals(0x2d, tails.flipTimer());
        assertEquals(4 * 100 + 5 * 10 + 6, tails.ringsBinary());
    }

    @Test
    void parseCsvRowLagZeroIsFalse() {
        String row = ROW.replaceFirst("^1,8,0,1", "1,8,0,0");
        assertFalse(SpecialStageTraceFrame.parseCsvRow(row).lag());
    }

    @Test
    void loadParsesCheckpointOwnedFinishAndLaterResultsStart(@TempDir Path dir)
            throws IOException {
        writeMetadata(dir, "s2_special_stage", 1);
        Files.writeString(dir.resolve("physics.csv"),
            HEADER + "\n" + rowForFrame(0) + "\n" + rowForFrame(1) + "\n" + rowForFrame(2) + "\n");
        Files.writeString(dir.resolve("aux_state.jsonl"),
            "{\"frame\":2,\"type\":\"checkpoint\",\"check_rings_flag\":\"0xff\"}\n"
                + "{\"frame\":1,\"observed_frame\":2,\"type\":\"stage_finished\"}\n"
                + "{\"frame\":2,\"type\":\"results_started\",\"slot\":32}\n");

        SpecialStageTraceData data = SpecialStageTraceData.load(dir);

        assertEquals(3, data.frameCount());
        assertEquals(0, data.getFrame(0).frame());
        assertEquals(1, data.getFrame(1).frame());
        assertEquals(2, data.getFrame(2).frame());
        assertEquals("s2_special_stage", data.metadata().traceProfile());
        assertEquals(1, data.metadata().specialStageIndex());

        List<TraceEvent> frame2Events = data.getEventsForFrame(2);
        assertEquals(2, frame2Events.size());

        OptionalInt stageFinished = data.stageFinishedFrame();
        assertTrue(stageFinished.isPresent());
        assertEquals(1, stageFinished.getAsInt());
        assertEquals(2, data.stageFinishedObservedFrame().orElseThrow(),
                "finish mapping must preserve the raw observation that saw the flag rise");
        assertEquals(2, data.resultsStartedFrame().orElseThrow());
        assertTrue(data.hardwareTimingSchedule().edges().isEmpty(),
                "legacy special-stage traces remain timing-stream free");
    }

    @Test
    void loadStrictlyParsesDedicatedHardwareTimingStream(@TempDir Path dir)
            throws IOException {
        writeHardwareTimingMetadata(dir);
        Files.writeString(dir.resolve("physics.csv"),
                HEADER + "\n" + rowForFrame(0) + "\n");
        Files.writeString(dir.resolve("hardware_timing.jsonl"), """
                {"event":"hardware_work_completed","raw_frame":0,"boundary":"post_objects","kind":"kos_module_queue","ordinal":0,"submission_fingerprint":"sha256:%s"}
                """.formatted("a".repeat(64)));

        SpecialStageTraceData data = SpecialStageTraceData.load(dir);

        assertEquals(1, data.hardwareTimingSchedule().edges().size());
        assertEquals(0,
                data.hardwareTimingSchedule().edges().getFirst().rawFrame());
    }

    @Test
    void loadWithoutDedicatedTimingStreamHasNoRecordedAuthority(@TempDir Path dir)
            throws IOException {
        writeHardwareTimingMetadata(dir);
        Files.writeString(dir.resolve("physics.csv"),
                HEADER + "\n" + rowForFrame(0) + "\n");

        SpecialStageTraceData data = SpecialStageTraceData.load(dir);

        assertTrue(data.hardwareTimingSchedule().edges().isEmpty());
    }

    @Test
    void loadRejectsWrongTraceProfile(@TempDir Path dir) throws IOException {
        writeMetadata(dir, "level", null);
        Files.writeString(dir.resolve("physics.csv"), HEADER + "\n" + rowForFrame(0) + "\n");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> SpecialStageTraceData.load(dir));
        assertTrue(ex.getMessage().contains("level"),
            "exception must name the actual profile: " + ex.getMessage());
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
              "game": "s2",
              "trace_profile": "%s",
              "trace_schema": 5,
              "act": 1,
              "bk2_frame_offset": 0,
              "trace_frame_count": 3,
              "start_x": "0x0000",
              "start_y": "0x0000",
              "recording_date": "2026-07-09",
              "rom_checksum": ""%s
            }
            """.formatted(traceProfile, ssIndexLine));
    }

    private static void writeHardwareTimingMetadata(Path dir)
            throws IOException {
        Files.writeString(dir.resolve("metadata.json"), """
            {
              "game": "s2",
              "trace_profile": "s2_special_stage",
              "trace_schema": 5,
              "act": 1,
              "bk2_frame_offset": 0,
              "trace_frame_count": 1,
              "start_x": "0x0000",
              "start_y": "0x0000",
              "recording_date": "2026-07-27",
              "rom_checksum": ""
            }
            """);
    }
}
