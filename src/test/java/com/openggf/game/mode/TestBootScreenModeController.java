package com.openggf.game.mode;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.LegalDisclaimerScreen;
import com.openggf.game.MasterTitleScreen;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BootScreenModeController}, the extracted owner of the
 * legal-disclaimer / master-title per-frame update dispatch. These assert the
 * frame contract the game loop previously inlined: update the screen, fire the
 * transition hook on the right condition, and always advance the input handler.
 */
class TestBootScreenModeController {

    private final BootScreenModeController controller = new BootScreenModeController();

    @Test
    void handlesOnlyBootScreenModes() {
        assertTrue(controller.handles(GameMode.LEGAL_DISCLAIMER));
        assertTrue(controller.handles(GameMode.MASTER_TITLE_SCREEN));
        assertFalse(controller.handles(GameMode.LEVEL));
        assertFalse(controller.handles(GameMode.TITLE_SCREEN));
    }

    @Test
    void legalDisclaimerUpdatesScreenAndAdvancesInput() {
        LegalDisclaimerScreen disclaimer = mock(LegalDisclaimerScreen.class);
        when(disclaimer.isDismissed()).thenReturn(false);
        InputHandler input = mock(InputHandler.class);
        AtomicReference<Boolean> dismissed = new AtomicReference<>(false);

        controller.updateLegalDisclaimer(disclaimer, input, () -> dismissed.set(true));

        verify(disclaimer).update(input);
        verify(input).update();
        assertFalse(dismissed.get(), "dismiss hook must not fire while screen is not dismissed");
    }

    @Test
    void legalDisclaimerFiresDismissHookWhenDismissed() {
        LegalDisclaimerScreen disclaimer = mock(LegalDisclaimerScreen.class);
        when(disclaimer.isDismissed()).thenReturn(true);
        InputHandler input = mock(InputHandler.class);
        AtomicReference<Boolean> dismissed = new AtomicReference<>(false);

        controller.updateLegalDisclaimer(disclaimer, input, () -> dismissed.set(true));

        assertTrue(dismissed.get(), "dismiss hook must fire once the screen reports dismissal");
        verify(input).update();
    }

    @Test
    void legalDisclaimerStillAdvancesInputWhenNoScreenWired() {
        InputHandler input = mock(InputHandler.class);

        controller.updateLegalDisclaimer(null, input, () -> {
            throw new AssertionError("dismiss hook must not fire when no screen is wired");
        });

        verify(input).update();
    }

    @Test
    void masterTitleUpdatesScreenAndAdvancesInputWithoutSelection() {
        MasterTitleScreen screen = mock(MasterTitleScreen.class);
        when(screen.isGameSelected()).thenReturn(false);
        InputHandler input = mock(InputHandler.class);
        AtomicReference<MasterTitleScreen> selected = new AtomicReference<>();

        controller.updateMasterTitle(screen, input, selected::set);

        verify(screen).update(input);
        verify(input).update();
        assertNull(selected.get(), "selection hook must not fire without a game selection");
    }

    @Test
    void masterTitleFiresSelectionHookWithSelectedScreen() {
        MasterTitleScreen screen = mock(MasterTitleScreen.class);
        when(screen.isGameSelected()).thenReturn(true);
        InputHandler input = mock(InputHandler.class);
        AtomicReference<MasterTitleScreen> selected = new AtomicReference<>();

        controller.updateMasterTitle(screen, input, selected::set);

        assertSame(screen, selected.get(), "selection hook must receive the selected screen");
        verify(input).update();
    }

    @Test
    void masterTitleStillAdvancesInputWhenNoScreenWired() {
        InputHandler input = mock(InputHandler.class);

        controller.updateMasterTitle(null, input, s -> {
            throw new AssertionError("selection hook must not fire when no screen is wired");
        });

        verify(input).update();
        verify(input, never()).isKeyDown(0);
    }
}
