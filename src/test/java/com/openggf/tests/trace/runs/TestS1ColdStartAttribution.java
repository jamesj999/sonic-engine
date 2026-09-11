package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attribution control for the tail of {@code s1-sonic-complete-withemeralds}.
 *
 * <p>When a change lets the chain reach segments it never reached before, the
 * ordinary chain gives no control arm for those segments: the unchanged code
 * stops earlier and never executes them, so their divergences cannot be
 * attributed either way. Segments 22, 23 and 24 became visible exactly that
 * way, and were undetermined as a result.
 *
 * <p>Segments 23 and 24 previously led with
 * {@code queue.s1_nemesis_plc.remaining_work rom=17 engine=20} at frame 1 --
 * the title-card release step consuming the destination's first recorded row.
 * The release now reports {@code SETUP_ONLY} when it ran a
 * pre-{@code Level_MainLoop} object pass, and both pins moved on to the next
 * divergence in their segment.
 *
 * <p>This boots the run <em>at</em> segment 22 via
 * {@link AbstractRunChainTest#assertChainReplayFromSegment}, carrying none of
 * the state segments 0-21 would have left. All three segments diverge
 * identically to the full chain -- same first-error frame, same field, same
 * ROM and engine values, and the same error counts. They are therefore owned
 * by their own entry conditions, are not carry-in, and are not caused by
 * whatever let the chain arrive there.
 *
 * <p><b>This is a characterisation pin, in the shape of
 * {@link TestS1CompleteEmeraldRunPrefix}'s ratcheting pins.</b> It asserts that
 * the defects are still <em>present and unchanged from a cold start</em>, which
 * is what makes them attributable. Fixing a defect will fail this test: update
 * the pin, and never relax one to keep it quiet. It is not a claim that the
 * divergences are acceptable.
 */
@RequiresRom(SonicGame.SONIC_1)
class TestS1ColdStartAttribution extends AbstractRunChainTest {

    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s1", "runs",
            "s1-sonic-complete-withemeralds");

    /** Segment 22 (`syz3_2`); segments 23 and 24 are `lz1` and `lz1_2`. */
    private static final int START_SEGMENT = 22;

    /**
     * The {@code lz1_2 -> lz2} handoff, which the full chain fails with
     * "Destination playback cursor advanced -216 frames", is <em>clean</em> from
     * a cold start at segment 24: the same {@code prepareAcrossLevelBoundary}
     * branch runs and lands the cursor exactly on the destination offset.
     *
     * <p>That makes the -216 the first item on this frontier shown to depend on
     * <em>arrival</em> state rather than on its own entry conditions -- the
     * opposite result to segments 22-24 above, from the same instrument. This
     * guards that asymmetry: if a cold start ever starts failing this handoff
     * too, the -216 stops being carry-in and the diagnosis changes.
     */
    @Test
    void coldStartCrossesTheLz1ToLz2HandoffCleanly() throws Exception {
        AssertionError failure = null;
        try {
            assertChainReplayFromSegment(RUN_DIR, 24);
        } catch (AssertionError e) {
            failure = e;
        }
        String report = failure == null ? "" : failure.getMessage();
        assertTrue(
                !report.contains("during level-load handoff (lz1_2 -> lz2"),
                "lz1_2 -> lz2 must still hand off cleanly from a cold start; "
                        + "if it does not, the -216 is no longer carry-in. "
                        + "Report:\n" + report);
    }

    @Test
    void segments22To24DivergeIdenticallyFromAColdStart() throws Exception {
        AssertionError failure = null;
        try {
            assertChainReplayFromSegment(RUN_DIR, START_SEGMENT);
        } catch (AssertionError e) {
            failure = e;
        }
        // Re-based: segment 22 boots as 0, so 23 and 24 are 1 and 2.
        String report = failure == null ? "" : failure.getMessage();
        // The boot segment's own comparison is written to its report but is
        // NOT raised as a chain axis -- a real limitation of booting at a
        // segment, and the reason this reads segment 0's report rather than
        // the failure message. Read the report, not the axis list.
        //
        // It reads THIS walk's in-memory copy, not the file: the file name is
        // keyed only on run id and re-based segment index, so the full chain's
        // real segment 0 lands on the same path from a parallel fork and used
        // to overwrite this one -- see AbstractRunChainTest#writtenSegmentReport.
        String bootReport = writtenSegmentReport(0);
        // Was 15,564 errors leading with x_sub at frame 8115. That whole axis
        // was one frame of speed-shoes: the shoes taken in this segment expired
        // one movement frame early, so the boosted acceleration was dropped a
        // frame before the ROM dropped it and every downstream position,
        // animation and dynamic-art field drifted for the rest of the segment.
        // What remains is the unrelated camera_y cluster in frames 475-518,
        // which self-heals; segment 22 now has NO non-camera physics mismatch
        // over its full 12,072 frames.
        assertTrue(
                bootReport.contains("\"errorCount\" : 44")
                        && !bootReport.contains("firstNonCameraPhysicsMismatch"),
                "segment 22 must still diverge identically from a cold start; "
                        + "if it was fixed, update this pin. Report:\n"
                        + bootReport);
        // Re-pinned from "16 errors leading with dynamic_art.edges at frame
        // 3124" to GREEN when Sonic_HurtStop's landing branch began writing the
        // walk animation (docs/s1disasm/_incObj/"01 Sonic.asm":1949). Those 16
        // were never an art defect: the segment's real first error was
        // player_mapping_frame at frame 3123 -- an ANIMATION-group field, which
        // firstNonCameraPhysicsMismatch cannot report because it is filtered to
        // PHYSICS -- and the absent DPLC transfer at 3124 was that divergence's
        // downstream symptom, one frame later by the ROM's ordinary DMA lag.
        // This pin is now strictly stronger than the one it replaces: no
        // divergence at all, rather than exactly sixteen.
        assertTrue(
                !report.contains("segment 1 of s1-sonic-complete-withemeralds "
                        + "diverged:"),
                "segment 23 must stay GREEN from a cold start; if it regressed, "
                        + "do not re-pin it to a divergence without establishing "
                        + "why. Report:\n" + report);
        // Updated when the water splash stopped consuming a level-object SST
        // slot: the LZ door now loads into ROM slot 34 instead of 45, so its
        // ascent no longer leads the ROM's by one 2px step and the frame-1337
        // edge-phase lead is gone. The remaining errors are a LATER, opposite-
        // signed divergence (engine now behind), not the same defect.
        //
        // Re-pinned 18221 -> 18205 when Solid_SideAir began clearing the
        // player's pushing bit unconditionally, as the ROM's Solid_NotPushing
        // does. First non-camera mismatch, frame and field are unchanged;
        // sixteen downstream errors on the same axis went away.
        //
        // Re-pinned 18205 -> 7176 when the Obj61 LZ blocks took the instance
        // solid-state latch key. Their real first error was
        // player_mapping_frame at frame 3181 -- an ANIMATION-group field
        // firstNonCameraPhysicsMismatch cannot report -- where the recording
        // publishes fr_Walk13 ($08) and the engine held fr_Push1 ($45): the
        // rising block's Solid_NoCollision tail could not find its own pushing
        // bit under the moving spawn key, so Solid_NotPushing never ran. The
        // frontier moved 11,564 frames later, to frame 14745, and the leading
        // field is now a PHYSICS one.
        //
        // Re-pinned 7176 -> GREEN when Obj64 started reading v_waterpos1
        // instead of v_waterpos2. Frame 14745 was the segment's FIRST error of
        // any group -- nothing diverged in the preceding 14,744 frames -- and
        // the whole 7,176 were its tail. The LZ bubble maker's underwater gate
        // reads the swayed water height; against the unswayed one it opened six
        // frames early, so the batch's large bubble spawned 77 frames early,
        // rose forty pixels too far and was inhaled as Sonic ran past. Like the
        // segment 23 pin above, this is now strictly stronger than the
        // divergence it replaces: no divergence at all, rather than exactly
        // 7,176.
        assertTrue(
                !report.contains("segment 2 of s1-sonic-complete-withemeralds "
                        + "diverged:"),
                "segment 24 must stay GREEN from a cold start; if it regressed, "
                        + "do not re-pin it to a divergence without establishing "
                        + "why. Report:\n" + report);
    }
}
