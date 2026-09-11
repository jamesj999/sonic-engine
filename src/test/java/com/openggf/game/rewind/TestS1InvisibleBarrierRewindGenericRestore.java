package com.openggf.game.rewind;

import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.sonic1.objects.Sonic1InvisibleBarrierObjectInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestS1InvisibleBarrierRewindGenericRestore {

    @Test
    void invisibleBarrierRoundTripsThroughGenericRecreate() {
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(
                Sonic1InvisibleBarrierObjectInstance.class.getName());

        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                Sonic1InvisibleBarrierObjectInstance.class.getName()
                        + " should round-trip via RewindRecreatable generic recreate");
    }
}
