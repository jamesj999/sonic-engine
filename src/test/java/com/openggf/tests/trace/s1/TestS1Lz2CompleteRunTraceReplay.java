package com.openggf.tests.trace.s1;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import com.openggf.trace.TraceEvent;
import java.nio.file.Path;

/** S1 LZ2 from the complete-run TAS (bk2 offset 106944, 10173 frames).
 *  zone()=3 is the engine gameplay-progression index (NOT ROM v_zone 1). */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Lz2CompleteRunTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_1; }
    @Override protected int zone() { return 3; }
    @Override protected int act() { return 1; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s1/lz2_completerun"); }
    @Override protected boolean compareObjectNearEvents() { return true; }
    @Override protected boolean shouldCompareObjectNearEvent(TraceEvent.ObjectNear near) {
        return "0x64".equalsIgnoreCase(near.objectType());
    }
}
