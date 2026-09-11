package com.openggf.game.rewind;

import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.sonic1.objects.badniks.Sonic1BallHogBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1BatbrainBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1BombBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1BurrobotBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1BuzzBomberBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1ChopperBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1CrabmeatBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1JawsBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1MotobugBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1NewtronBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1RollerBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1YadrinBadnikInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestS1BadnikGenericRecreate {

    @ParameterizedTest(name = "{0} round-trips via generic recreate")
    @ValueSource(classes = {
            Sonic1BallHogBadnikInstance.class,
            Sonic1BatbrainBadnikInstance.class,
            Sonic1BombBadnikInstance.class,
            Sonic1BurrobotBadnikInstance.class,
            Sonic1BuzzBomberBadnikInstance.class,
            Sonic1ChopperBadnikInstance.class,
            Sonic1CrabmeatBadnikInstance.class,
            Sonic1JawsBadnikInstance.class,
            Sonic1MotobugBadnikInstance.class,
            Sonic1NewtronBadnikInstance.class,
            Sonic1RollerBadnikInstance.class,
            Sonic1YadrinBadnikInstance.class
    })
    void s1BadnikRoundTripsThroughGenericRecreate(Class<?> objectClass) {
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(objectClass.getName());

        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                objectClass.getName() + " should round-trip via RewindRecreatable generic recreate");
    }
}
