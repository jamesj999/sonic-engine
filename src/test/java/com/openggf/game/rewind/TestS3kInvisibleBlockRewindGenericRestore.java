package com.openggf.game.rewind;

import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.sonic3k.objects.Sonic3kInvisibleBlockObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kInvisibleHurtBlockHObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kInvisibleHurtBlockVObjectInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestS3kInvisibleBlockRewindGenericRestore {

    private static final List<Class<?>> INVISIBLE_BLOCK_FAMILY = List.of(
            Sonic3kInvisibleBlockObjectInstance.class,
            Sonic3kInvisibleHurtBlockHObjectInstance.class,
            Sonic3kInvisibleHurtBlockVObjectInstance.class);

    @Test
    void invisibleBlockFamilyRoundTripsThroughGenericRecreate() {
        for (Class<?> cls : INVISIBLE_BLOCK_FAMILY) {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(cls.getName());

            assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                    cls.getName() + " should round-trip via RewindRecreatable generic recreate");
        }
    }
}
