package com.openggf.game.mode;

import com.openggf.control.InputHandler;
import com.openggf.game.DataSelectProvider;
import com.openggf.game.ContinueScreenProvider;
import com.openggf.game.GameMode;
import com.openggf.game.LevelSelectProvider;
import com.openggf.game.TitleScreenProvider;

/**
 * Owns per-frame update sequencing for menu-like game screens that run outside
 * normal gameplay but after ROM/game systems are available.
 */
public final class MenuScreenModeController {

    public boolean handles(GameMode mode) {
        return mode == GameMode.CONTINUE_SCREEN
                || mode == GameMode.TITLE_SCREEN
                || mode == GameMode.LEVEL_SELECT
                || mode == GameMode.DATA_SELECT;
    }

    /** Consume input during fades without advancing the ROM object loop. */
    public void updateContinueScreen(ContinueScreenProvider provider, InputHandler input,
                                     boolean fading, Runnable onExit) {
        if (provider != null && fading) provider.advanceFadeFrame();
        if (provider != null && !fading) {
            provider.update(input.logical().player1().startPressed(),
                    input.logical().player2().startPressed());
            if (provider.isFinished()) onExit.run();
        }
        input.update();
    }

    public void updateTitleScreen(TitleScreenProvider titleScreen,
                                  InputHandler inputHandler,
                                  Runnable onExit) {
        if (titleScreen != null) {
            titleScreen.update(inputHandler);
            if (titleScreen.isExiting() && onExit != null) {
                onExit.run();
            }
        }
        inputHandler.update();
    }

    public void updateLevelSelect(LevelSelectProvider levelSelect,
                                  InputHandler inputHandler,
                                  Runnable onExit) {
        if (levelSelect != null) {
            levelSelect.update(inputHandler);
            if (levelSelect.isExiting() && onExit != null) {
                onExit.run();
            }
        }
        inputHandler.update();
    }

    public void updateDataSelect(DataSelectProvider dataSelect,
                                 InputHandler inputHandler,
                                 Runnable onExit) {
        if (dataSelect != null) {
            dataSelect.update(inputHandler);
            if (dataSelect.isExiting() && onExit != null) {
                onExit.run();
            }
        }
        inputHandler.update();
    }
}
