package com.openggf.game.rewind;

import com.openggf.physics.Sensor;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestPlayableSpriteRewindState {

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void restoreRecomputesPushSensorOffsetFromRestoredAirState() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();

        Sonic source = new Sonic("source", (short) 100, (short) 100);
        source.setAir(true);
        source.setRolling(true);

        Sonic target = new Sonic("target", (short) 100, (short) 100);
        target.setAir(false);
        target.updatePushSensorYOffset();
        assertEquals(8, pushSensorY(target),
                "test setup should start with a grounded push-sensor Y offset");

        target.restoreRewindState(source.captureRewindState());

        assertTrue(target.getAir());
        assertEquals(0, pushSensorY(target),
                "restored airborne players must not keep the grounded push-sensor Y offset");
    }

    @Test
    void restoreRewindStateRestoresDebugModeFlag() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();

        Sonic source = new Sonic("source", (short) 100, (short) 100);
        source.setDebugMode(true);

        Sonic target = new Sonic("target", (short) 100, (short) 100);
        target.setDebugMode(false);

        target.restoreRewindState(source.captureRewindState());

        assertTrue(target.isDebugMode());
    }

    private static int pushSensorY(Sonic sonic) {
        Sensor[] pushSensors = sonic.getPushSensors();
        return pushSensors[0].getY();
    }
}
