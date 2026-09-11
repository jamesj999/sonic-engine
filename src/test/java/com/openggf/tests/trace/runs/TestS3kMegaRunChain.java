package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Chain integration test for the committed
 * {@code s3-knux-multibonus-ss} run (25 segments, 22 transitions: gumball x2,
 * slots x5, pachinko, blue spheres x3, Knuckles, zones AIZ -> HCZ -> MGZ).
 * Drives ONE continuous {@code GameLoop} through every segment via the shared
 * {@link AbstractRunChainTest} base, exercising both the {@code bonus_stage}
 * per-frame comparator path and the {@code special_stage} advance-uncompared
 * path, plus every {@code starpost_bonus} / {@code giant_ring} return-boundary
 * assertion this run carries.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kMegaRunChain extends AbstractRunChainTest {

    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s3k", "runs", "s3-knux-multibonus-ss");

    @Test
    void multiBonusSpecialStageRoundTrip() throws Exception {
        assertChainReplay(RUN_DIR);
    }
}
