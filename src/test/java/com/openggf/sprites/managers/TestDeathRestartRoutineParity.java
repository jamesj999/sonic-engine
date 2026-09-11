package com.openggf.sprites.managers;

import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SecondaryAbility;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The death row crossing writes the routine number, the restart delay and the
 * life subtraction on one frame, and zeroes the delay again when that
 * subtraction produced a game over.
 *
 * <p>ROM: S1 {@code Sonic_HandleDeath} (docs/s1disasm/_incObj/01
 * Sonic.asm:2011-2049) and {@code Sonic_ResetLevel} (:2062-2073); the same
 * shape appears in S2 {@code CheckGameOver} (docs/s2disasm/s2.asm:38279-38352)
 * and S3K {@code loc_12432} (docs/skdisasm/sonic3k.asm:24581-24616).
 *
 * <p>No recorded trace column carries lives or the restart flag, so this is the
 * only coverage the crossing frame has.
 */
@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestDeathRestartRoutineParity {

    private GameModule previousModule;
    private AbstractPlayableSprite sprite;

    @BeforeEach
    void setUp() {
        previousModule = GameModuleRegistry.getCurrent();
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        sprite = new AbstractPlayableSprite("sonic", (short) 0, (short) 0) {
            @Override
            protected void defineSpeeds() {
                this.max = 1536;
                this.runAccel = 12;
                this.runDecel = 128;
                this.slopeRunning = 32;
                this.friction = 12;
                this.jump = 1664;
                this.slopeRollingDown = 80;
                this.slopeRollingUp = 20;
            }

            @Override
            protected void createSensorLines() {
            }

            @Override
            public void draw() {
            }

            @Override
            public SecondaryAbility getSecondaryAbility() {
                return SecondaryAbility.INSTA_SHIELD;
            }
        };
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        if (previousModule != null) {
            GameModuleRegistry.setCurrent(previousModule);
        } else {
            GameModuleRegistry.reset();
        }
    }

    /** {@code move.w #60,restartime} plus {@code subq.b #1,(v_lives)} on one frame. */
    @Test
    void crossingTakesTheLifeImmediatelyAndArmsSixtyFrames() throws Exception {
        GameStateManager gameState = activeGameState();
        int livesBefore = gameState.getLives();
        sprite.setDead(true);

        crossDeathRow(gameState);

        assertEquals(livesBefore - 1, gameState.getLives(),
                "the life comes off on the crossing frame, not at restart");
        assertTrue(sprite.isInDeathRestartRoutine(), "routine 8 is entered on the crossing frame");
        assertEquals(60, sprite.getDeathCountdown(), "restartime is armed with 60");
    }

    /** {@code Sonic_ResetLevel} writes the restart flag on the sixtieth decrement. */
    @Test
    void armedDelayRestartsOnTheSixtiethDecrement() {
        sprite.setDead(true);
        sprite.enterDeathRestartRoutine(60);

        for (int frame = 1; frame < 60; frame++) {
            assertFalse(sprite.tickDeathCountdown(),
                    "restart must not be requested on frame " + frame);
        }
        assertTrue(sprite.tickDeathCountdown(), "the sixtieth decrement writes f_restart");
    }

    /** Game over: the life still comes off, but {@code restartime} goes back to zero. */
    @Test
    void gameOverTakesTheLastLifeAndNeverRestarts() throws Exception {
        GameStateManager gameState = activeGameState();
        while (gameState.getLives() > 1) {
            gameState.loseLife();
        }
        sprite.setDead(true);

        crossDeathRow(gameState);

        assertEquals(0, gameState.getLives(), "the last life is still subtracted");
        assertTrue(sprite.isInDeathRestartRoutine(),
                "the routine is entered either way, so the corpse still stops falling");
        assertEquals(0, sprite.getDeathCountdown(), "restartime is rewritten to zero");
    }

    /** A zero delay is never counted down, so the restart flag is never written. */
    @Test
    void zeroDelayNeverRequestsARestart() {
        sprite.setDead(true);
        sprite.enterDeathRestartRoutine(0);

        for (int frame = 0; frame < 600; frame++) {
            assertFalse(sprite.tickDeathCountdown(),
                    "a zero restartime must never request a restart");
        }
        assertTrue(sprite.isInDeathRestartRoutine(), "the corpse stays held in the routine");
    }

    /** Clearing the countdown is the engine's whole-death-state reset, so it leaves the routine. */
    @Test
    void clearingTheCountdownLeavesTheRoutine() {
        sprite.setDead(true);
        sprite.enterDeathRestartRoutine(60);
        assertTrue(sprite.isInDeathRestartRoutine());

        sprite.setDeathCountdown(0);

        assertFalse(sprite.isInDeathRestartRoutine(),
                "respawn and rewind resets take the routine number back with the delay");
    }

    /**
     * The movement manager reads the live game state ahead of its bootstrap
     * reference, so the crossing must be measured against the registered one.
     */
    private static GameStateManager activeGameState() {
        GameStateManager gameState = GameServices.gameStateOrNull();
        assertNotNull(gameState, "the gameplay-mode environment registers a game state");
        return gameState;
    }

    private void crossDeathRow(GameStateManager gameState) throws Exception {
        PlayableSpriteMovement movement =
                new PlayableSpriteMovement(sprite, null, gameState);
        Method method = PlayableSpriteMovement.class
                .getDeclaredMethod("enterDeathRestartRoutine");
        method.setAccessible(true);
        method.invoke(movement);
    }
}
