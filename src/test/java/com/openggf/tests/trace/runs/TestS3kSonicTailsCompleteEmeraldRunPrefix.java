package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Ratcheting parity frontier for the committed S3K Sonic+Tails emerald route.
 *
 * <p>{@link TestS3kSonicTailsCompleteEmeraldRunChain} is the binary frontier:
 * it drives the whole 63-segment route and is red until the route is finished.
 * That makes real progress invisible — a drive that reaches segment 5 and one
 * that stops in segment 0 both report simply "red" — and it makes a silent
 * regression indistinguishable from no progress at all. This class holds the
 * ratchet: a prefix pin at the deepest point the drive is known to reach, so
 * CI defends it.
 *
 * <p><b>Raise these pins as the frontier advances; never lower one to make a
 * run pass.</b> A pin that starts failing means the drive slid backwards, which
 * is exactly the regression the binary frontier cannot report. The measured
 * first-error frame and field for the live frontier belong in
 * {@code docs/status/trace-frontier-log.md}, as they always have.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kSonicTailsCompleteEmeraldRunPrefix extends AbstractRunChainTest {

    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s3k", "runs",
            "s3k-sonic-tails-complete-emeralds");

    /**
     * Segment 0 is AIZ1 through its giant-ring handoff. Reaching segment 1's
     * first driven row proves the whole {@code aiz} source was consumed and the
     * special-stage entry was taken.
     *
     * <p>Both halves of that boundary were closed on 2026-08-17. The tail is
     * exactly accountable: {@code SSEntryFlash_Init} spends one frame,
     * {@code SSEntryFlash_Main} walks {@code AniRaw_SSEntryFlash}'s nine mapping
     * frames to its {@code $F4} terminator, and {@code Obj_Wait}'s
     * {@code subq.w #1 / bmi} spends 33 on the {@code #$20} it is armed with —
     * 43 executions from the touch frame, putting {@code SSEntryFlash_GoSS}'s
     * {@code move.b #$34,(Game_mode).w} on touch+42.
     */
    @Test
    void aiz1ThroughGiantRingIntoFirstSpecialStageRow() throws Exception {
        assertChainReplayThroughSegmentRow(RUN_DIR, 1, 1);
    }
}
