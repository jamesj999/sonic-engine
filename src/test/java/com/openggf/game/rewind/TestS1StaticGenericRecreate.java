package com.openggf.game.rewind;

import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.sonic1.objects.Sonic1FlappingDoorObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1PylonObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1RockObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SpinningLightObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1WaterfallObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1WaterfallSoundObjectInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestS1StaticGenericRecreate {

    @ParameterizedTest(name = "{0} round-trips via generic recreate")
    @ValueSource(classes = {
            Sonic1FlappingDoorObjectInstance.class,
            Sonic1PylonObjectInstance.class,
            Sonic1RockObjectInstance.class,
            Sonic1SpinningLightObjectInstance.class,
            Sonic1WaterfallObjectInstance.class,
            Sonic1WaterfallSoundObjectInstance.class
    })
    void s1StaticObjectsRoundTripThroughGenericRecreate(Class<?> objectClass) {
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(objectClass.getName());

        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                objectClass.getName() + " should round-trip via RewindRecreatable generic recreate");
    }
}
