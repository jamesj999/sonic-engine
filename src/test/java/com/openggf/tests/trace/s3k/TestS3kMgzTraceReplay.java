package com.openggf.tests.trace.s3k;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kMgzTraceReplay extends AbstractTraceReplayTest {

    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s3k/mgz");

    @Override
    protected SonicGame game() {
        return SonicGame.SONIC_3K;
    }

    @Override
    protected int zone() {
        return 0x02; // MGZ
    }

    @Override
    protected int act() {
        // 0-based: 0 = Act 1. The recorder metadata writes "act": 1
        // (1-based), matching the convention documented in the CNZ replay.
        return 0x00;
    }

    @Override
    protected Path traceDirectory() {
        return TRACE_DIR;
    }

}
