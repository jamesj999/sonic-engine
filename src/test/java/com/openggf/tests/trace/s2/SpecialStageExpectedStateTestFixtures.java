package com.openggf.tests.trace.s2;

import com.openggf.trace.SpecialStageTraceFrame;
import com.openggf.trace.TraceEvent;

import java.util.LinkedHashMap;
import java.util.Map;

final class SpecialStageExpectedStateTestFixtures {
    private SpecialStageExpectedStateTestFixtures() {}

    static SpecialStageTraceFrame frame(int frame, int sonicRings) {
        var sonic = character(sonicRings);
        var tails = character(0);
        return new SpecialStageTraceFrame(frame, 0, 0, false,
                12, 0, 7, 4, 0, 2, 5, 7, 0, 0, 4, 0, sonic, tails);
    }

    static TraceEvent.StateSnapshot runObjectsEnd(int frame, int sonicRings) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("frame", frame);
        fields.put("type", "run_objects_end");
        fields.put("speed_factor", 12);
        fields.put("track_anim", 0);
        fields.put("track_anim_frame", 7);
        fields.put("track_drawing_index", 4);
        fields.put("track_orientation", 0);
        fields.put("track_duration_timer", 2);
        fields.put("current_segment", 5);
        fields.put("player_anim_frame_timer", 7);
        fields.put("rings_togo_bcd", 0);
        fields.put("check_rings_flag", 0);
        fields.put("tails_control_counter", 9);
        fields.put("swap_positions_flag", 0);
        putCharacter(fields, "sonic", sonicRings);
        putCharacter(fields, "tails", 0);
        return new TraceEvent.StateSnapshot(frame, fields);
    }

    private static SpecialStageTraceFrame.CharacterState character(int rings) {
        return new SpecialStageTraceFrame.CharacterState(true,
                128, 0, 90, 0, 300, 64, 2, 0, 0, 1, 2,
                rings, 0, 0, 0);
    }

    private static void putCharacter(Map<String, Object> fields, String prefix, int rings) {
        fields.put(prefix + "_present", 1);
        fields.put(prefix + "_ss_x", 128);
        fields.put(prefix + "_ss_x_sub", 0);
        fields.put(prefix + "_ss_y", 90);
        fields.put(prefix + "_ss_y_sub", 0);
        fields.put(prefix + "_ss_z", 300);
        fields.put(prefix + "_angle", 64);
        fields.put(prefix + "_routine", 2);
        fields.put(prefix + "_routine_secondary", 0);
        fields.put(prefix + "_status", 0);
        fields.put(prefix + "_anim", 1);
        fields.put(prefix + "_anim_frame", 2);
        fields.put(prefix + "_rings_bcd", rings);
        fields.put(prefix + "_hurt_timer", 0);
        fields.put(prefix + "_slide_timer", 0);
        fields.put(prefix + "_flip_timer", 0);
    }
}
