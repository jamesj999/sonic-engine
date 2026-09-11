package com.openggf.game.mode;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.LegalDisclaimerScreen;
import com.openggf.game.MasterTitleScreen;

import java.util.function.Consumer;

/**
 * Owns the per-frame update dispatch for the two boot screens that run before
 * any ROM/gameplay systems are loaded: {@link GameMode#LEGAL_DISCLAIMER} and
 * {@link GameMode#MASTER_TITLE_SCREEN}.
 *
 * <p>These modes are positionally isolated at the very top of the game loop:
 * they run before pause handling, before the editor toggle, and before the
 * profiler input section is opened, because their keys double as
 * confirm/dismiss inputs. Extracting them keeps the loop's mode dispatcher
 * from owning screen-specific update sequencing.
 *
 * <p>The controller is intentionally narrow: it never reaches into game-loop
 * state. Callers resolve the active screen instance and pass the side-effecting
 * transition callbacks (dismiss / game-selected) as functional hooks, so the
 * frame ordering and the {@code inputHandler.update()} contract are owned here
 * while the transition policy stays with the loop.
 */
public final class BootScreenModeController {

    /**
     * Returns {@code true} when {@code mode} is a boot screen this controller
     * is responsible for handling.
     */
    public boolean handles(GameMode mode) {
        return mode == GameMode.LEGAL_DISCLAIMER || mode == GameMode.MASTER_TITLE_SCREEN;
    }

    /**
     * Runs one frame of the legal-disclaimer screen.
     *
     * <p>Mirrors the original loop body: the screen is updated, and when it has
     * been dismissed the {@code onDismissed} hook fires once. {@code inputHandler}
     * is always advanced so the next frame observes fresh key edges.
     *
     * @param disclaimer the active screen instance, or {@code null} if none is wired
     * @param inputHandler frame input handler (advanced before return)
     * @param onDismissed fired once when the disclaimer reports dismissal; may be {@code null}
     */
    public void updateLegalDisclaimer(LegalDisclaimerScreen disclaimer,
                                      InputHandler inputHandler,
                                      Runnable onDismissed) {
        if (disclaimer != null) {
            disclaimer.update(inputHandler);
            if (disclaimer.isDismissed() && onDismissed != null) {
                onDismissed.run();
            }
        }
        inputHandler.update();
    }

    /**
     * Runs one frame of the master title screen.
     *
     * <p>Mirrors the original loop body: the screen is updated, and when a game
     * has been selected the {@code onGameSelected} hook fires with the screen so
     * the caller can begin the bootstrap fade/exit. {@code inputHandler} is
     * always advanced before return.
     *
     * @param masterScreen the active screen instance, or {@code null} if none is wired
     * @param inputHandler frame input handler (advanced before return)
     * @param onGameSelected fired when the screen reports a game selection
     */
    public void updateMasterTitle(MasterTitleScreen masterScreen,
                                  InputHandler inputHandler,
                                  Consumer<MasterTitleScreen> onGameSelected) {
        if (masterScreen != null) {
            masterScreen.update(inputHandler);
            if (masterScreen.isGameSelected()) {
                onGameSelected.accept(masterScreen);
            }
        }
        inputHandler.update();
    }
}
