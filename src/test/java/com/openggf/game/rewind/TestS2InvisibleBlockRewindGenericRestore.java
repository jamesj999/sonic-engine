package com.openggf.game.rewind;

import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.sonic2.objects.InvisibleBlockObjectInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestS2InvisibleBlockRewindGenericRestore {

    @Test
    void invisibleBlockRoundTripsThroughGenericRecreate() {
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(
                InvisibleBlockObjectInstance.class.getName());

        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                InvisibleBlockObjectInstance.class.getName()
                        + " should round-trip via RewindRecreatable generic recreate");
    }
}
