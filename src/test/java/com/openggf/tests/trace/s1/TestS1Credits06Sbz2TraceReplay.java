package com.openggf.tests.trace.s1;
import com.openggf.trace.*;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractCreditsDemoTraceReplayTest;

import java.nio.file.Path;

@RequiresRom(SonicGame.SONIC_1)
public class TestS1Credits06Sbz2TraceReplay extends AbstractCreditsDemoTraceReplayTest {

    @Override
    protected int creditsDemoIndex() { return 6; }

    @Override
    protected Path traceDirectory() {
        return Path.of("src/test/resources/traces/s1/credits_06_sbz2");
    }
}


