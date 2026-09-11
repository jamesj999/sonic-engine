package com.openggf.tests.trace.s1;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import java.nio.file.Path;

/** S1 SLZ1 from the complete-run TAS (bk2 offset 136660, 6411 frames).
 *  zone()=4 is the engine gameplay-progression index (NOT ROM v_zone 3). */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Slz1CompleteRunTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_1; }
    @Override protected int zone() { return 4; }
    @Override protected int act() { return 0; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s1/slz1_completerun"); }
}
