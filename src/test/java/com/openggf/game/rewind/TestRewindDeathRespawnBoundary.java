package com.openggf.game.rewind;

import com.openggf.game.session.GameplayModeContext;
import com.openggf.level.LevelManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

@RequiresRom(SonicGame.SONIC_2)
class TestRewindDeathRespawnBoundary {

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    void restoringBeforeDeathRespawnClearsPendingRespawnRequest() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        GameplayModeContext gm = TestEnvironment.activeGameplayMode();
        RewindRegistry registry = gm.getRewindRegistry();
        LevelManager levelManager = gm.getLevelManager();

        CompositeSnapshot beforeRespawnRequest = registry.capture();
        levelManager.requestRespawn();

        registry.restore(beforeRespawnRequest);

        assertFalse(levelManager.consumeRespawnRequest(),
                "rewinding before the death reset must not leave a stale respawn request queued");
    }

    @Test
    void restoringAfterDeathRespawnPreservesPendingRespawnRequest() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        GameplayModeContext gm = TestEnvironment.activeGameplayMode();
        RewindRegistry registry = gm.getRewindRegistry();
        LevelManager levelManager = gm.getLevelManager();

        levelManager.requestRespawn();
        CompositeSnapshot afterRespawnRequest = registry.capture();
        levelManager.consumeRespawnRequest();

        registry.restore(afterRespawnRequest);

        org.junit.jupiter.api.Assertions.assertTrue(levelManager.consumeRespawnRequest(),
                "rewinding to the death reset boundary should preserve the pending respawn request");
    }
}
