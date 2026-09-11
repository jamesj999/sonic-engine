package com.openggf.tests.trace.s1;
import com.openggf.trace.*;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Trace replay test for Sonic 1 Marble Zone Act 1.
 *
 * <p>Requires a Sonic 1 ROM and a BK2 recording in the trace directory.
 * Skipped when the ROM is unavailable or when the trace directory has no .bk2 file.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Mz1TraceReplay extends AbstractTraceReplayTest {

    @Override
    protected SonicGame game() { return SonicGame.SONIC_1; }

    // Engine zone index 1 = Marble Zone (gameplay progression order: GHZ=0, MZ=1, SYZ=2, LZ=3...).
    // NOT the ROM's v_zone value (GHZ=0, LZ=1, MZ=2, SLZ=3, SYZ=4, SBZ=5).
    @Override
    protected int zone() { return 1; }

    @Override
    protected int act() { return 0; }

    @Override
    protected Path traceDirectory() {
        return Path.of("src/test/resources/traces/s1/mz1_fullrun");
    }

    // Temporary: override to scan for best oscillation pre-advance value.
    // Set via -Dosc.override=N system property, or -1 to use metadata.
    @Override
    protected int overridePreTraceOscFrames() {
        String prop = System.getProperty("osc.override");
        return prop != null ? Integer.parseInt(prop) : -1;
    }
}


