package com.openggf.game.rewind;

import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.sonic1.objects.bosses.Sonic1SYZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2CPZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2EHZBossInstance;
import com.openggf.game.sonic2.objects.bosses.Sonic2WFZBossInstance;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestServiceRequiredBossRootRewindRoundTrip {

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void wfzBossRootRoundTripsThroughObjectConstructionContext() {
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(
                Sonic2WFZBossInstance.class.getName());

        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                "WFZ boss root must recreate with restore-time ObjectServices");
    }

    @Test
    void constructorSpawningBossRootsStayGraphOnlyInTheIsolatedSweep() {
        for (Map.Entry<String, String> expectedCoverage : constructorSpawningBossRoots().entrySet()) {
            String className = expectedCoverage.getKey();
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(className);

            RoundTripSweepResult.GraphCovered covered =
                    assertInstanceOf(RoundTripSweepResult.GraphCovered.class, result,
                            className + " needs graph harness coverage because its constructor spawns children");
            assertEquals(expectedCoverage.getValue(), covered.evidence());
        }
    }

    private static Map<String, String> constructorSpawningBossRoots() {
        return Map.of(
                Sonic1SYZBossInstance.class.getName(), "TestS1SyzBossBlockGraphRewind",
                Sonic2CPZBossInstance.class.getName(), "TestS2CpzBossGraphRewind",
                Sonic2EHZBossInstance.class.getName(), "TestS2EhzBossGraphRewind");
    }
}
