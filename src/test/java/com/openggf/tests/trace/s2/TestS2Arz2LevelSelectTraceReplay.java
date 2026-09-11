package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.trace.TraceEvent;

public class TestS2Arz2LevelSelectTraceReplay extends AbstractS2LevelSelectTraceReplayTest {
    public TestS2Arz2LevelSelectTraceReplay() {
        super("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1);
    }

    @Override
    protected boolean compareObjectNearEvents() {
        return true;
    }

    @Override
    protected boolean shouldCompareObjectNearEvent(TraceEvent.ObjectNear near) {
        return near.slot() >= 16;
    }
}
