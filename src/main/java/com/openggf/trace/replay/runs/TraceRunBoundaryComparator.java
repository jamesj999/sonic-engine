package com.openggf.trace.replay.runs;

import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceRunManifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Comparison-only return-boundary checks shared by headless and visual run
 * replay. The caller supplies an immutable engine snapshot; this class owns no
 * gameplay service and cannot restore or otherwise mutate run state.
 */
public final class TraceRunBoundaryComparator {

    private static final String PREFIX = "run_boundary.";

    private TraceRunBoundaryComparator() {
    }

    /** Manifest and return-frame facts that define one interior return. */
    public record ExpectedBoundary(
            TraceRunManifest.Transition entry,
            TraceRunManifest.Transition exit,
            TraceRunManifest.Segment preEntryLevel,
            TraceRunManifest.Segment returnLevel,
            TraceFrame returnFrameZero,
            Integer resolvedReturnZone,
            Integer returnArmFrameX,
            Integer returnArmFrameY) {
        public ExpectedBoundary {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(exit, "exit");
            Objects.requireNonNull(preEntryLevel, "preEntryLevel");
            Objects.requireNonNull(returnLevel, "returnLevel");
            Objects.requireNonNull(returnFrameZero, "returnFrameZero");
        }
    }

    /**
     * Read-only engine state sampled after the destination level is loaded.
     *
     * <p>{@code destinationRowsConsumed} states how many of the destination
     * segment's recorded rows the engine had already run when this snapshot was
     * taken — the playback cursor's distance from the segment's
     * {@code bk2_frame_offset}. It selects which recorded sample the position
     * comparison is co-temporal with; see {@link #comparePosition}.
     */
    public record ActualBoundary(
            Integer playerCentreX,
            Integer playerCentreY,
            Integer checkpointIndex,
            Integer currentZone,
            Integer currentAct,
            Integer rings,
            Integer emeralds,
            boolean organicallyReproducedInterior,
            int destinationRowsConsumed) {
    }

    /** Produces ordinary trace diagnostics for every assertion owned by the return policy. */
    public static FrameComparison compare(
            int frame, ExpectedBoundary expected, ActualBoundary actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        Map<String, FieldComparison> fields = new LinkedHashMap<>();

        TraceRunReplayWalker.ReturnAssertionMode mode =
                TraceRunReplayWalker.returnAssertionMode(expected.entry());
        switch (mode) {
            case POSITIONAL_RESTORE -> comparePosition(fields, expected, actual);
            case CHECKPOINT_RESTORE -> {
                if (expected.entry().lastStarPostHit() != null) {
                    put(fields, PREFIX + "checkpoint",
                            expected.entry().lastStarPostHit(),
                            actual.checkpointIndex());
                }
                comparePosition(fields, expected, actual);
            }
            case NEXT_ACT -> compareNextAct(fields, expected, actual);
            case RINGS_EMERALDS_ONLY -> comparePosition(fields, expected, actual);
        }

        // NEXT_ACT's exit value is the interior tally sampled when S1 first
        // writes id_Level (sonic.asm:3332), before the fresh act load clears
        // v_rings because Got_NextLevel cleared v_lastlamp
        // (_incObj/3A Got Through Card.asm:198; sonic.asm:2892-2900). The
        // settled destination snapshot is therefore not co-temporal with that
        // manifest field. Other return modes restore into the same level and
        // retain an exact post-load ring comparison.
        if (mode != TraceRunReplayWalker.ReturnAssertionMode.NEXT_ACT
                && expected.exit().ringsAfter() != null) {
            put(fields, PREFIX + "rings", expected.exit().ringsAfter(),
                    actual.rings());
        }
        compareEmeralds(fields, expected, actual);
        return new FrameComparison(frame, fields);
    }

