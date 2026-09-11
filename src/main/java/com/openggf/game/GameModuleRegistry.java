package com.openggf.game;

import com.openggf.architecture.CompositionRoot;
import com.openggf.data.Rom;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Bootstrap compatibility registry for game-module access before a world
 * session exists.
 *
 * <p>The game module defines game-specific behavior including:
 * <ul>
 *   <li>Object registry and IDs</li>
 *   <li>Audio profile and sound mappings</li>
 *   <li>Zone registry and level data</li>
 *   <li>Special stage and bonus stage providers</li>
 *   <li>Scroll handlers and zone features</li>
 * </ul>
 *
 * <p>Live gameplay module ownership belongs to {@link SessionManager} /
 * {@code WorldSession}. {@link #getCurrent()} resolves from that session when
 * one exists and falls back to the bootstrap default only for pre-session
 * construction paths.
 */
@CompositionRoot
public final class GameModuleRegistry {
    private static final Logger LOGGER = Logger.getLogger(GameModuleRegistry.class.getName());

    // Default to Sonic 2 for backward compatibility during bootstrap before a world session exists.
    private static GameModule bootstrapDefault = new Sonic2GameModule();

    private GameModuleRegistry() {
    }

    /**
     * Gets the session-owned active module when gameplay has a world session,
     * otherwise returns the bootstrap default.
     *
     * @return the session module or pre-session bootstrap default
     */
    public static synchronized GameModule getCurrent() {
        if (SessionManager.getCurrentWorldSession() != null) {
            return SessionManager.requireCurrentGameModule();
        }
        return bootstrapDefault;
    }

    /**
     * Returns the bootstrap default module without consulting session state.
     * Use this only for construction paths that intentionally run before a
     * gameplay world session or runtime exists.
     */
    public static synchronized GameModule getBootstrapDefault() {
        return bootstrapDefault;
    }

    /**
     * Sets the bootstrap default game module used before a world session exists.
     * Do not call this from gameplay runtime setup; open a world session with
     * the selected {@link GameModule} instead.
     *
     * @param module the module to set as current (ignored if null)
     */
    public static synchronized void setCurrent(GameModule module) {
        if (module != null) {
            LOGGER.info("Setting bootstrap game module: " + module.getIdentifier());
            bootstrapDefault = module;
        }
    }

    /**
     * Auto-detects the game from the ROM and updates the bootstrap default.
     * Falls back to Sonic 2 if detection fails. This exists for explicit
     * pre-session compatibility paths only.
     *
     * @param rom the ROM to detect
     * @return true if detection succeeded, false if using fallback
     */
    public static boolean detectAndSetModule(Rom rom) {
        return applyDetectedModule(GameServices.romDetection().detectAndCreateModule(rom));
    }

    /**
     * Applies a completed ROM detection result to the bootstrap module default.
     * This registry owns the bootstrap mutation and Sonic 2 fallback behavior.
     *
     * @param detectedModule the already-detected module, if any
     * @return true if detection succeeded, false if using fallback
     */
    static boolean applyDetectedModule(Optional<GameModule> detectedModule) {
        if (detectedModule.isPresent()) {
            setCurrent(detectedModule.get());
            return true;
        }

        LOGGER.warning("ROM detection failed, using default Sonic 2 module");
        setCurrent(new Sonic2GameModule());
        return false;
    }

    /**
     * Resets the registry to the default Sonic 2 module.
     * Useful for testing or reinitialization.
     */
    public static void reset() {
        setCurrent(new Sonic2GameModule());
        LOGGER.fine("Game module registry reset to Sonic 2 default");
    }
}
