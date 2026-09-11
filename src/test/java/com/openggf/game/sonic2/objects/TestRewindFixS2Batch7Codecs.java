package com.openggf.game.sonic2.objects;

import com.openggf.game.rewind.RewindRoundTripHarness;
import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.level.objects.SignpostSparkleObjectInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Verifies the S2 batch-7 signpost sparkle moved from its old shared codec onto
 * generic recreate.
 *
 * <p>The sparkle has a null base spawn and stores its position in non-final
 * {@code worldX}/{@code worldY}. Generic recreate constructs a zero-position
 * placeholder through its {@code (int,int)} constructor and the compact scalar
 * restore reapplies the captured position.
 */
class TestRewindFixS2Batch7Codecs {

    @Test
    void signpostSparkleRoundTripsThroughGenericRecreate() {
        RoundTripSweepResult result =
                RewindRoundTripHarness.probeClass(SignpostSparkleObjectInstance.class.getName());

        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                "signpost sparkle should round-trip through generic recreate");
    }
}
