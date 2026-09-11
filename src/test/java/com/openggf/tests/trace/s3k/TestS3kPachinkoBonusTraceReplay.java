package com.openggf.tests.trace.s3k;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import java.nio.file.Path;

/** S3K Pachinko bonus stage trace replay. zone()=0x14 (20); act()=0. */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kPachinkoBonusTraceReplay extends AbstractS3kBonusStageTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_3K; }
    @Override protected int zone() { return 0x14; }
    @Override protected int act() { return 0; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s3k/bonus_pachinko"); }
}
