package com.openggf;

import com.openggf.game.ContinueScreenProvider;
import com.openggf.game.GameMode;
import com.openggf.game.GameOverExit;
import com.openggf.game.mode.MenuScreenModeController;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator.PlcLifecycleFrame;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.save.SaveReason;

/** Owns the Continue mode's provider, fade transitions and return to the level. */
final class GameLoopContinueCoordinator {
    private final GameLoop loop;
    private final GameLoopContinueClock clock = new GameLoopContinueClock();
    private final MenuScreenModeController menu = new MenuScreenModeController();
    private ContinueScreenProvider provider;

    GameLoopContinueCoordinator(GameLoop loop) {
        this.loop = loop;
    }

    void beginIteration() {
        clock.beginIteration();
    }

    void updateFade() {
        // Continue has no gameplay body while paused; its blocking fade and
        // V-int clock must stop together when the window loses focus.
        if (loop.getCurrentGameMode() != GameMode.CONTINUE_SCREEN || !loop.isPaused()) {
            loop.resolveFadeManager().update();
        }
    }

    void enterAfterGameOverFade(GameOverExit exit) {
        if (exit != GameOverExit.CONTINUE_SCREEN || loop.gameState.getContinues() <= 0) {
            returnToTitleScreen();
            return;
        }
        reset();
        provider = loop.levelManager.getGameModule().createContinueScreenProvider();
        if (provider == null) {
            returnToTitleScreen();
            return;
        }
        loop.spriteManager.setInputSuppressed(false);
        loop.setGameMode(GameMode.CONTINUE_SCREEN);
        int vint = loop.levelManager.getObjectManager() != null
                ? loop.levelManager.getObjectManager().getVblaCounter() : 0;
        clock.enterAfterFade(provider, loop.gameState.getContinues(), vint, this::publishClock);
        GameLoopPlcLifecycle.startFromBlack(loop.resolveGameplayModeContext(), loop.fadeManager, null);
    }

    void update(PlcLifecycleFrame frame) {
        var input = loop.getInputHandler();
        clock.update(provider, () -> menu.updateContinueScreen(provider, input,
                        loop.fadeManager.isActive() || frame.isOwnedBy(PlcLifecyclePhase.PALETTE_FADE),
                        () -> GameLoopPlcLifecycle.startToBlack(
                                loop.resolveGameplayModeContext(), loop.fadeManager, this::finish)),
                input::update, this::publishClock);
    }

    private void finish() {
        clock.finishFade(provider, this::publishClock);
        if (!GameLoopGameOverExit.acceptContinue(provider, loop.gameState, loop.levelManager,
                () -> loop.requestSaveForCurrentSession(SaveReason.LIVES_CONTINUES_SAVE))) {
            reset();
            returnToTitleScreen();
            return;
        }
        reset();
        loop.levelManager.setLevelInactiveForTransition(false);
        loop.levelManager.setForceHudSuppressed(false);
        loop.spriteManager.setInputSuppressed(false);
        loop.setGameMode(GameMode.LEVEL);
        respawn();
    }

    /** Shared GAME OVER/ending return after reaching black. */
    void returnToTitleScreen() {
        GameLoopGameOverExit.exitToTitleScreen(loop.spriteManager, loop.levelManager, loop.camera,
                () -> loop.setGameMode(GameMode.TITLE_SCREEN), loop.getTitleScreenProviderLazy(),
                loop.fadeManager, loop.resolveGameplayModeContext());
    }

    /** Continue and ordinary death share the existing full restart/title-card load boundary. */
    void respawn() {
        TraceSessionLauncher.runDeathRestartLoad(loop.levelManager);
        loop.activateScheduledPlaybackForLoadedLevel();
        GameLoopPlcLifecycle.startFromBlack(loop.resolveGameplayModeContext(), loop.fadeManager, null);
        java.util.logging.Logger.getLogger(GameLoop.class.getName())
                .info("Respawned player, entering title card");
    }

    private void publishClock(int vintRunCount) {
        if (loop.levelManager.getObjectManager() != null) {
            loop.levelManager.getObjectManager().initVblaCounter(vintRunCount);
        }
    }

    void reset() {
        if (provider != null) provider.reset();
        provider = null;
    }

    ContinueScreenProvider provider() {
        return provider;
    }
}
