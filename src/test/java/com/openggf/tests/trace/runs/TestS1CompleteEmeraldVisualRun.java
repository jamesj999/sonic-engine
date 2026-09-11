package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the committed S1 emerald route through the production visual-session
 * owners rather than the chain fixture, so a defect that only the windowed
 * Trace Test Mode reaches is reproducible without a window.
 */
@RequiresRom(SonicGame.SONIC_1)
class TestS1CompleteEmeraldVisualRun {
    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s1", "runs",
            "s1-sonic-complete-withemeralds");

    @AfterEach
    void tearDown() {
        VisualRunReplayHarness.tearDown();
    }

    /**
     * The current frontier: GHZ1, the giant-ring handoff, the whole special
     * stage, the GHZ2 presentation bridge the stage returns through, and
     * admission of the GHZ2 gameplay the bridge hands back to.
     * <p>
     * The return gap's Sonic DPLC pair now matches the recording on identity
     * and row. Its row is the first frame of {@code GM_Level}'s counted
     * pre-main-loop tail — {@code Level_Delay}'s 4 frames plus
     * {@code PalFadeIn_Alt}'s 22 (docs/s1disasm/sonic.asm:2956-2969) — i.e.
     * main-loop admission minus 26, not the title card's release row. The
     * un-timed load steps between the two ({@code Hud_Base},
     * {@code LevelDataLoad}, {@code LoadTilesFromStart}) take real hardware
     * time in the recording, but no compared field observes their span: its
     * right edge is pinned by the counted tail and its left by the modelled
     * PLC drain. Raise the target here as later segments come in.
     */
    @Test
    void replaysThroughTheSpecialStageAndItsReturnBridgeAdmission() throws Exception {
        VisualRunReplayHarness.Result result =
                VisualRunReplayHarness.replay(
                        RUN_DIR, VisualRunReplayHarness.stopAfterSegment(3));

        assertEquals(3, result.currentSegmentIndex(),
                "visual run stalled before the GHZ2 return bridge: " + result);
    }

    /**
     * Walks every compared row of the returned GHZ2 act, the route's SECOND
     * giant ring, all 4,337 rows of the special stage behind it, its results
     * bridge, the GHZ3 presentation bridge it returns through, and then all
     * 8,520 rows of the GHZ3 act itself -- its boss, its prison capsule, the
     * animal window, and the end-of-act card. The pin above stops the instant
     * segment 3 is admitted, so it never reaches an act body; this one stops
     * only once segment 6 has published its LAST row, so a self-pause anywhere
     * inside four boundaries and three acts fails this instead.
     * <p>
     * Four defects live on this stretch. Row 107 of the GHZ2 act is its first
     * PLC completion that lands on an iteration held into a lag V-blank, where
     * the ROM's own {@code RunPLC} (docs/s1disasm/sonic.asm:3032) runs on the
     * lag closure rather than the row that completed the previous entry. The
     * giant ring is the second entry to a special stage in the run, and so the
     * first whose index ({@code special_stage_index} 1) differs from the stage
     * the provider still has loaded when the ring is touched. Inside the stage,
     * row 2,237's Sonic DPLC is the first observable consequence of an UP/DOWN
     * block that rewrote itself even when it could not change the rotation
     * speed (09 Sonic in Special Stage.asm:853-862, 888-897). And the GHZ3 act
     * is the first the run reaches through TWO results-screen bridges, so it is
     * the first whose object V-blank clock had accumulated both bridges' worth
     * of missing V-ints -- which de-phased the prison capsule's animal spawn
     * and escape-direction gates and armed the end-of-act PLC nine rows early.
     * <p>
     * It then crosses the route's FIRST plain act-to-act boundary. GHZ1 and
     * GHZ2 both left through a giant ring, so nothing before this exercised
     * {@code Got_NextLevel}'s {@code f_restart} write and the whole
     * {@code GM_Level} restart the level main loop falls back into
     * (docs/s1disasm/_incObj/3A Got Through Card.asm:200-211,
     * sonic.asm:3041-3055). That restart's {@code PaletteFadeOut}, its
     * mandatory locked title-card loop and MZ1's own load all run inside the
     * transition gap.
     * <p>
     * It then walks every compared row of MZ1 itself, whose last 170 rows are
     * the run's FIRST death. That death is a faithful reproduction of an
     * original-game bug, NOT an engine defect: crossing a camera boundary
     * backwards ratchets the camera upward and pushes Sonic into a death he
     * cannot avoid. Do not "fix" it. MZ1's descending bottom boundary catches
     * Sonic mid-roll, so the act ends through {@code Sonic_LevelBound} rather
     * than a signpost: {@code DynamicLevelEvents} snaps {@code v_limitbtm2} to
     * the camera and ratchets it upward, the camera rides it up through a
     * {@code v_limittop2} it is allowed to pass, the pit kill fires, and
     * {@code Sonic_HandleDeath} freezes the corpse for a 60-frame restart
     * delay. The ratchet is the bug; reproducing it is the point, so tidying
     * the boundary logic would break this lane rather than improve it. The pin stops once MZ1 has published its last row; the
     * death-restart boundary behind it is the route's first, and it now
     * replays: {@code Sonic_ResetLevel}'s sixtieth decrement lands on the first
     * row of the transition gap, which is the source level's own last
     * {@code Level_MainLoop} iteration rather than a row the gap owns
     * (sonic.asm:3009-3018). The lane then walks all 8,684 rows of MZ1's
     * restarted act, the route's THIRD giant ring and the special stage behind
     * it, MZ2's presentation bridge, and all 3,728 rows of MZ2 -- which ends in
     * the run's SECOND death, so the boundary is exercised twice.
     */
    @Test
    void replaysTheSecondGiantRingAndTheSpecialStageBehindIt() throws Exception {
        VisualRunReplayHarness.Result result =
                VisualRunReplayHarness.replay(
                        RUN_DIR, VisualRunReplayHarness.stopAfterSegmentBody(11));

        assertTrue(result.sharedCursor() > 46_804,
                "visual run stopped inside the MZ2 act: " + result);
        assertEquals(11, result.currentSegmentIndex(),
                "visual run stalled before MZ2's second half: " + result);
    }
}
