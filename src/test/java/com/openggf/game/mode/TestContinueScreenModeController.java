package com.openggf.game.mode;

import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.game.ContinueScreenProvider;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class TestContinueScreenModeController {
    private final MenuScreenModeController controller = new MenuScreenModeController();
    private final ContinueScreenProvider provider = mock(ContinueScreenProvider.class);
    private final InputHandler input = mock(InputHandler.class);
    private final Runnable exit = mock(Runnable.class);

    @Test void fadesConsumeEdgesWithoutAcceptingOrAdvancingCountdown() {
        controller.updateContinueScreen(provider, input, true, exit);
        verify(provider).advanceFadeFrame();
        verify(provider, never()).update(anyBoolean(), anyBoolean());
        verify(exit, never()).run();
        verify(input).update();
    }

    @Test void finishedScreenDispatchesExitAfterItsLastUpdate() {
        when(input.logical()).thenReturn(LogicalInputSnapshot.neutral());
        when(provider.isFinished()).thenReturn(true);
        controller.updateContinueScreen(provider, input, false, exit);
        var order = inOrder(provider, exit, input);
        order.verify(provider).update(false, false);
        order.verify(provider).isFinished();
        order.verify(exit).run();
        order.verify(input).update();
    }

    @Test void actionButtonIsNotStart() {
        PlayerInputState player = new PlayerInputState(0, 0, 1, 1, false, false);
        when(input.logical()).thenReturn(LogicalInputSnapshot.ofPlayers(player, player));
        controller.updateContinueScreen(provider, input, false, exit);
        verify(provider).update(false, false);
        verify(exit, never()).run();
    }
}
