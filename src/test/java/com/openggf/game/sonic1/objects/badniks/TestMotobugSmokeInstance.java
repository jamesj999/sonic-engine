package com.openggf.game.sonic1.objects.badniks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMotobugSmokeInstance {

    @Test
    void smokePersistsThroughAnimateRoutineAndDeletesOnFollowingTick() {
        Sonic1MotobugSmokeInstance smoke = new Sonic1MotobugSmokeInstance(0, 0, false);

        for (int i = 0; i < 23; i++) {
            smoke.update(i + 1, null);
            assertFalse(smoke.isDestroyed(),
                    "ROM smoke should survive through the afRoutine tick before Moto_Delete runs");
        }

        smoke.update(24, null);

        assertTrue(smoke.isDestroyed(),
                "Moto_Delete should remove the smoke on the tick after afRoutine advances the routine");
    }
}
