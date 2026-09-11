package com.openggf;

import com.openggf.audio.AudioManager;
import com.openggf.camera.Camera;
import com.openggf.game.GameOverExit;
import com.openggf.game.ContinueScreenProvider;
import com.openggf.game.GameStateManager;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.graphics.FadeManager;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.logging.Logger;

/**
 * The GAME OVER card's exit from the level, extracted from {@link GameLoop};
 * {@link #exitToTitleScreen} is the black-screen-to-title step the ending's
 * exit shares.
 *
 * <p>The card's game-mode write has ended the level main loop; the mode that
 * follows opens by stopping the music and fading the palette out (S1
 * {@code GM_Sega} docs/s1disasm/sonic.asm:1808-1813, S2 {@code SegaScreen}
 * docs/s2disasm/s2.asm:4095-4099; S1 {@code GM_Continue} fades first as well,
 * docs/s1disasm/sonic.asm:3449-3450).
 *
 */
final class GameLoopGameOverExit {
    private static final Logger LOGGER = Logger.getLogger(GameLoopGameOverExit.class.getName());

    private GameLoopGameOverExit() {
    }

    static void startToBlack(GameOverExit exit, LevelManager levelManager, AudioManager audioManager,
            FadeManager fadeManager, GameplayModeContext context, Runnable onBlack) {
        LOGGER.info("Game over card dismissed toward " + exit);
        levelManager.setLevelInactiveForTransition(true);
        audioManager.stopMusic();
        GameLoopPlcLifecycle.startToBlack(context, fadeManager, onBlack);
    }

    /** Apply the ROM Continue exit before the existing level restart/load boundary. */
    static boolean acceptContinue(ContinueScreenProvider provider, GameStateManager state,
                                  LevelManager levelManager, Runnable saveLivesContinues) {
        if (provider == null || !provider.isAccepted() || !state.consumeContinue()) return false;
        if (provider.clearsCheckpointOnContinue()) levelManager.getCheckpointState().clear();
        levelManager.getLevelGamestate().setRings(0);
        levelManager.getLevelGamestate().setTimerFrames(0);
        if (provider.savesOnContinue()) saveLivesContinues.run();
        return true;
    }

    /** The ending's own return: clean up demo state, fade the music, then black. */
    static void startEndingReturn(SpriteManager spriteManager, LevelManager levelManager, String mainCode,
            AudioManager audioManager, FadeManager fadeManager, GameplayModeContext context, Runnable onBlack) {
        spriteManager.setInputSuppressed(false);
        levelManager.setForceHudSuppressed(false);
        if (spriteManager.getSprite(mainCode) instanceof AbstractPlayableSprite player) {
            player.setControlLocked(false);
            player.clearForcedInputMask();
        }
        audioManager.fadeOutMusic();
        if (!fadeManager.isActive()) {
            GameLoopPlcLifecycle.startToBlack(context, fadeManager, onBlack);
        } else {
            onBlack.run();
        }
    }

    static void exitToTitleScreen(SpriteManager spriteManager, LevelManager levelManager, Camera camera,
            Runnable enterTitleScreenMode, TitleScreenProvider titleScreen, FadeManager fadeManager,
            GameplayModeContext context) {
        spriteManager.setInputSuppressed(false);
        levelManager.setForceHudSuppressed(false);
        levelManager.setLevelInactiveForTransition(false);

        camera.setX((short) 0);
        camera.setY((short) 0);

        enterTitleScreenMode.run();
        if (titleScreen != null) {
            titleScreen.initialize();
        }

        GameLoopPlcLifecycle.startFromBlack(context, fadeManager, null);
    }
}
