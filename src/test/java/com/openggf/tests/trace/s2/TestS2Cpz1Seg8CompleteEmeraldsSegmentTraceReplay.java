package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of {@code seg8_cpz1} — the CPZ act 1 level segment of the
 * committed {@code s2-sonic-tails-complete-emeralds} run (bk2 offset 61206,
 * 6613 rows), manifest segment 12.
 *
 * <p>Until this class existed the only thing driving those 6613 rows was
 * {@code TestS2CompleteEmeraldRunChain}, and a divergence inside them surfaced
 * there as a walk failure ("segment 12 lost production ownership before source
 * closure") with no frame and no field: the chain's per-segment comparator
 * report is only written once the segment closes, which a divergence bad enough
 * to kill the player never lets happen. That is exactly how the Grabber dive
 * phase error below stayed invisible. The sibling
 * {@link TestS2Cpz2Seg9CompleteEmeraldsSegmentTraceReplay} exists for the same
 * reason.
 *
 * <p><b>Status.</b> Landed red at the commit that added it: 2 errors over
 * 6613 rows, both single-frame {@code tails_status_byte} mismatches (frames
 * 2700 and 2797), expected {@code 0x0003} actual {@code 0x000B}. Those are the
 * two frames the CPZ act 1 warp-pipe exit spring throws Tails: {@code loc_296C2}
 * clears {@code status.player.on_object} as it launches
 * (docs/s2disasm/s2.asm:56455-56462) and the engine did not. Green since that
 * {@code bclr} was ported.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Cpz1Seg8CompleteEmeraldsSegmentTraceReplay extends AbstractTraceReplayTest {

    @Override
    protected SonicGame game() {
        return SonicGame.SONIC_2;
    }

    @Override
    protected int zone() {
        return Sonic2ZoneConstants.ZONE_CPZ;
    }

    @Override
    protected int act() {
        return 0;
    }

    @Override
    protected Path traceDirectory() {
        return Path.of("src", "test", "resources", "traces", "s2", "runs",
                "s2-sonic-tails-complete-emeralds", "seg8_cpz1");
    }
}
