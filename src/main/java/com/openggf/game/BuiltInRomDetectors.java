package com.openggf.game;

import com.openggf.architecture.CompositionRoot;
import com.openggf.game.sonic1.Sonic1RomDetector;
import com.openggf.game.sonic2.Sonic2RomDetector;
import com.openggf.game.sonic3k.Sonic3kRomDetector;

import java.util.List;

/**
 * Creates the built-in ROM detectors used by the engine.
 */
@CompositionRoot
public final class BuiltInRomDetectors {
    private BuiltInRomDetectors() {
    }

    /**
     * Returns fresh instances of every built-in detector in declared order.
     */
    public static List<RomDetector> all() {
        return List.of(
                new Sonic3kRomDetector(),
                new Sonic1RomDetector(),
                new Sonic2RomDetector());
    }

    /**
     * Returns a fresh built-in detector for the requested game.
     *
     * @param gameId the game whose detector to create
     * @return a fresh detector instance
     */
    public static RomDetector forGame(GameId gameId) {
        return switch (gameId) {
            case S1 -> new Sonic1RomDetector();
            case S2 -> new Sonic2RomDetector();
            case S3K -> new Sonic3kRomDetector();
        };
    }
}
