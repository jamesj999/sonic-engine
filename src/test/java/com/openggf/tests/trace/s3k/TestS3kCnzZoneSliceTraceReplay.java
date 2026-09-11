package com.openggf.tests.trace.s3k;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import java.nio.file.Path;

/** S3K CNZ from the Sonic+Tails complete-run TAS. Per-zone segment: act1 -> seamless act1->act2 transition -> act2 -> the act2->next-zone exit handoff. zone()=3 (S3K zone_id == engine index); act()=0. */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kCnzZoneSliceTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_3K; }
    @Override protected int zone() { return 3; }
    @Override protected int act() { return 0; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s3k/cnz_completerun"); }
}
