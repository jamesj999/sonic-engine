package com.openggf.level.objects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestScreenShakeTimerSlotObjectInstance {

    @Test
    void retainsItsSstSlotForTheNativeCountdown() {
        ScreenShakeTimerSlotObjectInstance timer = new ScreenShakeTimerSlotObjectInstance(180);

        for (int frame = 0; frame < 179; frame++) {
            timer.update(frame, null);
            assertFalse(timer.isDestroyed());
        }

        timer.update(179, null);
        assertTrue(timer.isDestroyed());
    }
}
