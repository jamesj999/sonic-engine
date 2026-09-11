package com.openggf.trace;

import com.openggf.trace.SpecialStageTraceFrame.CharacterState;

import java.util.List;
import java.util.Map;

/**
 * Comparison-only expected state for one Sonic 2 special-stage logical step.
 *
 * <p>The CSV row is sampled at VBlank and can therefore bisect the ROM's
 * subsequent {@code RunObjects} pass. For fields actually owned by that pass
 * (player object state, player ring digits, and Tails's control counter), a
 * pass-identity binder-selected {@code run_objects_end} snapshot is authoritative. Other
 * manager fields keep their CSV value because their owning routines run before
 * or after {@code RunObjects}; in particular this helper never shifts finish or
 * results state across frames.
 *
 * <p>This type only selects expectations. It is never used to mutate engine
 * state.
 */
public record SpecialStageExpectedState(
        SpecialStageTraceFrame csv,
        CharacterState sonic,
        CharacterState tails,
        int combinedRings,
        int tailsControlCounter,
        RunObjectsEndState runObjectsEnd) {

    /** Full atomic recorder snapshot captured at the REV01 RunObjects return. */
    public record RunObjectsEndState(
            int speedFactor,
            int trackAnim,
            int trackAnimFrame,
            int trackDrawingIndex,
            int trackOrientation,
            int trackDurationTimer,
            int currentSegment,
            int playerAnimFrameTimer,
            int ringsToGoBcd,
            int checkRingsFlag,
            int tailsControlCounter,
            int swapPositionsFlag,
            CharacterState sonic,
            CharacterState tails) {
    }

    public static SpecialStageExpectedState from(SpecialStageTraceFrame csv,
                                                  List<TraceEvent> events) {
        List<TraceEvent.StateSnapshot> passEnds = events.stream()
                .filter(TraceEvent.StateSnapshot.class::isInstance)
                .map(TraceEvent.StateSnapshot.class::cast)
                .filter(snapshot -> "run_objects_end".equals(snapshot.fields().get("type")))
                .toList();
        // The pass binder reduces a physical observation's ordered pass list to
        // one latest completed atomic state before calling this mapper.
        if (passEnds.size() > 1) {
            throw new IllegalArgumentException(
                    "duplicate run_objects_end snapshots for frame " + csv.frame());
        }
        RunObjectsEndState passEnd = passEnds.isEmpty()
                ? null
                : parseRunObjectsEnd(passEnds.getFirst().fields());

        CharacterState sonic = passEnd != null ? passEnd.sonic() : csv.sonic();
        CharacterState tails = passEnd != null ? passEnd.tails() : csv.tails();
        int tailsCounter = passEnd != null
                ? passEnd.tailsControlCounter()
                : csv.tailsControlCounter();
        return new SpecialStageExpectedState(csv, sonic, tails,
                ringsBinary(sonic) + ringsBinary(tails), tailsCounter, passEnd);
    }

    public boolean hasRunObjectsEnd() {
        return runObjectsEnd != null;
    }

    private static RunObjectsEndState parseRunObjectsEnd(Map<String, Object> fields) {
        return new RunObjectsEndState(
                required(fields, "speed_factor"),
                required(fields, "track_anim"),
                required(fields, "track_anim_frame"),
                required(fields, "track_drawing_index"),
                required(fields, "track_orientation"),
                required(fields, "track_duration_timer"),
                required(fields, "current_segment"),
                required(fields, "player_anim_frame_timer"),
                required(fields, "rings_togo_bcd"),
                required(fields, "check_rings_flag"),
                required(fields, "tails_control_counter"),
                required(fields, "swap_positions_flag"),
                character(fields, "sonic"),
                character(fields, "tails"));
    }

    private static CharacterState character(Map<String, Object> fields, String prefix) {
        boolean present = required(fields, prefix + "_present") != 0;
        if (!present) {
            return absentCharacter();
        }
        return new CharacterState(true,
                required(fields, prefix + "_ss_x"),
                required(fields, prefix + "_ss_x_sub"),
                required(fields, prefix + "_ss_y"),
                required(fields, prefix + "_ss_y_sub"),
                required(fields, prefix + "_ss_z"),
                required(fields, prefix + "_angle"),
                required(fields, prefix + "_routine"),
                required(fields, prefix + "_routine_secondary"),
                required(fields, prefix + "_status"),
                required(fields, prefix + "_anim"),
                required(fields, prefix + "_anim_frame"),
                required(fields, prefix + "_rings_bcd"),
                required(fields, prefix + "_hurt_timer"),
                required(fields, prefix + "_slide_timer"),
                required(fields, prefix + "_flip_timer"));
    }

    private static CharacterState absentCharacter() {
        return new CharacterState(false, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0);
    }

    private static int required(Map<String, Object> fields, String name) {
        Object raw = fields.get(name);
        if (raw == null) {
            throw new IllegalArgumentException(
                    "run_objects_end snapshot missing required field " + name);
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String text = raw.toString().trim();
        if (text.startsWith("0x") || text.startsWith("0X")) {
            return Integer.parseUnsignedInt(text.substring(2), 16);
        }
        return Integer.parseInt(text);
    }

    private static int ringsBinary(CharacterState character) {
        return character != null && character.present() ? character.ringsBinary() : 0;
    }
}
