package com.openggf.tests;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@RequiresRom(SonicGame.SONIC_2)
class TestObjectManagerExecLoopParity {
    @Test
    void ehz1ExecLoopRemainsStableAcrossSpawnUnloadFrames() throws Exception {
        SharedLevel sharedLevel = SharedLevel.load(SonicGame.SONIC_2, 0, 0);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withSharedLevel(sharedLevel)
                    .build();
            assertDoesNotThrow(() -> fixture.stepIdleFrames(180));
        } finally {
            sharedLevel.dispose();
        }
    }
}
