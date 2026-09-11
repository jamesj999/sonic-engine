package com.openggf.game;

import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameResult;
import com.openggf.LevelFrameStep;
import com.openggf.LevelFrameTestStep;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies ROM-accurate in-game pause ({@code Game_paused} / {@code Pause_Loop}).
 * <p>
 * No ROM or OpenGL required: the pause decision is pure game-state + input-edge
 * logic in {@link GameStateManager#applyPauseToggle(boolean)}, and the
 * frame-skip wiring in {@link LevelFrameStep#executeWithPause} short-circuits
 * before touching any level managers when paused.
 * <p>
 * ROM references (universal Start-edge toggle across all three games; per-game
 * pause divergences are debug-only cheats gated by {@code Slow_motion_flag} and
 * inert in normal play):
 * <ul>
 *   <li>S1 {@code PauseGame} / {@code Pause_Loop} —
 *       {@code docs/s1disasm/_inc/PauseGame.asm:5-54}</li>
 *   <li>S2 {@code PauseGame} / {@code Pause_Loop} —
 *       {@code docs/s2disasm/s2.asm:1585-1633}</li>
 *   <li>S3K {@code Pause_Game} / {@code Pause_Loop} sitting at the top of
 *       {@code LevelLoop} — {@code docs/skdisasm/s3.asm:1690-1761},
 *       {@code docs/skdisasm/sonic3k.asm:7884-7894}</li>
 * </ul>
 */
class TestInGamePause {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    // ---- Pure toggle semantics (GameStateManager.applyPauseToggle) ----

    @Test
    void startEdgeTogglesPauseOnAndOff() {
        GameStateManager state = new GameStateManager(); // lives default 3 > 0

        assertFalse(state.isGamePaused(), "starts unpaused");

        // First Start-press edge: enter pause. Skip the level update this frame.
        assertTrue(state.applyPauseToggle(true), "Start press enters pause (skip frame)");
        assertTrue(state.isGamePaused());

        // Start released (held false) across paused frames: stay paused, keep skipping.
        assertTrue(state.applyPauseToggle(false), "remains paused while no Start edge");
        assertTrue(state.applyPauseToggle(false), "remains paused");
        assertTrue(state.isGamePaused());

        // Second Start-press edge: unpause. ROM clears Game_paused and the rest of
        // LevelLoop runs that very frame, so the level update must NOT be skipped.
        assertFalse(state.applyPauseToggle(true), "Start press resumes (run frame)");
        assertFalse(state.isGamePaused());

        // Subsequent frames with no edge run normally.
        assertFalse(state.applyPauseToggle(false), "stays running");
        assertFalse(state.isGamePaused());
    }

    @Test
    void heldStartDoesNotRetoggleWithoutEdge() {
        GameStateManager state = new GameStateManager();
        // A single edge enters pause; holding Start (reported as a single leading
        // edge by the caller, then false while held) must not flip it back.
        assertTrue(state.applyPauseToggle(true));
        assertTrue(state.applyPauseToggle(false));
        assertTrue(state.applyPauseToggle(false));
        assertTrue(state.isGamePaused(), "held Start (no new edge) keeps pause");
    }

    /** {@code tst.b (v_lives).w; beq.s .unpauseGame}: a game over cannot be paused. */
    @Test
    void zeroLivesCannotPause() {
        GameStateManager state = new GameStateManager();
        state.loseLife();
        state.loseLife();
        state.loseLife(); // 3 -> 0
        assertEquals(0, state.getLives());

        assertFalse(state.applyPauseToggle(true),
                "PauseGame returns before the Start test once Life_count is zero");
        assertFalse(state.isGamePaused());
    }

    /** The same early-out clears {@code f_pause}, releasing a pause held over the last life. */
    @Test
    void losingLastLifeWhilePausedReleasesThePause() {
        GameStateManager state = new GameStateManager();
        assertTrue(state.applyPauseToggle(true));
        assertTrue(state.isGamePaused());

        state.loseLife();
        state.loseLife();
        state.loseLife(); // 3 -> 0

        assertFalse(state.applyPauseToggle(false),
                ".unpauseGame writes f_pause = 0 on the next frame");
        assertFalse(state.isGamePaused());
    }

    // ---- Frame-skip wiring (LevelFrameStep.executeWithPause) ----

    @Test
    void pausedFrameSkipsLevelUpdateButFrameStillAdvances() {
        GameplayModeContext mode = SessionManager.getCurrentGameplayMode();
        GameStateManager state = GameServices.gameState();
        assertFalse(state.isGamePaused());

        LevelFrameContext context = LevelFrameContext.from(mode);

        // Simulate the per-frame "frame counter advances + input consumed"
        // bookkeeping that the caller (HeadlessTestRunner / GameLoop) performs
        // around executeWithPause regardless of pause state.
        AtomicInteger frameCounter = new AtomicInteger(0);
        AtomicInteger spriteUpdates = new AtomicInteger(0);
        Runnable advanceFrame = frameCounter::incrementAndGet;
        Runnable spriteUpdate = spriteUpdates::incrementAndGet;

        // Frame 1: Start-press edge enters pause. The level update (spriteUpdate)
        // must be skipped, but the frame counter still advances. executeWithPause
        // short-circuits before touching level managers, so null camera/levelManager
        // are never dereferenced on the paused path.
        advanceFrame.run();
        LevelFrameResult frame1 = LevelFrameTestStep.executeWithPause(
                context, /* levelManager */ null, /* camera */ null,
                spriteUpdate, /* startEdgePressed */ true, LevelFrameStep.DIRECT_WRAPPER);
        assertEquals(LevelFrameResult.PAUSED, frame1);
        assertTrue(state.isGamePaused());
        assertEquals(1, frameCounter.get(), "frame counter advanced while paused");
        assertEquals(0, spriteUpdates.get(), "no level update while paused");

        // Frame 2: Start released (no edge), still paused -> skip again.
        advanceFrame.run();
        LevelFrameResult frame2 = LevelFrameTestStep.executeWithPause(
                context, null, null, spriteUpdate, false, LevelFrameStep.DIRECT_WRAPPER);
        assertEquals(LevelFrameResult.PAUSED, frame2);
        assertEquals(2, frameCounter.get(), "frame counter advanced again while paused");
        assertEquals(0, spriteUpdates.get(), "still no level update while paused");
        assertTrue(state.isGamePaused());

        // Frame 3: second Start-press edge resumes. applyPauseToggle clears the
        // pause and reports "run the frame"; the level update must run again.
        // We assert via the toggle + state here (driving the full execute() body
        // would require a ROM-loaded level), matching ROM behaviour where the
        // remainder of LevelLoop runs on the unpause frame.
        boolean wouldSkipFrame3 = state.applyPauseToggle(true);
        assertFalse(wouldSkipFrame3, "second Start press resumes the level update");
        assertFalse(state.isGamePaused());
    }
}