    /**
     * Compares the sampled player position against the recorded sample taken on
     * the SAME frame, chosen from {@code destinationRowsConsumed}.
     *
     * <p>A destination segment's recorded rows do not start on the frame the
     * mode change happens. Both recorders arm the destination segment on the
     * first end-of-frame with the level game mode and player control already
     * unlocked, stamp that frame as the transition's
     * {@code mode_change_bk2_frame} AND the segment's {@code bk2_frame_offset},
     * and then deliberately do not record it — "Arm-and-return: the arm frame
     * belongs to no segment" (S3KCompleteRunSegmenter.Step step 8) and
     * "Detection frame is never recorded" (S2RunCaptureRunner's level arm
     * gate). The arm frame's own state is published instead as the segment
     * metadata's {@code start_x}/{@code start_y}; row 0 is the NEXT frame.
     *
     * <p>That is the ROM's shape, not a recorder artefact. Both level routines
     * clear the control lock and the title-card game-mode bit inline, with no
     * vsync between them and the main loop's first wait — S3K
     * {@code move.b #0,(Ctrl_1_locked).w} / {@code bclr #7,(Game_mode).w}
     * ahead of {@code LevelLoop}'s {@code Wait_VSync}
     * (docs/skdisasm/sonic3k.asm:7859, 7883, 7888-7891), S2
     * {@code move.b #0,(Control_Locked).w} /
     * {@code bclr #GameModeFlag_TitleCard,(Game_Mode).w} ahead of
     * {@code Level_MainLoop}'s {@code WaitForVint} (docs/s2disasm/s2.asm:5081,
     * 5085, 5088-5091). So the frame that ENDS on that wait is a settled,
     * unlocked level frame that has consumed no input and run no
     * input-driven object pass; the first frame that reads a control word and
     * moves the player is the one after it. Every recorded return segment
     * shows exactly that: row 0 carries one frame of acceleration
     * ({@code x_sub == (x_speed << 8)}) away from the restored pixel.
     *
     * <p>An engine sampled with zero destination rows consumed is therefore on
     * the arm frame and is compared against {@code start_x}/{@code start_y};
     * one row consumed puts it on row 0 and is compared against row 0. Keyed on
     * the cursor alone — never on game, zone, route or frame index — so a
     * return whose phase differs from today's compares against the sample that
     * actually shares its instant.
     */
    private static void comparePosition(
            Map<String, FieldComparison> fields,
            ExpectedBoundary expected,
            ActualBoundary actual) {
        boolean onArmFrame = actual.destinationRowsConsumed() <= 0;
        Integer armX = expected.returnArmFrameX();
        Integer armY = expected.returnArmFrameY();
        if (expected.entry().savedXPos() != null) {
            put(fields, PREFIX + "position.x",
                    onArmFrame && armX != null
                            ? armX
                            : (int) expected.returnFrameZero().x(),
                    actual.playerCentreX());
        }
        if (expected.entry().savedYPos() != null) {
            put(fields, PREFIX + "position.y",
                    onArmFrame && armY != null
                            ? armY
                            : (int) expected.returnFrameZero().y(),
                    actual.playerCentreY());
        }
    }

    private static void compareNextAct(
            Map<String, FieldComparison> fields,
            ExpectedBoundary expected,
            ActualBoundary actual) {
        Integer preAct = expected.preEntryLevel().act();
        Integer returnAct = expected.returnLevel().act();
        if (preAct != null && returnAct != null) {
            put(fields, PREFIX + "next_act.manifest_advance",
                    true, !preAct.equals(returnAct));
            put(fields, PREFIX + "next_act.act",
                    engineAct(returnAct), actual.currentAct());
        }
        if (expected.returnLevel().zoneId() != null) {
            put(fields, PREFIX + "next_act.zone",
                    expected.resolvedReturnZone(), actual.currentZone());
        }
    }

    private static void compareEmeralds(
            Map<String, FieldComparison> fields,
            ExpectedBoundary expected,
            ActualBoundary actual) {
        if (actual.organicallyReproducedInterior()) {
            if (expected.exit().emeraldsAfter() != null) {
                put(fields, PREFIX + "emeralds.live",
                        expected.exit().emeraldsAfter(), actual.emeralds());
            }
            return;
        }
        if (expected.entry().emeraldsBefore() != null
                && expected.exit().emeraldsAfter() != null) {
            put(fields, PREFIX + "emeralds.recorded_progression",
                    expected.entry().emeraldsBefore() + 1,
                    expected.exit().emeraldsAfter());
        }
    }

    private static int engineAct(int manifestAct) {
        return Math.max(0, manifestAct - 1);
    }

    private static void put(
            Map<String, FieldComparison> fields,
            String name,
            Object expected,
            Object actual) {
        boolean match = Objects.equals(expected, actual);
        fields.put(name, new FieldComparison(
                name, String.valueOf(expected), String.valueOf(actual),
                match ? Severity.MATCH : Severity.ERROR,
                delta(expected, actual)));
    }

    private static int delta(Object expected, Object actual) {
        if (expected instanceof Number expectedNumber
                && actual instanceof Number actualNumber) {
            long value = Math.abs(expectedNumber.longValue()
                    - actualNumber.longValue());
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        }
        return Objects.equals(expected, actual) ? 0 : 1;
    }
}
