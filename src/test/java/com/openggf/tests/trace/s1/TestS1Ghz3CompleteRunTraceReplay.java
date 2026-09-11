package com.openggf.tests.trace.s1;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import java.nio.file.Path;

/** S1 GHZ3 from the complete-run TAS (bk2 offset 10885, 9678 frames).
 *  zone()=0 is the engine gameplay-progression index (NOT ROM v_zone 0). */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Ghz3CompleteRunTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_1; }
    @Override protected int zone() { return 0; }
    @Override protected int act() { return 2; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s1/ghz3_completerun"); }
}
