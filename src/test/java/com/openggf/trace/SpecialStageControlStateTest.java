package com.openggf.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialStageControlStateTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesInitialAndUnlockControlStateTransitions() throws Exception {
        Files.writeString(tempDir.resolve("metadata.json"), """
                {"game":"s2","trace_profile":"s2_special_stage","special_stage_index":0,"trace_schema":5}
                """);
        Files.writeString(tempDir.resolve("physics.csv"), header() + "\n" + row() + "\n");
        Files.writeString(tempDir.resolve("aux_state.jsonl"), """
                {"frame":0,"type":"control_state","started":0}
                {"frame":137,"type":"control_state","started":1}
                """);

        SpecialStageTraceData trace = SpecialStageTraceData.load(tempDir);

        assertEquals(2, trace.controlStateTransitions().size());
        assertEquals(0, trace.controlStateTransitions().get(0).frame());
        assertFalse(trace.controlStateTransitions().get(0).started());
        assertEquals(137, trace.controlStateTransitions().get(1).frame());
        assertTrue(trace.controlStateTransitions().get(1).started());
    }

    private static String header() {
        return "frame,input,input_p2,lag,speed_factor,track_anim,track_anim_frame,"
                + "track_drawing_index,track_orientation,track_duration_timer,current_segment,"
                + "player_anim_frame_timer,rings_togo_bcd,check_rings_flag,tails_control_counter,"
                + "swap_positions_flag,sonic_present,sonic_ss_x,sonic_ss_x_sub,sonic_ss_y,"
                + "sonic_ss_y_sub,sonic_ss_z,sonic_angle,sonic_routine,sonic_routine_secondary,"
                + "sonic_status,sonic_anim,sonic_anim_frame,sonic_rings_bcd,sonic_hurt_timer,"
                + "sonic_slide_timer,sonic_flip_timer,tails_present,tails_ss_x,tails_ss_x_sub,"
                + "tails_ss_y,tails_ss_y_sub,tails_ss_z,tails_angle,tails_routine,"
                + "tails_routine_secondary,tails_status,tails_anim,tails_anim_frame,"
                + "tails_rings_bcd,tails_hurt_timer,tails_slide_timer,tails_flip_timer";
    }

    private static String row() {
        return "0," + String.join(",", java.util.Collections.nCopies(47, "0"));
    }
}
