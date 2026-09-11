package com.openggf.game.mode;

import com.openggf.control.InputHandler;
import com.openggf.game.DataSelectProvider;
import com.openggf.game.LevelSelectProvider;
import com.openggf.game.TitleScreenProvider;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMenuScreenModeController {

    private final MenuScreenModeController controller = new MenuScreenModeController();
    private final InputHandler inputHandler = mock(InputHandler.class);

    @Test
    void titleScreenUpdateRunsProviderThenExitHookThenInputAdvance() {
        TitleScreenProvider titleScreen = mock(TitleScreenProvider.class);
        Runnable onExit = mock(Runnable.class);
        when(titleScreen.isExiting()).thenReturn(true);

        controller.updateTitleScreen(titleScreen, inputHandler, onExit);

        verify(titleScreen).update(inputHandler);
        verify(onExit).run();
        verify(inputHandler).update();
    }

    @Test
    void titleScreenUpdateDoesNotExitWhenProviderIsStillActive() {
        TitleScreenProvider titleScreen = mock(TitleScreenProvider.class);
        Runnable onExit = mock(Runnable.class);

        controller.updateTitleScreen(titleScreen, inputHandler, onExit);

        verify(titleScreen).update(inputHandler);
        verify(onExit, never()).run();
        verify(inputHandler).update();
    }

    @Test
    void levelSelectUpdateRunsExitHookWhenSelectionIsReady() {
        LevelSelectProvider levelSelect = mock(LevelSelectProvider.class);
        Runnable onExit = mock(Runnable.class);
        when(levelSelect.isExiting()).thenReturn(true);

        controller.updateLevelSelect(levelSelect, inputHandler, onExit);

        verify(levelSelect).update(inputHandler);
        verify(onExit).run();
        verify(inputHandler).update();
    }

    @Test
    void dataSelectUpdateRunsExitHookWhenSelectionIsReady() {
        DataSelectProvider dataSelect = mock(DataSelectProvider.class);
        Runnable onExit = mock(Runnable.class);
        when(dataSelect.isExiting()).thenReturn(true);

        controller.updateDataSelect(dataSelect, inputHandler, onExit);

        verify(dataSelect).update(inputHandler);
        verify(onExit).run();
        verify(inputHandler).update();
    }

    @Test
    void nullProviderStillAdvancesInput() {
        Runnable onExit = mock(Runnable.class);

        controller.updateDataSelect(null, inputHandler, onExit);

        verify(onExit, never()).run();
        verify(inputHandler).update();
    }
}
