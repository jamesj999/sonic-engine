package com.openggf.game;

import com.openggf.control.InputActionMasks;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.graphics.FadeManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestLegalDisclaimerControllerInput {

    @Test
    void logicalActionDismissesAfterReadabilityGate() throws Exception {
        FadeManager fadeManager = mock(FadeManager.class);
        LegalDisclaimerScreen screen = new LegalDisclaimerScreen(fadeManager);
        advanceToDismissible(screen);

        InputHandler input = new InputHandler();
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, 0, InputActionMasks.ACTION_A, false, false),
                PlayerInputState.neutral()));

        screen.update(input);

        verify(fadeManager).startFadeToBlack(any(Runnable.class));
    }

    @Test
    void logicalPlayerOneStartDismissesAfterReadabilityGate() throws Exception {
        FadeManager fadeManager = mock(FadeManager.class);
        LegalDisclaimerScreen screen = new LegalDisclaimerScreen(fadeManager);
        advanceToDismissible(screen);

        InputHandler input = new InputHandler();
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, 0, 0, false, true),
                PlayerInputState.neutral()));

        screen.update(input);

        verify(fadeManager).startFadeToBlack(any(Runnable.class));
    }

    @Test
    void logicalSecondPlayerStartDoesNotDismissAfterReadabilityGate() throws Exception {
        FadeManager fadeManager = mock(FadeManager.class);
        LegalDisclaimerScreen screen = new LegalDisclaimerScreen(fadeManager);
        advanceToDismissible(screen);

        InputHandler input = new InputHandler();
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.neutral(),
                PlayerInputState.of(0, 0, 0, 0, false, true)));

        screen.update(input);

        verify(fadeManager, never()).startFadeToBlack(any(Runnable.class));
    }

    private static void advanceToDismissible(LegalDisclaimerScreen screen) throws Exception {
        LegalDisclaimerState state = stateFrom(screen);
        state.onFadeInComplete();
        InputHandler neutral = new InputHandler();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            screen.update(neutral);
        }
    }

    private static LegalDisclaimerState stateFrom(LegalDisclaimerScreen screen) throws Exception {
        Field field = LegalDisclaimerScreen.class.getDeclaredField("state");
        field.setAccessible(true);
        return (LegalDisclaimerState) field.get(screen);
    }
}
