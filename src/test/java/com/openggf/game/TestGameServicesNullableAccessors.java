package com.openggf.game;

import com.openggf.game.session.EditorCursorState;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestGameServicesNullableAccessors {
    @BeforeEach void setUp() { TestEnvironment.resetAll(); }
    @AfterEach void tearDown() { SessionManager.clear(); }

    @Test
    void nullableAccessorsReturnNullWithoutGameplayMode() {
        // Post-migration: GameServices accessors resolve through the gameplay
        // mode context, so clearing the session is required.
        SessionManager.clear();
        assertFalse(GameServices.hasRuntime());
        assertNull(GameServices.cameraOrNull());
        assertNull(GameServices.levelOrNull());
        assertNull(GameServices.gameStateOrNull());
        assertNull(GameServices.timersOrNull());
        assertNull(GameServices.rngOrNull());
        assertNull(GameServices.parallaxOrNull());
        assertNull(GameServices.fadeOrNull());
        assertNull(GameServices.spritesOrNull());
        assertNull(GameServices.collisionOrNull());
        assertNull(GameServices.terrainCollisionOrNull());
        assertNull(GameServices.waterOrNull());
        assertNull(GameServices.bonusStageOrNull());
        assertNull(GameServices.animatedTileChannelGraphOrNull());
        assertNull(GameServices.specialRenderEffectRegistryOrNull());
        assertNull(GameServices.advancedRenderModeControllerOrNull());
    }

    @Test
    void nullableAccessorsReturnManagersWhenGameplayModeExists() {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        GameplayModeContext mode = SessionManager.getCurrentGameplayMode();
        assertNotNull(mode);
        assertSame(gameplayMode, mode);
        assertTrue(GameServices.hasRuntime());
        assertSame(mode.getCamera(), GameServices.cameraOrNull());
        assertSame(mode.getLevelManager(), GameServices.levelOrNull());
        assertSame(mode.getGameStateManager(), GameServices.gameStateOrNull());
        assertSame(mode.getTimerManager(), GameServices.timersOrNull());
        assertSame(mode.getRng(), GameServices.rngOrNull());
        assertSame(mode.getParallaxManager(), GameServices.parallaxOrNull());
        assertSame(mode.getFadeManager(), GameServices.fadeOrNull());
        assertSame(mode.getSpriteManager(), GameServices.spritesOrNull());
        assertSame(mode.getCollisionSystem(), GameServices.collisionOrNull());
        assertSame(mode.getTerrainCollisionManager(), GameServices.terrainCollisionOrNull());
        assertSame(mode.getWaterSystem(), GameServices.waterOrNull());
        assertSame(mode.getActiveBonusStageProvider(), GameServices.bonusStageOrNull());
        assertSame(mode.getAnimatedTileChannelGraph(), GameServices.animatedTileChannelGraphOrNull());
        assertSame(mode.getSpecialRenderEffectRegistry(), GameServices.specialRenderEffectRegistryOrNull());
        assertSame(mode.getAdvancedRenderModeController(), GameServices.advancedRenderModeControllerOrNull());
    }

    @Test
    void strictAccessorsStillThrowWithoutGameplayMode() {
        SessionManager.clear();
        assertThrows(IllegalStateException.class, GameServices::camera);
        assertThrows(IllegalStateException.class, GameServices::level);
        assertThrows(IllegalStateException.class, GameServices::gameState);
        assertThrows(IllegalStateException.class, GameServices::timers);
        assertThrows(IllegalStateException.class, GameServices::rng);
        assertThrows(IllegalStateException.class, GameServices::parallax);
        assertThrows(IllegalStateException.class, GameServices::fade);
        assertThrows(IllegalStateException.class, GameServices::sprites);
        assertThrows(IllegalStateException.class, GameServices::collision);
        assertThrows(IllegalStateException.class, GameServices::terrainCollision);
        assertThrows(IllegalStateException.class, GameServices::water);
        assertThrows(IllegalStateException.class, GameServices::bonusStage);
        assertThrows(IllegalStateException.class, GameServices::animatedTileChannelGraph);
        assertThrows(IllegalStateException.class, GameServices::specialRenderEffectRegistry);
        assertThrows(IllegalStateException.class, GameServices::advancedRenderModeController);
    }

    /**
     * Predicate-equivalence invariant: {@link GameServices#hasRuntime()} must
     * agree with the underlying {@code gameplayModeOrNull() != null} check
     * across state transitions. Editor mode entry now does a proper teardown
     * rather than parking, so the old parked-mode case is no longer applicable;
     * the invariant still holds across the remaining lifecycle states.
     */
    @Test
    void hasRuntimeAgreesWithGameplayModeAcrossLifecycle() {
        // 1. No gameplay mode, no session.
        SessionManager.clear();
        assertEquals(GameServices.cameraOrNull() != null, GameServices.hasRuntime(),
                "no-gameplay-mode: hasRuntime() must match gameplay mode availability");
        assertFalse(GameServices.hasRuntime());

        // 2. Active gameplay mode.
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        assertNotNull(gameplayMode);
        assertEquals(GameServices.cameraOrNull() != null, GameServices.hasRuntime(),
                "active: hasRuntime() must match gameplay mode availability");
        assertTrue(GameServices.hasRuntime());

        // 3. Editor mode active: entering editor mode destroys the gameplay mode while preserving the world session.
        SessionManager.enterEditorMode(new EditorCursorState(0, 0));
        assertEquals(GameServices.cameraOrNull() != null, GameServices.hasRuntime(),
                "editor: hasRuntime() must match gameplay mode availability");
        assertFalse(GameServices.hasRuntime(), "editor: no gameplay mode is active");

        // 4. Resumed from editor: fresh gameplay mode.
        GameplayModeContext resumedMode = SessionManager.resumeGameplayFromEditor();
        TestEnvironment.activeGameplayMode();
        assertEquals(GameServices.cameraOrNull() != null, GameServices.hasRuntime(),
                "post-resume: hasRuntime() must agree with gameplay mode availability");
        assertTrue(GameServices.hasRuntime(), "post-resume: gameplay should be active");

        // 5. Fully torn down.
        SessionManager.clear();
        assertEquals(GameServices.cameraOrNull() != null, GameServices.hasRuntime(),
                "post-destroy: hasRuntime() must match gameplay mode availability");
        assertFalse(GameServices.hasRuntime());
    }

    /**
     * Repeated bonusStage() calls should be safe and stable without changing
     * the active gameplay mode.
     */
    @Test
    void bonusStageDoesNotChangeLiveGameplayModeOnRepeatedCalls() {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        GameplayModeContext mode = SessionManager.getCurrentGameplayMode();
        assertNotNull(mode);

        // First call returns NoOp default.
        BonusStageProvider firstCall = GameServices.bonusStage();
        assertNotNull(firstCall);

        // Gameplay mode must still be live and unchanged.
        assertSame(mode, SessionManager.getCurrentGameplayMode());

        // Many repeated calls remain stable.
        for (int i = 0; i < 5; i++) {
            BonusStageProvider repeated = GameServices.bonusStage();
            assertSame(firstCall, repeated, "bonusStage() must return the same provider when unchanged");
            assertSame(gameplayMode, SessionManager.getCurrentGameplayMode(),
                    "bonusStage() must not change the active gameplay mode on repeated calls");
        }
    }
}


