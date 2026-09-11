package com.openggf.game;

import com.openggf.GameLoop;
import com.openggf.control.InputHandler;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the real {@link GameLoop} with a Mockito-mocked
 * {@link LegalDisclaimerScreen} to verify that {@code step()}:
 *   1. calls {@code disclaimer.update(...)} every frame while in
 *      {@link GameMode#LEGAL_DISCLAIMER},
 *   2. invokes the registered exit handler exactly once when
 *      {@code isDismissed()} flips to {@code true},
 *   3. honors a mode transition performed inside the exit handler so
 *      the disclaimer stops being polled on subsequent frames.
 *
 * <p>The third assertion catches the failure mode where the dispatch
 * fires the handler but the handler (or {@code Engine.exitLegalDisclaimer})
 * fails to change the game mode — the disclaimer would otherwise be
 * polled forever with the handler permanently nulled.
 *
 * <p>This test does <strong>not</strong> exercise the real
 * {@link com.openggf.Engine#exitLegalDisclaimer} because that method
 * touches GL ({@code cleanup}, {@code MasterTitleScreen.initialize},
 * {@code startFadeFromBlack}) and ROM init ({@code initializeGame}),
 * which require the full singleton/OpenGL stack. The post-fade behavior
 * (GL cleanup, master-title build, reveal fade) is covered by manual
 * launch verification in Task 13.
 *
 * <p>Construction follows the existing {@link com.openggf.TestGameLoop}
 * pattern: full singleton stack via {@link EngineServices#configure} and
 * {@link TestEnvironment#activeGameplayMode()}, plus the
 * {@code GameLoop(InputHandler)} test constructor.
 */
class TestLegalDisclaimerHandoff {

    private GameLoop gameLoop;
    private InputHandler inputHandler;
    private LegalDisclaimerScreen mockScreen;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();
        inputHandler = mock(InputHandler.class);
        gameLoop = new GameLoop(inputHandler);
        mockScreen = mock(LegalDisclaimerScreen.class);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    @Test
    void stepCallsDisclaimerUpdateWhileInLegalDisclaimerMode() {
        gameLoop.setLegalDisclaimerScreenSupplier(() -> mockScreen);
        gameLoop.setLegalDisclaimerExitHandler(() -> { });
        gameLoop.setGameMode(GameMode.LEGAL_DISCLAIMER);
        when(mockScreen.isDismissed()).thenReturn(false);

        gameLoop.step();
        gameLoop.step();
        gameLoop.step();

        verify(mockScreen, times(3)).update(inputHandler);
    }

    @Test
    void exitHandlerFiresAndAdvancesGameMode() {
        AtomicInteger exitCalls = new AtomicInteger();
        // Stand-in for Engine.exitLegalDisclaimer's mode-set responsibility.
        // The real method also cleans up GL and starts a reveal fade; those
        // are exercised by manual launch (see Task 13). Here we verify the
        // contract that matters at the GameLoop layer: the handler must
        // advance the game mode out of LEGAL_DISCLAIMER.
        Runnable exitHandler = () -> {
            exitCalls.incrementAndGet();
            gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
        };
        gameLoop.setLegalDisclaimerScreenSupplier(() -> mockScreen);
        gameLoop.setLegalDisclaimerExitHandler(exitHandler);
        gameLoop.setGameMode(GameMode.LEGAL_DISCLAIMER);

        // Frame 1: not dismissed → mode stays LEGAL_DISCLAIMER.
        when(mockScreen.isDismissed()).thenReturn(false);
        gameLoop.step();
        assertEquals(0, exitCalls.get());
        assertEquals(GameMode.LEGAL_DISCLAIMER, gameLoop.getCurrentGameMode());

        // Frame 2: dismissed → handler fires, mode advances.
        when(mockScreen.isDismissed()).thenReturn(true);
        gameLoop.step();
        assertEquals(1, exitCalls.get());
        assertEquals(GameMode.MASTER_TITLE_SCREEN, gameLoop.getCurrentGameMode());

        // Frame 3: mode is now MASTER_TITLE_SCREEN → disclaimer no longer
        // polled. mockScreen.update was called on frames 1 and 2 only.
        gameLoop.step();
        assertEquals(1, exitCalls.get());
        verify(mockScreen, times(2)).update(inputHandler);
    }

    @Test
    void exitHandlerThatLeavesModeInLegalDisclaimerStillFiresOnlyOnce() {
        // Pathological case: handler is invoked but fails to change mode.
        // The dispatch nulls the handler after first fire, so we should
        // not re-invoke even though isDismissed() keeps returning true.
        AtomicInteger exitCalls = new AtomicInteger();
        gameLoop.setLegalDisclaimerScreenSupplier(() -> mockScreen);
        gameLoop.setLegalDisclaimerExitHandler(exitCalls::incrementAndGet);
        gameLoop.setGameMode(GameMode.LEGAL_DISCLAIMER);
        when(mockScreen.isDismissed()).thenReturn(true);

        gameLoop.step();
        gameLoop.step();
        gameLoop.step();

        assertEquals(1, exitCalls.get(),
                "handler must not fire repeatedly even if mode is stuck");
    }

    @Test
    void otherGameModesDoNotTouchDisclaimer() {
        gameLoop.setLegalDisclaimerScreenSupplier(() -> mockScreen);
        gameLoop.setLegalDisclaimerExitHandler(() -> { });
        gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);

        gameLoop.step();

        verify(mockScreen, never()).update(inputHandler);
        verify(mockScreen, never()).isDismissed();
    }
}
