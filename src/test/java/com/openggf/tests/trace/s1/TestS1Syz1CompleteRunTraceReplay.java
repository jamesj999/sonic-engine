package com.openggf.tests.trace.s1;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import java.nio.file.Path;

/** S1 SYZ1 from the complete-run TAS (bk2 offset 61548, 9729 frames).
 *  zone()=2 is the engine gameplay-progression index (NOT ROM v_zone 4). */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Syz1CompleteRunTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_1; }
    @Override protected int zone() { return 2; }
    @Override protected int act() { return 0; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s1/syz1_completerun"); }
}
