package com.openggf.tests.trace.s1;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import java.nio.file.Path;

/** S1 MZ1 from the complete-run TAS (bk2 offset 20791, 8060 frames).
 *  zone()=1 is the engine gameplay-progression index (NOT ROM v_zone 2). */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Mz1CompleteRunTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_1; }
    @Override protected int zone() { return 1; }
    @Override protected int act() { return 0; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s1/mz1_completerun"); }
}
