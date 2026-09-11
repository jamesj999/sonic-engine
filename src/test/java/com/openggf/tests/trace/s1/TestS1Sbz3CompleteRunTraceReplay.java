package com.openggf.tests.trace.s1;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import com.openggf.trace.TraceEvent;
import java.nio.file.Path;

/** S1 SBZ3 from the complete-run TAS (bk2 offset 181004, 8354 frames).
 *  zone()=5 is the engine gameplay-progression index (NOT ROM v_zone 1). */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Sbz3CompleteRunTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_1; }
    @Override protected int zone() { return 5; }
    @Override protected int act() { return 2; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s1/sbz3_completerun"); }
    @Override protected boolean compareObjectNearEvents() { return true; }
    @Override protected boolean shouldCompareObjectNearEvent(TraceEvent.ObjectNear near) {
        return "0x64".equalsIgnoreCase(near.objectType());
    }
}
