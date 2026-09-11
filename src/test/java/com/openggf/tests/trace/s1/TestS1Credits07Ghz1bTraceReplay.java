package com.openggf.tests.trace.s1;
import com.openggf.trace.*;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractCreditsDemoTraceReplayTest;

import java.nio.file.Path;

@RequiresRom(SonicGame.SONIC_1)
public class TestS1Credits07Ghz1bTraceReplay extends AbstractCreditsDemoTraceReplayTest {

    @Override
    protected int creditsDemoIndex() { return 7; }

    @Override
    protected Path traceDirectory() {
        return Path.of("src/test/resources/traces/s1/credits_07_ghz1b");
    }
}


