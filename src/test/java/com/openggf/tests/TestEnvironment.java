package com.openggf.tests;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.OscillationManager;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.features.HCZWaterSkimHandler;
import com.openggf.game.sonic3k.features.HCZWaterTunnelHandler;
import com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance.HCZBreakableBarState;
import com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate;
import com.openggf.game.GameModule;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.GameServices;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.InitStep;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.StaticFixup;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.physics.GroundSensor;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Centralized test state reset. Called before each annotated test
 * (via the active test fixture)
 * to prevent singleton state from leaking between tests.
 */
public final class TestEnvironment {
    private TestEnvironment() {}

    /**
     * Resets all singleton state to a clean baseline.
     * Order matters: game module first (affects what other singletons do),
     * then subsystems from outer (audio, level) to inner (camera, timers).
     * <p>
     * Replaces the former {@code GameContext.forTesting()} method.
     */
    public static void resetAll() {
        resetToBootstrapBaseline();

        // Reset the static AbstractObjectInstance.cameraBounds so on-screen
        // visibility checks in newly constructed objects start from a known
        // baseline rather than whatever the previous test left behind.
        AbstractObjectInstance.resetCameraBoundsForTests();

        // Ensure a gameplay mode exists after reset so GameServices can
        // resolve session-owned managers.
        activeGameplayMode();
    }

    /**
     * Rebuilds the gameplay mode around the module selected from the target ROM
     * and installs that ROM into the shared {@link RomManager}.
     */
    public static void configureRomFixture(Rom rom) {
        Objects.requireNonNull(rom, "rom");

        resetAll();
        GameModuleRegistry.detectAndSetModule(rom);
        recreateGameplayMode();
        RomManager.getInstance().setRom(rom);
    }

    /**
     * Rebuilds the gameplay mode around an explicitly selected module without
     * requiring a real ROM.
     */
    public static void configureGameModuleFixture(GameModule module) {
        Objects.requireNonNull(module, "module");

        resetAll();
        GameModuleRegistry.setCurrent(module);
        recreateGameplayMode();
    }

    /**
     * Rebuilds the gameplay mode around a requested game module selected by
     * test enum, constructing the module only after engine services are ready.
     */
    public static void configureGameModuleFixture(SonicGame game) {
        Objects.requireNonNull(game, "game");

        resetAll();
        GameModuleRegistry.setCurrent(moduleFor(game));
        recreateGameplayMode();
    }

    private static void resetToBootstrapBaseline() {
        GroundSensor.setLevelManager(null);
        SonicConfigurationService.getInstance().resetToDefaults();
        // OscillationManager is a process-wide static table advanced by every level
        // frame; objects that read it (e.g. CnzHoverFanInstance) assume the reset
        // baseline in unit tests, so it must not leak across classes in a fork.
        OscillationManager.reset();
        // S3K static gameplay handlers advanced by level frames; the classes that
        // own them reset them in their own hooks, but any headless class that runs
        // HCZ frames leaves them set for the next class in the fork.
        HCZWaterTunnelHandler.reset();
        HCZWaterSkimHandler.reset();
        HCZBreakableBarState.reset();
        HCZWaterRushPaletteCycleGate.reset();
        Sonic3kLevelTriggerManager.reset();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

        // CRITICAL: Capture the current game's profile BEFORE resetting the module.
        // After reset(), the module reverts to Sonic2GameModule (the default).
        // We need the PREVIOUS game's teardown to clean up its own state.
        LevelInitProfile profile = GameModuleRegistry.getCurrent().getLevelInitProfile();

        SessionManager.clear();

        // Phase 0: Reset game module (shared across all games)
        GameModuleRegistry.reset();

        // Execute game-specific teardown steps
        for (InitStep step : profile.levelTeardownSteps()) {
            step.execute();
        }

        // Apply static fixups
        for (StaticFixup fixup : profile.postTeardownFixups()) {
            fixup.apply();
        }
    }

    private static void recreateGameplayMode() {
        SessionManager.clear();
        activeGameplayMode();
    }

    /**
     * Resets per-test state without touching the loaded level data or game module.
     */
    public static void resetPerTest() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        // Ensure a gameplay mode exists so GameServices can resolve managers.
        // The first test in a JVM fork may not have run resetAll() yet.
        activeGameplayMode();
        LevelInitProfile profile = GameModuleRegistry.getCurrent().getLevelInitProfile();
        for (InitStep step : profile.perTestResetSteps()) {
            step.execute();
        }
    }

    /**
     * Returns the active session-owned gameplay context, opening a gameplay
     * session and attaching managers when a legacy test path has not done so yet.
     */
    public static GameplayModeContext activeGameplayMode() {
        GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode == null) {
            gameplayMode = SessionManager.openGameplaySession(GameModuleRegistry.getCurrent());
        }
        if (gameplayMode.getCamera() == null) {
            GameplaySessionFactory.attachManagers(gameplayMode, EngineServices.current());
        }
        return gameplayMode;
    }

    public static DefaultObjectServices objectServices() {
        return new DefaultObjectServices(activeGameplayMode(), EngineServices.current());
    }

    public static Rom currentRom() {
        try {
            return GameServices.rom().getRom();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read active test ROM", e);
        }
    }

    private static GameModule moduleFor(SonicGame game) {
        return switch (game) {
            case SONIC_1 -> new Sonic1GameModule();
            case SONIC_2 -> new Sonic2GameModule();
            case SONIC_3K -> new Sonic3kGameModule();
        };
    }
}


