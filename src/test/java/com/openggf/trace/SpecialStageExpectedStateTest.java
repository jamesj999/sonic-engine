package com.openggf.trace;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialStageExpectedStateTest {

    @Test
    void f799MidPassCsvUsesSameLogicalRunObjectsEndRings() {
        SpecialStageTraceFrame csv = frame(799, 1);
        TraceEvent.StateSnapshot passEnd = runObjectsEnd(799, 3);

        SpecialStageExpectedState expected =
                SpecialStageExpectedState.from(csv, List.of(passEnd));

        assertTrue(expected.hasRunObjectsEnd());
        assertEquals(3, expected.combinedRings());
        assertEquals(3, expected.sonic().ringsBinary());
    }

    @Test
    void f791ExactPassEndRingCountRemainsOne() {
        SpecialStageExpectedState expected = SpecialStageExpectedState.from(
                frame(791, 1), List.of(runObjectsEnd(791, 1)));

        assertTrue(expected.hasRunObjectsEnd());
        assertEquals(1, expected.combinedRings());
    }

    @Test
    void missingRunObjectsEndFallsBackToCsvForStartup() {
        SpecialStageTraceFrame csv = frame(22, 0);

        SpecialStageExpectedState expected = SpecialStageExpectedState.from(csv, List.of());

        assertFalse(expected.hasRunObjectsEnd());
        assertEquals(csv.sonic(), expected.sonic());
        assertEquals(csv.tails(), expected.tails());
        assertEquals(0, expected.combinedRings());
        assertEquals(csv.tailsControlCounter(), expected.tailsControlCounter());
    }

    @Test
    void presentPassEndRejectsMissingAtomicFieldInsteadOfFallingBackToCsv() {
        TraceEvent.StateSnapshot partial = runObjectsEnd(799, 3);
        Map<String, Object> fields = new LinkedHashMap<>(partial.fields());
        fields.remove("sonic_ss_y_sub");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SpecialStageExpectedState.from(frame(799, 1),
                        List.of(new TraceEvent.StateSnapshot(799, fields))));

        assertTrue(error.getMessage().contains("sonic_ss_y_sub"));
    }

    @Test
    void duplicatePassEndForLogicalFrameIsRejected() {
        TraceEvent.StateSnapshot passEnd = runObjectsEnd(799, 3);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SpecialStageExpectedState.from(frame(799, 1),
                        List.of(passEnd, passEnd)));

        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void explicitAbsentPlayerDoesNotInheritCsvPlayerFields() {
        TraceEvent.StateSnapshot complete = runObjectsEnd(799, 3);
        Map<String, Object> fields = new LinkedHashMap<>(complete.fields());
        fields.put("tails_present", 0);
        fields.keySet().removeIf(key -> key.startsWith("tails_")
                && !key.equals("tails_present")
                && !key.equals("tails_control_counter"));

        SpecialStageExpectedState expected = SpecialStageExpectedState.from(frame(799, 1),
                List.of(new TraceEvent.StateSnapshot(799, fields)));

        assertFalse(expected.tails().present());
        assertEquals(0, expected.tails().ssX());
        assertEquals(3, expected.combinedRings());
    }

    private static TraceEvent.StateSnapshot runObjectsEnd(int frame, int sonicRings) {
        return new TraceEvent.StateSnapshot(frame, Map.ofEntries(
                Map.entry("frame", frame),
                Map.entry("type", "run_objects_end"),
                Map.entry("speed_factor", 12),
                Map.entry("track_anim", 0),
                Map.entry("track_anim_frame", 7),
                Map.entry("track_drawing_index", 4),
                Map.entry("track_orientation", 0),
                Map.entry("track_duration_timer", 2),
                Map.entry("current_segment", 5),
                Map.entry("player_anim_frame_timer", 7),
                Map.entry("rings_togo_bcd", 0),
                Map.entry("check_rings_flag", 0),
                Map.entry("tails_control_counter", 9),
                Map.entry("swap_positions_flag", 0),
                Map.entry("sonic_present", 1),
                Map.entry("sonic_ss_x", 128),
                Map.entry("sonic_ss_x_sub", 0),
                Map.entry("sonic_ss_y", 90),
                Map.entry("sonic_ss_y_sub", 0),
                Map.entry("sonic_ss_z", 300),
                Map.entry("sonic_angle", 64),
                Map.entry("sonic_routine", 2),
                Map.entry("sonic_routine_secondary", 0),
                Map.entry("sonic_status", 0),
                Map.entry("sonic_anim", 1),
                Map.entry("sonic_anim_frame", 2),
                Map.entry("sonic_rings_bcd", sonicRings),
                Map.entry("sonic_hurt_timer", 0),
                Map.entry("sonic_slide_timer", 0),
                Map.entry("sonic_flip_timer", 0),
                Map.entry("tails_present", 1),
                Map.entry("tails_ss_x", 128),
                Map.entry("tails_ss_x_sub", 0),
                Map.entry("tails_ss_y", 90),
                Map.entry("tails_ss_y_sub", 0),
                Map.entry("tails_ss_z", 300),
                Map.entry("tails_angle", 64),
                Map.entry("tails_routine", 2),
                Map.entry("tails_routine_secondary", 0),
                Map.entry("tails_status", 0),
                Map.entry("tails_anim", 1),
                Map.entry("tails_anim_frame", 2),
                Map.entry("tails_rings_bcd", 0),
                Map.entry("tails_hurt_timer", 0),
                Map.entry("tails_slide_timer", 0),
                Map.entry("tails_flip_timer", 0)));
    }

    private static SpecialStageTraceFrame frame(int frame, int sonicRings) {
        SpecialStageTraceFrame.CharacterState sonic = character(sonicRings);
        SpecialStageTraceFrame.CharacterState tails = character(0);
        return new SpecialStageTraceFrame(frame, 0, 0, false,
                12, 0, 7, 4, 0, 2, 5, 7, 0, 0, 4, 0,
                sonic, tails);
    }

    private static SpecialStageTraceFrame.CharacterState character(int rings) {
        return new SpecialStageTraceFrame.CharacterState(true,
                128, 0, 90, 0, 300, 64, 2, 0, 0, 1, 2,
                rings, 0, 0, 0);
    }
}
