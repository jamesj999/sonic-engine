package com.openggf.tests.trace.s2;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

@RequiresRom(SonicGame.SONIC_2)
abstract class AbstractS2LevelSelectTraceReplayTest extends AbstractTraceReplayTest {

    private final String route;
    private final int zone;
    private final int act;

    AbstractS2LevelSelectTraceReplayTest(String route, int zone) {
        this(route, zone, 0);
    }

    AbstractS2LevelSelectTraceReplayTest(String route, int zone, int act) {
        this.route = route;
        this.zone = zone;
        this.act = act;
    }

    @Override
    protected SonicGame game() {
        return SonicGame.SONIC_2;
    }

    @Override
    protected int zone() {
        return zone;
    }

    @Override
    protected int act() {
        return act;
    }

    @Override
    protected Path traceDirectory() {
        String candidate = System.getProperty(
                "openggf.trace.candidate.dir");
        return candidate != null
                ? Path.of(candidate)
                : Path.of("src/test/resources/traces/s2").resolve(route);
    }
}
