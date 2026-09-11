package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for HTZ Act 2 level event routine 9 (post-boss camera behavior).
 */
public class TestHTZBossEventRoutine9 {

    private Camera camera;
    private Sonic2LevelEventManager levelEvents;

    @BeforeEach
    public void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_2);
        GameServices.camera().resetState();
        GameServices.gameState().resetSession();

        camera = GameServices.camera();
        levelEvents = (Sonic2LevelEventManager) GameServices.module().getLevelEventProvider();
        levelEvents.initLevel(Sonic2LevelEventManager.ZONE_HTZ, 1); // HTZ Act 2
        levelEvents.setEventRoutine(18); // Routine 9
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void routine9DoesNothingWhileBossStillActive() {
        GameServices.gameState().setCurrentBossId(3); // HTZ boss active

        camera.setX((short) 0x30E8);
        camera.setMinX((short) 0x2EE0);
        camera.setMinY((short) 0x42A);
        camera.setMaxY((short) 0x432); // also sets maxY target

        levelEvents.update();

        assertEquals(0x2EE0, camera.getMinX());
        assertEquals(0x42A, camera.getMinY());
        assertEquals(0x432, camera.getMaxYTarget());
    }

    @Test
    public void routine9DoesNotUnlockJustBecauseCurrentBossIdCleared() {
        GameServices.gameState().setCurrentBossId(0);

        camera.setX((short) 0x30E0);
        camera.setMinX((short) 0x2EE0);
        camera.setMinY((short) 0x42A);
        camera.setMaxY((short) 0x432); // also sets maxY target

        levelEvents.update();

        assertEquals((short) 0x2EE0, camera.getMinX());
        assertEquals((short) 0x42A, camera.getMinY());
        assertEquals((short) 0x432, camera.getMaxYTarget());
    }

    @Test
    public void routine9UnlocksAfterBossDefeatedFlagAndEasesVerticalBounds() {
        GameServices.gameState().setCurrentBossId(3); // ROM Current_Boss_ID remains set
        GameServices.gameState().setBossDefeatedFlag(true);

        camera.setX((short) 0x30E0);
        camera.setMinX((short) 0x2EE0);
        camera.setMinY((short) 0x42A);
        camera.setMaxY((short) 0x432); // also sets maxY target

        levelEvents.update();

        assertEquals((short) 0x30E0, camera.getMinX());
        assertEquals((short) 0x428, camera.getMinY());
        assertEquals((short) 0x430, camera.getMaxYTarget());
    }
}
