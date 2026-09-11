package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;

public class TestS2Htz2LevelSelectTraceReplay extends AbstractS2LevelSelectTraceReplayTest {
    public TestS2Htz2LevelSelectTraceReplay() {
        super("htz2", Sonic2ZoneConstants.ZONE_HTZ, 1);
    }

    @Override
    protected int overridePreTraceOscFrames() {
        return Integer.getInteger("htz2.osc.override", -1);
    }
}
