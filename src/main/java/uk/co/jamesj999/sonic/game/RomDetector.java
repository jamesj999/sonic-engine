package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.data.Rom;

/**
 * Interface for detecting which game ROM is loaded.
 * Implementations provide game-specific detection logic by examining
 * ROM headers, checksums, or other identifying characteristics.
 *
 * <p>Multiple detectors can be registered with {@link RomDetectionService},
 * which will query each in priority order to determine the correct game module.
 */
public interface RomDetector {
    /**
     * Checks if this detector can handle the given ROM.
     * Implementations should examine the ROM's header, checksum,
     * or other identifying information to determine if it matches
     * this detector's game.
     *
     * @param rom the ROM to check
     * @return true if this detector recognizes the ROM
     */
    boolean canHandle(Rom rom);

    /**
     * Returns the priority of this detector.
     * Lower values indicate higher priority (checked first).
     * Use this to ensure more specific detectors run before generic ones.
     *
     * @return the priority value (lower = higher priority)
     */
    int getPriority();

    /**
     * Creates a new GameModule instance for this game.
     * Called after {@link #canHandle(Rom)} returns true.
     *
     * @return a new GameModule for this game
     */
    GameModule createModule();

    /**
     * Returns a human-readable name for this detector's game.
     * Used for logging and debugging purposes.
     *
     * @return the game name (e.g., "Sonic 2", "Sonic 1")
     */
    String getGameName();
}
