package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chain integration test for an S3K level -> bonus-stage -> level round trip
 * (spec: docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md,
 * plan (c) Task 4). Drives ONE continuous {@code GameLoop} through all segments
 * of a {@link com.openggf.trace.TraceRunManifest} via the shared
 * {@link AbstractRunChainTest} base, asserting the engine organically raises
 * each transition and that boundary state (rings / star-post / emeralds) carries
 * over.
 *
 * <p>Skip-if-missing: both run directories are absent until the gumball and
 * pachinko round-trip recordings land, so both test methods currently SKIP
 * cleanly via {@link Assumptions#assumeTrue}. The generalized drive itself lives
 * in {@link AbstractRunChainTest}; this class only names its (still unrecorded)
 * three-segment fixtures. Comparison-only throughout.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kBonusRoundTripChain extends AbstractRunChainTest {

    private static final Path RUN_DIR_GUMBALL = Path.of(
            "src", "test", "resources", "traces", "s3k", "runs", "s3k-aiz-gumball-roundtrip");
    private static final Path RUN_DIR_PACHINKO = Path.of(
            "src", "test", "resources", "traces", "s3k", "runs", "s3k-aiz-pachinko-roundtrip");

    @Test
    void gumballRoundTrip() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(RUN_DIR_GUMBALL),
                "Run directory not found: " + RUN_DIR_GUMBALL);
        assertChainReplay(RUN_DIR_GUMBALL);
    }

    @Test
    void pachinkoRoundTrip() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(RUN_DIR_PACHINKO),
                "Run directory not found: " + RUN_DIR_PACHINKO);
        assertChainReplay(RUN_DIR_PACHINKO);
    }
}
